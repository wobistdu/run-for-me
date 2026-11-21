package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Calendar transitions and fresh hunt layouts exercise the shipped terrain and motion driver. */
class DailyLifecycleJourney {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val assets=instrumentation.targetContext.assets

    @Test fun dailyEntryRolloverAndFreePlayChoice(){
        val s=RidgeSession(Network(assets),Course.load(assets))
        val today=LocalDate.of(2026,11,20)
        assertNull(s.expedition)
        s.beginTestHunt(today)
        val first=requireNotNull(s.expedition)
        repeat(30){s.step()}
        assertEquals(0.0,first.metres,0.0)
        s.queueStar(0)
        repeat(30){s.step()}
        assertTrue(first.metres>0)
        s.leaveExpedition()
        val trial=s.trial
        s.beginTestHunt(today.plusDays(1))
        val tomorrow=requireNotNull(s.expedition)
        assertNotEquals(first.plan.stars,tomorrow.plan.stars)
        assertSame(trial,s.trial)
        assertFalse(s.paused);assertEquals(0,tomorrow.flags)
    }

    @Test fun randomRoutesStayReachableAndInsetAcrossRetriesAndCalendarBoundaries(){
        val s=RidgeSession(Network(assets),Course.load(assets))
        val days=List(5){LocalDate.of(2026,11,20)}+
            listOf(LocalDate.of(2026,12,31),LocalDate.of(2027,1,1),LocalDate.of(2028,2,29))
        var previous=emptyList<Point>()
        val layouts=mutableSetOf<List<TrailStar>>()
        for(day in days){
            s.beginTestHunt(day)
            val run=requireNotNull(s.expedition)
            val points=run.plan.stars.map{it.point}
            val interior=ExpeditionPlan.EDGE_MARGIN..(Course.W-1-ExpeditionPlan.EDGE_MARGIN)
            points.forEachIndexed{i,p->
                assertTrue(p.x in interior && p.z in interior)
                assertTrue(previous.all{it.distance(p)>=ExpeditionPlan.PREVIOUS_CLEARANCE})
                assertTrue(points.drop(i+1).all{it.distance(p)>=ExpeditionPlan.STAR_SPACING})
                assertNotNull(WaypointRoute.leg(s.course,s.trial.position,p))
            }
            previous=points
            layouts+=run.plan.stars
            listOf(2,0,1).forEach{s.queueStar(it)}
            assertFalse(s.paused)
            var ticks=0
            while(s.questActive && !s.paused && ticks++<18000)s.step()
            assertNotNull("$day stalled: ${s.caption}",run.result)
            assertEquals("$day",3,run.result!!.medal)
            assertEquals(3,run.result!!.flags)
        }
        assertEquals("No weekday loop or duplicate layouts in this sample",days.size,layouts.size)
    }

    @Test fun dailyStatisticsResetTogetherAndHistorySurvivesRestart(){
        val prefs=instrumentation.context.getSharedPreferences("daily-lifecycle",0)
        prefs.edit().clear().commit()
        val zone=ZoneId.systemDefault()
        val midnight=LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val course=Course.load(assets)
        val a=Passage.CLIMB.route.first()
        val b=Passage.CLIMB.route.last()
        val journal=RunJournal(prefs)
        journal.record(course,a,b,midnight-1)
        journal.record(course,b,a,midnight-1)
        val yesterday=journal.snapshot(midnight-1)
        assertTrue(yesterday.todayMetres>0 && yesterday.ascentMetres>0)
        journal.save()
        val fresh=RunJournal(prefs)
        val today=fresh.snapshot(midnight)
        assertEquals(0.0,today.todayMetres,0.0)
        assertEquals(0.0,today.ascentMetres,0.0)
        assertEquals(0.0,today.kcal,0.0)
        assertEquals(yesterday.totalMetres,today.totalMetres,.001)
        fresh.record(course,a,b,midnight)
        fresh.record(course,b,a,midnight)
        val moved=fresh.snapshot(midnight)
        assertTrue(moved.todayMetres>0 && moved.ascentMetres>0 && moved.kcal>0)
        fresh.save()
        assertEquals(moved.todayMetres,RunJournal(prefs).snapshot(midnight).todayMetres,.001)
        prefs.edit().clear().commit()
    }
}
