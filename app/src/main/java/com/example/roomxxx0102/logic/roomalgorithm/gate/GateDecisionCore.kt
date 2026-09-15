package com.example.roomxxx0102.logic.roomalgorithm.gate

import kotlin.math.*

/** One ordered owner of all room/occupancy state. Sensors can suggest, never mutate counts. */
internal class GateDecisionCore(
    private val livingId: String,
    private val living: List<GP>,
    val gates: List<Gate>,
    private val rooms: List<String>,
    private val aspect: Double,
    private val params: GateParams,
    initial: Map<String, Int> = emptyMap(),
    private val requireFlowSupport: Boolean = false,
) {
    private data class Measured(val time: Long, val ground: Ground)
    private data class Crossing(val gate: Gate, val from: String, val to: String, val start: Long,
                                var hits: Int = 0, var last: Long = -1, val inferred: Boolean = false)
    private data class Contact(val gate: Gate, var time: Long, var cells: Int, var restoredAt: Long = -1,
                               var restoredHits: Int = 0, var restoredLast: Long = -1, var flowAgreement: Boolean = false)
    private class Person(val number: Int, var track: Int, var box: GB, val born: Long, confidence: Double) {
        val estimator = GroundEstimator(confidence)
        val history = ArrayDeque<Measured>()
        val contacts = linkedMapOf<String, Contact>()
        var ground: Ground? = null
        var lastGround = -1L
        var lastSeen = born
        var firstLocked = -1L
        var lockedHits = 0
        var room: String? = null
        var accepted = false
        var anonymous = false
        var status = "CANDIDATE"
        var possible = emptySet<String>()
        var crossing: Crossing? = null
        var conflict = false
        var absentSince = -1L
        var lastEvent = -10000L
        var originGate: String? = null
        var originTime = -1L
    }
    private val persons = linkedMapOf<Int, Person>()
    private var next = 1
    private var clock = -1L
    private var events = mutableListOf<GateEvent>()
    private var notes = mutableListOf<String>()
    private val exterior = gates.filter { it.exterior }.map { it.room }.toSet()

    init {
        initial.filterKeys { it in rooms && it !in exterior }.forEach { (room, count) ->
            repeat(count.coerceIn(0, 20)) {
                val id = next++
                persons[id] = Person(id, -id, GB(0.0,0.0,0.0,0.0), -100000L, params.footConfidence).apply {
                    accepted = true; anonymous = true; this.room = room; status = "INITIAL_SLOT"
                }
            }
        }
    }

    fun invalidateVisual() { persons.values.forEach { it.contacts.clear() } }

    fun detachIdentities(): GateDecision {
        persons.entries.removeAll { !it.value.accepted }
        persons.values.forEach { p ->
            p.track = -p.number; p.anonymous = true; p.lastSeen = -100000L
            p.history.clear(); p.contacts.clear(); p.ground = null; p.lastGround = -1
            p.crossing = null; p.absentSince = -1; p.conflict = false; p.status = "ID_BACKEND_CHANGED_COUNTS_RETAINED"
        }
        events.clear(); notes = mutableListOf("ID_BACKEND_CHANGED_COUNTS_RETAINED")
        return snapshot()
    }

    fun step(time: Long, detections: List<GDetection>?, vision: GateVisualBatch = GateVisualBatch(time), coverage: GB? = null): GateDecision {
        events = mutableListOf(); notes = mutableListOf()
        if (time <= clock) { notes += "STALE_OR_DUPLICATE_FRAME"; return snapshot() }
        val gap = clock >= 0 && time - clock > params.frameGapMs
        clock = time
        if (gap || !vision.healthy) {
            persons.values.forEach { p ->
                p.history.clear(); p.contacts.clear(); p.crossing = null; p.ground = null; p.lastGround = -1
                p.status = if (gap) "FRAME_GAP_NO_VANISH_EVENT" else "IMAGE_CHANGED_NO_EVENT"
            }
            notes += if (gap) "FRAME_GAP" else "IMAGE_UNRELIABLE"
        }
        // null means no successful detector observation, NOT an empty detection.
        if (detections == null) {
            notes += "DETECTOR_NOT_OBSERVED"
            return snapshot()
        }
        val ds = detections.filter { it.box.valid() && it.score.isFinite() }
        val currentIds = ds.map { it.track }.toSet()
        val used = hashSetOf<Int>()
        for (d in ds) {
            var p = persons.values.firstOrNull { it.track == d.track }
            if (p != null && p.accepted && time - p.lastSeen < 2000 &&
                d.box.center.distance(p.box.center, aspect) > max(0.16, p.box.h * 0.8)) {
                // Reject a discontinuous external ID, while retaining its real occupancy slot.
                p.track = -p.number; p.anonymous = false; p.contacts.clear(); p.crossing = null
                p.status = "ID_JUMP_LOCATION_RETAINED"; p.possible = setOfNotNull(p.room)
                p = null
                notes += "ID_JUMP:${d.track}"
            }
            if (p == null) {
                val matches = persons.values.filter { old -> old.accepted && !old.anonymous && old.number !in used &&
                    old.track !in currentIds && time - old.lastSeen in 1..6000 && old.room == livingId &&
                    old.box.iou(d.box) >= 0.40 && d.box.h / old.box.h.coerceAtLeast(1e-5) in 0.70..1.40 }
                    .sortedByDescending { it.box.iou(d.box) }
                p = if (matches.isNotEmpty() && (matches.size == 1 || matches[0].box.iou(d.box) - matches[1].box.iou(d.box) > 0.20)) {
                    matches.first().also { it.track = d.track; it.status = "REBOUND_SAME_PERSON"; notes += "REBIND:${it.number}:${d.track}" }
                } else {
                    if (persons.size >= 128) { notes += "PERSON_CAPACITY_GUARD"; continue }
                    Person(next++, d.track, d.box, time, params.footConfidence).also { persons[it.number] = it }
                }
            }
            used += p.number
            p.lastSeen = time; p.absentSince = -1
            p.conflict = d.shielded || ds.any { it.track != d.track && it.score >= 0.45 && it.box.iou(d.box) > 0.32 }
            val candidate = p.estimator.measure(d, coverage)
            val before = p.history.lastOrNull()
            val jump = candidate != null && before != null && time - before.time < 500 &&
                candidate.p.distance(before.ground.p, aspect) > max(0.07, d.box.h * 0.50)
            p.box = d.box
            if (candidate != null && !p.conflict && !jump) {
                p.ground = candidate
                if (candidate.measured) p.lastGround = time
            } else if (time - p.lastGround > params.frameGapMs) p.ground = null
            if (d.locked && d.credible() && !p.conflict && !jump) {
                if (p.firstLocked < 0) p.firstLocked = time
                p.lockedHits++
            } else if (!p.accepted) { p.firstLocked = -1; p.lockedHits = 0 }
            if (!p.accepted && time - p.born <= 1000) {
                val origin = vision.evidence.filter { it.track == d.track && it.origin && it.valid && !it.ambiguous }
                    .map { it.gate }.distinct().singleOrNull()
                if (origin != null) { p.originGate = origin; p.originTime = time }
            }
            if (!p.conflict && !jump && candidate?.measured == true && vision.healthy) {
                if (!p.accepted) remember(p, time)
                admit(p, d, time, currentIds)
                if (p.accepted && !gap) measuredCrossing(p, time)
                remember(p, time)
            }
            if (p.conflict) {
                p.contacts.clear(); p.crossing = null; p.status = "PERSON_OVERLAP_NO_TRANSFER"
            }
        }
        for (p in persons.values.toList()) {
            if (p.number !in used && p.accepted) {
                val area = p.box.w * p.box.h
                val covered = coverage == null || (area > 0 && coverage.intersection(p.box) >= area * 0.80 && coverage.contains(p.box.foot))
                if (covered) { if (p.absentSince < 0) p.absentSince = time } else {
                    p.absentSince = -1; p.status = "ROI_NOT_OBSERVED"; p.contacts.clear()
                }
            }
            if (p.accepted && !p.conflict && !gap && vision.healthy) {
                visualContacts(p, vision.evidence.filter { it.track == p.track }, time)
                hiddenCrossing(p, time)
            }
            if (!p.accepted && time - p.lastSeen > 1800) persons.remove(p.number)
            if (p.accepted && !p.anonymous && time - p.lastSeen > 400 && p.crossing == null && p.contacts.isEmpty() && p.possible.isEmpty()) {
                p.status = if (p.room == livingId) "OCCLUDED_LIVING" else "INSIDE_UNSEEN"
            }
            if (time - p.lastGround > params.frameGapMs) p.ground = null
        }
        return snapshot()
    }

    private fun remember(p: Person, time: Long) {
        val g = p.ground?.takeIf { it.measured && p.lastGround == time } ?: return
        if (p.history.lastOrNull()?.time != time) p.history.add(Measured(time, g))
        while (p.history.size > 40 || (p.history.isNotEmpty() && time - p.history.first().time > 1800)) p.history.removeFirst()
    }

    private fun locate(g: GP, body: GB): String? {
        if (polygonContains(g, living)) return livingId
        return gates.filter { !it.blind && it.side(g) < -0.005 && it.along(g) in 0.0..1.0 && it.contains(body.center) }
            .map { it.room }.distinct().singleOrNull()
    }

    private fun admit(p: Person, d: GDetection, time: Long, active: Set<Int>) {
        if (p.accepted || !d.locked || !d.credible() || p.conflict || p.lockedHits < 3 || time - p.firstLocked < params.admissionMs) return
        val path = p.history.filter { it.ground.measured }
        if (path.size < 3) return
        val travel = path.first().ground.p.distance(path.last().ground.p, aspect)
        // Original Tracker already demands real motion to lock. Do not require optical flow far from doors.
        val pathLength = path.zipWithNext().sumOf { (a,b) -> a.ground.p.distance(b.ground.p, aspect) }
        if (travel < max(0.006, p.box.h * 0.025) || travel < pathLength * 0.45) {
            p.status = "WAIT_STABLE_HUMAN"; return
        }
        val first = path.first().ground.p
        val currentRoom = locate(first, p.box)
        val origin = gates.firstOrNull { it.id == p.originGate && time - p.originTime <= 900 &&
            currentRoom == livingId && it.distance(first) <= max(0.018, p.box.h * params.contactHeight * 1.5) }
        var source = origin?.room ?: currentRoom
        if (source == null) { p.status = "WAIT_LOCATION"; return }
        val slots = persons.values.filter { it.number != p.number && it.accepted && it.room == source && it.track !in active &&
            (it.anonymous || (source != livingId && time - it.lastSeen > 350)) }
        val unresolved = persons.values.any { it.number != p.number && it.accepted && it.anonymous && it.room == null }
        if (unresolved) { p.status = "UNRESOLVED_IDENTITY"; return }
        if (slots.isNotEmpty()) {
            val old = slots.minBy { it.number }
            persons.remove(old.number)
            notes += "REUSE_OCCUPANCY_SLOT:${old.number}:${p.number}:$source"
        } else if (source == livingId) {
            val hidden = persons.values.filter { it.number != p.number && it.accepted && it.track !in active && !it.anonymous &&
                time - it.lastSeen > 350 && (it.room == livingId || it.room == null) }
            val nearbyRoomSlots = persons.values.filter { other -> other.accepted && other.number != p.number && other.track !in active &&
                gates.any { it.room == other.room && it.distance(first) < max(0.025, p.box.h * 0.25) } }
            if (hidden.isNotEmpty() || (origin == null && nearbyRoomSlots.isNotEmpty())) {
                (hidden + nearbyRoomSlots).forEach { it.possible = setOfNotNull(it.room, livingId); it.status = "UNRESOLVED_REAPPEARANCE" }
                p.status = "UNRESOLVED_IDENTITY_OR_SOURCE"; return
            }
        }
        p.accepted = true; p.room = source; p.status = "CONFIRMED_PERSON"; p.possible = emptySet()
        if (origin != null) {
            p.crossing = Crossing(origin, origin.room, livingId, time, inferred = true)
            p.possible = setOf(source, livingId); p.status = "EXIT_ORIGIN_PENDING"
        }
        notes += "ADMITTED:${p.number}:$source"
    }

    private fun margin(p: Person) = max(0.005, p.ground?.error ?: 0.012)
    private fun contactBand(p: Person) = (p.box.h * params.contactHeight).coerceIn(0.008, 0.035)

    private fun measuredCrossing(p: Person, time: Long) {
        val g = p.ground?.takeIf { it.measured && p.lastGround == time } ?: return
        val from = p.room ?: return
        val entering = from == livingId
        val margin = margin(p)
        val hitGates = gates.mapNotNull { gate ->
            if (from != livingId && from != gate.room) return@mapNotNull null
            val targetSide = if (entering) gate.side(g.p) < -margin && !polygonContains(g.p, living)
                             else gate.side(g.p) > margin && polygonContains(g.p, living)
            if (!targetSide) return@mapNotNull null
            val prior = p.history.lastOrNull { pos -> pos.time < time && time - pos.time <= 1000 &&
                (if (entering) gate.side(pos.ground.p) > max(margin, pos.ground.error) else gate.side(pos.ground.p) < -max(margin, pos.ground.error)) }
                ?: return@mapNotNull null
            val hit = gate.intersection(prior.ground.p, g.p) ?: return@mapNotNull null
            val travel = prior.ground.p.distance(g.p, aspect)
            if (travel > max(0.18, p.box.h * 1.1)) return@mapNotNull null
            gate to hit
        }
        if (hitGates.size > 1) {
            p.crossing = null; p.possible = (hitGates.map { it.first.room } + livingId).toSet()
            p.status = "AMBIGUOUS_SHARED_EDGE"; return
        }
        val gate = hitGates.singleOrNull()?.first
        if (gate != null && (p.crossing == null || p.crossing!!.gate.id != gate.id)) {
            p.crossing = Crossing(gate, from, if (entering) gate.room else livingId, time)
        }
        val pending = p.crossing ?: run {
            if (gates.none { it.distance(g.p) < contactBand(p) * 2 }) {
                p.possible = emptySet(); p.contacts.clear(); p.status = "STABLE_ROOM"
            }
            return
        }
        val inTarget = if (pending.to == livingId) pending.gate.side(g.p) > margin && polygonContains(g.p, living)
                       else pending.gate.side(g.p) < -margin && !polygonContains(g.p, living)
        if (!inTarget) {
            p.crossing = null; p.contacts.clear(); p.possible = emptySet(); p.status = "RETURNED_NO_TRANSFER"; return
        }
        if (time - pending.start > 1600) { p.crossing = null; return }
        if (pending.last != time) { pending.hits++; pending.last = time }
        p.possible = setOf(pending.from, pending.to); p.status = "CROSSING_PENDING"
        if (pending.hits >= 2 && time - pending.start >= params.crossedHoldMs && time - p.lastEvent >= 250) commit(p, pending, time)
    }

    private fun visualContacts(p: Person, evidence: List<GateVisual>, time: Long) {
        if (p.room != livingId || p.anonymous) return
        val last = p.history.lastOrNull() ?: return
        if (time - last.time > params.coastMs) return
        for (v in evidence) {
            val gate = gates.firstOrNull { it.id == v.gate && !it.blind } ?: continue
            if (!v.valid || v.ambiguous) { p.contacts.remove(gate.id); continue }
            val nearGround = gate.distance(last.ground.p) <= contactBand(p) * 1.8 && gate.along(last.ground.p) in -0.05..1.05
            if (v.contact && v.ownedCells >= params.minOwnedCells && nearGround) {
                val c = p.contacts.getOrPut(gate.id) { Contact(gate, time, v.ownedCells) }
                c.time = time; c.cells = max(c.cells, v.ownedCells)
            }
            val c = p.contacts[gate.id] ?: continue
            val motion = v.motion
            if (motion != null && v.motionVerified && v.motionCells >= 3) {
                val earlier = p.history.firstOrNull()?.ground?.p
                val later = p.history.lastOrNull()?.ground?.p
                if (earlier != null && later != null) {
                    val d = later - earlier; val norm = d.norm(aspect) * motion.norm(aspect)
                    c.flowAgreement = norm > 1e-8 && (d.x * motion.x * aspect * aspect + d.y * motion.y) / norm > 0.25
                }
            }
            if (v.backgroundReady && v.backgroundRestored && v.restoredFraction >= params.restoredFraction && v.visibleFraction <= 1.0 - params.restoredFraction) {
                if (c.restoredAt < 0) c.restoredAt = time
                c.restoredHits++; c.restoredLast = time
            } else { c.restoredAt = -1; c.restoredHits = 0; c.restoredLast = -1 }
        }
        p.contacts.entries.removeAll { time - it.value.time > params.coastMs }
    }

    private fun hiddenCrossing(p: Person, time: Long) {
        if (p.room != livingId || p.absentSince < 0 || time - p.absentSince < params.vanishedHoldMs || time - p.lastEvent < 300) return
        val last = p.history.lastOrNull() ?: return
        if (time - last.time > params.coastMs || p.contacts.isEmpty()) return
        val earlier = p.history.firstOrNull { time - it.time <= 1700 } ?: return
        val eligible = p.contacts.values.filter { c ->
            val band = contactBand(p)
            val progress = c.gate.side(earlier.ground.p) - c.gate.side(last.ground.p)
            // The last measured foot must actually reach the threshold uncertainty band.
            abs(c.gate.side(last.ground.p)) <= max(last.ground.error * 2.0, band * 0.65) &&
                c.gate.distance(last.ground.p) <= band && c.gate.along(last.ground.p) in 0.0..1.0 &&
                progress >= max(0.008, p.box.h * 0.035) &&
                progress >= earlier.ground.p.distance(last.ground.p, aspect) * 0.50
        }
        if (eligible.isEmpty()) { p.status = "OCCLUDED_LIVING"; return }
        p.possible = (eligible.map { it.gate.room } + livingId).toSet(); p.status = "OCCLUSION_PENDING"
        // Independent portal polygons do not imply independent people or a unique crossing.
        if (eligible.size != 1) { p.status = "AMBIGUOUS_PORTAL"; return }
        val c = eligible.single()
        if (c.restoredAt < 0 || c.restoredLast != time || c.restoredHits < 3 || time - c.restoredAt < params.vanishedHoldMs) return
        if (requireFlowSupport && !c.flowAgreement) return
        commit(p, Crossing(c.gate, livingId, c.gate.room, p.absentSince, inferred = true), time)
    }

    private fun commit(p: Person, crossing: Crossing, now: Long) {
        if (!p.accepted || p.conflict || p.room != crossing.from) return
        events += GateEvent(p.number, p.track, crossing.from, crossing.to, crossing.gate.id, crossing.start, crossing.inferred)
        p.room = crossing.to; p.crossing = null; p.contacts.clear(); p.history.clear(); p.possible = emptySet()
        p.absentSince = -1; p.lastEvent = now; p.originGate = null
        p.status = if (crossing.inferred) "VISUAL_TRANSFER" else "GROUND_TRANSFER"
        notes += "TRANSFER:${p.number}:${crossing.from}->${crossing.to}:${crossing.gate.id}"
    }

    fun snapshot(): GateDecision {
        val admitted = persons.values.filter { it.accepted }
        return GateDecision(rooms.associateWith { r -> if (r in exterior) 0 else admitted.count { it.room == r } },
            rooms.associateWith { r -> if (r in exterior) 0 else admitted.count { it.room == r && it.possible.isEmpty() } },
            rooms.associateWith { r -> if (r in exterior) 0 else admitted.count { it.room == r || r in it.possible || it.room == null } },
            persons.values.map { p -> GatePerson(p.number,p.track,p.room,p.ground,p.box,p.status,p.possible,p.accepted) },
            events.toList(), notes.takeLast(12), admitted.count { it.room == null })
    }
}
