package com.example.roomxxx0102.logic.roomalgorithm.flow

import kotlin.math.*

/** Stateful, deterministic transition engine. Image trackers never mutate the ledger. */
internal class PortalV3Core(
    private val livingId: String,
    private val living: List<FlowPoint>,
    val gates: List<FlowGate>,
    private val roomIds: List<String>,
    private val aspect: Double,
    initialCounts: Map<String, Int> = emptyMap(),
) {
    private data class Position(val t: Long, val ground: FlowGround)
    private data class Attempt(val gate: FlowGate, val from: String, val to: String, val t: Long, var frames: Int = 0, var last: Long = -1, val inferred: Boolean = false)
    private class Person(val number: Int, var track: Int, var box: FlowBox, val born: Long) {
        val estimator = FlowGroundEstimator()
        val history = ArrayDeque<Position>()
        var accepted = false
        var room: String? = null
        var lastDetection = born
        var lastStrong = -1L
        var lastLocked = -1L
        var ground: FlowGround? = null
        var lockedHits = 0
        var firstLocked = -1L
        var motion = 0.0
        val motionTrace = ArrayDeque<Pair<Long, FlowPoint>>()
        var imageTested = false
        var staticMotionTests = 0
        var lastFlow = -1L
        var detectorMissingSince = -1L
        var attempt: Attempt? = null
        var possible = emptySet<String>()
        var status = "CANDIDATE"
        var lastEvent = -100000L
        var conflict = false
        var lastAperture: Pair<String, Long>? = null
        val terminal = mutableMapOf<String, MutableSet<Int>>()
        val terminalFrames = mutableMapOf<String, MutableSet<Long>>()
        var lastTerminal = -1L
        var backgroundReturnAt = -10000L
        var originGate: String? = null
        var bootstrap = false
    }
    private val people = linkedMapOf<Int, Person>()
    private var nextPerson = 1
    private var lastTime = -1L
    private val samples = mutableMapOf<String, MutableList<FlowPoint>>()
    private val emitted = hashSetOf<String>()
    private var events = mutableListOf<FlowEvent>()
    private var observedTracks = emptySet<Int>()
    private var notes = mutableListOf<String>()

    init {
        for ((room, count) in initialCounts.filterKeys { it in roomIds }) {
            repeat(count.coerceIn(0, 20)) {
                val n = nextPerson++
                val p = Person(n, -n, FlowBox(0.0, 0.0, 0.0, 0.0), -100000)
                p.accepted = true; p.room = room; p.bootstrap = true; p.status = "INITIAL_OCCUPIED_SLOT"
                people[n] = p
            }
        }
    }

    fun profileSamples(): Map<String, List<FlowPoint>> = samples.mapValues { it.value.toList() }
    fun restoreProfiles(profiles: Map<String, List<FlowPoint>>) {
        profiles.filterKeys { key -> gates.any { key == it.id + ":in" || key == it.id + ":out" } }
            .forEach { (key, value) -> samples[key] = value.filter { it.finite() }.takeLast(12).toMutableList() }
    }

    fun step(timeMs: Long, detections: List<FlowDetection>?, flows: Map<Int, FlowEvidence> = emptyMap(), coverage: FlowBox? = null, frameHealthy: Boolean = true): FlowDecision {
        events = mutableListOf(); notes = mutableListOf()
        observedTracks = detections?.map { it.id }?.toSet() ?: emptySet()
        if (timeMs <= lastTime) return snapshot(listOf("DUPLICATE_OR_REVERSED_FRAME"))
        val gap = lastTime >= 0 && timeMs - lastTime > 500
        lastTime = timeMs
        if (gap || !frameHealthy) {
            people.values.forEach { p ->
                p.attempt = null; p.terminal.clear(); p.terminalFrames.clear(); p.history.clear()
                p.status = if (gap) "FRAME_GAP" else "FRAME_UNRELIABLE"
                if (p.accepted) p.possible = roomIds.toSet()
            }
            if (!frameHealthy) return snapshot(listOf("FRAME_UNRELIABLE_NO_TRANSITIONS"))
        }
        // No inference on an intermediate captured frame is NOT an empty detection.
        for (p in people.values.toList()) {
            val flow = flows[p.track] ?: continue
            if (flow.imageAvailable) p.imageTested = true
            if (!flow.frameHealthy || gap) continue
            p.lastFlow = timeMs
            if (flow.reliable && flow.cells >= 3) {
                p.motion += flow.delta.distance(FlowPoint(0.0, 0.0), aspect)
                p.motionTrace.add(timeMs to flow.delta)
                while (p.motionTrace.isNotEmpty() && timeMs - p.motionTrace.first().first > 1500) p.motionTrace.removeFirst()
                if (!flow.moved) p.staticMotionTests++
                val current = p.ground
                if (current != null && timeMs - p.lastStrong in 1..450) {
                    val uncertainty = current.uncertainty + 0.005 + (timeMs - p.lastStrong) * 0.000012
                    p.ground = FlowGround(current.point + flow.delta, false, uncertainty, "FLOW_PREDICTION")
                    p.box = p.box.moved(flow.delta)
                }
            }
            updateTerminalEvidence(p, flow, timeMs)
        }
        if (detections != null) {
            val activeIds = detections.map { it.id }.toSet()
            val used = hashSetOf<Int>()
            for (d in detections.filter { it.box.center.finite() && it.box.width > 0 && it.box.height > 0 }) {
                var p = people.values.firstOrNull { it.track == d.id }
                // Raw IDs are not permanent identities. Refuse discontinuous ID reuse.
                if (p != null && p.accepted && timeMs - p.lastDetection < 2000 && d.box.center.distance(p.box.center, aspect) > max(0.12, p.box.height * 0.75)) {
                    p.conflict = true; p.status = "IDENTITY_CONFLICT"; p.attempt = null
                    notes += "IDENTITY_CONFLICT:${d.id}"; continue
                }
                if (p == null) {
                    p = associateOccluded(d, timeMs, activeIds, used)
                    if (p != null) {
                        notes += "REBIND:${p.track}->${d.id}"; p.track = d.id
                    } else {
                        if (people.size >= 128) { notes += "CAPACITY_GUARD"; continue }
                        p = Person(nextPerson++, d.id, d.box, timeMs)
                        p.imageTested = flows[d.id]?.imageAvailable == true
                        people[p.number] = p
                    }
                }
                used += p.number
                p.conflict = d.shielded || detections.any { it.id != d.id && it.score >= 0.5 && d.box.iou(it.box) > 0.35 }
                p.lastDetection = timeMs; p.detectorMissingSince = -1L
                if (!p.accepted && timeMs - p.born <= 1200 && d.originGate != null) p.originGate = d.originGate
                val ground = p.estimator.measure(d, coverage)
                val previousGround = p.history.lastOrNull { it.ground.strong }
                val implausible = ground != null && previousGround != null && timeMs - previousGround.t < 500 &&
                    ground.point.distance(previousGround.ground.point, aspect) > max(0.07, d.box.height * 0.45)
                p.box = d.box
                if (ground != null && !implausible && !p.conflict) {
                    p.ground = ground
                    if (ground.strong) p.lastStrong = timeMs
                } else if (timeMs - p.lastStrong > 450) p.ground = null
                if (d.locked && d.bodyValid() && !p.conflict && ground != null && !implausible) {
                    p.lastLocked = timeMs
                    if (p.firstLocked < 0) p.firstLocked = timeMs
                    p.lockedHits++
                }
                if (ground?.strong == true && !implausible && !p.conflict) {
                    if (!p.accepted) remember(p, timeMs)
                    maybeAdmit(p, timeMs)
                    if (p.accepted) evaluateMeasured(p, timeMs)
                    if (p.accepted) remember(p, timeMs)
                }
                if (p.accepted && p.conflict) {
                    p.status = "PERSON_OVERLAP"; p.attempt = null
                }
            }
            for (p in people.values) {
                if (p.number in used) continue
                val tested = coverage == null || coverage.contains(p.box.center)
                if (tested && p.detectorMissingSince < 0) p.detectorMissingSince = timeMs
            }
        }
        for (p in people.values.toList()) {
            if (p.accepted && !p.conflict && !gap && frameHealthy) evaluateHidden(p, timeMs)
            if (p.accepted && !p.bootstrap && timeMs - p.lastDetection > 450 && p.attempt == null && p.status !in setOf("AMBIGUOUS_PORTAL", "UNKNOWN_ORIGIN")) {
                p.status = if (p.room == livingId) "OCCLUDED_LIVING" else "INSIDE_UNSEEN"
            }
            if (timeMs - p.lastStrong > 450) p.ground = null
            if (!p.accepted && timeMs - p.lastDetection > 2000) people.remove(p.number)
        }
        check(people.values.filter { it.accepted }.map { it.number }.distinct().size == people.values.count { it.accepted })
        return snapshot()
    }

    private fun maybeAdmit(p: Person, t: Long) {
        if (p.accepted || p.lockedHits < 3 || t - p.firstLocked < 200 || p.conflict) return
        val path = p.history.filter { it.ground.strong }.map { it.ground.point }
        val groundMotion = if (path.size < 3) 0.0 else path.first().distance(path.last(), aspect)
        // Existing lock + anatomically credible observations + independent pixel motion when available.
        val recentMotion = p.motionTrace.filter { t - it.first <= 1500 }.map { it.second }
        val netMotion = FlowPoint(recentMotion.sumOf { it.x }, recentMotion.sumOf { it.y }).distance(FlowPoint(0.0,0.0),aspect)
        val travel = recentMotion.sumOf { it.distance(FlowPoint(0.0,0.0),aspect) }
        val motionOk = if (p.imageTested) netMotion >= 0.005 && netMotion >= travel * 0.45 else groundMotion >= 0.012
        if (!motionOk) { p.status = "WAIT_HUMAN_MOTION"; return }
        val first = p.history.firstOrNull { it.ground.strong }?.ground?.point ?: return
        val initialRoom = locateInitial(first, p.box)
        val origin = gates.firstOrNull { it.id == p.originGate }
            ?.takeIf { initialRoom == livingId && it.distance(first) <= max(0.025, p.box.height * 0.22) }
        val firstRoom = origin?.room ?: initialRoom
        if (firstRoom == livingId && origin == null) {
            val competing = gates.filter { it.distance(first) <= max(0.03, p.box.height * 0.22) }
            val latent = people.values.filter { other -> other.accepted && other.number != p.number && t - other.lastDetection > 250 && competing.any { it.room == other.room } }
            if (latent.isNotEmpty()) {
                p.status = "UNRESOLVED_ORIGIN"
                latent.forEach { it.possible = setOfNotNull(it.room, livingId); it.status = "AMBIGUOUS_EXIT_ORIGIN" }
                return
            }
        }
        // Transfer one anonymous occupied slot, not a claim of biometric identity.
        val old = people.values.filter { it.number != p.number && it.accepted && it.track != p.track && it.track !in observedTracks && t - it.lastDetection > 250 && it.room == firstRoom }
        val reusable = old.filter { firstRoom != livingId || it.bootstrap }
        if (firstRoom != null && reusable.isNotEmpty()) {
            val slot = reusable.minBy { it.number }
            p.room = firstRoom; people.remove(slot.number)
            notes += "REUSE_ROOM_SLOT:$firstRoom:${slot.number}->${p.number}"
        } else {
            // An unexplained new ID while someone is hidden may be the same person: don't double count.
            val latentLiving = people.values.any { it.number != p.number && it.accepted && it.room == livingId && it.track !in observedTracks && t - it.lastDetection > 450 }
            if (firstRoom == livingId && latentLiving) {
                p.status = "UNRESOLVED_IDENTITY"; return
            }
            p.room = firstRoom
        }
        p.accepted = true
        if (origin != null) p.attempt = Attempt(origin, origin.room, livingId, t, inferred = true)
        p.status = if (p.room == null) "UNKNOWN_ORIGIN" else "CONFIRMED_PERSON"
        notes += "PERSON_ADMITTED:${p.number}:${p.room ?: "UNKNOWN"}"
    }

    private fun locateInitial(point: FlowPoint, box: FlowBox): String? {
        if (inPolygon(point, living)) return livingId
        val options = gates.filter { !it.isBlind && it.side(point) < -0.005 && it.along(point) in -0.1..1.1 && it.containsBody(box.center) }
        return options.map { it.room }.distinct().singleOrNull()
    }

    private fun associateOccluded(d: FlowDetection, t: Long, active: Set<Int>, used: Set<Int>): Person? {
        if (d.originGate != null) return null
        val options = people.values.filter { p ->
            p.accepted && !p.bootstrap && p.number !in used && p.track !in active && t - p.lastDetection <= 8000 &&
                (p.room == livingId || p.room == null) && d.box.iou(p.box) >= 0.35 &&
                d.box.height / p.box.height.coerceAtLeast(0.001) in 0.65..1.45
        }.sortedByDescending { it.box.iou(d.box) }
        if (options.isEmpty()) return null
        if (options.size > 1 && options[0].box.iou(d.box) - options[1].box.iou(d.box) < 0.20) return null
        return options.first()
    }

    private fun remember(p: Person, t: Long) {
        val g = p.ground ?: return
        if (p.history.lastOrNull()?.t != t) p.history.add(Position(t, g))
        while (p.history.isNotEmpty() && (t - p.history.first().t > 2200 || p.history.size > 40)) p.history.removeFirst()
    }

    private fun band(p: Person) = (p.box.height * 0.07).coerceIn(0.009, 0.035)
    private fun margin(p: Person) = max(0.004, p.ground?.uncertainty ?: 0.012)

    private fun evaluateMeasured(p: Person, t: Long) {
        val current = p.ground ?: return
        if (!current.strong || p.conflict) return
        if (p.room == null) {
            // A known person can regain a visible living-room location without inventing an origin door.
            if (inPolygon(current.point, living) && gates.none { it.distance(current.point) < band(p) * 2 }) {
                p.room = livingId; p.possible = emptySet(); p.status = "ORIGIN_UNKNOWN_NOW_LIVING"
                notes += "LOCATION_RESOLVED_ORIGIN_UNKNOWN:${p.number}"
            }
            return
        }
        val from = p.room!!
        val m = margin(p)
        val candidates = mutableListOf<Pair<FlowGate, Double>>()
        for (gate in gates) {
            if (from != livingId && from != gate.room) continue
            val entering = from == livingId
            if (entering == inPolygon(current.point, living)) continue
            val s = gate.side(current.point)
            val onTarget = if (entering) s < -m else s > m
            if (!onTarget) continue
            val pre = p.history.lastOrNull { pos ->
                pos.t < t && t - pos.t <= 1500 && pos.ground.strong &&
                    (if (entering) gate.side(pos.ground.point) > max(m, pos.ground.uncertainty) else gate.side(pos.ground.point) < -max(m, pos.ground.uncertainty))
            } ?: continue
            // A low confidence foot jumping upward inside a sofa cannot satisfy these guards.
            if (!gate.intersects(pre.ground.point, current.point)) continue
            val distance = pre.ground.point.distance(current.point, aspect)
            if (distance > max(0.20, p.box.height * 1.25)) continue
            val dx = current.point.x - pre.ground.point.x
            val dy = current.point.y - pre.ground.point.y
            val normalProgress = abs(s - gate.side(pre.ground.point)) / distance.coerceAtLeast(0.001)
            val key = gate.id + if (entering) ":in" else ":out"
            val learned = profileAgreement(key, FlowPoint(dx, dy))
            candidates += gate to (normalProgress.coerceIn(0.0, 1.0) + 0.08 * learned)
        }
        if (candidates.size > 1) {
            val ordered = candidates.sortedByDescending { it.second }
            if (ordered[0].second - ordered[1].second < 0.18) {
                p.attempt = null; p.possible = (candidates.map { it.first.room } + livingId).toSet()
                p.status = "AMBIGUOUS_PORTAL"; return
            }
        }
        val best = candidates.maxByOrNull { it.second }?.first
        val pending = p.attempt
        if (best != null) {
            val target = if (from == livingId) best.room else livingId
            if (pending == null || pending.gate.id != best.id || pending.to != target) p.attempt = Attempt(best, from, target, t)
        }
        val a = p.attempt ?: run {
            if (gates.none { it.distance(current.point) < band(p) * 2 }) p.possible = emptySet()
            return
        }
        val onTarget = if (a.to == livingId) a.gate.side(current.point) > m && inPolygon(current.point, living) else a.gate.side(current.point) < -m && !inPolygon(current.point, living)
        if (!onTarget) {
            p.attempt = null; p.possible = emptySet(); p.status = "RETURNED_WITHOUT_TRANSFER"; return
        }
        if (t - a.t > 1600) { p.attempt = null; return }
        if (a.last != t) { a.last = t; a.frames++ }
        p.possible = setOf(a.from, a.to); p.status = "CROSSING_PENDING"
        // Two independently timed observations beyond the uncertainty band; no mandatory disappearance.
        if (a.frames >= 2 && t - a.t >= 100 && t - p.lastEvent >= 250) commit(p, a, t)
    }

    private fun profileAgreement(key: String, motion: FlowPoint): Double {
        val v = samples[key] ?: return 0.0
        if (v.size < 3) return 0.0
        val direction = FlowPoint(median(v.map { it.x }), median(v.map { it.y }))
        val norm = direction.distance(FlowPoint(0.0, 0.0), aspect) * motion.distance(FlowPoint(0.0, 0.0), aspect)
        return if (norm < 1e-8) 0.0 else ((direction.x * motion.x * aspect * aspect + direction.y * motion.y) / norm).coerceIn(-1.0, 1.0)
    }

    private fun updateTerminalEvidence(p: Person, f: FlowEvidence, t: Long) {
        if (t - p.lastTerminal > 800) { p.terminal.clear(); p.terminalFrames.clear() }
        if (!p.accepted || !f.frameHealthy) return
        if (f.backgroundReturn) p.backgroundReturnAt = t
        val alive = f.live.groupBy { it.cell }.values.map { cell ->
            cell[cell.size / 2].copy(current = FlowPoint(median(cell.map { it.current!!.x }), median(cell.map { it.current!!.y })))
        }
        val aperture = gates.map { gate -> gate to alive.count { gate.containsBody(it.current!!) }.toDouble() / alive.size.coerceAtLeast(1) }
            .filter { it.second >= 0.55 }.sortedByDescending { it.second }
        if (aperture.isNotEmpty() && (aperture.size == 1 || aperture[0].second - aperture[1].second > 0.25)) p.lastAperture = aperture[0].first.id to t
        for (s in f.samples.filter { it.terminal && it.age >= 3 }) {
            val options = gates.filter { !it.isBlind && it.containsBody(s.previous) }
            if (options.size != 1) continue // One pixel cannot vote for two doors.
            val id = options.single().id
            p.terminal.getOrPut(id) { mutableSetOf() }.add(s.cell)
            p.terminalFrames.getOrPut(id) { mutableSetOf() }.add(t)
            p.lastTerminal = t
        }
    }

    private fun evaluateHidden(p: Person, t: Long) {
        if (p.detectorMissingSince < 0 || t - p.detectorMissingSince < 150 || p.room != livingId || t - p.lastEvent < 350) return
        if (t - p.lastStrong > 1200) {
            if (p.possible.isNotEmpty()) p.status = "AMBIGUOUS_PORTAL"
            return
        }
        val last = p.history.lastOrNull { it.ground.strong } ?: return
        val options = gates.filter { gate ->
            gate.along(last.ground.point) in -0.12..1.12 && gate.distance(last.ground.point) <= band(p) * 1.5
        }
        if (options.isEmpty()) {
            p.possible = emptySet(); p.status = "OCCLUDED_LIVING"; return
        }
        // Do not resolve the gate by nearest bbox. Require a recent approach of the ground support.
        val eligible = options.filter { gate ->
            val earlier = p.history.firstOrNull { it.ground.strong && t - it.t <= 1600 } ?: return@filter false
            val progress = gate.side(earlier.ground.point) - gate.side(last.ground.point)
            progress > max(0.008, p.box.height * 0.04) && gate.side(last.ground.point) >= -band(p)
        }
        p.possible = (options.map { it.room } + livingId).toSet()
        p.status = "AMBIGUOUS_PORTAL"
        if (eligible.isEmpty()) return
        val ranked = eligible.map { gate -> gate to (p.terminal[gate.id]?.size ?: 0) }.sortedByDescending { it.second }
        val gate = ranked.first().first
        val votes = ranked.first().second
        if (ranked.size > 1 && votes - ranked[1].second < 3) return
        val terminalFrames = p.terminalFrames[gate.id]?.size ?: 0
        val exclusiveBody = p.lastAperture?.let { it.first == gate.id && t - it.second <= 900 } == true
        val predictedAcross = p.ground?.let { g ->
            !g.strong && t - p.lastStrong <= 450 && gate.side(g.point) + g.uncertainty < 0.0
        } == true
        val knownContact = gate.distance(last.ground.point) <= band(p) &&
            (abs(gate.side(last.ground.point)) <= last.ground.uncertainty * 1.5 || predictedAcross)
        val returnToBackground = t - p.backgroundReturnAt <= 650
        // Sparse failure alone is never disappearance. Need background replacement at owned pixels.
        val gradual = terminalFrames >= 2 && votes >= 3
        val abruptAtContact = returnToBackground && votes >= 5 && knownContact && eligible.size == 1
        if (knownContact && exclusiveBody && (gradual || abruptAtContact) && t - p.detectorMissingSince >= 250) {
            val a = Attempt(gate, livingId, gate.room, p.detectorMissingSince, inferred = true)
            commit(p, a, t)
        }
    }

    private fun commit(p: Person, a: Attempt, t: Long) {
        if (p.room != a.from || !p.accepted || p.conflict) return
        val key = "${p.number}:${a.gate.id}:${a.from}:${a.to}:${a.t}"
        if (!emitted.add(key)) return
        if (emitted.size > 4096) emitted.clear()
        val motion = p.history.firstOrNull()?.ground?.point?.let { start -> p.ground?.point?.minus(start) }
        if (!a.inferred && motion != null && motion.finite()) {
            val profileKey = a.gate.id + if (a.to == livingId) ":out" else ":in"
            val v = samples.getOrPut(profileKey) { mutableListOf() }
            v += motion
            if (v.size > 12) v.removeAt(0)
        }
        events += FlowEvent(p.number, p.track, a.from, a.to, a.gate.id, a.t, a.inferred)
        p.room = a.to; p.possible = emptySet(); p.attempt = null
        p.status = if (a.inferred) "INFERRED_GATE_TRANSFER" else "MEASURED_GATE_TRANSFER"
        p.lastEvent = t; p.terminal.clear(); p.terminalFrames.clear(); p.history.clear()
        p.detectorMissingSince = -1L
        notes += "TRANSFER:${p.number}:${a.from}->${a.to}:${a.gate.id}:${if (a.inferred) "VISUAL_OCCLUSION" else "GROUND_CROSSING"}"
    }

    private fun snapshot(extra: List<String> = emptyList()): FlowDecision {
        val accepted = people.values.filter { it.accepted }
        val counts = roomIds.associateWith { id -> accepted.count { it.room == id } }
        val lower = roomIds.associateWith { id -> accepted.count { it.room == id && it.possible.isEmpty() } }
        val upper = roomIds.associateWith { id -> accepted.count { it.room == id || it.room == null || id in it.possible } }
        return FlowDecision(counts, lower, upper, accepted.count { it.room == null }, events.toList(), people.values.map { p ->
            FlowPersonView(p.number, p.track, p.room, p.ground, p.box, p.status, p.possible, p.accepted)
        }, (notes + extra).takeLast(12))
    }
}
