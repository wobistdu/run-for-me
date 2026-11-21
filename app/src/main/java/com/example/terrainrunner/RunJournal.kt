package com.example.terrainrunner

import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

internal data class RunTotals(
    val todayMetres:Double, val totalMetres:Double, val ascentMetres:Double,
    val days:Int, val streak:Int
){
    // Game estimate for a 60 kg avatar, including the extra work on ascents.
    val kcal get()=60.0*(todayMetres/1000.0+.009*ascentMetres)
}

/** One entry per active local calendar day. Simulation and publication run on the UI thread. */
class RunJournal internal constructor(private val prefs:SharedPreferences){
    private data class Day(var metres:Double=0.0,var ascent:Double=0.0)
    private val days=mutableMapOf<Long,Day>()
    private val dirty=mutableSetOf<Long>()
    private var total=0.0
    private var zone=ZoneId.systemDefault()
    private var day=0L
    private var midnight=0L
    private var tomorrow=0L

    init{
        for((key,value) in prefs.all){
            val epoch=key.substringAfter('/').toLongOrNull()?:continue
            val mm=value as? Long?:continue
            val saved=days.getOrPut(epoch){Day()}
            if(key.startsWith("distance/")){saved.metres=mm/1000.0;total+=saved.metres}
            if(key.startsWith("ascent/"))saved.ascent=mm/1000.0
        }
    }

    private fun localDay(time:Long){
        if(time>=midnight && time<tomorrow)return
        val date=Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        day=date.toEpochDay()
        midnight=date.atStartOfDay(zone).toInstant().toEpochMilli()
        tomorrow=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    internal fun record(ground:Course,from:Point,to:Point,time:Long=System.currentTimeMillis()){
        if(from==to)return
        // Convert local terrain metres to character-scale metres, then award the game multiplier.
        val scale=1.65/Course.RUNNER_HEIGHT*1.5
        val horizontal=from.distance(to)*Course.CELL
        val rise=(ground.height(to)-ground.height(from))*Course.CELL
        val metres=hypot(horizontal.toDouble(),rise.toDouble())*scale
        if(!metres.isFinite() || metres<=0.0)return
        localDay(time)
        val entry=days.getOrPut(day){Day()}
        entry.metres+=metres
        entry.ascent+=max(0.0,rise*scale)
        total+=metres
        dirty.add(day)
    }

    internal fun snapshot(time:Long=System.currentTimeMillis(),localZone:ZoneId=ZoneId.systemDefault()):RunTotals{
        if(localZone!=zone){zone=localZone;tomorrow=0L}
        localDay(time)
        val today=days[day]
        var cursor=if(today!=null)day else day-1
        var streak=0
        while(days.containsKey(cursor)){streak++;cursor--}
        return RunTotals(today?.metres?:0.0,total,today?.ascent?:0.0,days.size,streak)
    }

    internal fun save(){
        if(dirty.isEmpty())return
        val edit=prefs.edit()
        for(date in dirty){
            val value=days.getValue(date)
            edit.putLong("distance/$date",(value.metres*1000).roundToLong())
            edit.putLong("ascent/$date",(value.ascent*1000).roundToLong())
        }
        edit.apply()
        dirty.clear()
    }
}
