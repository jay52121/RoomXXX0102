package com.example.roomxxx_vocie

/**
 * KWS 控制器接口
 */
interface KwsController {
    /**
     * 设置监听器
     */
    fun setListener(listener: KwsListener?)

    /**
     * 启动 KWS
     * start() 可重复调用：重复调用应视为重启
     */
    fun start(config: KwsConfig = KwsConfig())

    /**
     * 停止 KWS
     * stop() 必须可重复调用且幂等
     */
    fun stop()

    /**
     * 获取当前状态
     */
    fun getState(): KwsState
}
