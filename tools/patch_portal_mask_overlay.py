from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate"
TESTS = ROOT / "app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/gate"

HELPER = '''package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.concurrent.ConcurrentHashMap

/** One horizontal run of source pixels. Coordinates are normalized against the source frame. */
internal data class GatePixelRun(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

internal data class GateEncodedMask(
    val runs: List<GatePixelRun>,
    val pixels: Int,
)

/**
 * Converts a binary one-channel mask into exact horizontal pixel runs.
 * Gaps stay gaps: this never closes contours or fills pixels that are zero in the source mask.
 */
internal fun encodeGateMaskRuns(
    bytes: ByteArray,
    width: Int,
    height: Int,
    rectX: Int,
    rectY: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): GateEncodedMask {
    if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0 || bytes.size < width * height) {
        return GateEncodedMask(emptyList(), 0)
    }
    val runs = ArrayList<GatePixelRun>()
    var pixels = 0
    for (y in 0 until height) {
        var x = 0
        val row = y * width
        while (x < width) {
            while (x < width && (bytes[row + x].toInt() and 0xff) == 0) x++
            if (x >= width) break
            val start = x
            while (x < width && (bytes[row + x].toInt() and 0xff) != 0) x++
            val endExclusive = x
            pixels += endExclusive - start
            runs += GatePixelRun(
                (rectX + start).toDouble() / sourceWidth,
                (rectY + y).toDouble() / sourceHeight,
                (rectX + endExclusive).toDouble() / sourceWidth,
                (rectY + y + 1).toDouble() / sourceHeight,
            )
        }
    }
    return GateEncodedMask(runs, pixels)
}

internal data class GateDebugMaskSnapshot(
    val motionRuns: List<GatePixelRun>,
    val backgroundRuns: List<GatePixelRun>,
    val ownedRuns: List<GatePixelRun>,
    val motionPixels: Int,
    val backgroundPixels: Int,
    val ownedPixels: Int,
)

/** Visualization-only bridge. These masks must never feed transition decisions. */
internal object GateMaskDebug {
    private val latest = ConcurrentHashMap<String, GateDebugMaskSnapshot>()

    fun publish(
        gateId: String,
        rect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
        motion: Mat,
        backgroundDiff: Mat,
        owned: Mat?,
    ) {
        val motionMask = encodeMat(motion, rect, sourceWidth, sourceHeight)
        val backgroundMask = encodeMat(backgroundDiff, rect, sourceWidth, sourceHeight)
        val ownedMask = if (owned == null || owned.empty()) GateEncodedMask(emptyList(), 0)
            else encodeMat(owned, rect, sourceWidth, sourceHeight)
        latest[gateId] = GateDebugMaskSnapshot(
            motionMask.runs,
            backgroundMask.runs,
            ownedMask.runs,
            motionMask.pixels,
            backgroundMask.pixels,
            ownedMask.pixels,
        )
    }

    fun snapshot(gateId: String): GateDebugMaskSnapshot? = latest[gateId]
    fun clearGate(gateId: String) { latest.remove(gateId) }
    fun clear() { latest.clear() }

    private fun encodeMat(mask: Mat, rect: Rect, sourceWidth: Int, sourceHeight: Int): GateEncodedMask {
        if (mask.empty()) return GateEncodedMask(emptyList(), 0)
        val width = mask.cols()
        val height = mask.rows()
        val bytes = ByteArray(width * height)
        mask.get(0, 0, bytes)
        return encodeGateMaskRuns(bytes, width, height, rect.x, rect.y, sourceWidth, sourceHeight)
    }
}
'''

TEST = '''package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateMaskDebugTest {
    @Test fun horizontalRunsPreserveZeroPixelHoles() {
        val on = 255.toByte()
        val bytes = byteArrayOf(
            0, on, on, 0, on, 0,
            0, 0, 0, 0, 0, 0,
        )
        val encoded = encodeGateMaskRuns(bytes, 6, 2, 10, 20, 100, 100)
        assertEquals(3, encoded.pixels)
        assertEquals(2, encoded.runs.size)
        assertEquals(0.11, encoded.runs[0].left, 1e-9)
        assertEquals(0.13, encoded.runs[0].right, 1e-9)
        assertEquals(0.14, encoded.runs[1].left, 1e-9)
        assertEquals(0.15, encoded.runs[1].right, 1e-9)
        assertEquals(0.20, encoded.runs[0].top, 1e-9)
        assertEquals(0.21, encoded.runs[0].bottom, 1e-9)
    }

    @Test fun emptyMaskProducesNoPaintRuns() {
        val encoded = encodeGateMaskRuns(ByteArray(12), 4, 3, 0, 0, 100, 100)
        assertEquals(0, encoded.pixels)
        assertTrue(encoded.runs.isEmpty())
    }
}
'''

(PKG / "GateMaskDebug.kt").write_text(HELPER, encoding="utf-8")
(TESTS / "GateMaskDebugTest.kt").write_text(TEST, encoding="utf-8")

vision_path = PKG / "GateEventVisionV2.kt"
vision = vision_path.read_text(encoding="utf-8")
replacements = [
    (
        "            val remaining=max(Core.countNonZero(foreground),Core.countNonZero(raw))\n",
        "            val debugOwned=when {\n"
        "                ownership!=null&&!ownership.empty()->ownership\n"
        "                !state.owned.empty()->state.owned\n"
        "                else->null\n"
        "            }\n"
        "            // Debug only: expose exact thresholded pixels. The raw fixed-reference mask is\n"
        "            // intentionally shown before morphology so tiny threshold crossings remain visible.\n"
        "            GateMaskDebug.publish(state.gate.id,state.rect,sourceWidth,sourceHeight,motion,raw,debugOwned)\n"
        "            val remaining=max(Core.countNonZero(foreground),Core.countNonZero(raw))\n"
    ),
    (
        "            val view=GateEventTileView(state.gate.id,rectBox(state.rect),contourView(foreground,state.rect),remaining,ownCount,state.history.size,decision.phase,state.owner,state.referenceKnown)\n",
        "            val view=GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),remaining,ownCount,state.history.size,decision.phase,state.owner,state.referenceKnown)\n"
    ),
    (
        "    private fun contourView(mask:Mat,rect:Rect):List<List<FlowPoint>>{\n"
        "        val work=mask.clone();val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>();try{Imgproc.findContours(work,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);return contours.filter{Imgproc.contourArea(it)>=cfg.contourMinArea}.sortedByDescending{Imgproc.contourArea(it)}.take(10).map{c->val a=c.toArray();val stride=max(1,a.size/48);a.filterIndexed{i,_->i%stride==0}.map{q->FlowPoint((q.x+rect.x)/sourceWidth,(q.y+rect.y)/sourceHeight)}}.filter{it.size>=3}}finally{contours.forEach{it.release()};hierarchy.release();work.release()}\n"
        "    }\n"
        "    private fun viewIdle(state:PortalState,d:GateSensorDecision)=GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),state.history.size,d.phase,d.ownerTrack,state.referenceKnown)\n",
        "    private fun viewIdle(state:PortalState,d:GateSensorDecision):GateEventTileView {\n"
        "        // ARMED/OFF does no pixel processing, so never leave a stale ACTIVE mask on screen.\n"
        "        GateMaskDebug.clearGate(state.gate.id)\n"
        "        return GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),state.history.size,d.phase,d.ownerTrack,state.referenceKnown)\n"
        "    }\n"
    ),
    (
        "        }catch(e:Exception){\n            current.values.forEach{runCatching{it.release()}}\n",
        "        }catch(e:Exception){\n            GateMaskDebug.clear()\n            current.values.forEach{runCatching{it.release()}}\n"
    ),
    (
        "        states.values.forEach{it.release()};states.clear();scheduler.reset();lk?.reset();sourceWidth=w;sourceHeight=h;previousTime=-1;sceneSamples=null\n",
        "        states.values.forEach{it.release()};states.clear();GateMaskDebug.clear();scheduler.reset();lk?.reset();sourceWidth=w;sourceHeight=h;previousTime=-1;sceneSamples=null\n"
    ),
    (
        "    override fun close(){states.values.forEach{it.release()};states.clear();scheduler.reset();lk?.reset();openKernel.release();growKernel.release();sceneSamples=null}\n",
        "    override fun close(){states.values.forEach{it.release()};states.clear();GateMaskDebug.clear();scheduler.reset();lk?.reset();openKernel.release();growKernel.release();sceneSamples=null}\n"
    ),
]
for old, new in replacements:
    if old not in vision:
        raise SystemExit(f"GateEventVisionV2 patch anchor missing: {old[:100]!r}")
    vision = vision.replace(old, new, 1)
vision_path.write_text(vision, encoding="utf-8")

overlay_path = PKG / "GateOverlay.kt"
overlay = overlay_path.read_text(encoding="utf-8")
overlay = overlay.replace("import android.graphics.Path\n", "", 1)
old = '        lines+="黄色轮廓=真实局部门前景  绿色线=B版局部双向光流"\n'
new = (
    '        val masks=s.v?.tiles.orEmpty().mapNotNull { GateMaskDebug.snapshot(it.gate) }\n'
    '        lines+="像素 动态 ${masks.sumOf{it.motionPixels}}  背景差 ${masks.sumOf{it.backgroundPixels}}  人体 ${masks.sumOf{it.ownedPixels}}"\n'
    '        lines+="黄色=逐像素帧间变化  橙色=逐像素参考背景差  青色=人体归属  绿色线=B版光流"\n'
)
if old not in overlay:
    raise SystemExit("GateOverlay legend anchor missing")
overlay = overlay.replace(old, new, 1)
start_marker = "        // Draw the actual foreground contours. The old 4x8 filled debug bricks are deliberately gone."
end_marker = "            paint.style=Paint.Style.FILL\n            paint.color=when(tile.phase)"
start = overlay.find(start_marker)
end = overlay.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("GateOverlay drawing block anchors missing")
replacement = '''        fun drawMaskRuns(runs:List<GatePixelRun>,color:Int) {
            paint.style=Paint.Style.FILL
            paint.color=color
            paint.isAntiAlias=false
            runs.forEach { run ->
                canvas.drawRect(x(run.left),y(run.top),x(run.right),y(run.bottom),paint)
            }
            paint.isAntiAlias=true
        }

        // Exact-pixel debug rendering. Horizontal runs are only a compact transport format: every
        // painted source pixel was non-zero in the corresponding binary mask; holes stay unpainted.
        for(tile in s.v?.tiles.orEmpty()) {
            if(tile.phase==GateSensorPhase.OFF) continue
            GateMaskDebug.snapshot(tile.gate)?.let { mask ->
                drawMaskRuns(mask.backgroundRuns,Color.argb(72,255,128,0))
                drawMaskRuns(mask.motionRuns,Color.argb(145,255,235,0))
                drawMaskRuns(mask.ownedRuns,Color.argb(190,0,220,255))
            }
'''
overlay = overlay[:start] + replacement + overlay[end:]
overlay_path.write_text(overlay, encoding="utf-8")
