package com.example.roomxxx_vocie

/**
 * “持续监听”的配置
 * @property sampleRate 采样率，默认 16000
 * @property frameSizeInSamples 帧大小，默认 1600 (100ms @16k)
 * @property cooldownMs 触发后的冷却时间
 * @property silenceResetMs 长静音后清空上下文的时间，避免跨很久拼接触发
 * @property triggerThreshold 触发阈值，需与 keywords 文件里的配置参考对齐
 * @property keywordsScore 关键词分数，作为 KWS 评分的基础参数
 * @property maxActivePaths 最大活跃路径
 * @property numTrailingBlanks 尾随空格数
 * @property dropDispatchDelayMs 丢帧保险丝：调度延迟触发阈值
 * @property dropBacklogFrames 丢帧保险丝：积压帧数触发阈值（100ms 一帧）
 * @property dropQueueDepth 丢帧保险丝：队列深度触发阈值
 * @property dropMinIntervalMs 丢帧保险丝：两次触发的最小间隔
 * @property modelAssetDir 模型资源目录
 * @property keywordsAssetPath 关键词文件路径
 */
data class KwsConfig(
    val sampleRate: Int = 16000,
    val frameSizeInSamples: Int = 1600,
    val cooldownMs: Long = 100,
    val silenceResetMs: Long = 2800,
    val triggerThreshold: Float = 0.20f,
    val keywordsScore: Float = 2.5f,
    val maxActivePaths: Int = 11,
    val numTrailingBlanks: Int = 1,
    val dropDispatchDelayMs: Long = 800,
    val dropBacklogFrames: Int = 8,
    val dropQueueDepth: Int = 8,
    val dropMinIntervalMs: Long = 1000,
    val modelAssetDir: String = "kws_model/zh_en_3M_2025_12_20_chunk8_int8",
    val keywordsAssetPath: String = "kws_keywords/keywords.txt"
)
