package com.example.roomxxx0102

import android.graphics.PointF

/**
 * **房间类型枚举 (Room Type)**
 *
 * 定义房间在空间逻辑中的角色。
 */
enum class RoomType {
    /**
     * **主权领土 (Sovereign Territory)**
     *
     * 通常指“客厅”。
     * 这是一个基于凸包的**实体区域**。
     * 判定逻辑：只要脚底坐标落在该区域内，即视为“在该区域活动”。
     */
    SOVEREIGN_TERRITORY,

    /**
     * **传送门陷阱 (Portal Trap)**
     *
     * 指卧室、厨房、阳台等附属房间。
     * 这是一个依附在客厅边缘的**虚拟出入口区域**。
     * 判定逻辑：当 ID 从客厅消失，且最后位置落在该区域内，视为“进入该房间”。
     */
    PORTAL_TRAP
}

/**
 * **区域配置数据 (Room Region Data)**
 *
 * 存储一个特定区域的空间定义信息。
 * 用于序列化保存到 JSON 文件中。
 *
 * @property id 唯一标识符 (UUID).
 * @property name 显示名称 (如 "Living Room", "Master Bedroom").
 * @property type 区域类型 (主权领土 或 传送门).
 * @property boundaryPoints 构成该区域边界的多边形顶点列表。
 *                         - 对于 [RoomType.SOVEREIGN_TERRITORY]: 这是计算出的凸包顶点。
 *                         - 对于 [RoomType.PORTAL_TRAP]: 这是逃逸点云生成的包围盒矩形顶点。
 */
data class RoomRegion(
    val id: String,
    val name: String,
    val type: RoomType,
    val boundaryPoints: List<PointF> = emptyList()
)
