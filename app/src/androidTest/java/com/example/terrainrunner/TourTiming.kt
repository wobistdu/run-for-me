package com.example.terrainrunner

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue

/** Fixed-work CPU comparison, separate from compositor display timing. */
class TourTiming {
    @Test fun measureTour(){
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val s=RidgeSession(Network(assets),Course.load(assets))
        repeat(2){lap->
            s.replay(Passage.ALL)
            val times=mutableMapOf<String,MutableList<Long>>()
            while(!s.trial.done && s.trial.ticks<10000){
                val p=s.trial.position
                val zone=when {
                    s.course.onDeck(p)->"log"
                    p.mx<-.18f || p.mz<-.08f->"rocks"
                    else->"level"
                }
                val begin=System.nanoTime()
                s.step()
                times.getOrPut(zone){mutableListOf()}.add(System.nanoTime()-begin)
            }
            assertTrue(s.trial.done)
            if(lap==1)for((zone,values) in times){
                values.sort()
                fun ms(frac:Double)=values[((values.size-1)*frac).toInt()]/1e6
                Log.i("TourTiming","$zone n=${values.size} meanMs=${values.average()/1e6} p50=${ms(.5)} p95=${ms(.95)} p99=${ms(.99)}")
            }
        }
    }
}
