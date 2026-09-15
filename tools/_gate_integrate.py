from pathlib import Path
root=Path(__file__).resolve().parents[1]
b='app/src/main/java/com/example/roomxxx0102/'
def edit(path,pairs):
 p=root/path;s=p.read_text(encoding='utf-8')
 for old,new in pairs:
  assert s.count(old)==1,(path,old[:100],s.count(old))
  s=s.replace(old,new,1)
 p.write_text(s,encoding='utf-8')
edit(b+'logic/roomalgorithm/RoomAlgorithmRegistry.kt',[
 ('import android.content.Context','import android.content.Context\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateAlgorithms'),
 ('    private val registrations = listOf(','    private val registrations = listOf(\n'+''.join('        Registration(AlgorithmOption(GateAlgorithms.'+key+', "'+label+'")) { config ->\n            GateAlgorithms.create(GateAlgorithms.'+key+', config.appContext)\n        },\n' for key,label in [('DIFFERENCE_ID','\\u95e8\\u53e3\\u5dee\\u5206\\uff08\\u9ad8\\u5e27\\u7387\\uff09'),('FLOW_ID','\\u95e8\\u53e3\\u5149\\u6d41\\uff08\\u7cbe\\u5ea6\\u4f18\\u5148\\uff09'),('MOG2_ID','OpenCV MOG2\\uff08\\u81ea\\u9002\\u5e94\\u524d\\u666f\\uff09')])),
 ('        val algorithmId = resolveAlgorithmId(selectedId)\n        val presenceVersion', '        val algorithmId = resolveAlgorithmId(selectedId)\n        if (GateAlgorithms.handles(algorithmId)) return GateAlgorithms.configurationKey(algorithmId, config.appContext)\n        val presenceVersion')])
edit(b+'ui/activities/MainActivity.kt',[
 ('import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub','import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateAlgorithms\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateRuntime'),
 ('            val frameTimestampMs = sourceMeta.stamp.timestampMs','            GateRuntime.recordPose(time)\n            val frameTimestampMs = sourceMeta.stamp.timestampMs'),
 ('if (roomAlgorithm.algorithmId == RoomAlgorithmRegistry.PORTAL_V3_FLOW_ID) {','if (roomAlgorithm.algorithmId == RoomAlgorithmRegistry.PORTAL_V3_FLOW_ID || GateAlgorithms.handles(roomAlgorithm.algorithmId)) {'),
 ('            PortalFrameHub.setEnabled(roomAlgorithm.algorithmId == RoomAlgorithmRegistry.PORTAL_V3_FLOW_ID)','            PortalFrameHub.setEnabled(roomAlgorithm.algorithmId == RoomAlgorithmRegistry.PORTAL_V3_FLOW_ID)\n            GateRuntime.configure(roomAlgorithm.algorithmId, applicationContext)'),
 ('        PortalFrameHub.setEnabled(false)','        PortalFrameHub.setEnabled(false)\n        GateRuntime.configure(null, applicationContext)')])
edit(b+'ui/drawers/PoseDrawer.kt',[
 ('import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3Overlay','import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3Overlay\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateOverlay'),
 ('        PortalV3Overlay.draw(canvas, drawLeft, drawTop, drawWidth, drawHeight)','        PortalV3Overlay.draw(canvas, drawLeft, drawTop, drawWidth, drawHeight)\n        GateOverlay.draw(canvas, drawLeft, drawTop, drawWidth, drawHeight)')])
edit(b+'ui/settings/SettingsHomeFragment.kt',[
 ('import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3Settings','import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3Settings\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateSettings'),
 ('text = "V3 \u8d77\u59cb\u4eba\u6570\uff08\u53ef\u9009\uff09"','text = "\\u95e8\\u53e3\\u7b97\\u6cd5 / V3 \\u8d77\\u59cb\\u4eba\\u6570\\uff08\\u53ef\\u9009\\uff09"'),
 ('            algorithmParent.addView(baselineButton, algorithmParent.indexOfChild(algorithmRow) + 1)','''            algorithmParent.addView(baselineButton, algorithmParent.indexOfChild(algorithmRow) + 1)
            val parametersButton = android.widget.Button(requireContext()).apply {
                text = "\\u95e8\\u53e3\\u7b97\\u6cd5\\u53c2\\u6570 / \\u8bca\\u65ad"
                setOnClickListener { GateSettings.show(requireContext()) }
            }
            algorithmParent.addView(parametersButton, algorithmParent.indexOfChild(algorithmRow) + 2)''')])
edit(b+'logic/video/VideoPlayerFacade.kt',[
 ('    fun getDurationMs(): Int?','    fun getDurationMs(): Int?\n\n    fun getRenderedVideoFrameCount(): Int? = null')])
edit(b+'logic/video/ExoVideoPlayer.kt',[
 ('    override fun getDurationMs(): Int?', '''    override fun getRenderedVideoFrameCount(): Int? {
        val counters = player.videoDecoderCounters ?: return null
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    override fun getDurationMs(): Int?''')])
helper=r'''    /** Reserve BEFORE reading TextureView; at most one accepted frame exists, never catch up. */
    private fun analyzeGateFrame(player: VideoPlayerFacade) {
        if (!player.isPlaying() && !isStillMode) {
            handler.postDelayed(analyzeRunnable, 200)
            return
        }
        if (!inferenceInFlight.compareAndSet(false, true)) {
            GateRuntime.recordBusyDrop()
            handler.postDelayed(analyzeRunnable, 8)
            return
        }
        val started = System.nanoTime()
        var submitted = false
        try {
            val advanced = computeTemporalAdvanced(player)
            val stepping = frameStepController.hasPendingStep()
            if (AppSettings.isStillStandardFrameEnabled && isStillMode && !player.isPlaying() && !stepping && !advanced) return
            val position = player.getCurrentPositionMs() ?: return
            val viewW = textureView.width
            val viewH = textureView.height
            if (viewW <= 0 || viewH <= 0) return
            val captureStart = System.nanoTime()
            // Hand matching keeps its original resolution; otherwise avoid reading a full-screen bitmap.
            val bitmap = if (handSmokeTester != null) textureView.bitmap else {
                val scale = minOf(1.0, GateRuntime.captureEdge.toDouble() / maxOf(viewW, viewH))
                textureView.getBitmap((viewW * scale).roundToInt().coerceAtLeast(2), (viewH * scale).roundToInt().coerceAtLeast(2))
            } ?: return
            GateRuntime.recordCapture((System.nanoTime() - captureStart) / 1e6)
            GateRuntime.recordRendered(player.getRenderedVideoFrameCount())
            val signature = FrameSignatureUtils.create(bitmap)
            val digest = signature.summary
            if (digest != lastObservedFrameDigest) { observedFrameSeq++; lastObservedFrameDigest = digest }
            if (frameStepController.onFrameObserved(position, signature)) return
            val stamp = PortalFrameHub.capture(bitmap, position.toLong(), advanced)
            val roi = nextFrameRoi?.let(::RectF)
            val handRoi = (nextHandFrameRoi ?: roi)?.let(::RectF)
            handSmokeTester?.detect(bitmap, handRoi)
            lastAnalysisPositionMs = position
            RoiLogAggregator.updateFrameDigest(digest, position, advanced)
            val poseMode = isPoseMode
            val suppress = isStillMode && System.currentTimeMillis() < suppressStagnantUnlockUntilMs
            inferenceExecutor.execute {
                try {
                    if (stamp.epoch != PortalFrameHub.epoch) return@execute
                    if (poseMode) poseAnalyzer?.analyzeBitmapAndTrackPoses(
                        bitmap = bitmap, roi = roi, drawOnOverlay = true,
                        temporalAdvanced = advanced, suppressStagnantUnlock = suppress, frameStamp = stamp
                    ) else yoloAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = true)
                } catch (e: Exception) {
                    Log.e("GateFrame", "accepted frame failed; not an empty detection", e)
                } finally {
                    GateRuntime.recordRound((System.nanoTime() - started) / 1e6)
                    inferenceInFlight.set(false)
                    handler.post {
                        if (isAnalyzing && videoPlayer === player) {
                            val elapsedMs = (System.nanoTime() - started) / 1000000
                            val delay = if (GateRuntime.enabled) (GateRuntime.samplePeriodMs - elapsedMs).coerceAtLeast(0) else 100
                            handler.removeCallbacks(analyzeRunnable)
                            handler.postDelayed(analyzeRunnable, delay)
                        }
                    }
                }
            }
            submitted = true
        } catch (e: Exception) {
            Log.e("GateFrame", "frame capture failed", e)
        } finally {
            if (!submitted) {
                inferenceInFlight.set(false)
                if (isAnalyzing && videoPlayer === player) handler.postDelayed(analyzeRunnable, GateRuntime.samplePeriodMs)
            }
        }
    }

'''
edit(b+'logic/video/VideoFeeder.kt',[
 ('import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub','import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub\nimport com.example.roomxxx0102.logic.roomalgorithm.gate.GateRuntime'),
 ('''            if (!isAnalyzing || player == null) {
                return
            }''','''            if (!isAnalyzing || player == null) {
                return
            }
            if (GateRuntime.enabled) {
                analyzeGateFrame(player)
                return
            }'''),
 ('    private fun submitInferenceTask(',helper+'    private fun submitInferenceTask('),
 ('            player.seekTo(target.toLong(), seekMode)','            if (GateRuntime.enabled) PortalFrameHub.resetSource()\n            player.seekTo(target.toLong(), seekMode)')])
