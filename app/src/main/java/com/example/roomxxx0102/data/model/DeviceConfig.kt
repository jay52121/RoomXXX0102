package com.example.roomxxx0102.data.model

import android.graphics.PointF
import java.util.UUID

data class DeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var hotspot: PointF,
    var polygon: MutableList<PointF>
)
