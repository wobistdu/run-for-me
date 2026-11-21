package com.example.terrainrunner

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Fits the rendered shoes to the log or to the supporting terrain triangles. */
class BridgeContact internal constructor(assets:AssetManager, private val folder:String="ridge") {
    private fun buffer(bytes:Int)=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    private fun loadMesh(assets:AssetManager,name:String)=assets.open("$folder/$name.bin").use { input ->
        val bytes=input.readBytes()
        buffer(bytes.size).apply {
            put(bytes); rewind()
            require(8L+80L*getInt(0)+12L*getInt(4)==bytes.size.toLong())
        }
    }
    private val shoes=loadMesh(assets,"shoes")
    private val lowerLegs=loadMesh(assets,"lower-legs")
    private val flexed=if(folder==RunnerStyle.STRIDE.folder)loadMesh(assets,"lower-legs-flex").also {
        require(it.getInt(0)==lowerLegs.getInt(0) && it.getInt(4)==lowerLegs.getInt(4))
    } else null
    private var lastCourse:Course?=null
    private lateinit var deck:ByteBuffer
    private var groundCourse:Course?=null
    private val terrain by lazy { buffer(Course.SAMPLES*Course.SAMPLES*12) }
    private val skeleton=PfnnSkeleton()
    private val bones=FloatArray(31*12)
    private val swingBones=FloatArray(31*12)
    private var decodedPose:Pose?=null
    private external fun measure(shoes:ByteBuffer,deck:ByteBuffer,spans:Int,sides:Int,bones:FloatArray,
        unit:Float,x:Float,y:Float,z:Float,dx:Float,dz:Float,supportFoot:Int,flexMesh:ByteBuffer?,flexWeight:Float):Float

    internal fun minimumLift(t:Trial):Float {
        val f=t.course.crossings?:return 0f
        val x=t.position.mx;val z=t.position.mz
        val reach=Course.RUNNER_HEIGHT
        if(x<f.x0-reach || x>f.x1+reach || abs(z-f.centerZ)>reach+f.halfWidth)return 0f
        if(t.pose.output==null)return 0f
        if(lastCourse!==t.course){
            deck=buffer((f.spans+1)*(f.upperSegments+1)*12)
            for(i in 0..f.spans)for(j in 0..f.upperSegments){
                val vx=f.x0+(f.x1-f.x0)*i/f.spans
                val vz=f.sectionZ(j)
                deck.putFloat(vx).putFloat(f.vertexHeight(t.course,i,j)).putFloat(vz)
            }
            deck.rewind()
            lastCourse=t.course
        }
        prepareBones(t.pose)
        val penetration=measure(shoes,deck,f.spans,f.upperSegments,bones,Network.MODEL_TO_METRES,
            x,t.predictedBaseY,z,t.heading.x,t.heading.z,-1,null,0f)
        // One scene micrometre covers rounding differences between CPU and renderer skinning.
        return if(penetration>0f)penetration+1e-6f else 0f
    }

    private fun prepareBones(source:Pose){
        if(decodedPose===source)return
        skeleton.decode(requireNotNull(source.output))
        setBones(skeleton,bones)
        decodedPose=source
    }
    private fun setBones(pose:PfnnSkeleton,bones:FloatArray){
        for(i in pose.positions.indices){
            val q=pose.rotations[i];val p=pose.positions[i];val k=i*12
            val xx=2*q.x*q.x;val yy=2*q.y*q.y;val zz=2*q.z*q.z
            val xy=2*q.x*q.y;val yz=2*q.y*q.z;val zx=2*q.z*q.x
            val wx=2*q.w*q.x;val wy=2*q.w*q.y;val wz=2*q.w*q.z
            bones[k]=1-yy-zz; bones[k+1]=xy-wz; bones[k+2]=zx+wy; bones[k+3]=p.x
            bones[k+4]=xy+wz; bones[k+5]=1-xx-zz; bones[k+6]=yz-wx; bones[k+7]=p.y
            bones[k+8]=zx-wy; bones[k+9]=yz+wx; bones[k+10]=1-xx-yy; bones[k+11]=p.z
        }
    }

    private fun prepareTerrain(t:Trial){
        val cells=Course.SAMPLES-1
        if(groundCourse!==t.course){
            terrain.clear()
            for(i in 0..cells)for(j in 0..cells){
                terrain.putFloat((i*.1f-Course.CENTER)*Course.CELL)
                    .putFloat(t.course.vertexHeight(i,j))
                    .putFloat((j*.1f-Course.CENTER)*Course.CELL)
            }
            terrain.rewind()
            groundCourse=t.course
        }
    }

    internal fun terrainOffset(t:Trial,previous:Float):Float{
        val output=t.pose.output?:return 0f
        val left=max(output[4],output[5]);val right=max(output[6],output[7])
        val confidence=max(left,right)
        // Retain most of the last correction during a short gap in support.
        // Strong contact still fits the planted sole exactly on this tick.
        val release=if(abs(previous)>.000002f)previous*.96f else 0f
        val start=.08f
        if(confidence<=start)return release
        val cells=Course.SAMPLES-1
        prepareTerrain(t)
        prepareBones(t.pose)
        fun signedOffset(foot:Int)=measure(shoes,terrain,cells,cells,bones,Network.MODEL_TO_METRES,
            t.position.mx,t.predictedBaseY,t.position.mz,t.heading.x,t.heading.z,foot,null,0f)
        fun smooth(v:Float):Float{
            val k=v.coerceIn(0f,1f)
            return k*k*(3f-2f*k)
        }
        fun fit(foot:Int,strength:Float):Float{
            val target=signedOffset(foot)
            if(!target.isFinite())return release
            return release+(target+1e-6f-release)*smooth((strength-start)/(.65f-start))
        }
        val primary=if(left>=right)0 else 1
        val first=fit(primary,confidence)
        val other=min(left,right)
        if(other<=start || (confidence>=.65f && other<.65f))return first
        val overlap=max(first,fit(1-primary,other))
        if(other>=.65f)return overlap
        // Let the landing foot start lifting the body as the outgoing foot unloads.
        return first+(overlap-first)*(1f-smooth((confidence-other)/(.65f-start)))
    }

    internal fun clearSwing(t:Trial):Boolean{
        val output=t.pose.output?:return false
        val pose=t.swingSkeleton
        prepareTerrain(t)
        prepareBones(t.pose)
        skeleton.positions.copyInto(pose.positions)
        skeleton.rotations.copyInto(pose.rotations)
        var corrected=false
        for(foot in 0..1){
            if(max(output[4+2*foot],output[5+2*foot])>=.65f)continue
            fun penetration(modified:Boolean):Float {
                val matrices=if(modified)swingBones.also{setBones(pose,it)} else bones
                return measure(lowerLegs,terrain,Course.SAMPLES-1,Course.SAMPLES-1,matrices,
                    Network.MODEL_TO_METRES,t.position.mx,t.displayBaseY,t.position.mz,
                    t.heading.x,t.heading.z,foot,flexed,
                    if(flexed==null)0f else (if(modified)pose else skeleton).kneeFlex(foot))
            }
            var depth=penetration(corrected)
            if(depth<=0f)continue
            val leg=SwingLeg(skeleton,foot)
            var lift=0f
            var bestDepth=depth;var bestLift=0f
            for(attempt in 0 until 8){
                lift=min(leg.maxLift,lift+(depth+.000005f)/Network.MODEL_TO_METRES)
                leg.place(pose,lift)
                depth=penetration(true)
                if(depth<bestDepth){bestLift=lift;bestDepth=depth}
                if(depth<=0f || lift>=leg.maxLift)break
            }
            if(bestLift!=lift)leg.place(pose,bestLift)
            corrected=corrected || bestLift>0f
        }
        return corrected
    }
}
