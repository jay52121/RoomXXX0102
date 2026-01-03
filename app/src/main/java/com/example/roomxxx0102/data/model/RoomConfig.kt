package com.example.roomxxx0102.data.model

import android.graphics.PointF
import java.util.UUID

/**
 * **房间类型枚举 (Room Type)**
 *
 * 定义房间在空间逻辑中的角色。
 */
enum class RoomType {
    SOVEREIGN_TERRITORY, // 主权领土 (客厅)
    PORTAL_TRAP          // 传送门陷阱 (其他房间)
}

/**
 * **区域几何数据 (Room Region Data)**
 *
 * 存储一个特定区域的空间定义信息。
 * 主要由录制器生成，用于核心算法判定。
 *
 * @property id 唯一标识符 (UUID).
 * @property name 显示名称 (如 "Living Room", "Master Bedroom").
 * @property type 区域类型 (主权领土 或 传送门).
 * @property boundaryPoints 构成该区域边界的多边形顶点列表。
 */
data class RoomRegion(
    val id: String,
    val name: String,
    val type: RoomType,
    val boundaryPoints: List<PointF> = emptyList()
)

/**
 * **房间配置模型 (Room Config Model)**
 *
 * 定义了单个房间的基本属性，主要用于 UI 列表展示和简单的状态管理。
 *
 * @property id 唯一标识符 (UUID).
 * @property name 房间名称 (如 "客厅", "主卧").
 * @property isSovereignTerritory 是否为主权领土 (即客厅/主监控区)。
 * @property isRecorded 是否已完成录制 (用于控制 UI 按钮颜色和状态)。
 * @property boundaryPoints 区域边界点 (归一化坐标 0..1)。
 * @property anchorPoint 次房间在地图上的锚点 (归一化坐标 0..1)，仅用于次房间。
 */
data class RoomConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val isSovereignTerritory: Boolean = false,
    var isRecorded: Boolean = false,
    var boundaryPoints: List<PointF> = emptyList(),
    var anchorPoint: PointF? = null // 🔥 新增：次房间锚点
)
