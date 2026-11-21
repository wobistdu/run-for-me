package com.example.terrainrunner

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

class RouteContinuity {
    @Test fun facingAndClimbOnTheTour(){
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val scene=RidgeSession(Network(assets),Course.load(assets))
        val trial=scene.trial
        var previousY=0f
        var previousDy=0f
        var lastStride=0f
        var maxYaw=0f
        val climbs=mutableListOf<Float>()
        val strideChanges=mutableListOf<Float>()
        val samples=if(InstrumentationRegistry.getArguments().getString("recordContinuity")=="true")StringBuilder() else null
        do{
            val before=trial.heading
            val start=trial.position
            scene.step()
            val facing=trial.heading
            val yaw=atan2(abs(before.x*facing.z-before.z*facing.x),before.x*facing.x+before.z*facing.z)*180f/PI.toFloat()
            maxYaw=max(maxYaw,yaw)
            val pelvis=trial.displayBaseY+trial.pose.output!![33]*Network.MODEL_TO_METRES
            val dy=pelvis-previousY
            val stride=start.distance(trial.position)
            val uphill=trial.course.height(trial.position)>trial.course.height(start)
            if(samples!=null && abs(start.mz+.106f+.21f*start.mx)<.018f && start.mx in -.245f.. .22f){
                val y=trial.pose.output!!
                samples.appendLine("${trial.ticks},${if(uphill)1 else 0},${trial.predictedBaseY+y[33]*Network.MODEL_TO_METRES},$pelvis,${max(y[4],y[5])},${max(y[6],y[7])}")
            }
            if(trial.ticks>2 && uphill && abs(start.mz+.106f+.21f*start.mx)<.018f && start.mx in -.245f.. .22f){
                climbs+=abs(dy-previousDy)
                strideChanges+=abs(stride-lastStride)
            }
            previousY=pelvis;previousDy=dy;lastStride=stride
        }while(!trial.done && !scene.paused && trial.ticks<12000)
        assertTrue(trial.done && climbs.size>100)
        if(samples!=null)InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("ridge-continuity.csv").writeText(samples.toString())
        assertTrue("single-tick turn: $maxYaw",maxYaw<=4.01f)
        fun p99(v:List<Float>)=v.sorted()[((v.size-1)*.99f).toInt()]
        assertTrue("uphill pelvis second difference: ${p99(climbs)}",p99(climbs)<.00065f)
        assertTrue("largest uphill pelvis second difference: ${climbs.max()}",climbs.max()<.001f)
        Log.i("RouteContinuity","frames=${trial.ticks} maxYawDeg=$maxYaw uphillN=${climbs.size} uphillPelvisAccelP99LocalMm=${1000*p99(climbs)} uphillPelvisAccelMaxLocalMm=${1000*climbs.max()} uphillStrideChangeP99LocalMm=${Course.CELL*1000*p99(strideChanges)}")
    }
}
