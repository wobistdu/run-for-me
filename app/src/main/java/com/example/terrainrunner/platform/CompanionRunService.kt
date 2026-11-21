package com.example.terrainrunner.platform

import android.app.*
import android.content.Intent
import android.os.PowerManager
import android.os.IBinder
import com.example.terrainrunner.ActiveRun
import kotlinx.coroutines.*

/** Supplies a clock for an onstage sandbox when display callbacks are temporarily absent. */
class CompanionRunService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val app get()=application as SpatialApplication
    private lateinit var run:ActiveRun

    override fun onCreate(){
        super.onCreate()
        val channel="companion-run"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"沙盘陪跑",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,LaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,channel)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(getString(com.example.terrainrunner.R.string.app_name) + " · 沙盘陪跑")
            .setContentText("点此返回沙盘").setContentIntent(open).setOngoing(true).build()
        startForeground(1,notification)
        run=app.activeRun()
        scope.launch{
            val s=run.ready.await()
            val power=getSystemService(PowerManager::class.java)
            var lastSave=0L
            while(isActive){
                val now=System.nanoTime()
                run.background(s,now,power.isInteractive)
                if(now-lastSave>=1_000_000_000L){
                    s.journal?.save();lastSave=now
                }
                delay(32)
            }
        }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){
        scope.cancel()
        app.journal.save()
        app.releaseRun(run)
        super.onDestroy()
    }
}
