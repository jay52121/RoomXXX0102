package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import kotlin.math.*

/** B only. At most two portal crops, one forward/backward pair per crop, no catch-up queue. */
internal class GateLk(private val cfg: GateConfig) {
    data class Job(val person: Int,val gate: FlowGate,val rect: Rect,val seedMask: Mat,val allowSeed: Boolean)
    private data class Dot(val p: Point,val cell: Int,val age: Int)
    private class State(val person:Int,val gate:String,var rect:Rect) {
        var dots=emptyList<Dot>();var seeded=-10000L;var time=-1L
    }
    private val states=linkedMapOf<String,State>()
    var budgetExceeded=false;private set
    val pointCount get()=states.values.sumOf { it.dots.size }
    fun reset() { states.clear();budgetExceeded=false }

    fun update(previous:Mat?,current:Mat,jobs:List<Job>,time:Long,width:Int,height:Int,started:Long): Map<Int,FlowEvidence> {
        budgetExceeded=false
        val grouped=mutableMapOf<Int,MutableList<FlowEvidence>>()
        val active=jobs.map { "${it.person}:${it.gate.id}" }.toSet()
        states.keys.retainAll(active)
        try {
            for(job in jobs.take(2)) {
                val key="${job.person}:${job.gate.id}"
                if(elapsed(started)>=cfg.optionalBudgetMs) {
                    states.remove(key);budgetExceeded=true;continue
                }
                val state=states.getOrPut(key) { State(job.person,job.gate.id,job.rect) }
                if(state.rect!=job.rect || time-state.time>cfg.maxGapMs) state.dots=emptyList()
                state.rect=job.rect
                val old=previous?.submat(job.rect);val now=current.submat(job.rect)
                val samples=mutableListOf<FlowSample>()
                var delta=FlowPoint(0.0,0.0);var reliable=false
                try {
                    if(old!=null && state.dots.isNotEmpty()) {
                        val starts=state.dots.map { it.p }
                        val ends=track(old,now,starts)
                        val dx=median(ends.mapIndexedNotNull { i,q -> q?.let { it.x-starts[i].x } })
                        val dy=median(ends.mapIndexedNotNull { i,q -> q?.let { it.y-starts[i].y } })
                        val cutoff=max(2.0,3.0*median(ends.mapIndexedNotNull { i,q -> q?.let { hypot(it.x-starts[i].x-dx,it.y-starts[i].y-dy) } }))
                        val kept=mutableListOf<Dot>()
                        state.dots.forEachIndexed { i,d ->
                            val q=ends[i]?.takeIf { hypot(it.x-d.p.x-dx,it.y-d.p.y-dy)<=cutoff }
                            val before=normalized(d.p,job.rect,width,height)
                            if(q!=null) {
                                val after=normalized(q,job.rect,width,height)
                                if(job.gate.containsBody(after)) {
                                    kept+=Dot(q,d.cell,d.age+1)
                                    samples+=FlowSample(before,after,d.cell,d.age+1)
                                }
                            }
                        }
                        delta=FlowPoint(dx/width,dy/height)
                        reliable=kept.count { it.age>=2 }>=12 && kept.map { it.cell }.distinct().size>=4 &&
                            kept.size>=state.dots.size*.4 && hypot(dx,dy)<min(job.rect.width,job.rect.height)*.3
                        state.dots=kept
                    }
                    if(job.allowSeed && (state.dots.size<cfg.points*.65 || time-state.seeded>=350) && elapsed(started)<cfg.optionalBudgetMs) {
                        val corners=MatOfPoint();val mask=job.seedMask.clone()
                        try {
                            state.dots.forEach { Imgproc.circle(mask,it.p,3,Scalar(0.0),-1) }
                            Imgproc.goodFeaturesToTrack(now,corners,cfg.points*3,0.01,2.5,mask,3,false,0.04)
                            val cellCounts=IntArray(32)
                            state.dots.forEach { cellCounts[it.cell]++ }
                            val out=state.dots.toMutableList();val quota=max(3,(cfg.points+15)/16)
                            for(q in corners.toArray()) {
                                if(out.size>=cfg.points || pointCount-state.dots.size+out.size>=cfg.points*2) break
                                val cell=min(7,(q.y*8/job.rect.height).toInt())*4+min(3,(q.x*4/job.rect.width).toInt())
                                if(cell !in 0..31 || cellCounts[cell]>=quota) continue
                                cellCounts[cell]++;out+=Dot(q,cell,0)
                            }
                            state.dots=out;state.seeded=time
                        } finally { corners.release();mask.release() }
                    }
                    state.time=time
                    grouped.getOrPut(job.person) { mutableListOf() } += FlowEvidence(job.person,delta,samples,reliable,reason="PORTAL_LOCAL_LK_FB")
                } finally { old?.release();now.release() }
            }
        } finally { jobs.forEach { it.seedMask.release() } }
        return grouped.mapValues { (id,fs) ->
            val best=fs.maxBy { it.samples.size }
            val consistent=fs.filter { it.reliable }.all { it.delta.distance(best.delta)<0.012 }
            best.copy(id=id,samples=fs.flatMap { it.samples },reliable=best.reliable && consistent)
        }
    }

    private fun track(from:Mat,to:Mat,points:List<Point>): List<Point?> {
        if(points.isEmpty()) return emptyList()
        val a=MatOfPoint2f(*points.toTypedArray());val b=MatOfPoint2f();val back=MatOfPoint2f()
        val status=MatOfByte();val reverseStatus=MatOfByte();val errors=MatOfFloat();val reverseErrors=MatOfFloat()
        try {
            val criteria=TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,20,.03)
            Video.calcOpticalFlowPyrLK(from,to,a,b,status,errors,Size(21.0,21.0),cfg.pyramidLevel,criteria,0,1e-4)
            Video.calcOpticalFlowPyrLK(to,from,b,back,reverseStatus,reverseErrors,Size(21.0,21.0),cfg.pyramidLevel,criteria,0,1e-4)
            val p=b.toArray();val r=back.toArray();val s=status.toArray();val rs=reverseStatus.toArray();val e=errors.toArray()
            return points.indices.map { i ->
                val q=p.getOrNull(i);val v=r.getOrNull(i)
                if(q==null || v==null || s.getOrElse(i){0}==0.toByte() || rs.getOrElse(i){0}==0.toByte() ||
                    !q.x.isFinite() || !q.y.isFinite() || q.x<1 || q.y<1 || q.x>=to.cols()-1 || q.y>=to.rows()-1 ||
                    e.getOrElse(i){Float.MAX_VALUE}>22 || hypot(v.x-points[i].x,v.y-points[i].y)>cfg.fbError) null else q
            }
        } finally { listOf(a,b,back,status,reverseStatus,errors,reverseErrors).forEach { it.release() } }
    }

    /** A bounded late-exit hint; not a reconstructed foot or a biological identity claim. */
    fun traceOrigin(d:FlowDetection,current:Mat,history:List<Pair<Long,Mat>>,gates:List<FlowGate>,w:Int,h:Int,started:Long): String? {
        if(history.size<2) return null
        val eligible=gates.filter { !it.isBlind && it.side(d.box.foot)>0 && it.distance(d.box.foot)<d.box.height*.22 }
        if(eligible.isEmpty()) return null
        val mask=Mat.zeros(h,w,CvType.CV_8UC1);val corners=MatOfPoint()
        try {
            val torso=listOf(5,6,12,11).mapNotNull { d.joint(it) }
            if(torso.size!=4) return null
            val poly=MatOfPoint(*torso.map { Point(it.x*w,it.y*h) }.toTypedArray())
            try { Imgproc.fillConvexPoly(mask,poly,Scalar(255.0)) } finally { poly.release() }
            Imgproc.goodFeaturesToTrack(current,corners,64,0.02,3.0,mask)
            var points=corners.toArray().toList()
            if(points.size<12) return null
            var originals=points.toList()
            val votes=mutableMapOf<String,Int>();var newer=current;var followed=0
            for((_,older) in history.takeLast(3).asReversed()) {
                if(elapsed(started)>=cfg.optionalBudgetMs) break
                val tracked=track(newer,older,points)
                val kept=tracked.indices.filter { tracked[it]!=null }
                originals=kept.map { originals[it] };points=kept.map { tracked[it]!! };newer=older
                if(points.size<10) break
                val matches=eligible.filter { g ->
                    val agreeing=points.indices.filter { i ->
                        val earlier=FlowPoint(points[i].x/w,points[i].y/h)
                        val later=FlowPoint(originals[i].x/w,originals[i].y/h)
                        g.containsBody(earlier) && g.side(later)-g.side(earlier)>.008 && earlier.distance(later)>.012
                    }
                    agreeing.size>=maxOf(10,(points.size*.7).toInt()) && agreeing.map { i ->
                        val x=((originals[i].x/w-d.box.left)/d.box.width*4).toInt().coerceIn(0,3)
                        val y=((originals[i].y/h-d.box.top)/d.box.height*8).toInt().coerceIn(0,7)
                        x+4*y
                    }.distinct().size>=4
                }
                if(matches.size==1) votes[matches.single().id]=(votes[matches.single().id]?:0)+1
                followed++
            }
            return if(followed>=2) votes.filterValues { it>=2 }.keys.singleOrNull() else null
        } finally { mask.release();corners.release() }
    }
    private fun normalized(p:Point,r:Rect,w:Int,h:Int)=FlowPoint((p.x+r.x)/w,(p.y+r.y)/h)
    private fun elapsed(t:Long)=(System.nanoTime()-t)/1_000_000L
}
