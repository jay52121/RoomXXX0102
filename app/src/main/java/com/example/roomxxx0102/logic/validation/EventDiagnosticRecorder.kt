package com.example.roomxxx0102.logic.validation

import android.content.Context
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticBus
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticConfig
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticEvent
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticFrame
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticPerson
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateMaskDigest
import com.example.roomxxx0102.logic.roomalgorithm.gate.GatePortalDiagnostic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal data class DiagnosticMarkedMatch(
    val markedIndex: Int,
    val runtimeIndex: Int?,
    val classification: String,
)

internal data class DiagnosticMatchResult(
    val marked: List<DiagnosticMarkedMatch>,
    val duplicateRuntimeIndices: Set<Int>,
    val falsePositiveRuntimeIndices: Set<Int>,
)

/** 只做客观的一对一时间/方向匹配，不尝试判断“失败属于哪一层”。 */
internal object EventDiagnosticMatcher {
    fun match(
        marked: List<MarkedEvent>,
        runtime: List<GateDiagnosticEvent>,
        windowMs: Long,
    ): DiagnosticMatchResult {
        val used = mutableSetOf<Int>()
        val markedMatches = marked.mapIndexed { markedIndex, gt ->
            val sameDirection = runtime.indices
                .filter { index -> index !in used }
                .filter { index -> runtime[index].direction == gt.type.name }
                .filter { index -> abs(runtime[index].timeMs - gt.timestampMs) <= windowMs }
                .minByOrNull { index -> abs(runtime[index].timeMs - gt.timestampMs) }
            if (sameDirection != null) {
                used += sameDirection
                DiagnosticMarkedMatch(markedIndex, sameDirection, "MATCH")
            } else {
                val wrongDirection = runtime.indices
                    .filter { index -> index !in used }
                    .filter { index -> abs(runtime[index].timeMs - gt.timestampMs) <= windowMs }
                    .minByOrNull { index -> abs(runtime[index].timeMs - gt.timestampMs) }
                if (wrongDirection != null) {
                    used += wrongDirection
                    DiagnosticMarkedMatch(markedIndex, wrongDirection, "WRONG_DIRECTION")
                } else {
                    DiagnosticMarkedMatch(markedIndex, null, "MISS")
                }
            }
        }
        val duplicates = mutableSetOf<Int>()
        val falsePositives = mutableSetOf<Int>()
        runtime.indices.filter { it !in used }.forEach { index ->
            val event = runtime[index]
            val nearAnyMarked = marked.any { gt -> abs(event.timeMs - gt.timestampMs) <= windowMs }
            if (nearAnyMarked) duplicates += index else falsePositives += index
        }
        return DiagnosticMatchResult(markedMatches, duplicates, falsePositives)
    }
}

/**
 * 事件中心诊断录制器。
 *
 * 只保存人工事件 [-1.5s,+2.5s] 与孤立算法误报 [-1.5s,+1.5s] 的 V4 分析帧。
 * 不保存视频、不保存 Logcat、不保存完整 Pose、不保存逐像素 Mask。
 */
internal class EventDiagnosticRecorder(
    private val context: Context,
    markedEvents: List<MarkedEvent>,
    private val roomNames: Map<String, String>,
    private val gateNames: Map<String, String>,
) {
    companion object {
        const val GT_PRE_MS = 1500L
        const val GT_POST_MS = 2500L
        const val FP_PRE_MS = 1500L
        const val FP_POST_MS = 1500L
        const val MATCH_WINDOW_MS = 1000L
        const val PORTAL_INFERENCE_WINDOW_MS = 350L
    }

    private data class Window(val anchorMs: Long, val startMs: Long, val endMs: Long, val runtimeKey: String? = null)

    private val marked = markedEvents.sortedBy { it.timestampMs }
    private val gtWindows = marked.map { Window(it.timestampMs, it.timestampMs - GT_PRE_MS, it.timestampMs + GT_POST_MS) }
    private val rolling = ArrayDeque<GateDiagnosticFrame>()
    private val captured = linkedMapOf<Long, GateDiagnosticFrame>()
    private val runtimeEvents = mutableListOf<GateDiagnosticEvent>()
    private val runtimeKeys = mutableSetOf<String>()
    private val fpWindows = mutableListOf<Window>()
    private val fpKeys = mutableSetOf<String>()
    private var firstRuntimeTag: String? = null

    // 人工事件门位推断与 V4 算法完全独立：只使用静态 Portal 多边形 + 当时人体框。
    private val inferenceRooms = RoomRepository.getAllRooms()
    private val inferenceRegions = MarkedPortalTruthInference.regions(inferenceRooms)
    private val livingRoomName = inferenceRooms.firstOrNull { it.isSovereignTerritory }?.name ?: "客厅"
    private val inferenceBest = mutableMapOf<Int, InferredPortalTruth>()
    private val inferenceFinal = mutableMapOf<Int, InferredPortalTruth>()
    private val inferencePublished = mutableSetOf<Int>()

    init {
        MarkedPortalInferenceOverlayBus.clear()
    }

    @Synchronized
    fun recordFrame(frame: GateDiagnosticFrame) {
        firstRuntimeTag = firstRuntimeTag ?: frame.runtimeTag
        updatePortalInference(frame)

        rolling.addLast(frame)
        while (rolling.isNotEmpty() && frame.timeMs - rolling.first().timeMs > FP_PRE_MS) {
            rolling.removeFirst()
        }

        if (gtWindows.any { frame.timeMs in it.startMs..it.endMs } ||
            fpWindows.any { frame.timeMs in it.startMs..it.endMs }) {
            captured[frame.frameSeq] = frame
        }

        frame.events.forEach { event ->
            val key = runtimeKey(event)
            if (runtimeKeys.add(key)) runtimeEvents += event
            val nearAnyGt = marked.any { gt -> abs(event.timeMs - gt.timestampMs) <= MATCH_WINDOW_MS }
            if (!nearAnyGt && fpKeys.add(key)) {
                val window = Window(event.timeMs, event.timeMs - FP_PRE_MS, event.timeMs + FP_POST_MS, key)
                fpWindows += window
                rolling.forEach { past ->
                    if (past.timeMs in window.startMs..window.endMs) captured[past.frameSeq] = past
                }
                captured[frame.frameSeq] = frame
            }
        }
    }

    private fun updatePortalInference(frame: GateDiagnosticFrame) {
        marked.forEachIndexed { index, gt ->
            if (index in inferencePublished) return@forEachIndexed
            val start = gt.timestampMs - PORTAL_INFERENCE_WINDOW_MS
            val end = gt.timestampMs + PORTAL_INFERENCE_WINDOW_MS
            if (frame.timeMs in start..end) {
                MarkedPortalTruthInference.infer(frame.people, inferenceRegions, frame.timeMs)?.let { candidate ->
                    val previous = inferenceBest[index]
                    if (previous == null || candidate.score > previous.score) {
                        inferenceBest[index] = candidate
                    }
                }
            }
            if (frame.timeMs > end) {
                finalizePortalInference(index, gt)
            }
        }
    }

    private fun finalizePortalInference(index: Int, gt: MarkedEvent) {
        if (index in inferencePublished) return
        val inferred = inferenceBest[index]
        if (inferred != null) {
            inferenceFinal[index] = inferred
            val route = if (gt.type == EventType.ENTER) {
                "$livingRoomName → ${inferred.portalName}"
            } else {
                "${inferred.portalName} → $livingRoomName"
            }
            val scoreText = String.format(Locale.US, "%.2f", inferred.score)
            MarkedPortalInferenceOverlayBus.publish(
                "【推断进出】#${index + 1} $route ｜ 人#${inferred.person} ｜ 几何重叠=$scoreText"
            )
        } else {
            MarkedPortalInferenceOverlayBus.publish(
                "【推断进出】#${index + 1} 未找到人体与房门的几何重叠"
            )
        }
        inferencePublished += index
    }

    @Synchronized
    fun finish(): File {
        marked.forEachIndexed { index, gt ->
            if (index !in inferencePublished) finalizePortalInference(index, gt)
        }

        val result = EventDiagnosticMatcher.match(marked, runtimeEvents, MATCH_WINDOW_MS)
        val root = JSONObject()
        root.put("schema", 2)
        root.put("algorithm", firstRuntimeTag ?: "V4-A")
        root.put("portalTruthAvailable", false)
        root.put("portalInferenceAvailable", inferenceFinal.isNotEmpty())
        root.put(
            "note",
            "人工事件门位由人工时间点±${PORTAL_INFERENCE_WINDOW_MS}ms内的人体框×静态Portal几何独立推断；未使用V4候选门/锁门/FSM/输出。当前先供人工核对，尚不作为硬GT参与MATCH分类。"
        )
        root.put("windowMs", JSONObject()
            .put("gtPre", GT_PRE_MS)
            .put("gtPost", GT_POST_MS)
            .put("portalInference", PORTAL_INFERENCE_WINDOW_MS)
            .put("falsePositivePre", FP_PRE_MS)
            .put("falsePositivePost", FP_POST_MS)
            .put("match", MATCH_WINDOW_MS))
        GateDiagnosticBus.configSnapshot()?.let { root.put("config", configJson(it)) }
        root.put("rooms", mapJson(roomNames))
        root.put("gates", mapJson(gateNames))
        root.put("frameColumns", JSONArray(listOf("dt","seq","activeGates","people","portals","outputs","counts","notes")))
        root.put("personColumns", JSONArray(listOf(
            "person","track","room","status","accepted","candidates","l","t","r","b",
            "gx","gy","gStrong","gUnc","gSource","gate","phase","direction","depth","groundSide",
            "groundDist","groundAlong","evidence"
        )))
        root.put("portalColumns", JSONArray(listOf(
            "gate","owner","phase","schedulerDist","contact","referenceKnown","exclusive","foreground",
            "peak","clearForMs","historyFrames","bodyMotionRatio","motion","owned"
        )))
        root.put("maskColumns", JSONArray(listOf("pixels","p20","p50","p80","grid8x4")))
        root.put("outputColumns", JSONArray(listOf("person","track","from","to","gate","direction","dt","inferred","reason")))

        val eventsJson = JSONArray()
        result.marked.forEach { match ->
            val gt = marked[match.markedIndex]
            val start = gt.timestampMs - GT_PRE_MS
            val end = gt.timestampMs + GT_POST_MS
            val frames = framesIn(start, end)
            val gtJson = JSONObject()
                .put("type", gt.type.name)
                .put("t", gt.timestampMs)
                .put("frame", gt.frameIndex)
            val inferred = inferenceFinal[match.markedIndex]
            if (inferred != null) {
                gtJson.put("inferredPortal", JSONObject()
                    .put("roomId", inferred.portalRoomId)
                    .put("name", inferred.portalName)
                    .put("person", inferred.person)
                    .put("track", inferred.track)
                    .put("score", n(inferred.score))
                    .put("personCoverage", n(inferred.personCoverage))
                    .put("portalCoverage", n(inferred.portalCoverage))
                    .put("dt", inferred.sampleTimeMs - gt.timestampMs))
            } else {
                gtJson.put("inferredPortal", JSONObject.NULL)
            }
            val obj = JSONObject()
                .put("id", match.markedIndex + 1)
                .put("gt", gtJson)
                .put("classification", match.classification)
                .put("window", JSONArray(listOf(-GT_PRE_MS, GT_POST_MS)))
            match.runtimeIndex?.let { index -> obj.put("matchedOutput", eventJson(runtimeEvents[index], gt.timestampMs)) }
            val outputs = runtimeEvents.filter { it.timeMs in start..end }
            obj.put("outputs", JSONArray(outputs.map { eventJson(it, gt.timestampMs) }))
            obj.put("audit", auditJson(frames, gt.timestampMs))
            obj.put("frames", JSONArray(frames.map { frameJson(it, gt.timestampMs) }))
            eventsJson.put(obj)
        }
        root.put("events", eventsJson)

        val fpJson = JSONArray()
        result.falsePositiveRuntimeIndices.sorted().forEach { runtimeIndex ->
            val event = runtimeEvents[runtimeIndex]
            val start = event.timeMs - FP_PRE_MS
            val end = event.timeMs + FP_POST_MS
            val frames = framesIn(start, end)
            fpJson.put(JSONObject()
                .put("output", eventJson(event, event.timeMs))
                .put("window", JSONArray(listOf(-FP_PRE_MS, FP_POST_MS)))
                .put("audit", auditJson(frames, event.timeMs))
                .put("frames", JSONArray(frames.map { frameJson(it, event.timeMs) })))
        }
        root.put("falsePositives", fpJson)

        val summary = JSONObject()
            .put("groundTruth", marked.size)
            .put("portalInferred", inferenceFinal.size)
            .put("portalInferenceMissing", marked.size - inferenceFinal.size)
            .put("runtimeOutputs", runtimeEvents.size)
            .put("match", result.marked.count { it.classification == "MATCH" })
            .put("miss", result.marked.count { it.classification == "MISS" })
            .put("wrongDirection", result.marked.count { it.classification == "WRONG_DIRECTION" })
            .put("duplicate", result.duplicateRuntimeIndices.size)
            .put("falsePositive", result.falsePositiveRuntimeIndices.size)
        root.put("summary", summary)

        val dir = context.externalMediaDirs.firstOrNull()?.let { File(it, "diagnostics") }
            ?: context.getExternalFilesDir(null)?.let { File(it, "diagnostics") }
            ?: File(context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "V4A_事件诊断_$stamp.json")
        file.writeText(root.toString(), Charsets.UTF_8)
        return file
    }

    private fun framesIn(startMs: Long, endMs: Long): List<GateDiagnosticFrame> =
        captured.values.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }

    private fun runtimeKey(event: GateDiagnosticEvent): String =
        "${event.timeMs}|${event.track}|${event.from}|${event.to}|${event.gateId}"

    private fun frameJson(frame: GateDiagnosticFrame, anchorMs: Long): JSONArray {
        return JSONArray()
            .put(frame.timeMs - anchorMs)
            .put(frame.frameSeq)
            .put(frame.activeGates)
            .put(JSONArray(frame.people.map(::personJson)))
            .put(JSONArray(frame.portals.map(::portalJson)))
            .put(JSONArray(frame.events.map { eventJson(it, anchorMs) }))
            .put(countsJson(frame.counts))
            .put(JSONArray(frame.notes))
    }

    private fun personJson(p: GateDiagnosticPerson): JSONArray = JSONArray()
        .put(p.person).put(p.track).put(nullable(p.room)).put(p.status).put(p.accepted)
        .put(JSONArray(p.candidates))
        .put(n(p.box.left)).put(n(p.box.top)).put(n(p.box.right)).put(n(p.box.bottom))
        .put(nullableNumber(p.groundX)).put(nullableNumber(p.groundY)).put(nullable(p.groundStrong))
        .put(nullableNumber(p.groundUncertainty)).put(nullable(p.groundSource)).put(nullable(p.gateId))
        .put(nullable(p.phase)).put(nullable(p.direction)).put(nullableNumber(p.depth))
        .put(nullableNumber(p.groundSide)).put(nullableNumber(p.groundDistance)).put(nullableNumber(p.groundAlong))
        .put(nullable(p.evidence))

    private fun portalJson(p: GatePortalDiagnostic): JSONArray = JSONArray()
        .put(p.gateId).put(nullable(p.ownerTrack)).put(p.phase).put(nullableNumber(p.schedulerDistance))
        .put(p.contact).put(p.referenceKnown).put(nullable(p.exclusive)).put(p.foregroundPixels)
        .put(p.peakPixels).put(p.clearForMs).put(p.historyFrames).put(nullableNumber(p.bodyMotionRatio))
        .put(maskJson(p.motion)).put(maskJson(p.owned))

    private fun maskJson(mask: GateMaskDigest?): Any {
        if (mask == null) return JSONObject.NULL
        return JSONArray()
            .put(mask.pixels)
            .put(nullableNumber(mask.p20))
            .put(nullableNumber(mask.p50))
            .put(nullableNumber(mask.p80))
            .put(JSONArray(mask.grid))
    }

    private fun eventJson(event: GateDiagnosticEvent, anchorMs: Long): JSONArray = JSONArray()
        .put(event.person).put(event.track).put(event.from).put(event.to).put(event.gateId)
        .put(event.direction).put(event.timeMs - anchorMs).put(event.inferred).put(nullable(event.reason))

    private fun countsJson(counts: Map<String, Int>): JSONArray = JSONArray(
        counts.entries.sortedBy { it.key }.map { JSONArray(listOf(it.key, it.value)) }
    )

    private data class PortalAudit(
        var firstSeen: Long? = null,
        var firstContact: Long? = null,
        var maxMotionPixels: Int = 0,
        var maxMotionP50: Double? = null,
        var maxMotionP80: Double? = null,
        var maxOwnedPixels: Int = 0,
        var maxOwnedP50: Double? = null,
        var maxOwnedP80: Double? = null,
        var maxClearForMs: Long = 0,
    )

    private fun auditJson(frames: List<GateDiagnosticFrame>, anchorMs: Long): JSONObject {
        val audits = linkedMapOf<String, PortalAudit>()
        var firstTransiting: Long? = null
        var firstWaitClear: Long? = null
        var lastStrongGround: Long? = null
        frames.forEach { frame ->
            val dt = frame.timeMs - anchorMs
            frame.people.forEach { person ->
                if (person.phase == "TRANSITING" && firstTransiting == null) firstTransiting = dt
                if (person.phase == "WAIT_CLEAR" && firstWaitClear == null) firstWaitClear = dt
                if (person.groundStrong == true) lastStrongGround = dt
            }
            frame.portals.forEach { portal ->
                val a = audits.getOrPut(portal.gateId) { PortalAudit() }
                if (a.firstSeen == null) a.firstSeen = dt
                if (portal.contact && a.firstContact == null) a.firstContact = dt
                portal.motion?.let { m ->
                    a.maxMotionPixels = maxOf(a.maxMotionPixels, m.pixels)
                    a.maxMotionP50 = maxNullable(a.maxMotionP50, m.p50)
                    a.maxMotionP80 = maxNullable(a.maxMotionP80, m.p80)
                }
                portal.owned?.let { o ->
                    a.maxOwnedPixels = maxOf(a.maxOwnedPixels, o.pixels)
                    a.maxOwnedP50 = maxNullable(a.maxOwnedP50, o.p50)
                    a.maxOwnedP80 = maxNullable(a.maxOwnedP80, o.p80)
                }
                a.maxClearForMs = maxOf(a.maxClearForMs, portal.clearForMs)
            }
        }
        val portalArray = JSONArray()
        audits.forEach { (gateId, a) ->
            portalArray.put(JSONObject()
                .put("gate", gateId)
                .put("name", gateNames[gateId] ?: gateId)
                .put("firstSeenDt", nullable(a.firstSeen))
                .put("firstContactDt", nullable(a.firstContact))
                .put("maxMotionPixels", a.maxMotionPixels)
                .put("maxMotionP50", nullableNumber(a.maxMotionP50))
                .put("maxMotionP80", nullableNumber(a.maxMotionP80))
                .put("maxOwnedPixels", a.maxOwnedPixels)
                .put("maxOwnedP50", nullableNumber(a.maxOwnedP50))
                .put("maxOwnedP80", nullableNumber(a.maxOwnedP80))
                .put("maxClearForMs", a.maxClearForMs))
        }
        return JSONObject()
            .put("firstTransitingDt", nullable(firstTransiting))
            .put("firstWaitClearDt", nullable(firstWaitClear))
            .put("lastStrongGroundDt", nullable(lastStrongGround))
            .put("portals", portalArray)
    }

    private fun maxNullable(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun configJson(c: GateDiagnosticConfig): JSONObject = JSONObject()
        .put("method", c.methodId)
        .put("sampleMs", c.sampleMs)
        .put("maxGapMs", c.maxGapMs)
        .put("pixelThreshold", c.pixelThreshold)
        .put("clearMs", c.clearMs)
        .put("clearRatio", c.clearRatio)
        .put("contactScale", c.contactScale)
        .put("admissionTravel", c.admissionTravel)
        .put("episodeMs", c.episodeMs)
        .put("historyMs", c.historyMs)
        .put("holdMs", c.holdMs)
        .put("armScore", c.armScore)
        .put("armDistanceScale", c.armDistanceScale)
        .put("maxActiveGates", c.maxActiveGates)
        .put("depthMinPixels", c.depthMinPixels)
        .put("depthEnterCommit", c.depthEnterCommit)
        .put("depthExitCommit", c.depthExitCommit)
        .put("depthTravel", c.depthTravel)
        .put("depthMinSamples", c.depthMinSamples)
        .put("depthMinSpanMs", c.depthMinSpanMs)
        .put("waitClearMs", c.waitClearMs)
        .put("disappearanceMs", c.disappearanceMs)

    private fun mapJson(map: Map<String, String>): JSONObject = JSONObject().apply {
        map.entries.sortedBy { it.key }.forEach { (key, value) -> put(key, value) }
    }

    private fun n(value: Double): Double = round(value * 10000.0) / 10000.0
    private fun nullableNumber(value: Double?): Any = value?.let(::n) ?: JSONObject.NULL
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL
}
