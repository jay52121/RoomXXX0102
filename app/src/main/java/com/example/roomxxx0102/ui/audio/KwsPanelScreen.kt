package com.example.roomxxx0102.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
import com.example.roomxxx_vocie.KwsConfig
import com.example.roomxxx_vocie.KwsControllerImpl
import com.example.roomxxx_vocie.audio.AudioRecordSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
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
    currentPlayerTimeMsProvider: () -> Long = { 0L }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val meterRunning = remember { mutableStateOf(false) }
    val meterEnabled = remember { mutableStateOf(false) }
    val meterLatest = remember { AtomicReference<MeterSnapshot?>(null) }
    val clipHoldUntilMs = remember { AtomicLong(0L) }
    val meterLastFrameMs = remember { AtomicLong(0L) }
    val lastPeakOverMs = remember { AtomicLong(0L) }
    val meterProgress = remember { mutableStateOf(0f) }
    val peakDb = remember { mutableStateOf(-120.0) }
    val rmsDb = remember { mutableStateOf(-120.0) }
    val clipOn = remember { mutableStateOf(false) }
    val recordSource = remember { AudioRecordSource(context.applicationContext) }
    val recording = remember { mutableStateOf(false) }
    val playing = remember { mutableStateOf(false) }
    val lastRecordingFile = remember { mutableStateOf<File?>(null) }
    val lastRecordingInfo = remember { mutableStateOf("无") }
    val logEntries = remember { mutableStateOf(listOf<OutputLogEntry>()) }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val cfg = pendingConfig.value ?: currentConfig.value
            pendingConfig.value = null
            isListening.value = true
            controller.stop()
            controller.start(cfg)
            listenStatus.value = "监听中"
        } else {
            isListening.value = false
            listenStatus.value = "已停止"
        }
    }

    fun stopMeter() {
        if (meterRunning.value) {
            meterSource.stop()
            meterRunning.value = false
        }
        meterLastFrameMs.set(0L)
        lastPeakOverMs.set(0L)
    }

    fun startMeterIfAllowed() {
        if (meterRunning.value || !meterEnabled.value || recording.value || isListening.value) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
        meterSource.start(currentConfig.value) { frame ->
            val snapshot = computeMeterSnapshot(frame.pcm, clipHoldUntilMs)
            meterLatest.set(snapshot)
            meterLastFrameMs.set(frame.timestampMs)
            if (snapshot.peakDb > -30.0) lastPeakOverMs.set(frame.timestampMs)
        }
        meterRunning.value = true
    }

    LaunchedEffect(Unit) {
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

        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
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
                    playerTimeMs = playerTimeMs,
                    summaryText = "${event.command}  ${summaryLatency}",
                    detailText = "总延迟: ${if (latencyMs >= 0L) "${latencyMs}ms" else "未知"}\n$detail"
                )
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
            recordSource.stop()
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
                            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
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
            LinearProgressIndicator(progress = { meterProgress.value }, color = levelColor(peakDb.value, clipOn.value), modifier = Modifier.fillMaxWidth())
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
                    if (hasPermission) {
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
                    if (recording.value) return@Button
                    isListening.value = false
                    controller.stop()
                    listenStatus.value = "已停止"
                    latestStatus.value = ""
                    stopMeter()
                    recording.value = true
                    val cfg = currentConfig.value
                    val totalSamples = cfg.sampleRate * 5
                    val buffer = ShortArray(totalSamples)
                    val writeIndex = AtomicInteger(0)
                    recordSource.start(cfg) { frame ->
                        val index = writeIndex.get()
                        val remaining = totalSamples - index
                        if (remaining <= 0) return@start
                        val toCopy = min(remaining, frame.pcm.size)
                        System.arraycopy(frame.pcm, 0, buffer, index, toCopy)
                        writeIndex.addAndGet(toCopy)
                        if (writeIndex.get() >= totalSamples) {
                            recordSource.stop()
                            recording.value = false
                            scope.launch {
                                val sampleCount = writeIndex.get()
                                val wavFile = File(context.filesDir, "rec_${System.currentTimeMillis()}.wav")
                                withContext(Dispatchers.IO) { writeWavFile(wavFile, buffer, sampleCount, cfg.sampleRate) }
                                lastRecordingFile.value = wavFile
                                val durationSec = sampleCount.toDouble() / cfg.sampleRate
                                lastRecordingInfo.value = "${wavFile.name} | ${"%.2f".format(durationSec)}s | ${cfg.sampleRate}Hz"
                                startMeterIfAllowed()
                            }
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (recording.value) "录音中" else "录音") }
                Button(onClick = {
                    val wavFile = lastRecordingFile.value ?: return@Button
                    isListening.value = false
                    controller.stop()
                    listenStatus.value = "已停止"
                    latestStatus.value = ""
                    val player = MediaPlayer()
                    try {
                        player.setDataSource(wavFile.absolutePath)
                        player.setOnCompletionListener { mp -> mp.release(); playing.value = false }
                        player.setOnPreparedListener { mp -> playing.value = true; mp.start() }
                        player.prepare()
                    } catch (e: Exception) {
                        player.release()
                        playing.value = false
                        appendLog(
                            logEntries = logEntries,
                            playerTimeMs = currentPlayerTimeMsProvider(),
                            summaryText = "播放失败",
                            detailText = e.message ?: "未知错误"
                        )
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (playing.value) "播放中" else "播放") }
            }

            Text("最近录音: ${lastRecordingInfo.value}", color = Color(0xFFD7DCE0))
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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

private data class OutputLogEntry(
    val id: Long,
    val playerTimeText: String,
    val summaryText: String,
    val detailText: String
)

private fun appendLog(
    logEntries: MutableState<List<OutputLogEntry>>,
    playerTimeMs: Long,
    summaryText: String,
    detailText: String
) {
    val next = ArrayList<OutputLogEntry>(logEntries.value.size + 1)
    next.addAll(logEntries.value)
    next.add(
        OutputLogEntry(
            id = System.nanoTime(),
            playerTimeText = formatPlayerTime(playerTimeMs),
            summaryText = summaryText,
            detailText = detailText
        )
    )
    if (next.size > MAX_LOG_ITEMS) next.subList(0, next.size - MAX_LOG_ITEMS).clear()
    logEntries.value = next
}

private fun formatPlayerTime(playerTimeMs: Long): String {
    val safeMs = if (playerTimeMs < 0L) 0L else playerTimeMs
    val totalSeconds = safeMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
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
