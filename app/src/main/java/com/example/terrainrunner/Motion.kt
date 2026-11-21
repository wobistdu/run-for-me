package com.example.terrainrunner

import kotlin.math.*

data class Pose(val phase:Float=0f,val output:FloatArray?=null,val stride:Float?=null,val referenceOffset:Float=0f)
data class MotionInput(val position:Point,val heading:Point,val speed:Float,val grade:Float,val next:Point,val ground:Course,val sampleAhead:(Float)->Point)
interface MotionDriver { fun step(input:MotionInput,previous:Pose,dt:Float):Pose }
/** A trial advances the same fixed clock, for all observation passages, including slow playback. */
class Trial(course:Course,route:List<Point>,private val driver:MotionDriver,private var contact:BridgeContact?=null,
    private val onRockTrail:(Point)->Boolean={false}) {
    var course=course; private set
    var route=route; private set
    internal var displayLift=0f;private set
    // The network's vertical reference is relative to the input root, before stride integration.
    private var inputRoot=route.first()
    internal val predictedBaseY get()=course.height(inputRoot)*Course.CELL+pose.referenceOffset*Network.MODEL_TO_METRES
    internal val swingSkeleton=PfnnSkeleton()
    internal var swingCorrected=false;private set
    private var rockOffset=0f
    internal val displayBaseY get()=predictedBaseY+displayLift+rockOffset
    internal fun useRunner(meshContact:BridgeContact){
        contact=meshContact
        rockOffset=0f
        useGround(course)
    }
    fun useGround(committed:Course){
        course=committed
        displayLift=contact?.minimumLift(this)?:0f
        updateRockOffset()
    }
    private fun updateRockOffset(){
        val rock=onRockTrail(position) && !course.onDeck(position) && displayLift==0f
        rockOffset=when{
            course.onDeck(position) || displayLift>0f->0f
            rock->contact?.terrainOffset(this,rockOffset)?:0f
            abs(rockOffset)>.000002f->rockOffset*.6f
            else->0f
        }
        swingCorrected=rock && contact?.clearSwing(this)==true
    }
    private var checkpoints=emptySet<Int>()
    fun followFromHere(newRoute:List<Point>,stops:List<Int> = emptyList()){
        require(newRoute.size>=2 && position==newRoute.first())
        route=newRoute;checkpoints=stops.toSet();leg=1;done=false;stationaryTicks=0
    }
    // Appending or undoing a future stop keeps the active leg, phase and travelled prefix.
    internal fun replaceTail(newRoute:List<Point>,stops:List<Int>){
        require(!done && leg<newRoute.size && newRoute[leg]==route[leg])
        route=newRoute;checkpoints=stops.toSet()
    }
    companion object { const val DT=1f/60 }
    var position=route.first();private set
    var pose=Pose();private set
    var heading=route.first().let { start ->
        val next=route[1]
        val length=start.distance(next)
        Point((next.x-start.x)/length,(next.z-start.z)/length)
    };private set
    var ticks=0;private set
    var done=false;private set
    private var leg=1
    val lastVisitedIndex get()=if(done)route.lastIndex else leg-1
    private var stationaryTicks=0
    val stalled get()=stationaryTicks>=180
    val seconds get()=ticks*DT
    private fun lookAhead(distance:Float):Point {
        var remaining=distance
        var q=position
        for(i in leg..route.lastIndex){
            val length=q.distance(route[i])
            if(length>remaining)return q.toward(route[i],remaining/length)
            remaining-=length;q=route[i]
        }
        // The tour keeps running beyond a leg. A stationary future horizon at
        // its last point can make the network stop just short of the arrival.
        val end=route.last();val prev=route[route.lastIndex-1]
        val length=prev.distance(end)
        return if(length>.00001f)prev.toward(end,1f+remaining/length) else end
    }
    fun step(){
        if(done)return
        val target=route[leg]
        val left=position.distance(target)
        val ahead=if(left>=.25f)position.toward(target,.25f/left) else lookAhead(.25f)
        val aim=if(left>=.25f)target else ahead
        val dx=aim.x-position.x;val dz=aim.z-position.z
        val length=hypot(dx,dz)
        if(length>1e-6f){
            val desired=Point(dx/length,dz/length)
            val angle=atan2(heading.z*desired.x-heading.x*desired.z,heading.x*desired.x+heading.z*desired.z)
            val limit=4f*(PI.toFloat()/180f)
            heading=if(abs(angle)<=limit)desired else{
                val a=angle.coerceIn(-limit,limit)
                Point(heading.x*cos(a)+heading.z*sin(a),heading.z*cos(a)-heading.x*sin(a))
            }
        }
        val grade=(course.height(ahead)-course.height(position))/max(.00001f,position.distance(ahead))
        val speed=3.2f*Network.BODY_SCALE*(course.crossings?.pace(position)?:1f)/(1+.9f*abs(grade))
        inputRoot=position
        pose=driver.step(MotionInput(position,heading,speed,grade,ahead,course,::lookAhead),pose,DT)
        val stride=pose.stride?: (speed*DT)
        if(stride<.00001f)stationaryTicks++ else stationaryTicks=0
        ticks++
        // Carry the PFNN stride through tessellated bends. Only explicit user stops
        // and the finish need an exact, observable arrival frame.
        var remaining=stride
        while(!done){
            val end=route[leg]
            val distance=position.distance(end)
            if(distance>remaining && !(stride<.00001f && distance<.014f)){
                position=position.toward(end,remaining/distance)
                break
            }
            position=end
            remaining=max(0f,remaining-distance)
            val reached=leg
            if(leg==route.lastIndex)done=true else leg++
            if(reached in checkpoints || remaining<=0f)break
        }
        // Register the displayed root after route advancement, without feeding it into inference.
        // Resolve contact immediately; release over a few fixed ticks at the log ends.
        val required=contact?.minimumLift(this)?:0f
        val release=displayLift*.5f
        displayLift=max(required,if(release>1e-6f)release else 0f)
        updateRockOffset()
    }
}
