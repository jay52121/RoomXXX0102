from pathlib import Path

ROOT = Path('.')

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# 1) Evidence + policy: keep sparse analysis continuous and add whole-body portal absorption evidence.
(ROOT / 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4Evidence.kt').write_text('''package com.example.roomxxx0102.logic.roomalgorithm.gate

internal data class PortalDepthEvidence(
    val gateId: String,
    val track: Int,
    val timeMs: Long,
    val p20: Double,
    val median: Double,
    val p80: Double,
    val ownedPixels: Int,
    val exclusive: Boolean,
)

/** Visible-body convergence into a portal. It deliberately does not depend on feet. */
internal data class PortalBodyEvidence(
    val gateId: String,
    val track: Int,
    val timeMs: Long,
    val poseInsideRatio: Double,
    val pixelInsideRatio: Double,
    val visiblePosePoints: Int,
    val bodyPixels: Int,
    val insidePixels: Int,
    val centerAlong: Double,
    val centerSide: Double,
    val exclusive: Boolean,
) {
    // Pose carries most of the weight because the local crop can truncate a body outside the door.
    // With too few pose points, pixel overlap remains a weak fallback rather than becoming decisive.
    val absorption: Double get() = if (visiblePosePoints >= 4) {
        (poseInsideRatio * 0.75 + pixelInsideRatio * 0.25).coerceIn(0.0, 1.0)
    } else (pixelInsideRatio * 0.85).coerceIn(0.0, 1.0)
}

internal enum class PortalEpisodePhase { CONTACT, TRANSITING, WAIT_CLEAR }

internal data class PortalV4PersonDebug(
    val track: Int,
    val room: String?,
    val gateId: String?,
    val phase: PortalEpisodePhase?,
    val direction: String?,
    val depth: Double?,
    val groundSide: Double?,
    val evidence: String?,
)

internal data class PortalV4Policy(
    // A cadence hint only. A sparse analysis interval is not a video discontinuity.
    val gapMs: Long = 300,
    val contactScale: Double = 0.10,
    val admissionTravel: Double = 0.012,
    val depthMinPixels: Int = 16,
    val depthEnterCommit: Double = 0.44,
    val depthExitCommit: Double = 0.20,
    val depthTravel: Double = 0.20,
    val depthMinSamples: Int = 3,
    val depthMinSpanMs: Long = 80,
    val depthHistoryMs: Long = 3200,
    // Episodes die from lack of evidence, with a separate hard stale cap.
    val episodeIdleMs: Long = 1400,
    val episodeTimeoutMs: Long = 5000,
    val waitClearMs: Long = 320,
    val disappearanceMs: Long = 220,
    val clearMs: Long = 200,
    val clearRatio: Double = 0.08,
    // Whole-body absorption is a pending visual witness, never an instant room transfer.
    val absorptionArmRatio: Double = 0.58,
    val absorptionCommitRatio: Double = 0.78,
    val absorptionReleaseRatio: Double = 0.42,
    val absorptionPassByAlong: Double = 0.22,
    val visualWitnessMs: Long = 600,
    val absorptionPeakFreshMs: Long = 1700,
) {
    companion object {
        fun from(config: GateConfig) = PortalV4Policy(
            gapMs = config.maxGapMs.toLong(),
            contactScale = config.contactScale,
            admissionTravel = config.admissionTravel,
            episodeIdleMs = maxOf(1200L, config.holdMs.toLong() + 300L),
            episodeTimeoutMs = maxOf(4500L, config.episodeMs.toLong() * 3L),
            clearMs = config.clearMs.toLong(),
            clearRatio = config.clearRatio,
        )
    }
}
''', encoding='utf-8')

# 2) Diagnostics expose the new body-convergence signal and the real episode timing policy.
replace_once(
    'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateDiagnosticBus.kt',
'''    val bodyMotionRatio: Double?,
    val motion: GateMaskDigest?,
    val owned: GateMaskDigest?,
)''',
'''    val bodyMotionRatio: Double?,
    val motion: GateMaskDigest?,
    val owned: GateMaskDigest?,
    val bodyInsideRatio: Double? = null,
    val poseInsideRatio: Double? = null,
    val visiblePosePoints: Int? = null,
    val bodyAlong: Double? = null,
    val bodySide: Double? = null,
)''')
replace_once(
    'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateDiagnosticBus.kt',
'''    val waitClearMs: Long,
    val disappearanceMs: Long,
)''',
'''    val waitClearMs: Long,
    val disappearanceMs: Long,
    val episodeIdleMs: Long = 0,
    val episodeHardMs: Long = 0,
    val absorptionArmRatio: Double = 0.0,
    val absorptionCommitRatio: Double = 0.0,
    val absorptionReleaseRatio: Double = 0.0,
    val absorptionPassByAlong: Double = 0.0,
    val visualWitnessMs: Long = 0,
)''')
replace_once(
    'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateDiagnosticBus.kt',
'''                waitClearMs = policy.waitClearMs,
                disappearanceMs = policy.disappearanceMs,
            )''',
'''                waitClearMs = policy.waitClearMs,
                disappearanceMs = policy.disappearanceMs,
                episodeIdleMs = policy.episodeIdleMs,
                episodeHardMs = policy.episodeTimeoutMs,
                absorptionArmRatio = policy.absorptionArmRatio,
                absorptionCommitRatio = policy.absorptionCommitRatio,
                absorptionReleaseRatio = policy.absorptionReleaseRatio,
                absorptionPassByAlong = policy.absorptionPassByAlong,
                visualWitnessMs = policy.visualWitnessMs,
            )''')

# 3) Vision: sparse analysis is not a reset; publish body absorption evidence.
vision = 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateEventVisionV2.kt'
replace_once(vision,
'''    val depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
    val diagnostics: List<GatePortalDiagnostic> = emptyList(),
)''',
'''    val depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
    val diagnostics: List<GatePortalDiagnostic> = emptyList(),
    val bodies: Map<Int, List<PortalBodyEvidence>> = emptyMap(),
)''')
replace_once(vision,
'''        val diagnostic:GatePortalDiagnostic?,
    )''',
'''        val diagnostic:GatePortalDiagnostic?,
        val body:PortalBodyEvidence?,
    )''')
replace_once(vision,
'''        val depths=linkedMapOf<Int,MutableList<PortalDepthEvidence>>()
        val diagnostics=mutableListOf<GatePortalDiagnostic>()''',
'''        val depths=linkedMapOf<Int,MutableList<PortalDepthEvidence>>()
        val bodies=linkedMapOf<Int,MutableList<PortalBodyEvidence>>()
        val diagnostics=mutableListOf<GatePortalDiagnostic>()''')
replace_once(vision,
'''            val gap=previousTime>=0&&(timeMs<=previousTime||timeMs-previousTime>cfg.maxGapMs)
            if(gap){states.values.forEach{it.resetEpisode(true)};scheduler.reset();lk?.reset();notes+="VISUAL_GAP_RESET_LOCAL_HISTORY"}''',
'''            val reversed=previousTime>=0&&timeMs<=previousTime
            val hardMotionGap=previousTime>=0&&timeMs-previousTime>max(2500L,cfg.historyMs.toLong()*2L)
            if(reversed){states.values.forEach{it.resetEpisode(true)};scheduler.reset();lk?.reset();notes+="VISUAL_TIME_RESET_LOCAL_HISTORY"}
            if(hardMotionGap){
                states.values.forEach{it.previous.release();it.previous=Mat()}
                lk?.reset();notes+="LONG_VISUAL_GAP_RESET_MOTION_ONLY"
            }''')
replace_once(vision,
'''                result.depth?.let{depths.getOrPut(owner){mutableListOf()}.add(it)}
                result.diagnostic?.let(diagnostics::add)''',
'''                result.depth?.let{depths.getOrPut(owner){mutableListOf()}.add(it)}
                result.body?.let{bodies.getOrPut(owner){mutableListOf()}.add(it)}
                result.diagnostic?.let(diagnostics::add)''')
replace_once(vision,
'''                depths.mapValues{it.value.toList()},diagnostics.toList()
            )''',
'''                depths.mapValues{it.value.toList()},diagnostics.toList(),bodies.mapValues{it.value.toList()}
            )''')
replace_once(vision,
'''        val raw=Mat();val foreground=Mat();val diff=Mat();val motion=Mat();var ownership:Mat?=null''',
'''        val raw=Mat();val foreground=Mat();val diff=Mat();val motion=Mat();var ownership:Mat?=null;var bodyVisible:Mat?=null''')
replace_once(vision,
'''            if(detection!=null){
                ownership=bodyMask(detection,state);val bodyPixels=Core.countNonZero(ownership).coerceAtLeast(1);val moving=Mat()
                try{Core.bitwise_and(ownership,motion,moving);pixelChange=Core.countNonZero(moving).toDouble()/bodyPixels}finally{moving.release()}
                Core.bitwise_and(ownership,foreground,ownership);ownCount=Core.countNonZero(ownership)
                if(ownCount>=8){ownership.copyTo(state.owned);state.contacted.addAll(occupiedFineCells(ownership));state.acquiredWhileVisible=true}
            }else if(!state.owned.empty()){''',
'''            if(detection!=null){
                val visibleBody=bodyMask(detection,state,false);bodyVisible=visibleBody
                val ownedMask=Mat();ownership=ownedMask;Core.bitwise_and(visibleBody,state.mask,ownedMask)
                val bodyPixels=Core.countNonZero(ownedMask).coerceAtLeast(1);val moving=Mat()
                try{Core.bitwise_and(ownedMask,motion,moving);pixelChange=Core.countNonZero(moving).toDouble()/bodyPixels}finally{moving.release()}
                Core.bitwise_and(ownedMask,foreground,ownedMask);ownCount=Core.countNonZero(ownedMask)
                if(ownCount>=8){ownedMask.copyTo(state.owned);state.contacted.addAll(occupiedFineCells(ownedMask));state.acquiredWhileVisible=true}
            }else if(!state.owned.empty()){''')
replace_once(vision,
'''            val clearFor=state.clear.observe(timeMs,remaining,ownCount,state.referenceKnown&&state.acquiredWhileVisible&&inspected,cfg.maxGapMs,cfg.clearRatio)''',
'''            val clearFor=state.clear.observe(timeMs,remaining,ownCount,state.referenceKnown&&state.acquiredWhileVisible&&inspected,max(1200,cfg.maxGapMs*4),cfg.clearRatio)''')
replace_once(vision,
'''            val depth=if(owner>=0) portalDepthEvidence(state,owner,debugOwned,timeMs,exclusive) else null
            val diagnostic=if(GateDiagnosticBus.isCapturing()) GatePortalDiagnostic(''',
'''            val depth=if(owner>=0) portalDepthEvidence(state,owner,debugOwned,timeMs,exclusive) else null
            val body=if(owner>=0&&detection!=null) bodyVisible?.let{portalBodyEvidence(state,detection,it,timeMs,exclusive)} else null
            val diagnostic=if(GateDiagnosticBus.isCapturing()) GatePortalDiagnostic(''')
replace_once(vision,
'''                bodyMotionRatio=pixelChange,
                motion=maskDigest(state,motion),
                owned=maskDigest(state,debugOwned),
            ) else null''',
'''                bodyMotionRatio=pixelChange,
                motion=maskDigest(state,motion),
                owned=maskDigest(state,debugOwned),
                bodyInsideRatio=body?.pixelInsideRatio,
                poseInsideRatio=body?.poseInsideRatio,
                visiblePosePoints=body?.visiblePosePoints,
                bodyAlong=body?.centerAlong,
                bodySide=body?.centerSide,
            ) else null''')
replace_once(vision,
'''            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null,depth,diagnostic)
        }finally{ownership?.release();raw.release();foreground.release();diff.release();motion.release()}''',
'''            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null,depth,diagnostic,body)
        }finally{ownership?.release();bodyVisible?.release();raw.release();foreground.release();diff.release();motion.release()}''')
replace_once(vision,
'''    private fun bodyMask(d:FlowDetection,state:PortalState):Mat{''',
'''    private fun bodyMask(d:FlowDetection,state:PortalState,clipToGate:Boolean=true):Mat{''')
replace_once(vision,
'''        Core.bitwise_and(m,state.mask,m);return m
    }
    private fun occupiedFineCells''',
'''        if(clipToGate)Core.bitwise_and(m,state.mask,m);return m
    }
    private fun occupiedFineCells''')
replace_once(vision,
'''    private fun portalDepthEvidence(state:PortalState,track:Int,mask:Mat?,timeMs:Long,exclusive:Boolean):PortalDepthEvidence? {''',
'''    private fun portalBodyEvidence(state:PortalState,d:FlowDetection,visibleMask:Mat,timeMs:Long,exclusive:Boolean):PortalBodyEvidence? {
        val inside=Mat()
        try{
            Core.bitwise_and(visibleMask,state.mask,inside)
            val bodyPixels=Core.countNonZero(visibleMask);val insidePixels=Core.countNonZero(inside)
            val pose=(0..16).mapNotNull{d.joint(it,.22)}
            if(bodyPixels<8&&pose.size<3)return null
            val poseInside=pose.count{state.gate.containsBody(it)}
            val poseRatio=if(pose.isEmpty())0.0 else poseInside.toDouble()/pose.size
            val pixelRatio=if(bodyPixels<=0)0.0 else insidePixels.toDouble()/bodyPixels
            val center=if(pose.isEmpty())d.box.center else FlowPoint(pose.map{it.x}.average(),pose.map{it.y}.average())
            return PortalBodyEvidence(state.gate.id,d.id,timeMs,poseRatio,pixelRatio,pose.size,bodyPixels,insidePixels,
                state.gate.along(center),state.gate.side(center),exclusive)
        }finally{inside.release()}
    }

    private fun portalDepthEvidence(state:PortalState,track:Int,mask:Mat?,timeMs:Long,exclusive:Boolean):PortalDepthEvidence? {''')

# 4) LK tolerates sparse analysis cadence; a 350-600ms detector cadence is not identity discontinuity.
replace_once(
    'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateEventLk.kt',
'''        if (state.owner != owner || timeMs <= state.time || timeMs - state.time > cfg.maxGapMs) {''',
'''        if (state.owner != owner || timeMs <= state.time || timeMs - state.time > max(1200L, cfg.maxGapMs.toLong() * 4L)) {''')

# 5) Core: preserve state across sparse analysis, make episode lifetime evidence-driven, and add pass-by-safe absorption.
core = 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4Core.kt'
replace_once(core,
'''    private data class Episode(
        val gate: FlowGate,
        val from: String,
        val to: String,
        val startedAt: Long,
        var sourcePoint: FlowPoint?,
        val depths: ArrayDeque<DepthObs> = ArrayDeque(),
        var phase: PortalEpisodePhase = PortalEpisodePhase.CONTACT,
    )''',
'''    private data class Episode(
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
    )''')
replace_once(core,
'''        frameHealthy: Boolean = true,
    ): FlowDecision {''',
'''        frameHealthy: Boolean = true,
        bodies: Map<Int, List<PortalBodyEvidence>> = emptyMap(),
    ): FlowDecision {''')
replace_once(core,
'''        val gap = lastTime >= 0 && timeMs - lastTime > policy.gapMs
        lastTime = timeMs
        observedTracks = detections?.map { it.id }?.toSet() ?: emptySet()
        if (gap || !frameHealthy) {
            people.values.forEach { p ->
                p.episode = null; p.possible = emptySet(); p.groundHistory.clear(); p.centerHistory.clear()
                p.status = if (gap) "FRAME_GAP" else "FRAME_UNRELIABLE"
            }
            if (!frameHealthy) return snapshot(listOf("FRAME_UNRELIABLE_NO_TRANSITIONS"))
        }''',
'''        val analysisGapMs = if(lastTime>=0) timeMs-lastTime else -1L
        lastTime = timeMs
        observedTracks = detections?.map { it.id }?.toSet() ?: emptySet()
        if (!frameHealthy) {
            people.values.filter{it.accepted}.forEach { p -> if(p.waitClear==null)p.status="FRAME_UNRELIABLE_HOLD" }
            return snapshot(listOf("FRAME_UNRELIABLE_HOLD_STATE"))
        }
        if(analysisGapMs>policy.gapMs) notes += "SPARSE_ANALYSIS_GAP:${analysisGapMs}ms"''')
replace_once(core,
'''            val currentDepths = depths[p.track].orEmpty()
            val flow = flows[p.track]''',
'''            val currentDepths = depths[p.track].orEmpty()
            val currentBodies = bodies[p.track].orEmpty()
            val flow = flows[p.track]''')
replace_once(core,
'''                ensureEpisode(p, timeMs, currentDepths)
                evaluateEpisode(p, timeMs, currentDepths, flow)''',
'''                ensureEpisode(p, timeMs, currentDepths, currentBodies)
                evaluateEpisode(p, timeMs, currentDepths, currentBodies, flow)''')
replace_once(core,
'''                    while (p.groundHistory.isNotEmpty() && t - p.groundHistory.first().t > 1800) p.groundHistory.removeFirst()''',
'''                    while (p.groundHistory.isNotEmpty() && t - p.groundHistory.first().t > policy.depthHistoryMs) p.groundHistory.removeFirst()''')
replace_once(core,
'''    private fun ensureEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>) {''',
'''    private fun ensureEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>, bodies: List<PortalBodyEvidence>) {''')
replace_once(core,
'''            val depth = depths.firstOrNull { it.gateId == gate.id && it.exclusive && it.ownedPixels >= policy.depthMinPixels }
            val g = p.ground
            val groundNear = g != null && gate.along(g.point) in -0.18..1.18 && gate.distance(g.point) <= contactBand(p)
            val originBoost = p.originGate == gate.id
            if (depth == null && !groundNear && !originBoost) null else {
                val score = (if (originBoost) 20.0 else 0.0) + (if (depth != null) 10.0 + depth.p80 else 0.0) +
                    (if (groundNear) 5.0 - gate.distance(g!!.point) else 0.0)
                Candidate(gate, score)
            }''',
'''            val depth = depths.firstOrNull { it.gateId == gate.id && it.exclusive && it.ownedPixels >= policy.depthMinPixels }
            val body = bodies.firstOrNull { it.gateId == gate.id && it.exclusive && it.absorption >= 0.16 }
            val g = p.ground
            val groundNear = g != null && gate.along(g.point) in -0.18..1.18 && gate.distance(g.point) <= contactBand(p)
            val originBoost = p.originGate == gate.id
            if (depth == null && body == null && !groundNear && !originBoost) null else {
                val score = (if (originBoost) 20.0 else 0.0) + (if (depth != null) 10.0 + depth.p80 else 0.0) +
                    (if (body != null) 6.0 + body.absorption * 4.0 else 0.0) +
                    (if (groundNear) 5.0 - gate.distance(g!!.point) else 0.0)
                Candidate(gate, score)
            }''')
replace_once(core,
'''    private fun evaluateEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>, flow: FlowEvidence?) {
        val e = p.episode ?: return
        if (t - e.startedAt > policy.episodeTimeoutMs) {
            p.episode = null; p.possible = emptySet(); p.status = "PORTAL_EPISODE_TIMEOUT"; return
        }
        val currentDepth = depths.firstOrNull { it.gateId == e.gate.id && it.exclusive && it.ownedPixels >= policy.depthMinPixels }
        if (currentDepth != null) {
            e.depths.add(DepthObs(t, currentDepth)); e.phase = PortalEpisodePhase.TRANSITING
            while (e.depths.isNotEmpty() && t - e.depths.first().t > policy.episodeTimeoutMs) e.depths.removeFirst()
            p.lastDepth = currentDepth.median
        }

        val g = p.ground''',
'''    private fun evaluateEpisode(p: Person, t: Long, depths: List<PortalDepthEvidence>, bodies: List<PortalBodyEvidence>, flow: FlowEvidence?) {
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
        }''')
# The previous replacement includes `val g = p.ground`; remove the duplicate that follows in source.
replace_once(core,
'''        if (g?.strong == true) {''',
'''        if (g?.strong == true) {''')
# Insert pass-by + witnessed depth logic before the old immediate depth commit.
replace_once(core,
'''        if (depthCommits(e)) {
            commit(p, e, t, inferred = true, evidence = "PORTAL_DEPTH_MIGRATION")
            return
        }

        if (e.from == livingId && p.detectorMissingSince >= 0 && t - p.detectorMissingSince >= policy.disappearanceMs) {''',
'''        if(e.from==livingId&&currentBody!=null&&bodyScore!=null&&e.peakAbsorption>=policy.absorptionArmRatio){
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

        if (e.from == livingId && p.detectorMissingSince >= 0 && t - p.detectorMissingSince >= policy.disappearanceMs) {''')
replace_once(core,
'''        p.status = if (e.to == livingId) "TRANSITING_OUT" else "TRANSITING_IN"
        p.lastEvidence = currentDepth?.let { "DEPTH:${"%.2f".format(it.median)}" }''',
'''        p.status = if (e.to == livingId) "TRANSITING_OUT" else "TRANSITING_IN"
        p.lastEvidence = when {
            currentBody!=null -> "BODY:${"%.2f".format(bodyScore)}"
            currentDepth!=null -> "DEPTH:${"%.2f".format(currentDepth.median)}"
            else -> p.lastEvidence
        }''')

# 6) Wire body evidence through the room algorithm and give the runtime a distinct tag.
room = 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateRoomAlgorithm.kt'
replace_once(room,
'''    override val runtimeTag="GateV4.2-${config.method.name}-EPISODE_CORE"''',
'''    override val runtimeTag="GateV4.3-${config.method.name}-CONTINUITY_ABSORPTION"''')
replace_once(room,
'''        val decision=engine.step(time,detections=if(successful) anchored else null,depths=visual?.depths.orEmpty(),flows=visual?.flows.orEmpty(),coverage=roi,frameHealthy=successful && visual?.healthy!=false)''',
'''        val decision=engine.step(time,detections=if(successful) anchored else null,depths=visual?.depths.orEmpty(),flows=visual?.flows.orEmpty(),coverage=roi,
            frameHealthy=successful && visual?.healthy!=false,bodies=visual?.bodies.orEmpty())''')

# 7) Core unit tests: sparse frames and a failed inference keep continuity; body absorption waits and pass-by cancels.
test = 'app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4CoreTest.kt'
replace_once(test,
'''    private fun depth(t:Long,v:Double,gate:String="A",id:Int=1,pixels:Int=240)=PortalDepthEvidence(
        gate,id,t,(v-.10).coerceAtLeast(0.0),v,(v+.16).coerceAtMost(1.0),pixels,true
    )''',
'''    private fun depth(t:Long,v:Double,gate:String="A",id:Int=1,pixels:Int=240)=PortalDepthEvidence(
        gate,id,t,(v-.10).coerceAtLeast(0.0),v,(v+.16).coerceAtMost(1.0),pixels,true
    )
    private fun body(t:Long,ratio:Double,along:Double=.5,gate:String="A",id:Int=1)=PortalBodyEvidence(
        gate,id,t,ratio,ratio,8,240,(240*ratio).toInt(),along,0.02,true
    )''')
replace_once(test,
'''    @Test fun frameGapCancelsEpisodeWithoutMovingLedger() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),depths=mapOf(1 to listOf(depth(300,.10))))
        val r=c.step(800,listOf(person(.47)),depths=mapOf(1 to listOf(depth(800,.70))))
        assertTrue(r.events.isEmpty())
        assertEquals(1,r.counts["L"])
    }
}''',
'''    @Test fun sparseAnalysisGapKeepsGroundCrossingContinuity() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        val r=c.step(800,listOf(person(.47)))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
    }

    @Test fun failedInferenceHoldsEpisodeInsteadOfErasingHistory() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        val held=c.step(650,null,frameHealthy=false)
        assertTrue(held.events.isEmpty())
        val r=c.step(900,listOf(person(.47)))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
    }

    @Test fun wholeBodyAbsorptionCommitsOnlyAfterDisappearanceWitness() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.30,.42))))
        val peak=c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.88,.48))))
        assertTrue(peak.events.isEmpty())
        c.step(800,emptyList())
        val r=c.step(1450,emptyList())
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
        assertEquals(1,r.counts["A"])
        assertTrue(r.events.single().inferred)
    }

    @Test fun bodyThatTraversesAlongDoorIsPassByNotEntry() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.28,.18))))
        c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.86,.48))))
        val r=c.step(850,listOf(person(.525)),bodies=mapOf(1 to listOf(body(850,.30,.84))))
        assertTrue(r.events.isEmpty())
        assertEquals(1,r.counts["L"])
        assertEquals(0,r.counts["A"])
        assertTrue(r.notes.any{it.startsWith("PASS_BY:")})
    }
}''')

# 8) Record the design intent in project history without touching door/light structural modelling yet.
for name, entry in {
    'codexHistory.md': '''\n\n## 2026-09-17 V4.3 continuity + whole-body absorption\n- Sparse YOLO/Pose analysis intervals are no longer treated as video discontinuities. Epoch/seek/time reversal remains the hard continuity boundary.\n- Failed inference holds the portal episode instead of erasing Ground/Depth/body history.\n- Portal episode lifetime is evidence-driven with an idle timeout plus a separate hard stale cap.\n- Added whole-body portal absorption evidence from all visible pose points plus local body-mask overlap; feet are not required.\n- Visual-only entry is delayed by a witness interval. If the same body emerges along the other side of the aperture, it is classified as pass-by and the pending transfer is cancelled. This specifically protects the occluded-foot children-room doorway case.\n- Door opening/closing and lighting-state modelling remain separate future work; the new absorption evidence itself is person-bound and does not promote raw door motion to a transfer.\n''',
    'dialogueHistory.md': '''\n\n### 2026-09-17 用户补充的真实场景\n- 视频开头第一件事是人在入户门外，开门后进入室内；后续诊断解释按这个物理事实理解。\n- 正对、左侧、右侧门普遍会出现“人体所有可见 Pose/轮廓最终融入门洞”的现象，这是重要的进入证据。\n- 最右儿童房脚部长期被餐桌遮挡；人只是经过儿童房门口时，可见身体也可能完整投影进门洞，因此“身体进入门洞”不能立即提交房间切换，必须观察是否继续从门另一侧在客厅出现。\n'''
}.items():
    p=ROOT/name
    if p.exists():
        p.write_text(p.read_text(encoding='utf-8')+entry,encoding='utf-8')

print('V4.3 patch applied')
