package com.example.terrainrunner

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.LaunchActivity
import com.example.terrainrunner.platform.SpatialApplication
import com.pico.spatial.core.ecs.BlendShapeControllerComponent
import com.pico.spatial.core.ecs.Entity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Confirm the chosen skin actually animates in a mounted Volume using the app clock. */
class StridePresentation {
    @Test fun mountedLowGateDrivesBothKneeShapes()=runBlocking<Unit>{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run=withContext(Dispatchers.Main){(context.applicationContext as SpatialApplication).activeRun()}
        val session=run.ready.await()
        val mounted=ActiveRun::class.java.getDeclaredField("scene").apply{isAccessible=true}
        val view=withTimeout(45000){
            var scene:RidgeScene?=null
            while(scene==null){delay(50);scene=withContext(Dispatchers.Main){mounted.get(run) as? RidgeScene}}
            requireNotNull(scene)
        }
        val previous=withContext(Dispatchers.Main){session.runner}
        try{
            val controller=withContext(Dispatchers.Main){
                session.openRunners();session.previewRunner(RunnerStyle.STRIDE);session.applyRunner()
                session.replay(Passage.DUCK)
                val models=RidgeScene::class.java.getDeclaredField("runners").apply{isAccessible=true}.get(view) as Map<*,*>
                val male=requireNotNull(models[RunnerStyle.STRIDE])
                val skin=male.javaClass.getDeclaredField("skin").apply{isAccessible=true}.get(male) as Entity
                requireNotNull(skin.components.get<BlendShapeControllerComponent>())
            }
            val peak=FloatArray(2);val low=FloatArray(2){1f}
            var split=0f
            withTimeout(30000){
                do{
                    delay(30)
                    val done=withContext(Dispatchers.Main){
                        val weights=controller.getBlendShapeWeights()
                        assertEquals(2,weights.size)
                        for(i in 0..1){peak[i]=maxOf(peak[i],weights[i]);low[i]=minOf(low[i],weights[i])}
                        split=maxOf(split,kotlin.math.abs(weights[0]-weights[1]))
                        session.trial.position.mx>.27f
                    }
                }while(!done)
            }
            Log.i("StridePresentation","low gate shape ranges ${low.toList()}..${peak.toList()} independent=$split")
            assertTrue(peak.all{it>.8f} && low.all{it<.2f} && split>.35f)
        }finally{
            withContext(Dispatchers.Main){
                session.openRunners();session.previewRunner(previous);session.applyRunner();session.replay(Passage.ALL)
            }
        }
    }
    @Test fun mountedMaleRunnerCrossesBridgeAndReachesEastTrail()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as SpatialApplication
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run=withContext(Dispatchers.Main){app.activeRun()}
        val session=run.ready.await()
        val mounted=ActiveRun::class.java.getDeclaredField("scene").apply{isAccessible=true}
        val activeModel=RidgeScene::class.java.getDeclaredField("active").apply{isAccessible=true}
        suspend fun scene()=withContext(Dispatchers.Main){mounted.get(run) as? RidgeScene}
        val view=withTimeout(45000){
            while(scene()==null)delay(100)
            requireNotNull(scene())
        }
        val previous=withContext(Dispatchers.Main){session.runner}
        try{
            withContext(Dispatchers.Main){
                session.openRunners()
                session.previewRunner(RunnerStyle.STRIDE)
                session.applyRunner()
            }
            var bridgeSamples=0
            var sawBridge=false
            var wentPastBridge=false
            var moved=false
            val start=withContext(Dispatchers.Main){view.submittedRoot}
            withTimeout(180000){
                while(!wentPastBridge){
                    delay(50)
                    withContext(Dispatchers.Main){
                        val t=session.trial
                        if(activeModel.get(view)==RunnerStyle.STRIDE && view.submittedRoot!=start)moved=true
                        if(session.course.onDeck(t.position)){
                            sawBridge=true
                            bridgeSamples++
                        }else if(sawBridge && t.position.mx>.22f && t.position.mz>.13f)wentPastBridge=true
                    }
                }
            }
            assertTrue(moved && bridgeSamples>3)
            Log.i("StridePresentation","mounted male moved=$moved bridgeSamples=$bridgeSamples reached east trail=$wentPastBridge")
        }finally{
            withContext(Dispatchers.Main){
                session.openRunners();session.previewRunner(previous);session.applyRunner()
            }
        }
    }
}
