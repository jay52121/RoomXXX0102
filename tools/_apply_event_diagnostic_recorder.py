from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    if old not in text:
        raise RuntimeError(f"replacement anchor not found: {path}: {old[:80]!r}")
    text = text.replace(old, new, 1)
    write(path, text)


GATE_DIAGNOSTIC_BUS = r'''package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowBox

/**
 * 诊断回放专用的小型数据总线。
 *
 * 默认关闭；关闭时视觉链路不会额外扫描 Mask。开启后，每个 V4 分析帧只保留一份
 * 不含 Bitmap/Mat 的结构化快照，由 UI 线程的 EventDiagnosticRecorder 立即取走。
 */
internal data class GateMaskDigest(
    val pixels: Int,
    val p20: Double?,
    val p50: Double?,
    val p80: Double?,
    val grid: List<Int>,
)

internal data class GatePortalDiagnostic(
    val gateId: String,
    val ownerTrack: Int?,
    val phase: String,
    val schedulerDistance: Double?,
    val contact: Boolean,
    val referenceKnown: Boolean,
    val exclusive: Boolean?,
    val foregroundPixels: Int,
    val peakPixels: Int,
    val clearForMs: Long,
    val historyFrames: Int,
    val bodyMotionRatio: Double?,
    val motion: GateMaskDigest?,
    val owned: GateMaskDigest?,
)

internal data class GateDiagnosticPerson(
    val person: Int,
    val track: Int,
    val room: String?,
    val status: String,
    val accepted: Boolean,
    val candidates: List<String>,
    val box: FlowBox,
    val groundX: Double?,
    val groundY: Double?,
    val groundStrong: Boolean?,
    val groundUncertainty: Double?,
    val groundSource: String?,
    val gateId: String?,
    val phase: String?,
    val direction: String?,
    val depth: Double?,
    val groundSide: Double?,
    val groundDistance: Double?,
    val groundAlong: Double?,
    val evidence: String?,
)

internal data class GateDiagnosticEvent(
    val person: Int,
    val track: Int,
    val from: String,
    val to: String,
    val gateId: String,
    val direction: String,
    val timeMs: Long,
    val inferred: Boolean,
    val reason: String?,
)

internal data class GateDiagnosticConfig(
    val methodId: String,
    val sampleMs: Int,
    val maxGapMs: Int,
    val pixelThreshold: Int,
    val clearMs: Int,
    val clearRatio: Double,
    val contactScale: Double,
    val admissionTravel: Double,
    val episodeMs: Int,
    val historyMs: Int,
    val holdMs: Int,
    val armScore: Double,
    val armDistanceScale: Double,
    val maxActiveGates: Int,
    val depthMinPixels: Int,
    val depthEnterCommit: Double,
    val depthExitCommit: Double,
    val depthTravel: Double,
    val depthMinSamples: Int,
    val depthMinSpanMs: Long,
    val waitClearMs: Long,
    val disappearanceMs: Long,
) {
    companion object {
        fun from(config: GateConfig): GateDiagnosticConfig {
            val policy = PortalV4Policy.from(config)
            return GateDiagnosticConfig(
                methodId = config.method.id,
                sampleMs = config.sampleMs,
                maxGapMs = config.maxGapMs,
                pixelThreshold = config.pixelThreshold,
                clearMs = config.clearMs,
                clearRatio = config.clearRatio,
                contactScale = config.contactScale,
                admissionTravel = config.admissionTravel,
                episodeMs = config.episodeMs,
                historyMs = config.historyMs,
                holdMs = config.holdMs,
                armScore = config.armScore,
                armDistanceScale = config.armDistanceScale,
                maxActiveGates = config.maxActiveGates,
                depthMinPixels = policy.depthMinPixels,
                depthEnterCommit = policy.depthEnterCommit,
                depthExitCommit = policy.depthExitCommit,
                depthTravel = policy.depthTravel,
                depthMinSamples = policy.depthMinSamples,
                depthMinSpanMs = policy.depthMinSpanMs,
                waitClearMs = policy.waitClearMs,
                disappearanceMs = policy.disappearanceMs,
            )
        }
    }
}

internal data class GateDiagnosticFrame(
    val timeMs: Long,
    val frameSeq: Long,
    val runtimeTag: String,
    val activeGates: Int,
    val people: List<GateDiagnosticPerson>,
    val portals: List<GatePortalDiagnostic>,
    val events: List<GateDiagnosticEvent>,
    val counts: Map<String, Int>,
    val notes: List<String>,
)

internal object GateDiagnosticBus {
    @Volatile private var capturing = false
    @Volatile private var latestFrame: GateDiagnosticFrame? = null
    @Volatile private var currentConfig: GateDiagnosticConfig? = null

    fun isCapturing(): Boolean = capturing

    @Synchronized
    fun beginCapture() {
        capturing = true
        latestFrame = null
        currentConfig = null
    }

    @Synchronized
    fun endCapture() {
        capturing = false
        latestFrame = null
        currentConfig = null
    }

    @Synchronized
    fun configure(config: GateConfig) {
        if (!capturing) return
        currentConfig = GateDiagnosticConfig.from(config)
    }

    @Synchronized
    fun publish(frame: GateDiagnosticFrame) {
        if (!capturing) return
        latestFrame = frame
    }

    @Synchronized
    fun frameFor(frameSeq: Long): GateDiagnosticFrame? {
        if (!capturing) return null
        return latestFrame?.takeIf { it.frameSeq == frameSeq }
    }

    @Synchronized
    fun configSnapshot(): GateDiagnosticConfig? = currentConfig
}
'''

EVENT_DIAGNOSTIC_RECORDER = r'''package com.example.roomxxx0102.logic.validation

import android.content.Context
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

    @Synchronized
    fun recordFrame(frame: GateDiagnosticFrame) {
        firstRuntimeTag = firstRuntimeTag ?: frame.runtimeTag
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

    @Synchronized
    fun finish(): File {
        val result = EventDiagnosticMatcher.match(marked, runtimeEvents, MATCH_WINDOW_MS)
        val root = JSONObject()
        root.put("schema", 1)
        root.put("algorithm", firstRuntimeTag ?: "V4-A")
        root.put("portalTruthAvailable", false)
        root.put("note", "人工事件当前只含ENTER/EXIT和时间；目标门不作为真值，避免用算法候选门污染GT。")
        root.put("windowMs", JSONObject()
            .put("gtPre", GT_PRE_MS)
            .put("gtPost", GT_POST_MS)
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
            val obj = JSONObject()
                .put("id", match.markedIndex + 1)
                .put("gt", JSONObject()
                    .put("type", gt.type.name)
                    .put("t", gt.timestampMs)
                    .put("frame", gt.frameIndex))
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
'''

MATCHER_TEST = r'''package com.example.roomxxx0102.logic.validation

import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiagnosticMatcherTest {
    private fun gt(type: EventType, t: Long) = MarkedEvent(type, (t / 50).toInt(), t)
    private fun out(direction: String, t: Long) = GateDiagnosticEvent(
        person = 1,
        track = 7,
        from = if (direction == "ENTER") "living" else "room",
        to = if (direction == "ENTER") "room" else "living",
        gateId = "room#1",
        direction = direction,
        timeMs = t,
        inferred = false,
        reason = "TEST",
    )

    @Test fun oneToOneMatchAndDuplicateAreSeparated() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 1000)),
            runtime = listOf(out("ENTER", 1080), out("ENTER", 1200)),
            windowMs = 1000,
        )
        assertEquals("MATCH", result.marked.single().classification)
        assertEquals(0, result.marked.single().runtimeIndex)
        assertTrue(1 in result.duplicateRuntimeIndices)
        assertTrue(result.falsePositiveRuntimeIndices.isEmpty())
    }

    @Test fun oppositeDirectionIsObjectiveWrongDirection() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 2000)),
            runtime = listOf(out("EXIT", 2050)),
            windowMs = 1000,
        )
        assertEquals("WRONG_DIRECTION", result.marked.single().classification)
        assertTrue(result.falsePositiveRuntimeIndices.isEmpty())
    }

    @Test fun isolatedRuntimeOutputIsFalsePositive() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 1000)),
            runtime = listOf(out("ENTER", 5000)),
            windowMs = 1000,
        )
        assertEquals("MISS", result.marked.single().classification)
        assertTrue(0 in result.falsePositiveRuntimeIndices)
    }
}
'''

write("app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateDiagnosticBus.kt", GATE_DIAGNOSTIC_BUS)
write("app/src/main/java/com/example/roomxxx0102/logic/validation/EventDiagnosticRecorder.kt", EVENT_DIAGNOSTIC_RECORDER)
write("app/src/test/java/com/example/roomxxx0102/logic/validation/EventDiagnosticMatcherTest.kt", MATCHER_TEST)

# GateEventVisionV2: expose scheduler state and compact Portal-local mask digests only while capture is enabled.
path = "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateEventVisionV2.kt"
replace_once(path,
'''    val owner: Int?,
    val referenceKnown: Boolean,
)''',
'''    val owner: Int?,
    val referenceKnown: Boolean,
    val schedulerDistance: Double? = null,
    val contact: Boolean = false,
)''', marker="val schedulerDistance: Double? = null")
replace_once(path,
'''    val origin: Pair<Int,String>? = null,
    val depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
)''',
'''    val origin: Pair<Int,String>? = null,
    val depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
    val diagnostics: List<GatePortalDiagnostic> = emptyList(),
)''', marker="val diagnostics: List<GatePortalDiagnostic>")
replace_once(path,
'''    private data class ActiveResult(val view:GateEventTileView,val evidence:FlowEvidence,val note:String?,val depth:PortalDepthEvidence?)''',
'''    private data class ActiveResult(
        val view:GateEventTileView,
        val evidence:FlowEvidence,
        val note:String?,
        val depth:PortalDepthEvidence?,
        val diagnostic:GatePortalDiagnostic?,
    )''', marker="val diagnostic:GatePortalDiagnostic?")
replace_once(path,
'''        val depths=linkedMapOf<Int,MutableList<PortalDepthEvidence>>()
        val views=mutableListOf<GateEventTileView>();val samples=mutableListOf<FlowSample>();var origin:Pair<Int,String>?=null''',
'''        val depths=linkedMapOf<Int,MutableList<PortalDepthEvidence>>()
        val diagnostics=mutableListOf<GatePortalDiagnostic>()
        val views=mutableListOf<GateEventTileView>();val samples=mutableListOf<FlowSample>();var origin:Pair<Int,String>?=null''', marker="val diagnostics=mutableListOf<GatePortalDiagnostic>()")
replace_once(path,
'''                result.depth?.let{depths.getOrPut(owner){mutableListOf()}.add(it)}''',
'''                result.depth?.let{depths.getOrPut(owner){mutableListOf()}.add(it)}
                result.diagnostic?.let(diagnostics::add)''', marker="result.diagnostic?.let(diagnostics::add)")
replace_once(path,
'''            return GateEventVisionResult(flows,views,samples,healthy,elapsed(started),lk?.pointCount?:0,processing.size,historyBytes(),notes,origin,depths.mapValues{it.value.toList()})''',
'''            return GateEventVisionResult(
                flows,views,samples,healthy,elapsed(started),lk?.pointCount?:0,processing.size,historyBytes(),notes,origin,
                depths.mapValues{it.value.toList()},diagnostics.toList()
            )''', marker="depths.mapValues{it.value.toList()},diagnostics.toList()")
replace_once(path,
'''            val depth=if(owner>=0) portalDepthEvidence(state,owner,debugOwned,timeMs,exclusive) else null
            gray.copyTo(state.previous);state.lastProcessed=timeMs
            val view=GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),remaining,ownCount,state.history.size,decision.phase,state.owner,state.referenceKnown)
            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null,depth)''',
'''            val depth=if(owner>=0) portalDepthEvidence(state,owner,debugOwned,timeMs,exclusive) else null
            val diagnostic=if(GateDiagnosticBus.isCapturing()) GatePortalDiagnostic(
                gateId=state.gate.id,
                ownerTrack=state.owner,
                phase=decision.phase.name,
                schedulerDistance=decision.distance.takeIf{it.isFinite()},
                contact=decision.contact,
                referenceKnown=state.referenceKnown,
                exclusive=exclusive,
                foregroundPixels=remaining,
                peakPixels=state.clear.peak,
                clearForMs=clearFor,
                historyFrames=state.history.size,
                bodyMotionRatio=pixelChange,
                motion=maskDigest(state,motion),
                owned=maskDigest(state,debugOwned),
            ) else null
            gray.copyTo(state.previous);state.lastProcessed=timeMs
            val view=GateEventTileView(
                state.gate.id,rectBox(state.rect),emptyList(),remaining,ownCount,state.history.size,decision.phase,state.owner,
                state.referenceKnown,decision.distance.takeIf{it.isFinite()},decision.contact
            )
            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null,depth,diagnostic)''', marker="motion=maskDigest(state,motion)")
replace_once(path,
'''        return GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),state.history.size,d.phase,d.ownerTrack,state.referenceKnown)''',
'''        return GateEventTileView(
            state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),
            state.history.size,d.phase,d.ownerTrack,state.referenceKnown,d.distance.takeIf{it.isFinite()},d.contact
        )''', marker="state.history.size,d.phase,d.ownerTrack,state.referenceKnown,d.distance")
replace_once(path,
'''    private fun portalDepthEvidence(state:PortalState,track:Int,mask:Mat?,timeMs:Long,exclusive:Boolean):PortalDepthEvidence? {''',
'''    /** 诊断专用：8个门深度带 x 4个门宽带，占用率量化到0..255。 */
    private fun maskDigest(state:PortalState,mask:Mat?):GateMaskDigest? {
        if(mask==null||mask.empty())return null
        val w=mask.cols();val h=mask.rows();if(w<=0||h<=0)return null
        val bytes=ByteArray(w*h);mask.get(0,0,bytes)
        val gateBytes=ByteArray(w*h);state.mask.get(0,0,gateBytes)
        val depthScale=max(0.004,state.gate.aperture.maxOfOrNull{(-state.gate.side(it)).coerceAtLeast(0.0)}?:0.0)
        val bins=IntArray(64);val hits=IntArray(32);val capacity=IntArray(32);var count=0
        for(y in 0 until h)for(x in 0 until w){
            val offset=y*w+x
            if((gateBytes[offset].toInt() and 255)==0)continue
            val p=FlowPoint((state.rect.x+x+.5)/sourceWidth,(state.rect.y+y+.5)/sourceHeight)
            val depth=(-state.gate.side(p)/depthScale).coerceIn(0.0,1.0)
            val along=state.gate.along(p).coerceIn(0.0,1.0)
            val di=(depth*8.0).toInt().coerceIn(0,7)
            val ui=(along*4.0).toInt().coerceIn(0,3)
            val gi=di*4+ui
            capacity[gi]++
            if((bytes[offset].toInt() and 255)==0)continue
            hits[gi]++;bins[(depth*63.0).roundToInt().coerceIn(0,63)]++;count++
        }
        if(count==0)return GateMaskDigest(0,null,null,null,List(32){0})
        fun q(frac:Double):Double{
            val target=max(1,ceil(count*frac).toInt());var seen=0
            for(i in bins.indices){seen+=bins[i];if(seen>=target)return i/63.0}
            return 1.0
        }
        val grid=List(32){i->if(capacity[i]<=0)0 else ((hits[i]*255.0/capacity[i]).roundToInt()).coerceIn(0,255)}
        return GateMaskDigest(count,q(.20),q(.50),q(.80),grid)
    }

    private fun portalDepthEvidence(state:PortalState,track:Int,mask:Mat?,timeMs:Long,exclusive:Boolean):PortalDepthEvidence? {''', marker="private fun maskDigest(state:PortalState")

# GateRoomAlgorithm: publish one immutable compact frame after V4 makes its decision.
path = "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateRoomAlgorithm.kt"
replace_once(path,
'''            core=PortalV4Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,
                if(initialKnown) baseline.counts else emptyMap(),PortalV4Policy.from(config))
            vision=GateEventVision.create(gates,config)''',
'''            core=PortalV4Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,
                if(initialKnown) baseline.counts else emptyMap(),PortalV4Policy.from(config))
            if(GateDiagnosticBus.isCapturing()) GateDiagnosticBus.configure(config)
            vision=GateEventVision.create(gates,config)''', marker="GateDiagnosticBus.configure(config)")
replace_once(path,
'''        lastDecision=decision;lastSequence=seq;lastTime=time
        val notes=decision.notes+visual?.notes.orEmpty()+if(vision==null) listOf("NATIVE_UNAVAILABLE_GROUND_ONLY") else emptyList()
        GateRuntime.output(android.os.SystemClock.elapsedRealtime())
        GateOverlay.publish(config.method.label,decision,engine.gates,visual,initialKnown,input.rooms.associate { it.roomId to it.roomName },config,engine.debugSnapshot())''',
'''        lastDecision=decision;lastSequence=seq;lastTime=time
        val notes=decision.notes+visual?.notes.orEmpty()+if(vision==null) listOf("NATIVE_UNAVAILABLE_GROUND_ONLY") else emptyList()
        val debug=engine.debugSnapshot()
        if(GateDiagnosticBus.isCapturing()) {
            val detailed=visual?.diagnostics.orEmpty().associateBy{it.gateId}
            val portals=visual?.tiles.orEmpty().map{tile->
                detailed[tile.gate]?:GatePortalDiagnostic(
                    gateId=tile.gate,ownerTrack=tile.owner,phase=tile.phase.name,
                    schedulerDistance=tile.schedulerDistance,contact=tile.contact,referenceKnown=tile.referenceKnown,
                    exclusive=null,foregroundPixels=tile.foreground,peakPixels=0,clearForMs=0L,
                    historyFrames=tile.historyFrames,bodyMotionRatio=null,motion=null,owned=null
                )
            }
            val people=decision.people.map{person->
                val d=debug[person.track]
                val gate=d?.gateId?.let{id->engine.gates.firstOrNull{it.id==id}}
                val ground=person.ground
                GateDiagnosticPerson(
                    person=person.person,track=person.track,room=person.room,status=person.status,
                    accepted=person.accepted,candidates=person.candidates.sorted(),box=person.box,
                    groundX=ground?.point?.x,groundY=ground?.point?.y,groundStrong=ground?.strong,
                    groundUncertainty=ground?.uncertainty,groundSource=ground?.source,gateId=d?.gateId,
                    phase=d?.phase?.name,direction=d?.direction,depth=d?.depth,groundSide=d?.groundSide,
                    groundDistance=if(gate!=null&&ground!=null)gate.distance(ground.point)else null,
                    groundAlong=if(gate!=null&&ground!=null)gate.along(ground.point)else null,
                    evidence=d?.evidence
                )
            }
            val diagEvents=decision.events.map{event->
                val direction=when{event.from==living.roomId->"ENTER";event.to==living.roomId->"EXIT";else->"INTERNAL"}
                GateDiagnosticEvent(
                    person=event.person,track=event.track,from=event.from,to=event.to,gateId=event.gate,
                    direction=direction,timeMs=event.timeMs,inferred=event.inferred,reason=debug[event.track]?.evidence
                )
            }
            GateDiagnosticBus.publish(GateDiagnosticFrame(
                timeMs=time,frameSeq=seq,runtimeTag=runtimeTag,activeGates=visual?.activeGates?:0,
                people=people,portals=portals,events=diagEvents,counts=decision.counts,notes=notes.takeLast(12)
            ))
        }
        GateRuntime.output(android.os.SystemClock.elapsedRealtime())
        GateOverlay.publish(config.method.label,decision,engine.gates,visual,initialKnown,input.rooms.associate { it.roomId to it.roomName },config,debug)''', marker="GateDiagnosticBus.publish(GateDiagnosticFrame(")

# VideoFeeder: allow a one-shot non-looping replay and signal true playback end.
path = "app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt"
replace_once(path,
'''    // +1 帧补偿触发时回调给上层 UI，用于显示横幅提示。
    var onStepNudge: ((String) -> Unit)? = null''',
'''    // +1 帧补偿触发时回调给上层 UI，用于显示横幅提示。
    var onStepNudge: ((String) -> Unit)? = null
    /** 诊断回放使用：关闭循环，并在真实播放到末尾后生成一次完成回调。 */
    var loopPlayback: Boolean = true
    var onPlaybackCompleted: (() -> Unit)? = null
    private var playbackCompletionDelivered = false''', marker="var loopPlayback: Boolean = true")
replace_once(path,
'''            if (!isAnalyzing || player == null) {
                return
            }
            
            // 🔥 新逻辑：只要正在播放，或者处于静止模式，就继续识别''',
'''            if (!isAnalyzing || player == null) {
                return
            }
            if (!loopPlayback && !playbackCompletionDelivered && !isStillMode && !player.isPlaying()) {
                val duration = player.getDurationMs() ?: -1
                val position = player.getCurrentPositionMs() ?: -1
                val endTolerance = maxOf(180, frameStepMs * 3)
                if (duration > 0 && position >= duration - endTolerance) {
                    playbackCompletionDelivered = true
                    isAnalyzing = false
                    onPlaybackCompleted?.invoke()
                    return
                }
            }
            
            // 🔥 新逻辑：只要正在播放，或者处于静止模式，就继续识别''', marker="val endTolerance = maxOf(180, frameStepMs * 3)")
replace_once(path,
'''    private fun setupMediaPlayer(filePath: String? = null, uri: Uri? = null) {
        stop()
        lastAppliedVideoLayout = null''',
'''    private fun setupMediaPlayer(filePath: String? = null, uri: Uri? = null) {
        stop()
        playbackCompletionDelivered = false
        lastAppliedVideoLayout = null''', marker="playbackCompletionDelivered = false\n        lastAppliedVideoLayout")
replace_once(path,
'''                prepare(filePath = filePath, uri = uri, looping = true)''',
'''                prepare(filePath = filePath, uri = uri, looping = loopPlayback)''', marker="looping = loopPlayback")

# MainActivity: UI orchestration only. The recorder itself remains independent from UI.
path = "app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt"
replace_once(path,
'''import com.example.roomxxx0102.logic.roomalgorithm.gate.GateSettings
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateRuntime''',
'''import com.example.roomxxx0102.logic.roomalgorithm.gate.GateSettings
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateRuntime
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticBus''', marker="import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticBus")
replace_once(path,
'''import com.example.roomxxx0102.logic.validation.EventMarkerManager
import com.example.roomxxx0102.logic.validation.EventType''',
'''import com.example.roomxxx0102.logic.validation.EventMarkerManager
import com.example.roomxxx0102.logic.validation.EventDiagnosticRecorder
import com.example.roomxxx0102.logic.validation.EventType''', marker="import com.example.roomxxx0102.logic.validation.EventDiagnosticRecorder")
replace_once(path,
'''    private val eventMarkerManager = EventMarkerManager()
    private val deviceHitMarkerManager = DeviceHitMarkerManager()''',
'''    private val eventMarkerManager = EventMarkerManager()
    private data class DiagnosticReplaySavedSettings(
        val roomAlgorithmId: String,
        val pauseOnRoomSwitch: Boolean,
        val smartMatchPause: Boolean,
    )
    private var diagnosticReplaySavedSettings: DiagnosticReplaySavedSettings? = null
    private var diagnosticRecorder: EventDiagnosticRecorder? = null
    private var isDiagnosticReplayActive = false
    private var btnDiagnosticReplay: Button? = null
    private val deviceHitMarkerManager = DeviceHitMarkerManager()''', marker="private var diagnosticRecorder: EventDiagnosticRecorder?")
replace_once(path,
'''            val roomResult = roomAlgorithm.processFrame(RoomAlgorithmFrameInput(
                bitmap = bitmap ?: sourceMeta.analysisBitmap,
                timestampMs = frameTimestampMs,
                frameSeq = frameSeq,
                poses = results,
                rooms = buildPresenceRoomSnapshots(allRooms),
                doors = buildPresenceDoorSnapshots(allRooms),
                imageWidth = frameWidth,
                imageHeight = frameHeight,
                sceneInfo = RoomAlgorithmSceneInfo(isVideoPlayback = isVideoMode),
                poseMetadata = sourceMeta
            ))''',
'''            val roomResult = roomAlgorithm.processFrame(RoomAlgorithmFrameInput(
                bitmap = bitmap ?: sourceMeta.analysisBitmap,
                timestampMs = frameTimestampMs,
                frameSeq = frameSeq,
                poses = results,
                rooms = buildPresenceRoomSnapshots(allRooms),
                doors = buildPresenceDoorSnapshots(allRooms),
                imageWidth = frameWidth,
                imageHeight = frameHeight,
                sceneInfo = RoomAlgorithmSceneInfo(isVideoPlayback = isVideoMode),
                poseMetadata = sourceMeta
            ))
            if (isDiagnosticReplayActive) {
                GateDiagnosticBus.frameFor(frameSeq)?.let { diagnosticRecorder?.recordFrame(it) }
            }''', marker="GateDiagnosticBus.frameFor(frameSeq)")
replace_once(path,
'''        val btnDeleteCurrentEvent = findViewById<Button>(R.id.btnDeleteCurrentEvent)
        btnHandOverlay = findViewById(R.id.btnHandOverlay)''',
'''        val btnDeleteCurrentEvent = findViewById<Button>(R.id.btnDeleteCurrentEvent)
        btnDiagnosticReplay = findViewById(R.id.btnDiagnosticReplay)
        btnHandOverlay = findViewById(R.id.btnHandOverlay)''', marker="btnDiagnosticReplay = findViewById")
replace_once(path,
'''        btnDebugPanel.setOnLongClickListener {
            val report = buildDebugPanelClipboardReport(System.currentTimeMillis())
            val copied = copyTextToClipboard("debug_panel_report", report)
            if (copied) {
                Toast.makeText(this, "已复制调试面板信息", Toast.LENGTH_SHORT).show()
            }
            true
        }
        btnHandOverlay?.setOnClickListener {''',
'''        btnDebugPanel.setOnLongClickListener {
            val report = buildDebugPanelClipboardReport(System.currentTimeMillis())
            val copied = copyTextToClipboard("debug_panel_report", report)
            if (copied) {
                Toast.makeText(this, "已复制调试面板信息", Toast.LENGTH_SHORT).show()
            }
            true
        }
        btnDiagnosticReplay?.setOnClickListener { startDiagnosticReplay() }
        btnHandOverlay?.setOnClickListener {''', marker="btnDiagnosticReplay?.setOnClickListener")
replace_once(path,
'''    private fun cycleObserveMode() {
        val nextMode = when (currentObserveMode) {''',
'''    private fun cycleObserveMode() {
        if (isDiagnosticReplayActive) {
            Toast.makeText(this, "诊断回放中不能切换观察模式", Toast.LENGTH_SHORT).show()
            return
        }
        val nextMode = when (currentObserveMode) {''', marker="诊断回放中不能切换观察模式")
replace_once(path,
'''    private fun toggleRuntimeMode() {
        if (isVideoMode) {''',
'''    private fun toggleRuntimeMode() {
        if (isDiagnosticReplayActive) {
            Toast.makeText(this, "诊断回放中不能切换运行模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVideoMode) {''', marker="诊断回放中不能切换运行模式")
replace_once(path,
'''    private fun onSeekBackwardRequested() {
        val beforePos = videoFeeder?.getCurrentPositionMs()''',
'''    private fun onSeekBackwardRequested() {
        if (isDiagnosticReplayActive) return
        val beforePos = videoFeeder?.getCurrentPositionMs()''', marker="private fun onSeekBackwardRequested() {\n        if (isDiagnosticReplayActive)")
replace_once(path,
'''    private fun onSeekForwardRequested() {
        val beforePos = videoFeeder?.getCurrentPositionMs()''',
'''    private fun onSeekForwardRequested() {
        if (isDiagnosticReplayActive) return
        val beforePos = videoFeeder?.getCurrentPositionMs()''', marker="private fun onSeekForwardRequested() {\n        if (isDiagnosticReplayActive)")
replace_once(path,
'''    private fun refreshDebugPanelButton() {
        val btn = findViewById<Button>(R.id.btnDebugPanel)''',
'''    private fun refreshDebugPanelButton() {
        val btn = findViewById<Button>(R.id.btnDebugPanel)''')
replace_once(path,
'''        btn.setTextColor(Color.parseColor(if (isDebugPanelEnabled) "#FFFFFF" else "#9FB4D0"))
        refreshEventMarkerControls()
    }

    private fun togglePause(btn: Button) {''',
'''        btn.setTextColor(Color.parseColor(if (isDebugPanelEnabled) "#FFFFFF" else "#9FB4D0"))
        refreshEventMarkerControls()
        refreshDiagnosticReplayButton()
    }

    private fun refreshDiagnosticReplayButton() {
        val button = btnDiagnosticReplay ?: return
        val show = isDiagnosticReplayActive || (isDebugPanelEnabled && isVideoMode && currentObserveMode == ObserveMode.PERSON)
        button.visibility = if (show) View.VISIBLE else View.GONE
        button.isEnabled = !isDiagnosticReplayActive
        button.alpha = if (isDiagnosticReplayActive) 0.65f else 1f
        button.text = if (isDiagnosticReplayActive) "诊断录制中" else "诊断回放"
    }

    private fun startDiagnosticReplay() {
        if (isDiagnosticReplayActive) return
        if (!isVideoMode) {
            Toast.makeText(this, "诊断回放仅支持回顾视频", Toast.LENGTH_SHORT).show()
            return
        }
        val rooms = RoomRepository.getAllRooms()
        val living = rooms.firstOrNull { it.isSovereignTerritory }
        if (living == null) {
            Toast.makeText(this, "缺少客厅标定，无法开始诊断", Toast.LENGTH_SHORT).show()
            return
        }
        val roomNames = rooms.associate { it.id to it.name }
        val gateNames = buildPresenceDoorSnapshots(rooms).associate { door ->
            val target = if (door.roomAId == living.id) door.roomBId else door.roomAId
            door.doorId to (roomNames[target] ?: target)
        }
        diagnosticReplaySavedSettings = DiagnosticReplaySavedSettings(
            roomAlgorithmId = AppSettings.roomAlgorithmId,
            pauseOnRoomSwitch = AppSettings.isPauseOnRoomSwitchEnabled,
            smartMatchPause = AppSettings.isSmartMatchPauseEnabled,
        )
        diagnosticRecorder = EventDiagnosticRecorder(
            context = applicationContext,
            markedEvents = eventMarkerManager.getEvents(),
            roomNames = roomNames,
            gateNames = gateNames,
        )
        isDiagnosticReplayActive = true
        GateDiagnosticBus.beginCapture()
        AppSettings.setRoomAlgorithmId("portal_v4_diff")
        AppSettings.setPauseOnRoomSwitchEnabled(false)
        AppSettings.setSmartMatchPauseEnabled(false)
        ensureRoomAlgorithm()
        videoFeeder?.loopPlayback = false
        videoFeeder?.onPlaybackCompleted = {
            runOnUiThread { finishDiagnosticReplay() }
        }
        hardRestartPlayback()
        refreshDiagnosticReplayButton()
        val gtCount = eventMarkerManager.getEvents().size
        val text = if (gtCount > 0) "诊断回放开始：$gtCount 个人工事件" else "诊断回放开始：无人工事件，仅记录误报"
        showCenterBanner(text, CenterBannerDomain.ROOM, 5000L)
    }

    private fun finishDiagnosticReplay() {
        if (!isDiagnosticReplayActive) return
        val recorder = diagnosticRecorder
        val fileResult = runCatching { recorder?.finish() }
        isDiagnosticReplayActive = false
        diagnosticRecorder = null
        videoFeeder?.onPlaybackCompleted = null
        videoFeeder?.loopPlayback = true
        GateDiagnosticBus.endCapture()
        restoreDiagnosticReplaySettings(recreateAlgorithm = true)
        currentPlayState = PlayState.STILL
        videoFeeder?.pause()
        videoFeeder?.setStillMode(true)
        refreshPlayStateButton()
        refreshSeekButtons()
        refreshDiagnosticReplayButton()
        fileResult.onSuccess { file ->
            if (file != null) {
                copyTextToClipboard("event_diagnostic_path", file.absolutePath)
                Toast.makeText(this, "诊断完成：${file.name}", Toast.LENGTH_LONG).show()
                showCenterBanner("诊断完成：${file.absolutePath}", CenterBannerDomain.ROOM, 10000L)
            }
        }.onFailure { error ->
            Toast.makeText(this, "诊断文件生成失败：${error.message}", Toast.LENGTH_LONG).show()
            Log.e("EventDiagnostic", "finish failed", error)
        }
    }

    private fun restoreDiagnosticReplaySettings(recreateAlgorithm: Boolean) {
        val saved = diagnosticReplaySavedSettings ?: return
        diagnosticReplaySavedSettings = null
        AppSettings.setRoomAlgorithmId(saved.roomAlgorithmId)
        AppSettings.setPauseOnRoomSwitchEnabled(saved.pauseOnRoomSwitch)
        AppSettings.setSmartMatchPauseEnabled(saved.smartMatchPause)
        if (recreateAlgorithm) ensureRoomAlgorithm()
    }

    private fun togglePause(btn: Button) {''', marker="private fun startDiagnosticReplay()")
replace_once(path,
'''    private fun togglePause(btn: Button) {
        stopSeekHold()''',
'''    private fun togglePause(btn: Button) {
        if (isDiagnosticReplayActive) {
            Toast.makeText(this, "诊断回放将自动完整播放", Toast.LENGTH_SHORT).show()
            return
        }
        stopSeekHold()''', marker="诊断回放将自动完整播放")
replace_once(path,
'''    override fun onDestroy() {
        if (::roomAlgorithm.isInitialized) roomAlgorithm.reset()''',
'''    override fun onDestroy() {
        if (isDiagnosticReplayActive) {
            isDiagnosticReplayActive = false
            diagnosticRecorder = null
            GateDiagnosticBus.endCapture()
            videoFeeder?.onPlaybackCompleted = null
            videoFeeder?.loopPlayback = true
            restoreDiagnosticReplaySettings(recreateAlgorithm = false)
        }
        if (::roomAlgorithm.isInitialized) roomAlgorithm.reset()''', marker="restoreDiagnosticReplaySettings(recreateAlgorithm = false)")

# Keep the diagnostic button next to the debug panel, hidden unless debug is enabled.
path = "app/src/main/res/layout/activity_main.xml"
replace_once(path,
'''        <Button
            android:id="@+id/btnHandOverlay"''',
'''        <Button
            android:id="@+id/btnDiagnosticReplay"
            android:layout_width="86dp"
            android:layout_height="38dp"
            android:layout_marginBottom="7dp"
            android:background="@drawable/bg_side_action_button_active"
            android:minHeight="0dp"
            android:minWidth="0dp"
            android:paddingHorizontal="6dp"
            android:text="诊断回放"
            android:textAllCaps="false"
            android:textColor="#FFFFFF"
            android:textSize="11sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <Button
            android:id="@+id/btnHandOverlay"''', marker="@+id/btnDiagnosticReplay")

print("event diagnostic recorder patch applied")
