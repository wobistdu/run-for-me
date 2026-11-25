package com.example.terrainrunner.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.terrainrunner.ActiveRun
import com.example.terrainrunner.RunJournal
import com.example.terrainrunner.mainApp

class SpatialApplication : Application() {
    internal val journal by lazy { RunJournal(getSharedPreferences("runner-journal", MODE_PRIVATE)) }

    internal val dailyRanking by lazy { com.example.terrainrunner.DailyRanking(this, journal) }
    private var run:ActiveRun?=null
    internal fun activeRun():ActiveRun=run?:ActiveRun(this).also{run=it}
    internal fun releaseRun(owner:ActiveRun){
        if(run===owner){owner.close();run=null}
    }

    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
