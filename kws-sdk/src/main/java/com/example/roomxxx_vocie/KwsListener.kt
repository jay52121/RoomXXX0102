package com.example.roomxxx_vocie

/**
 * KWS 回调接口
 */
fun interface KwsListener {
    /**
     * 当识别到有效口令时回调
     */
    fun onCommand(event: CommandEvent)
}
