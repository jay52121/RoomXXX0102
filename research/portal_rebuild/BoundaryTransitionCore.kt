package com.example.roomxxx0102.research.portal

/** Research component only. A calibrated boundary observation is NOT bodySide or mask coverage. */
internal class BoundaryTransitionCore(
    private val confirmMs: Long = 120,
    private val maxResponseBoundMs: Long = 1000,
) {
    data class Observation(
        val atMs: Long, val entity: String, val gate: String,
        val anchorEpoch: Long, val calibrationId: String,
        val signedDistance: Double?, val uncertaintyRadius: Double,
        val geometricallyCalibrated: Boolean, val ownerVisible: Boolean,
    )
    data class Event(val atMs: Long, val entity: String, val gate: String,
                     val direction: String, val sourceSeenMs: Long, val destinationSeenMs: Long)
    private data class State(var anchor: Long, var calibration: String,
        var origin: Int = 0, var lastAt: Long = -1, var lastSourceAt: Long = -1,
        var pending: Int = 0, var pendingAt: Long = -1)
    private val states = mutableMapOf<Pair<String,String>,State>()

    fun observe(m: Observation): Event? {
        val key=m.entity to m.gate
        val s=states.getOrPut(key){State(m.anchorEpoch,m.calibrationId)}
        if(m.atMs<=s.lastAt)return null
        // A sampling gap does not itself change identity or invalidate a known
        // side. Track/anchor continuity is an explicit upstream contract.
        if(s.anchor!=m.anchorEpoch || s.calibration!=m.calibrationId) {
            s.anchor=m.anchorEpoch;s.calibration=m.calibrationId;s.origin=0;s.pending=0
        }
        s.lastAt=m.atMs
        // Missing/invalid/censored is UNKNOWN, never a zero-distance observation.
        if(!m.ownerVisible || !m.geometricallyCalibrated || m.signedDistance==null ||
            !m.signedDistance.isFinite() || !m.uncertaintyRadius.isFinite() || m.uncertaintyRadius<0) {
            s.pending=0;return null
        }
        val side=when {
            m.signedDistance < -m.uncertaintyRadius -> -1
            m.signedDistance > m.uncertaintyRadius -> 1
            else -> 0
        }
        if(side==0){s.pending=0;return null}
        if(s.origin==0){s.origin=side;s.lastSourceAt=m.atMs;return null}
        if(side==s.origin){s.pending=0;s.lastSourceAt=m.atMs;return null}
        if(s.pending!=side){s.pending=side;s.pendingAt=m.atMs;return null}
        if(m.atMs-s.pendingAt<confirmMs)return null
        // Actual crossing must be after the last definitely source-side time;
        // this bound is conditional on the upstream boundary observations.
        if(m.atMs-s.lastSourceAt>maxResponseBoundMs)return null
        val e=Event(m.atMs,m.entity,m.gate,if(side>0)"ENTER" else "EXIT",s.lastSourceAt,s.pendingAt)
        s.origin=side;s.lastSourceAt=m.atMs;s.pending=0
        return e
    }
}

/** Motion is coupled to the exact source image and the same physical anchor. */
internal class CausalMotionAccumulator {
    data class PairMeasurement(val fromMs:Long,val toMs:Long,val dotsFrameMs:Long,
        val imageFrameMs:Long,val anchorEpoch:Long,val signedDelta:Double)
    var displacement=0.0;private set
    private var end=-1L
    private var anchor=-1L
    fun add(m:PairMeasurement):Boolean {
        if(m.fromMs>=m.toMs || m.dotsFrameMs!=m.fromMs || m.imageFrameMs!=m.fromMs ||
            !m.signedDelta.isFinite())return false
        if(anchor==m.anchorEpoch && m.toMs<=end)return false
        if(anchor!=m.anchorEpoch || end!=m.fromMs)displacement=0.0
        anchor=m.anchorEpoch;end=m.toMs
        displacement+=m.signedDelta
        return true
    }
}

internal class ContinuousVelocity(private val timeConstantMs:Double=250.0) {
    var velocity=0.0;private set
    fun add(displacement:Double,intervalMs:Long):Double {
        require(intervalMs>0 && displacement.isFinite() && timeConstantMs>0)
        val rate=displacement*1000.0/intervalMs
        val alpha=1.0-kotlin.math.exp(-intervalMs/timeConstantMs)
        velocity+=alpha*(rate-velocity)
        return velocity
    }
}

/** The ledger never determines whether a crossing was observed. Unknown initial counts stay unknown. */
internal class TransferLedger(private val initial:Map<String,Int> = emptyMap()) {
    private val ids=mutableSetOf<String>()
    private val changes=mutableMapOf<String,Int>()
    val delta:Map<String,Int> get()=changes.toMap()
    fun apply(id:String,from:String,to:String):Boolean {
        require(from!=to)
        if(!ids.add(id))return false
        changes[from]=(changes[from]?:0)-1;changes[to]=(changes[to]?:0)+1
        return true
    }
    fun exact(room:String):Int?=initial[room]?.let{it+(changes[room]?:0)}?.takeIf{it>=0}
}
