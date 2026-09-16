package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import java.util.Locale

object GateOverlay {
    private data class Snapshot(val label:String,val d:FlowDecision,val gates:List<FlowGate>,val v:GateEventVisionResult?,val known:Boolean,val names:Map<String,String>)
    @Volatile private var snapshot:Snapshot?=null
    internal fun publish(label:String,d:FlowDecision,gates:List<FlowGate>,v:GateEventVisionResult?,known:Boolean,names:Map<String,String>,config:GateConfig) {
        snapshot=Snapshot(label,d,gates.toList(),v,known,names.toMap())
    }
    fun clear() { snapshot=null }
    fun snapshotPanelLines(): List<String> {
        val s=snapshot?:return emptyList()
        val lines=mutableListOf(s.label+" · Event ROI")
        lines+=if(s.known) "初始人数已设定" else "仅显示已知人数；隐藏初值未知"
        val exterior=s.gates.filter { it.isExterior }.map { it.room }.toSet()
        s.d.counts.filterKeys { it !in exterior }.entries.chunked(3).forEach { group ->
            lines+=group.joinToString("  ") { (id,n) ->
                val lo=s.d.lower[id]?:n;val hi=s.d.upper[id]?:n
                "${s.names[id]?:id}:$n"+if(lo!=hi) "[$lo..$hi]" else ""
            }
        }
        lines+="输出 ${String.format(Locale.US,"%.1f",GateRuntime.outputFps)}fps  Pose ${GateRuntime.poseCostMs}ms"
        lines+="视觉 ${s.v?.costMs?:0}ms  活跃门 ${s.v?.activeGates?:0}  光流 ${s.v?.points?:0}点"
        lines+="门历史 ${(s.v?.historyBytes?:0)/1024}KB  整轮 ${GateRuntime.pipelineCostMs}ms"
        lines+="截图 ${GateRuntime.captureCostMs}ms  队列 0  跳采请求 ${GateRuntime.skipped}"
        lines+="黄色轮廓=真实局部门前景  绿色线=B版局部双向光流"
        if(s.v==null) lines+="本地视觉不可用：仅使用门底边"
        if(s.v?.notes?.any { it.contains("SCENE_CHANGED") }==true) lines+="稀疏全局采样检测到画面整体变化"
        return lines
    }
    fun draw(canvas:Canvas,left:Float,top:Float,width:Float,height:Float) {
        val s=snapshot?:return
        val unit=(height/720f).coerceIn(.65f,1.6f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=2f*unit;textSize=19f*unit }
        fun x(a:Double)=left+(a*width).toFloat()
        fun y(a:Double)=top+(a*height).toFloat()
        val save=canvas.save();canvas.clipRect(left,top,left+width,top+height)

        // Draw the actual foreground contours. The old 4x8 filled debug bricks are deliberately gone.
        for(tile in s.v?.tiles.orEmpty()) {
            if(tile.phase==GateSensorPhase.OFF) continue
            paint.style=Paint.Style.FILL
            paint.color=Color.argb(if(tile.phase==GateSensorPhase.HOLD) 45 else 72,255,190,0)
            tile.contours.forEach { contour ->
                if(contour.size<3) return@forEach
                val path=Path()
                contour.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p.x),y(p.y)) else path.lineTo(x(p.x),y(p.y)) }
                path.close();canvas.drawPath(path,paint)
            }
            paint.style=Paint.Style.STROKE
            paint.color=Color.argb(180,255,210,0)
            tile.contours.forEach { contour ->
                if(contour.size<3) return@forEach
                val path=Path();contour.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p.x),y(p.y)) else path.lineTo(x(p.x),y(p.y)) };path.close()
                canvas.drawPath(path,paint)
            }
            paint.style=Paint.Style.FILL
            paint.color=when(tile.phase) {
                GateSensorPhase.ARMED->Color.YELLOW
                GateSensorPhase.ACTIVE->Color.GREEN
                GateSensorPhase.HOLD->Color.rgb(255,165,0)
                GateSensorPhase.OFF->Color.GRAY
            }
            val gate=s.gates.firstOrNull { it.id==tile.gate }
            if(gate!=null) {
                val status=when(tile.phase) {
                    GateSensorPhase.ARMED->"候选靠门"
                    GateSensorPhase.ACTIVE->"局部视觉"
                    GateSensorPhase.HOLD->"遮挡续看"
                    GateSensorPhase.OFF->"关闭"
                }
                canvas.drawText("${s.names[gate.room]?:gate.room}:$status",x(tile.box.left),y(tile.box.top)-3*unit,paint)
            }
        }

        paint.color=Color.CYAN;paint.strokeWidth=3f*unit;paint.style=Paint.Style.STROKE
        s.gates.forEach { canvas.drawLine(x(it.a.x),y(it.a.y),x(it.b.x),y(it.b.y),paint) }
        paint.strokeWidth=1f*unit;paint.color=Color.GREEN
        s.v?.samples?.take(384)?.forEach { sample -> sample.current?.let { q ->
            canvas.drawLine(x(sample.previous.x),y(sample.previous.y),x(q.x),y(q.y),paint)
            canvas.drawCircle(x(q.x),y(q.y),2*unit,paint)
        } }
        paint.style=Paint.Style.FILL
        for(person in s.d.people.filter { it.track>=0 }) {
            paint.color=if(person.accepted) Color.CYAN else Color.GRAY
            person.ground?.let { g->
                paint.style=if(g.strong) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawCircle(x(g.point.x),y(g.point.y),5*unit,paint);paint.style=Paint.Style.FILL
            }
            val status=when(person.status) {
                "OCCLUDED_LIVING"->"客厅遮挡，不换房"
                "INSIDE_UNSEEN"->"房内不可见"
                "CROSSING_PENDING"->"跨门待确认"
                "AMBIGUOUS_PORTAL","UNRESOLVED_ORIGIN","AMBIGUOUS_EXIT_ORIGIN"->"来源门待定"
                "WAIT_HUMAN_MOTION","CANDIDATE"->"候选人体"
                "PERSON_OVERLAP","IDENTITY_CONFLICT","UNRESOLVED_IDENTITY"->"身份待定"
                "INFERRED_GATE_TRANSFER"->"门口遮挡迁移"
                "MEASURED_GATE_TRANSFER"->"脚点跨门确认"
                else->if(person.accepted) "已接纳" else "待观察"
            }
            canvas.drawText("#${person.person} $status",x(person.box.left),y(person.box.top).coerceAtLeast(top+20*unit),paint)
        }
        canvas.restoreToCount(save)
    }
}
