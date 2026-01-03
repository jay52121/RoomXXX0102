package com.example.roomxxx0102.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * **应用全局配置 (App Settings)**
 *
 * 管理调试开关和全局参数。
 */
object AppSettings {
    private const val PREF_NAME = "roomflow_settings"
    private const val KEY_SHOW_BOX = "show_box"
    private const val KEY_SHOW_POINT = "show_point"
    private const val KEY_ENABLE_POSE = "enable_pose"

    private lateinit var prefs: SharedPreferences

    // 默认值
    var isDebugBoxShown: Boolean = false
        private set
    var isCenterPointShown: Boolean = true
        private set
    var isPoseModeEnabled: Boolean = true // 🔥 默认开启 Pose
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 加载保存的值，如果不存在则使用默认值
        isDebugBoxShown = prefs.getBoolean(KEY_SHOW_BOX, false)
        isCenterPointShown = prefs.getBoolean(KEY_SHOW_POINT, true)
        isPoseModeEnabled = prefs.getBoolean(KEY_ENABLE_POSE, true)
    }

    fun setDebugBoxShown(show: Boolean) {
        isDebugBoxShown = show
        prefs.edit().putBoolean(KEY_SHOW_BOX, show).apply()
    }

    fun setCenterPointShown(show: Boolean) {
        isCenterPointShown = show
        prefs.edit().putBoolean(KEY_SHOW_POINT, show).apply()
    }

    fun setPoseModeEnabled(enable: Boolean) {
        isPoseModeEnabled = enable
        prefs.edit().putBoolean(KEY_ENABLE_POSE, enable).apply()
    }
}
