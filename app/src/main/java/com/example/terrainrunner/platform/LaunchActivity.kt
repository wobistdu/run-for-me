package com.example.terrainrunner.platform

import android.content.Intent
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity(){
    override fun onResume(){
        super.onResume()
        startForegroundService(Intent(this,CompanionRunService::class.java))
    }
    override fun onDestroy(){
        if(isFinishing)stopService(Intent(this,CompanionRunService::class.java))
        super.onDestroy()
    }
}
