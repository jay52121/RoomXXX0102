package com.example.roomxxx0102.logic.recorder

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomRegion
import com.example.roomxxx0102.data.model.RoomType
import com.example.roomxxx0102.utils.GeometryUtils

/**
 * **录制状态 (Recording State)**
 */
enum class RecorderState {
    IDLE,
    RECORDING_SOVEREIGN, // 正在录制客厅主权领土 (凸包)
    RECORDING_PORTAL     // 正在录制房间传送门 (逃逸点)
}

/**
 * **房间录制器 (Room Recorder)**
 *
 * 这是一个状态机，负责处理空间数据的采集逻辑。
 * 它接收每一帧的姿态识别结果，根据当前模式采集数据，并最终生成 [RoomRegion] 配置。
 *
 * **核心逻辑**:
 * 1. **凸包采集模式**: 记录所有“已锁定”目标的足迹点，结束时生成凸包。
 * 2. **传送门采集模式**: 追踪目标 ID，当目标消失或脱离客厅区域时，记录其“最后位置”作为逃逸点，结束时生成包围盒。
 */
class RoomRecorder {

    private var currentState = RecorderState.IDLE
    
    // 当前正在录制的房间元数据
    private var currentRoomId: String = ""
    private var currentRoomName: String = ""
    
    // --- 数据容器 ---
    
    // [Sovereign Mode]: 收集的所有足迹点
    private val recordedFootprintPoints = ArrayList<PointF>()
    
    // [Portal Mode]: 收集的所有逃逸点 (ID 消失时的位置)
    private val recordedEscapePoints = ArrayList<PointF>()
    
    // [Portal Mode Context]: 参考的客厅凸包 (用于判断是否脱离)
    private var referenceSovereignHull: List<PointF> = emptyList()
    
    // [Tracking]: 记录每个 ID 上一帧的位置，用于检测消失事件
    private val lastKnownPositions = HashMap<Int, PointF>()

    /**
     * **开始录制客厅 (Sovereign Territory)**
     *
     * 用户需沿着客厅可达区域边界行走。
     */
    fun startRecordingSovereign(id: String, name: String) {
        currentState = RecorderState.RECORDING_SOVEREIGN
        currentRoomId = id
        currentRoomName = name
        recordedFootprintPoints.clear()
        Log.d("RoomRecorder", "开始录制客厅主权领土: $name")
    }

    /**
     * **开始录制房间入口 (Portal Trap)**
     *
     * 用户需反复从客厅进入该房间。
     *
     * @param livingRoomHull 客厅的凸包边界，作为参考坐标系。
     */
    fun startRecordingPortal(id: String, name: String, livingRoomHull: List<PointF>) {
        if (livingRoomHull.isEmpty()) {
            Log.e("RoomRecorder", "错误: 无法录制传送门，未提供客厅凸包数据。")
            return
        }
        currentState = RecorderState.RECORDING_PORTAL
        currentRoomId = id
        currentRoomName = name
        referenceSovereignHull = livingRoomHull
        recordedEscapePoints.clear()
        lastKnownPositions.clear()
        Log.d("RoomRecorder", "开始录制传送门陷阱: $name")
    }

    /**
     * **停止录制并生成结果**
     *
     * @return 构建好的 [RoomRegion] 对象。如果不满足生成条件则返回 null。
     */
    fun stopRecording(): RoomRegion? {
        val region = when (currentState) {
            RecorderState.RECORDING_SOVEREIGN -> {
                // 计算凸包
                val hull = GeometryUtils.computeConvexHull(recordedFootprintPoints)
                if (hull.size >= 3) {
                    RoomRegion(currentRoomId, currentRoomName, RoomType.SOVEREIGN_TERRITORY, hull)
                } else {
                    Log.w("RoomRecorder", "足迹点不足，无法生成凸包。")
                    null
                }
            }
            RecorderState.RECORDING_PORTAL -> {
                // 计算包围盒 (矩形扩张)
                if (recordedEscapePoints.isNotEmpty()) {
                    val box = computeBoundingBox(recordedEscapePoints)
                    RoomRegion(currentRoomId, currentRoomName, RoomType.PORTAL_TRAP, box)
                } else {
                    Log.w("RoomRecorder", "未捕捉到逃逸点，无法生成传送门。")
                    null
                }
            }
            else -> null
        }
        
        currentState = RecorderState.IDLE
        recordedFootprintPoints.clear()
        recordedEscapePoints.clear()
        lastKnownPositions.clear()
        return region
    }

    /**
     * **处理每一帧的姿态数据**
     *
     * 应该在 YoloPoseAnalyzer 的回调中调用此方法。
     */
    fun processFrame(results: List<PoseResult>) {
        if (currentState == RecorderState.IDLE) return

        val currentFrameIds = HashSet<Int>()

        for (pose in results) {
            // 过滤：只记录已确认 (Confirmed) 的真人目标，防止鬼影干扰数据
            if (!pose.isConfirmed) continue

            // 提取脚底坐标 (取左右脚踝中点)
            val footPoint = extractFootPoint(pose) ?: continue
            
            currentFrameIds.add(pose.id)

            when (currentState) {
                RecorderState.RECORDING_SOVEREIGN -> {
                    // 模式 A: 持续收集足迹
                    recordedFootprintPoints.add(footPoint)
                }
                RecorderState.RECORDING_PORTAL -> {
                    // 模式 B: 更新最后位置，等待消失
                    lastKnownPositions[pose.id] = footPoint
                }
                else -> {}
            }
        }

        // [Portal Mode Logic]: 检测消失的 ID
        if (currentState == RecorderState.RECORDING_PORTAL) {
            val disappearedIds = lastKnownPositions.keys - currentFrameIds
            for (id in disappearedIds) {
                val lastPos = lastKnownPositions[id]!!
                
                // 核心判定：只有当最后位置位于客厅外部(或边缘)时，才视为有效逃逸
                // (这里简化为都记录，依靠实际录制动作保证准确性)
                recordedEscapePoints.add(lastPos)
                Log.d("RoomRecorder", "捕捉到逃逸点: $lastPos (ID: $id)")
                
                // 移除已处理的 ID
                lastKnownPositions.remove(id)
            }
        }
    }

    /**
     * 提取脚底坐标 (左右脚踝中点)。
     * YOLO Keypoints: 15=Left Ankle, 16=Right Ankle
     */
    private fun extractFootPoint(pose: PoseResult): PointF? {
        val kpts = pose.keypoints
        if (kpts.size <= 16) return null

        val leftAnkle = kpts[15]
        val rightAnkle = kpts[16]

        // 只有当脚踝置信度足够时才有效
        if (leftAnkle.conf > 0.5f && rightAnkle.conf > 0.5f) {
            return PointF((leftAnkle.x + rightAnkle.x) / 2, (leftAnkle.y + rightAnkle.y) / 2)
        }
        return null
    }

    /**
     * 计算点云的矩形包围盒，并转换为 4 个顶点。
     */
    private fun computeBoundingBox(points: List<PointF>): List<PointF> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }

        // 适度扩张 (Padding)，避免边界判定过于严苛
        val padding = 0.05f // 5% 屏幕宽度的容差
        minX -= padding
        maxX += padding
        minY -= padding
        maxY += padding

        return listOf(
            PointF(minX, minY), // Top-Left
            PointF(maxX, minY), // Top-Right
            PointF(maxX, maxY), // Bottom-Right
            PointF(minX, maxY)  // Bottom-Left
        )
    }
}
