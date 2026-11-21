package com.example.terrainrunner

import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*

/** Keeps the imported textured mesh alive across replacements and restores. */
internal class SculptedGround(private val ground:Entity){
    private val model=requireNotNull(ground.components.get<ModelComponent>())
    private val base=model.mesh.apply{toGlobal()}
    val vertices=base.getPositions().asList().chunked(3){Vector3(it[0],it[1],it[2])}
    val triangleCount=base.getTriangleIndices().size/3
    private val points=vertices.map{Course.local(it.x,it.z)}
    private val surface=EditableSurface(base,"SculptedRidge")
    private var deformed=false
    private var collision:ShapeResource?=null
    private val physicsMaterial=PhysicsMaterialResource().apply{toGlobal()}
    private var painted:Course?=null
    private var solid:Course?=null
    fun paint(c:Course,commit:Boolean){
        // Apply brush deltas to the actual textured SGS vertices and keep original UVs.
        if(painted!==c){
            val next=c.heights.any{it!=0f}
            if(next){
                terrainEditStage("positions"){
                    for(i in vertices.indices)surface.positions[i*3+1]=
                        vertices[i].y+Course.CELL*c.displacement(points[i])
                }
                terrainEditStage("visual"){surface.upload()}
            }
            if(deformed!=next)model.mesh=if(next)surface.mesh else base
            deformed=next
            painted=c
        }
        if(commit && solid!==c){
            val shape=terrainEditStage("collision-cook"){ShapeResource.createStaticMesh(if(deformed)surface.mesh else base).apply{toGlobal()}}
            terrainEditStage("collision-attach"){ground.components.set(CollisionComponent(listOf(shape),physicsMaterial))}
            collision?.close();collision=shape
            solid=c
        }
    }
    fun close(){collision?.close();physicsMaterial.close();surface.close();base.close()}
}
