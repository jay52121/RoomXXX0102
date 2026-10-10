package com.example.roomxxx0102.logic.webdebug

import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.logic.roomalgorithm.RoomAlgorithmFrameResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每帧真正的原始 Pose + 当前算法结果。
 * 禁止序列化 Bitmap、Mask 原始像素、视频内容或调试图像。
 */
internal object WebDebugFrameSerializer {
    fun build(
        timeMs: Long,
        frameSeq: Long,
        imageWidth: Int,
        imageHeight: Int,
        poses: List<PoseResult>,
        result: RoomAlgorithmFrameResult,
        livingRoomId: String?,
        roomNames: Map<String, String>,
        algorithmTag: String
    ): JSONObject {
        val people = JSONArray()
        poses.forEach { pose ->
            val box = pose.box
            val keypoints = JSONArray()
            pose.keypoints.forEach { k ->
                keypoints.put(JSONArray().put(k.x.toDouble()).put(k.y.toDouble()).put(k.conf.toDouble()))
            }
            people.put(JSONObject()
                .put("id", pose.id)
                .put("score", pose.score.toDouble())
                .put("confirmed", pose.isConfirmed)
                .put("shielded", pose.isShielded)
                .put("source", pose.idSource.name)
                .put("box", JSONArray().put(box.left.toDouble()).put(box.top.toDouble())
                    .put(box.right.toDouble()).put(box.bottom.toDouble()))
                .put("points", keypoints)
                .put("landing", JSONArray().put(pose.landingPoint.x.toDouble()).put(pose.landingPoint.y.toDouble()))
            )
        }
        val events = JSONArray()
        result.events.forEach { event ->
            val type = when {
                event.fromRoomId == livingRoomId -> "ENTER"
                event.toRoomId == livingRoomId -> "EXIT"
                else -> "SWITCH"
            }
            val eventMs = event.timestampMs.takeIf { it >= 0L } ?: timeMs
            val roomId = if (type == "EXIT") event.fromRoomId else event.toRoomId
            val key = "$type|$eventMs|${event.trackId ?: -1}|${event.doorId}"
            events.put(JSONObject()
                .put("key", key)
                .put("type", type)
                .put("timeMs", eventMs)
                .put("roomId", roomId)
                .put("from", event.fromRoomId)
                .put("to", event.toRoomId)
                .put("fromName", roomNames[event.fromRoomId] ?: event.fromRoomId)
                .put("toName", roomNames[event.toRoomId] ?: event.toRoomId)
                .put("doorId", event.doorId)
                .put("trackId", event.trackId ?: JSONObject.NULL)
                .put("reason", event.reason.name)
            )
        }
        val reasons = JSONArray()
        result.rejectedReasons.take(18).forEach { reasons.put(it.take(260)) }
        val hints = JSONObject()
        result.trackSwitchScores.forEach { (id, hint) ->
            hints.put(id.toString(), JSONObject()
                .put("score", hint.score)
                .put("type", hint.type.name)
                .put("from", hint.fromRoomId ?: JSONObject.NULL)
                .put("to", hint.toRoomId ?: JSONObject.NULL))
        }
        val debug = JSONObject()
        result.debugInfo.details.entries.take(25).forEach {
            debug.put(it.key, it.value.take(260))
        }
        return JSONObject()
            .put("timeMs", timeMs)
            .put("seq", frameSeq)
            .put("sourceWidth", imageWidth)
            .put("sourceHeight", imageHeight)
            .put("algorithm", algorithmTag)
            .put("coordinateSystem", "normalized")
            .put("poses", people)
            .put("counts", JSONObject(result.roomCounts))
            .put("observedCounts", JSONObject(result.observedCounts))
            .put("pendingDoorCounters", JSONObject(result.pendingDoorCounters))
            .put("switchHints", hints)
            .put("debugSummary", result.debugInfo.summary.take(800))
            .put("debugDetails", debug)
            .put("rejectedReasons", reasons)
            .put("events", events)
    }
}
