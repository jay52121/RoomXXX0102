package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import java.util.Locale

object GateOverlay {
    private data class Snapshot(val label:String,val d:FlowDecision,val gates:List<FlowGate>,val v:GateVisionResult?,val known:Boolean,val names:Map<String,String>)
    @Volatile private var snapshot:Snapshot?=null
    internal fun publish(label:String,d:FlowDecision,gates:List<FlowGate>,v:GateVisionResult?,known:Boolean,names:Map<String,String>,config:GateConfig) {
        snapshot=Snapshot(label,d,gates.toList(),v,known,names.toMap())
    }
    fun clear() { snapshot=null }
    fun draw(canvas:Canvas,left:Float,top:Float,width:Float,height:Float) {
        val s=snapshot?:return
        val unit=(height/720f).coerceIn(.65f,1.6f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=2f*unit;textSize=19f*unit }
        fun x(a:Double)=left+(a*width).toFloat()
        fun y(a:Double)=top+(a*height).toFloat()
        val save=canvas.save();canvas.clipRect(left,top,left+width,top+height)
        for(tile in s.v?.tiles.orEmpty()) {
            val gate=s.gates.firstOrNull { it.id==tile.gate }?:continue
            val clip=canvas.save();val path=Path()
            gate.aperture.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p.x),y(p.y)) else path.lineTo(x(p.x),y(p.y)) };path.close()
            canvas.clipPath(path)
            paint.color=Color.argb(65,255,200,0)
            tile.cells.forEach { cell ->
                val a=tile.box.left+(cell%4)*tile.box.width/4;val b=tile.box.top+(cell/4)*tile.box.height/8
                canvas.drawRect(x(a),y(b),x(a+tile.box.width/4),y(b+tile.box.height/8),paint)
            }
            canvas.restoreToCount(clip)
            paint.color=if(tile.warmed) Color.GREEN else Color.YELLOW
            val status=when(tile.state) {
                "BACKGROUND_WARMUP"->"\u80cc\u666f\u9884\u70ed"
                "CONTACT"->"\u4eba\u4f53\u63a5\u89e6"
                "OCCLUSION"->"\u906e\u6321\u89c2\u5bdf"
                "MULTI_PERSON"->"\u591a\u4eba\u51b2\u7a81"
                "SCENE_UNRELIABLE"->"\u753b\u9762\u53d8\u5316"
                else->"\u7a7a\u95f2"
            }
            canvas.drawText("${s.names[gate.room]?:gate.room}:$status",x(tile.box.left),y(tile.box.top)-3*unit,paint)
        }
        paint.color=Color.CYAN;paint.strokeWidth=3f*unit
        s.gates.forEach { canvas.drawLine(x(it.a.x),y(it.a.y),x(it.b.x),y(it.b.y),paint) }
        paint.strokeWidth=1f*unit;paint.color=Color.GREEN
        s.v?.samples?.take(384)?.forEach { sample -> sample.current?.let { q ->
            canvas.drawLine(x(sample.previous.x),y(sample.previous.y),x(q.x),y(q.y),paint)
            canvas.drawCircle(x(q.x),y(q.y),2*unit,paint)
        } }
        for(person in s.d.people.filter { it.track>=0 }) {
            paint.color=if(person.accepted) Color.CYAN else Color.GRAY
            person.ground?.let { g->
                paint.style=if(g.strong) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawCircle(x(g.point.x),y(g.point.y),5*unit,paint);paint.style=Paint.Style.FILL
            }
            val status=when(person.status) {
                "OCCLUDED_LIVING"->"\u5ba2\u5385\u906e\u6321\uff0c\u4e0d\u6362\u623f"
                "INSIDE_UNSEEN"->"\u623f\u5185\u4e0d\u53ef\u89c1"
                "CROSSING_PENDING"->"\u8de8\u95e8\u5f85\u786e\u8ba4"
                "AMBIGUOUS_PORTAL","UNRESOLVED_ORIGIN","AMBIGUOUS_EXIT_ORIGIN"->"\u6765\u6e90\u95e8\u5f85\u5b9a"
                "WAIT_HUMAN_MOTION","CANDIDATE"->"\u5019\u9009\u4eba\u4f53"
                "PERSON_OVERLAP","IDENTITY_CONFLICT","UNRESOLVED_IDENTITY"->"\u8eab\u4efd\u5f85\u5b9a"
                "INFERRED_GATE_TRANSFER"->"\u95e8\u53e3\u906e\u6321\u8fc1\u79fb"
                "MEASURED_GATE_TRANSFER"->"\u811a\u70b9\u8de8\u95e8\u786e\u8ba4"
                else->if(person.accepted) "\u5df2\u63a5\u7eb3" else "\u5f85\u89c2\u5bdf"
            }
            canvas.drawText("#${person.person} $status",x(person.box.left),y(person.box.top).coerceAtLeast(top+20*unit),paint)
        }
        val lines=mutableListOf(s.label)
        lines+=if(s.known) "\u521d\u59cb\u4eba\u6570\u5df2\u8bbe\u5b9a" else "\u4ec5\u663e\u793a\u5df2\u77e5\u4eba\u6570\uff1b\u9690\u85cf\u521d\u503c\u672a\u77e5"
        val exterior=s.gates.filter { it.isExterior }.map { it.room }.toSet()
        s.d.counts.filterKeys { it !in exterior }.entries.chunked(3).forEach { group ->
            lines+=group.joinToString("  ") { (id,n) ->
                val lo=s.d.lower[id]?:n;val hi=s.d.upper[id]?:n
                "${s.names[id]?:id}:$n"+if(lo!=hi) "[$lo..$hi]" else ""
            }
        }
        lines+="\u8f93\u51fa ${String.format(Locale.US,"%.1f",GateRuntime.outputFps)}fps  Pose ${GateRuntime.poseCostMs}ms"
        lines+="\u89c6\u89c9 ${s.v?.costMs?:0}ms  \u5149\u6d41 ${s.v?.points?:0}\u70b9  \u6574\u8f6e ${GateRuntime.pipelineCostMs}ms"
        lines+="\u622a\u56fe ${GateRuntime.captureCostMs}ms  \u6392\u961f 0  \u8df3\u91c7\u8bf7\u6c42 ${GateRuntime.skipped}"
        lines+="\u9ec4\u683c=\u524d\u666f\uff08\u975e\u4eba\u4f53\u5206\u5272\uff09 \u7eff\u70b9=B\u7248\u771f\u5b9e\u5149\u6d41"
        if(s.v==null) lines+="\u672c\u5730\u89c6\u89c9\u4e0d\u53ef\u7528\uff1a\u4ec5\u4f7f\u7528\u95e8\u5e95\u8fb9"
        if(s.v?.notes?.any { it.contains("SCENE_CHANGED") }==true) lines+="\u753b\u9762\u6574\u4f53\u53d8\u5316\uff1a\u8bf7\u68c0\u67e5\u5149\u7167\u6216\u673a\u4f4d"
        paint.color=Color.argb(195,0,0,0)
        canvas.drawRect(left+5,top+5,left+minOf(width,510*unit),top+(lines.size*23+15)*unit,paint)
        paint.color=Color.WHITE
        lines.forEachIndexed { i,line->canvas.drawText(line,left+12,top+(30+i*23)*unit,paint) }
        canvas.restoreToCount(save)
    }
}
