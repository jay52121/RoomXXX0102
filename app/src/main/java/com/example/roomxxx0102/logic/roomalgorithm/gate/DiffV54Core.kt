package com.example.roomxxx0102.logic.roomalgorithm.gate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V5.4 physical-progress model. Unlike V5.x's episode-wide threshold classifiers,
 * this models directional travel, portal transfer, completed transfer and retreat.
 * The key input is local-image optical flow projected onto the calibrated gate
 * normal; body-side motion is a lower-confidence fallback. Pixel amplitude and
 * number of sampled frames do not determine how far a human moved.
 *
 * All times are VIDEO FRAME TIMES. Results are stamped when actually published;
 * the detector never consults labels, retrospective frames or the room ledger.
 */
internal class DiffV54Core(
    private val livingRoomId: String,
    initialCounts: Map<String, Int> = emptyMap(),
    private val confirmMs: Long = 120L,
) {
    internal enum class Phase { WATCH, APPROACH_IN, APPROACH_OUT, TRANSIT_IN, TRANSIT_OUT, OCCLUDED, CONFIRMED, RETREAT }
    internal data class Measurement(
        val timeMs: Long,
        val gateId: String,
        val roomId: String,
        val owner: Int,
        val active: Boolean,
        val bodySide: Double?,
        val absorption: Double?,
        val bodyAlong: Double?,
        val motionPixels: Int,
        /** Optical-flow delta: positive toward living room, negative toward room. */
        val normalFlow: Double? = null,
        val flowPoints: Int = 0,
        val ownedGrid: List<Int> = emptyList(),
    )
    internal data class Crossing(
        val timeMs: Long, val gateId: String, val roomId: String,
        val owner: Int, val direction: String, val confidence: Double, val evidence: String,
    )
    internal data class Diagnostic(val phase: Phase, val progress: Double, val opticalFrames: Int, val uncertainty: Double)

    private data class Pending(val dir: Int, val at: Long, val evidence: String, val strength: Double)
    private data class GateState(
        var phase: Phase = Phase.WATCH,
        var owner: Int = -1,
        var roomId: String = "",
        var lastSampleAt: Long = -1,
        var lastActiveAt: Long = -1,
        var lastSide: Double? = null,
        var lastAbs: Double? = null,
        var lastAlong: Double? = null,
        var lastGoodOpticalAt: Long = -1,
        var flowSupport: Int = 0,
        var travel: Double = 0.0,
        var reverseTravel: Double = 0.0,
        var travelStartAt: Long = -1,
        var direction: Int = 0,
        var peakAbs: Double = 0.0,
        var firstAbs: Double = 0.0,
        var lastMotionAt: Long = -1,
        var settledAt: Long = -1,
        var observedCount: Int = 0,
        var pending: Pending? = null,
        var lastPublishedDir: Int = 0,
        var lastPublishedAt: Long = -1,
        var noise: Double = 0.002,
        var uncertainty: Double = 1.0,
        var generation: Int = 0,
    )

    private val gates = linkedMapOf<String, GateState>()
    private val countsInternal = initialCounts.toMutableMap().withDefault { 0 }
    private val ownedInternal = linkedMapOf<Int, String>()
    private var previousTime = -1L
    val counts: Map<String, Int> get() = countsInternal.toMap()
    val ownedRooms: Map<Int, String> get() = ownedInternal.toMap()
    val pendingPortalCount: Int get() = gates.values.count { it.pending != null || it.phase in setOf(Phase.TRANSIT_IN, Phase.TRANSIT_OUT) }

    fun diagnostic(gateId: String): Diagnostic? = gates[gateId]?.let {
        Diagnostic(it.phase, it.travel, it.flowSupport, it.uncertainty)
    }

    fun detachIdentitySource() {
        // Do not manufacture transitions from a restart or delete measured counts.
        gates.clear()
        ownedInternal.clear()
    }

    fun step(timeMs: Long, measures: List<Measurement>, frameHealthy: Boolean = true): List<Crossing> {
        if (timeMs <= previousTime) return emptyList()
        previousTime = timeMs
        if (!frameHealthy) return emptyList()
        val seen = hashSetOf<String>()
        for (m in measures) {
            if (m.owner < 0 || m.timeMs != timeMs || m.roomId == livingRoomId || !seen.add(m.gateId)) continue
            val g = gates.getOrPut(m.gateId) { GateState() }
            observe(g, m)
        }
        // A pending crossing may be released after brief missing detections, but
        // never generated from the absence of a healthy frame by itself.
        val due = gates.mapNotNull { (gate, g) ->
            val p = g.pending ?: return@mapNotNull null
            if (timeMs < p.at + confirmMs) return@mapNotNull null
            if (g.phase == Phase.RETREAT || g.owner < 0 || timeMs - g.lastSampleAt > 400) {
                g.pending = null
                return@mapNotNull null
            }
            if (g.lastPublishedDir == p.dir && g.lastPublishedAt >= 0 && timeMs - g.lastPublishedAt < 2500) {
                g.pending = null
                return@mapNotNull null
            }
            val sig = measures.firstOrNull { it.gateId == gate }
            val absNow = sig?.absorption ?: g.lastAbs
            if (p.dir < 0 && absNow != null && g.phase != Phase.OCCLUDED && absNow < .83) {
                g.pending = null
                return@mapNotNull null
            }
            Pair(gate, Triple(g, p, sig))
        }.sortedByDescending { it.second.second.strength }
        val out = mutableListOf<Crossing>()
        for ((gate, triple) in due) {
            val (g, p, _) = triple
            val duplicate = out.any { it.owner == g.owner && it.roomId != g.roomId && abs(it.timeMs - timeMs) < 180 }
            if (duplicate) { g.pending = null; continue }
            val dir = if (p.dir < 0) "ENTER" else "EXIT"
            val room = g.roomId
            val e = Crossing(timeMs, gate, room, g.owner, dir, p.strength, p.evidence)
            out += e
            publish(e)
            g.lastPublishedDir = p.dir
            g.lastPublishedAt = timeMs
            g.phase = Phase.CONFIRMED
            g.pending = null
            g.travel = 0.0
            g.flowSupport = 0
            g.settledAt = -1L
            g.travelStartAt = timeMs
        }
        gates.entries.removeAll { (_, g) -> g.lastSampleAt >= 0 && timeMs - g.lastSampleAt > 3500 && g.pending == null }
        return out
    }

    private fun observe(g: GateState, m: Measurement) {
        val now = m.timeMs
        val dt = if (g.lastSampleAt < 0) 100L else now - g.lastSampleAt
        // Do not integrate a long tracking gap as if every intermediate frame existed.
        // A new tracked person must never inherit the previous owner's directional
        // distance, pending candidate, optical confidence, or last pose -- not
        // even when ByteTrack reassigns an id in consecutive 90ms frames.
        if (dt > 650 || (g.owner != -1 && g.owner != m.owner)) {
            resetKinematics(g)
        }
        g.owner = m.owner
        g.roomId = m.roomId
        val recent = dt in 25L..400L
        val poseDelta = if (recent && m.bodySide != null && g.lastSide != null) {
            (m.bodySide - g.lastSide!!).takeIf { abs(it) <= .05 }
        } else null
        val opticalDelta = m.normalFlow?.takeIf {
            m.flowPoints >= 10 && it.isFinite() && abs(it) <= .06 && recent
        }
        // LK is reference-image independent; pose-side motion is a weaker,
        // noisy fallback, not a replacement for a witnessed mask transition.
        val disagreement = opticalDelta != null && poseDelta != null &&
            opticalDelta * poseDelta < 0 && abs(opticalDelta) > .001 && abs(poseDelta) > .004
        val actual = when {
            opticalDelta != null -> opticalDelta
            poseDelta != null -> poseDelta * 0.70
            else -> 0.0
        }
        if (opticalDelta != null) { g.flowSupport++; g.lastGoodOpticalAt = now }
        if (!m.active) { if (g.phase in setOf(Phase.APPROACH_IN, Phase.APPROACH_OUT, Phase.TRANSIT_IN, Phase.TRANSIT_OUT)) g.phase = Phase.OCCLUDED }
        else g.lastActiveAt = now
        // Adaptive per-gate noise scale with bounded forgetting. Important when
        // different background references change pixel counts by 10-50x.
        if (poseDelta != null) {
            g.noise = (g.noise * .90 + min(.012, abs(poseDelta)) * .10).coerceIn(.0015, .012)
        }
        val epsilon = max(.0018, g.noise * 0.70)
        val observedDir = when {
            actual < -epsilon -> -1
            actual > epsilon -> +1
            else -> 0
        }
        if (m.active && observedDir != 0 && !disagreement && g.phase != Phase.RETREAT) {
            if (g.direction == 0 || g.phase == Phase.CONFIRMED && observedDir != g.lastPublishedDir && now - g.lastPublishedAt > 280) {
                g.direction = observedDir
                g.phase = if (observedDir < 0) Phase.APPROACH_IN else Phase.APPROACH_OUT
                g.travel = 0.0
                g.travelStartAt = now
                g.reverseTravel = 0.0
                g.peakAbs = m.absorption ?: 0.0
                g.firstAbs = m.absorption ?: 0.0
                g.flowSupport = if (opticalDelta != null) 1 else 0
                g.settledAt = -1
                g.generation++
            }
            if (g.direction == observedDir) {
                g.travel += abs(actual)
                g.reverseTravel = max(0.0, g.reverseTravel * .5 - abs(actual))
                g.lastMotionAt = now
                g.settledAt = -1L
            } else {
                // Uncommitted approach followed by a sustained reverse is a
                // RETREAT, not an EXIT. Freeze this episode until it leaves the
                // sensing window; otherwise the return leg becomes a false event.
                g.reverseTravel += abs(actual)
                if (g.lastPublishedDir != g.direction &&
                    g.reverseTravel >= max(.008, g.travel * .35)) {
                    g.pending = null
                    g.phase = Phase.RETREAT
                    g.direction = 0
                    g.travel = 0.0
                }
            }
        } else if (observedDir == 0 && g.direction != 0 && g.lastMotionAt >= 0 && now - g.lastMotionAt >= 100) {
            if (g.settledAt < 0) g.settledAt = now
        }
        if (m.absorption != null) g.peakAbs = max(g.peakAbs, m.absorption)
        val absorb = m.absorption ?: g.lastAbs
        val support = if (g.flowSupport >= 2 && now - g.lastGoodOpticalAt <= 450L) 1.0 else 0.0
        // Stable movement evidence, not a count of frames or a timer since the
        // first overlap, earns transit status. Larger noise raises the boundary.
        val travelThreshold = max(.011, g.noise * 4.0)
        if (g.pending == null && g.direction != 0 && g.travel >= travelThreshold && !disagreement &&
            g.phase !in setOf(Phase.CONFIRMED, Phase.RETREAT)) {
            g.phase = if (g.direction < 0) Phase.TRANSIT_IN else Phase.TRANSIT_OUT
            // Clearing is measured relative to the occupancy PEAK observed during
            // this exit, not the pre-contact first sample. The latter can be
            // lower than the body occupancy at exit (real-device #1 and #13),
            // causing an impossible 'first minus current' confirmation.
            val cleared = absorb != null && g.peakAbs - absorb >= .085 && absorb <= .94
            val saturated = absorb != null && absorb >= .975 && g.peakAbs >= .98
            val settled = g.settledAt >= 0 && now - g.settledAt >= 80L
            val completed = if (g.direction < 0) {
                saturated && (settled || (g.travel >= travelThreshold * 1.75 && g.flowSupport >= 2))
            } else {
                cleared || (absorb != null && absorb <= .74 && g.flowSupport >= 2)
            }
            if (completed && support >= .9) {
                val strength = (min(1.0, g.travel / travelThreshold) * .6 + support * .4).coerceIn(0.0, 1.0)
                g.pending = Pending(g.direction, now, if (g.direction < 0) "flow-silhouette-completed" else "flow-foreground-cleared", strength)
            }
        }
        // Genuine inward occlusion: visible inbound movement first, then local
        // foreground motion during HOLD. A static missing detector cannot qualify.
        if (!m.active && g.phase == Phase.OCCLUDED && g.direction == -1 && g.pending == null &&
            g.travel >= travelThreshold && g.peakAbs >= .92 &&
            g.lastActiveAt >= 0 && now - g.lastActiveAt in 150L..500L &&
            m.motionPixels >= 350 && g.flowSupport >= 2 && now - g.lastGoodOpticalAt <= 650L) {
            g.pending = Pending(-1, now, "inward-flow-followed-by-occlusion", .72)
        }
        // A pending proposal can be cancelled by a strong contradictory pose or
        // optical observation before the causal confirmation deadline.
        val p = g.pending
        if (p != null && observedDir != 0 && observedDir != p.dir &&
            opticalDelta != null && abs(opticalDelta) > epsilon * 1.5) {
            g.pending = null
            g.phase = Phase.RETREAT
            g.travel = 0.0
            g.direction = 0
        }
        g.uncertainty = if (opticalDelta != null) .22 else if (poseDelta != null) .65 else .96
        g.lastSide = m.bodySide ?: g.lastSide
        g.lastAbs = m.absorption ?: g.lastAbs
        g.lastAlong = m.bodyAlong ?: g.lastAlong
        g.lastSampleAt = now
        g.observedCount++
    }

    private fun resetKinematics(g: GateState) {
        g.phase = Phase.WATCH
        g.lastGoodOpticalAt = -1
        g.lastSide = null
        g.lastAbs = null
        g.direction = 0
        g.travel = 0.0
        g.reverseTravel = 0.0
        g.flowSupport = 0
        g.peakAbs = 0.0
        g.pending = null
        g.travelStartAt = -1L
        g.lastMotionAt = -1L
        g.settledAt = -1L
    }

    private fun publish(e: Crossing) {
        val n = countsInternal.getValue(e.roomId)
        if (e.direction == "ENTER") {
            countsInternal[e.roomId] = n + 1
            countsInternal[livingRoomId] = max(0, countsInternal.getValue(livingRoomId) - 1)
            ownedInternal[e.owner] = e.roomId
        } else {
            // A prior missed entry may not veto an independently witnessed exit.
            countsInternal[e.roomId] = max(0, n - 1)
            countsInternal[livingRoomId] = countsInternal.getValue(livingRoomId) + 1
            ownedInternal[e.owner] = livingRoomId
        }
    }
}