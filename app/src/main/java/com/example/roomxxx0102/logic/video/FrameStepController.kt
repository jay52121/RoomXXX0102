package com.example.roomxxx0102.logic.video

import com.example.roomxxx0102.utils.AppLog

class FrameStepController(
    private val getDurationMs: () -> Int?,
    private val issueSeekToMs: (Int) -> Unit,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class PendingStep(
        val direction: Int,
        val anchorTimeMs: Int,
        val anchorSignature: FrameSignature,
        val targetMs: Int,
        val settleWindowMs: Long,
        val debug: VideoFeeder.StepSeekDebug,
        var seekCompleteAtMs: Long? = null
    )

    companion object {
        private const val TAG = "RoomStepFullDiag"
        private const val FIXED_STEP_MS = 35
        private const val SETTLE_WINDOW_MS = 50L
    }

    private var anchorTimeMs: Int? = null
    private var anchorSignature: FrameSignature? = null
    private var latestObservedTimeMs: Int? = null
    private var latestObservedSignature: FrameSignature? = null
    private var pendingStep: PendingStep? = null

    fun resetAnchor(reason: String) {
        anchorTimeMs = null
        anchorSignature = null
        pendingStep = null
        AppLog.i(TAG, "resetAnchor reason=$reason")
    }

    fun onSeekComplete(positionMs: Int, completedAtMs: Long) {
        pendingStep?.let { pending ->
            pending.seekCompleteAtMs = completedAtMs
            pending.debug.afterCallMs = positionMs
            AppLog.i(
                TAG,
                "seekComplete target=${pending.targetMs} landed=$positionMs settle=${pending.settleWindowMs}"
            )
        }
    }

    fun hasPendingStep(): Boolean = pendingStep != null

    fun onFrameObserved(positionMs: Int, signature: FrameSignature): Boolean {
        latestObservedTimeMs = positionMs
        latestObservedSignature = signature
        if (anchorTimeMs == null || anchorSignature == null) {
            anchorTimeMs = positionMs
            anchorSignature = signature
        }

        val pending = pendingStep ?: return false
        val seekDoneAt = pending.seekCompleteAtMs ?: return false
        val nowMs = nowMsProvider()
        if (nowMs - seekDoneAt < pending.settleWindowMs) {
            return false
        }

        pending.debug.afterCallMs = positionMs
        anchorTimeMs = positionMs
        anchorSignature = signature
        pending.debug.resolved = true
        pending.debug.success = true
        pending.debug.confirmedPosMs = positionMs
        AppLog.i(
            TAG,
            "stepSuccess direction=${pending.direction} anchor=${pending.anchorTimeMs} " +
                "target=${pending.targetMs} landed=$positionMs"
        )
        pendingStep = null
        return false
    }

    fun stepForward(baseStepMs: Int): VideoFeeder.StepSeekDebug? = startStep(direction = 1, baseStepMs = baseStepMs)

    fun stepBackward(baseStepMs: Int): VideoFeeder.StepSeekDebug? = startStep(direction = -1, baseStepMs = baseStepMs)

    private fun startStep(direction: Int, baseStepMs: Int): VideoFeeder.StepSeekDebug? {
        pendingStep?.let { pending ->
            AppLog.i(
                TAG,
                "stepRejectedBusy direction=$direction pendingDirection=${pending.direction} " +
                    "anchor=${pending.anchorTimeMs} target=${pending.targetMs}"
            )
            return VideoFeeder.StepSeekDebug(
                beforeMs = pending.anchorTimeMs,
                targetMs = pending.targetMs,
                afterCallMs = latestObservedTimeMs ?: pending.anchorTimeMs,
                deltaMs = pending.targetMs - pending.anchorTimeMs,
                issuedAtMs = nowMsProvider(),
                baseDigest = pending.anchorSignature.summary,
                resolved = true,
                success = false,
                failureReason = "BUSY"
            )
        }

        val anchorPos = anchorTimeMs ?: latestObservedTimeMs
        val anchorSig = anchorSignature ?: latestObservedSignature
        val durationMs = getDurationMs()
        if (anchorPos == null || anchorSig == null || durationMs == null) {
            return VideoFeeder.StepSeekDebug(
                beforeMs = anchorPos ?: -1,
                targetMs = anchorPos ?: -1,
                afterCallMs = anchorPos ?: -1,
                deltaMs = 0,
                issuedAtMs = nowMsProvider(),
                baseDigest = anchorSig?.summary,
                resolved = true,
                success = false,
                failureReason = "ANCHOR_UNAVAILABLE"
            )
        }

        anchorTimeMs = anchorPos
        anchorSignature = anchorSig

        val target = (anchorPos + direction * FIXED_STEP_MS).coerceIn(0, durationMs)
        if (target == anchorPos) {
            return VideoFeeder.StepSeekDebug(
                beforeMs = anchorPos,
                targetMs = anchorPos,
                afterCallMs = anchorPos,
                deltaMs = 0,
                issuedAtMs = nowMsProvider(),
                baseDigest = anchorSig.summary,
                resolved = true,
                success = false,
                failureReason = "NO_CANDIDATES"
            )
        }

        val debug = VideoFeeder.StepSeekDebug(
            beforeMs = anchorPos,
            targetMs = target,
            afterCallMs = anchorPos,
            deltaMs = target - anchorPos,
            issuedAtMs = nowMsProvider(),
            baseDigest = anchorSig.summary
        ).apply {
            candidateCount = 1
        }

        val pending = PendingStep(
            direction = direction,
            anchorTimeMs = anchorPos,
            anchorSignature = anchorSig,
            targetMs = target,
            settleWindowMs = SETTLE_WINDOW_MS,
            debug = debug
        )
        pendingStep = pending
        AppLog.i(
            TAG,
            "stepStart direction=$direction anchor=$anchorPos baseStepMs=$baseStepMs " +
                "fixedStepMs=$FIXED_STEP_MS target=$target"
        )
        issueSeekToMs(target)
        return debug
    }
}
