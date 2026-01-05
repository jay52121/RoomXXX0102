package com.example.roomxxx0102.utils

import android.graphics.Color

object RoomColorPalette {
    val colors: List<Int> = listOf(
        Color.parseColor("#FF3B30"), // Red
        Color.parseColor("#FF9500"), // Orange
        Color.parseColor("#FFCC00"), // Yellow
        Color.parseColor("#34C759"), // Green
        Color.parseColor("#00C7BE"), // Teal
        Color.parseColor("#007AFF"), // Blue
        Color.parseColor("#5856D6"), // Indigo
        Color.parseColor("#AF52DE"), // Purple
        Color.parseColor("#FF2D55"), // Pink
        Color.parseColor("#5AC8FA")  // Light Blue
    )

    fun nextAvailable(used: Set<Int>): Int? {
        return colors.firstOrNull { it !in used }
    }
}
