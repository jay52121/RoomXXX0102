package com.example.roomxxx0102.logic.roomalgorithm

/** 房间判定算法的稳定入口。 */
interface RoomAlgorithmEngine {
    val algorithmId: String
    val runtimeTag: String
    val configurationKey: String

    fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult

    fun reset()
}
