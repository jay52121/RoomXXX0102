package com.example.roomxxx0102.logic.roomalgorithm.gate

import java.io.DataInputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Frozen, scene-calibrated classifier. There are NO labels, gate names, track
 * numbers, or absolute video times in its feature vector. */
internal class PortalTemporalModel private constructor(
    private val base: FloatArray, private val trees: List<Tree>
) {
    private data class Tree(val target: Int, val feature: IntArray,
        val left: IntArray, val right: IntArray, val missingLeft: BooleanArray,
        val value: FloatArray)
    fun probabilities(x: FloatArray): DoubleArray {
        require(x.size == 265)
        val margin = base.copyOf()
        for (t in trees) {
            var n = 0
            while (t.left[n] >= 0) {
                val v = x[t.feature[n]]
                n = if (if (v.isNaN()) t.missingLeft[n] else v < t.value[n]) t.left[n] else t.right[n]
            }
            margin[t.target] += t.value[n]
        }
        val hi = margin.maxOrNull()!!.toDouble()
        val result = DoubleArray(3) { exp(margin[it].toDouble() - hi) }
        val total = result.sum()
        return DoubleArray(3) { result[it] / total }
    }
    companion object {
        const val ASSET = "portal_temporal_v6.bin.gz"
        const val FEATURE_VERSION = 1
        fun readBundle(stream: InputStream): Pair<PortalTemporalModel, PortalTemporalModel> {
            DataInputStream(GZIPInputStream(stream)).use { input ->
                val magic=ByteArray(8);input.readFully(magic)
                require(String(magic, Charsets.US_ASCII)=="SISPTW01")
                fun model(): PortalTemporalModel {
                    val size=input.readInt();require(size in 1..1048576)
                    val bytes=ByteArray(size);input.readFully(bytes)
                    return readRaw(ByteArrayInputStream(bytes))
                }
                val fast=model();val sustained=model()
                require(input.read()==-1)
                return fast to sustained
            }
        }
        private fun readRaw(stream: InputStream): PortalTemporalModel {
            DataInputStream(stream).use { input ->
                val magic = ByteArray(8); input.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == "SISPV601") { "Invalid temporal model header" }
                require(input.readInt() == 265) { "Unsupported feature contract" }
                val count = input.readInt(); require(count in 1..2048)
                val base = FloatArray(3) { input.readFloat() }
                require(base.all { it.isFinite() })
                val trees = ArrayList<Tree>()
                repeat(count) {
                    val target = input.readUnsignedByte(); val size = input.readUnsignedShort()
                    require(target in 0..2 && size in 1..1024)
                    val feature = IntArray(size); val left = IntArray(size); val right = IntArray(size)
                    val missing = BooleanArray(size); val value = FloatArray(size)
                    repeat(size) { n ->
                        feature[n] = input.readShort().toInt(); left[n] = input.readShort().toInt()
                        right[n] = input.readShort().toInt(); missing[n] = input.readUnsignedByte() != 0
                        value[n] = input.readFloat()
                        require(value[n].isFinite())
                        require(left[n] == -1 && right[n] == -1 ||
                            left[n] in n+1 until size && right[n] in n+1 until size && feature[n] in 0..264)
                    }
                    trees += Tree(target, feature, left, right, missing, value)
                }
                require(input.read() == -1) { "Unexpected model tail" }
                return PortalTemporalModel(base, trees)
            }
        }
    }
}

/** Upper-body/foreground sequence inference on causal millisecond windows.
 * No ankle, knee, box-bottom crossing, ground-truth or prior ledger state is
 * used to classify. All output timestamps are the current input frame. */
internal class PortalTemporalCore(
    private val model: PortalTemporalModel,
    private val sustainedModel: PortalTemporalModel,
    private val doors: List<Door>,
    private val living: String,
    initialCounts: Map<String, Int> = emptyMap(),
) {
    data class Point(val x: Double, val y: Double)
    data class Joint(val point: Point, val confidence: Double)
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val center get() = Point((left + right) / 2, (top + bottom) / 2)
    }
    data class Person(val id: Int, val box: Box, val joints: List<Joint>,
                      val score: Double, val shielded: Boolean = false)
    data class Mask(val pixels: Int, val p20: Double?, val p50: Double?, val p80: Double?,
                    val grid: List<Int>)
    data class Portal(val gate: String, val owner: Int?, val phase: String,
                      val absorption: Double?, val poseInside: Double?, val bodyMotion: Double?,
                      val owned: Mask?, val motion: Mask?)
    data class Door(val id: String, val room: String, val a: Point, val b: Point,
                    val aperture: List<Point>, val sign: Double, val aspect: Double) {
        private val dx get() = (b.x - a.x) * aspect
        private val dy get() = b.y - a.y
        val length = hypot((b.x - a.x) * aspect, b.y - a.y).coerceAtLeast(1e-8)
        fun side(p: Point) = sign * (dx * (p.y-a.y) - dy * (p.x-a.x) * aspect) / length
        fun along(p: Point) = ((p.x-a.x)*aspect*dx + (p.y-a.y)*dy)/(length*length)
        val depth = max(.004, aperture.maxOfOrNull { -side(it) } ?: .004)
        val rect = Box(aperture.minOfOrNull { it.x } ?: 0.0, aperture.minOfOrNull { it.y } ?: 0.0,
            aperture.maxOfOrNull { it.x } ?: 0.0, aperture.maxOfOrNull { it.y } ?: 0.0)
    }
    data class Event(val timeMs: Long, val gateId: String, val roomId: String,
                     val owner: Int, val direction: String, val confidence: Double)
    data class Packet(val gate: String, val room: String, val owner: Int,
                      val features: FloatArray, val probabilities: DoubleArray, val sustained: DoubleArray)
    private data class Sample(val time: Long, val values: FloatArray)
    private data class Previous(val time: Long, val owner: Int, val joints: Map<Int, Point>)
    private class History {
        val samples = ArrayDeque<Sample>()
        var seen: Long? = null
        var previous: Previous? = null
        var emitted: Long? = null
        var candidateAt: Long? = null
        var candidateLast: Long? = null
        var candidateDir = 0
    }
    private val histories = linkedMapOf<String, History>()
    private val population = initialCounts.toMutableMap().withDefault { 0 }
    private val owners = linkedMapOf<Int, String>()
    private var previousTime: Long? = null
    var packets: List<Packet> = emptyList(); private set
    val counts: Map<String, Int> get() = population.toMap()
    val ownedRooms: Map<Int, String> get() = owners.toMap()
    val pendingPortalCount get() = histories.size
    fun detachIdentitySource() { histories.clear(); owners.clear() }

    fun step(time: Long, detections: List<Person>, portals: List<Portal>,
             width: Int, height: Int, healthy: Boolean = true): List<Event> {
        packets = emptyList()
        if (previousTime?.let { time <= it } == true) return emptyList()
        previousTime = time
        if (!healthy || width <= 0 || height <= 0) return emptyList()
        val byGate = portals.associateBy { it.gate }
        val input = mutableListOf<Packet>()
        val outputs = mutableListOf<Event>()
        for (door in doors) {
            if (door.aperture.size < 3 || door.room == living) continue
            val h = histories.getOrPut(door.id) { History() }
            val p = byGate[door.id]
            var person = if (p?.owner != null) detections.firstOrNull { it.id == p.owner } else null
            if (person == null) person = detections.filter {
                it.score >= .25 && !it.shielded && overlap(it.box, door.rect) >= .02
            }.maxByOrNull { overlap(it.box, door.rect) }
            if (person != null) h.seen = time
            if (person == null && p == null && (h.seen == null || time - h.seen!! > 1680)) continue
            val joints = validJoints(person)
            val raw = raw(door, person, p, h, joints, time, width, height)
            h.samples.addLast(Sample(time, raw))
            while (h.samples.isNotEmpty() && time-h.samples.first.time >= 1680) h.samples.removeFirst()
            if (person != null) h.previous = Previous(time, person.id, joints)
            val x = features(h, time)
            val prob = model.probabilities(x)
            val sustained = sustainedModel.probabilities(x)
            val owner = person?.id ?: p?.owner ?: -1
            input += Packet(door.id, door.room, owner, x, prob, sustained)
            if (h.emitted?.let { time-it < 1000 } == true) {
                h.candidateAt=null;h.candidateDir=0
                continue
            }
            val fastDir = if (prob[1] >= prob[2]) 1 else 2
            val slowDir = if (sustained[1] >= sustained[2]) 1 else 2
            if(sustained[slowDir]>=.55) {
                if(h.candidateAt==null || h.candidateDir!=slowDir ||
                    h.candidateLast?.let { time-it>300 }==true) {
                    h.candidateAt=time;h.candidateDir=slowDir
                }
                h.candidateLast=time
            } else { h.candidateAt=null;h.candidateDir=0 }
            // Short event evidence targets the first 500ms. Longer-lived
            // completion evidence needs a NEW observation at least 80ms later.
            // Neither head knows the ground-truth time during inference.
            val direction = if(prob[fastDir]>=.55) fastDir else
                if(h.candidateAt?.let { time-it>=80 }==true) slowDir else 0
            if(direction!=0) {
                outputs += Event(time, door.id, door.room, owner,
                    if (direction == 1) "ENTER" else "EXIT", max(prob[direction],sustained[direction]))
                h.emitted=time;h.candidateAt=null;h.candidateDir=0
            }
        }
        packets = input
        for (e in outputs) {
            if (e.direction == "ENTER") {
                population[e.roomId] = population.getValue(e.roomId) + 1
                population[living] = max(0, population.getValue(living)-1)
                if (e.owner >= 0) owners[e.owner] = e.roomId
            } else {
                population[e.roomId] = max(0, population.getValue(e.roomId)-1)
                population[living] = population.getValue(living)+1
                if (e.owner >= 0) owners[e.owner] = living
            }
        }
        return outputs
    }
    private fun features(h: History, time: Long): FloatArray {
        val out = FloatArray(265) { Float.NaN }
        for ((bi, win) in WINDOWS.withIndex()) {
            val selected = h.samples.filter { time-it.time >= win.first && time-it.time < win.second }
            val off = bi*53
            for (j in 0 until 52) {
                var total = 0.0; var count = 0
                for (s in selected) if (s.values[j].isFinite()) { total += s.values[j].toDouble(); count++ }
                if (count > 0) out[off+j] = (total/count).toFloat()
            }
            out[off+52] = (selected.size/(1+(win.second-win.first)/100.0)).toFloat()
            val span = out[off+4]-out[off+3]
            val scale = if (span > .04f) span else Float.NaN
            for (j in intArrayOf(3,4,5,10,11,14,26)) out[off+j] /= scale
        }
        return out
    }
    private fun raw(g: Door, d: Person?, p: Portal?, h: History, js: Map<Int, Point>,
                    now: Long, w: Int, ht: Int): FloatArray {
        val r = DoubleArray(52) { Double.NaN }
        r[0] = if (d != null) 1.0 else 0.0; r[1] = if (p?.phase == "ACTIVE") 1.0 else 0.0
        r[2] = if (p?.owned != null) 1.0 else 0.0
        r[51] = h.seen?.let { min(2000L,now-it)/1000.0 } ?: 2.0
        var area = Double.NaN
        if (d != null) {
            val b = d.box
            area = max(1.0,w.toDouble()*ht*b.height*b.width)
            fun midpoint(i: Int, j: Int): Point? {
                val first=js[i] ?: return null; val second=js[j] ?: return null
                return Point((first.x+second.x)/2,(first.y+second.y)/2)
            }
            val shoulder=midpoint(5,6); val hip=midpoint(11,12)
            listOf(shoulder,hip,js[0]).forEachIndexed { i,q ->
                if (q != null) { r[3+i]=g.side(q)/g.depth; r[6+i]=g.along(q) }
            }
            if (shoulder != null) r[9]=hypot((js[5]!!.x-js[6]!!.x)*g.aspect,js[5]!!.y-js[6]!!.y)/g.length
            if (shoulder != null && hip != null) r[10]=hypot((shoulder.x-hip.x)*g.aspect,shoulder.y-hip.y)/g.depth
            r[11]=g.side(b.center)/g.depth; r[12]=g.along(b.center)
            r[13]=b.width*g.aspect/g.length; r[14]=b.height/g.depth; r[15]=overlap(b,g.rect)
            r[29]=listOf(5,6,11,12).mapNotNull { d.joints.getOrNull(it)?.confidence?.takeIf { it.isFinite() } }.let { if(it.isEmpty()) Double.NaN else it.average() }
            r[30]=d.score
            val previous=h.previous
            if (previous != null) {
                val dt=(now-previous.time)/1000.0
                val common=js.keys.intersect(previous.joints.keys)
                if (dt in .025.. .4 && previous.owner==d.id && common.size>=2) {
                    r[26]=median(common.map { (g.side(js[it]!!)-g.side(previous.joints[it]!!))/g.depth/dt })
                    r[27]=common.size/5.0
                    r[28]=median(common.map { (g.along(js[it]!!)-g.along(previous.joints[it]!!))/dt })
                }
            }
        }
        if (p != null) {
            r[16]=p.absorption ?: Double.NaN; r[17]=p.poseInside ?: Double.NaN
            r[18]=p.owned?.p20 ?: Double.NaN; r[19]=p.owned?.p50 ?: Double.NaN; r[20]=p.owned?.p80 ?: Double.NaN
            if(area.isFinite()) { r[21]=min(4.0,(p.owned?.pixels ?: 0)/area); r[22]=min(4.0,(p.motion?.pixels ?: 0)/area) }
            r[23]=p.bodyMotion ?: Double.NaN
            // Slots 24/25 intentionally missing. LK was not measured in five
            // captures and must not become a hidden recording identifier.
            for ((start,grid) in listOf(31 to p.owned?.grid,39 to p.motion?.grid)) {
                if(grid?.size==32) for(i in 0..7) r[start+i]=(0..3).sumOf { grid[i*4+it].toDouble() }/(255*4)
            }
            val grid=p.owned?.grid
            if(grid?.size==32) for(i in 0..3) r[47+i]=(0..7).sumOf { grid[it*4+i].toDouble() }/(255*8)
        }
        return FloatArray(52) { r[it].toFloat() }
    }
    private fun validJoints(d: Person?): Map<Int, Point> {
        if(d==null) return emptyMap()
        val b=d.box; val points=linkedMapOf<Int, Point>()
        for(i in intArrayOf(0,5,6,11,12)) {
            val j=d.joints.getOrNull(i) ?: continue; val p=j.point
            if(j.confidence>=.35 && p.x.isFinite() && p.y.isFinite() &&
                p.x>=b.left-.025 && p.x<=b.right+.025 && p.y>=b.top-.025 && p.y<=b.bottom+.025 &&
                !(p.x==0.0 && p.y==0.0)) points[i]=p
        }
        return points
    }
    companion object {
        private val WINDOWS = listOf(0L to 160L,160L to 360L,360L to 680L,680L to 1080L,1080L to 1680L)
        private fun overlap(b: Box,r: Box): Double =
            max(0.0,min(b.right,r.right)-max(b.left,r.left))*max(0.0,min(b.bottom,r.bottom)-max(b.top,r.top))/max(1e-8,b.width*b.height)
        private fun median(v: List<Double>): Double {
            if(v.isEmpty()) return Double.NaN
            val s=v.sorted(); return (s[s.size/2]+s[(s.size-1)/2])/2
        }
    }
}
