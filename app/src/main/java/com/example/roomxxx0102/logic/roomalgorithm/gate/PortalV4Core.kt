package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import kotlin.math.abs
import kotlin.math.max

/**
 * V4 transition core. Visual backends measure pixels; this state machine alone may mutate rooms.
 *
 * Invariants:
 * 1) one person owns at most one portal episode;
 * 2) one episode can emit at most one transfer;
 * 3) after a transfer the same portal is locked by WAIT_CLEAR until the body has really left it;
 * 4) a strong finite gate-line crossing is sufficient immediately; depth migration is the fallback
 *    when feet disappear inside the doorway.
 */
internal class PortalV4Core(
    private val livingId: String,
    private val living: List<FlowPoint>,
    val gates: List<FlowGate>,
    private val roomIds: List<String>,
    private val aspect: Double,
    initialCounts: Map<String, Int> = emptyMap(),
    private val policy: PortalV4Policy = PortalV4Policy(),
) {
    private data class GroundObs(val t: Long, val ground: FlowGround)
    private data class DepthObs(val t: Long, val evidence: PortalDepthEvidence)
    private data class Episode(
        val gate: FlowGate,
        val from: String,
        val to: String,
        val startedAt: Long,
        var sourcePoint: FlowPoint?,
        val depths: ArrayDeque<DepthObs> = ArrayDeque(),
        var phase: PortalEpisodePhase = PortalEpisodePhase.CONTACT,
        var lastEvidenceAt: Long = startedAt,
        var minAbsorption: Double = 1.0,
        var peakAbsorption: Double = 0.0,
        var peakAbsorptionAt: Long = -1L,
        var peakAlong: Double? = null,
        var visualReadyAt: Long = -1L,
    )
    private data class WaitClear(val gate: FlowGate, val committedAt: Long, var clearSince: Long = -1L)
    private class Person(val number: Int, var track: Int, var box: FlowBox, val born: Long) {
        val estimator = FlowGroundEstimator()
        val groundHistory = ArrayDeque<GroundObs>()
        val centerHistory = ArrayDeque<Pair<Long, FlowPoint>>()
        var accepted = false
        var bootstrap = false
        var room: String? = null
        var lastDetection = born
        var firstLocked = -1L
        var lockedHits = 0
        var ground: FlowGround? = null
        var lastStrong = -1L
        var detectorMissingSince = -1L
        var originGate: String? = null
        var episode: Episode? = null
        var waitClear: WaitClear? = null
        var possible = emptySet<String>()
        var status = "CANDIDATE"
        var conflict = false
        var lastEvent = -100000L
        var lastDepth: Double? = null
        var lastGroundSide: Double? = null
        var lastEvidence: String? = null
    }

    private val people = linkedMapOf<Int, Person>()
    private var nextPerson = 1
    private var lastTime = -1L
    private var observedTracks = emptySet<Int>()
    private var events = mutableListOf<FlowEvent>()
    private var notes = mutableListOf<String>()

    init {
        for ((room, count) in initialCounts.filterKeys { it in roomIds }) {
            repeat(count.coerceIn(0, 20)) {
                val n = nextPerson++
                people[n] = Person(n, -n, FlowBox(0.0, 0.0, 0.0, 0.0), -100000).also {
                    it.accepted = true; it.bootstrap = true; it.room = room; it.status = "INITIAL_OCCUPIED_SLOT"
                    it.lastDetection = -100000L
                }
            }
        }
    }

    fun detachIdentitySource(): FlowDecision {
        people.entries.removeAll { !it.value.accepted }
        people.values.forEach { p ->
            p.track = -p.number; p.bootstrap = true; p.ground = null; p.lastStrong = -1L
            p.groundHistory.clear(); p.centerHistory.clear(); p.episode = null; p.waitClear = null
            p.detectorMissingSince = -1L; p.conflict = false; p.possible = emptySet()
            p.status = "IDENTITY_SOURCE_CHANGED_RETAIN_COUNTS"
        }
        return snapshot(listOf("IDENTITY_SOURCE_CHANGED_RETAIN_COUNTS"))
    }

    fun step(
        timeMs: Long,
        detections: List<FlowDetection>?,
        depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
        flows: Map<Int, FlowEvidence> = emptyMap(),
        coverage: FlowBox? = null,
        frameHealthy: Boolean = true,
        bodies: Map<Int, List<PortalBodyEvidence>> = emptyMap(),
    ): FlowDecision {
        events = mutableListOf(); notes = mutableListOf()
        if (timeMs <= lastTime) return snapshot(listOf("DUPLICATE_OR_REVERSED_FRAME"))
        val analysisGapMs = if(lastTime>=0) timeMs-lastTime else -1L
        lastTime = timeMs
        observedTracks = detections?.map { it.id }?.toSet() ?: emptySet()
        if (!frameHealthy) {
            people.values.filter{it.accepted}.forEach { p -> if(p.waitClear==null)p.status="FRAME_UNRELIABLE_HOLD" }
            return snapshot(listOf("FRAME_UNRELIABLE_HOLD_STATE"))
        }
        if(analysisGapMs>policy.gapMs) notes += "SPARSE_ANALYSIS_GAP:${analysisGapMs}ms"

        if (detections != null) processDetections(timeMs, detections, coverage, depths)

        for (p in people.values.toList()) {
            if (!p.accepted || p.conflict) continue
            val currentDepths = depths[p.track].orEmpty()
            val currentBodies = bodies[p.track].orEmpty()
            val flow = flows[p.track]
            if (p.track !in observedTracks) {
                if (p.detectorMissingSince < 0) p.detectorMissingSince = timeMs
            } else p.detectorMissingSince = -1L

            if (p.waitClear != null) {
                updateWaitClear(p, timeMs, currentDepths)
            } else {
                ensureEpisode(p, timeMs, currentDepths, currentBodies)
                evaluateEpisode(p, timeMs, currentDepths, currentBodies, flow)
            }
            if (timeMs - p.lastStrong > 500) p.ground = null
            if (p.episode == null && p.waitClear == null && p.status !in setOf("CONFIRMED_PERSON", "STABLE_ROOM")) {
                p.status = if (p.room == livingId) "STABLE_LIVING" else "STABLE_ROOM"
            }
        }

        people.entries.removeAll { (_, p) -> !p.accepted && timeMs - p.lastDetection > 2000 }
        return snapshot()
    }

    private fun processDetections(
        t: Long,
        detections: List<FlowDetection>,
        coverage: FlowBox?,
        depths: Map<Int, List<PortalDepthEvidence>>,
    ) {
        val activeIds = detections.map { it.id }.toSet()
        val used = hashSetOf<Int>()
        for (d in detections.filter { it.box.center.finite() && it.box.width > 0 && it.box.height > 0 }) {
            var p = people.values.firstOrNull { it.track == d.id }
            if (p != null && p.accepted && t - p.lastDetection < 1800 &&
                d.box.center.distance(p.box.center, aspect) > max(0.14, p.box.height * 0.85)) {
                p.track = -p.number; p.bootstrap = true; p.episode = null; p.waitClear = null
                p.status = "TRACK_ID_DISCONTINUITY_RETAIN_SLOT"; p = null
                notes += "TRACK_ID_DISCONTINUITY:${d.id}"
            }
            if (p == null) {
                p = rebindHiddenSlot(d, t, activeIds, used)
                if (p != null) {
                    notes += "REBIND:${p.track}->${d.id}"
                    p.track = d.id; p.bootstrap = false
                } else {
                    p = Person(nextPerson++, d.id, d.box, t)
                    people[p.number] = p
                }
            }
            used += p.number
            p.conflict = d.shielded || detections.any { it.id != d.id && it.score >= 0.5 && d.box.iou(it.box) > 0.40 }
            p.lastDetection = t
            if (d.originGate != null) p.originGate = d.originGate
            p.box = d.box
            p.centerHistory.add(t to d.box.center)
            while (p.centerHistory.isNotEmpty() && t - p.centerHistory.first().first > 1400) p.centerHistory.removeFirst()

            val measured = p.estimator.measure(d, coverage)
            if (measured != null && !p.conflict) {
                p.ground = measured
                if (measured.strong) {
                    p.lastStrong = t
                    p.groundHistory.add(GroundObs(t, measured))
                    while (p.groundHistory.isNotEmpty() && t - p.groundHistory.first().t > policy.depthHistoryMs) p.groundHistory.removeFirst()
                }
            }
            if (d.locked && d.bodyValid() && !p.conflict) {
                if (p.firstLocked < 0) p.firstLocked = t
                p.lockedHits++
            }
            if (!p.accepted) maybeAdmit(p, d, t, depths[d.id].orEmpty())
        }
    }

    private fun rebindHiddenSlot(d: FlowDetection, t: Long, activeIds: Set<Int>, used: Set<Int>): Person? {
        val origin = d.originGate?.let { id -> gates.firstOrNull { it.id == id } }
        if (origin != null) {
            return people.values.filter { p ->
                p.accepted && p.number !in used && p.room == origin.room && p.track !in activeIds &&
                    (p.bootstrap || t - p.lastDetection > 220)
            }.minByOrNull { if (it.bootstrap) 0 else 1 }
        }
        return people.values.filter { p ->
            p.accepted && p.number !in used && p.room == livingId && p.track !in activeIds &&
                !p.bootstrap && t - p.lastDetection in 1..1800 && d.box.iou(p.box) >= 0.35
        }.maxByOrNull { d.box.iou(it.box) }
    }

    private fun maybeAdmit(p: Person, d: FlowDetection, t: Long, depth: List<PortalDepthEvidence>) {
        if (p.accepted || p.lockedHits < 3 || p.firstLocked < 0 || t - p.firstLocked < 150 || p.conflict) return
        val travel = if (p.centerHistory.size < 2) 0.0 else p.centerHistory.first().second.distance(p.centerHistory.last().second, aspect)
        val origin = p.originGate?.let { id -> gates.firstOrNull { it.id == id } }
        val moving = travel >= policy.admissionTravel || origin != null || depth.any { it.ownedPixels >= policy.depthMinPixels }
        if (!moving) { p.status = "WAIT_HUMAN_MOTION"; return }

        val ground = p.ground
        val inferredGate = if (origin == null && ground != null) gates.filter { g ->
            !g.isBlind && g.along(ground.point) in -0.12..1.12 && g.side(ground.point) < -max(0.004, ground.uncertainty) &&
                (g.containsBody(d.box.center) || g.distance(ground.point) <= contactBand(p))
        }.singleOrNull() else null
        val initialRoom = when {
            origin != null -> origin.room
            ground != null && inPolygon(ground.point, living) -> livingId
            inferredGate != null -> inferredGate.room
            else -> null
        }
        if (initialRoom == null) { p.status = "UNKNOWN_ORIGIN_CANDIDATE"; return }

        val reusable = people.values.filter { other ->
            other.number != p.number && other.accepted && other.room == initialRoom && other.track !in observedTracks &&
                (other.bootstrap || t - other.lastDetection > 250)
        }.minByOrNull { if (it.bootstrap) 0 else 1 }
        if (reusable != null) {
            people.remove(reusable.number)
            notes += "REUSE_ROOM_SLOT:$initialRoom:${reusable.number}->${p.number}"
        }
        p.accepted = true; p.room = initialRoom; p.bootstrap = false; p.status = "CONFIRMED_PERSON"
        notes += "PERSON_ADMITTED:${p.number}:$initialRoom"
        if (origin != null) {
            p.episode = Episode(origin, origin.room, livingId, t, sourcePointFor(p, origin, origin.room))
            p.possible = setOf(origin.room, livingId); p.status = "TRANSITING_OUT"
        }
    }

    private fun ensureEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>, bodies: List<PortalBodyEvidence>) {
        if (p.episode != null || p.room == null) return
        val eligible = if (p.room == livingId) gates.filter { !it.isBlind } else gates.filter { !it.isBlind && it.room == p.room }
        if (eligible.isEmpty()) return
        data class Candidate(val gate: FlowGate, val score: Double)
        val candidates = eligible.mapNotNull { gate ->
            val depth = depths.firstOrNull { it.gateId == gate.id && it.exclusive && it.ownedPixels >= policy.depthMinPixels }
            val body = bodies.firstOrNull { it.gateId == gate.id && it.exclusive && it.absorption >= 0.16 }
            val g = p.ground
            val groundNear = g != null && gate.along(g.point) in -0.18..1.18 && gate.distance(g.point) <= contactBand(p)
            val originBoost = p.originGate == gate.id
            if (depth == null && body == null && !groundNear && !originBoost) null else {
                val score = (if (originBoost) 20.0 else 0.0) + (if (depth != null) 10.0 + depth.p80 else 0.0) +
                    (if (body != null) 6.0 + body.absorption * 4.0 else 0.0) +
                    (if (groundNear) 5.0 - gate.distance(g!!.point) else 0.0)
                Candidate(gate, score)
            }
        }.sortedByDescending { it.score }
        if (candidates.isEmpty()) return
        if (candidates.size > 1 && candidates[0].score - candidates[1].score < 0.35) {
            p.status = "AMBIGUOUS_PORTAL"; p.possible = (candidates.take(2).map { it.gate.room } + p.room!!).toSet(); return
        }
        val gate = candidates.first().gate
        val from = p.room!!
        val to = if (from == livingId) gate.room else livingId
        p.episode = Episode(gate, from, to, t, sourcePointFor(p, gate, from))
        p.possible = setOf(from, to); p.status = if (to == livingId) "TRANSITING_OUT" else "TRANSITING_IN"
        p.originGate = null
    }

    private fun sourcePointFor(p: Person, gate: FlowGate, from: String): FlowPoint? {
        val sign = if (from == livingId) 1.0 else -1.0
        return p.groundHistory.toList().asReversed().firstOrNull { obs ->
            sign * gate.side(obs.ground.point) > max(0.004, obs.ground.uncertainty)
        }?.ground?.point
    }

    private fun evaluateEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>, bodies: List<PortalBodyEvidence>, flow: FlowEvidence?) {
        val e = p.episode ?: return
        val currentDepth = depths.firstOrNull { it.gateId == e.gate.id && it.exclusive && it.ownedPixels >= policy.depthMinPixels }
        val currentBody = bodies.firstOrNull { it.gateId == e.gate.id && it.exclusive }
        val bodyScore = currentBody?.absorption
        if (currentDepth != null) {
            e.depths.add(DepthObs(t, currentDepth)); e.phase = PortalEpisodePhase.TRANSITING;e.lastEvidenceAt=t
            while (e.depths.isNotEmpty() && t - e.depths.first().t > policy.depthHistoryMs) e.depths.removeFirst()
            p.lastDepth = currentDepth.median
        }
        if(currentBody!=null&&bodyScore!=null){
            e.lastEvidenceAt=t;e.minAbsorption=minOf(e.minAbsorption,bodyScore)
            if(bodyScore>e.peakAbsorption){e.peakAbsorption=bodyScore;e.peakAbsorptionAt=t;e.peakAlong=currentBody.centerAlong}
            if(bodyScore>=policy.absorptionArmRatio)e.phase=PortalEpisodePhase.TRANSITING
        }

        val g = p.ground
        if(g?.strong==true&&e.gate.along(g.point) in -0.22..1.22&&e.gate.distance(g.point)<=contactBand(p)*2.5)e.lastEvidenceAt=t
        if (t - e.startedAt > policy.episodeTimeoutMs) {
            p.episode = null; p.possible = emptySet(); p.status = "PORTAL_EPISODE_HARD_TIMEOUT"; return
        }
        if(t-e.lastEvidenceAt>policy.episodeIdleMs){
            p.episode=null;p.possible=emptySet();p.status="PORTAL_EPISODE_IDLE_TIMEOUT";return
        }
        if (g?.strong == true) {
            val side = e.gate.side(g.point); p.lastGroundSide = side
            val margin = max(0.004, g.uncertainty * 1.35)
            val source = e.sourcePoint ?: sourcePointFor(p, e.gate, e.from).also { e.sourcePoint = it }
            if (source != null) {
                val sourceSide = e.gate.side(source)
                val crossed = if (e.from == livingId) sourceSide > margin && side < -margin else sourceSide < -margin && side > margin
                if (crossed && e.gate.intersects(source, g.point)) {
                    commit(p, e, t, inferred = false, evidence = "GROUND_CROSSING")
                    return
                }
            }
            val sourceStill = if (e.from == livingId) side >= -margin else side <= margin
            if (sourceStill && e.gate.distance(g.point) > contactBand(p) * 2.2 && t - e.startedAt > 220 && currentDepth == null) {
                p.episode = null; p.possible = emptySet(); p.status = "RETURNED_WITHOUT_TRANSFER"; return
            }
        }

        if(e.from==livingId&&currentBody!=null&&bodyScore!=null&&e.peakAbsorption>=policy.absorptionArmRatio){
            val peakAlong=e.peakAlong
            if(bodyScore<=policy.absorptionReleaseRatio&&peakAlong!=null&&abs(currentBody.centerAlong-peakAlong)>=policy.absorptionPassByAlong){
                p.episode=null;p.possible=emptySet();p.lastEvidence="BODY_PASS_BY";p.status="PASSED_PORTAL"
                notes+="PASS_BY:${p.number}:${e.gate.id}"
                return
            }
        }

        if (depthCommits(e)) {
            if(e.from==livingId&&e.peakAbsorption>=policy.absorptionArmRatio){
                if(e.visualReadyAt<0)e.visualReadyAt=t
                p.lastEvidence="DEPTH_READY_WAIT_WITNESS"
            }else{
                commit(p, e, t, inferred = true, evidence = "PORTAL_DEPTH_MIGRATION")
                return
            }
        }
        if(e.from==livingId&&e.visualReadyAt>=0&&t-e.visualReadyAt>=policy.visualWitnessMs){
            val peakAlong=e.peakAlong
            val tangentialStable=currentBody==null||peakAlong==null||abs(currentBody.centerAlong-peakAlong)<policy.absorptionPassByAlong
            if(tangentialStable){
                commit(p,e,t,inferred=true,evidence="PORTAL_DEPTH_MIGRATION_WITNESSED")
                return
            }
        }

        val absorptionFresh=e.peakAbsorptionAt>=0&&t-e.peakAbsorptionAt<=policy.absorptionPeakFreshMs
        val sawApproach=e.minAbsorption<=policy.absorptionArmRatio
        if(e.from==livingId&&absorptionFresh&&sawApproach&&e.peakAbsorption>=policy.absorptionCommitRatio&&
            p.detectorMissingSince>=0&&t-p.detectorMissingSince>=policy.visualWitnessMs){
            commit(p,e,t,inferred=true,evidence="PORTAL_BODY_ABSORPTION")
            return
        }

        if (e.from == livingId && p.detectorMissingSince >= 0 && t - p.detectorMissingSince >= policy.disappearanceMs) {
            val w = flow?.windowEvidence?.firstOrNull { it.gateId == e.gate.id }
            val last = e.depths.lastOrNull()?.evidence
            val deepEnough = last != null && (last.median >= 0.20 || last.p80 >= 0.38)
            val cleared = w != null && w.referenceKnown && w.exclusive && w.acquiredWhileVisible &&
                w.clearForMs >= policy.clearMs && w.foregroundPixels <= max(2, (w.peakPixels * policy.clearRatio).toInt())
            if (deepEnough && cleared) {
                commit(p, e, t, inferred = true, evidence = "PORTAL_DISAPPEARANCE")
                return
            }
        }
        p.status = if (e.to == livingId) "TRANSITING_OUT" else "TRANSITING_IN"
        p.lastEvidence = when {
            currentBody!=null -> "BODY:${"%.2f".format(bodyScore)}"
            currentDepth!=null -> "DEPTH:${"%.2f".format(currentDepth.median)}"
            else -> p.lastEvidence
        }
    }

    private fun depthCommits(e: Episode): Boolean {
        if (e.depths.size < policy.depthMinSamples) return false
        val samples = e.depths.toList()
        if (samples.last().t - samples.first().t < policy.depthMinSpanMs) return false
        val medians = samples.map { it.evidence.median }
        val minDepth = medians.minOrNull() ?: return false
        val maxDepth = medians.maxOrNull() ?: return false
        if (maxDepth - minDepth < policy.depthTravel) return false
        val half = max(1, medians.size / 2)
        val firstAvg = medians.take(half).average()
        val lastAvg = medians.takeLast(half).average()
        val current = samples.last().evidence
        return if (e.from == livingId) {
            current.median >= policy.depthEnterCommit && current.p80 >= 0.58 && lastAvg - firstAvg >= policy.depthTravel * 0.55
        } else {
            current.median <= policy.depthExitCommit && current.p20 <= 0.12 && maxDepth >= 0.42 && firstAvg - lastAvg >= policy.depthTravel * 0.55
        }
    }

    private fun commit(p: Person, e: Episode, t: Long, inferred: Boolean, evidence: String) {
        if (!p.accepted || p.room != e.from || p.conflict) return
        events += FlowEvent(p.number, p.track, e.from, e.to, e.gate.id, t, inferred)
        p.room = e.to; p.episode = null; p.possible = emptySet(); p.lastEvent = t
        p.waitClear = WaitClear(e.gate, t); p.status = "WAIT_CLEAR"; p.lastEvidence = evidence
        p.groundHistory.clear()
        notes += "TRANSFER:${p.number}:${e.from}->${e.to}:${e.gate.id}:$evidence"
    }

    private fun updateWaitClear(p: Person, t: Long, depths: List<PortalDepthEvidence>) {
        val w = p.waitClear ?: return
        val depthNear = depths.any { it.gateId == w.gate.id && it.ownedPixels >= policy.depthMinPixels }
        val groundNear = p.ground?.let { g ->
            w.gate.along(g.point) in -0.20..1.20 && w.gate.distance(g.point) <= clearBand(p)
        } == true
        // WAIT_CLEAR is a threshold lock, not an aperture lock. A long portal may extend deep
        // into the sub-room, so bbox overlap with the whole aperture must not block a later exit.
        val bodyNear = p.track in observedTracks && lowerBodyNearThreshold(w.gate, p.box, p)
        val near = depthNear || groundNear || bodyNear
        if (near) {
            w.clearSince = -1L; p.status = "WAIT_CLEAR"; return
        }
        if (w.clearSince < 0) w.clearSince = t
        if (t - w.clearSince >= policy.waitClearMs) {
            p.waitClear = null; p.status = if (p.room == livingId) "STABLE_LIVING" else "STABLE_ROOM"
            p.lastEvidence = "PORTAL_CLEARED"
        } else p.status = "WAIT_CLEAR"
    }

    private fun lowerBodyNearThreshold(g: FlowGate, box: FlowBox, p: Person): Boolean {
        val foot = box.foot
        return g.along(foot) in -0.25..1.25 && g.distance(foot) <= clearBand(p) * 1.15
    }

    private fun contactBand(p: Person) = max(0.018, p.box.height * policy.contactScale * 1.35)
    private fun clearBand(p: Person) = max(0.028, p.box.height * policy.contactScale * 1.8)

    fun debugSnapshot(): Map<Int, PortalV4PersonDebug> = people.values.filter { it.track >= 0 }.associate { p ->
        val e = p.episode
        val w = p.waitClear
        p.track to PortalV4PersonDebug(
            track = p.track,
            room = p.room,
            gateId = e?.gate?.id ?: w?.gate?.id,
            phase = e?.phase ?: if (w != null) PortalEpisodePhase.WAIT_CLEAR else null,
            direction = e?.let { if (it.to == livingId) "OUT" else "IN" },
            depth = p.lastDepth,
            groundSide = p.lastGroundSide,
            evidence = p.lastEvidence,
        )
    }

    private fun snapshot(extra: List<String> = emptyList()): FlowDecision {
        val accepted = people.values.filter { it.accepted }
        val counts = roomIds.associateWith { id -> accepted.count { it.room == id } }
        val lower = roomIds.associateWith { id -> accepted.count { it.room == id && it.possible.isEmpty() } }
        val upper = roomIds.associateWith { id -> accepted.count { it.room == id || it.room == null || id in it.possible } }
        return FlowDecision(
            counts, lower, upper, accepted.count { it.room == null }, events.toList(),
            people.values.map { p -> FlowPersonView(p.number, p.track, p.room, p.ground, p.box, p.status, p.possible, p.accepted) },
            (notes + extra).takeLast(12)
        )
    }
}
