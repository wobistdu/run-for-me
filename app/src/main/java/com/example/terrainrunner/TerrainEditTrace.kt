package com.example.terrainrunner

import android.os.Trace
import android.util.Log

/** Keep timing/logging off the normal editing path; Perfetto enables it for a capture. */
internal inline fun <T> terrainEditStage(stage:String,block:()->T):T{
    if(!Trace.isEnabled())return block()
    Trace.beginSection("TerrainEdit/$stage")
    val start=java.lang.System.nanoTime()
    try{return block()}finally{
        val elapsed=(java.lang.System.nanoTime()-start)/1e6
        Trace.endSection()
        Log.i("TerrainEdit","$stage ms=$elapsed")
    }
}
