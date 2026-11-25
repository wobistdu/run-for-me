package com.example.terrainrunner

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.LaunchActivity
import com.example.terrainrunner.platform.SpatialApplication
import com.pico.spatial.core.ecs.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RunnerVisibilityStudy {
    @Test fun onlyActiveRunnerAndOpenPreviewHaveVisibleGeometry()=runBlocking<Unit>{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run=withContext(Dispatchers.Main){(context.applicationContext as SpatialApplication).activeRun()}
        val state=run.ready.await()
        val field=ActiveRun::class.java.getDeclaredField("scene").apply{isAccessible=true}
        val view=withTimeout(45000){
            while(withContext(Dispatchers.Main){field.get(run)}==null)delay(100)
            field.get(run) as RidgeScene
        }
        fun entity(name:String)=RidgeScene::class.java.getDeclaredField(name).apply{isAccessible=true}.get(view) as Entity
        fun visibleSkins(root:Entity)=root.tree().count{it.enabled && it.components.get<ModelComponent>()!=null}
        val oldRunner=withContext(Dispatchers.Main){state.runner}
        val oldPaused=withContext(Dispatchers.Main){state.paused}
        val oldChoosing=withContext(Dispatchers.Main){state.choosingRunner}
        try{
            repeat(3){
                for(style in RunnerStyle.entries){
                    withContext(Dispatchers.Main){
                        state.openRunners();state.previewRunner(style)
                        view.animate(1f)
                        assertEquals(1,visibleSkins(entity("actor")))
                        assertEquals(1,visibleSkins(entity("turntable")))
                        state.closeRunners()
                        view.animate(1f)
                        assertEquals(0,visibleSkins(entity("turntable")))
                        state.openRunners();state.previewRunner(style);state.applyRunner()
                        view.animate(1f)
                        assertEquals(style,state.runner)
                        assertEquals(1,visibleSkins(entity("actor")))
                        assertEquals(0,visibleSkins(entity("turntable")))
                    }
                    delay(100)
                }
            }
        }finally{
            withContext(Dispatchers.Main){
                state.openRunners();state.previewRunner(oldRunner);state.applyRunner()
                state.paused=oldPaused
                if(oldChoosing)state.openRunners()
                view.animate(1f)
            }
        }
    }
}
