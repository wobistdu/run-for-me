package com.example.terrainrunner

import android.util.Log
import android.content.Intent
import com.example.terrainrunner.platform.LaunchActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.SpatialApplication
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PpsDailyRankingStudy {
    @Test fun realPpsDailyRankingAcceptsCurrentDistanceAndReadsTopTen()=runBlocking<Unit>{
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpatialApplication
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(3000)
        withContext(Dispatchers.Main){
            val service=app.dailyRanking
            assertTrue(service.configured)
            val expected=app.journal.snapshot(System.currentTimeMillis()).todayMetres.toLong()
            val rows=service.topTen(LocalDate.now(),interactive=false)
            if(expected>0){
                assertTrue("Real current distance was not returned by PPS",rows.any{it.metres>=expected})
                assertTrue("PPS returned only fallback names",rows.any{it.username!="跑者"})
            }
            assertTrue(rows.size<=10)
            assertTrue(rows.zipWithNext().all{(a,b)->a.metres>=b.metres})
            assertTrue(rows.all{it.username.isNotBlank() && it.rank>0 && it.metres>=0})
            Log.i("PpsDailyRankingStudy","PPS daily ranking succeeded, entries=${rows.size}, expectedMetres=$expected, rows=$rows")
        }
    }
}
