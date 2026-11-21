package com.example.terrainrunner

import kotlin.math.*
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Point(val x: Float, val z: Float) {
    val mx get()=(x-Course.CENTER)*Course.CELL
    val mz get()=(z-Course.CENTER)*Course.CELL
    fun distance(b: Point) = hypot(x-b.x,z-b.z)
    fun toward(b: Point,t: Float) = Point(x+(b.x-x)*t,z+(b.z-z)*t)
}
enum class Tool { LOWER, RAISE }

/** All horizontal coordinates and elevations are in grid units. One unit is 40 mm. */
class Course private constructor(val heights: FloatArray,private val relief:FloatArray,val crossings:Crossings?) {
    companion object {
        const val WIDTH=.68f
        const val FLOOR_FILL=.999f
        const val RUNNER_HEIGHT=WIDTH*.98f/(25f*FLOOR_FILL)
        const val CENTER=8.5f
        const val SAMPLES=171
        fun local(x:Float,z:Float)=Point(x/CELL+CENTER,z/CELL+CENTER)
        const val W=18
        const val H=18
        const val CELL=.04f
        const val RADIUS=1.8f
        fun load(assets:AssetManager):Course {
            val bytes=assets.open("ridge/surface.f32").use{it.readBytes()}
            val buf=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return ridge(FloatArray(buf.remaining()).also{buf.get(it)},Crossings.load(assets))
        }
        fun ridge(surface:FloatArray,crossings:Crossings?=null):Course{
            require(surface.size==SAMPLES*SAMPLES && surface.all{it.isFinite()})
            return Course(FloatArray(W*H),surface,crossings)
        }
    }
    private val edited=heights.any{it!=0f}
    fun point(i:Int)=Point((i%W).toFloat(),(i/W).toFloat())
    fun inside(p:Point)=p.x in 1f..(W-2f) && p.z in 1f..(H-2f)
    fun onDeck(p:Point)=crossings?.onDeck(p)==true
    fun jump(p:Point)=max(((.026f-abs(p.mx-.083f))/.017f).coerceIn(0f,1f)*
        ((.045f-abs(p.mz-.18f))/.014f).coerceIn(0f,1f),crossings?.signal(p,false)?:0f)
    fun crouch(p:Point)=max(((.046f-abs(p.mx-.21f))/.024f).coerceIn(0f,1f)*
        ((.033f-abs(p.mz-.18f))/.012f).coerceIn(0f,1f),crossings?.signal(p,true)?:0f)
    fun editable(p:Point)=inside(p) && supports(p)
    fun supports(p:Point):Boolean {
        if(p.x !in 0f..17f || p.z !in 0f..17f)return false
        val portalPost=abs(p.mx-.21f)<.013f &&
            (abs(p.mz-.154f)<.008f || abs(p.mz-.206f)<.008f)
        if(portalPost || crossings?.atPost(p)==true || crossings?.atWall(p)==true)return false
        val floor=floorHeight(p)
        if(floor<(crossings?.floodLimit(p)?:-.013f))return onDeck(p)
        return true
    }
    internal fun vertexHeight(i:Int,j:Int)=relief[i+j*SAMPLES]+
        if(edited)CELL*displacement(Point(i*.1f,j*.1f)) else 0f
    // Interpolate on the same diagonal as the square ground mesh.
    private fun floorHeight(p:Point):Float {
        val x=(p.x*10).coerceIn(0f,170f);val z=(p.z*10).coerceIn(0f,170f)
        val i=x.toInt().coerceAtMost(169);val j=z.toInt().coerceAtMost(169)
        val u=x-i;val v=z-j
        fun vertex(dx:Int,dz:Int)=vertexHeight(i+dx,j+dz)
        val bl=vertex(0,0);val br=vertex(1,0);val tl=vertex(0,1);val tr=vertex(1,1)
        return if(v>=u)bl+(tl-bl)*v+(tr-tl)*u else bl+(br-bl)*u+(tr-br)*v
    }
    fun height(p:Point):Float {
        val floor=floorHeight(p)/CELL
        val fixtures=crossings?:return floor
        return if(fixtures.onDeck(p))max(floor,fixtures.deckHeight(this,p)) else floor
    }
    // Overhead rails use the ceiling gait channel. Only low obstacles enter
    // the network's ground profile, while the route root travels on the floor.
    fun networkHeight(p:Point,halfSpacing:Float):Float{
        val top=crossings?.wallTop(p,halfSpacing)?:return height(p)
        return max(height(p),top/CELL+displacement(p))
    }
    fun displacement(p:Point):Float {
        val x=floor(p.x).toInt().coerceIn(0,W-2); val z=floor(p.z).toInt().coerceIn(0,H-2)
        val u=(p.x-x).coerceIn(0f,1f); val v=(p.z-z).coerceIn(0f,1f)
        val a=heights[x+W*z]; val b=heights[x+1+W*z]; val c=heights[x+W*(z+1)]; val d=heights[x+1+W*(z+1)]
        return if(u+v<=1) a+(b-a)*u+(c-a)*v else d+(c-d)*(1-u)+(b-d)*(1-v)
    }
    /** Distance to the swept centreline determines depth, independent of event rate or dwell. */
    class Stroke(val original:Course,val tool:Tool,start:Point) {
        var draft=original
            private set
        private var last=start
        init {
            require(tool==Tool.LOWER || tool==Tool.RAISE)
            extend(start)
        }
        fun extend(end:Point){
            if(!original.editable(end))return
            val dx=end.x-last.x
            val dz=end.z-last.z
            val length2=dx*dx+dz*dz
            val heights=draft.heights.copyOf()
            var changed=false
            for(i in heights.indices){
                val q=original.point(i)
                if(!original.editable(q))continue
                val t=if(length2==0f) 0f else
                    (((q.x-last.x)*dx+(q.z-last.z)*dz)/length2).coerceIn(0f,1f)
                val d=q.distance(last.toward(end,t))/RADIUS
                if(d>=1f)continue
                val amount=.75f*(1-d)*(1-d)*(1+2*d)
                val value=if(tool==Tool.LOWER) min(heights[i],original.heights[i]-amount)
                    else max(heights[i],original.heights[i]+amount)
                val bounded=value.coerceIn(-.65f,1.8f)
                if(bounded!=heights[i]){heights[i]=bounded;changed=true}
            }
            last=end
            if(changed)draft=Course(heights,original.relief,original.crossings)
        }
    }

}
