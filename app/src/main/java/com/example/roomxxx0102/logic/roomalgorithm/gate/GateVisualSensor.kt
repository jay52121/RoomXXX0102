package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import kotlin.math.*

internal interface GateVisualSensor : AutoCloseable {
    fun measure(bitmap: Bitmap?, time: Long, detections: List<GDetection>, people: List<GatePerson>): GateVisualBatch
}

internal class DifferenceGateSensor(gates: List<Gate>, params: GateParams) : LocalGateSensor(gates, params, GateMethod.DIFFERENCE)
internal class OpticalFlowGateSensor(gates: List<Gate>, params: GateParams) : LocalGateSensor(gates, params, GateMethod.OPTICAL_FLOW)
internal class Mog2GateSensor(gates: List<Gate>, params: GateParams) : LocalGateSensor(gates, params, GateMethod.MOG2)

/** Each gate has a fixed crop, two images and a frozen clean reference; no pending image queue. */
internal abstract class LocalGateSensor(
    private val gates: List<Gate>, private val params: GateParams, private val method: GateMethod,
) : GateVisualSensor {
    private data class Dot(val point: Point, val cell: Int, val age: Int = 0)
    private class Owner(val track: Int, var box: GB, val born: Long, val reference: Mat) {
        var lastPose = born
        var lastSeed = -100000L
        var ownedArea = 0
        var peakArea = 0
        var cells = 0
        var footprint: Mat? = null
        var lastSeenMask: Mat? = null
        var centroid: GP? = null
        var motion: GP? = null
        var dots = emptyList<Dot>()
        var flowGoodUntil = -1L
        var flowCells = 0
        var trails = emptyList<Pair<GP, GP>>()
        var flowPath = GP(0.0, 0.0)
        var lost = false
        fun close() { reference.release(); footprint?.release(); lastSeenMask?.release(); dots = emptyList() }
    }
    private class Window(val gate: Gate, val rect: Rect, val mask: Mat) {
        var previous: Mat? = null
        var reference: Mat? = null
        var running: Mat? = null
        var warmStart = -1L
        var warmHits = 0
        var ready = false
        var last = -1L
        var lastIdle = -1L
        var mog: BackgroundSubtractorMOG2? = null
        var onset = -1L
        var onsetHadHuman = false
        var lastForeground = 0
        var lastForegroundTime = -1L
        var hadHuman = false
        val owners = linkedMapOf<Int, Owner>()
        fun close() { previous?.release(); reference?.release(); running?.release(); mask.release(); mog?.clear(); owners.values.forEach { it.close() }; owners.clear() }
        fun resetReference() { reference?.release(); reference = null; running?.release(); running = null; ready = false; warmStart = -1; warmHits = 0; mog?.clear(); mog = null }
    }
    private val windows = mutableListOf<Window>()
    private var dimensions: Pair<Int, Int>? = null
    private var cvReady: Boolean? = null
    private var lastTime = -1L
    private var previousGuard: ByteArray? = null
    private var blockedUntil = -1L
    private var lastIdleScan = -1L
    private lateinit var rgba: Mat
    private lateinit var rgb: Mat
    private lateinit var gray: Mat
    private lateinit var smallGuard: Mat
    private var totalPoints = 0
    private var closed = false

    override fun measure(bitmap: Bitmap?, time: Long, detections: List<GDetection>, people: List<GatePerson>): GateVisualBatch {
        if (closed || bitmap == null || bitmap.isRecycled) return GateVisualBatch(time, message = "NO_IMAGE_GROUND_ONLY")
        if (cvReady == null) cvReady = try { OpenCVLoader.initLocal() } catch (_: LinkageError) { false } catch (_: RuntimeException) { false }
        if (cvReady != true) return GateVisualBatch(time, message = "OPENCV_UNAVAILABLE_GROUND_ONLY")
        if (!::rgba.isInitialized) { rgba=Mat(); rgb=Mat(); gray=Mat(); smallGuard=Mat() }
        val start = System.nanoTime()
        val gap = if (lastTime < 0) 0 else time - lastTime
        if (lastTime >= 0 && gap <= 0) return GateVisualBatch(time, healthy = false, message = "DUPLICATE_VISUAL_FRAME", gapMs = gap)
        val nearAnyone = detections.any { d -> gates.any { g ->
            g.bounds()?.intersection(d.box)?.let { it > 0 } == true && g.distance(d.box.foot) < (d.box.h * 0.6).coerceIn(0.035, 0.22)
        } }
        val active = windows.any { it.owners.isNotEmpty() }
        // Idle windows only need a cheap reference/onset scan, not a second full-rate tracker.
        if (!nearAnyone && !active && time - lastIdleScan < 200) return GateVisualBatch(time, message = "IDLE_NO_VISUAL_WORK", gapMs = gap)
        if (!nearAnyone && !active) lastIdleScan = time
        lastTime = time
        val scale = min(1.0, params.imageEdge.toDouble() / max(bitmap.width, bitmap.height))
        val w = max(2, (bitmap.width * scale).roundToInt())
        val h = max(2, (bitmap.height * scale).roundToInt())
        val resized = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            Utils.bitmapToMat(resized, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            if (dimensions != (w to h)) {
                windows.forEach { it.close() }; windows.clear(); dimensions = w to h; previousGuard = null
                for (g in gates.filter { !it.blind }) createWindow(g, w, h)?.let { windows += it }
            }
            val sceneOk = checkScene(gray, detections, time, w, h)
            val broken = gap > params.frameGapMs && active
            if (!sceneOk || broken) {
                windows.forEach { s -> s.owners.values.forEach { it.close() }; s.owners.clear(); s.previous?.release(); s.previous = null
                    if (!sceneOk) s.resetReference() }
                return GateVisualBatch(time, healthy = false, costMs = elapsed(start), message = if (broken) "VISUAL_GAP" else "LIGHT_OR_CAMERA_CHANGE", gapMs = gap)
            }
            val evidence = mutableListOf<GateVisual>()
            var skipped = 0
            totalPoints = windows.sumOf { s -> s.owners.values.sumOf { it.dots.size } }
            val accepted = people.filter { it.accepted }.map { it.track }.toSet()
            val ordered = windows.sortedByDescending { s -> s.owners.isNotEmpty() || detections.any { d -> s.gate.bounds()?.intersection(d.box)?.let { it > 0 } == true } }
            for (s in ordered) {
                val related = detections.filter { d -> s.gate.bounds()?.intersection(d.box)?.let { it > 0 } == true }
                val haveOwner = s.owners.isNotEmpty()
                if (!haveOwner && related.isEmpty() && time - s.lastIdle < 200) continue
                if (!haveOwner && related.isEmpty()) s.lastIdle = time
                if (elapsed(start) > params.workBudgetMs && !haveOwner && related.isEmpty()) { skipped++; continue }
                evidence += processWindow(s, time, detections, related, accepted, w, h, start)
            }
            return GateVisualBatch(time, evidence, costMs = elapsed(start), points = totalPoints,
                active = windows.count { it.owners.isNotEmpty() }, skipped = skipped,
                message = if (elapsed(start) > params.workBudgetMs) "BUDGET_OVERRUN_NO_BACKLOG" else "OK", gapMs = gap)
        } catch (e: RuntimeException) {
            Log.w("GateSensor", "visual measurement failed; no disappearance evidence", e)
            windows.forEach { s -> s.owners.values.forEach { it.close() }; s.owners.clear() }
            return GateVisualBatch(time, healthy = false, costMs = elapsed(start), message = "VISUAL_ERROR", gapMs = gap)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun createWindow(g: Gate, w: Int, h: Int): Window? {
        val b = g.bounds() ?: return null
        val l = floor(b.l * w).toInt().coerceIn(0,w-2); val t = floor(b.t * h).toInt().coerceIn(0,h-2)
        val r = ceil(b.r * w).toInt().coerceIn(l+2,w); val bottom = ceil(b.b * h).toInt().coerceIn(t+2,h)
        val rect = Rect(l,t,r-l,bottom-t)
        val mask = Mat.zeros(rect.height,rect.width,CvType.CV_8UC1)
        val polygon = MatOfPoint(*g.aperture.map { Point(it.x*w-l,it.y*h-t) }.toTypedArray())
        try { Imgproc.fillPoly(mask,listOf(polygon),Scalar(255.0)) } finally { polygon.release() }
        // Final attribution is always inside the original aperture. Shared boundary pixels are neutral.
        if (rect.width >= 10 && rect.height >= 10) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(3.0,3.0))
            try { Imgproc.erode(mask,mask,kernel) } finally { kernel.release() }
        }
        if (Core.countNonZero(mask) < params.minForegroundPixels) { mask.release(); return null }
        return Window(g,rect,mask)
    }

    private fun checkScene(image: Mat, ds: List<GDetection>, time: Long, w: Int, h: Int): Boolean {
        Imgproc.resize(image,smallGuard,Size(80.0,45.0),0.0,0.0,Imgproc.INTER_AREA)
        val now = ByteArray(80*45); smallGuard.get(0,0,now)
        val prior = previousGuard; previousGuard = now
        if (prior != null) {
            val changes = ArrayList<Double>(1000)
            for (y in 0 until 45 step 2) for (x in 0 until 80 step 2) {
                val p = GP((x+0.5)/80,(y+0.5)/45)
                if (ds.any { it.box.contains(p,0.02) } || gates.any { it.contains(p) }) continue
                val i = y*80+x
                changes += ((now[i].toInt() and 255) - (prior[i].toInt() and 255)).toDouble()
            }
            if (changes.size >= 80) {
                val offset = med(changes)
                val residual = changes.count { abs(it-offset) > 30 }.toDouble()/changes.size
                if (abs(offset) > 24 || residual > 0.48) blockedUntil = time + 500
            }
        }
        return time >= blockedUntil
    }

    private fun processWindow(s: Window, time: Long, all: List<GDetection>, related: List<GDetection>, accepted: Set<Int>,
                              w: Int, h: Int, started: Long): List<GateVisual> {
        val current = gray.submat(s.rect)
        val color = rgb.submat(s.rect)
        val foreground = Mat(); val motionMask = Mat(); val difference = Mat()
        val out = mutableListOf<GateVisual>()
        try {
            if (s.previous != null) {
                Core.absdiff(current,s.previous, difference)
                Imgproc.threshold(difference,motionMask,params.differenceThreshold.toDouble(),255.0,Imgproc.THRESH_BINARY)
                Core.bitwise_and(motionMask,s.mask,motionMask)
            } else s.mask.copyTo(motionMask)
            val motionRatio = Core.countNonZero(motionMask).toDouble()/Core.countNonZero(s.mask).coerceAtLeast(1)
            val noHuman = related.isEmpty() && s.owners.isEmpty()
            if (!s.ready) {
                if (noHuman && motionRatio < 0.04) {
                    if (s.warmStart < 0) s.warmStart=time
                    s.warmHits++
                    if (s.running == null) { s.running=Mat(); color.convertTo(s.running,CvType.CV_32FC3) }
                    else Imgproc.accumulateWeighted(color,s.running,0.15)
                    if (time-s.warmStart >= params.warmupMs && s.warmHits >= 4) {
                        s.reference=Mat(); s.running!!.convertTo(s.reference,CvType.CV_8UC3); s.ready=true
                    }
                } else { s.warmStart=-1; s.warmHits=0 }
            }
            if (s.ready && s.reference != null) {
                if (method == GateMethod.MOG2) {
                    if (s.mog == null) {
                        s.mog = Video.createBackgroundSubtractorMOG2(params.mogHistory,params.mogVariance,true)
                        s.mog!!.setNMixtures(3)
                        s.mog!!.apply(s.reference,foreground,1.0)
                    }
                    s.mog!!.apply(color,foreground,if(noHuman && motionRatio < 0.03 && s.lastForeground < params.minForegroundPixels) params.backgroundRate else 0.0)
                    Imgproc.threshold(foreground,foreground,254.0,255.0,Imgproc.THRESH_BINARY)
                } else {
                    val refGray=Mat()
                    try {
                        Imgproc.cvtColor(s.reference,refGray,Imgproc.COLOR_RGB2GRAY)
                        Core.absdiff(current,refGray,difference)
                        Imgproc.threshold(difference,foreground,params.differenceThreshold.toDouble(),255.0,Imgproc.THRESH_BINARY)
                    } finally { refGray.release() }
                }
                Core.bitwise_and(foreground,s.mask,foreground)
                if (method == GateMethod.MOG2) filterComponents(foreground)
                if (noHuman && motionRatio < 0.03 && Core.countNonZero(foreground) < params.minForegroundPixels) {
                    Imgproc.accumulateWeighted(color,s.running,params.backgroundRate)
                    s.running!!.convertTo(s.reference,CvType.CV_8UC3)
                }
            } else { motionMask.copyTo(foreground) }
            val fgArea=Core.countNonZero(foreground)
            if (s.ready && fgArea >= params.minForegroundPixels && s.lastForeground < params.minForegroundPixels) {
                s.onset=time; s.onsetHadHuman=s.hadHuman || related.isNotEmpty()
            }
            if (fgArea >= params.minForegroundPixels) s.lastForegroundTime=time
            s.lastForeground=fgArea
            val reliable=related.filter { it.locked && it.credible() && !it.shielded }
            for (d in reliable) {
                // This is only an activation test; box bottom never proves a crossing.
                val near=s.gate.distance(d.box.foot) <= (d.box.h*0.55).coerceIn(0.035,0.20)
                if (!near) {
                    s.owners.remove(d.track)?.let { totalPoints-=it.dots.size; it.close() }
                    continue
                }
                if (s.owners.size >= 2 && d.track !in s.owners) continue
                val body=bodyMask(d,all,s,w,h)
                val owned=Mat()
                try {
                    Core.bitwise_and(body,foreground,owned)
                    val area=Core.countNonZero(owned)
                    val cells=countCells(owned)
                    if (area < params.minForegroundPixels || cells < params.minOwnedCells) continue
                    val owner=s.owners.getOrPut(d.track) { Owner(d.track,d.box,time,(s.reference ?: color).clone()) }
                    val shapeOk=d.box.h/owner.box.h.coerceAtLeast(1e-5) in 0.72..1.4
                    if (!shapeOk) continue
                    val wasLost=time-owner.lastPose > params.frameGapMs
                    if (wasLost) { owner.dots=emptyList(); owner.flowGoodUntil=-1 }
                    owner.lastPose=time; owner.box=d.box; owner.lost=false
                    if (owner.footprint == null) owner.footprint=owned.clone() else Core.bitwise_or(owner.footprint,owned,owner.footprint)
                    owner.ownedArea=area; owner.peakArea=max(owner.peakArea,area); owner.cells=max(owner.cells,cells)
                    owner.lastSeenMask?.release(); owner.lastSeenMask=owned.clone()
                    val origin=s.ready && s.onset >= 0 && !s.onsetHadHuman && time-s.onset in 0..900 && d.track !in accepted &&
                        s.gate.distance(d.box.foot) <= max(0.018,d.box.h*params.contactHeight*1.5)
                    if (origin) out += GateVisual(s.gate.id,d.track,time,valid=true,backgroundReady=true,origin=true,ownedCells=cells,reason="RECENT_UNOWNED_PORTAL_ONSET")
                } finally { body.release(); owned.release() }
            }
            for ((id,o) in s.owners.toMap()) {
                if (time-o.lastPose > params.coastMs) { totalPoints-=o.dots.size; o.close(); s.owners.remove(id); continue }
                val currentDetection=related.firstOrNull { it.track == id }
                val otherHuman=related.any { it.track != id && it.score >= 0.35 }
                val ambiguous=otherHuman || s.owners.size > 1
                if (ambiguous) {
                    o.dots=emptyList()
                    out += GateVisual(s.gate.id,id,time,ambiguous=true,reason="MULTIPLE_OWNERS")
                    continue
                }
                val hasFreshBody=currentDetection?.let { it.locked && it.credible() } == true
                if (!hasFreshBody) o.lost=true
                var motion: GP?=null; var motionCells=0; var verified=false
                if (method == GateMethod.OPTICAL_FLOW && o.dots.isNotEmpty() && s.previous != null &&
                    s.last >= 0 && time-s.last in 1..params.frameGapMs && elapsed(started) < params.workBudgetMs) {
                    val before=o.dots
                    val tracked=track(s.previous!!,current,before)
                    val valid=tracked.mapNotNull { it.second }
                    val dx=med(tracked.mapNotNull { (a,b) -> b?.let { it.point.x-a.point.x } })
                    val dy=med(tracked.mapNotNull { (a,b) -> b?.let { it.point.y-a.point.y } })
                    val deviations=tracked.mapNotNull { (a,b) -> b?.let { hypot(it.point.x-a.point.x-dx,it.point.y-a.point.y-dy) } }
                    val cutoff=max(2.0,med(deviations)*3.0)
                    val coherent=tracked.filter { (a,b) -> b!=null && hypot(b.point.x-a.point.x-dx,b.point.y-a.point.y-dy)<=cutoff }
                    o.dots=coherent.mapNotNull { it.second }
                    totalPoints+=o.dots.size-before.size
                    motionCells=o.dots.filter { it.age >= 2 }.map { it.cell }.distinct().size
                    verified=motionCells>=3 && valid.size.toDouble()/before.size>=0.4 && hypot(dx,dy) <= min(s.rect.width,s.rect.height)*0.4
                    if (verified) {
                        motion=GP(dx/w,dy/h); o.motion=motion; o.flowGoodUntil=time+params.coastMs; o.flowCells=motionCells
                        o.flowPath += motion
                    }
                    o.trails=coherent.take(96).map { (a,b) -> absolute(a.point,s,w,h) to absolute(b!!.point,s,w,h) }
                } else if (method == GateMethod.OPTICAL_FLOW && time-s.last > params.frameGapMs) {
                    totalPoints-=o.dots.size; o.dots=emptyList(); o.flowGoodUntil=-1
                }
                if (method == GateMethod.OPTICAL_FLOW && hasFreshBody && elapsed(started)<params.workBudgetMs &&
                    time-o.lastSeed>=300 && o.dots.size<params.pointsPerPerson*2/3 && totalPoints<params.maxPoints) {
                    val seedMask=bodyMask(currentDetection!!,all,s,w,h)
                    try {
                        Core.bitwise_and(seedMask,foreground,seedMask)
                        val free=min(params.pointsPerPerson-o.dots.size,params.maxPoints-totalPoints)
                        val extra=seed(current,seedMask,o.dots,free)
                        o.dots=o.dots+extra; totalPoints+=extra.size; o.lastSeed=time
                    } finally { seedMask.release() }
                }
                val footprint=o.footprint
                if (footprint == null) continue
                val inverse=Mat(); val restored=Mat(); val present=Mat()
                try {
                    Core.bitwise_not(foreground,inverse)
                    Core.bitwise_and(footprint,inverse,restored)
                    Core.bitwise_and(footprint,foreground,present)
                    val total=Core.countNonZero(footprint).coerceAtLeast(1)
                    val restoreRatio=Core.countNonZero(restored).toDouble()/total
                    val visible=fgArea.toDouble()/o.peakArea.coerceAtLeast(1)
                    val clear=fgArea < max(params.minForegroundPixels.toDouble(), o.peakArea*(1-params.restoredFraction))
                    val contact=hasFreshBody && o.ownedArea>=params.minForegroundPixels && o.cells>=params.minOwnedCells
                    val flowOk=method!=GateMethod.OPTICAL_FLOW || (time<=o.flowGoodUntil && o.flowCells>=3)
                    out += GateVisual(s.gate.id,id,time,contact=contact,backgroundReady=s.ready,valid=true,
                        ownedCells=o.cells,visibleFraction=visible,restoredFraction=restoreRatio,
                        backgroundRestored=s.ready && clear && restoreRatio>=params.restoredFraction && flowOk,
                        motion=motion ?: o.motion?.takeIf { time<=o.flowGoodUntil }, motionCells=max(motionCells,o.flowCells),
                        motionVerified=verified || (time<=o.flowGoodUntil && o.flowCells>=3),
                        reason=if(!s.ready) "BACKGROUND_NOT_CALIBRATED" else if(!flowOk) "FLOW_NOT_VERIFIED" else if(clear) "BACKGROUND_RETURNED" else "FOREGROUND_PRESENT",
                        marks=marks(if(clear) footprint else foreground,s,w,h), trails=o.trails)
                } finally { inverse.release(); restored.release(); present.release() }
            }
            s.hadHuman=related.isNotEmpty()
            return out
        } finally {
            s.previous?.release(); s.previous=current.clone(); s.last=time
            current.release(); color.release(); foreground.release(); motionMask.release(); difference.release()
        }
    }

    private fun filterComponents(mask: Mat) {
        val labels=Mat(); val stats=Mat(); val centers=Mat(); val keep=Mat.zeros(mask.rows(),mask.cols(),CvType.CV_8UC1); val component=Mat()
        try {
            val count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centers,8,CvType.CV_32S)
            val candidates=(1 until count).filter { stats.get(it,Imgproc.CC_STAT_AREA)[0] >= max(4,params.minForegroundPixels/2) }
                .sortedByDescending { stats.get(it,Imgproc.CC_STAT_AREA)[0] }.take(8)
            for (id in candidates) { Core.compare(labels,Scalar(id.toDouble()),component,Core.CMP_EQ); Core.bitwise_or(keep,component,keep) }
            keep.copyTo(mask)
        } finally { listOf(labels,stats,centers,keep,component).forEach { it.release() } }
    }

    private fun bodyMask(d: GDetection, others: List<GDetection>, s: Window, w: Int, h: Int): Mat {
        val mask=Mat.zeros(s.rect.height,s.rect.width,CvType.CV_8UC1)
        fun px(p: GP)=Point(p.x*w-s.rect.x,p.y*h-s.rect.y)
        val shoulders=listOfNotNull(d.joint(5),d.joint(6))
        val width=if(shoulders.size==2) shoulders[0].distance(shoulders[1],w.toDouble()/h)*h else d.box.w*w*0.5
        val torso=listOf(5,6,12,11).mapNotNull { d.joint(it) }
        if(torso.size==4) {
            val center=GP(torso.map { it.x }.average(),torso.map { it.y }.average())
            val poly=MatOfPoint(*torso.map { px(center+(it-center)*0.86) }.toTypedArray())
            try { Imgproc.fillConvexPoly(mask,poly,Scalar(255.0)) } finally { poly.release() }
        }
        for((a,b) in listOf(5 to 7,7 to 9,6 to 8,8 to 10,11 to 13,13 to 15,12 to 14,14 to 16)) {
            val pa=d.joint(a) ?: continue; val pb=d.joint(b) ?: continue
            Imgproc.line(mask,px(pa),px(pb),Scalar(255.0),max(2,(width*0.20).roundToInt()))
        }
        d.joint(0)?.let { Imgproc.circle(mask,px(it),max(2,(width*0.23).roundToInt()),Scalar(255.0),-1) }
        val allowed=Mat.zeros(mask.rows(),mask.cols(),CvType.CV_8UC1)
        try {
            Imgproc.rectangle(allowed,px(GP(d.box.l,d.box.t)),px(GP(d.box.r,d.box.b)),Scalar(255.0),-1)
            Core.bitwise_and(mask,allowed,mask); Core.bitwise_and(mask,s.mask,mask)
            others.filter { it.track!=d.track }.forEach { other ->
                Imgproc.rectangle(mask,px(GP(other.box.l,other.box.t)),px(GP(other.box.r,other.box.b)),Scalar(0.0),-1)
            }
        } finally { allowed.release() }
        return mask
    }

    private fun seed(image: Mat, mask: Mat, existing: List<Dot>, allowance: Int): List<Dot> {
        if(allowance<=0) return emptyList()
        val work=mask.clone(); val corners=MatOfPoint()
        try {
            existing.forEach { Imgproc.circle(work,it.point,3,Scalar(0.0),-1) }
            Imgproc.goodFeaturesToTrack(image,corners,min(allowance*3,720),0.01,2.5,work,3,false,0.04)
            val counts=IntArray(24)
            existing.forEach { counts[it.cell.coerceIn(0,23)]++ }
            val maxCell=max(4,params.pointsPerPerson/24+2)
            val result=mutableListOf<Dot>()
            for(p in corners.toArray()) {
                val cell=((p.y/image.rows()*6).toInt().coerceIn(0,5)*4+(p.x/image.cols()*4).toInt().coerceIn(0,3))
                if(counts[cell]>=maxCell) continue
                counts[cell]++; result+=Dot(p,cell)
                if(result.size>=allowance) break
            }
            return result
        } finally { work.release(); corners.release() }
    }

    private fun track(from: Mat, to: Mat, dots: List<Dot>): List<Pair<Dot,Dot?>> {
        if(dots.isEmpty()) return emptyList()
        val p=MatOfPoint2f(*dots.map { it.point }.toTypedArray()); val q=MatOfPoint2f(); val back=MatOfPoint2f()
        val status=MatOfByte(); val backwardStatus=MatOfByte(); val error=MatOfFloat(); val backwardError=MatOfFloat()
        try {
            val criteria=TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,20,0.03)
            val window=Size(params.lkWindow.toDouble(),params.lkWindow.toDouble())
            Video.calcOpticalFlowPyrLK(from,to,p,q,status,error,window,params.pyramidLevel,criteria,0,1e-4)
            val qs=q.toArray(); val ss=status.toArray(); val es=error.toArray()
            if(qs.size!=dots.size) return dots.map { it to null }
            // Only valid forward points enter backward LK; invalid coordinates never poison the call.
            val indices=dots.indices.filter { i -> ss.getOrElse(i){0}!=0.toByte() && qs[i].x.isFinite() && qs[i].y.isFinite() &&
                qs[i].x>=1 && qs[i].y>=1 && qs[i].x<to.cols()-1 && qs[i].y<to.rows()-1 && es.getOrElse(i){100f}<24 }
            if(indices.isEmpty()) return dots.map { it to null }
            val valid=MatOfPoint2f(*indices.map { qs[it] }.toTypedArray())
            try {
                Video.calcOpticalFlowPyrLK(to,from,valid,back,backwardStatus,backwardError,window,params.pyramidLevel,criteria,0,1e-4)
                val bs=backwardStatus.toArray(); val points=back.toArray()
                val passed=mutableMapOf<Int,Dot>()
                indices.forEachIndexed { j,i ->
                    if(bs.getOrElse(j){0}!=0.toByte() && j<points.size && hypot(points[j].x-dots[i].point.x,points[j].y-dots[i].point.y)<=params.fbError) {
                        passed[i]=Dot(qs[i],dots[i].cell,dots[i].age+1)
                    }
                }
                return dots.mapIndexed { i,d -> d to passed[i] }
            } finally { valid.release() }
        } finally { listOf(p,q,back,status,backwardStatus,error,backwardError).forEach { it.release() } }
    }

    private fun countCells(mask: Mat): Int {
        var count=0
        for(y in 0 until 6) for(x in 0 until 4) {
            val x0=x*mask.cols()/4; val x1=(x+1)*mask.cols()/4
            val y0=y*mask.rows()/6; val y1=(y+1)*mask.rows()/6
            if(x1<=x0||y1<=y0) continue
            val cell=mask.submat(y0,y1,x0,x1)
            try { if(Core.countNonZero(cell)>=2) count++ } finally { cell.release() }
        }
        return count
    }
    private fun marks(mask: Mat,s: Window,w:Int,h:Int):List<GP> {
        val result=mutableListOf<GP>()
        for(y in 0 until 8) for(x in 0 until 4) {
            val x0=x*mask.cols()/4;val x1=(x+1)*mask.cols()/4
            val y0=y*mask.rows()/8;val y1=(y+1)*mask.rows()/8
            if(x1<=x0||y1<=y0) continue
            val cell=mask.submat(y0,y1,x0,x1)
            try { if(Core.countNonZero(cell)>=2) result+=GP((s.rect.x+(x0+x1)*0.5)/w,(s.rect.y+(y0+y1)*0.5)/h) } finally { cell.release() }
        }
        return result
    }
    private fun absolute(p: Point,s:Window,w:Int,h:Int)=GP((p.x+s.rect.x)/w,(p.y+s.rect.y)/h)
    private fun elapsed(start: Long)=(System.nanoTime()-start)/1e6
    override fun close() {
        if(closed) return
        closed=true;windows.forEach { it.close() };windows.clear()
        if (::rgba.isInitialized) listOf(rgba,rgb,gray,smallGuard).forEach { it.release() }
        previousGuard=null
    }
}
