package com.example.terrainrunner

import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared mesh for all route flags. The origin is the point where the stake meets the terrain. */
internal fun waypointFlagMesh():MeshResource{
    val vertices=mutableListOf<Vector3>()
    val colors=mutableListOf<Color4>()
    val indices=mutableListOf<Int>()
    fun vertex(x:Float,y:Float,z:Float,color:Color4):Int{
        vertices+=Vector3(x,y,z)
        colors+=color
        return vertices.lastIndex
    }
    fun triangle(a:Int,b:Int,c:Int){indices.add(a);indices.add(b);indices.add(c)}
    val pole=Color4(.30f,.20f,.12f,1f)
    val cap=Color4(.91f,.76f,.52f,1f)
    val sides=6
    for(i in 0 until sides){
        val angle=2*PI*i/sides
        val x=.00062f*cos(angle).toFloat()
        val z=.00062f*sin(angle).toFloat()
        vertex(x,-.00055f,z,pole)
        vertex(x,.0171f,z,cap)
    }
    for(i in 0 until sides){
        val a=i*2;val b=(i+1)%sides*2
        triangle(a,a+1,b+1);triangle(a,b+1,b)
        if(i in 1 until sides-1)triangle(1,b+1,a+1)
    }

    val scarlet=Color4(.91f,.035f,.018f,1f)
    val sunlit=Color4(1f,.105f,.052f,1f)
    val fold=Color4(.62f,.018f,.019f,1f)
    // Give the pennant a fold in depth so it reads obliquely from behind and above.
    val top=vertex(.00045f,.0166f,0f,scarlet)
    val foot=vertex(.00045f,.0087f,0f,scarlet)
    val crest=vertex(.0041f,.0163f,.0021f,sunlit)
    val hem=vertex(.0041f,.0105f,.0021f,sunlit)
    val bend=vertex(.0076f,.0148f,.00045f,fold)
    val edge=vertex(.0076f,.0118f,.00045f,fold)
    val tip=vertex(.0121f,.0131f,.0017f,sunlit)
    fun fabric(a:Int,b:Int,c:Int){triangle(a,b,c);triangle(c,b,a)}
    fabric(top,foot,crest);fabric(crest,foot,hem)
    fabric(crest,hem,bend);fabric(bend,hem,edge);fabric(bend,edge,tip)
    return MeshResource.createWithMeshModel(MeshModel(vertices,indices,colors=colors),name="RouteFlag").apply{toGlobal()}
}
