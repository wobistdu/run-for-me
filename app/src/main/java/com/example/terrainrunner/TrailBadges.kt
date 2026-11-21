package com.example.terrainrunner

import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*

internal enum class TrailBadge(val title:String,val days:Int){
    DAWN("晨光",1), STREAM("溪蓝",3), DUSK("暮紫",7);
    fun isEarned(earnedDays:Int)=earnedDays>=days
    val modelUri get()="asset://badges/${name.lowercase()}.glb"
    companion object{
        const val SLOT_DP=77
        const val ROW_DP=58
    }
}

/** Each SGS model moves from its board slot into the detail view without loading a second mesh. */
internal class TrailBadges(board:Entity,private val metresPerDp:Float,private val medals:Map<TrailBadge,Entity>){
    private data class Finish(val model:ModelComponent,val original:PhysicallyBasedMaterial,val silver:PhysicallyBasedMaterial)
    private val finishes=medals.mapValues{(_,root)->
        val model=root.tree().mapNotNull{it.components.get<ModelComponent>()}.single()
        val original=model.materials.single() as PhysicallyBasedMaterial
        // The first switch to silver detaches the colored material until this badge is earned.
        original.toGlobal()
        val silver=PhysicallyBasedMaterial.create().apply{
            toGlobal()
            setBaseColor(Color4(.80f,.82f,.85f,1f))
            setMetallic(.52f);setRoughness(.42f)
            original.getNormalTexture()?.let{setNormalTexture(it)}
        }
        board.addChild(root)
        Finish(model,original,silver)
    }
    val previewHit=Entity().also{
        val size=Vector3(140f,140f,118f)*metresPerDp
        it.components.set(CollisionComponent(listOf(ShapeResource.createBox(size)),PhysicsMaterialResource()))
        it.components.set(InteractableComponent())
        it.enabled=false;board.addChild(it)
    }
    private var shown:TrailBadge?=null
    private var yaw=Float.NaN
    private var days=-1
    private var width=0f
    private var height=0f

    fun update(earnedDays:Int,selected:TrailBadge?,angle:Float){
        if(days!=earnedDays){
            for((badge,finish) in finishes){
                finish.model.materials[0]=if(badge.isEarned(earnedDays))finish.original else finish.silver
            }
            days=earnedDays
        }
        if(shown!=selected){
            shown=selected;yaw=Float.NaN
            previewHit.enabled=selected!=null
            layout()
            android.util.Log.i("TrailBadges","gallery=${selected?.name}")
        }
        if(selected!=null && yaw!=angle){
            yaw=angle
            medals.getValue(selected).tr.eulerAngles=EulerAngles(-6f,angle,0f)
        }
    }
    fun place(width:Float,height:Float){
        if(this.width==width && this.height==height)return
        this.width=width;this.height=height
        layout()
    }
    private fun layout(){
        val preview=Vector3(-width*.5f+111f*metresPerDp,0f,58f*metresPerDp)
        previewHit.tr.position=preview
        for((badge,root) in medals){
            root.enabled=shown==null || badge==shown
            if(badge==shown){
                root.tr.position=preview
                root.tr.scaleVector=Vector3(56f*metresPerDp)
            }else if(shown==null){
                val slot=TrailBadge.SLOT_DP*(medals.size-badge.ordinal-.5f)
                root.tr.position=Vector3(width*.5f-(22f+slot)*metresPerDp,-height*.5f+52f*metresPerDp,5f*metresPerDp)
                root.tr.scaleVector=Vector3(19f*metresPerDp)
                root.tr.eulerAngles=EulerAngles(0f,0f,0f)
            }
        }
    }
    fun close(){finishes.values.forEach{it.silver.close();it.original.close()}}
}
