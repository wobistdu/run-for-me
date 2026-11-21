package com.example.terrainrunner

import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import kotlin.math.*

private val Entity.transform get()=requireNotNull(components.get<TransformComponent>())

/** Lightweight shared meshes. All motion stays in the existing render clock. */
internal class ExpeditionScenery(private val island:Entity,private val state:RidgeSession){
    private val material=UnlitMaterial.create().apply{toGlobal()}
    private val starMesh=starMesh()
    private val ringMesh=ringMesh()
    private val particleMesh=MeshResource.createSphere(.5f).apply{toGlobal()}
    private val colors=listOf(Color4(1f,.73f,.21f,1f),Color4(.20f,.89f,1f,1f),Color4(.77f,.51f,1f,1f))
    private val haloMaterial=UnlitMaterial.create().apply{toGlobal();setBaseColor(colors[0])}
    private val queuedMaterial=UnlitMaterial.create().apply{toGlobal();setBaseColor(Color4(.43f,.90f,.82f,1f))}
    private val halo=ModelEntity(ringMesh,haloMaterial).also{
        it.enabled=false
        it.transform.scaleVector=Vector3(.7f)
        island.addChild(it)
    }
    private val stars=List(3){ModelEntity(starMesh,material).also{
        it.components.set(CollisionComponent(listOf(ShapeResource.createSphere(.019f)),PhysicsMaterialResource()))
        it.components.set(InteractableComponent())
        island.addChild(it);it.enabled=false
    }}
    fun starIndex(target:Entity?)=stars.indexOfFirst{it.id==target?.id}
    var focusedStar=-1
    private val rings=List(3){ModelEntity(ringMesh,haloMaterial).also{island.addChild(it);it.enabled=false}}
    private val confirmations=List(3){ModelEntity(ringMesh,queuedMaterial).also{island.addChild(it);it.enabled=false}}
    private val sparks=List(16){ModelEntity(particleMesh,haloMaterial).also{island.addChild(it);it.enabled=false}}
    private var run:ExpeditionRun?=null
    private var collected=emptySet<Int>()
    private var burstAt=0L
    private var burstPoint:Point?=null
    private var ribbon=-1
    private var haloShown=false
    private val starsShown=BooleanArray(3)
    private val confirmationsShown=BooleanArray(3)
    private var sparksShown=false
    fun update(){
        val now=java.lang.System.nanoTime()
        val seconds=(now%60_000_000_000L)/1e9f
        if(ribbon!=state.ribbon){ribbon=state.ribbon;haloMaterial.setBaseColor(colors[ribbon])}
        val hunting=state.questActive
        if(haloShown!=hunting){halo.enabled=hunting;haloShown=hunting}
        if(hunting){
            val p=state.trial.position
            halo.transform.position=Vector3(p.mx,state.course.height(p)*Course.CELL+.0008f,p.mz)
        }
        val current=state.expedition
        if(current!==run){run=current;collected=emptySet();burstPoint=null;focusedStar=-1}
        if(current!=null && current.collected!=collected){
            val added=current.collected-collected
            if(added.isNotEmpty()){burstPoint=current.plan.stars[added.last()].point;burstAt=now}
            collected=current.collected.toSet()
        }
        val firstHint=current!=null && !state.hasQuestFlags
        for(i in 0..2){
            val point=current?.plan?.stars?.get(i)?.point
            val visible=point!=null && i !in collected
            val queued=point!=null && state.legs.any{it.flag==point}
            if(starsShown[i]!=visible){
                starsShown[i]=visible;stars[i].enabled=visible;rings[i].enabled=visible
            }
            val confirmed=visible && queued
            if(confirmationsShown[i]!=confirmed){confirmationsShown[i]=confirmed;confirmations[i].enabled=confirmed}
            if(visible){
                val y=state.course.height(point)*Course.CELL
                val selected=i==focusedStar
                stars[i].transform.position=Vector3(point.mx,y+.027f+.002f*sin(seconds*2.5f+i),point.mz)
                stars[i].transform.eulerAngles=EulerAngles(0f,seconds*(if(queued)16 else 40)+i*45f,0f)
                stars[i].transform.scaleVector=Vector3(if(selected)1.22f else if(queued).88f else 1f)
                rings[i].transform.position=Vector3(point.mx,y+.001f,point.mz)
                // A single breathing landing ring teaches the first pinch without a modal.
                val pulse=if(firstHint && i==0) .24f else .07f
                rings[i].transform.scaleVector=Vector3(if(selected)1.3f else 1f+pulse*(1f+sin(seconds*3+i)))
                if(queued){
                    confirmations[i].transform.position=Vector3(point.mx,y+.0013f,point.mz)
                    confirmations[i].transform.scaleVector=Vector3(1.22f)
                }
            }
        }
        val burst=burstPoint
        val age=(now-burstAt)/1e9f
        val bursting=burst!=null && age<1.1f
        if(sparksShown!=bursting){sparksShown=bursting;sparks.forEach{it.enabled=bursting}}
        if(!bursting)return
        for(i in sparks.indices){
            val e=sparks[i]
            val angle=i*2*PI/sparks.size
            val radius=.006f+age*.035f
            e.transform.position=Vector3(burst.mx+cos(angle).toFloat()*radius,
                state.course.height(burst)*Course.CELL+.024f+sin(age*PI).toFloat()*.022f,
                burst.mz+sin(angle).toFloat()*radius)
            e.transform.scaleVector=Vector3(.003f*(1-age/1.1f).coerceAtLeast(0f))
        }
    }
    fun close(){starMesh.close();ringMesh.close();particleMesh.close();material.close();haloMaterial.close();queuedMaterial.close()}
}

private fun starMesh():MeshResource{
    val points=mutableListOf(Vector3(0f,0f,.003f),Vector3(0f,0f,-.003f))
    repeat(10){i->
        val a=PI/2+i*PI/5
        val radius=if(i%2==0).011f else .005f
        points+=Vector3(cos(a).toFloat()*radius,sin(a).toFloat()*radius,0f)
    }
    val indices=mutableListOf<Int>()
    repeat(10){i->val a=i+2;val b=(i+1)%10+2;indices.addAll(listOf(0,a,b,1,b,a))}
    val colors=points.indices.map{if(it==0)Color4(1f,.96f,.65f,1f) else Color4(1f,.66f,.13f,1f)}
    return MeshResource.createWithMeshModel(MeshModel(points,indices,colors=colors),name="TrailStar").apply{toGlobal()}
}

private fun ringMesh():MeshResource{
    val points=mutableListOf<Vector3>();val indices=mutableListOf<Int>()
    repeat(40){i->
        val a=2*PI*i/40
        for(radius in listOf(.013f,.014f))points+=Vector3(cos(a).toFloat()*radius,0f,sin(a).toFloat()*radius)
    }
    repeat(40){i->
        val a=2*i;val b=2*((i+1)%40)
        indices.addAll(listOf(a,b,a+1,a+1,b,b+1,a+1,b,a,b+1,b,a+1))
    }
    return MeshResource.createWithMeshModel(MeshModel(points,indices),name="TrailHalo").apply{toGlobal()}
}
