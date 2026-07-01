package com.example.roomxxx0102.logic.gesture

import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import java.util.Locale
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

class HandTranslationController(
    private val fullRangeProvider: () -> Float
) {
    enum class State {
        IDLE,
        HOLDING,
        ACTIVE
    }

    data class Snapshot(
        val state: State,
        val gestureMatched: Boolean,
        val selectedHandIndex: Int?,
        val holdElapsedMs: Long,
        val x: Float,
        val y: Float,
        val originScale: Float?
    )

    private data class Vec2(val x: Float, val y: Float)

    private data class HandGeometry(
        val palmCenter: Vec2,
        val handScale: Float
    )

    private data class IndexedGeometry(
        val index: Int,
        val geometry: HandGeometry
    )

    companion object {
        const val HOLD_DURATION_MS = 1_000L
        const val HOLD_GRACE_MS = 150L
        const val ACTIVE_GRACE_MS = 300L
        const val EMA_ALPHA = 0.30f
        const val CENTER_DEAD_ZONE = 2.50f
        const val MAX_FRAME_JUMP_SCALE = 0.45f

        private const val MIN_HAND_SCALE = 0.01f
        private const val INDEX_DISTANCE_RATIO = 1.12f
        private const val INDEX_PIP_STRAIGHT_DEG = 145f
        private const val INDEX_DIP_STRAIGHT_DEG = 135f
        private const val THUMB_DISTANCE_RATIO = 1.10f
        private const val THUMB_REACH_SCALE = 0.55f
        private const val THUMB_SEPARATION_SCALE = 0.42f
        private const val THUMB_STRAIGHT_DEG = 125f
        private const val CURLED_DISTANCE_RATIO = 1.08f
        private const val CURLED_PIP_ANGLE_DEG = 135f
        private const val CURLED_DIP_ANGLE_DEG = 125f
    }

    private var state = State.IDLE
    private var holdStartedAtMs: Long? = null
    private var lastGestureMatchedAtMs: Long? = null
    private var originCenter: Vec2? = null
    private var originScale: Float? = null
    private var selectedHandIndex: Int? = null
    private var trackedHandCenter: Vec2? = null
    private var lastObservedCenter: Vec2? = null
    private var smoothedX = 0f
    private var smoothedY = 0f

    @Synchronized
    fun update(
        hand: List<HandSmokeTester.HandPoint>?,
        timestampMs: Long
    ): Snapshot = updateHands(hand?.let(::listOf).orEmpty(), timestampMs)

    @Synchronized
    fun updateHands(
        hands: List<List<HandSmokeTester.HandPoint>>,
        timestampMs: Long
    ): Snapshot {
        val matchedHands = hands.mapIndexedNotNull { index, hand ->
            val geometry = geometryOrNull(hand) ?: return@mapIndexedNotNull null
            if (matchesActivationGesture(hand, geometry)) IndexedGeometry(index, geometry) else null
        }
        var matchedHand: IndexedGeometry? = null

        when (state) {
            State.IDLE -> {
                matchedHand = matchedHands.maxByOrNull { it.geometry.handScale }
                if (matchedHand != null) {
                    state = State.HOLDING
                    holdStartedAtMs = timestampMs
                    lastGestureMatchedAtMs = timestampMs
                    selectedHandIndex = matchedHand.index
                    trackedHandCenter = matchedHand.geometry.palmCenter
                }
            }

            State.HOLDING -> {
                matchedHand = selectTrackedHand(matchedHands)
                if (matchedHand != null) {
                    lastGestureMatchedAtMs = timestampMs
                    selectedHandIndex = matchedHand.index
                    trackedHandCenter = matchedHand.geometry.palmCenter
                    val heldMs = timestampMs - (holdStartedAtMs ?: timestampMs)
                    if (heldMs >= HOLD_DURATION_MS) activate(matchedHand, timestampMs)
                } else if (outsideGrace(timestampMs, HOLD_GRACE_MS)) {
                    resetInternal()
                }
            }

            State.ACTIVE -> {
                matchedHand = selectTrackedHand(matchedHands)
                if (matchedHand != null) {
                    lastGestureMatchedAtMs = timestampMs
                    selectedHandIndex = matchedHand.index
                    trackedHandCenter = matchedHand.geometry.palmCenter
                    updateActivePosition(matchedHand.geometry)
                } else if (outsideGrace(timestampMs, ACTIVE_GRACE_MS)) {
                    resetInternal()
                }
            }
        }

        return snapshot(matchedHand != null, timestampMs)
    }

    @Synchronized
    fun reset(): Snapshot {
        resetInternal()
        return snapshot(gestureMatched = false, timestampMs = 0L)
    }

    private fun activate(hand: IndexedGeometry, timestampMs: Long) {
        val geometry = hand.geometry
        state = State.ACTIVE
        selectedHandIndex = hand.index
        originCenter = geometry.palmCenter
        originScale = geometry.handScale
        lastObservedCenter = geometry.palmCenter
        lastGestureMatchedAtMs = timestampMs
        smoothedX = 0f
        smoothedY = 0f
    }

    private fun selectTrackedHand(candidates: List<IndexedGeometry>): IndexedGeometry? {
        val reference = trackedHandCenter ?: return candidates.maxByOrNull { it.geometry.handScale }
        return candidates.minByOrNull { distance(reference, it.geometry.palmCenter) }
    }

    private fun updateActivePosition(geometry: HandGeometry) {
        val fixedOrigin = originCenter ?: return
        val fixedScale = originScale?.takeIf { it > MIN_HAND_SCALE } ?: return
        val previousObserved = lastObservedCenter
        lastObservedCenter = geometry.palmCenter
        if (previousObserved != null &&
            distance(previousObserved, geometry.palmCenter) > MAX_FRAME_JUMP_SCALE * fixedScale
        ) {
            return
        }

        val fullRange = fullRangeProvider().coerceAtLeast(0.1f)
        val rawX = ((geometry.palmCenter.x - fixedOrigin.x) / fixedScale / fullRange * 100f)
            .coerceIn(-100f, 100f)
        val rawY = (-(geometry.palmCenter.y - fixedOrigin.y) / fixedScale / fullRange * 100f)
            .coerceIn(-100f, 100f)
        smoothedX = ema(rawX, smoothedX)
        smoothedY = ema(rawY, smoothedY)
        if (kotlin.math.abs(smoothedX) < CENTER_DEAD_ZONE) smoothedX = 0f
        if (kotlin.math.abs(smoothedY) < CENTER_DEAD_ZONE) smoothedY = 0f
    }

    private fun geometryOrNull(hand: List<HandSmokeTester.HandPoint>): HandGeometry? {
        if (hand.size < 21) return null
        val indexMcp = hand[5].toVec2()
        val middleMcp = hand[9].toVec2()
        val ringMcp = hand[13].toVec2()
        val pinkyMcp = hand[17].toVec2()
        val wrist = hand[0].toVec2()
        val center = Vec2(
            x = (indexMcp.x + middleMcp.x + ringMcp.x + pinkyMcp.x) / 4f,
            y = (indexMcp.y + middleMcp.y + ringMcp.y + pinkyMcp.y) / 4f
        )
        val palmWidth = distance(indexMcp, pinkyMcp)
        val palmLength = distance(wrist, middleMcp)
        val scale = (palmWidth + palmLength) / 2f
        if (!scale.isFinite() || scale <= MIN_HAND_SCALE) return null
        return HandGeometry(center, scale)
    }

    private fun matchesActivationGesture(
        hand: List<HandSmokeTester.HandPoint>,
        geometry: HandGeometry
    ): Boolean {
        return isIndexExtended(hand, geometry) &&
            isThumbExtended(hand, geometry) &&
            isFingerCurled(hand, geometry.palmCenter, 9, 10, 11, 12) &&
            isFingerCurled(hand, geometry.palmCenter, 13, 14, 15, 16) &&
            isFingerCurled(hand, geometry.palmCenter, 17, 18, 19, 20)
    }

    private fun isIndexExtended(
        hand: List<HandSmokeTester.HandPoint>,
        geometry: HandGeometry
    ): Boolean {
        val mcp = hand[5].toVec2()
        val pip = hand[6].toVec2()
        val dip = hand[7].toVec2()
        val tip = hand[8].toVec2()
        val extendsAway = distance(tip, geometry.palmCenter) >=
            distance(pip, geometry.palmCenter) * INDEX_DISTANCE_RATIO
        return extendsAway &&
            angleDeg(mcp, pip, dip) >= INDEX_PIP_STRAIGHT_DEG &&
            angleDeg(pip, dip, tip) >= INDEX_DIP_STRAIGHT_DEG
    }

    private fun isThumbExtended(
        hand: List<HandSmokeTester.HandPoint>,
        geometry: HandGeometry
    ): Boolean {
        val mcp = hand[2].toVec2()
        val ip = hand[3].toVec2()
        val tip = hand[4].toVec2()
        val indexMcp = hand[5].toVec2()
        val reachesAway = distance(tip, geometry.palmCenter) >= max(
            distance(ip, geometry.palmCenter) * THUMB_DISTANCE_RATIO,
            geometry.handScale * THUMB_REACH_SCALE
        )
        val separated = distance(tip, indexMcp) >= geometry.handScale * THUMB_SEPARATION_SCALE
        return reachesAway && separated && angleDeg(mcp, ip, tip) >= THUMB_STRAIGHT_DEG
    }

    private fun isFingerCurled(
        hand: List<HandSmokeTester.HandPoint>,
        palmCenter: Vec2,
        mcpIndex: Int,
        pipIndex: Int,
        dipIndex: Int,
        tipIndex: Int
    ): Boolean {
        val mcp = hand[mcpIndex].toVec2()
        val pip = hand[pipIndex].toVec2()
        val dip = hand[dipIndex].toVec2()
        val tip = hand[tipIndex].toVec2()
        val tipNearPalm = distance(tip, palmCenter) <=
            distance(pip, palmCenter) * CURLED_DISTANCE_RATIO
        return tipNearPalm ||
            angleDeg(mcp, pip, dip) <= CURLED_PIP_ANGLE_DEG ||
            angleDeg(pip, dip, tip) <= CURLED_DIP_ANGLE_DEG
    }

    private fun outsideGrace(timestampMs: Long, graceMs: Long): Boolean {
        val lastMatched = lastGestureMatchedAtMs ?: return true
        return timestampMs - lastMatched > graceMs
    }

    private fun snapshot(gestureMatched: Boolean, timestampMs: Long): Snapshot {
        val holdElapsed = if (state == State.HOLDING) {
            (timestampMs - (holdStartedAtMs ?: timestampMs)).coerceIn(0L, HOLD_DURATION_MS)
        } else {
            0L
        }
        return Snapshot(
            state = state,
            gestureMatched = gestureMatched,
            selectedHandIndex = selectedHandIndex,
            holdElapsedMs = holdElapsed,
            x = smoothedX.coerceIn(-100f, 100f),
            y = smoothedY.coerceIn(-100f, 100f),
            originScale = originScale
        )
    }

    private fun resetInternal() {
        state = State.IDLE
        holdStartedAtMs = null
        lastGestureMatchedAtMs = null
        originCenter = null
        originScale = null
        selectedHandIndex = null
        trackedHandCenter = null
        lastObservedCenter = null
        smoothedX = 0f
        smoothedY = 0f
    }

    private fun HandSmokeTester.HandPoint.toVec2(): Vec2 = Vec2(x, y)

    private fun distance(a: Vec2, b: Vec2): Float = hypot(a.x - b.x, a.y - b.y)

    private fun ema(current: Float, previous: Float): Float {
        return EMA_ALPHA * current + (1f - EMA_ALPHA) * previous
    }

    private fun angleDeg(a: Vec2, b: Vec2, c: Vec2): Float {
        val bax = a.x - b.x
        val bay = a.y - b.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        val denominator = hypot(bax, bay) * hypot(bcx, bcy)
        if (denominator <= 1e-6f) return 0f
        val cosine = ((bax * bcx + bay * bcy) / denominator).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    fun formatValue(value: Float): String = String.format(Locale.US, "%+06.1f", value)
}
