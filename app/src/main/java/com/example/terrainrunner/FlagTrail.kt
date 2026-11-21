package com.example.terrainrunner

import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.*

/** A strip with small arrowheads in its silhouette. Both edges sample the committed surface. */
internal fun flagTrail(path:List<Point>,course:Course):MeshResource{
    val samples=mutableListOf(path.first())
    for(i in 1 until path.size){
        val start=path[i-1];val end=path[i]
        val count=ceil(start.distance(end)/.027f).toInt()
        for(j in 1..count)samples+=start.toward(end,j.toFloat()/count)
    }
    val length=FloatArray(samples.size)
    for(i in 1..samples.lastIndex)length[i]=length[i-1]+samples[i-1].distance(samples[i])*Course.CELL
    val period=length.last()/max(1,(length.last()/.043f).roundToInt())
    val coral=Color4(.98f,.18f,.065f,1f)
    val cream=Color4(1f,.84f,.53f,1f)
    val vertices=ArrayList<Vector3>(samples.size*2)
    val colors=ArrayList<Color4>(samples.size*2)
    val indices=ArrayList<Int>((samples.size-1)*6)
    for(i in samples.indices){
        val a=samples[max(0,i-1)];val b=samples[min(i+1,samples.lastIndex)]
        val span=a.distance(b).coerceAtLeast(.00001f)
        val phase=length[i]%period-period*.45f
        val arrowLength=min(.0048f,period*.35f)
        val arrow=phase>=0 && phase<arrowLength
        val halfWidth=if(arrow).00058f+.00125f*(1f-phase/arrowLength) else .00058f
        for(sign in listOf(-1f,1f)){
            val p=Course.local(samples[i].mx+sign*(b.z-a.z)/span*halfWidth,
                samples[i].mz-sign*(b.x-a.x)/span*halfWidth)
            vertices+=Vector3(p.mx,course.height(p)*Course.CELL+.00048f,p.mz)
            colors+=if(arrow)cream else coral
        }
        if(i>0){
            val k=i*2
            indices.addAll(listOf(k-2,k,k-1,k-1,k,k+1))
        }
    }
    return MeshResource.createWithMeshModel(MeshModel(vertices,indices,colors=colors),name="FlagTrail").apply{toGlobal()}
}
