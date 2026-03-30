package com.example.roomxxx0102.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.content.ContextCompat
import com.example.roomxxx0102.logic.audio.PlaybackVideoAudioSource
import com.example.roomxxx_vocie.KwsConfig
import com.example.roomxxx_vocie.KwsControllerImpl
import com.example.roomxxx_vocie.audio.AudioInputMode
import com.example.roomxxx_vocie.audio.AudioRecordSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.kwsSettingsDataStore by preferencesDataStore(name = "kws_settings")
private val KEY_THRESHOLD = floatPreferencesKey("trigger_threshold")
private val KEY_KEYWORDS_SCORE = floatPreferencesKey("keywords_score")
private val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
private val KEY_SILENCE_RESET_MS = longPreferencesKey("silence_reset_ms")
private val KEY_MAX_ACTIVE_PATHS = longPreferencesKey("max_active_paths")
private val KEY_NUM_TRAILING_BLANKS = longPreferencesKey("num_trailing_blanks")
private val KEY_DROP_DISPATCH_DELAY_MS = longPreferencesKey("drop_dispatch_delay_ms")
private val KEY_DROP_BACKLOG_FRAMES = longPreferencesKey("drop_backlog_frames")
private val KEY_DROP_QUEUE_DEPTH = longPreferencesKey("drop_queue_depth")
private val KEY_DROP_MIN_INTERVAL_MS = longPreferencesKey("drop_min_interval_ms")
private const val MAX_LOG_ITEMS = 20

@Composable
fun KwsPanelScreen(
    controller: KwsControllerImpl,
    modifier: Modifier = Modifier,
    currentPlayerTimeMsProvider: () -> Long = { 0L },
    currentAudioInputModeProvider: () -> AudioInputMode = { AudioInputMode.PLAYBACK },
    onSelectAudioInputMode: (AudioInputMode) -> AudioInputMode = { it },
    currentPlaybackAudioSourceSpecProvider: () -> PlaybackVideoAudioSource.SourceSpec? = { null },
    playbackAudioActiveProvider: () -> Boolean = { false },
    logClearSignal: Int = 0,
    latestDeviceResultUpdate: AudioCommandLogUpdate? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentAudioInputModeProviderState = rememberUpdatedState(currentAudioInputModeProvider)
    val onSelectAudioInputModeState = rememberUpdatedState(onSelectAudioInputMode)
    val currentPlaybackAudioSourceSpecProviderState = rememberUpdatedState(currentPlaybackAudioSourceSpecProvider)
    val playbackAudioActiveProviderState = rememberUpdatedState(playbackAudioActiveProvider)
    val isListening = remember { mutableStateOf(true) }
    val currentConfig = remember { mutableStateOf(KwsConfig()) }
    val thresholdValue = remember { mutableStateOf(currentConfig.value.triggerThreshold) }
    val keywordsScoreValue = remember { mutableStateOf(currentConfig.value.keywordsScore) }
    val cooldownValue = remember { mutableStateOf(currentConfig.value.cooldownMs.toFloat()) }
    val silenceResetValue = remember { mutableStateOf(currentConfig.value.silenceResetMs.toFloat()) }
    val maxActivePathsValue = remember { mutableStateOf(currentConfig.value.maxActivePaths.toFloat()) }
    val numTrailingBlanksValue = remember { mutableStateOf(currentConfig.value.numTrailingBlanks.toFloat()) }
    val dropDispatchDelayValue = remember { mutableStateOf(currentConfig.value.dropDispatchDelayMs.toFloat()) }
    val dropBacklogFramesValue = remember { mutableStateOf(currentConfig.value.dropBacklogFrames.toFloat()) }
    val dropQueueDepthValue = remember { mutableStateOf(currentConfig.value.dropQueueDepth.toFloat()) }
    val dropMinIntervalValue = remember { mutableStateOf(currentConfig.value.dropMinIntervalMs.toFloat()) }
    val pendingConfig = remember { mutableStateOf<KwsConfig?>(null) }
    val listenStatus = remember { mutableStateOf("已停止") }
    val latestStatus = remember { mutableStateOf("") }
    val statStatus = remember { mutableStateOf("") }
    val meterSource = remember { AudioRecordSource(context.applicationContext) }
    val playbackMeterSource = remember {
        PlaybackVideoAudioSource(
            context = context.applicationContext,
            sourceProvider = { currentPlaybackAudioSourceSpecProviderState.value() },
            playbackPositionProvider = currentPlayerTimeMsProvider,
            playbackActiveProvider = { playbackAudioActiveProviderState.value() }
        )
    }
    val meterRunning = remember { mutableStateOf(false) }
    val meterEnabled = remember { mutableStateOf(true) }
    val meterLatest = remember { AtomicReference<MeterSnapshot?>(null) }
    val clipHoldUntilMs = remember { AtomicLong(0L) }
    val meterLastFrameMs = remember { AtomicLong(0L) }
    val lastPeakOverMs = remember { AtomicLong(0L) }
    val meterProgress = remember { mutableStateOf(0f) }
    val peakDb = remember { mutableStateOf(-120.0) }
    val rmsDb = remember { mutableStateOf(-120.0) }
    val clipOn = remember { mutableStateOf(false) }
    val logEntries = remember { mutableStateOf(listOf<OutputLogEntry>()) }
    val pendingLogUpdates = remember { mutableStateOf<Map<Long, AudioCommandLogUpdate>>(emptyMap()) }
    val audioInputMode = remember { mutableStateOf(currentAudioInputModeProvider()) }
    val pendingAudioInputMode = remember { mutableStateOf<AudioInputMode?>(null) }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun stopMeter() {
        if (meterRunning.value) {
            meterSource.stop()
            playbackMeterSource.stop()
            meterRunning.value = false
        }
        meterLastFrameMs.set(0L)
        lastPeakOverMs.set(0L)
    }

    fun startMeterIfAllowed() {
        if (meterRunning.value || !meterEnabled.value || isListening.value) return
        val meterCallback: (com.example.roomxxx_vocie.audio.AudioFrame) -> Unit = { frame ->
            val snapshot = computeMeterSnapshot(frame.pcm, clipHoldUntilMs)
            meterLatest.set(snapshot)
            meterLastFrameMs.set(frame.timestampMs)
            if (snapshot.peakDb > -30.0) lastPeakOverMs.set(frame.timestampMs)
        }
        when (audioInputMode.value) {
            AudioInputMode.MICROPHONE -> {
                val hasPermission = hasRecordPermission()
                if (!hasPermission) return
                meterSource.start(currentConfig.value, meterCallback)
            }
            AudioInputMode.PLAYBACK -> {
                playbackMeterSource.start(currentConfig.value, meterCallback)
            }
        }
        meterRunning.value = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pendingConfigValue = pendingConfig.value
        if (granted) {
            pendingAudioInputMode.value?.let { targetMode ->
                audioInputMode.value = onSelectAudioInputModeState.value(targetMode)
                latestStatus.value = "音源: ${audioInputModeLabel(audioInputMode.value)}"
            }
            pendingAudioInputMode.value = null
            if (pendingConfigValue != null) {
                pendingConfig.value = null
                isListening.value = true
                controller.stop()
                controller.start(pendingConfigValue)
                listenStatus.value = "监听中"
            } else if (!isListening.value && meterEnabled.value) {
                stopMeter()
                startMeterIfAllowed()
            }
        } else {
            pendingAudioInputMode.value = null
            if (pendingConfigValue != null) {
                pendingConfig.value = null
                isListening.value = false
                listenStatus.value = "已停止"
            }
        }
    }

    LaunchedEffect(Unit) {
        audioInputMode.value = currentAudioInputModeProviderState.value()
        val prefs = context.kwsSettingsDataStore.data.first()
        val loadedConfig = currentConfig.value.copy(
            triggerThreshold = prefs[KEY_THRESHOLD] ?: currentConfig.value.triggerThreshold,
            keywordsScore = prefs[KEY_KEYWORDS_SCORE] ?: currentConfig.value.keywordsScore,
            cooldownMs = prefs[KEY_COOLDOWN_MS] ?: currentConfig.value.cooldownMs,
            silenceResetMs = prefs[KEY_SILENCE_RESET_MS] ?: currentConfig.value.silenceResetMs,
            maxActivePaths = (prefs[KEY_MAX_ACTIVE_PATHS] ?: currentConfig.value.maxActivePaths.toLong()).toInt(),
            numTrailingBlanks = (prefs[KEY_NUM_TRAILING_BLANKS] ?: currentConfig.value.numTrailingBlanks.toLong()).toInt(),
            dropDispatchDelayMs = prefs[KEY_DROP_DISPATCH_DELAY_MS] ?: currentConfig.value.dropDispatchDelayMs,
            dropBacklogFrames = (prefs[KEY_DROP_BACKLOG_FRAMES] ?: currentConfig.value.dropBacklogFrames.toLong()).toInt(),
            dropQueueDepth = (prefs[KEY_DROP_QUEUE_DEPTH] ?: currentConfig.value.dropQueueDepth.toLong()).toInt(),
            dropMinIntervalMs = prefs[KEY_DROP_MIN_INTERVAL_MS] ?: currentConfig.value.dropMinIntervalMs
        )
        currentConfig.value = loadedConfig
        thresholdValue.value = loadedConfig.triggerThreshold
        keywordsScoreValue.value = loadedConfig.keywordsScore
        cooldownValue.value = loadedConfig.cooldownMs.toFloat()
        silenceResetValue.value = loadedConfig.silenceResetMs.toFloat()
        maxActivePathsValue.value = loadedConfig.maxActivePaths.toFloat()
        numTrailingBlanksValue.value = loadedConfig.numTrailingBlanks.toFloat()
        dropDispatchDelayValue.value = loadedConfig.dropDispatchDelayMs.toFloat()
        dropBacklogFramesValue.value = loadedConfig.dropBacklogFrames.toFloat()
        dropQueueDepthValue.value = loadedConfig.dropQueueDepth.toFloat()
        dropMinIntervalValue.value = loadedConfig.dropMinIntervalMs.toFloat()

        val requireMic = audioInputMode.value == AudioInputMode.MICROPHONE
        val hasPermission = hasRecordPermission()
        if (!requireMic || hasPermission) {
            controller.stop()
            controller.start(loadedConfig)
            isListening.value = true
            listenStatus.value = "监听中"
        } else {
            isListening.value = false
            pendingConfig.value = loadedConfig
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            meterLatest.get()?.let {
                meterProgress.value = it.progress
                peakDb.value = it.peakDb
                rmsDb.value = it.rmsDb
            }
            clipOn.value = System.currentTimeMillis() < clipHoldUntilMs.get()
            if (meterEnabled.value && meterRunning.value) {
                val lastFrameMs = meterLastFrameMs.get()
                if (lastFrameMs > 0 && System.currentTimeMillis() - lastFrameMs > 500) {
                    stopMeter()
                    startMeterIfAllowed()
                }
            }
            delay(100)
        }
    }

    DisposableEffect(Unit) {
        controller.setAudioFrameListener { frame ->
            if (meterEnabled.value) {
                val snapshot = computeMeterSnapshot(frame.pcm, clipHoldUntilMs)
                meterLatest.set(snapshot)
                meterLastFrameMs.set(frame.timestampMs)
                if (snapshot.peakDb > -30.0) lastPeakOverMs.set(frame.timestampMs)
            }
        }
        controller.setListener { event ->
            scope.launch {
                val playerTimeMs = currentPlayerTimeMsProvider()
                val lastPeak = lastPeakOverMs.get()
                val latencyMs = if (lastPeak > 0L) event.timestampMs - lastPeak else -1L
                val summaryLatency = if (latencyMs >= 0L) "${latencyMs}ms" else "未知"
                val detail = controller.getLastDelayDetail().ifBlank { "readGapMs=? procCostMs=? backlogMs=?" }
                appendLog(
                    logEntries = logEntries,
                    token = event.timestampMs,
                    playerTimeMs = playerTimeMs,
                    summaryText = "${event.command}  ${summaryLatency}",
                    detailText = "总延迟: ${if (latencyMs >= 0L) "${latencyMs}ms" else "未知"}\n$detail"
                )
                pendingLogUpdates.value[event.timestampMs]?.let { update ->
                    if (updateCommandLog(logEntries, update)) {
                        pendingLogUpdates.value = pendingLogUpdates.value - event.timestampMs
                    }
                }
            }
        }
        controller.setStatusListener { message ->
            scope.launch {
                if (message.startsWith("KWS_STAT")) {
                    statStatus.value = message
                } else {
                    latestStatus.value = when (message) {
                        "静音重置" -> "已重置"
                        "冷却开始" -> "冷却中"
                        "冷却结束" -> "监听中"
                        else -> message
                    }
                }
            }
        }
        onDispose {
            controller.setListener(null)
            controller.setStatusListener(null)
            controller.setAudioFrameListener(null)
            controller.stop()
            meterSource.stop()
            playbackMeterSource.stop()
        }
    }

    val settingsScrollState = rememberScrollState()
    val outputScrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101214))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .background(Color(0xFF171A1D))
                .padding(16.dp)
                .verticalScroll(settingsScrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("电平表", color = Color(0xFFE8EAED))
                Switch(
                    checked = meterEnabled.value,
                    onCheckedChange = {
                        meterEnabled.value = it
                        if (!it) {
                            stopMeter()
                            meterLatest.set(null)
                            meterProgress.value = 0f
                            peakDb.value = -120.0
                            rmsDb.value = -120.0
                            clipOn.value = false
                        } else {
                            startMeterIfAllowed()
                        }
                    }
                )
            }
            LinearProgressIndicator(
                progress = { meterProgress.value },
                color = levelColor(peakDb.value, clipOn.value),
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Peak dBFS: ${formatDb(peakDb.value)}", color = Color(0xFFE8EAED))
                    Text("RMS dBFS: ${formatDb(rmsDb.value)}", color = Color(0xFFE8EAED))
                }
                Text("CLIP", color = if (clipOn.value) Color(0xFFD00000) else Color(0xFF888888))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cfg = currentConfig.value.copy(
                        triggerThreshold = thresholdValue.value,
                        keywordsScore = keywordsScoreValue.value,
                        cooldownMs = cooldownValue.value.toLong(),
                        silenceResetMs = silenceResetValue.value.toLong(),
                        maxActivePaths = maxActivePathsValue.value.toInt(),
                        numTrailingBlanks = numTrailingBlanksValue.value.toInt(),
                        dropDispatchDelayMs = dropDispatchDelayValue.value.toLong(),
                        dropBacklogFrames = dropBacklogFramesValue.value.toInt(),
                        dropQueueDepth = dropQueueDepthValue.value.toInt(),
                        dropMinIntervalMs = dropMinIntervalValue.value.toLong()
                    )
                    currentConfig.value = cfg
                    scope.launch {
                        context.kwsSettingsDataStore.edit { prefs ->
                            prefs[KEY_THRESHOLD] = cfg.triggerThreshold
                            prefs[KEY_KEYWORDS_SCORE] = cfg.keywordsScore
                            prefs[KEY_COOLDOWN_MS] = cfg.cooldownMs
                            prefs[KEY_SILENCE_RESET_MS] = cfg.silenceResetMs
                            prefs[KEY_MAX_ACTIVE_PATHS] = cfg.maxActivePaths.toLong()
                            prefs[KEY_NUM_TRAILING_BLANKS] = cfg.numTrailingBlanks.toLong()
                            prefs[KEY_DROP_DISPATCH_DELAY_MS] = cfg.dropDispatchDelayMs
                            prefs[KEY_DROP_BACKLOG_FRAMES] = cfg.dropBacklogFrames.toLong()
                            prefs[KEY_DROP_QUEUE_DEPTH] = cfg.dropQueueDepth.toLong()
                            prefs[KEY_DROP_MIN_INTERVAL_MS] = cfg.dropMinIntervalMs
                        }
                    }
                    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val requireMic = audioInputMode.value == AudioInputMode.MICROPHONE
                    if (!requireMic || hasPermission) {
                        controller.stop()
                        controller.start(cfg)
                        isListening.value = true
                        listenStatus.value = "监听中"
                        stopMeter()
                    } else {
                        pendingConfig.value = cfg
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, modifier = Modifier.weight(1f)) { Text("应用") }
                Button(onClick = {
                    logEntries.value = emptyList()
                    expandedLogId = null
                }, modifier = Modifier.weight(1f)) { Text("清空") }
                Button(onClick = { controller.triggerDebugBlock(800) }, modifier = Modifier.weight(1f)) { Text("堵塞") }
                Button(onClick = {
                    val targetMode = if (audioInputMode.value == AudioInputMode.PLAYBACK) {
                        AudioInputMode.MICROPHONE
                    } else {
                        AudioInputMode.PLAYBACK
                    }
                    if (targetMode == AudioInputMode.MICROPHONE && !hasRecordPermission()) {
                        pendingAudioInputMode.value = targetMode
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        audioInputMode.value = onSelectAudioInputModeState.value(targetMode)
                        latestStatus.value = "音源: ${audioInputModeLabel(audioInputMode.value)}"
                        if (!isListening.value && meterEnabled.value) {
                            stopMeter()
                            startMeterIfAllowed()
                        }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text("音源:${audioInputModeLabel(audioInputMode.value)}")
                }
            }

            SliderLine("触发阈值", thresholdValue, currentConfig.value.triggerThreshold, 0.1f..1.0f, 8) { roundToStep(it, 0.1f, 0.1f, 1f) }
            SliderLine("keywordsScore", keywordsScoreValue, currentConfig.value.keywordsScore, 1f..10f, 18) { roundToStep(it, 0.5f, 1f, 10f) }
            SliderLine("冷却时长(ms)", cooldownValue, currentConfig.value.cooldownMs.toFloat(), 0f..5000f, 50) { roundToStep(it, 100f, 0f, 5000f) }
            SliderLine("静音重置(ms)", silenceResetValue, currentConfig.value.silenceResetMs.toFloat(), 0f..5000f, 50) { roundToStep(it, 100f, 0f, 5000f) }
            SliderLine("maxActivePaths", maxActivePathsValue, currentConfig.value.maxActivePaths.toFloat(), 2f..20f, 18) { roundToStep(it, 1f, 2f, 20f) }
            SliderLine("numTrailingBlanks", numTrailingBlanksValue, currentConfig.value.numTrailingBlanks.toFloat(), 0f..2f, 1) { roundToStep(it, 1f, 0f, 2f) }
            SliderLine("dropDispatchDelayMs", dropDispatchDelayValue, currentConfig.value.dropDispatchDelayMs.toFloat(), 400f..1600f, 24) { roundToStep(it, 50f, 400f, 1600f) }
            SliderLine("dropBacklogFrames", dropBacklogFramesValue, currentConfig.value.dropBacklogFrames.toFloat(), 4f..16f, 11) { roundToStep(it, 1f, 4f, 16f) }
            SliderLine("dropQueueDepth", dropQueueDepthValue, currentConfig.value.dropQueueDepth.toFloat(), 4f..16f, 11) { roundToStep(it, 1f, 4f, 16f) }
            SliderLine("dropMinIntervalMs", dropMinIntervalValue, currentConfig.value.dropMinIntervalMs.toFloat(), 500f..3000f, 25) { roundToStep(it, 100f, 500f, 3000f) }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = isListening.value,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val requireMic = audioInputMode.value == AudioInputMode.MICROPHONE
                            val hasPermission = hasRecordPermission()
                            if (!requireMic || hasPermission) {
                                isListening.value = true
                                controller.stop()
                                controller.start(currentConfig.value)
                                listenStatus.value = "监听中"
                                stopMeter()
                            } else {
                                pendingConfig.value = currentConfig.value
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            isListening.value = false
                            controller.stop()
                            listenStatus.value = "已停止"
                            latestStatus.value = ""
                            startMeterIfAllowed()
                        }
                    }
                )
                Text(buildStatusText(listenStatus.value, latestStatus.value, statStatus.value), color = Color(0xFFE8EAED))
            }

            Text("监听状态: ${listenStatus.value}", color = Color(0xFFD7DCE0))
            Text("最近状态: ${if (latestStatus.value.isBlank()) "无" else latestStatus.value}", color = Color(0xFFD7DCE0))
            Text("统计状态: ${if (statStatus.value.isBlank()) "无" else statStatus.value}", color = Color(0xFFD7DCE0))
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0xFF2A2D31))
        )

        Column(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .background(Color(0xFF15181B))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(outputScrollState),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logEntries.value.isEmpty()) {
                    Text("暂无调试输出", color = Color(0xFF8B949E))
                } else {
                    logEntries.value.forEach { entry ->
                        val expanded = expandedLogId == entry.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (expanded) Color(0xFF23272C) else Color(0x0015181B))
                                .clickable {
                                    expandedLogId = if (expanded) null else entry.id
                                }
                                .padding(vertical = 3.dp, horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${entry.playerTimeText} ${entry.summaryText}",
                                color = Color(0xFFE8EAED)
                            )
                            if (expanded && entry.detailText.isNotBlank()) {
                                Text(entry.detailText, color = Color(0xFFB8C0C8))
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(logEntries.value.size) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    LaunchedEffect(latestDeviceResultUpdate?.updateId) {
        latestDeviceResultUpdate?.let { update ->
            if (!updateCommandLog(logEntries, update)) {
                pendingLogUpdates.value = pendingLogUpdates.value + (update.token to update)
            }
        }
    }

    LaunchedEffect(logClearSignal) {
        logEntries.value = emptyList()
        pendingLogUpdates.value = emptyMap()
        expandedLogId = null
    }
}

@Composable
private fun SliderLine(
    label: String,
    state: MutableState<Float>,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    normalize: (Float) -> Float
) {
    Text("$label: ${formatSliderValue(state.value)} （当前: ${formatSliderValue(current)}）", color = Color(0xFFE8EAED))
    Slider(value = state.value, onValueChange = { state.value = normalize(it) }, valueRange = range, steps = steps)
}

private fun formatSliderValue(value: Float): String {
    return if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
}

data class AudioCommandLogUpdate(
    val token: Long,
    val summarySuffix: String,
    val detailSuffix: String = "",
    val updateId: Long = System.nanoTime()
)

private data class OutputLogEntry(
    val id: Long,
    val token: Long?,
    val playerTimeText: String,
    val summaryText: String,
    val detailText: String
)

private fun appendLog(
    logEntries: MutableState<List<OutputLogEntry>>,
    token: Long? = null,
    playerTimeMs: Long,
    summaryText: String,
    detailText: String
) {
    val next = ArrayList<OutputLogEntry>(logEntries.value.size + 1)
    next.addAll(logEntries.value)
    next.add(
        OutputLogEntry(
            id = System.nanoTime(),
            token = token,
            playerTimeText = formatPlayerTime(playerTimeMs),
            summaryText = summaryText,
            detailText = detailText
        )
    )
    if (next.size > MAX_LOG_ITEMS) next.subList(0, next.size - MAX_LOG_ITEMS).clear()
    logEntries.value = next
}

private fun updateCommandLog(
    logEntries: MutableState<List<OutputLogEntry>>,
    update: AudioCommandLogUpdate
): Boolean {
    var changed = false
    val next = logEntries.value.map { entry ->
        if (entry.token != update.token) return@map entry
        changed = true
        val mergedDetail = if (update.detailSuffix.isBlank()) {
            entry.detailText
        } else if (entry.detailText.isBlank()) {
            update.detailSuffix
        } else {
            "${entry.detailText}\n${update.detailSuffix}"
        }
        entry.copy(
            summaryText = "${entry.summaryText}  ${update.summarySuffix}",
            detailText = mergedDetail
        )
    }
    if (changed) {
        logEntries.value = next
    }
    return changed
}

private fun formatPlayerTime(playerTimeMs: Long): String {
    val safeMs = if (playerTimeMs < 0L) 0L else playerTimeMs
    val totalSeconds = safeMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

private fun audioInputModeLabel(mode: AudioInputMode): String {
    return when (mode) {
        AudioInputMode.PLAYBACK -> "播放器"
        AudioInputMode.MICROPHONE -> "麦克风"
    }
}

private fun roundToStep(value: Float, step: Float, min: Float, max: Float): Float {
    if (step <= 0f) return value.coerceIn(min, max)
    val rounded = kotlin.math.round(value / step) * step
    return rounded.coerceIn(min, max)
}

private fun buildStatusText(listenStatus: String, latestStatus: String, statStatus: String): String {
    val base = when (latestStatus) {
        "冷却中" -> "冷却中"
        "已重置" -> "已重置"
        "监听中" -> "监听中"
        "" -> listenStatus
        else -> latestStatus
    }
    return if (statStatus.isNotBlank()) "$base / $statStatus" else base
}

private data class MeterSnapshot(val progress: Float, val peakDb: Double, val rmsDb: Double)

private fun computeMeterSnapshot(pcm: ShortArray, clipHoldUntilMs: AtomicLong): MeterSnapshot {
    var peak = 0
    var sumSquares = 0.0
    var clipDetected = false
    for (sample in pcm) {
        val absVal = abs(sample.toInt())
        if (absVal > peak) peak = absVal
        val normalized = sample / 32768.0
        sumSquares += normalized * normalized
        if (absVal >= 32760) clipDetected = true
    }
    if (clipDetected) clipHoldUntilMs.set(System.currentTimeMillis() + 500)
    val rms = if (pcm.isNotEmpty()) kotlin.math.sqrt(sumSquares / pcm.size) else 0.0
    val peakDb = dbfs(peak.toDouble())
    val rmsDb = dbfs(rms * 32767.0)
    val progress = ((peakDb + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    return MeterSnapshot(progress, peakDb, rmsDb)
}

private fun dbfs(value: Double): Double = if (value <= 0.0) -120.0 else 20.0 * log10(value / 32767.0)
private fun formatDb(value: Double): String = if (value <= -119.0) "-inf" else String.format("%.1f", value)

private fun levelColor(peakDb: Double, clip: Boolean): Color = when {
    clip || peakDb > -6.0 -> Color(0xFFD00000)
    peakDb > -12.0 -> Color(0xFFFFB300)
    else -> Color(0xFF1F7A8C)
}

private fun writeWavFile(file: File, pcm: ShortArray, sampleCount: Int, sampleRate: Int) {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = sampleCount * blockAlign
    val riffSize = 36 + dataSize
    FileOutputStream(file).use { output ->
        output.write("RIFF".toByteArray())
        output.write(intToLittleEndian(riffSize))
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        output.write(intToLittleEndian(16))
        output.write(shortToLittleEndian(1))
        output.write(shortToLittleEndian(channels.toShort()))
        output.write(intToLittleEndian(sampleRate))
        output.write(intToLittleEndian(byteRate))
        output.write(shortToLittleEndian(blockAlign.toShort()))
        output.write(shortToLittleEndian(bitsPerSample.toShort()))
        output.write("data".toByteArray())
        output.write(intToLittleEndian(dataSize))
        for (i in 0 until sampleCount) output.write(shortToLittleEndian(pcm[i]))
    }
}

private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte()
)

private fun shortToLittleEndian(value: Short): ByteArray = byteArrayOf(
    (value.toInt() and 0xFF).toByte(),
    ((value.toInt() shr 8) and 0xFF).toByte()
)
