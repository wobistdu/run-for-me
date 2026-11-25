package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class RunJournalTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val prefs=instrumentation.context.getSharedPreferences("journal-test",0)
    private val zone=ZoneId.of("America/New_York")
    private val a=Point(2f,2f)
    private val b=Point(3f,2f)
    private fun start(date:String)=LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun fresh():RunJournal{
        prefs.edit().clear().commit()
        return RunJournal(prefs)
    }

    @Test fun seventyThreeKilogramRunningEnergy(){
        assertEquals(0.0,RunTotals(0.0,0.0,0.0,0,0).kcal,0.0)
        assertEquals(73.0,RunTotals(1000.0,1000.0,0.0,1,1).kcal,1e-9)
        assertEquals(105.85,RunTotals(1000.0,1000.0,100.0,1,1).kcal,1e-9)
    }

    @Test fun slopeUsesCharacterScaleAndPersists(){
        val j=fresh()
        val course=Course.ridge(FloatArray(Course.SAMPLES*Course.SAMPLES){(it%Course.SAMPLES)*.002f})
        val now=start("2026-03-07")
        assertEquals(0,j.snapshot(now,zone).days)
        j.record(course,a,b,now)
        j.record(course,b,a,now)
        j.record(course,b,b,now)
        val result=j.snapshot(now,zone)
        val scale=1.65/Course.RUNNER_HEIGHT*1.5
        assertEquals(2*hypot(.04,.02)*scale,result.todayMetres,1e-5)
        assertEquals(.02*scale,result.ascentMetres,1e-5)
        assertEquals(result.todayMetres,result.totalMetres,0.0)
        assertTrue(result.kcal>0)
        j.save()
        val restored=RunJournal(prefs).snapshot(now,zone)
        assertEquals(result.todayMetres,restored.todayMetres,.00051)
        assertEquals(result.ascentMetres,restored.ascentMetres,.00051)
        assertEquals(result.kcal,restored.kcal,.0002)
    }

    @Test fun localMidnightStreakAndMissedDay(){
        val j=fresh()
        val course=Course.ridge(FloatArray(Course.SAMPLES*Course.SAMPLES))
        val sunday=start("2026-03-08")
        val monday=start("2026-03-09")
        j.snapshot(sunday-1,zone)
        j.record(course,a,b,sunday-1)
        val empty=j.snapshot(sunday,zone)
        assertEquals(0.0,empty.todayMetres,0.0)
        assertEquals(1,empty.streak)
        j.record(course,a,b,sunday)
        j.record(course,a,b,monday-1)
        j.record(course,a,b,monday)
        val three=j.snapshot(monday,zone)
        assertEquals(3,three.days)
        assertEquals(3,three.streak)
        assertEquals(three.todayMetres*4,three.totalMetres,1e-5)
        val wednesday=start("2026-03-11")
        assertEquals(0,j.snapshot(wednesday,zone).streak)
        j.record(course,b,a,wednesday)
        j.save()
        val resumed=RunJournal(prefs).snapshot(wednesday,zone)
        assertEquals(4,resumed.days)
        assertEquals(1,resumed.streak)
    }

    @Test fun fullTourAndPause(){
        val j=fresh()
        val assets=instrumentation.targetContext.assets
        val s=RidgeSession(Network(assets),Course.load(assets),j)
        do{s.step()}while(!s.trial.done && !s.paused && s.trial.ticks<10000)
        assertTrue(s.trial.done)
        val lap=j.snapshot()
        assertTrue(lap.totalMetres>100 && lap.ascentMetres>0)
        s.paused=true
        repeat(120){s.step()}
        assertEquals(lap,j.snapshot())
        s.paused=false
        repeat(60){s.step()}
        assertTrue(j.snapshot().totalMetres>lap.totalMetres)
        android.util.Log.i("RunJournalTest","oneTour metres=${lap.todayMetres} ascent=${lap.ascentMetres} kcal=${lap.kcal}")
    }
}
