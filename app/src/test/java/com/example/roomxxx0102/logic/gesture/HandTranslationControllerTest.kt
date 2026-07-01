package com.example.roomxxx0102.logic.gesture

import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTranslationControllerTest {
    private val controller = HandTranslationController { 1.5f }

    @Test
    fun `目标手势保持满一秒后才激活`() {
        val hand = activationHand()

        assertEquals(HandTranslationController.State.HOLDING, controller.update(hand, 0L).state)
        assertEquals(HandTranslationController.State.HOLDING, controller.update(hand, 999L).state)
        val active = controller.update(hand, 1_000L)

        assertEquals(HandTranslationController.State.ACTIVE, active.state)
        assertEquals(0f, active.x, 0.001f)
        assertEquals(0f, active.y, 0.001f)
    }

    @Test
    fun `整手右移和上移分别输出正X和正Y`() {
        val hand = activationHand()
        controller.update(hand, 0L)
        val activated = controller.update(hand, 1_000L)
        val scale = requireNotNull(activated.originScale)

        var snapshot = activated
        for (step in 1..5) {
            snapshot = controller.update(
                shift(hand, dx = scale * 0.30f * step, dy = -scale * 0.30f * step),
                1_000L + step * 100L
            )
        }
        repeat(10) { index ->
            snapshot = controller.update(
                shift(hand, dx = scale * 1.50f, dy = -scale * 1.50f),
                1_600L + index * 100L
            )
        }

        assertTrue(snapshot.x > 95f)
        assertTrue(snapshot.y > 95f)
        assertTrue(snapshot.x <= 100f)
        assertTrue(snapshot.y <= 100f)
    }

    @Test
    fun `ACTIVE短暂丢手保持输出超过三百毫秒后退出`() {
        val hand = activationHand()
        controller.update(hand, 0L)
        controller.update(hand, 1_000L)

        assertEquals(HandTranslationController.State.ACTIVE, controller.update(null, 1_300L).state)
        val idle = controller.update(null, 1_301L)

        assertEquals(HandTranslationController.State.IDLE, idle.state)
        assertEquals(0f, idle.x, 0.001f)
        assertEquals(0f, idle.y, 0.001f)
    }

    @Test
    fun `双手顺序交换时按手势连续性锁定真实手并忽略坍缩伪手`() {
        val realHand = activationHand()
        val collapsedHand = List(21) { point(0.10f, 0.10f) }

        val holding = controller.updateHands(listOf(collapsedHand, realHand), 0L)
        val active = controller.updateHands(listOf(realHand, collapsedHand), 1_000L)

        assertEquals(HandTranslationController.State.HOLDING, holding.state)
        assertEquals(1, holding.selectedHandIndex)
        assertEquals(HandTranslationController.State.ACTIVE, active.state)
        assertEquals(0, active.selectedHandIndex)
    }

    private fun activationHand(): List<HandSmokeTester.HandPoint> {
        return listOf(
            point(0.50f, 0.82f),
            point(0.43f, 0.70f),
            point(0.36f, 0.61f),
            point(0.27f, 0.55f),
            point(0.17f, 0.50f),
            point(0.44f, 0.61f),
            point(0.44f, 0.46f),
            point(0.44f, 0.33f),
            point(0.44f, 0.20f),
            point(0.49f, 0.59f),
            point(0.50f, 0.48f),
            point(0.53f, 0.56f),
            point(0.50f, 0.62f),
            point(0.54f, 0.61f),
            point(0.56f, 0.50f),
            point(0.59f, 0.58f),
            point(0.55f, 0.65f),
            point(0.60f, 0.65f),
            point(0.63f, 0.56f),
            point(0.65f, 0.63f),
            point(0.60f, 0.69f)
        )
    }

    private fun shift(
        hand: List<HandSmokeTester.HandPoint>,
        dx: Float,
        dy: Float
    ): List<HandSmokeTester.HandPoint> {
        return hand.map { point -> point.copy(x = point.x + dx, y = point.y + dy) }
    }

    private fun point(x: Float, y: Float) = HandSmokeTester.HandPoint(x, y, 0f)
}
