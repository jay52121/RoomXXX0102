package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Locale
import kotlin.math.min

object GateOverlay {
    private data class Frame(val title:String,val decision:GateDecision,val visual:GateVisualBatch,val gates:List<Gate>,
                             val labels:Map<String,String>,val initialKnown:Boolean,val coreMs:Double)
    @Volatile private var frame:Frame?=null
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=2f }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=24f; color=Color.WHITE }
    internal fun publish(title:String,d:GateDecision,v:GateVisualBatch,gates:List<Gate>,labels:Map<String,String>,known:Boolean,cost:Double) {
        frame=Frame(title,d,v,gates,labels,known,cost)
    }
    fun clear() { frame=null }
    fun draw(canvas:Canvas,left:Float,top:Float,width:Float,height:Float) {
        if(!GateRuntime.enabled) return
        val f=frame ?: return
        fun x(p:GP)=left+(p.x*width).toFloat()
        fun y(p:GP)=top+(p.y*height).toFloat()
        canvas.save()
        canvas.clipRect(left,top,left+width,top+height)
        for(g in f.gates) {
            val evidence=f.visual.evidence.filter { it.gate==g.id }
            line.color=if(evidence.any { it.contact }) Color.GREEN else Color.argb(160,120,200,255)
            canvas.drawLine(x(g.a),y(g.a),x(g.b),y(g.b),line)
            if(evidence.isNotEmpty() && g.aperture.size>=3) {
                val path=Path().apply { moveTo(x(g.aperture.first()),y(g.aperture.first()));g.aperture.drop(1).forEach { lineTo(x(it),y(it)) };close() }
                canvas.drawPath(path,line)
            }
            for(v in evidence) {
                fill.color=if(v.backgroundRestored) Color.MAGENTA else if(v.ambiguous)Color.YELLOW else Color.CYAN
                v.marks.take(32).forEach { canvas.drawCircle(x(it),y(it),3.0f,fill) }
                line.color=Color.GREEN
                v.trails.take(80).forEach { (a,b)->canvas.drawLine(x(a),y(a),x(b),y(b),line) }
            }
        }
        for(p in f.decision.people) {
            val g=p.ground ?: continue
            fill.color=if(g.measured) Color.GREEN else Color.YELLOW
            canvas.drawCircle(x(g.p),y(g.p),5f,fill)
        }
        val font=(width/48).coerceIn(16f,27f);text.textSize=font
        val lines=mutableListOf(
            f.title+if(f.initialKnown)" | \u521d\u59cb\u5df2\u77e5" else " | \u9690\u85cf\u521d\u503c\u672a\u77e5",
            "\u8f93\u51fa ${fmt(GateRuntime.outputFps)} fps | \u89c6\u9891\u6e32\u67d3 ${fmt(GateRuntime.renderedFps)} fps | \u5fd9\u8df3\u8fc7 ${GateRuntime.busyDrops}",
            "Pose+ID ${GateRuntime.poseMs} ms | \u89c6\u89c9 ${fmt(f.visual.costMs)} ms | \u5224\u5b9a ${fmt(f.coreMs)} ms",
            "\u622a\u56fe ${fmt(GateRuntime.captureMs)} ms | \u5e27\u95f4\u9694 ${f.visual.gapMs} ms | \u8865\u7b97 0 | \u70b9 ${f.visual.points}",
            f.decision.counts.entries.filter { (_,n)->n>0 }.joinToString("  ") { (id,n)->"${f.labels[id]?:id}:$n" }.ifEmpty { "\u5df2\u77e5\u4eba\u6570: 0" }
        )
        f.decision.people.filter { it.accepted || it.status.startsWith("UNRESOLVED") }.take(3).forEach { p ->
            lines += "#${p.track} ${f.labels[p.room]?:"?"} ${status(p.status)}"+if(p.possible.isEmpty())"" else " [${p.possible.joinToString { f.labels[it]?:it }}]"
        }
        if(f.visual.message !in setOf("OK","IDLE_NO_VISUAL_WORK")) lines += status(f.visual.message)
        val panelWidth=min(width-16,lines.maxOfOrNull { text.measureText(it) }?.plus(20) ?: 300f)
        fill.color=Color.argb(190,0,0,0)
        canvas.drawRect(left+8,top+8,left+8+panelWidth,top+16+(font+6)*lines.size,fill)
        lines.forEachIndexed { i,s ->canvas.drawText(s,left+16,top+10+(font+6)*(i+1),text) }
        canvas.restore()
    }
    private fun fmt(value:Double)=String.format(Locale.US,"%.1f",value)
    private fun status(s:String)=when(s) {
        "CONFIRMED_PERSON","STABLE_ROOM"->"\u4f4d\u7f6e\u7a33\u5b9a"
        "CROSSING_PENDING","EXIT_ORIGIN_PENDING"->"\u8de8\u95e8\u5f85\u786e\u8ba4"
        "GROUND_TRANSFER"->"\u811a\u70b9\u8de8\u95e8\u786e\u8ba4"
        "VISUAL_TRANSFER"->"\u906e\u6321\u7ec4\u5408\u8bc1\u636e\u786e\u8ba4"
        "OCCLUDED_LIVING"->"\u5ba2\u5385\u906e\u6321\uff0c\u4eba\u6570\u4fdd\u7559"
        "INSIDE_UNSEEN"->"\u5b50\u623f\u95f4\u5185\u4e0d\u53ef\u89c1"
        "OCCLUSION_PENDING"->"\u95e8\u53e3\u906e\u6321\u5f85\u5b9a"
        "AMBIGUOUS_PORTAL","AMBIGUOUS_SHARED_EDGE"->"\u95e8\u7684\u6765\u6e90\u4e0d\u660e"
        "PERSON_OVERLAP_NO_TRANSFER"->"\u591a\u4eba\u906e\u6321\uff0c\u6682\u4e0d\u8f6c\u79fb"
        "ROI_NOT_OBSERVED"->"\u4eba\u4f53\u4e0d\u5728\u68c0\u6d4b\u8303\u56f4"
        "VISUAL_GAP","FRAME_GAP_NO_VANISH_EVENT"->"\u5e27\u95f4\u9694\u8fc7\u5927\uff0c\u4e0d\u4f5c\u6d88\u5931\u63a8\u65ad"
        "LIGHT_OR_CAMERA_CHANGE","IMAGE_CHANGED_NO_EVENT"->"\u753b\u9762\u7a81\u53d8\uff0c\u7b49\u5f85\u7a33\u5b9a"
        "BUDGET_OVERRUN_NO_BACKLOG"->"\u89c6\u89c9\u8d85\u9884\u7b97\uff0c\u672a\u79ef\u538b"
        "BACKGROUND_NOT_CALIBRATED"->"\u7b49\u5f85\u7a7a\u95f2\u80cc\u666f"
        "WAIT_STABLE_HUMAN","CANDIDATE"->"\u771f\u4eba\u51c6\u5165\u89c2\u5bdf\u4e2d"
        "INITIAL_SLOT"->"\u521d\u59cb\u5360\u7528\u540d\u989d"
        "RETURNED_NO_TRANSFER"->"\u6298\u8fd4\uff0c\u672a\u8f6c\u79fb"
        "REBOUND_SAME_PERSON"->"\u6062\u590d\u539f\u4eba\u4f53\uff0c\u4e0d\u52a0\u4eba"
        else->if(s.startsWith("UNRESOLVED"))"\u8eab\u4efd\u6216\u6765\u6e90\u5f85\u5b9a" else s
    }
}
