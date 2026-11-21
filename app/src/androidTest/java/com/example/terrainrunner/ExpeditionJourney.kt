package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.Entity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

internal fun RidgeSession.beginTestHunt(date:LocalDate=LocalDate.now()){
    assertTrue(kotlinx.coroutines.runBlocking{startExpedition(date)})
}

/** Device journeys use the shipped terrain, PFNN and contact solver end to end. */
class ExpeditionJourney {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun session()=RidgeSession(Network(context.assets),Course.load(context.assets))
    @Test fun defaultTourHasNoFootRingBeforeOrAfterHunt()=runBlocking {
        val s=session()
        withContext(Dispatchers.Main){
            val island=Entity()
            val scenery=ExpeditionScenery(island,s)
            try {
                fun visibleCount()=island.getChildren().count{it.enabled}
                assertEquals(0,visibleCount())
                s.step();scenery.update()
                assertEquals(0,visibleCount())
                assertTrue(s.startExpedition())
                scenery.update()
                assertEquals(7,visibleCount()) // runner halo, three stars and three landing rings
                s.queueStar(0);scenery.update()
                assertEquals(8,visibleCount())
                s.leaveExpedition();scenery.update()
                assertEquals(0,visibleCount())
            } finally {
                island.destroy(true)
                scenery.close()
            }
        }
    }
    private fun finish(s:RidgeSession){
        assertFalse("the first flag must start the runner",s.paused)
        var ticks=0
        while(s.questActive && !s.paused && ticks++<18000)s.step()
        assertNotNull("route stalled: ${s.caption} / ticks=$ticks",s.expedition?.result)
    }

    @Test fun everyDailyRouteFinishesWithThreeStarsOnRealTerrain(){
        val s=session()
        repeat(7){day->
            s.beginTestHunt(LocalDate.of(2026,11,16).plusDays(day.toLong()))
            val plan=requireNotNull(s.expedition).plan
            listOf(1,0,2).forEach{s.queueStar(it)}
            finish(s)
            val result=requireNotNull(s.expedition?.result)
            assertEquals(3,result.medal)
            assertEquals(3,result.flags)
            assertTrue(result.metres>10)
            assertFalse(s.paused)
            android.util.Log.i("ExpeditionJourney","${plan.title} medal=${result.medal} metres=${result.metres} seconds=${result.seconds}")
            s.leaveExpedition()
        }
    }

    @Test fun firstValidFlagReplacesLiveRouteWithoutResettingRunner(){
        val s=session()
        s.faster()
        s.editPoints()
        s.append(Course.local(.26f,.18f))
        s.exitPoints()
        repeat(12){s.step()}
        val trial=s.trial
        val pose=trial.pose
        val flags=s.legs
        val path=trial.route
        s.beginTestHunt()
        assertSame(trial,s.trial);assertSame(pose,trial.pose)
        assertSame(flags,s.legs);assertSame(path,trial.route)
        assertEquals(1.25f,s.speed,0f);assertFalse(s.paused)
        s.append(Point(-5f,-5f));s.removePoint()
        assertSame(flags,s.legs)
        val run=requireNotNull(s.expedition)
        val before=trial.position
        repeat(20){s.step()}
        assertNotEquals(before,trial.position);assertEquals(0.0,run.metres,0.0)
        val atCommit=trial.position;val phase=trial.pose.phase
        s.queueStar(0);s.queueStar(0)
        assertSame(trial,s.trial);assertEquals(phase,trial.pose.phase,0f)
        assertEquals(atCommit,run.start)
        assertEquals(listOf(run.plan.stars[0].point),s.visiblePoints)
        assertEquals(1,run.flags)
        s.queueStar(1)
        assertEquals(2,s.legs.size)
        repeat(30){s.step()}
        assertTrue(run.metres>0)
        s.paused=true
        val stopped=trial.position
        repeat(30){s.step()}
        assertEquals(stopped,trial.position)
        s.paused=false;s.queueStar(2);finish(s)
    }

    @Test fun retryPersistsOnlyOneStampAndRestoresSculptedSandbox(){
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("journey-book",0)
        prefs.edit().clear().commit()
        val book=FieldBook(prefs)
        val s=session()
        s.loadBook(book)
        s.editMode()
        s.commit(Course.Stroke(s.course,Tool.RAISE,Course.local(.13f,.22f)))
        s.finishEditing()
        val sculpted=s.course
        val day=LocalDate.of(2026,11,20)
        repeat(2){
            s.beginTestHunt(day)
            listOf(2,1,0).forEach{s.queueStar(it)}
            finish(s)
            assertEquals(1,book.results().size)
            s.leaveExpedition()
            assertSame(sculpted,s.course)
            assertFalse(s.paused)
        }
        val restored=FieldBook(prefs)
        assertEquals(3,restored.best(day.toEpochDay())?.medal)
        assertNull(restored.best(day.plusDays(1).toEpochDay()))
        assertEquals(1,s.earnedDays)
        assertEquals(1,TrailBadge.entries.count{s.earnedDays>=it.days})
        prefs.edit().clear().commit()
    }

    @Test fun lifetimeStarsCountArrivalsIncludingPartialRunsAndLegacyDays(){
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("lifetime-stars-test",0)
        prefs.edit().clear().commit()
        try{
            val book=FieldBook(prefs)
            book.record(ExpeditionResult(100,1,80.0,5,30))
            val s=session();s.loadBook(book);s.faster()
            assertEquals(3L,s.totalStars)
            repeat(2){attempt->
                s.beginTestHunt();assertEquals(1.25f,s.speed,0f)
                s.queueStar(0);s.queueStar(0)
                assertEquals(3L+attempt,s.totalStars)
                var ticks=0
                while(s.expedition!!.collected.isEmpty() && ticks++<15000)s.step()
                assertEquals(4L+attempt,s.totalStars)
                repeat(10){s.step()}
                s.leaveExpedition()
                assertEquals(4L+attempt,FieldBook(prefs).collectedStars)
                assertEquals(1,s.earnedDays)
            }
        }finally{prefs.edit().clear().commit()}
    }

    @Test fun achievementsUseDistinctCompletedDaysAndRestoreAfterRestart(){
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("badge-journey",0)
        prefs.edit().clear().commit()
        try{
            val book=FieldBook(prefs)
            val s=session()
            for(n in 0..7){
                if(n>0){
                    book.record(ExpeditionResult(n.toLong(),2,90.0,4,12))
                    book.record(ExpeditionResult(n.toLong(),3,70.0,3,10))
                }
                s.loadBook(FieldBook(prefs))
                assertEquals(n,s.earnedDays)
                val expected=listOf(n>=1,n>=3,n>=7)
                assertEquals(expected,TrailBadge.entries.map{s.earnedDays>=it.days})
            }
        }finally{prefs.edit().clear().commit()}
    }

    @Test fun undoAndRerouteKeepArrivalAndFlagBudgetHonest(){
        val s=session()
        s.beginTestHunt()
        val run=requireNotNull(s.expedition)
        val indices=listOf(1,2,0)
        s.queueStar(indices[0]);s.queueStar(indices[1])
        repeat(50){s.step()}
        val point=s.trial.position
        s.removePoint()
        assertEquals(point,s.trial.position)
        s.queueStar(indices[1]);s.queueStar(indices[2])
        finish(s)
        assertEquals(4,run.result?.flags)
        assertEquals(2,run.result?.medal)
        s.beginTestHunt()
        s.queueStar(0);s.removePoint()
        assertFalse(s.paused)
        assertTrue(s.visiblePoints.isEmpty())
        s.queueStar(1)
        assertFalse(s.paused)
        assertEquals(2,s.expedition?.flags)
    }

    @Test fun unreachableShoreAndFloodedTerrainNeverGetStars(){
        val side=Course.SAMPLES
        val divided=Course.ridge(FloatArray(side*side){if(it%side in 77..93)-.08f else 0f})
        val start=Point(4f,8f)
        val date=LocalDate.of(2026,11,20)
        val plan=requireNotNull(ExpeditionPlan.create(date,divided,start,emptyList()))
        assertTrue(plan.stars.all{it.point.x<7.7f && WaypointRoute.leg(divided,start,it.point)!=null})
        val flooded=Course.ridge(FloatArray(side*side){-.08f})
        assertNull(ExpeditionPlan.create(date,flooded,start,emptyList()))
    }

    @Test fun firstFlagGuideCompletesOnlyOnArrivalAndSurvivesRestart(){
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("first-flag-guide-test",0)
        prefs.edit().clear().commit()
        try{
            val s=session()
            s.loadBook(FieldBook(prefs))
            s.editPoints()
            s.append(Point(-5f,-5f))
            assertTrue(s.visiblePoints.isEmpty())
            assertFalse(s.reachedFirstFlag)
            val start=s.trial.position
            val point=Point(start.x+.68f,start.z)
            s.append(point)
            assertEquals(listOf(point),s.visiblePoints)
            s.removePoint()
            assertFalse(s.reachedFirstFlag)
            s.append(point)
            var ticks=0
            while(s.visiblePoints.isNotEmpty() && ticks++<600)s.step()
            assertEquals(point,s.trial.position)
            assertTrue(s.reachedFirstFlag)
            assertTrue(s.caption.contains("已到红旗"))
            assertTrue(FieldBook(prefs).reachedFirstFlag)
        }finally{prefs.edit().clear().commit()}
    }

    @Test fun boardChoicePersistsAndDailyBestUsesFewestFlags(){
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("board-and-score-test",0)
        prefs.edit().clear().commit()
        try{
            val s=session()
            val book=FieldBook(prefs)
            book.record(ExpeditionResult(1,3,70.0,3,10))
            s.loadBook(book)
            assertTrue(s.boardVisible)
            s.selectedBadge=TrailBadge.DAWN
            assertEquals(TrailBadge.DAWN,s.selectedBadge)
            s.toggleBoard()
            assertFalse(s.boardVisible);assertNull(s.selectedBadge)
            assertFalse(FieldBook(prefs).boardVisible)
            s.toggleBoard()
            assertTrue(FieldBook(prefs).boardVisible)
            prefs.edit().putString("v1/77","1|80.0|4|21").commit()
            assertEquals(2,book.best(77)?.medal)
            assertFalse(book.record(ExpeditionResult(77,2,30.0,5,10)))
            assertTrue(book.record(ExpeditionResult(77,3,150.0,3,50)))
            assertEquals(3,book.best(77)?.flags)
        }finally{prefs.edit().clear().commit()}
    }

    @Test fun openingAndLeavingBeforeFirstFlagPreservePausedRoute(){
        val s=session()
        s.editPoints();s.append(Course.local(.26f,.18f));s.exitPoints()
        s.paused=true
        val route=s.trial.route;val legs=s.legs;val phase=s.trial.pose.phase
        s.beginTestHunt();repeat(10){s.step()};s.leaveExpedition()
        assertSame(route,s.trial.route);assertSame(legs,s.legs);assertTrue(s.paused)
        assertEquals(phase,s.trial.pose.phase,0f)
    }
}
