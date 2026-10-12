package com.example.roomxxx0102.logic.roomalgorithm.gate

import kotlin.math.abs
import kotlin.math.max

/**
 * V5.3 rolling local-trajectory crossing detector; do not equate observation onset to crossing.
 *
 * Unlike V5.0, a positive witness is queued immediately and delivered after a
 * short causal confirmation interval. The event timestamp is the actual step
 * that publishes it; it is NEVER backdated to an episode midpoint.
 *
 * Ground/keypoint crossing, V4 WAIT_CLEAR and human annotations are not inputs.
 */
internal class DiffV53Core(
    private val livingRoomId: String,
    private val exteriorRooms: Set<String>,
    initialCounts: Map<String, Int> = emptyMap(),
    private val confirmationMs: Long = 120L,
) {
    private data class Episode(
        val samples: MutableList<DiffV5Signal> = mutableListOf(),
        var lastMs: Long = -1,
        var lastActiveMs: Long = -1,
        var holdSinceMs: Long = -1,
        var commits: Int = 0,
        var previousDirection: String = "",
        var previousCommitMs: Long = -1,
    )
    private data class Candidate(
        val event: DiffV5Transfer,
        val observedAt: Long,
        val delayMs: Long,
    )
    private val episodes = linkedMapOf<String, Episode>()
    private val pending = mutableListOf<Candidate>()
    private val population = initialCounts.toMutableMap().withDefault { 0 }
    private val owners = linkedMapOf<Int, String>()
    private val publishedPopulation = initialCounts.toMutableMap().withDefault { 0 }
    private val publishedOwners = linkedMapOf<Int, String>()
    private val recentlyDelivered = mutableListOf<DiffV5Transfer>()
    private var previousTime = -1L

    val counts: Map<String, Int> get() = publishedPopulation.toMap()
    val ownedRooms: Map<Int, String> get() = publishedOwners.toMap()
    val pendingPortalCount: Int get() = episodes.size + pending.size

    fun detachIdentitySource() {
        episodes.clear()
        pending.clear()
        owners.clear()
        publishedOwners.clear()
        recentlyDelivered.clear()
        // A tracker restart is not a room transition; preserve population.
    }

    fun step(
        timeMs: Long,
        signals: List<DiffV5Signal>,
        frameHealthy: Boolean = true,
    ): List<DiffV5Transfer> {
        if (timeMs <= previousTime) return emptyList()
        previousTime = timeMs
        if (!frameHealthy) return emptyList()

        val emitted = mutableListOf<DiffV5Transfer>()
        // Commit after the confirmation deadline, not when the original evidence
        // was seen. Recorded time therefore equals the real algorithm response.
        fun releaseDue() {
            val due = pending.filter { timeMs >= it.observedAt + it.delayMs }
                .sortedBy { it.observedAt }
            pending.removeAll(due.toSet())
            for (item in due) {
                val actual = item.event.copy(timeMs = timeMs)
                publish(actual)
                emitted += actual
            }
        }
        releaseDue()
        val touched = mutableSetOf<String>()
        val valid = signals.filter { it.ownerTrack >= 0 &&
            it.phase in setOf("ACTIVE", "HOLD") && it.roomId != livingRoomId }
            .sortedWith(compareBy<DiffV5Signal> { it.timeMs }.thenBy { it.roomId })
        for (s in valid) {
            touched += s.roomId
            var ep = episodes.getOrPut(s.roomId) { Episode() }
            if (ep.samples.isNotEmpty() && timeMs - ep.samples.last().timeMs > 850) {
                ep = Episode()
                episodes[s.roomId] = ep
            }
            ep.samples += s
            ep.lastMs = timeMs
            if (s.phase == "ACTIVE") {
                ep.holdSinceMs = -1
                if (s.bodySide != null && s.absorption != null &&
                    s.absorption >= 0.28 && s.diffPixels >= 20) ep.lastActiveMs = timeMs
            } else if (ep.holdSinceMs < 0) ep.holdSinceMs = timeMs
            propose(ep, timeMs)?.let { enqueue(ep, it, timeMs) }
            // Long stationary occupancy must not make a permanently growing list.
            if (ep.samples.size > 160) ep.samples.subList(0, ep.samples.size - 160).clear()
        }
        for ((room, ep) in episodes.toMap()) {
            if (room in touched) continue
            if (timeMs - ep.lastMs > 2000) {
                episodes.remove(room)
            } else propose(ep, timeMs)?.let { enqueue(ep, it, timeMs) }
        }
        releaseDue()
        recentlyDelivered.removeAll { timeMs - it.timeMs > 1600 }
        return emitted
    }

    /**
     * The gate may have started watching two seconds before the actual crossing.
     * An episode-wide side range is therefore only a candidate: release it only
     * after a NEW local side, occupancy or depth-frontier transition.
     *
     * The time windows are measured from the current frame (causal), never from
     * the first ARMED or ACTIVE frame and never from a ground-truth annotation.
     */
    private fun corroborates(ep: Episode, witness: DiffV5Transfer, now: Long): Boolean {
        val active = ep.samples.filter { it.ownerTrack == witness.ownerTrack &&
            it.phase == "ACTIVE" && it.bodySide != null && it.absorption != null &&
            it.absorption >= .28 && it.diffPixels >= 20 && now - it.timeMs <= 1150L }
        if (active.size < 3) return false
        val last = active.last()
        val recent = active.filter { now - it.timeMs <= 180L }
        val prior = active.filter { now - it.timeMs in 240L..640L }
        fun med(source: List<DiffV5Signal>, f: (DiffV5Signal) -> Double?): Double? =
            nullableMedian(source.mapNotNull(f))
        fun delta(f: (DiffV5Signal) -> Double?): Double? {
            val newer = med(recent, f) ?: return null
            val older = med(prior, f) ?: return null
            return newer - older
        }
        val newRatio = med(recent) { it.absorption }
        val ds = delta { it.bodySide }
        val dr = delta { it.absorption }
        val da = delta { it.bodyAlong }
        // Depth rows of the 8x4 mask are *image-relative*, not metric depth.
        // Use the spatial digest only as corroboration, never as an independent
        // proof that a human crossed an actual 3D plane.
        fun gridDepth(grid: List<Int>): Double? {
            if (grid.size != 32) return null
            val weighted = grid.indices.sumOf { (it / 4 + .5) * grid[it].toDouble() }
            val weight = grid.sumOf { it.toLong() }
            return if (weight >= 8L) weighted / (weight * 8.0) else null
        }
        val gridChange = delta { gridDepth(it.ownedGrid) }
        val reason = witness.evidence.substringBefore(' ')
        if (witness.direction == "ENTER") {
            when (reason) {
                "moving-inside", "saturated-inward", "sustained-absorption" -> {
                    val sideAndPixels = ds != null && ds <= -.011 &&
                        (dr == null || dr >= -.10)
                    val spatialFrontier = gridChange != null && gridChange >= .065 &&
                        ds != null && ds <= -.005 && (dr == null || dr >= -.03)
                    return recent.size >= 2 && newRatio != null && newRatio >= .987 &&
                        (sideAndPixels || spatialFrontier)
                }
                "absorbed-disappearance" -> {
                    val age = now - last.timeMs
                    val hold = ep.samples.filter { it.phase == "HOLD" &&
                        it.timeMs > last.timeMs && now - it.timeMs < 680L }
                    return age in 360L..900L && hold.size >= 2 &&
                        hold.take(5).any { it.motionPixels >= 500 }
                }
                "short-occlusion-entry", "inward-occlusion-trajectory" ->
                    return now - last.timeMs >= 300L
                else -> return recent.size >= 2 && newRatio != null && newRatio >= .965 &&
                    ((ds != null && ds <= -.009) || (da != null && abs(da) >= .20))
            }
        }
        val visibleExit = dr != null && dr <= -.022 &&
            ((ds != null && ds >= .006) || (da != null && abs(da) >= .10))
        val outwardMovement = ds != null && ds >= .020 && (dr == null || dr <= .03)
        val spatialExit = gridChange != null && gridChange <= -.065 &&
            dr != null && dr <= -.08 && ds != null && ds >= .003
        return visibleExit || outwardMovement || spatialExit
    }

    private fun enqueue(ep: Episode, witness: DiffV5Transfer, now: Long) {
        if (pending.any { it.event.roomId == witness.roomId && it.event.ownerTrack == witness.ownerTrack }) return
        // Competing gates are mutually exclusive only while observing the same instant,
        // not for an arbitrary 950ms after a physically independent EXIT/ENTER pair.
        if (pending.any { it.event.ownerTrack == witness.ownerTrack && it.event.roomId != witness.roomId &&
                abs(it.observedAt - now) < 220 }) return
        // A provisional crossing reserves its own episode, so subsequent active
        // frames cannot enqueue the same event repeatedly during confirmation.
        val delay = when (witness.evidence.substringBefore(' ')) {
            "leaving-aperture" -> max(180, confirmationMs)
            "saturated-inward" -> max(170, confirmationMs)
            "moving-inside" -> max(100, confirmationMs)
            "aperture-transfer", "turning-away" -> max(120, confirmationMs)
            // Brief active evidence makes disappearing-body timestamps uncertain.
            "absorbed-disappearance" -> if (witness.lastMs - witness.firstMs < 650L)
                max(130L, confirmationMs) else 0L
            else -> 0L
        }
        // Reserve the transition for subsequent proposals, but don't publish the
        // room count or event until the confirmation deadline has elapsed.
        if (!corroborates(ep, witness, now) || !admit(witness, ep)) return
        applyWorking(witness)
        ep.commits++
        ep.previousDirection = witness.direction
        ep.previousCommitMs = now
        pending += Candidate(witness, now, delay)
    }

    private fun admit(e: DiffV5Transfer, ep: Episode): Boolean {
        if (recentlyDelivered.any { it.ownerTrack == e.ownerTrack && it.roomId != e.roomId &&
                abs(it.timeMs - previousTime) < 220 }) return false
        val current = owners[e.ownerTrack]
        val number = population.getValue(e.roomId)
        if (e.direction == "ENTER") {
            if (current == e.roomId) return false
            if (current != null && current != livingRoomId && e.confidence < 3.0) return false
        } else {
            if (current != null && current != livingRoomId && current != e.roomId && number == 0) return false
            if (e.roomId !in exteriorRooms && current != e.roomId && number == 0 && e.confidence < 3.5) {
                // Strong independently observed EXITs can correct a missed earlier ENTER,
                // but a weak disappearance may not invent a synthetic room transition.
                val visible = ep.samples.filter { it.ownerTrack == e.ownerTrack &&
                    it.phase == "ACTIVE" && it.bodySide != null && it.absorption != null &&
                    it.absorption >= .28 && it.diffPixels >= 20 }
                val strongVisualExit = e.evidence.startsWith("moving-outside ") &&
                    e.confidence >= 2.65 && visible.size >= 5 && run {
                    val first = visible.take(3)
                    val last = visible.takeLast(3)
                    median(last.map { it.bodySide!! }) - median(first.map { it.bodySide!! }) >= .030 &&
                        median(first.map { it.absorption!! }) - median(last.map { it.absorption!! }) >= .32
                }
                if (!strongVisualExit) return false
            }
        }
        return true
    }

    private fun applyWorking(e: DiffV5Transfer) {
        if (e.direction == "ENTER") {
            population[e.roomId] = population.getValue(e.roomId) + 1
            population[livingRoomId] = max(0, population.getValue(livingRoomId) - 1)
            owners[e.ownerTrack] = e.roomId
        } else {
            population[e.roomId] = max(0, population.getValue(e.roomId) - 1)
            population[livingRoomId] = population.getValue(livingRoomId) + 1
            owners[e.ownerTrack] = livingRoomId
        }
        recentlyDelivered += e
    }

    private fun publish(e: DiffV5Transfer) {
        if (e.direction == "ENTER") {
            publishedPopulation[e.roomId] = publishedPopulation.getValue(e.roomId) + 1
            publishedPopulation[livingRoomId] = max(0, publishedPopulation.getValue(livingRoomId) - 1)
            publishedOwners[e.ownerTrack] = e.roomId
        } else {
            publishedPopulation[e.roomId] = max(0, publishedPopulation.getValue(e.roomId) - 1)
            publishedPopulation[livingRoomId] = publishedPopulation.getValue(livingRoomId) + 1
            publishedOwners[e.ownerTrack] = livingRoomId
        }
    }

    private fun propose(ep: Episode, now: Long): DiffV5Transfer? {
        if (ep.commits >= 2 || ep.commits > 0 && now - ep.previousCommitMs < 1200) return null
        var active = ep.samples.filter { it.timeMs >= now - 1150L && it.phase == "ACTIVE" && it.bodySide != null &&
            it.absorption != null && it.absorption >= 0.28 && it.diffPixels >= 20 }
        if (active.size < 3) return null
        val owner = active.groupingBy { it.ownerTrack }.eachCount().maxByOrNull { it.value }!!.key
        // Track IDs can restart while a person crosses a gate. Keep a short,
        // sequential hand-off only when exactly one other ID succeeds the
        // original; never fuse alternating IDs or simultaneous users.
        val ownerChanges = active.zipWithNext().count { it.first.ownerTrack != it.second.ownerTrack }
        val tracks = active.map { it.ownerTrack }.distinct()
        val joinedHandoff = tracks.size == 2 && ownerChanges == 1 &&
            active.count { it.ownerTrack != owner } <= 2 &&
            active.zipWithNext().all { it.second.timeMs - it.first.timeMs <= 300 }
        if (!joinedHandoff) active = active.filter { it.ownerTrack == owner }
        if (ep.commits > 0) active = active.filter { it.timeMs > ep.previousCommitMs + 200 }
        if (active.size < 3 || now - active.last().timeMs > 700) return null
        val firstAt = active.first().timeMs
        val lastAt = active.last().timeMs
        val first = active.take(3)
        val last = active.takeLast(3)
        val firstSide = median(first.map { it.bodySide!! })
        val currentSide = median(last.map { it.bodySide!! })
        val lo = active.minOf { it.bodySide!! }
        val hi = active.maxOf { it.bodySide!! }
        val firstRatio = median(first.map { it.absorption!! })
        val lastRatio = median(last.map { it.absorption!! })
        val peakRatio = active.maxOf { it.absorption!! }
        val firstAlong = nullableMedian(first.mapNotNull { it.bodyAlong })
        val lastAlong = nullableMedian(last.mapNotNull { it.bodyAlong })
        val along = active.mapNotNull { it.bodyAlong }
        val lowAlong = along.minOrNull()
        val highAlong = along.maxOrNull()
        val positive = currentSide - active.dropLast(1).minOf { it.bodySide!! }
        val negative = active.dropLast(1).maxOf { it.bodySide!! } - currentSide
        val duration = now - firstAt
        var direction = ""
        var reason = ""
        var strength = 0.0
        if (positive >= .032 && currentSide - firstSide >= .020 && duration >= 400 &&
            peakRatio >= .74 && (firstRatio - lastRatio >= .025 || positive >= .045)) {
            direction = "EXIT"; strength = positive / .017; reason = "moving-outside"
        }
        if (negative >= .032 && firstSide - currentSide >= .016 && duration >= 720 &&
            lastRatio >= .82 && !(firstAlong != null && lastAlong != null && lastAlong - firstAlong > .72)) {
            if (direction.isEmpty() || negative > positive * 1.3) {
                direction = "ENTER"; strength = negative / .016; reason = "moving-inside"
            }
        }
        if (direction.isEmpty() && firstAlong != null && lastAlong != null &&
            firstAlong >= .70 && lastAlong <= firstAlong - .30 && lastRatio >= .97 &&
            firstRatio >= .68 && lastRatio - firstRatio >= .08 && duration >= 550 && firstSide - currentSide >= .012) {
            direction = "ENTER"; strength = 2.7; reason = "aperture-transfer"
        }
        if (direction.isEmpty() && firstAlong != null && lastAlong != null && highAlong != null &&
            firstAlong >= .65 && highAlong >= .92 && highAlong - lastAlong >= .11 &&
            currentSide - firstSide >= .014 && peakRatio >= .87 && duration >= 900) {
            direction = "EXIT"; strength = 2.7; reason = "turning-away"
        }
        if (direction.isEmpty() && positive >= .018 && currentSide - firstSide >= .014 &&
            firstRatio - lastRatio >= .040 && duration >= 700 && firstRatio >= .90) {
            direction = "EXIT"; strength = positive / .014; reason = "leaving-aperture"
        }
        if (direction.isEmpty() && firstRatio - lastRatio >= .35 && lastRatio <= .65 &&
            (currentSide - firstSide >= .005 || positive >= .012 || firstAlong != null && lastAlong != null && firstAlong - lastAlong >= .25) && duration >= 300 && (currentSide-firstSide >= .002 || positive>=.012)) {
            direction = "EXIT"; strength = (firstRatio - lastRatio) * 5; reason = "clearing-aperture"
        }
        // A hand-off of the track ID near the end of the crossing can
        // truncate a falling foreground ratio. Combine sustained clearing
        // with transverse movement, without guessing an event from ID switch.
        if (direction.isEmpty() && joinedHandoff && firstRatio-lastRatio >= .24 &&
            firstAlong != null && lastAlong != null && firstAlong-lastAlong >= .25 &&
            currentSide-firstSide >= 0.0 && lastRatio <= .65 && duration >= 300) {
            direction = "EXIT"; strength = 2.8; reason = "continuous-track-clearing"
        }
        if (direction.isEmpty() && negative >= .033 && lastRatio >= .94 && duration >= 850 &&
            peakRatio >= .95 && (firstSide - currentSide >= .010 || lastRatio >= .98)) {
            direction = "ENTER"; strength = negative / .008; reason = "saturated-inward"
        }
        if (direction.isEmpty() && ep.commits == 0 && ep.holdSinceMs >= 0 && ep.lastActiveMs >= 0 &&
            now - ep.lastActiveMs >= (if (lastAt-firstAt >= 1500) 290L else 410L) && peakRatio >= .88 && lastRatio >= .78 &&
            active.size >= 4 && lastAt - firstAt >= 240) {
            val moving = hi - lo >= .012 || (highAlong != null && lowAlong != null && highAlong - lowAlong >= .16)
            val fromFarSide = active.first().bodyAlong?.let { start ->
                start > 1.0 && active.last().bodyAlong?.let { end -> start - end >= .30 } == true
            } == true
            val persistentlyOwned = lastRatio >= .92 && peakRatio >= .95 && lastAt - firstAt >= 350 &&
                active.count { it.motionPixels >= 300 } >= 2
            val notLeavingAgain = active.last().absorption?.let { peakRatio - it <= .18 } == true
            if (moving && notLeavingAgain && (fromFarSide || persistentlyOwned)) {
                direction = "ENTER"
                strength = if (fromFarSide) 2.9 else 2.5
                reason = "absorbed-disappearance"
            }
        }
        if (direction.isEmpty() && ep.commits == 0 && duration >= 800 && lastRatio >= .965 &&
            firstRatio >= .975 && peakRatio >= .985 && active.size >= 4 &&
            lastAt - firstAt >= 650 && now - lastAt <= 200) {
            if (hi - lo <= .040 && abs(currentSide-firstSide) <= .024 && highAlong != null && lowAlong != null &&
                highAlong - lowAlong >= .10 && active.count { it.motionPixels >= 300 } >= 2) {
                direction = "ENTER"; strength = 2.8; reason = "sustained-absorption"
            }
        }
        // V5.2: after 3-4 valid ACTIVE samples the detector may disappear while
        // the local image continues to show strong motion. Process that HOLD
        // evidence without requiring an arbitrary number of YOLO observations.
        if (direction.isEmpty() && ep.commits == 0 && ep.holdSinceMs >= 0 &&
            ep.lastActiveMs >= 0 && active.size in 3..5 &&
            lastAt - firstAt >= 150L && now - lastAt in 50L..750L &&
            peakRatio >= .94 && lastRatio >= .90 &&
            active.count { it.motionPixels >= max(350.0, it.diffPixels * .65) } >= 2) {
            val firstHold = ep.samples.firstOrNull { it.phase == "HOLD" && it.timeMs > lastAt }
            val startAlong = active.first().bodyAlong
            val endAlong = active.last().bodyAlong
            val holdMotion = firstHold != null &&
                firstHold.motionPixels >= max(500.0, active.last().diffPixels * .50)
            if (holdMotion && startAlong != null && endAlong != null) {
                if (lastAt - firstAt <= 420L && startAlong in .34.. .89 &&
                    endAlong - startAlong >= .035 &&
                    active.last().bodySide!! <= active.first().bodySide!! + .008 &&
                    firstRatio >= .92) {
                    direction = "ENTER"; strength = 2.8; reason = "short-occlusion-entry"
                } else if (firstHold!!.bodySide == null && firstHold.absorption == null &&
                    startAlong >= 1.0 && startAlong - endAlong >= .19 &&
                    active.first().bodySide!! - active.last().bodySide!! >= .012 &&
                    now - lastAt >= 400L && lastRatio >= .87) {
                    direction = "ENTER"; strength = 3.1; reason = "inward-occlusion-trajectory"
                }
            }
        }
        if (direction.isEmpty()) return null
        val expected = when (ep.previousDirection) { "ENTER" -> "EXIT"; "EXIT" -> "ENTER"; else -> null }
        if (expected != null && direction != expected) return null
        if (direction == "EXIT" && ep.commits == 0 && duration < 380) return null
        val gateId = active.groupingBy { it.gateId }.eachCount().maxByOrNull { it.value }!!.key
        // Time here is the evidence time only; the actual output frame is set
        // in releaseDue(). No retrospective T is inserted into the result.
        return DiffV5Transfer(active.first().roomId, gateId, owner, direction,
            now, firstAt, lastAt, strength, "$reason side=${"%.3f".format(currentSide-firstSide)} " +
                "ratio=${"%.3f".format(lastRatio-firstRatio)}")
    }

    private fun median(v: List<Double>): Double {
        val sorted = v.sorted()
        return if (sorted.size % 2 != 0) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    private fun nullableMedian(v: List<Double>): Double? = if (v.isEmpty()) null else median(v)
}