package com.example.roomxxx0102.logic.roomalgorithm.temporal

import java.io.DataInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Trained, scene-calibrated temporal classifier. This is not a claimed universal
 *  physical door-plane estimator. Runtime accepts only geometry + causal poses.
 *  Absolute time, frame sequence, room identity, route index, annotations, legacy
 *  decisions, feet, knees, and bbox bottom crossing are NOT model features. */
internal class TemporalPortalModel private constructor(
    private val trees: List<Tree>, private val scale: Double, private val bias: DoubleArray
) {
    private data class Tree(val features: IntArray, val borders: FloatArray, val leaves: DoubleArray)
    val featureCount: Int get() = 69
    fun predict(x: FloatArray): DoubleArray {
        require(x.size == featureCount && x.all { it.isFinite() })
        val logits = DoubleArray(3)
        for (tree in trees) {
            var leaf = 0
            for (j in tree.features.indices) if (x[tree.features[j]] > tree.borders[j]) leaf = leaf or (1 shl j)
            for (c in 0..2) logits[c] += tree.leaves[leaf * 3 + c]
        }
        for (c in 0..2) logits[c] = logits[c] * scale + bias[c]
        val largest = logits.maxOrNull()!!
        var total = 0.0
        for (c in 0..2) { logits[c] = exp(logits[c] - largest); total += logits[c] }
        for (c in 0..2) logits[c] /= total
        return logits
    }
    companion object {
        fun load(input: InputStream): TemporalPortalModel = DataInputStream(GZIPInputStream(input)).use { s ->
            val magic = ByteArray(8); s.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "SISPTM01") { "Invalid temporal model signature" }
            val features = s.readInt(); val count = s.readInt(); val classes = s.readInt()
            require(features == 69 && count in 1..2048 && classes == 3)
            val scale = s.readDouble(); val bias = DoubleArray(3) { s.readDouble() }
            require(scale.isFinite() && bias.all { it.isFinite() })
            val trees = List(count) {
                val depth = s.readInt(); require(depth in 1..10)
                val indices = IntArray(depth); val borders = FloatArray(depth)
                for (j in 0 until depth) {
                    indices[j] = s.readInt(); borders[j] = s.readFloat()
                    require(indices[j] in 0 until features && borders[j].isFinite())
                }
                val leaves = DoubleArray((1 shl depth) * classes) { s.readDouble() }
                require(leaves.all { it.isFinite() })
                Tree(indices, borders, leaves)
            }
            require(s.read() == -1) { "Trailing data in temporal model" }
            TemporalPortalModel(trees, scale, bias)
        }
    }
}

internal class TemporalPortalCore(
    private val geometry: List<Gate>, private val model: TemporalPortalModel,
    private val livingRoom: String = "living_room", initialCounts: Map<String,Int> = emptyMap(),
    private val threshold: Double = 0.60
) {
    data class Point(val x: Double, val y: Double, val confidence: Double)
    data class Pose(val id: Int, val left: Double, val top: Double, val right: Double,
                    val bottom: Double, val score: Double, val shielded: Boolean,
                    val points: List<Point>)
    data class Gate(val id: String, val room: String, val ax: Double, val ay: Double,
                    val bx: Double, val by: Double, val sign: Double, val aspect: Double,
                    val aperture: List<Pair<Double,Double>>) {
        val dx = (bx-ax)*aspect; val dy = by-ay
        val length = hypot(dx,dy)
        val left = aperture.minOf { it.first }; val right = aperture.maxOf { it.first }
        val top = aperture.minOf { it.second }; val bottom = aperture.maxOf { it.second }
        val depth = max(.01, aperture.maxOf { -side(it.first,it.second) })
        fun side(x: Double,y: Double) = sign*(dx*(y-ay)-dy*(x-ax)*aspect)/length
        fun along(x: Double,y: Double) = ((x-ax)*aspect*dx+(y-ay)*dy)/(length*length)
        init {
            require(aperture.size >= 3 && length > .00001 && aspect > 0)
            require(listOf(ax,ay,bx,by,sign,aspect).all { it.isFinite() })
            require(aperture.all { it.first.isFinite() && it.second.isFinite() })
        }
    }
    data class Crossing(val timeMs: Long, val gateId: String, val roomId: String,
                        val owner: Int, val direction: String, val confidence: Double)
    data class Observation(val gateId: String, val owner: Int, val features: FloatArray,
                           val probabilities: DoubleArray)
    private data class Snapshot(val time: Long, val visible: Float,
                                val joints: FloatArray, val torso: FloatArray)
    private data class State(val history: ArrayDeque<Snapshot> = ArrayDeque(),
                             var seenAt: Long = -999999L, var owner: Int = -1,
                             var lastMotionAt: Long = -999999L,
                             val lastOutput: LongArray = LongArray(3) { -999999L })
    private val states = geometry.associate { it.id to State() }
    private var lastTime = -1L
    private val population = initialCounts.toMutableMap().withDefault { 0 }
    private val locations = mutableMapOf<Int,String>()
    var observations: List<Observation> = emptyList(); private set
    val counts: Map<String,Int> get() = population.toMap()
    val ownedRooms: Map<Int,String> get() = locations.toMap()
    fun detachIdentitySource() { states.values.forEach { it.history.clear();it.owner=-1;it.seenAt=-999999;it.lastMotionAt=-999999;it.lastOutput.fill(-999999) }; locations.clear() }
    fun reset() { detachIdentitySource(); lastTime=-1; population.clear(); observations=emptyList() }

    /** All observations in one invocation must be from this exact frame. Output
     *  time is always `now`; neither history replay nor model inference backdates. */
    fun step(now: Long, poses: List<Pose>, healthy: Boolean = true): List<Crossing> {
        if (now <= lastTime) return emptyList()
        lastTime = now; observations=emptyList()
        if (!healthy) return emptyList()
        val out=mutableListOf<Crossing>(); val details=mutableListOf<Observation>()
        for (gate in geometry) {
            val state=states.getValue(gate.id)
            val det=select(gate,poses)
            if (det != null) {
                if (state.owner >= 0 && state.owner != det.id) { state.history.clear();state.lastMotionAt=-999999 }
                state.owner=det.id;state.seenAt=now
            }
            // Geometry candidate work is cheap; idle doors invoke no model or pixels.
            if (now-state.seenAt > 1500L) continue
            val current=snapshot(gate,det,now)
            val previous=state.history.lastOrNull()
            // An appearance at a calibrated position is not itself a crossing.
            // Require witnessed same-joint motion, with velocity in normalized
            // gate units/second (not a fixed per-frame displacement threshold).
            if(previous!=null && now-previous.time in 25L..400L) {
                val du=mutableListOf<Float>();val dv=mutableListOf<Float>()
                for(j in JOINTS.indices)if(current.joints[j*3+2]>0 && previous.joints[j*3+2]>0) {
                    du+=current.joints[j*3]-previous.joints[j*3]
                    dv+=current.joints[j*3+1]-previous.joints[j*3+1]
                }
                if(du.size>=3) {
                    du.sort();dv.sort()
                    val speed=hypot(median(du).toDouble(),median(dv).toDouble())*1000.0/(now-previous.time)
                    if(speed>=.035)state.lastMotionAt=now
                }
            }
            state.history.addLast(current)
            while (state.history.isNotEmpty() && now-state.history.first.time > 1700L) state.history.removeFirst()
            val x=features(gate,state,now);val p=model.predict(x)
            details+=Observation(gate.id,state.owner,x,p)
            val cls=p.indices.maxByOrNull { p[it] }!!
            if (cls==0 || p[cls]<threshold || now-state.lastMotionAt>2000L || now-state.lastOutput[cls]<1800L) continue
            state.lastOutput[cls]=now
            out+=Crossing(now,gate.id,gate.room,state.owner,if(cls==1)"ENTER" else "EXIT",p[cls])
        }
        observations=details
        // The count ledger observes outputs; it NEVER vetoes classifier evidence.
        for(e in out) {
            val from=if(e.direction=="ENTER")livingRoom else e.roomId
            val to=if(e.direction=="ENTER")e.roomId else livingRoom
            population[from]=max(0,population.getValue(from)-1)
            population[to]=population.getValue(to)+1
            locations[e.owner]=to
        }
        return out
    }
    private fun select(g: Gate, poses: List<Pose>): Pose? {
        var best:Pose?=null;var bestScore=Double.NEGATIVE_INFINITY
        for(d in poses) {
            if(d.shielded || !d.score.isFinite() || d.score<.25 || d.points.size<13)continue
            if(!listOf(d.left,d.top,d.right,d.bottom).all { it.isFinite() } || d.right<=d.left || d.bottom<=d.top)continue
            val w=max(.001,d.right-d.left);val h=max(.001,d.bottom-d.top)
            val intersection=max(0.0,min(d.right,g.right)-max(d.left,g.left))*max(0.0,min(d.bottom,g.bottom)-max(d.top,g.top))
            val overlap=intersection/(w*h)
            val gap=max(0.0,max(g.left-d.right,d.left-g.right))/max(.005,g.right-g.left)
            if(gap>.8 || d.top>g.bottom+.05)continue
            val score=overlap*2-gap+.1*d.score
            if(score>bestScore){best=d;bestScore=score}
        }
        return best
    }
    private fun snapshot(g: Gate,p: Pose?,time: Long): Snapshot {
        if(p==null)return Snapshot(time,0f,FloatArray(27){MISSING},FloatArray(6){MISSING})
        val joints=FloatArray(27){MISSING};val raw=arrayOfNulls<Pair<Double,Double>>(JOINTS.size)
        for(i in JOINTS.indices) {
            val q=p.points[JOINTS[i]]
            if(q.confidence>=.22 && q.x.isFinite() && q.y.isFinite() &&
                q.x>=p.left-.025 && q.x<=p.right+.025 && q.y>=p.top-.025 && q.y<=p.bottom+.025 &&
                (q.x!=0.0 || q.y!=0.0)) {
                val u=g.along(q.x,q.y);val v=-g.side(q.x,q.y)/g.depth
                raw[i]=u to v
                joints[i*3]=u.coerceIn(-4.0,5.0).toFloat()
                joints[i*3+1]=v.coerceIn(-3.0,4.0).toFloat()
                joints[i*3+2]=q.confidence.toFloat()
            }else joints[i*3+2]=0f
        }
        val torso=FloatArray(6){MISSING}
        for((k, pair) in listOf(1 to 7,2 to 8).withIndex()) {
            val s=raw[pair.first];val h=raw[pair.second]
            if(s!=null && h!=null && s.second-h.second>.025) {
                val span=s.second-h.second
                torso[k*3]=(h.second/span).toFloat()
                torso[k*3+1]=((h.first-s.first)/span).toFloat()
                torso[k*3+2]=h.first.toFloat()
            }
        }
        return Snapshot(time,1f,joints,torso)
    }
    private fun features(g:Gate,s:State,now:Long):FloatArray {
        val snaps=LAGS.map { lag ->
            val target=now-lag
            val candidate=s.history.toList().asReversed().firstOrNull { it.time<=target }
            if(candidate==null || target-candidate.time>250L)snapshot(g,null,target) else candidate
        }
        val out=FloatArray(69);var at=0
        for(v in snaps){out[at++]=v.visible;for(x in v.torso)out[at++]=x}
        val current=snaps[0]
        for(old in snaps.drop(1)) {
            val us=mutableListOf<Float>();val vs=mutableListOf<Float>()
            for(j in JOINTS.indices)if(current.joints[j*3+2]>0 && old.joints[j*3+2]>0) {
                us+=current.joints[j*3]-old.joints[j*3]
                vs+=current.joints[j*3+1]-old.joints[j*3+1]
            }
            if(us.isEmpty()){repeat(4){out[at++]=MISSING};out[at++]=0f}
            else {
                us.sort();vs.sort()
                out[at++]=median(us);out[at++]=median(vs)
                out[at++]=(quantile(us,.75)-quantile(us,.25)).toFloat()
                out[at++]=(quantile(vs,.75)-quantile(vs,.25)).toFloat()
                out[at++]=us.size/9f
            }
        }
        out[at++]=atan2(g.dy,g.dx).toFloat();out[at]= (g.length/g.depth).toFloat()
        return out
    }
    private fun median(v:List<Float>):Float=if(v.size%2==1)v[v.size/2] else (v[v.size/2-1]+v[v.size/2])/2f
    private fun quantile(v:List<Float>,q:Double):Double {
        val a=(v.size-1)*q;val lo=floor(a).toInt();val hi=ceil(a).toInt()
        return v[lo].toDouble()+(v[hi].toDouble()-v[lo].toDouble())*(a-lo)
    }
    companion object {
        private const val MISSING=-9f
        private val JOINTS=intArrayOf(0,5,6,7,8,9,10,11,12)
        private val LAGS=longArrayOf(0,120,280,500,850,1250)
    }
}
