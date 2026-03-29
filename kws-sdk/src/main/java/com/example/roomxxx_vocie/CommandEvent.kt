package com.example.roomxxx_vocie

/**
 * 唤醒事件数据类
 * @property command 识别到的口令类型
 * @property timestampMs 墙上时间毫秒 (System.currentTimeMillis())
 * @property keywordRaw 原始识别文本，例如 “打开这个” / “关闭这个”
 * @property score 置信度分数，允许为空
 */
data class CommandEvent(
    val command: Command,
    val timestampMs: Long,
    val keywordRaw: String,
    val score: Float? = null
)
