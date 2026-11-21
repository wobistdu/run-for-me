package com.example.terrainrunner

import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Path-following adapter for Holden et al.'s 10-bank linear PFNN. */
class Network(assets:AssetManager):ResettableDriver {
    companion object {
        init { System.loadLibrary("runner") }
        const val BIND_UNIT=.00052f
        // Standing height of the registered SGS runner, 160 PFNN units from sole to crown.
        const val BODY_SCALE=Course.RUNNER_HEIGHT/(160f*BIND_UNIT)
        const val MODEL_TO_METRES=BIND_UNIT*BODY_SCALE
        const val UNIT=Course.CELL/MODEL_TO_METRES
    }
    private val weights=assets.openFd("pfnn/linear.pfnn").use { fd ->
        val expected=(342*2+311*2+10*((342+1)*512+(512+1)*512+(512+1)*311))*4
        require(fd.length==expected.toLong())
        val buf=ByteBuffer.allocateDirect(expected).order(ByteOrder.LITTLE_ENDIAN)
        fd.createInputStream().use{stream->
            val chunk=ByteArray(65536)
            while(buf.hasRemaining()){val n=stream.read(chunk,0,min(chunk.size,buf.remaining()));require(n>0);buf.put(chunk,0,n)}
        }
        buf.rewind();buf
    }
    internal val bridgeContact by lazy { BridgeContact(assets) }
    private val strideContact by lazy { BridgeContact(assets,RunnerStyle.STRIDE.folder) }
    internal fun contactFor(style:RunnerStyle)=when(style){
        RunnerStyle.ORIGINAL->bridgeContact
        RunnerStyle.STRIDE->strideContact
    }
    val idle=FloatArray(311){weights.getFloat((684+it)*4)}
    val parents=assets.open("pfnn/parents.bin").use { s ->
        val f=ByteBuffer.wrap(s.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        IntArray(31){f.float.toInt()}
    }
    private val features=FloatArray(342)
    private val output=FloatArray(311)
    private val history=ArrayDeque<Pair<Point,Point>>()
    private var frame=0
    private external fun predict(weights:ByteBuffer,phase:Float,input:FloatArray,output:FloatArray)
    internal fun evaluate(phase:Float,input:FloatArray)=FloatArray(311).also { require(input.size==342 && phase in 0f..<1f);predict(weights,phase,input,it) }
    override fun reset(){history.clear();idle.copyInto(output);frame=0}
    init{reset()}
    override fun step(input:MotionInput,previous:Pose,dt:Float):Pose {
        val dir=input.heading;val right=Point(dir.z,-dir.x)
        val root=input.position;val ground=input.ground
        val height=ground.height(root)
        fun future(t:Int)=input.sampleAhead(t*dt*input.speed)
        for(i in 0..11){
            val offset=(i-6)*10
            val past=if(offset<0)history.getOrNull(max(0,history.size+offset)) else null
            val p=if(offset<0)past?.first?:root else future(offset)
            val next=if(offset>=0)future(offset+1) else p
            val d=if(offset<0)past?.second?:dir else if(next.distance(p)>.0001f)
                Point((next.x-p.x)/next.distance(p),(next.z-p.z)/next.distance(p)) else dir
            val dx=p.x-root.x;val dz=p.z-root.z
            features[i]=(dx*right.x+dz*right.z)*UNIT
            features[12+i]=(dx*dir.x+dz*dir.z)*UNIT
            features[24+i]=d.x*right.x+d.z*right.z;features[36+i]=d.x*dir.x+d.z*dir.z
            val probe=Point(p.x+d.x*.35f,p.z+d.z*.35f)
            val slope=(ground.height(probe)-ground.height(p))/.35f
            val crouch=ground.crouch(p)
            val walk=if(ground.onDeck(p))1f else ((slope-.10f)/.55f).coerceIn(0f,1f)
            features[60+i]=walk*(1-crouch)
            features[72+i]=(1f-walk)*(1-crouch)
            features[84+i]=crouch
            features[96+i]=ground.jump(p)
            val lateral=25f/UNIT
            val halfSpacing=5f*input.speed*dt*Course.CELL*abs(d.z)
            for(j in 0..2){
                val k=(1-j)*lateral
                features[306+12*j+i]=(ground.networkHeight(Point(p.x+d.z*k,p.z-d.x*k),halfSpacing)-height)*UNIT
            }
        }
        // The published network uses the mean of its twelve centreline heights as
        // vertical origin for BOTH terrain inputs and the reconstructed skeleton.
        var referenceOffset=0f
        for(i in 0 until 12)referenceOffset+=features[318+i]/12f
        for(i in 306 until 342)features[i]-=referenceOffset
        output.copyInto(features,120,32,218)
        predict(weights,previous.phase,features,output)
        if(output.any{!it.isFinite()})return Pose(phase=Float.NaN,stride=0f)
        history.addLast(root to dir);if(history.size>60)history.removeFirst()
        val phase=(previous.phase+output[3]).mod(1f)
        if(frame++%30==0) Log.i("TerrainPose","frame=${frame-1} x=${root.x} z=${root.z} phase=$phase heightAhead=${features[327]} kneeY=${output[42]} ankleY=${output[45]} rootDz=${output[1]}")
        // Project forward root displacement onto the validated route. The path owns yaw.
        return Pose(phase=phase,output=output,stride=(output[1]/UNIT).coerceIn(0f,.2f),referenceOffset=referenceOffset)
    }
}
