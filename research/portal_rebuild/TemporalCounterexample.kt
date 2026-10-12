package com.example.roomxxx0102.logic.roomalgorithm.gate

/** 审计反例，不是新穿门算法，也不包含人工录像答案。 */
fun main() {
    for (period in listOf(50L, 75L, 90L, 100L, 150L)) {
        val core = DiffV54Core("living_room")
        val result = mutableListOf<DiffV54Core.Crossing>()
        var previous: Double? = null
        for (t in 0L..3000L step period) {
            // 同一条无噪声连续轨迹，仅改变取样时刻。
            val pos = -.10 - .03 * t / 1000.0
            val fraction = (.80 + .00015 * t).coerceAtMost(1.0)
            result += core.step(t, listOf(DiffV54Core.Measurement(
                t, "gate", "room", 1, true, pos, fraction, .5, 2000,
                previous?.let { pos - it }, 24
            )))
            previous = pos
        }
        val d = core.diagnostic("gate")!!
        val eventText = result.map { it.direction + "@" + it.timeMs }
        println("period=$period events=$eventText phase=${d.phase} travel=${d.progress}")
    }
}
