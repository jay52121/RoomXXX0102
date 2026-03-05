package com.example.roomxxx0102.logic.analyzer

import android.graphics.RectF
import android.util.Log
import com.example.roomxxx0102.data.repository.AppSettings
import java.util.ArrayDeque
import java.util.Locale

object RoiLogAggregator {
    private const val TAG = "RF_ROI"
    private const val RECENT_FRAME_LIMIT = 24
    private const val PRESENCE_HISTORY_LIMIT = 8

    private var lastLogTs = 0L
    private var lastLogRoiBox: RectF? = null

    private var frameId = 0
    private var rawCount = 0
    private var nmsCount = 0
    private var trackedCount = 0
    private var roiEnabled = false

    private var roiPx: RectF? = null
    private var box: RectF? = null

    private var roiBox: RectF? = null
    private var roiTracking = false
    private var roiSparse = false
    private var roiStable = false

    private var dxPx = 0f
    private var dyPx = 0f
    private var maxOffsetPx = 0f
    private var deadZone = 0f
    private var inDeadZone = false
    private var topMove = 0f
    private var roiCy = 0f
    private var roiSize = 0f
    private var clampL = false
    private var clampR = false
    private var clampT = false
    private var clampB = false

    private var lastTL: String? = null
    private var lastTR: String? = null
    private var currTL: String? = null
    private var currTR: String? = null

    private var poseUpdateCount = 0
    private var poseResultsCount = 0
    private var poseTimeMs = 0L
    private var poseHasBmp = false
    private var frameDigest: String? = null
    private var frameDigestPosMs: Int? = null
    private var frameDigestTemporalAdvanced: Boolean = false

    private var sizeChange: String? = null

    private var presenceAlgoVersion: String = "-"
    private var presenceLastEvent: String = "-"
    private var presenceDecision: String = "-"
    private var presenceCounts: String = "-"
    private var presencePosMs: Int? = null
    private var livingPersistentCount: Int = 0
    private var livingCurrentCount: Int = 0
    private var lastPresenceHistoryKey: String? = null
    private var lastPresenceHistoryFrameId: Int = -1

    private data class PresenceHistoryEntry(
        val frame: Int,
        val ms: Int,
        val event: String,
        val decision: String,
        val counts: String
    )

    private val recentFrameSnapshots: ArrayDeque<String> = ArrayDeque()
    private val recentPresenceSnapshots: ArrayDeque<String> = ArrayDeque()
    private var lastSnapshotFrameId = -1

    @Synchronized
    fun updateHeartbeat(frame: Int, raw: Int, nms: Int, tracked: Int, roi: Boolean) {
        frameId = frame
        rawCount = raw
        nmsCount = nms
        trackedCount = tracked
        roiEnabled = roi
        appendFrameSnapshotIfNeeded()
        maybeLog()
    }

    @Synchronized
    fun updateRoiCoord(roiPx: RectF?, box: RectF?) {
        this.roiPx = roiPx
        this.box = box
        maybeLog()
    }

    @Synchronized
    fun updateRoiVisual(box: RectF?, tracking: Boolean, sparse: Boolean, stable: Boolean) {
        roiBox = box
        roiTracking = tracking
        roiSparse = sparse
        roiStable = stable
        maybeLog()
    }

    @Synchronized
    fun updateRoiTick(
        dxPx: Float,
        dyPx: Float,
        maxOffsetPx: Float,
        deadZone: Float,
        inDeadZone: Boolean,
        topMove: Float,
        roiCy: Float,
        roiSize: Float,
        clampL: Boolean,
        clampR: Boolean,
        clampT: Boolean,
        clampB: Boolean
    ) {
        this.dxPx = dxPx
        this.dyPx = dyPx
        this.maxOffsetPx = maxOffsetPx
        this.deadZone = deadZone
        this.inDeadZone = inDeadZone
        this.topMove = topMove
        this.roiCy = roiCy
        this.roiSize = roiSize
        this.clampL = clampL
        this.clampR = clampR
        this.clampT = clampT
        this.clampB = clampB
        maybeLog()
    }

    @Synchronized
    fun updateTopDbg(
        lastTLx: Float,
        lastTLy: Float,
        lastTRx: Float,
        lastTRy: Float,
        currTLx: Float,
        currTLy: Float,
        currTRx: Float,
        currTRy: Float
    ) {
        lastTL = "(${fmt(lastTLx)},${fmt(lastTLy)})"
        lastTR = "(${fmt(lastTRx)},${fmt(lastTRy)})"
        currTL = "(${fmt(currTLx)},${fmt(currTLy)})"
        currTR = "(${fmt(currTRx)},${fmt(currTRy)})"
        maybeLog()
    }

    @Synchronized
    fun updatePoseUi(updateCount: Int, resultsCount: Int, timeMs: Long, hasBmp: Boolean) {
        poseUpdateCount = updateCount
        poseResultsCount = resultsCount
        poseTimeMs = timeMs
        poseHasBmp = hasBmp
        maybeLog()
    }

    @Synchronized
    fun updateFrameDigest(digest: String, positionMs: Int, temporalAdvanced: Boolean) {
        frameDigest = digest
        frameDigestPosMs = positionMs
        frameDigestTemporalAdvanced = temporalAdvanced
        maybeLog()
    }

    @Synchronized
    fun updateRoiSizeChange(prevSize: Float, newSize: Float, ratio: Float, maxSide: Float) {
        sizeChange = "size=${fmt(prevSize)}->${fmt(newSize)} ratio=${fmt(ratio)} maxSide=${fmt(maxSide)}"
        maybeLog()
    }

    @Synchronized
    fun updatePresenceDebug(
        algoVersion: String,
        eventText: String,
        decisionText: String,
        countsText: String,
        posMs: Int?
    ) {
        presenceAlgoVersion = algoVersion
        presenceLastEvent = eventText
        presenceDecision = decisionText
        presenceCounts = countsText
        presencePosMs = posMs
        appendPresenceSnapshotIfNeeded()
        maybeLog()
    }

    @Synchronized
    fun updateLivingRoomCounts(persistentCount: Int, currentCount: Int) {
        livingPersistentCount = persistentCount.coerceAtLeast(0)
        livingCurrentCount = currentCount.coerceAtLeast(0)
        maybeLog()
    }

    /**
     * 提供给 UI 叠加调试面板使用的实时快照。
     * 仅做读取，不改变任何日志节流逻辑。
     */
    @Synchronized
    fun snapshotForPanel(): List<String> = snapshotForPanel(includePresenceHistory = true)

    /**
     * 带参数版本：可控制是否拼接 presence 历史。
     */
    @Synchronized
    fun snapshotForPanel(includePresenceHistory: Boolean = true): List<String> {
        val lines = mutableListOf<String>()
        lines.add("frame=$frameId raw=$rawCount nms=$nmsCount tracked=$trackedCount roi=$roiEnabled")
        lines.add("box=${fmt(box)}")
        lines.add("roiBox=${fmt(roiBox)} track=$roiTracking sparse=$roiSparse stable=$roiStable")
        lines.add("pose update=$poseUpdateCount results=$poseResultsCount ms=$poseTimeMs bmp=$poseHasBmp")
        lines.add("presence algo=$presenceAlgoVersion")
        lines.add("presence event=$presenceLastEvent")
        lines.add("presence decision=$presenceDecision")
        lines.add("presence counts=$presenceCounts")
        lines.add("living counts(存在/当前)=$livingPersistentCount/$livingCurrentCount")
        lines.add("presence schema=h=[f,t,fr,md,er,lc,sg] m=[dd,dps,gpc,des,dpe,trc,src,sops,pac,srss,pts,das,scs,ss,e,eth,scsTh,srssTh,sopsTh,dnd,nd,dpsR,dpsF,dpsE,dpsTr,pacE,ins,itr,pbs,crs,eph,epf,epfl,ecp,ess,sRef,vRef,rRef,dad,dld,dalr,dadS,dadL,bsc,ssc,psc,bmp,pg,cg,ngp,dsg,bdt,bfac] x=[pacMin,phrMin,gpm,edg,mwu,evnR,evcR,xvnR,evdeR,evdeP,evdaM,xvdh,xvpm,xvpr,cand,pc,stk,stkr,fbf,bap,lsw,ssba]")
        if (includePresenceHistory) {
            val history = snapshotPresenceHistory(5)
            if (history.isNotEmpty()) {
                lines.add("presence recent(${history.size}):")
                history.forEach { lines.add(it) }
            }
        }
        return lines
    }

    /**
     * 提供最近若干帧摘要，供“一键复制 unlock 调试信息”使用。
     */
    @Synchronized
    fun snapshotRecentFrames(maxCount: Int = 8): List<String> {
        if (maxCount <= 0 || recentFrameSnapshots.isEmpty()) return emptyList()
        val list = recentFrameSnapshots.toList()
        val start = (list.size - maxCount).coerceAtLeast(0)
        return list.subList(start, list.size)
    }

    @Synchronized
    fun snapshotPresenceHistory(maxCount: Int = 8): List<String> {
        if (maxCount <= 0 || recentPresenceSnapshots.isEmpty()) return emptyList()
        val list = compressPresenceSnapshots(recentPresenceSnapshots.toList())
        val start = (list.size - maxCount).coerceAtLeast(0)
        return list.subList(start, list.size)
    }

    @Synchronized
    private fun maybeLog() {
        val now = System.currentTimeMillis()
        val mode = AppSettings.roiLogMode
        val shouldLog = when (mode) {
            AppSettings.ROI_LOG_MODE_MOVE -> {
                val changed = roiBox != lastLogRoiBox
                if (changed) {
                    lastLogRoiBox = roiBox
                }
                changed
            }
            AppSettings.ROI_LOG_MODE_OFF -> false
            else -> {
                if (now - lastLogTs < 1000L) {
                    return
                }
                lastLogTs = now
                true
            }
        }
        if (!shouldLog) return

        Log.d(
            TAG,
            "frame=$frameId raw=$rawCount nms=$nmsCount tracked=$trackedCount roi=$roiEnabled " +
                "roiPx=${fmt(roiPx)} box=${fmt(box)} roiBox=${fmt(roiBox)} " +
                "track=$roiTracking sparse=$roiSparse stable=$roiStable " +
                "dx=${fmt(dxPx)} dy=${fmt(dyPx)} max=${fmt(maxOffsetPx)} dead=${fmt(deadZone)} " +
                "inDead=$inDeadZone topMove=${fmt(topMove)} roiCy=${fmt(roiCy)} roiSize=${fmt(roiSize)} " +
                "clampL=$clampL clampR=$clampR clampT=$clampT clampB=$clampB " +
                "lastTL=$lastTL lastTR=$lastTR currTL=$currTL currTR=$currTR " +
                "digest=${frameDigest ?: "null"} posMs=${frameDigestPosMs ?: -1} ta=$frameDigestTemporalAdvanced " +
                "poseUpdate=$poseUpdateCount poseResults=$poseResultsCount poseMs=$poseTimeMs bmp=$poseHasBmp " +
                "sizeChange=${sizeChange ?: "-"}"
        )
    }

    @Synchronized
    private fun appendFrameSnapshotIfNeeded() {
        if (frameId == lastSnapshotFrameId) return
        lastSnapshotFrameId = frameId
        val summary = "f=$frameId raw=$rawCount nms=$nmsCount t=$trackedCount roi=$roiEnabled " +
            "roiBox=${fmt(roiBox)} box=${fmt(box)} dx=${fmt(dxPx)} dy=${fmt(dyPx)} " +
            "max=${fmt(maxOffsetPx)} topMove=${fmt(topMove)} stable=$roiStable " +
            "digest=${frameDigest ?: "null"} posMs=${frameDigestPosMs ?: -1} ta=$frameDigestTemporalAdvanced"
        recentFrameSnapshots.addLast(summary)
        while (recentFrameSnapshots.size > RECENT_FRAME_LIMIT) {
            recentFrameSnapshots.removeFirst()
        }
    }

    @Synchronized
    private fun appendPresenceSnapshotIfNeeded() {
        val shouldRecord = presenceLastEvent != "-" ||
            presenceDecision.contains("ENTER_", ignoreCase = true) ||
            presenceDecision.contains("SCORE_REJECT", ignoreCase = true) ||
            presenceDecision.contains("AMBIGUOUS_DOOR", ignoreCase = true) ||
            presenceDecision.contains("NO_VISIBLE_DOOR", ignoreCase = true) ||
            presenceDecision.contains("VISIBLE_SYNC", ignoreCase = true)
        if (!shouldRecord) return

        val key = "f=$frameId|ms=${presencePosMs ?: -1}|e=$presenceLastEvent|d=$presenceDecision|c=$presenceCounts"
        if (key == lastPresenceHistoryKey) return
        lastPresenceHistoryKey = key

        val entry = "[$frameId,${presencePosMs ?: -1}]|$presenceLastEvent|$presenceDecision|$presenceCounts"
        // 同一帧若多次更新 presence 文案，仅保留最后一次，避免复制日志时出现重复帧。
        if (lastPresenceHistoryFrameId == frameId && recentPresenceSnapshots.isNotEmpty()) {
            recentPresenceSnapshots.removeLast()
        }
        lastPresenceHistoryFrameId = frameId
        recentPresenceSnapshots.addLast(entry)
        while (recentPresenceSnapshots.size > PRESENCE_HISTORY_LIMIT) {
            recentPresenceSnapshots.removeFirst()
        }
    }

    private fun compressPresenceSnapshots(raw: List<String>): List<String> {
        if (raw.isEmpty()) return emptyList()
        val parsed = raw.mapNotNull { parsePresenceHistoryEntry(it) }
        if (parsed.isEmpty()) return raw
        val merged = mutableListOf<String>()

        var runStart = parsed.first()
        var runEnd = parsed.first()
        var runCount = 1
        var lastPayload = payloadKey(parsed.first())

        for (i in 1 until parsed.size) {
            val entry = parsed[i]
            val payload = payloadKey(entry)
            if (payload == lastPayload) {
                runEnd = entry
                runCount += 1
            } else {
                merged.add(formatCompressedEntry(runStart, runEnd, runCount, lastPayload))
                runStart = entry
                runEnd = entry
                runCount = 1
                lastPayload = payload
            }
        }
        merged.add(formatCompressedEntry(runStart, runEnd, runCount, lastPayload))
        return merged
    }

    private fun parsePresenceHistoryEntry(line: String): PresenceHistoryEntry? {
        val regex = Regex("^\\[(\\d+),(-?\\d+)]\\|(.*?)\\|(.*?)\\|(.*)$")
        val match = regex.find(line) ?: return null
        return PresenceHistoryEntry(
            frame = match.groupValues[1].toIntOrNull() ?: return null,
            ms = match.groupValues[2].toIntOrNull() ?: -1,
            event = match.groupValues[3],
            decision = match.groupValues[4],
            counts = match.groupValues[5]
        )
    }

    private fun payloadKey(entry: PresenceHistoryEntry): String {
        return "e=${entry.event}|d=${entry.decision}|c=${entry.counts}"
    }

    private fun formatCompressedEntry(
        start: PresenceHistoryEntry,
        end: PresenceHistoryEntry,
        count: Int,
        payload: String
    ): String {
        val hash = payload.hashCode().toUInt().toString(16).take(6)
        val label = if (count <= 1) {
            "${start.frame},${start.ms},$hash"
        } else {
            "${start.frame}-${end.frame}x$count,${start.ms}->${end.ms},$hash"
        }
        return "[$label]|${start.event}|${start.decision}|${start.counts}"
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun fmt(rect: RectF?): String {
        if (rect == null) return "null"
        return "RectF(${fmt(rect.left)},${fmt(rect.top)},${fmt(rect.right)},${fmt(rect.bottom)})"
    }
}
