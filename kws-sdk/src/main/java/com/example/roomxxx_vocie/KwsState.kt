package com.example.roomxxx_vocie

/**
 * KWS 运行状态
 */
enum class KwsState {
    /** 空闲/未启动 */
    IDLE,
    /** 正在监听 */
    LISTENING,
    /** 触发后短时间内忽略重复触发 */
    COOLDOWN,
    /** 初始化/权限/底层库失败等 */
    ERROR
}
