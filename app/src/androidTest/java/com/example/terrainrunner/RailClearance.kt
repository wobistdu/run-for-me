package com.example.terrainrunner

import android.content.res.AssetManager
import org.json.JSONObject
import com.pico.spatial.core.math.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Skin runner.glb exactly as the renderer does and clip its triangle edges to each rail's depth slab. */
internal class RailClearance(assets:AssetManager,style:RunnerStyle=RunnerStyle.ORIGINAL){
    private val raw=assets.open("${style.folder}/runner.glb").use{it.readBytes()}
    private val buf=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    private val json=JSONObject(String(raw,20,buf.getInt(12),Charsets.UTF_8))
    private val dataStart=28+buf.getInt(12)
    private val primitive=json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
    private val attrs=primitive.getJSONObject("attributes")
    private fun accessor(i:Int)=json.getJSONArray("accessors").getJSONObject(i)
    private fun start(i:Int):Int{
        val a=accessor(i);val v=json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        return dataStart+v.optInt("byteOffset")+a.optInt("byteOffset")
    }
    private val pos=attrs.getInt("POSITION")
    private val n=accessor(pos).getInt("count")
    private val joint=IntArray(n*4)
    private val weight=FloatArray(n*4)
    private val local=FloatArray(n*4*3)
    private val targetPositions=primitive.optJSONArray("targets")?.let { targets->
        IntArray(targets.length()){start(targets.getJSONObject(it).getInt("POSITION"))}
    }
    private val flexDelta=targetPositions?.let{FloatArray(local.size)}
    private val xyz=FloatArray(n*3)
    private val index=primitive.getInt("indices")
    private val faces=IntArray(accessor(index).getInt("count")){buf.getShort(start(index)+2*it).toInt() and 65535}
    init{
        val joints=start(attrs.getInt("JOINTS_0"));val first=start(pos)
        val matrices=start(json.getJSONArray("skins").getJSONObject(0).getInt("inverseBindMatrices"))
        val weights=start(attrs.getInt("WEIGHTS_0"))
        for(i in 0 until n){
            for(slot in 0..3){
                val k=4*i+slot
                val j=buf.getShort(joints+2*k).toInt()
                joint[k]=j
                weight[k]=buf.getFloat(weights+4*k)
                for(axis in 0..2){
                    var v=buf.getFloat(matrices+64*j+48+4*axis)
                    for(d in 0..2)v+=buf.getFloat(matrices+64*j+16*d+4*axis)*buf.getFloat(first+12*i+4*d)
                    local[3*k+axis]=v*Network.BODY_SCALE
                    targetPositions?.let { targets->
                        val target=targets[if(joint[4*i]<6)0 else 1]
                        var delta=0f
                        for(d in 0..2)delta+=buf.getFloat(matrices+64*j+16*d+4*axis)*buf.getFloat(target+12*i+4*d)
                        flexDelta!![3*k+axis]=delta*Network.BODY_SCALE
                    }
                }
            }
        }
        require(faces.size/3<=50000)
        require((0 until n).all{i->abs((0..3).sumOf{weight[4*i+it].toDouble()}-1)<.0001})
    }
    fun wristVertices(id:Int)= (0 until n).count{joint[4*it]==id && weight[4*it]>.9f}

    private data class KneeEdge(val a:Int,val b:Int,val rest:Float)
    private val kneeEdges by lazy {
        val first=start(pos)
        fun rest(i:Int,d:Int)=buf.getFloat(first+12*i+4*d)
        val edges=mutableSetOf<KneeEdge>()
        for(k in faces.indices step 3)for(d in 0..2){
            val i=faces[k+d];val j=faces[k+(d+1)%3]
            if(rest(i,1)/Network.BIND_UNIT !in 40f..59f || rest(j,1)/Network.BIND_UNIT !in 40f..59f)continue
            val dx=rest(i,0)-rest(j,0);val dy=rest(i,1)-rest(j,1);val dz=rest(i,2)-rest(j,2)
            val length=sqrt(dx*dx+dy*dy+dz*dz)
            if(length>.18f*Network.BIND_UNIT)edges.add(KneeEdge(min(i,j)*3,max(i,j)*3,length*Network.BODY_SCALE))
        }
        edges.toList()
    }
    fun kneeEdgeRatios(t:Trial):FloatArray{
        skin(t)
        return FloatArray(kneeEdges.size){i->
            val (a,b,rest)=kneeEdges[i]
            val dx=xyz[a]-xyz[b];val dy=xyz[a+1]-xyz[b+1];val dz=xyz[a+2]-xyz[b+2]
            sqrt(dx*dx+dy*dy+dz*dz)/rest
        }
    }
    /** Anterior/posterior surface landmarks on each patellar ring, relative to bind thickness. */
    fun kneeDepthRatios(t:Trial):List<Float>{
        skin(t)
        val first=start(pos)
        fun rest(i:Int,d:Int)=buf.getFloat(first+12*i+4*d)
        return listOf(1,-1).map{side->
            val ring=(0 until n).filter{rest(it,0)*side>0 && abs(rest(it,1)/Network.BIND_UNIT-49.5f)<1.8f}
            val a=ring.maxBy{rest(it,2)};val b=ring.minBy{rest(it,2)}
            var bind2=0f;var posed2=0f
            for(axis in 0..2){
                val bind=rest(a,axis)-rest(b,axis);val posed=xyz[a*3+axis]-xyz[b*3+axis]
                bind2+=bind*bind;posed2+=posed*posed
            }
            sqrt(posed2/bind2)/Network.BODY_SCALE
        }
    }

    private val armOffsets=(0..30).filter(style::bindOffset).associateWith { j->
        val m=json.getJSONArray("nodes").getJSONObject(j).getJSONArray("matrix")
        Vector3(m.getDouble(12).toFloat(),m.getDouble(13).toFloat(),m.getDouble(14).toFloat())*(1f/Network.BIND_UNIT)
    }
    private val decoded=PfnnSkeleton()
    private fun skin(t:Trial,feetOnly:Boolean=false,original:Boolean=false){
        val rendered=if(t.swingCorrected && !original)t.swingSkeleton else decoded.apply { decode(requireNotNull(t.pose.output)) }
        val displayed=rendered.positions.copyOf()
        if(!feetOnly){
            for((joint,offset) in armOffsets) {
                val parent=joint-1
                displayed[joint]=displayed[parent]+rendered.rotations[parent].rotateVector(offset)
            }
            for(wrist in listOf(20,27)){
                val delta=displayed[wrist]-rendered.positions[wrist]
                for(i in wrist+1..wrist+3)displayed[i]=rendered.positions[i]+delta
            }
        }
        val base=t.displayBaseY
        val flex=if(flexDelta!=null)floatArrayOf(rendered.kneeFlex(0),rendered.kneeFlex(1)) else null
        for(i in 0 until n){
            if(feetOnly && !isFoot(i) && joint[4*i]!=3 && joint[4*i]!=8)continue
            var lx=0f;var ly=0f;var lz=0f
            for(slot in 0..3){
                val k=4*i+slot;val w=weight[k]
                if(w==0f)continue
                val j=joint[k];val q=rendered.rotations[j];val p=displayed[j]
                var x=local[3*k];var y=local[3*k+1];var z=local[3*k+2]
                if(flex!=null){
                    val w=flex[if(joint[4*i]<6)0 else 1]
                    x+=w*flexDelta!![3*k];y+=w*flexDelta[3*k+1];z+=w*flexDelta[3*k+2]
                }
                val tx=2*(q.y*z-q.z*y);val ty=2*(q.z*x-q.x*z);val tz=2*(q.x*y-q.y*x)
                lx+=w*(x+q.w*tx+q.y*tz-q.z*ty+p.x*Network.MODEL_TO_METRES)
                ly+=w*(y+q.w*ty+q.z*tx-q.x*tz+p.y*Network.MODEL_TO_METRES)
                lz+=w*(z+q.w*tz+q.x*ty-q.y*tx+p.z*Network.MODEL_TO_METRES)
            }
            xyz[3*i]=t.position.mx+t.heading.z*lx+t.heading.x*lz
            xyz[3*i+1]=base+ly
            xyz[3*i+2]=t.position.mz-t.heading.x*lx+t.heading.z*lz
        }
    }
    private fun isFoot(i:Int)=joint[4*i] in 4..5 || joint[4*i] in 9..10
    // Independent of the contact asset and polygon clipper: inspect the APK's rendered mesh.
    // Sample vertices, edge midpoints and interiors, including both shoes through swing.
    fun shoeDeckGap(t:Trial):Float{
        skin(t,true)
        val deck=requireNotNull(t.course.crossings)
        var gap=Float.POSITIVE_INFINITY
        fun probe(x:Float,y:Float,z:Float){
            val p=Course.local(x,z)
            if(deck.onDeck(p))gap=min(gap,y-deck.deckHeight(t.course,p)*Course.CELL)
        }
        for(i in 0 until n)if(isFoot(i))probe(xyz[3*i],xyz[3*i+1],xyz[3*i+2])
        for(i in faces.indices step 3){
            val a=faces[i];val b=faces[i+1];val c=faces[i+2]
            if(!isFoot(a) || !isFoot(b) || !isFoot(c))continue
            for(j in 0..2){
                val k=3*faces[i+j];val l=3*faces[i+(j+1)%3]
                probe((xyz[k]+xyz[l])*.5f,(xyz[k+1]+xyz[l+1])*.5f,(xyz[k+2]+xyz[l+2])*.5f)
            }
            fun center(axis:Int)=(xyz[3*a+axis]+xyz[3*b+axis]+xyz[3*c+axis])/3f
            probe(center(0),center(1),center(2))
        }
        return gap
    }
    fun swingGaps(t:Trial,foot:Int,original:Boolean=false):FloatArray {
        skin(t,true,original)
        val shin=3+5*foot
        val gaps=FloatArray(3){Float.POSITIVE_INFINITY}
        for(i in 0 until n){
            val part=joint[4*i]-shin
            if(part !in 0..2)continue
            val p=Course.local(xyz[3*i],xyz[3*i+2])
            gaps[part]=min(gaps[part],xyz[3*i+1]-t.course.height(p)*Course.CELL)
        }
        return gaps
    }
    fun soleGap(t:Trial,foot:Int):Float{
        skin(t,true)
        var gap=Float.POSITIVE_INFINITY
        val ankle=4+foot*5
        for(i in 0 until n)if(joint[4*i] in ankle..ankle+1){
            val point=Course.local(xyz[3*i],xyz[3*i+2])
            gap=min(gap,xyz[3*i+1]-t.course.height(point)*Course.CELL)
        }
        return gap
    }
    // Lower bound on lateral separation, including both post widths. Checking
    // every vertex in the near frames is conservative even for slanted limbs.
    fun bypassGap(t:Trial,wall:Wall):Float{
        skin(t)
        var right=Float.NEGATIVE_INFINITY
        for(i in 0 until n)right=max(right,xyz[3*i])
        return wall.left-right
    }
    fun sample(t:Trial,f:Crossings,bar:Bar):Float{
        skin(t)
        val near=bar.z-f.barHalfDepth;val far=bar.z+f.barHalfDepth
        val reference=f.laneY+if(bar.overhead)bar.bottom else bar.top
        var gap=Float.POSITIVE_INFINITY
        fun check(x:Float,y:Float){
            if(abs(x-f.laneX)<=f.halfSpan)gap=min(gap,if(bar.overhead)reference-y else y-reference)
        }
        var i=0
        while(i<faces.size){
            for(edge in 0..2){
                val a=3*faces[i+edge];val b=3*faces[i+(edge+1)%3]
                val z=xyz[a+2];val end=xyz[b+2]
                if(z in near..far)check(xyz[a],xyz[a+1])
                if(z==end)continue
                for(side in 0..1){
                    val boundary=if(side==0)near else far
                    val u=(boundary-z)/(end-z)
                    if(u in 0f..1f)check(xyz[a]+u*(xyz[b]-xyz[a]),xyz[a+1]+u*(xyz[b+1]-xyz[a+1]))
                }
            }
            i+=3
        }
        return gap
    }
}
