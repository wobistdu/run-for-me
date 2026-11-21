package com.example.terrainrunner

import android.content.res.AssetManager
import org.json.JSONObject
import kotlin.math.*

private fun JSONObject.f(key:String)=getDouble(key).toFloat()
data class Bar(val z:Float,val bottom:Float,val top:Float){
    val overhead get()=bottom>=.65f*Course.RUNNER_HEIGHT
}

internal data class Wall(val left:Float,val right:Float,val z:Float,val depth:Float,val height:Float,val base:Float)

class Crossings private constructor(spec:JSONObject){
    private val deck=spec.getJSONObject("deck")
    val x0=deck.f("x0");val x1=deck.f("x1")
    val centerZ=deck.f("z");val halfWidth=deck.f("radius")
    internal val upperSegments=deck.getInt("halfSegments")
    private val angles=DoubleArray(upperSegments+1){-PI/2+PI*it/upperSegments}
    private val crossZ=FloatArray(angles.size){centerZ+halfWidth*sin(angles[it]).toFloat()}
    private val crossY=FloatArray(angles.size){halfWidth*(cos(angles[it]).toFloat()-1f)}
    val deckTop=deck.f("top");internal val spans=deck.getInt("spans")
    val laneX=spec.f("laneX");val laneY=spec.f("laneY")
    val halfSpan=spec.f("halfSpan");val postRadius=spec.f("postHalfWidth")
    val barHalfDepth=spec.f("barHalfDepth")
    val bars=spec.getJSONArray("bars").let{items->List(items.length()){i->
        val item=items.getJSONObject(i);Bar(item.f("z"),item.f("bottom"),item.f("top"))
    }}
    internal val walls=spec.getJSONArray("walls").let{items->List(items.length()){i->
        val w=items.getJSONObject(i);Wall(w.f("x0"),w.f("x1"),w.f("z"),w.f("halfDepth"),w.f("height"),w.f("base"))
    }}
    fun atWall(p:Point)=walls.any{p.mx in it.left-.003f..it.right+.003f && abs(p.mz-it.z)<=it.depth+.003f}
    companion object{
        fun load(assets:AssetManager)=assets.open("ridge/crossings.json").bufferedReader().use{Crossings(JSONObject(it.readText()))}
    }
    fun onDeck(p:Point)=p.mx in x0..x1 && abs(p.mz-centerZ)<=halfWidth
    internal fun sectionZ(j:Int)=crossZ[j]
    internal fun vertexHeight(c:Course,i:Int,j:Int):Float{
        val x=x0+(x1-x0)*i/spans
        return deckTop+crossY[j]+Course.CELL*c.displacement(Course.local(x,crossZ[j]))
    }
    /** Same top triangles as the collidable log, including the brush delta at each vertex. */
    fun deckHeight(c:Course,p:Point):Float{
        val step=(x1-x0)/spans
        val u=((p.mx-x0)/step).coerceIn(0f,spans.toFloat())
        val i=u.toInt().coerceAtMost(spans-1)
        val side=(1..upperSegments).firstOrNull{p.mz<=crossZ[it]}?:upperSegments
        val z0=crossZ[side-1];val z1=crossZ[side]
        val fx=u-i;val fz=((p.mz-z0)/(z1-z0)).coerceIn(0f,1f)
        fun delta(k:Int,z:Float)=c.displacement(Course.local(x0+step*k,z))
        val a=delta(i,z0);val d=delta(i+1,z1)
        val offset=if(fz>=fx)a*(1-fz)+delta(i,z1)*(fz-fx)+d*fx
            else a*(1-fx)+delta(i+1,z0)*(fx-fz)+d*fz
        return (deckTop+crossY[side-1]*(1-fz)+crossY[side]*fz)/Course.CELL+offset
    }
    fun atPost(p:Point)=abs(abs(p.mx-laneX)-halfSpan)<postRadius+.001f &&
        bars.any{abs(p.mz-it.z)<postRadius+.001f}
    private val stream=spec.getJSONObject("stream")
    private val streamX=stream.f("x0")..stream.f("x1")
    private val streamZ=stream.f("z0")..stream.f("z1")
    private val streamLimit=-.013f*stream.f("depthScale")
    fun floodLimit(p:Point)=if(p.mx in streamX && p.mz in streamZ)streamLimit else -.013f
    fun pace(p:Point)=1f-.35f*signal(p,true)
    fun signal(p:Point,overhead:Boolean):Float{
        val lateral=((halfSpan-abs(p.mx-laneX))/.005f).coerceIn(0f,1f)
        if(lateral<=0f)return 0f
        var weight=0f
        for(bar in bars){
            if(bar.overhead!=overhead)continue
            val radius=if(overhead).042f else 175f*Network.MODEL_TO_METRES
            val falloff=if(overhead).013f else 100f*Network.MODEL_TO_METRES
            val envelope=((radius-abs(p.mz-bar.z))/falloff).coerceIn(0f,1f)
            weight=max(weight,envelope)
        }
        return weight*lateral
    }
    fun wallTop(p:Point,halfSpacing:Float):Float?{
        val wall=walls.firstOrNull{p.mx in it.left..it.right && abs(p.mz-it.z)<it.depth+halfSpacing}?:return null
        val d=(abs(p.mz-wall.z)-wall.depth).coerceAtLeast(0f)
        return wall.base+wall.height*(1f-d/halfSpacing.coerceAtLeast(.00001f))
    }
}
