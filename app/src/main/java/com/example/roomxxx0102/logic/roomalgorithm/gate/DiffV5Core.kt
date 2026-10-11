package com.example.roomxxx0102.logic.roomalgorithm.gate

import kotlin.math.abs
import kotlin.math.max

/**
 * Experimental V5 transition ledger. It consumes portal difference evidence only;
 * it never calls the V4 ground-crossing state machine.
 *
 * This first version commits a completed portal episode with a bounded inactivity
 * wait. Event timestamps refer to the witnessed motion, not commit time.
 */
internal data class DiffV5Signal(
    val timeMs: Long,
    val gateId: String,
    val roomId: String,
    val ownerTrack: Int,
    val phase: String,
    val diffPixels: Int,
    val depth: Double?,
    val bodySide: Double?,
    val bodyAlong: Double?,
    val absorption: Double?,
    val motionPixels: Int,
)

internal data class DiffV5Transfer(
    val roomId: String,
    val gateId: String,
    val ownerTrack: Int,
    val direction: String,
    val timeMs: Long,
    val firstMs: Long,
    val lastMs: Long,
    val confidence: Double,
    val evidence: String,
)

internal class DiffV5Core(
    private val livingRoomId: String,
    private val exteriorRooms: Set<String>,
    initialCounts: Map<String, Int> = emptyMap(),
    private val gapMs: Long = 700L,
) {
    private val episodeByRoom = linkedMapOf<String, MutableList<DiffV5Signal>>()
    private val roomPopulation = initialCounts.toMutableMap().withDefault { 0 }
    private val ownerRoom = linkedMapOf<Int, String>()
    private var previousTime = -1L
    private var lastEmissions: List<DiffV5Transfer> = emptyList()

    val counts: Map<String, Int> get() = roomPopulation.toMap()
    val ownedRooms: Map<Int, String> get() = ownerRoom.toMap()
    val pendingPortalCount: Int get() = episodeByRoom.size

    fun detachIdentitySource() {
        episodeByRoom.clear()
        ownerRoom.clear()
        // Keep room population. An identity provider switch is not a person movement.
    }

    fun step(timeMs: Long, signals: List<DiffV5Signal>, frameHealthy: Boolean = true): List<DiffV5Transfer> {
        if (timeMs <= previousTime) return emptyList()
        previousTime = timeMs
        if (!frameHealthy) return emptyList()

        val completed = mutableListOf<List<DiffV5Signal>>()
        val accepted = signals.filter {
            it.ownerTrack >= 0 && it.phase in setOf("ACTIVE", "HOLD") && it.roomId != livingRoomId
        }.sortedWith(compareBy<DiffV5Signal> { it.timeMs }.thenBy { it.roomId })
        for (signal in accepted) {
            val buffer = episodeByRoom.getOrPut(signal.roomId) { mutableListOf() }
            if (buffer.isNotEmpty() && signal.timeMs - buffer.last().timeMs > gapMs) {
                completed += buffer.toList()
                buffer.clear()
            }
            buffer.add(signal)
        }
        val stale = episodeByRoom.filterValues { it.isNotEmpty() && timeMs - it.last().timeMs > gapMs }.keys.toList()
        for (roomId in stale) {
            val buffer = episodeByRoom.remove(roomId) ?: continue
            completed += buffer.toList()
        }
        lastEmissions = commitCandidates(completed)
        return lastEmissions
    }

    /** Only for a replay runner after EOF. An app must never pretend it saw future frames. */
    fun finishReplay(): List<DiffV5Transfer> {
        val completed = episodeByRoom.values.filter { it.isNotEmpty() }.map { it.toList() }
        episodeByRoom.clear()
        lastEmissions = commitCandidates(completed)
        return lastEmissions
    }

    private fun commitCandidates(completed: List<List<DiffV5Signal>>): List<DiffV5Transfer> {
        val raw = completed.filter { it.size >= 4 }
            .flatMap { splitReverse(it) }.mapNotNull { classify(it) }
            .sortedBy { it.timeMs }
        val accepted = mutableListOf<DiffV5Transfer>()
        for (candidate in raw) {
            val current = ownerRoom[candidate.ownerTrack]
            val count = roomPopulation.getValue(candidate.roomId)
            if (candidate.direction == "ENTER") {
                if (current != null && current != livingRoomId) continue
                if (candidate.lastMs - candidate.firstMs < 650 && candidate.confidence < 2.5) continue
                ownerRoom[candidate.ownerTrack] = candidate.roomId
                roomPopulation[candidate.roomId] = count + 1
                roomPopulation[livingRoomId] = max(0, roomPopulation.getValue(livingRoomId) - 1)
            } else {
                if (current != null && current != candidate.roomId && count == 0) continue
                if (current != candidate.roomId && count == 0 && candidate.roomId !in exteriorRooms) continue
                if (count > 0) roomPopulation[candidate.roomId] = count - 1
                roomPopulation[livingRoomId] = roomPopulation.getValue(livingRoomId) + 1
                ownerRoom[candidate.ownerTrack] = livingRoomId
            }
            accepted += candidate
        }
        return accepted
    }

    private fun splitReverse(episode: List<DiffV5Signal>): List<List<DiffV5Signal>> {
        if (episode.last().timeMs - episode.first().timeMs < 4500) return listOf(episode)
        val visible = episode.filter {
            it.phase == "ACTIVE" && (it.absorption ?: -1.0) >= 0.70 &&
                it.bodySide != null && it.diffPixels >= 50
        }
        if (visible.size < 24) return listOf(episode)
        val centers = (2 until visible.size - 2).map { i ->
            visible[i].timeMs to median(visible.subList(i - 2, i + 3).mapNotNull { it.bodySide })!!
        }
        val inside = centers.subList(centers.size / 5, centers.size * 4 / 5)
        if (inside.isEmpty()) return listOf(episode)
        val turn = inside.minBy { it.second }
        val before = centers.filter { it.first < turn.first - 550 }.map { it.second }
        val after = centers.filter { it.first > turn.first + 550 }.map { it.second }
        if (before.isEmpty() || after.isEmpty() ||
            median(before.take(5))!! - turn.second < 0.023 ||
            median(after.takeLast(5))!! - turn.second < 0.027) return listOf(episode)
        val left = episode.filter { it.timeMs <= turn.first }
        val right = episode.filter { it.timeMs > turn.first }
        return if (left.isNotEmpty() && right.isNotEmpty()) listOf(left, right) else listOf(episode)
    }

    private fun classify(episode: List<DiffV5Signal>): DiffV5Transfer? {
        val first = episode.first().timeMs
        val last = episode.last().timeMs
        var active = episode.filter { s ->
            s.phase == "ACTIVE" && s.bodySide != null && s.absorption != null &&
                s.absorption >= 0.28 && s.diffPixels >= 20
        }
        if (active.size < 3 || active.last().timeMs - active.first().timeMs < 200) return null
        val owner = active.groupingBy { it.ownerTrack }.eachCount().maxBy { it.value }.key
        active = active.filter { it.ownerTrack == owner }
        if (active.size < 3 || active.last().timeMs - active.first().timeMs < 200) return null
        if (active.maxOf { it.absorption ?: 0.0 } < 0.85) return null
        val earlySide = quarter(active) { it.bodySide }.first ?: return null
        val lateSide = quarter(active) { it.bodySide }.second ?: return null
        val earlyRatio = quarter(active) { it.absorption }.first ?: return null
        val lateRatio = quarter(active) { it.absorption }.second ?: return null
        val visibleDepth = episode.filter { it.ownerTrack == owner && it.depth != null && it.diffPixels >= 25 }
        val (earlyDepth, lateDepth) = quarter(visibleDepth) { it.depth }
        val side = lateSide - earlySide
        val ratio = lateRatio - earlyRatio
        val depth = if (earlyDepth != null && lateDepth != null) lateDepth - earlyDepth else 0.0
        val inward = max(0.0, -side / 0.025) + max(0.0, ratio / 0.25) + max(0.0, depth / 0.12)
        val outward = max(0.0, side / 0.025) + max(0.0, -ratio / 0.25) + max(0.0, -depth / 0.12)
        var inwardTrusted = (side <= -0.012 && lateRatio >= 0.72) ||
            (ratio >= 0.22 && lateRatio >= 0.85 && active.size >= 4) ||
            (side <= -0.008 && lateRatio >= 0.96 && active.size >= 6)
        val (earlyAlong, lateAlong) = quarter(active) { it.bodyAlong }
        val lateral = earlyAlong != null && lateAlong != null && abs(lateAlong - earlyAlong) >= 0.20
        val outwardTrusted = (side >= 0.014 && earlyRatio >= 0.65) ||
            (ratio <= -0.15 && earlyRatio >= 0.70 && (side >= 0.002 || lateral || lateRatio <= 0.70)) ||
            (depth <= -0.12 && ratio <= -0.05 && earlyRatio >= 0.72)
        val stronglyAbsorbed = active.filter { (it.absorption ?: 0.0) >= 0.90 }
        val depthRange = if (visibleDepth.isNotEmpty()) {
            visibleDepth.maxOf { it.depth!! } - visibleDepth.minOf { it.depth!! }
        } else 0.0
        val significantMotion = active.count {
            it.motionPixels >= max(120.0, it.diffPixels * 0.20)
        } >= 3
        val absorbedThenHidden = stronglyAbsorbed.size >= 7 &&
            stronglyAbsorbed.last().timeMs - stronglyAbsorbed.first().timeMs >= 650 &&
            lateRatio >= 0.88 && last - active.last().timeMs >= 450 &&
            significantMotion && depthRange >= 0.065 && episode.any { it.phase == "HOLD" }
        if (absorbedThenHidden) inwardTrusted = true
        if (!inwardTrusted && !outwardTrusted) return null
        val direction = if (inwardTrusted && outwardTrusted) {
            if (inward >= outward) "ENTER" else "EXIT"
        } else if (inwardTrusted) "ENTER" else "EXIT"
        val tangential = earlyAlong != null && lateAlong != null &&
            lateAlong - earlyAlong >= 0.27 && side >= -0.008
        if (direction == "ENTER" && (active.size < 4 || tangential)) return null
        var point = active[active.size / 2].timeMs
        if (last - first > 2500) point = active[max(1, active.size * 2 / 5)].timeMs
        if (direction == "ENTER" && side <= -0.018 && active.size >= 12) {
            val half = earlySide + side * 0.50
            val cross = active.drop(active.size / 5).firstOrNull { (it.bodySide ?: Double.POSITIVE_INFINITY) <= half }
            if (cross != null && cross.timeMs > point) point = cross.timeMs
        }
        val gateId = active.groupingBy { it.gateId }.eachCount().maxBy { it.value }.key
        return DiffV5Transfer(episode.first().roomId, gateId, owner, direction, point,
            first, last, max(inward, outward), "side=%+.3f ratio=%+.2f depth=%+.2f".format(side,ratio,depth))
    }

    private fun quarter(samples: List<DiffV5Signal>, value: (DiffV5Signal) -> Double?): Pair<Double?, Double?> {
        if (samples.isEmpty()) return null to null
        val n = max(2, samples.size / 4)
        return median(samples.take(n).mapNotNull(value)) to median(samples.takeLast(n).mapNotNull(value))
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}