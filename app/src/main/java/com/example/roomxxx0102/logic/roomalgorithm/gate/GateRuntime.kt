package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.content.Context
import kotlin.math.max

/** Scheduling switches contain no room state. Metrics are bounded and never drive a room transfer. */
object GateRuntime {
    @Volatile var enabled = false
        private set
    @Volatile var samplePeriodMs = 50L
        private set
    @Volatile var captureEdge = 1280
        private set
    @Volatile var backgroundRevision = 0L
        private set
    @Volatile var poseMs = 0L
        private set
    @Volatile var captureMs = 0.0
        private set
    @Volatile var roundMs = 0.0
        private set
    @Volatile var outputFps = 0.0
        private set
    @Volatile var renderedFps = 0.0
        private set
    @Volatile var busyDrops = 0L
        private set
    @Volatile var lastError: String = ""
        private set
    private var lastOutputNs = 0L
    private var renderTimeNs = 0L
    private var renderedCount = -1
    private val rows = ArrayDeque<String>()
    private var parameterSummary = ""

    @Synchronized fun configure(id: String?, context: Context?) {
        val method = GateMethod.fromId(id)
        enabled = method != null
        if (method != null) {
            val params = GateSettings.read(context, method)
            samplePeriodMs = 1000L / params.sampleHz
            captureEdge = params.captureEdge
            parameterSummary = params.toString()
        }
        poseMs=0; captureMs=0.0; roundMs=0.0; outputFps=0.0; renderedFps=0.0
        lastOutputNs=0; renderTimeNs=0; renderedCount=-1; busyDrops=0; lastError=""; rows.clear()
    }
    @Synchronized fun requestBackgroundReset() { backgroundRevision++ }
    fun recordPose(ms: Long) { if(enabled) poseMs=ms.coerceAtLeast(0) }
    fun recordCapture(ms: Double) { if(enabled) captureMs=ms }
    fun recordRound(ms: Double) { if(enabled) roundMs=ms }
    @Synchronized fun recordBusyDrop() { if(enabled) busyDrops++ }
    @Synchronized fun recordRendered(count: Int?) {
        if(!enabled || count==null) return
        val now=System.nanoTime()
        if(renderTimeNs!=0L && now-renderTimeNs>=500000000L) {
            renderedFps=max(0,count-renderedCount)*1e9/(now-renderTimeNs)
            renderedCount=count;renderTimeNs=now
        } else if(renderTimeNs==0L) { renderedCount=count;renderTimeNs=now }
    }
    @Synchronized internal fun recordOutput(id: String,time:Long,visual:GateVisualBatch,coreMs:Double,decision:GateDecision) {
        val now=System.nanoTime()
        if(lastOutputNs>0) {
            val measured=1e9/(now-lastOutputNs).coerceAtLeast(1)
            outputFps=if(outputFps==0.0) measured else outputFps*0.8+measured*0.2
        }
        lastOutputNs=now
        lastError=visual.message.takeUnless { it in setOf("OK","IDLE_NO_VISUAL_WORK") }.orEmpty()
        val evidence = visual.evidence.joinToString(";") { "${it.gate}:${it.track}:contact=${it.contact}:cells=${it.ownedCells}:bg=${it.backgroundReady}:remain=${it.visibleFraction}:restore=${it.restoredFraction}:flow=${it.motionVerified}:${it.reason}" }
        rows.add("$id\t$time\t$poseMs\t${visual.costMs}\t$coreMs\t${visual.gapMs}\t${visual.active}\t${visual.points}\t$busyDrops\t${visual.message}\t${decision.events.joinToString { "${it.from}->${it.to}:${it.gate}" }}\t${decision.counts}\t${decision.notes}\t$evidence\t$captureMs\t$roundMs\t$outputFps\t$renderedFps")
        while(rows.size>1200) rows.removeFirst()
    }
    @Synchronized fun diagnostics():String = "# $parameterSummary\nalgorithm\tmedia_ms\tpose_tracker_ms\tvisual_ms\tfsm_ms\tgap_ms\tactive_gates\tpoints\tbusy_skips\tvisual_status\tevents\tcounts\tnotes\tevidence\tcapture_ms\tprevious_round_ms\toutput_fps\trendered_fps\n"+rows.joinToString("\n")
}
