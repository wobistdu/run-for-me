package com.example.terrainrunner

import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.*
import kotlin.random.Random

/** Sparse grass at the bank and outcrops. One visual mesh; only the land receives input. */
internal class GrassTufts(course:Course) {
    private val roots=mutableListOf<Point>()
    private val offsets=mutableListOf<Vector3>()
    private val colors=mutableListOf<Color4>()
    private val indices=mutableListOf<Int>()
    private val material=UnlitMaterial.create().apply{toGlobal()}
    private val surface:EditableSurface
    val entity:ModelEntity
    val triangleCount get()=indices.size/3

    init {
        val random=Random(713)
        val patches=listOf(-.178f to .13f,-.146f to .092f,-.060f to .101f,
            -.045f to .229f,-.127f to .281f,-.232f to .297f,-.29f to .222f,
            .195f to .123f,.264f to .225f)
        for((cx,cz) in patches)repeat(7){
            val x=cx+(random.nextFloat()-.5f)*.034f
            val z=cz+(random.nextFloat()-.5f)*.025f
            val point=Course.local(x,z)
            if(!course.supports(point) || course.height(point)*Course.CELL<-.009f)return@repeat
            val height=.0025f+.0048f*random.nextFloat()
            for(blade in 0 until 12){
                val angle=random.nextFloat()*6.28318f
                val dx=cos(angle);val dz=sin(angle)
                val h=height*(.55f+.65f*random.nextFloat())
                val width=.00022f+.00022f*random.nextFloat()
                val base=.0020f*random.nextFloat()
                val root=Course.local(x+dx*base,z+dz*base)
                val lean=h*(.22f+.58f*random.nextFloat())
                val first=offsets.size
                val straw=random.nextFloat()
                val olive=Color4(.12f+.14f*straw,.15f+.10f*straw,.051f+.044f*straw,1f)
                val tip=Color4(.23f+.16f*straw,.25f+.13f*straw,.085f+.072f*straw,1f)
                for(side in listOf(-1f,1f)){
                    offsets+=Vector3(-dz*width*side,-.0004f,dx*width*side)
                    colors+=Color4(.059f,.076f,.030f,1f)
                }
                for(side in listOf(-1f,1f)){
                    offsets+=Vector3(dx*lean*.34f-dz*width*.7f*side,h*.62f,dz*lean*.34f+dx*width*.7f*side)
                    colors+=olive
                }
                offsets+=Vector3(dx*lean,h,dz*lean);colors+=tip
                repeat(5){roots.add(root)}
                for(i in listOf(0,1,2,1,3,2,2,3,4,2,1,0,2,3,1,4,3,2))indices.add(first+i)
            }
        }
        surface=build(course).use{EditableSurface(it,"BankGrasses")}
        entity=ModelEntity(surface.mesh,material)
    }
    private fun build(c:Course):MeshResource {
        val positions=offsets.mapIndexed { i,offset ->
            val p=roots[i]
            Vector3(p.mx,c.height(p)*Course.CELL,p.mz)+offset
        }
        return MeshResource.createWithMeshModel(MeshModel(positions,indices,colors=colors),name="BankGrasses").apply{toGlobal()}
    }
    fun followGround(c:Course){
        var y=0f
        for(i in offsets.indices){
            // Each blade has five vertices at the same ground sample.
            if(i%5==0)y=c.height(roots[i])*Course.CELL
            surface.positions[3*i+1]=y+offsets[i].y
        }
        surface.upload()
    }
    fun close(){surface.close();material.close()}
}
