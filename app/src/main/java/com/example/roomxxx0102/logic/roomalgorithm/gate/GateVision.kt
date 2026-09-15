package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import kotlin.math.*

internal data class GateTileView(val gate: String, val box: FlowBox, val cells: List<Int>, val foreground: Int,
    val owned: Int, val warmed: Boolean, val owner: Int?, val state: String)
internal data class GateVisionResult(val flows: Map<Int,FlowEvidence>, val tiles: List<GateTileView>,
    val samples: List<FlowSample>, val healthy: Boolean, val costMs: Long, val points: Int,
    val notes: List<String>, val origin: Pair<Int,String>? = null)

/** One admitted source frame at a time; no history replay. Geometry is image-plane geometry only. */
internal class GateVision private constructor(private val gates: List<FlowGate>, private val cfg: GateConfig) : AutoCloseable {
    companion object {
        fun create(gates: List<FlowGate>, config: GateConfig): GateVision? = try {
            if(OpenCVLoader.initLocal()) GateVision(gates,config) else null
        } catch (_: LinkageError) { null } catch (_: RuntimeException) { null }
    }
    private class Session(val id: Int, val created: Long, val owned: Mat) {
        var lastReliable = created
        var box = FlowBox(0.0,0.0,0.0,0.0)
        val contacted = mutableSetOf<Int>()
        val clear = GateClearEvidence()
        var reference = false
        var valid = true
    }
    private class Tile(val gate: FlowGate, val rect: Rect, val mask: Mat, val pixelMask: ByteArray,
                       val mog: BackgroundSubtractorMOG2?) {
        val background = Mat()
        val background8 = Mat()
        val diff = Mat()
        val foreground = Mat()
        val rawForeground = Mat()
        val motion = Mat()
        val nativeMask = Mat()
        var learnedMs = 0L
        var backgroundKnown = false
        var session: Session? = null
        fun release() {
            session?.owned?.release()
            listOf(mask,background,background8,diff,foreground,rawForeground,motion,nativeMask).forEach { it.release() }
            mog?.clear()
        }
    }
    private var cvReady: Boolean? = null
    private var width=0; private var height=0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val rgba=Mat(); private val rgb=Mat(); private val gray=Mat(); private val previous=Mat(); private val sceneDiff=Mat()
    private var previousTime=-1L
    private val tiles=mutableListOf<Tile>()
    private var grayBytes=ByteArray(0); private var previousBytes=ByteArray(0)
    private val growKernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(5.0,5.0))
    private val cleanKernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(3.0,3.0))
    private var lk: GateLk? = null
    private var blockedUntil=-1L
    private val history=ArrayDeque<Pair<Long,Mat>>()
    private val originTried=mutableMapOf<Int,Long>()

    fun update(source: Bitmap, time: Long, detections: List<FlowDetection>, people: List<FlowPersonView>, coverage: FlowBox?): GateVisionResult {
        val start=System.nanoTime()
        val flows=linkedMapOf<Int,FlowEvidence>(); val views=mutableListOf<GateTileView>()
        val samples=mutableListOf<FlowSample>();val notes=mutableListOf<String>()
        if(cvReady==null) cvReady=try { OpenCVLoader.initLocal() } catch (_: LinkageError) { false } catch (_: RuntimeException) { false }
        if(cvReady!=true) return GateVisionResult(emptyMap(),emptyList(),emptyList(),true,0,0,listOf("OPEN_CV_UNAVAILABLE_GROUND_ONLY"))
        var healthy=true
        val lkJobs=mutableListOf<GateLk.Job>()
        var jobsReleased=false
        var origin: Pair<Int,String>?=null
        try {
            prepare(source)
            canvas!!.drawBitmap(source,null,AndroidRect(0,0,width,height),paint)
            Utils.bitmapToMat(bitmap!!,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            if(cfg.method==GateMethod.MOG2) Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
            gray.get(0,0,grayBytes)
            val dt=if(previousTime<0) 0L else time-previousTime
            val continuous=previousTime>=0 && dt in 1..cfg.maxGapMs.toLong()
            if(!continuous) {
                tiles.forEach { t-> t.session?.owned?.release();t.session=null }
                lk?.reset();history.forEach { it.second.release() };history.clear()
                notes+="VISUAL_GAP_NO_TERMINAL_EVIDENCE"
            }
            if(continuous && globallyChanged(detections)) {
                blockedUntil=time+400
                tiles.forEach { t-> t.backgroundKnown=false;t.learnedMs=0;t.session?.owned?.release();t.session=null }
                lk?.reset();history.forEach { it.second.release() };history.clear();notes+="SCENE_CHANGED_CHECK_CAMERA_LIGHT"
            }
            healthy=time>=blockedUntil
            if(continuous) {
                Core.absdiff(gray,previous,sceneDiff)
                val differences=ByteArray(width*height);sceneDiff.get(0,0,differences)
                for(d in detections) {
                    val changed=bodyChange(d,differences)
                    if(changed!=null) flows[d.id]=FlowEvidence(d.id,imageAvailable=true,frameHealthy=healthy,pixelChange=changed,reason="PIXEL_ADMISSION_ONLY")
                }
            }
            val accepted=people.filter { it.accepted }.associateBy { it.track }
            for(tile in tiles) {
                val current=gray.submat(tile.rect)
                val prev=if(continuous) previous.submat(tile.rect) else null
                val color=if(cfg.method==GateMethod.MOG2) rgb.submat(tile.rect) else null
                try {
                    var old=tile.session
                    if(old!=null && (time-old.lastReliable>cfg.episodeMs || accepted[old.id]?.room==null)) {
                        old.owned.release();tile.session=null;old=null
                    }
                    val touching=detections.filter { overlaps(tile.gate,it.box) }
                    val nearby=touching.filter { d ->
                        d.locked && d.bodyValid() && !d.shielded && d.id in accepted && nearGate(tile.gate,d,accepted[d.id])
                    }
                    val incoming=nearby.singleOrNull()
                    val conflict=nearby.size>1 || (old!=null && touching.any { it.id!=old.id && it.score>=0.35 })
                    if(conflict) { old?.valid=false;notes+="GATE_PERSON_CONFLICT:${tile.gate.id}" }
                    // A detector-owned object in front of the aperture must never be learned into the background.
                    val freeze=touching.isNotEmpty() || old!=null
                    if(tile.background.empty()) current.convertTo(tile.background,CvType.CV_32F)
                    if(prev!=null) {
                        Core.absdiff(current,prev,tile.diff)
                        Imgproc.threshold(tile.diff,tile.motion,cfg.pixelThreshold.toDouble(),255.0,Imgproc.THRESH_BINARY)
                        Core.bitwise_and(tile.motion,tile.mask,tile.motion)
                    } else { tile.motion.create(current.size(),CvType.CV_8UC1);tile.motion.setTo(Scalar(0.0)) }
                    val motionCount=Core.countNonZero(tile.motion)
                    val aperturePixels=Core.countNonZero(tile.mask).coerceAtLeast(1)
                    if(healthy && !freeze && (motionCount.toDouble()/aperturePixels)<0.04) {
                        if(!tile.backgroundKnown && tile.learnedMs==0L) current.convertTo(tile.background,CvType.CV_32F)
                        if(continuous) tile.learnedMs+=dt
                        Imgproc.accumulateWeighted(current,tile.background,cfg.backgroundRate)
                        if(tile.learnedMs>=cfg.backgroundMs) tile.backgroundKnown=true
                    } else if(!tile.backgroundKnown) {
                        tile.learnedMs=0
                        if(!freeze) current.convertTo(tile.background,CvType.CV_32F)
                    }
                    tile.background.convertTo(tile.background8,CvType.CV_8U)
                    Core.absdiff(current,tile.background8,tile.diff)
                    Imgproc.threshold(tile.diff,tile.rawForeground,cfg.pixelThreshold.toDouble(),255.0,Imgproc.THRESH_BINARY)
                    Core.bitwise_and(tile.rawForeground,tile.mask,tile.rawForeground)
                    if(tile.mog!=null && color!=null) {
                        tile.mog.apply(color,tile.nativeMask,if(freeze || !healthy) 0.0 else cfg.backgroundRate)
                        // OpenCV: 127 = shadow, 255 = foreground. Shadows cannot vote for a person.
                        Imgproc.threshold(tile.nativeMask,tile.foreground,254.0,255.0,Imgproc.THRESH_BINARY)
                        Core.bitwise_and(tile.foreground,tile.mask,tile.foreground)
                    } else tile.rawForeground.copyTo(tile.foreground)
                    // Keep raw pixels for disappearance safety. Morphology is only for seed visualization.
                    Imgproc.morphologyEx(tile.foreground,tile.foreground,Imgproc.MORPH_OPEN,cleanKernel)
                    var session=tile.session
                    if(incoming!=null && !conflict && healthy) {
                        if(session==null || session.id!=incoming.id) {
                            session?.owned?.release()
                            session=Session(incoming.id,time,Mat.zeros(tile.rect.height,tile.rect.width,CvType.CV_8UC1))
                            tile.session=session
                        }
                        session.box=incoming.box
                        val ownership=bodyMask(incoming,tile)
                        try {
                            Core.bitwise_and(ownership,tile.foreground,ownership)
                            val ownCount=Core.countNonZero(ownership)
                            if(ownCount>=12) {
                                ownership.copyTo(session.owned)
                                session.contacted.addAll(occupiedCells(ownership,tile).map { it.first })
                                session.lastReliable=time
                                session.reference=session.reference || tile.backgroundKnown
                                session.valid=true
                            }
                            if(cfg.method==GateMethod.OPTICAL_FLOW && lkJobs.size<2) {
                                lkJobs+=GateLk.Job(incoming.id,tile.gate,tile.rect,ownership.clone(),true)
                            }
                        } finally { ownership.release() }
                    } else if(session!=null && healthy && continuous && !conflict) {
                        // No new disconnected seed after detector loss. Propagate the existing support locally.
                        val expanded=Mat()
                        try {
                            Imgproc.dilate(session.owned,expanded,growKernel)
                            Core.bitwise_and(expanded,tile.foreground,session.owned)
                        } finally { expanded.release() }
                        if(cfg.method==GateMethod.OPTICAL_FLOW && lkJobs.size<2) {
                            lkJobs+=GateLk.Job(session.id,tile.gate,tile.rect,session.owned.clone(),false)
                        }
                    }
                    val fgCount=Core.countNonZero(tile.foreground)
                    val rawCount=Core.countNonZero(tile.rawForeground)
                    val ownedCount=session?.let { Core.countNonZero(it.owned) } ?: 0
                    // Require the aperture to recover, not just a vacated pixel footprint. A person can move within it.
                    val remaining=max(fgCount,rawCount)
                    if(session!=null) {
                        val inspected=coverage==null || coverage.contains(session.box.center)
                        val trusted=healthy && continuous && tile.backgroundKnown && session.reference && session.valid && !conflict && inspected
                        val hold=session.clear.observe(time,remaining,if(incoming!=null) ownedCount else 0,trusted,cfg.maxGapMs,cfg.clearRatio)
                        val window=FlowWindowEvidence(tile.gate.id,session.contacted.size,session.clear.peak,remaining,hold,
                            tile.backgroundKnown,session.valid && !conflict,session.reference,time)
                        val prior=flows[session.id] ?: FlowEvidence(session.id,imageAvailable=false,frameHealthy=healthy)
                        flows[session.id]=prior.copy(windowEvidence=prior.windowEvidence+window)
                    }
                    val cellImage=occupiedCells(tile.foreground,tile).map { it.first }
                    views+=GateTileView(tile.gate.id,rectBox(tile.rect),cellImage,remaining,ownedCount,tile.backgroundKnown,session?.id,
                        when { !healthy->"SCENE_UNRELIABLE";conflict->"MULTI_PERSON";!tile.backgroundKnown->"BACKGROUND_WARMUP";session==null->"IDLE";incoming!=null->"CONTACT";else->"OCCLUSION" })
                } finally { current.release();prev?.release();color?.release() }
            }
            if(cfg.method==GateMethod.OPTICAL_FLOW) {
                val tracker=lk ?: GateLk(cfg).also { lk=it }
                jobsReleased=true
                val measured=if(continuous && healthy) tracker.update(previous,gray,lkJobs,time,width,height,start) else {
                    tracker.reset();tracker.update(null,gray,lkJobs,time,width,height,start)
                }
                for((id,f) in measured) {
                    val p=flows[id]
                    flows[id]=f.copy(pixelChange=p?.pixelChange,windowEvidence=p?.windowEvidence.orEmpty())
                    samples+=f.samples
                }
                if(tracker.budgetExceeded) notes+="OPTIONAL_LK_BUDGET_REACHED"
                // Rare, bounded backward ownership check for a newly detected late exit. Never a work queue.
                val visibleIds=detections.map { it.id }.toSet()
                val hiddenLiving=people.any { it.accepted && it.track !in visibleIds && it.room !in gates.map { g->g.room } }
                if(healthy && continuous && !hiddenLiving && elapsed(start)<cfg.optionalBudgetMs) {
                    val d=detections.firstOrNull { it.id !in accepted && it.locked && it.bodyValid() &&
                        time-(originTried[it.id]?:-10000)>=700 && gates.any { g->g.distance(it.box.foot)<0.08 } }
                    if(d!=null) {
                        originTried[d.id]=time
                        val g=tracker.traceOrigin(d,gray,history.toList(),gates,width,height,start)
                        if(g!=null) origin=d.id to g
                    }
                }
                if(healthy) history.add(time to gray.clone())
                while(history.size>4 || (history.isNotEmpty() && time-history.first().first>650)) history.removeFirst().second.release()
                if(originTried.size>64) originTried.clear()
            }
            previousTime=time;gray.copyTo(previous)
            grayBytes.copyInto(previousBytes)
            return GateVisionResult(flows,views,samples,healthy,elapsed(start),lk?.pointCount?:0,notes,origin)
        } catch (e: Exception) {
            if(!jobsReleased) lkJobs.forEach { it.seedMask.release() }
            lk?.reset();tiles.forEach { it.session?.valid=false }
            return GateVisionResult(emptyMap(),views,emptyList(),false,elapsed(start),0,listOf("VISUAL_ERROR:${e.javaClass.simpleName}"))
        }
    }

    private fun prepare(source: Bitmap) {
        val scale=min(1.0,cfg.visionEdge.toDouble()/max(source.width,source.height))
        val w=max(2,(source.width*scale).roundToInt());val h=max(2,(source.height*scale).roundToInt())
        if(w==width && h==height) return
        tiles.forEach { it.release() };tiles.clear();bitmap?.recycle();history.forEach { it.second.release() };history.clear()
        width=w;height=h;grayBytes=ByteArray(w*h);previousBytes=ByteArray(w*h)
        bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);canvas=Canvas(bitmap!!)
        previous.release();previousTime=-1;lk?.reset()
        val labels=IntArray(w*h) { -1 }
        gates.forEachIndexed { n,g ->
            if(g.aperture.size<3 || g.isBlind) return@forEachIndexed
            val rect=gateRect(g)
            for(y in rect.y until rect.y+rect.height) for(x in rect.x until rect.x+rect.width) {
                if(g.containsBody(FlowPoint((x+.5)/w,(y+.5)/h))) {
                    val i=y*w+x;labels[i]=if(labels[i]==-1) n else -2
                }
            }
        }
        gates.forEachIndexed { n,g ->
            if(g.aperture.size<3 || g.isBlind) return@forEachIndexed
            val rect=gateRect(g);val bytes=ByteArray(rect.width*rect.height)
            for(y in 0 until rect.height) for(x in 0 until rect.width) {
                if(labels[(y+rect.y)*w+x+rect.x]==n) bytes[y*rect.width+x]=255.toByte()
            }
            val mask=Mat(rect.height,rect.width,CvType.CV_8UC1);mask.put(0,0,bytes)
            val model=if(cfg.method==GateMethod.MOG2) Video.createBackgroundSubtractorMOG2(200,cfg.mogVariance,true) else null
            tiles+=Tile(g,rect,mask,bytes,model)
        }
    }
    private fun gateRect(g: FlowGate): Rect {
        val l=(g.aperture.minOf { it.x }*width).toInt().coerceIn(0,width-2)
        val t=(g.aperture.minOf { it.y }*height).toInt().coerceIn(0,height-2)
        val r=ceil(g.aperture.maxOf { it.x }*width).toInt().coerceIn(l+2,width)
        val b=ceil(g.aperture.maxOf { it.y }*height).toInt().coerceIn(t+2,height)
        return Rect(l,t,r-l,b-t)
    }
    private fun nearGate(g: FlowGate,d: FlowDetection,p: FlowPersonView?): Boolean {
        val feet=listOfNotNull(d.joint(15,.60),d.joint(16,.60))
        val foot=if(feet.size==2) FlowPoint(feet.map { it.x }.average(),feet.map { it.y }.average()) else p?.ground?.takeIf { it.strong }?.point ?: return false
        return g.along(foot) in -.15..1.15 && g.distance(foot)<(d.box.height*.28).coerceIn(.02,.10)
    }
    private fun overlaps(g: FlowGate,b: FlowBox): Boolean {
        if(g.aperture.isEmpty()) return false
        val l=g.aperture.minOf { it.x };val r=g.aperture.maxOf { it.x }
        val t=g.aperture.minOf { it.y };val bottom=g.aperture.maxOf { it.y }
        return b.right>l && b.left<r && b.bottom>t && b.top<bottom
    }
    private fun bodyMask(d: FlowDetection,t: Tile): Mat {
        val m=Mat.zeros(t.rect.height,t.rect.width,CvType.CV_8UC1)
        fun px(p:FlowPoint)=Point(p.x*width-t.rect.x,p.y*height-t.rect.y)
        val torso=listOf(5,6,12,11).mapNotNull { d.joint(it) }
        if(torso.size==4) {
            val mid=FlowPoint(torso.map { it.x }.average(),torso.map { it.y }.average())
            val poly=MatOfPoint(*torso.map { px(mid+(it-mid)*.85) }.toTypedArray())
            try { Imgproc.fillConvexPoly(m,poly,Scalar(255.0)) } finally { poly.release() }
        }
        val thickness=max(2,(d.box.width*width*.13).roundToInt())
        for((a,b) in listOf(5 to 7,7 to 9,6 to 8,8 to 10,11 to 13,13 to 15,12 to 14,14 to 16)) {
            val x=d.joint(a)?:continue;val y=d.joint(b)?:continue
            Imgproc.line(m,px(x),px(y),Scalar(255.0),thickness)
        }
        d.joint(0)?.let { Imgproc.circle(m,px(it),max(2,thickness),Scalar(255.0),-1) }
        Core.bitwise_and(m,t.mask,m)
        return m
    }
    private fun occupiedCells(mask: Mat,tile: Tile): List<Pair<Int,Int>> {
        val bytes=ByteArray(tile.rect.width*tile.rect.height);mask.get(0,0,bytes)
        val counts=IntArray(32)
        for(y in 0 until tile.rect.height) for(x in 0 until tile.rect.width) {
            if(bytes[y*tile.rect.width+x].toInt()!=0) counts[min(7,y*8/tile.rect.height)*4+min(3,x*4/tile.rect.width)]++
        }
        return counts.indices.filter { counts[it]>=2 }.map { it to counts[it] }
    }
    private fun globallyChanged(d: List<FlowDetection>): Boolean {
        val changes=mutableListOf<Int>();var changed=0
        for(y in height/16 until height step max(1,height/12)) for(x in width/24 until width step max(1,width/18)) {
            val p=FlowPoint(x.toDouble()/width,y.toDouble()/height)
            if(d.any { it.box.contains(p,.02) } || gates.any { it.containsBody(p) }) continue
            val diff=(grayBytes[y*width+x].toInt() and 255)-(previousBytes[y*width+x].toInt() and 255)
            changes+=diff;if(abs(diff)>cfg.pixelThreshold) changed++
        }
        return changes.size>=24 && (changed.toDouble()/changes.size>.55 || abs(median(changes.map { it.toDouble() }))>14)
    }
    private fun bodyChange(d: FlowDetection,diff: ByteArray): Double? {
        if(!d.bodyValid()) return null
        var n=0;var changed=0
        for(j in listOf(5,6,11,12,13,14)) {
            val p=d.joint(j)?:continue
            val x=(p.x*width).toInt().coerceIn(1,width-2);val y=(p.y*height).toInt().coerceIn(1,height-2)
            for(dy in -1..1) for(dx in -1..1) { n++;if((diff[(y+dy)*width+x+dx].toInt() and 255)>cfg.pixelThreshold) changed++ }
        }
        return if(n>=18) changed.toDouble()/n else null
    }
    private fun rectBox(r:Rect)=FlowBox(r.x.toDouble()/width,r.y.toDouble()/height,(r.x+r.width).toDouble()/width,(r.y+r.height).toDouble()/height)
    private fun elapsed(start:Long)=(System.nanoTime()-start)/1_000_000L
    override fun close() {
        tiles.forEach { it.release() };tiles.clear();lk?.reset();history.forEach { it.second.release() };history.clear()
        listOf(rgba,rgb,gray,previous,sceneDiff,growKernel,cleanKernel).forEach { it.release() }
        bitmap?.recycle();bitmap=null;canvas=null
    }
}
