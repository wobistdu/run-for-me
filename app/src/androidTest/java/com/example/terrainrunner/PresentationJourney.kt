package com.example.terrainrunner

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.LaunchActivity
import com.example.terrainrunner.platform.SpatialApplication
import java.io.FileInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the mounted scene and its real render/service clock, not a stepped simulation.
 * This verifies UI/game-state integration; it does not simulate headset hand tracking.
 */
class PresentationJourney {
    @Test fun mountedHuntReachesRenderedResultWithoutManualSimulation()=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as SpatialApplication
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val session=withContext(Dispatchers.Main){app.activeRun().ready.await()}
        withTimeout(45000){
            while(withContext(Dispatchers.Main){session.trial.ticks}<15)delay(100)
        }
        suspend fun capture(name:String){
            delay(500) // Let Compose and the compositor present the changed state.
            instrumentation.uiAutomation.executeShellCommand("screencap -p").use{descriptor->
                FileInputStream(descriptor.fileDescriptor).use{input->
                    context.filesDir.resolve("polish-$name.png").outputStream().use{output->input.copyTo(output)}
                }
            }
        }
        capture("home")
        val before=withContext(Dispatchers.Main){
            val count=session.totalStars
            assertTrue(session.startExpedition())
            count
        }
        capture("hunt")
        withContext(Dispatchers.Main){
            listOf(0,1,2).forEach(session::queueStar)
            assertEquals(3,session.expedition?.flags)
        }
        capture("queued")
        withTimeout(180000){
            while(withContext(Dispatchers.Main){session.questActive}){
                assertFalse("mounted route stalled",withContext(Dispatchers.Main){session.paused})
                delay(150)
            }
        }
        withContext(Dispatchers.Main){
            assertEquals(3,session.expedition?.result?.medal)
            assertEquals(before+3,session.totalStars)
            assertTrue(session.reachedFirstFlag)
            assertFalse(session.paused)
        }
        capture("result")
    }
}
