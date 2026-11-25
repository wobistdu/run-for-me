package com.example.terrainrunner

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.LaunchActivity
import com.example.terrainrunner.platform.SpatialApplication
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DailyRankingPresentation {
    @Test fun rankingOpensWhileRunnerKeepsMovingAndReturns()=runBlocking<Unit>{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run=withContext(Dispatchers.Main){(context.applicationContext as SpatialApplication).activeRun()}
        val state=run.ready.await()
        val field=ActiveRun::class.java.getDeclaredField("scene").apply{isAccessible=true}
        withTimeout(45000){
            while(withContext(Dispatchers.Main){field.get(run)}==null)delay(100)
        }
        val previous=withContext(Dispatchers.Main){state.showLeaderboard}
        try{
            val start=withContext(Dispatchers.Main){
                state.selectedBadge=null
                state.showLeaderboard=true
                state.trial.ticks
            }
            delay(2000)
            withContext(Dispatchers.Main){
                assertTrue(state.showLeaderboard)
                assertTrue("Opening rankings paused the runner",state.trial.ticks>start)
                state.showLeaderboard=false
            }
            delay(250)
            withContext(Dispatchers.Main){assertFalse(state.showLeaderboard)}
        }finally{withContext(Dispatchers.Main){state.showLeaderboard=previous}}
    }
}
