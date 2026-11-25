package com.example.terrainrunner

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.terrainrunner.platform.SpatialApplication
import kotlinx.coroutines.*

/** Display callbacks and the service share this clock and the mounted scene on the main thread. */
internal class ActiveRun(app:SpatialApplication){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val audio=TrailAudio(app)
    private val appRanking=app.dailyRanking
    val ready=scope.async {
        withContext(Dispatchers.IO){
            val prefs=app.getSharedPreferences("runner-style",0)
            val style=RunnerStyle.saved(prefs.getString("selected",null))
            RidgeSession(Network(app.assets),Course.load(app.assets),app.journal,style){selected->
                prefs.edit().putString("selected",selected.name).apply()
            }.also{
                it.loadBook(FieldBook(app.getSharedPreferences("field-book",0)))
            }
        }
    }
    private var last=0L
    private var remainder=0.0
    private var lastDisplay=0L
    private var scene:RidgeScene?=null
    private var windowState:(()->String)?=null
    private var onstage:(()->Boolean)?=null
    private var lastCalendarCheck=0L
    private val power=app.getSystemService(android.os.PowerManager::class.java)
    private var skippedPoses=false
    private val debug=app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0
    private var sampleStart=0L
    private var steps=0
    private var displayPoses=0
    private var servicePoses=0

    fun attach(view:RidgeScene,visible:()->Boolean,state:()->String){
        scene=view
        windowState=state
        onstage=visible
        view.resetInterpolation()
    }
    fun detach(view:RidgeScene){
        if(scene!==view)return
        scene=null
        windowState=null
        onstage=null
    }
    fun display(s:RidgeSession,view:RidgeScene,now:Long){
        if(scene!==view)return
        lastDisplay=System.nanoTime()
        tick(s,now,view,true,power.isInteractive && onstage?.invoke()==true)
    }
    fun background(s:RidgeSession,now:Long,screenOn:Boolean){
        if(now-lastDisplay<32_000_000L)return
        val visible=screenOn && scene!=null && onstage?.invoke()==true
        tick(s,now,if(visible)scene else null,false,visible)
    }
    private fun tick(s:RidgeSession,now:Long,view:RidgeScene?,display:Boolean,visible:Boolean){
        if(now<=last)return
        val elapsed=if(last==0L)0.0 else (now-last)*1e-9
        last=now
        if(now-lastCalendarCheck>=1_000_000_000L){
            s.journal?.snapshot()
            appRanking.syncIfDue()
            lastCalendarCheck=now
        }
        if(view!=null && skippedPoses){view.resetInterpolation();skippedPoses=false}
        if(s.paused || !visible)remainder=0.0 else {
            // Bound catch-up after sleep. Credit only the steps actually simulated.
            remainder+=elapsed.coerceAtMost(.25)*s.speed
            while(remainder>=1.0/60 && !s.paused){
                val stars=s.totalStars
                s.step()
                if(s.soundEnabled && s.totalStars>stars)audio.collected(s.expedition?.collected?.size?:1)
                if(view==null)skippedPoses=true else view.capturePose()
                steps++
                remainder-=1.0/60
            }
        }
        if(view!=null){
            if(!s.editing)view.paint(s.course,true)
            if(view.animate((remainder*60).toFloat())){
                if(display)displayPoses++ else servicePoses++
            }
        }
        if(debug){
            if(sampleStart==0L)sampleStart=now
            if(now-sampleStart>=5_000_000_000L){
                val seconds=(now-sampleStart)*1e-9
                Log.i("RidgeTiming","speed=${s.speed} paused=${s.paused} simHz=${steps/seconds} poseHz=${(displayPoses+servicePoses)/seconds} displayPoses=$displayPoses fallbackPoses=$servicePoses ${windowState?.invoke()?:"detached"} sceneRoot=${view?.submittedRoot}")
                sampleStart=now;steps=0;displayPoses=0;servicePoses=0
            }
        }
    }
    fun close(){audio.close();scope.cancel()}
}
