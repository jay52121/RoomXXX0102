package sisp.audit.motion
import kotlin.math.abs
import sisp.audit.motion.PortalImageMotion.Landmark
import sisp.audit.motion.PortalImageMotion.Sample
fun main(){
    var tests=0
    fun close(a:Double,b:Double){check(abs(a-b)<1e-9){"$a != $b"}}
    val all=(0..16).map{Landmark(it,.3+(it%3)*.01,.3+it*.01,1.0)}
    fun sample(t:Long,ids:Set<Int> =(0..16).toSet(),moveX:Double=0.0,moveY:Double=0.0,track:Int=1) =
        Sample(track,t,all.filter{it.id in ids}.map{it.copy(x=it.x+moveX,y=it.y+moveY)})
    val start=sample(0)
    val disappearance=PortalImageMotion.estimate(start,sample(100,(4..12).toSet()))!!
    close(disappearance.dx,0.0);close(disappearance.dy,0.0);tests++
    for(period in listOf(33,50,75,90,100,120,150,200)){
        val m=PortalImageMotion.estimate(start,sample(period.toLong(),(2..12).toSet(),.03*period/1000.0,-.02*period/1000.0))!!
        close(m.vx,.03);close(m.vy,-.02)
    };tests++
    val badFeet=Sample(1,100,all.map{if(it.id>=13)it.copy(x=.99,y=.99)else it})
    close(PortalImageMotion.estimate(start,badFeet)!!.dy,0.0);tests++
    check(PortalImageMotion.estimate(start,sample(100,track=2))==null);tests++
    check(PortalImageMotion.estimate(start,sample(0))==null);tests++
    check(PortalImageMotion.estimate(start,sample(501))==null);tests++
    check(PortalImageMotion.estimate(start,sample(100,setOf(0,5,6)))==null);tests++
    val outlier=Sample(1,100,all.map{if(it.id==9)it.copy(x=100.0,y=200.0)else it})
    close(PortalImageMotion.estimate(start,outlier)!!.dx,0.0);tests++
    println("IMAGE_MOTION_CONTRACTS=$tests PASS (NOT an event-accuracy claim)")
}
