package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowDetection
import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowGate
import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowPoint
import kotlin.math.max

/**
 * Pure geometry scheduler for V4. Image work is forbidden here.
 *
 * Raw YOLO candidates are allowed to arm a gate early, but only a real lower-body/threshold
 * contact can make the gate ACTIVE. After the detector disappears an ACTIVE gate is kept in
 * HOLD for a bounded interval so the visual backend can observe the actual disappearance.
 */
internal enum class GateSensorPhase { OFF, ARMED, ACTIVE, HOLD }

internal data class GateSensorDecision(
    val gateId: String,
    val phase: GateSensorPhase,
    val ownerTrack: Int?,
    val distance: Double,
    val contact: Boolean,
) {
    val shouldProcessPixels: Boolean get() = phase == GateSensorPhase.ACTIVE || phase == GateSensorPhase.HOLD
}

internal class GateActivityScheduler(private val cfg: GateConfig) {
    // V5.2 only: start local optical evidence before a foot reaches the threshold.
    // All V4/V5.0/V5.1 scheduling semantics remain unchanged.
    private val earlyBody = cfg.method == GateMethod.V5_2_DIFFERENCE || cfg.method == GateMethod.V5_3_DIFFERENCE || cfg.method == GateMethod.V5_4_DIFFERENCE
    private data class State(
        var phase: GateSensorPhase = GateSensorPhase.OFF,
        var ownerTrack: Int? = null,
        var lastNearMs: Long = -1L,
        var lastContactMs: Long = -1L,
        var lastDistance: Double = Double.POSITIVE_INFINITY,
        var firstOverlapMs: Long = -1L,
        var overlapTrack: Int? = null,
    )

    private data class Candidate(
        val gate: FlowGate,
        val detection: FlowDetection,
        val point: FlowPoint,
        val distance: Double,
        val contact: Boolean,
    )

    private val states = linkedMapOf<String, State>()

    fun reset() = states.clear()

    fun update(timeMs: Long, detections: List<FlowDetection>, gates: List<FlowGate>): List<GateSensorDecision> {
        val eligibleDetections = detections.filter { d ->
            d.score.isFinite() && d.score >= cfg.armScore && d.box.width > 0.0 && d.box.height > 0.0
        }
        val candidates = mutableListOf<Candidate>()
        for (gate in gates) {
            if (gate.isBlind || gate.aperture.size < 3) continue
            for (d in eligibleDetections) {
                if (earlyBody) {
                    val overlap = bodyApertureOverlap(gate, d)
                    if (overlap < 0.24) continue
                    // Rank by body/aperture intersection, not by leg or foot geometry.
                    candidates += Candidate(gate, d, d.box.center, 1.0 - overlap, overlap >= 0.36)
                } else {
                    val point = lowerBodyPoint(d)
                    if (gate.along(point) !in -0.22..1.22) continue
                    val distance = gate.distance(point)
                    val armBand = max(0.018, d.box.height * cfg.armDistanceScale)
                    if (distance > armBand) continue
                    val contactBand = max(0.010, d.box.height * cfg.contactScale * 1.25)
                    val contact = distance <= contactBand && lowerBodyTouchesAperture(gate, d, point)
                    candidates += Candidate(gate, d, point, distance, contact)
                }
            }
        }

        // One detector can geometrically arm two adjacent gates. Keep the strongest two portal
        // hypotheses globally; pixel processing is never allowed to fan out to every gate.
        val selected = candidates
            .sortedWith(compareByDescending<Candidate> { it.contact }.thenBy { it.distance })
            .fold(mutableListOf<Candidate>()) { out, c ->
                if (out.none { it.gate.id == c.gate.id }) out += c
                out
            }
            .take(cfg.maxActiveGates)
            .associateBy { it.gate.id }

        val ids = gates.map { it.id }.toSet()
        states.keys.retainAll(ids)
        val result = mutableListOf<GateSensorDecision>()
        for (gate in gates) {
            if (gate.isBlind || gate.aperture.size < 3) continue
            val state = states.getOrPut(gate.id) { State() }
            val c = selected[gate.id]
            if (c != null) {
                val priorNear = state.lastNearMs
                val sameOwner = state.overlapTrack == c.detection.id &&
                    priorNear >= 0 && timeMs - priorNear <= cfg.maxGapMs
                state.ownerTrack = c.detection.id
                state.lastNearMs = timeMs
                state.lastDistance = c.distance
                if (c.contact) {
                    if (!earlyBody) {
                        state.phase = GateSensorPhase.ACTIVE
                        state.lastContactMs = timeMs
                    } else {
                        if (!sameOwner || state.firstOverlapMs < 0) state.firstOverlapMs = timeMs
                        state.overlapTrack = c.detection.id
                        // One extra analysis frame rejects isolated overlap flickers.
                        if (timeMs - state.firstOverlapMs >= max(60, cfg.sampleMs)) {
                            state.phase = GateSensorPhase.ACTIVE
                            state.lastContactMs = timeMs
                        } else {
                            // A new owner never inherits the last person's ACTIVE state.
                            state.phase = GateSensorPhase.ARMED
                        }
                    }
                } else if (state.phase != GateSensorPhase.ACTIVE) {
                    state.phase = GateSensorPhase.ARMED
                    state.firstOverlapMs = -1L
                    state.overlapTrack = null
                }
            } else {
                val sinceContact = if (state.lastContactMs < 0) Long.MAX_VALUE else timeMs - state.lastContactMs
                val sinceNear = if (state.lastNearMs < 0) Long.MAX_VALUE else timeMs - state.lastNearMs
                state.phase = when {
                    state.ownerTrack != null && sinceContact <= cfg.holdMs -> GateSensorPhase.HOLD
                    sinceNear <= ARM_HYSTERESIS_MS -> GateSensorPhase.ARMED
                    else -> GateSensorPhase.OFF
                }
                if (state.phase == GateSensorPhase.OFF) {
                    state.ownerTrack = null
                    state.lastDistance = Double.POSITIVE_INFINITY
                    state.lastContactMs = -1L
                    state.firstOverlapMs = -1L
                    state.overlapTrack = null
                }
            }
            result += GateSensorDecision(gate.id, state.phase, state.ownerTrack, state.lastDistance,
                c?.contact == true || state.phase == GateSensorPhase.HOLD)
        }
        return result
    }

    /** Fraction of the person's bbox covered by the local door aperture bbox.
     * The test is deliberately on the whole body, without reading feet or knees.
     */
    private fun bodyApertureOverlap(g: FlowGate, d: FlowDetection): Double {
        if (g.aperture.size < 3) return 0.0
        val left = g.aperture.minOf { it.x }
        val right = g.aperture.maxOf { it.x }
        val top = g.aperture.minOf { it.y }
        val bottom = g.aperture.maxOf { it.y }
        val width = (minOf(d.box.right, right) - maxOf(d.box.left, left)).coerceAtLeast(0.0)
        val height = (minOf(d.box.bottom, bottom) - maxOf(d.box.top, top)).coerceAtLeast(0.0)
        return (width * height / (d.box.width * d.box.height).coerceAtLeast(0.000001)).coerceIn(0.0, 1.0)
    }

    private fun lowerBodyPoint(d: FlowDetection): FlowPoint {
        val feet = listOfNotNull(d.joint(15, 0.35), d.joint(16, 0.35))
        if (feet.isNotEmpty()) return FlowPoint(feet.map { it.x }.average(), feet.map { it.y }.average())
        val knees = listOfNotNull(d.joint(13, 0.35), d.joint(14, 0.35))
        if (knees.isNotEmpty()) {
            val x = knees.map { it.x }.average()
            val y = (knees.map { it.y }.average() + d.box.bottom) * 0.5
            return FlowPoint(x, y)
        }
        return d.box.foot
    }

    private fun lowerBodyTouchesAperture(g: FlowGate, d: FlowDetection, foot: FlowPoint): Boolean {
        if (g.containsBody(foot)) return true
        val l = g.aperture.minOf { it.x }
        val r = g.aperture.maxOf { it.x }
        val t = g.aperture.minOf { it.y }
        val b = g.aperture.maxOf { it.y }
        val lowerTop = d.box.top + d.box.height * 0.48
        return d.box.right > l && d.box.left < r && d.box.bottom > t && lowerTop < b
    }

    companion object {
        private const val ARM_HYSTERESIS_MS = 300L
    }
}
