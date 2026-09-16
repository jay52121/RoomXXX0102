package com.example.roomxxx0102.logic.roomalgorithm.gate

/** Only small volatile diagnostics cross threads; image/identity/FSM state stays on one worker. */
object GateRuntime {
    @Volatile var enabled=false; private set
    @Volatile var sampleMs=50; private set
    @Volatile var captureEdge=2560; private set
    @Volatile var captureCostMs=0L
    @Volatile var poseCostMs=0L
    @Volatile var pipelineCostMs=0L
    @Volatile var skipped=0L
    @Volatile var outputFps=0.0
    private var first=0L; private var outputs=0
    @Synchronized internal fun configure(config: GateConfig?) {
        enabled=config!=null
        sampleMs=config?.sampleMs?:50;captureEdge=config?.captureEdge?:2560
        first=0L;outputs=0;skipped=0;outputFps=0.0
    }
    @Synchronized fun output(now:Long) {
        if(first==0L) first=now
        outputs++
        if(now-first>=1000) { outputFps=outputs*1000.0/(now-first);outputs=0;first=now }
    }
}
