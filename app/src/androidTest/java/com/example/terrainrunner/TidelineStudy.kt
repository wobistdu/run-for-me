package com.example.terrainrunner

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TidelineStudy {
    private val assets get()=InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test fun originalLapAndBridge()=verifyLap(RunnerStyle.ORIGINAL)
    @Test fun strideLapAndBridge()=verifyLap(RunnerStyle.STRIDE)

    @Test fun strideKneesKeepTheirShapeThroughLowGate(){
        val session=RidgeSession(Network(assets),Course.load(assets),initialRunner=RunnerStyle.STRIDE)
        val mesh=RailClearance(assets,RunnerStyle.STRIDE)
        session.replay(Passage.DUCK)
        val t=session.trial
        val samples=mutableListOf<Float>()
        while(!t.done && !session.paused && t.ticks<2500){
            session.step()
            if(t.ticks==140){
                val depths=mesh.kneeDepthRatios(t)
                Log.i("TidelineStudy","low gate frame 140 paired knee depths=$depths")
                assertTrue("pinched or inflated knee: $depths",depths.all{it in .90f..1.14f})
            }
            if(t.ticks%6==0)samples.addAll(mesh.kneeEdgeRatios(t).asList())
        }
        assertTrue(t.done && samples.isNotEmpty())
        samples.sort()
        val p01=samples[samples.size/100]
        val p99=samples[samples.size*99/100]
        Log.i("TidelineStudy","STRIDE low gate ticks=${t.ticks} knee edge ratios p01=$p01 p99=$p99")
        assertTrue("knee folds inward: $p01",p01>.54f)
        assertTrue("knee stretches: $p99",p99<1.34f)
    }

    private fun verifyLap(style:RunnerStyle){
        val mesh=RailClearance(assets,style)
        val course=Course.load(assets)
        val session=RidgeSession(Network(assets),course,initialRunner=style)
        val t=session.trial
        var beamSpan=0.0;var beamFrames=0
        var flatSpan=0.0;var flatFrames=0
        var worstSole=0f
        do {
            session.step()
            val raw=requireNotNull(t.pose.output)
            val span=abs(raw[32+3*20]-raw[32+3*27])
            if(course.onDeck(t.position) && t.position.mx in -.143f..-.057f){
                beamSpan+=span;beamFrames++
                if(beamFrames%15==0)worstSole=minOf(worstSole,mesh.shoeDeckGap(t))
            }else if(t.position.mx>.25f && t.position.mz<0f){
                flatSpan+=span;flatFrames++
            }
        }while(!t.done && !session.paused && t.ticks<15000)
        Log.i("TidelineStudy","style=$style lapTicks=${t.ticks} beamFrames=$beamFrames flatFrames=$flatFrames beamWristSpan=${beamSpan/beamFrames} flatWristSpan=${flatSpan/flatFrames} worstSole=$worstSole")
        assertTrue(t.done)
        assertTrue(beamFrames>30 && flatFrames>30 && beamSpan/beamFrames>flatSpan/flatFrames*1.3)
        assertTrue("bridge sole $worstSole",worstSole>=-.00005f)
        for(wrist in listOf(20,27))assertTrue(mesh.wristVertices(wrist)>0)
        val point=t.position;val phase=t.pose.phase
        session.openRunners();session.previewRunner(RunnerStyle.ORIGINAL);session.closeRunners()
        assertEquals(style,session.runner)
        session.openRunners();session.previewRunner(RunnerStyle.ORIGINAL);session.applyRunner()
        assertSame(t,session.trial);assertEquals(point,t.position);assertEquals(phase,t.pose.phase,0f)
        assertEquals(RunnerStyle.ORIGINAL,session.runner)
    }

    @Test fun allRunnersHaveStaticPreviewAndWristBind()=runBlocking{
        assertEquals(RunnerStyle.ORIGINAL,RunnerStyle.saved(null))
        assertEquals(RunnerStyle.ORIGINAL,RunnerStyle.saved("TIDELINE"))
        assertEquals(listOf("林溪","陆野"),RunnerStyle.entries.map{it.title})
        for(style in RunnerStyle.entries){
            assertEquals(style,RunnerStyle.saved(style.name))
            val body=Entity.loadSuspend(style.modelUri)
            withContext(Dispatchers.Main){
                try{
                    val mesh=body.findSkinnedMeshEntity().single()
                    val model=requireNotNull(mesh.components.get<ModelComponent>())
                    assertTrue(model.materials.single() is UnlitMaterial)
                    assertTrue(model.mesh.getTriangleIndices().size/3<=50000)
                    val pose=SkeletonPoseComponent()
                    mesh.components.set(pose)
                    val names=pose.getJointNames();val transforms=pose.getJointTransforms()
                    assertEquals(31,names.size)
                    for(name in listOf("J19","J20","J26","J27"))
                        assertTrue(transforms[names.indexOf(name)].position.length()>Network.BIND_UNIT*10)
                    val preview=requireNotNull(body.clone(Entity.CloneOptions(recursive=true,shouldShareMaterialInstance=true)))
                    try{
                        val copy=preview.findSkinnedMeshEntity().single()
                        val neutral=SkeletonPoseComponent()
                        copy.components.set(neutral)
                        assertTrue(neutral.setJointTransforms(transforms));copy.components.set(InteractableComponent())
                    }finally{preview.destroy(true)}
                }finally{body.destroy(true)}
            }
        }
    }
}
