package com.example.roomxxx0102.logic.roomalgorithm.gate

internal enum class GateMethod(val id: String, val label: String) {
    DIFFERENCE("portal_v4_diff", "V4-A \u5dee\u5206\u4e0e\u95e8\u5e95\u8fb9\uff08\u901f\u5ea6\u4f18\u5148\uff09"),
    OPTICAL_FLOW("portal_v4_lk", "V4-B \u5c40\u90e8\u53cc\u5411\u5149\u6d41\uff08\u7cbe\u5ea6\u5019\u9009\uff09"),
    MOG2("portal_v4_mog2", "V4-C OpenCV MOG2\uff08\u80cc\u666f\u5206\u79bb\uff09");
    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } }
}

/** Limits are explicit and independent of the YOLO model/thresholds. */
internal data class GateConfig(
    val method: GateMethod,
    val sampleMs: Int = 50,
    val captureEdge: Int = 1280,
    val visionEdge: Int = if (method == GateMethod.OPTICAL_FLOW) 640 else 480,
    val maxGapMs: Int = 300,
    val pixelThreshold: Int = 18,
    val backgroundMs: Int = 600,
    val clearMs: Int = 200,
    val clearRatio: Double = 0.08,
    val contactScale: Double = 0.10,
    val confirmMs: Int = 120,
    val admissionTravel: Double = 0.012,
    val episodeMs: Int = 1400,
    val points: Int = 192,
    val fbError: Double = 1.5,
    val pyramidLevel: Int = 2,
    val optionalBudgetMs: Int = 30,
    val mogVariance: Double = 16.0,
    val backgroundRate: Double = 0.025,
) {
    fun checked() = copy(
        sampleMs=sampleMs.coerceIn(33,200), captureEdge=captureEdge.coerceIn(640,2560),
        visionEdge=visionEdge.coerceIn(320,960), maxGapMs=maxGapMs.coerceIn(150,500),
        pixelThreshold=pixelThreshold.coerceIn(8,60), backgroundMs=backgroundMs.coerceIn(300,5000),
        clearMs=clearMs.coerceIn(120,1000), clearRatio=finite(clearRatio,0.08).coerceIn(0.02,0.20),
        contactScale=finite(contactScale,0.10).coerceIn(0.05,0.20), confirmMs=confirmMs.coerceIn(100,600),
        admissionTravel=finite(admissionTravel,0.012).coerceIn(0.005,0.04), episodeMs=episodeMs.coerceIn(600,2500),
        points=points.coerceIn(64,384), fbError=finite(fbError,1.5).coerceIn(0.5,3.0),
        pyramidLevel=pyramidLevel.coerceIn(1,3), optionalBudgetMs=optionalBudgetMs.coerceIn(10,60),
        mogVariance=finite(mogVariance,16.0).coerceIn(8.0,64.0), backgroundRate=finite(backgroundRate,0.025).coerceIn(0.002,0.08)
    )
    val key get() = toString()
    private fun finite(v: Double, default: Double) = if(v.isFinite()) v else default
}

/** Sampling admission has no image queue. The worker is the only owner of inference/FSM. */
internal class GateSamplingPermit {
    private var busy = false
    private var lastStart = Long.MIN_VALUE
    var skipped = 0L; private set
    @Synchronized fun acquire(now: Long, interval: Int): Boolean {
        if(busy || (lastStart != Long.MIN_VALUE && now-lastStart < interval)) { skipped++; return false }
        busy=true;lastStart=now;return true
    }
    @Synchronized fun release() { busy=false }
    @Synchronized fun isBusy() = busy
}

/** A fixed background return is not the same thing as a zero frame difference. */
internal class GateClearEvidence {
    var peak = 0; private set
    private var since = -1L
    private var last = -1L
    fun observe(time: Long, foreground: Int, ownPixels: Int, trustworthy: Boolean, maxGap: Int, ratio: Double): Long {
        if(last>=0 && (time<=last || time-last>maxGap)) since=-1L
        if(time<=last) return 0L
        last=time
        if(!trustworthy) { since=-1; return 0 }
        peak=maxOf(peak,ownPixels)
        if(peak>=12 && foreground<=maxOf(2,(peak*ratio).toInt())) {
            if(since<0) since=time
            return time-since
        }
        since=-1;return 0
    }
    fun reset() { peak=0;since=-1;last=-1 }
}
