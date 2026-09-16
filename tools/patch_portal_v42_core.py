from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


vision = "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateEventVisionV2.kt"
replace(vision,
'''    val notes: List<String>,
    val origin: Pair<Int,String>? = null,
)''',
'''    val notes: List<String>,
    val origin: Pair<Int,String>? = null,
    val depths: Map<Int, List<PortalDepthEvidence>> = emptyMap(),
)''')
replace(vision,
'''    private data class ActiveResult(val view:GateEventTileView,val evidence:FlowEvidence,val note:String?)''',
'''    private data class ActiveResult(val view:GateEventTileView,val evidence:FlowEvidence,val note:String?,val depth:PortalDepthEvidence?)''')
replace(vision,
'''        val started=System.nanoTime();val notes=mutableListOf<String>();val flows=linkedMapOf<Int,FlowEvidence>()
        val views=mutableListOf<GateEventTileView>();val samples=mutableListOf<FlowSample>();var origin:Pair<Int,String>?=null''',
'''        val started=System.nanoTime();val notes=mutableListOf<String>();val flows=linkedMapOf<Int,FlowEvidence>()
        val depths=linkedMapOf<Int,MutableList<PortalDepthEvidence>>()
        val views=mutableListOf<GateEventTileView>();val samples=mutableListOf<FlowSample>();var origin:Pair<Int,String>?=null''')
replace(vision,
'''                val detection=detections.firstOrNull{it.id==owner}
                val result=processActive(state,gray,detection,decision,timeMs,coverage,started)
                views+=result.view;flows[owner]=merge(flows[owner],result.evidence);samples+=result.evidence.samples;result.note?.let(notes::add)''',
'''                val detection=detections.firstOrNull{it.id==owner}
                val exclusive=detections.count{it.score>=cfg.armScore&&overlapsGate(state.gate,it)}<=1
                val result=processActive(state,gray,detection,decision,timeMs,coverage,exclusive,started)
                views+=result.view;flows[owner]=merge(flows[owner],result.evidence);samples+=result.evidence.samples;result.note?.let(notes::add)
                result.depth?.let{depths.getOrPut(owner){mutableListOf()}.add(it)}''')
replace(vision,
'''            return GateEventVisionResult(flows,views,samples,healthy,elapsed(started),lk?.pointCount?:0,processing.size,historyBytes(),notes,origin)''',
'''            return GateEventVisionResult(flows,views,samples,healthy,elapsed(started),lk?.pointCount?:0,processing.size,historyBytes(),notes,origin,depths.mapValues{it.value.toList()})''')
replace(vision,
'''    private fun processActive(state:PortalState,gray:Mat,detection:FlowDetection?,decision:GateSensorDecision,timeMs:Long,coverage:FlowBox?,started:Long):ActiveResult{''',
'''    private fun processActive(state:PortalState,gray:Mat,detection:FlowDetection?,decision:GateSensorDecision,timeMs:Long,coverage:FlowBox?,exclusive:Boolean,started:Long):ActiveResult{''')
replace(vision,
'''            val window=FlowWindowEvidence(state.gate.id,state.contacted.size,state.clear.peak,remaining,clearFor,state.referenceKnown,true,state.acquiredWhileVisible,timeMs)''',
'''            val window=FlowWindowEvidence(state.gate.id,state.contacted.size,state.clear.peak,remaining,clearFor,state.referenceKnown,exclusive,state.acquiredWhileVisible,timeMs)''')
replace(vision,
'''            gray.copyTo(state.previous);state.lastProcessed=timeMs
            val view=GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),remaining,ownCount,state.history.size,decision.phase,state.owner,state.referenceKnown)
            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null)''',
'''            val depth=if(owner>=0) portalDepthEvidence(state,owner,debugOwned,timeMs,exclusive) else null
            gray.copyTo(state.previous);state.lastProcessed=timeMs
            val view=GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),remaining,ownCount,state.history.size,decision.phase,state.owner,state.referenceKnown)
            return ActiveResult(view,evidence,if(lk?.budgetExceeded==true)"OPTIONAL_LK_BUDGET_REACHED" else null,depth)''')
replace(vision,
'''    private fun viewIdle(state:PortalState,d:GateSensorDecision):GateEventTileView {
        // ARMED/OFF does no pixel processing, so never leave a stale ACTIVE mask on screen.
        GateMaskDebug.clearGate(state.gate.id)
        return GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),state.history.size,d.phase,d.ownerTrack,state.referenceKnown)
    }
    private fun rectBox(r:Rect)=FlowBox(r.x.toDouble()/sourceWidth,r.y.toDouble()/sourceHeight,(r.x+r.width).toDouble()/sourceWidth,(r.y+r.height).toDouble()/sourceHeight)''',
'''    private fun viewIdle(state:PortalState,d:GateSensorDecision):GateEventTileView {
        // ARMED/OFF does no pixel processing, so never leave a stale ACTIVE mask on screen.
        GateMaskDebug.clearGate(state.gate.id)
        return GateEventTileView(state.gate.id,rectBox(state.rect),emptyList(),0,if(state.owned.empty())0 else Core.countNonZero(state.owned),state.history.size,d.phase,d.ownerTrack,state.referenceKnown)
    }
    private fun overlapsGate(g:FlowGate,d:FlowDetection):Boolean {
        val l=g.aperture.minOf{it.x};val r=g.aperture.maxOf{it.x};val t=g.aperture.minOf{it.y};val b=g.aperture.maxOf{it.y}
        val lowerTop=d.box.top+d.box.height*.50
        return d.box.right>l&&d.box.left<r&&d.box.bottom>t&&lowerTop<b
    }
    private fun portalDepthEvidence(state:PortalState,track:Int,mask:Mat?,timeMs:Long,exclusive:Boolean):PortalDepthEvidence? {
        if(mask==null||mask.empty())return null
        val w=mask.cols();val h=mask.rows();if(w<=0||h<=0)return null
        val bytes=ByteArray(w*h);mask.get(0,0,bytes)
        val depthScale=max(0.004,state.gate.aperture.maxOfOrNull{(-state.gate.side(it)).coerceAtLeast(0.0)}?:0.0)
        val bins=IntArray(64);var count=0
        for(y in 0 until h)for(x in 0 until w){
            if((bytes[y*w+x].toInt() and 255)==0)continue
            val p=FlowPoint((state.rect.x+x+.5)/sourceWidth,(state.rect.y+y+.5)/sourceHeight)
            val depth=(-state.gate.side(p)/depthScale).coerceIn(0.0,1.0)
            bins[(depth*63.0).roundToInt().coerceIn(0,63)]++;count++
        }
        if(count<8)return null
        fun q(frac:Double):Double{
            val target=max(1,ceil(count*frac).toInt());var seen=0
            for(i in bins.indices){seen+=bins[i];if(seen>=target)return i/63.0}
            return 1.0
        }
        return PortalDepthEvidence(state.gate.id,track,timeMs,q(.20),q(.50),q(.80),count,exclusive)
    }
    private fun rectBox(r:Rect)=FlowBox(r.x.toDouble()/sourceWidth,r.y.toDouble()/sourceHeight,(r.x+r.width).toDouble()/sourceWidth,(r.y+r.height).toDouble()/sourceHeight)''')

room = "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateRoomAlgorithm.kt"
replace(room,
'''    override val runtimeTag="GateV4.1-${config.method.name}-EVENT_ROI"
    private var core:PortalV3Core?=null''',
'''    override val runtimeTag="GateV4.2-${config.method.name}-EPISODE_CORE"
    private var core:PortalV4Core?=null''')
replace(room,
'''            core=PortalV3Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,
                if(initialKnown) baseline.counts else emptyMap(),
                FlowCorePolicy(true,config.maxGapMs.toLong(),config.contactScale,config.confirmMs.toLong(),config.admissionTravel,
                    config.clearMs.toLong(),config.clearRatio,config.episodeMs.toLong()))''',
'''            core=PortalV4Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,
                if(initialKnown) baseline.counts else emptyMap(),PortalV4Policy.from(config))''')
replace(room,
'''        val decision=engine.step(time,if(successful) anchored else null,visual?.flows.orEmpty(),roi,successful && visual?.healthy!=false)''',
'''        val decision=engine.step(time,detections=if(successful) anchored else null,depths=visual?.depths.orEmpty(),flows=visual?.flows.orEmpty(),coverage=roi,frameHealthy=successful && visual?.healthy!=false)''')
replace(room,
'''        GateOverlay.publish(config.method.label,decision,engine.gates,visual,initialKnown,input.rooms.associate { it.roomId to it.roomName },config)''',
'''        GateOverlay.publish(config.method.label,decision,engine.gates,visual,initialKnown,input.rooms.associate { it.roomId to it.roomName },config,engine.debugSnapshot())''')

overlay = "app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/GateOverlay.kt"
replace(overlay,
'''    private data class Snapshot(val label:String,val d:FlowDecision,val gates:List<FlowGate>,val v:GateEventVisionResult?,val known:Boolean,val names:Map<String,String>)
    @Volatile private var snapshot:Snapshot?=null
    internal fun publish(label:String,d:FlowDecision,gates:List<FlowGate>,v:GateEventVisionResult?,known:Boolean,names:Map<String,String>,config:GateConfig) {
        snapshot=Snapshot(label,d,gates.toList(),v,known,names.toMap())
    }''',
'''    private data class Snapshot(val label:String,val d:FlowDecision,val gates:List<FlowGate>,val v:GateEventVisionResult?,val known:Boolean,val names:Map<String,String>,val coreDebug:Map<Int,PortalV4PersonDebug>)
    @Volatile private var snapshot:Snapshot?=null
    internal fun publish(label:String,d:FlowDecision,gates:List<FlowGate>,v:GateEventVisionResult?,known:Boolean,names:Map<String,String>,config:GateConfig,coreDebug:Map<Int,PortalV4PersonDebug> = emptyMap()) {
        snapshot=Snapshot(label,d,gates.toList(),v,known,names.toMap(),coreDebug)
    }''')
replace(overlay,
'''        lines+="黄色=逐像素帧间变化  橙色=逐像素参考背景差  青色=人体归属  绿色线=B版光流"
        if(s.v==null) lines+="本地视觉不可用：仅使用门底边"''',
'''        lines+="黄色=逐像素帧间变化  橙色=逐像素参考背景差  青色=人体归属  绿色线=B版光流"
        s.coreDebug.values.filter{it.phase!=null}.take(3).forEach { d ->
            val depth=d.depth?.let{String.format(Locale.US,"%.2f",it)}?:"-"
            val side=d.groundSide?.let{String.format(Locale.US,"%.3f",it)}?:"-"
            lines+="#${d.track} ${d.phase} ${d.direction?:""} gate=${d.gateId?:"-"} depth=$depth side=$side ${d.evidence?:""}"
        }
        if(s.v==null) lines+="本地视觉不可用：仅使用门底边"''')
replace(overlay,
'''                "INFERRED_GATE_TRANSFER"->"门口遮挡迁移"
                "MEASURED_GATE_TRANSFER"->"脚点跨门确认"
                else->if(person.accepted) "已接纳" else "待观察"
            }
            canvas.drawText("#${person.person} $status",x(person.box.left),y(person.box.top).coerceAtLeast(top+20*unit),paint)''',
'''                "INFERRED_GATE_TRANSFER"->"门口遮挡迁移"
                "MEASURED_GATE_TRANSFER"->"脚点跨门确认"
                "TRANSITING_IN"->"进门中"
                "TRANSITING_OUT"->"出门中"
                "WAIT_CLEAR"->"已提交·等待离门"
                "STABLE_LIVING"->"稳定客厅"
                "STABLE_ROOM"->"稳定房内"
                else->if(person.accepted) person.status else "待观察"
            }
            val textY=y(person.box.top).coerceAtLeast(top+20*unit)
            canvas.drawText("#${person.person} $status",x(person.box.left),textY,paint)
            s.coreDebug[person.track]?.let { d ->
                val depth=d.depth?.let{String.format(Locale.US,"%.2f",it)}?:"-"
                val side=d.groundSide?.let{String.format(Locale.US,"%.3f",it)}?:"-"
                canvas.drawText("${d.phase?:"STABLE"} ${d.direction?:""} D=$depth S=$side ${d.evidence?:""}",x(person.box.left),textY+16*unit,paint)
            }''')

print("Portal V4.2 episode core patch applied")
