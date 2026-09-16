package com.example.roomxxx0102.logic.roomalgorithm.flow

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect

/** Immutable cross-thread snapshot; debug graphics never feed back into inference. */
object PortalV3Overlay {
    private data class Snapshot(val decision:FlowDecision,val gates:List<FlowGate>,val samples:List<FlowSample>,val known:Boolean,val names:Map<String,String>,val cost:Long,val notes:List<String>)
    @Volatile private var value:Snapshot?=null
    internal fun publish(d:FlowDecision,g:List<FlowGate>,s:List<FlowSample>,known:Boolean,names:Map<String,String>,cost:Long,notes:List<String>) {
        value=Snapshot(d,g.toList(),s.take(1800),known,names.toMap(),cost,notes.toList())
    }
    fun clear() { value=null }
    fun snapshotPanelLines(): List<String> {
        val s=value?:return emptyList()
        val lines=mutableListOf<String>()
        lines+="V3 " + if(s.known) "\u8d77\u59cb\u4eba\u6570\u5df2\u8bbe\u7f6e" else "\u4ec5\u5df2\u77e5\u4eba\u6570\uff08\u9690\u85cf\u521d\u503c\u672a\u77e5\uff09"
        s.decision.counts.entries.toList().chunked(3).forEach { group ->
            lines+=group.joinToString("  ") { (id,n) ->
                val lo=s.decision.lower[id]?:n;val hi=s.decision.upper[id]?:n
                "${s.names[id]?:id}:" + (if(s.known) "$n" else "\u5df2\u77e5$n") + if(lo!=hi) "[$lo..$hi]" else ""
            }
        }
        lines+="\u5149\u6d41 ${s.cost}ms / ${s.samples.count { it.current!=null }}\u70b9; \u672a\u5b9a\u4f4d ${s.decision.unknown}"
        if(s.notes.any { it.contains("CAMERA_MOVED") }) lines+="\u6444\u50cf\u5934\u5df2\u79fb\u52a8\uff1a\u8bf7\u91cd\u7f6e\u5e76\u68c0\u67e5\u6807\u5b9a"
        return lines
    }
    fun draw(canvas:Canvas,left:Float,top:Float,width:Float,height:Float) {
        val s=value?:return
        val unit=(height/700f).coerceIn(0.65f,1.8f)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=2f*unit; textSize=19f*unit; setShadowLayer(2f,0f,0f,Color.BLACK) }
        fun x(v:Double)=left+(v*width).toFloat()
        fun y(v:Double)=top+(v*height).toFloat()
        val saved=canvas.save();canvas.clipRect(left,top,left+width,top+height)
        for(g in s.gates) {
            p.color=Color.CYAN;p.strokeWidth=4f*unit
            canvas.drawLine(x(g.a.x),y(g.a.y),x(g.b.x),y(g.b.y),p)
        }
        p.strokeWidth=1.5f*unit
        for(t in s.samples) {
            p.color=if(t.current!=null) Color.GREEN else if(t.terminal) Color.MAGENTA else Color.rgb(255,180,0)
            val q=t.current
            if(q!=null) { canvas.drawLine(x(t.previous.x),y(t.previous.y),x(q.x),y(q.y),p);canvas.drawCircle(x(q.x),y(q.y),2f*unit,p) }
            else if(t.terminal) canvas.drawCircle(x(t.previous.x),y(t.previous.y),3.5f*unit,p)
        }
        for(person in s.decision.people.filter { it.track>=0 }) {
            val g=person.ground
            p.color=if(person.accepted) Color.CYAN else Color.GRAY
            if(g!=null) {
                p.style=if(g.strong) Paint.Style.FILL else Paint.Style.STROKE
                p.pathEffect=if(g.strong) null else DashPathEffect(floatArrayOf(4f,3f),0f)
                canvas.drawCircle(x(g.point.x),y(g.point.y),5f*unit,p)
                p.pathEffect=null;p.style=Paint.Style.FILL
            }
            val state=when(person.status) {
                "OCCLUDED_LIVING" -> "\u5ba2\u5385\u906e\u6321\uff1a\u4fdd\u7559\u4eba\u6570"
                "INSIDE_UNSEEN" -> "\u623f\u5185\u4e0d\u53ef\u89c1"
                "CROSSING_PENDING" -> "\u8de8\u95e8\u5f85\u786e\u8ba4"
                "AMBIGUOUS_PORTAL", "AMBIGUOUS_EXIT_ORIGIN", "UNRESOLVED_ORIGIN" -> "\u95e8\u6765\u6e90\u5b58\u7591"
                "UNRESOLVED_IDENTITY", "IDENTITY_CONFLICT", "PERSON_OVERLAP" -> "\u8eab\u4efd\u6216\u91cd\u53e0\u5b58\u7591"
                "WAIT_HUMAN_MOTION", "CANDIDATE" -> "\u5019\u9009\uff1a\u6682\u4e0d\u8ba1\u6570"
                "INFERRED_GATE_TRANSFER" -> "\u89c6\u89c9\u906e\u6321\u8fc1\u79fb"
                "MEASURED_GATE_TRANSFER" -> "\u5e95\u8fb9\u8de8\u8d8a\u786e\u8ba4"
                else -> if(person.accepted) "\u5df2\u786e\u8ba4" else "\u5f85\u786e\u8ba4"
            }
            canvas.drawText("V3 #${person.person} $state",x(person.box.left).coerceIn(left,(left+width-160f*unit).coerceAtLeast(left)),y(person.box.top).coerceAtLeast(top+20f*unit),p)
        }
        canvas.restoreToCount(saved)
    }
}
