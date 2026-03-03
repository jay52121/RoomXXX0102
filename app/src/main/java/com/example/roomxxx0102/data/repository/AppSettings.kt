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
    private const val KEY_ENABLE_ROI_CROP = "enable_roi_crop"
    private const val KEY_TEST_VIDEO_URI = "test_video_uri"
    private const val KEY_ENABLE_NEW_TRACKER = "enable_new_tracker"
    private const val KEY_ROI_LOG_MODE = "roi_log_mode"
    private const val KEY_STILL_STANDARD_FRAME = "still_standard_frame"
    private const val KEY_CLIPBOARD_DEBUG_ON_STEP = "clipboard_debug_on_step"
    private const val KEY_PRESENCE_ALGO_VERSION = "presence_algorithm_version"
    private const val KEY_PAUSE_ON_ROOM_SWITCH = "pause_on_room_switch"
    private const val KEY_PAUSE_DECISION_LOG_ON_SWITCH = "pause_decision_log_on_switch"
    private const val KEY_EVENT_MISS_PAUSE_WINDOW_MS = "event_miss_pause_window_ms"
    private const val KEY_SMART_MATCH_PAUSE_ENABLED = "smart_match_pause_enabled"

    private lateinit var prefs: SharedPreferences

    // 默认值
    var isDebugBoxShown: Boolean = false
        private set
    var isCenterPointShown: Boolean = true
        private set
    var isPoseModeEnabled: Boolean = true // 🔥 默认开启 Pose
        private set
    var isRoiRealCropEnabled: Boolean = false // 🔥 新增：ROI 真实裁剪开关
        private set
    var testVideoUri: String? = null
        private set
    var isNewTrackerPredictionEnabled: Boolean = false
        private set
    var roiLogMode: Int = ROI_LOG_MODE_TIME
        private set
    var isStillStandardFrameEnabled: Boolean = false
        private set
    var isClipboardDebugOnStepEnabled: Boolean = false
        private set
    var presenceAlgorithmVersion: String = PRESENCE_ALGO_AUTO
        private set
    var isPauseOnRoomSwitchEnabled: Boolean = false
        private set
    var isPauseDecisionLogOnSwitchEnabled: Boolean = false
        private set
    var eventMissPauseWindowMs: Int = 500
        private set
    var isSmartMatchPauseEnabled: Boolean = true
        private set

    const val ROI_LOG_MODE_TIME = 0
    const val ROI_LOG_MODE_MOVE = 1
    const val ROI_LOG_MODE_OFF = 2
    const val PRESENCE_ALGO_AUTO = "AUTO_LATEST"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 加载保存的值，如果不存在则使用默认值
        isDebugBoxShown = prefs.getBoolean(KEY_SHOW_BOX, false)
        isCenterPointShown = prefs.getBoolean(KEY_SHOW_POINT, true)
        isPoseModeEnabled = prefs.getBoolean(KEY_ENABLE_POSE, true)
        isRoiRealCropEnabled = prefs.getBoolean(KEY_ENABLE_ROI_CROP, false)
        testVideoUri = prefs.getString(KEY_TEST_VIDEO_URI, null)
        isNewTrackerPredictionEnabled = prefs.getBoolean(KEY_ENABLE_NEW_TRACKER, false)
        roiLogMode = prefs.getInt(KEY_ROI_LOG_MODE, ROI_LOG_MODE_TIME)
        isStillStandardFrameEnabled = prefs.getBoolean(KEY_STILL_STANDARD_FRAME, false)
        isClipboardDebugOnStepEnabled = prefs.getBoolean(KEY_CLIPBOARD_DEBUG_ON_STEP, false)
        presenceAlgorithmVersion = prefs.getString(KEY_PRESENCE_ALGO_VERSION, PRESENCE_ALGO_AUTO) ?: PRESENCE_ALGO_AUTO
        isPauseOnRoomSwitchEnabled = prefs.getBoolean(KEY_PAUSE_ON_ROOM_SWITCH, false)
        isPauseDecisionLogOnSwitchEnabled = prefs.getBoolean(KEY_PAUSE_DECISION_LOG_ON_SWITCH, false)
        eventMissPauseWindowMs = prefs.getInt(KEY_EVENT_MISS_PAUSE_WINDOW_MS, 500).coerceIn(100, 1000)
        isSmartMatchPauseEnabled = prefs.getBoolean(KEY_SMART_MATCH_PAUSE_ENABLED, true)
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

    fun setRoiRealCropEnabled(enable: Boolean) {
        isRoiRealCropEnabled = enable
        prefs.edit().putBoolean(KEY_ENABLE_ROI_CROP, enable).apply()
    }

    fun setTestVideoUri(uri: String?) {
        testVideoUri = uri
        if (uri.isNullOrBlank()) {
            prefs.edit().remove(KEY_TEST_VIDEO_URI).apply()
        } else {
            prefs.edit().putString(KEY_TEST_VIDEO_URI, uri).apply()
        }
    }

    fun setNewTrackerPredictionEnabled(enable: Boolean) {
        isNewTrackerPredictionEnabled = enable
        prefs.edit().putBoolean(KEY_ENABLE_NEW_TRACKER, enable).apply()
    }

    fun setRoiLogMode(mode: Int) {
        roiLogMode = mode
        prefs.edit().putInt(KEY_ROI_LOG_MODE, mode).apply()
    }

    fun setStillStandardFrameEnabled(enable: Boolean) {
        isStillStandardFrameEnabled = enable
        prefs.edit().putBoolean(KEY_STILL_STANDARD_FRAME, enable).apply()
    }

    fun setClipboardDebugOnStepEnabled(enable: Boolean) {
        isClipboardDebugOnStepEnabled = enable
        prefs.edit().putBoolean(KEY_CLIPBOARD_DEBUG_ON_STEP, enable).apply()
    }

    fun setPresenceAlgorithmVersion(version: String) {
        presenceAlgorithmVersion = if (version.isBlank()) PRESENCE_ALGO_AUTO else version
        prefs.edit().putString(KEY_PRESENCE_ALGO_VERSION, presenceAlgorithmVersion).apply()
    }

    fun setPauseOnRoomSwitchEnabled(enable: Boolean) {
        isPauseOnRoomSwitchEnabled = enable
        prefs.edit().putBoolean(KEY_PAUSE_ON_ROOM_SWITCH, enable).apply()
    }

    fun setPauseDecisionLogOnSwitchEnabled(enable: Boolean) {
        isPauseDecisionLogOnSwitchEnabled = enable
        prefs.edit().putBoolean(KEY_PAUSE_DECISION_LOG_ON_SWITCH, enable).apply()
    }

    fun setEventMissPauseWindowMs(windowMs: Int) {
        eventMissPauseWindowMs = windowMs.coerceIn(100, 1000)
        prefs.edit().putInt(KEY_EVENT_MISS_PAUSE_WINDOW_MS, eventMissPauseWindowMs).apply()
    }

    fun setSmartMatchPauseEnabled(enable: Boolean) {
        isSmartMatchPauseEnabled = enable
        prefs.edit().putBoolean(KEY_SMART_MATCH_PAUSE_ENABLED, enable).apply()
    }
}
