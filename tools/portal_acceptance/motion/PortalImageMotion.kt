package sisp.audit.motion

import kotlin.math.abs
import kotlin.math.sqrt

/** Image-space motion only. It does NOT identify a physical 3D door crossing.
 * No feet, knees, bounding-box bottoms, annotations, or room counts are inputs.
 * Velocity units: normalized image coordinate per second.
 * Residual scatter is NOT a calibrated sensor uncertainty; callers must add
 * their independently calibrated localization/quantization noise floor.
 */
object PortalImageMotion {
    data class Landmark(val id:Int,val x:Double,val y:Double,val confidence:Double)
    data class Sample(val track:Int,val videoMs:Long,val landmarks:List<Landmark>)
    data class Motion(
        val startMs:Long,val endMs:Long,val dx:Double,val dy:Double,
        val vx:Double,val vy:Double,val support:Int,
        val residualSigma:Double,val speedSigma:Double,
    )
    private fun median(v:List<Double>):Double {
        val a=v.sorted();val i=a.size/2
        return if(a.size%2==1)a[i] else (a[i-1]+a[i])/2.0
    }
    fun estimate(before:Sample,after:Sample):Motion? {
        val dt=after.videoMs-before.videoMs
        if(before.track!=after.track || dt !in 1L..500L)return null
        fun valid(s:Sample):Map<Int,Landmark> = s.landmarks.filter {
            it.id in 0..12 && it.x.isFinite() && it.y.isFinite() &&
            it.confidence.isFinite() && it.confidence >= .22
        }.groupBy {it.id}.mapNotNull { (id,list)->
            // Duplicate identities are not silently chosen as independent points.
            if(list.size==1) id to list.single() else null
        }.toMap()
        val a=valid(before);val b=valid(after);val ids=a.keys.intersect(b.keys)
        if(ids.size<4)return null
        val deltas=ids.map { id-> Pair(b.getValue(id).x-a.getValue(id).x,b.getValue(id).y-a.getValue(id).y) }
        val dx=median(deltas.map{it.first});val dy=median(deltas.map{it.second})
        val r=deltas.map { sqrt((it.first-dx)*(it.first-dx)+(it.second-dy)*(it.second-dy)) }
        val sigma=1.4826*median(r)
        // More frequent samples must not turn real slow movement into zero.
        val seconds=dt/1000.0
        return Motion(before.videoMs,after.videoMs,dx,dy,dx/seconds,dy/seconds,
            ids.size,sigma,sigma/seconds)
    }
}
