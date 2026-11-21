package com.example.terrainrunner

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Short original bells; follows the game/media volume and never plays offstage. */
internal class TrailAudio(context:Context){
    private val ready=mutableSetOf<Int>()
    private val pool=SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val collect:Int
    private val complete:Int
    init{
        pool.setOnLoadCompleteListener{_,id,status->if(status==0)synchronized(ready){ready.add(id)}}
        collect=pool.load(context,R.raw.trail_collect,1)
        complete=pool.load(context,R.raw.trail_complete,1)
    }
    fun collected(count:Int){
        val sound=if(count==3)complete else collect
        if(synchronized(ready){sound in ready})
            pool.play(sound,.34f,.34f,1,0,if(count==2)1.125f else 1f)
    }
    fun close(){pool.release()}
}
