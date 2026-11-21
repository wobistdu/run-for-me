package com.example.terrainrunner

import android.content.SharedPreferences
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToInt

internal const val AVATAR_METRES = 1.65 / Course.RUNNER_HEIGHT * 1.5 * Course.CELL

internal data class TrailStar(val name:String,val point:Point)

/** The title follows the calendar. Star positions are drawn anew for each attempt. */
internal data class ExpeditionPlan(
    val day:Long,val title:String,val stars:List<TrailStar>
){
    companion object{
        val titles=listOf("溪畔来信","山脊微光","越过风声","林间漫游","逐光小径","山野回响","日落归途")
        fun title(date:LocalDate)=titles[Random(date.toEpochDay() xor 0x51A7L).nextInt(titles.size)]
        const val EDGE_MARGIN=2f // Grid units: 8 cm on each side of the authored 68 cm island.
        const val STAR_SPACING=2.5f
        const val PREVIOUS_CLEARANCE=1f
        fun create(date:LocalDate,course:Course,start:Point,previous:List<Point>):ExpeditionPlan?{
            val interior=EDGE_MARGIN..(Course.W-1-EDGE_MARGIN)
            val reachable=WaypointRoute.reachable(course,start).filter{p->
                p.x in interior && p.z in interior && p.distance(start)>=1f &&
                    previous.none{it.distance(p)<PREVIOUS_CLEARANCE}
            }.shuffled()
            val stars=mutableListOf<TrailStar>()
            for(p in reachable){
                if(stars.any{it.point.distance(p)<STAR_SPACING})continue
                if(course.onDeck(p) || course.crouch(p)>.1f || course.jump(p)>.1f)continue
                // Keep the ground ring clear of ledges, unsupported banks and solid obstacles.
                if(!listOf(Point(p.x-.35f,p.z),Point(p.x+.35f,p.z),Point(p.x,p.z-.35f),Point(p.x,p.z+.35f))
                    .all{WaypointRoute.walkable(course,p,it)})continue
                val name=when(stars.size){0->"晨星";1->"辰星";else->"暮星"}
                stars+=TrailStar(name,p)
                if(stars.size==3)return ExpeditionPlan(date.toEpochDay(),title(date),stars)
            }
            return null
        }
    }
}

private fun medalForFlags(flags:Int)=when{flags<=3->3;flags<=5->2;else->1}

internal data class ExpeditionResult(
    val day:Long,val medal:Int,val metres:Double,val flags:Int,val seconds:Int
){
    val title get()=when(medal){3->"三星领航！";2->"漂亮的路线！";else->"山野寻星完成！"}
    val distanceLabel get()="${metres.roundToInt()} m"
}

internal class ExpeditionRun(val plan:ExpeditionPlan){
    var start:Point?=null;private set
    fun startAt(point:Point){start=point}
    val collected=mutableSetOf<Int>()
    var flags=0;private set
    var metres=0.0;private set
    private var ticks=0
    var result:ExpeditionResult?=null;private set
    fun placeFlag(){if(result==null)flags++}
    fun move(from:Point,to:Point){
        if(result!=null)return
        metres+=from.distance(to)*AVATAR_METRES
        ticks++
    }
    fun arrive(point:Point):Int?{
        if(result!=null)return null
        val index=plan.stars.indexOfFirst{it.point.distance(point)<.03f}
        if(index<0 || !collected.add(index))return null
        if(collected.size==3){
            result=ExpeditionResult(plan.day,medalForFlags(flags),metres,flags,ticks/60)
        }
        return index
    }
}

/** One best result per local date; repeat attempts never farm stamps or unlocks. */
internal class FieldBook(private val prefs:SharedPreferences){
    // Legacy saves contain one completed hunt per recorded date. Partial and repeat
    // hunts were not logged in those versions, so only verified stars can be restored.
    val collectedStars:Long get()=if(prefs.contains("collected-stars"))prefs.getLong("collected-stars",0L)
        else results().size*3L
    fun addCollectedStar():Long{
        val next=collectedStars+1
        prefs.edit().putLong("collected-stars",next).apply()
        return next
    }
    fun best(day:Long):ExpeditionResult?{
        val fields=prefs.getString("v1/$day",null)?.split('|')?:return null
        if(fields.size!=4)return null
        val medal=fields[0].toIntOrNull()?:return null
        val metres=fields[1].toDoubleOrNull()?:return null
        val flags=fields[2].toIntOrNull()?:return null
        val seconds=fields[3].toIntOrNull()?:return null
        if(medal !in 1..3 || !metres.isFinite() || metres<=0 || flags<3 || seconds<0)return null
        return ExpeditionResult(day,medalForFlags(flags),metres,flags,seconds)
    }
    fun results()=prefs.all.keys.filter{it.startsWith("v1/")}
        .mapNotNull{it.removePrefix("v1/").toLongOrNull()?.let(::best)}
        .sortedByDescending{it.day}
    fun record(result:ExpeditionResult):Boolean{
        val previous=best(result.day)
        val improved=previous==null || result.flags<previous.flags
        if(improved)prefs.edit().putString("v1/${result.day}",
            "${result.medal}|${result.metres}|${result.flags}|${result.seconds}").apply()
        return improved
    }
    var reachedFirstFlag:Boolean
        get()=prefs.getBoolean("reached-first-flag",false)
        set(value){prefs.edit().putBoolean("reached-first-flag",value).apply()}
    var boardVisible:Boolean
        get()=prefs.getBoolean("board-visible",true)
        set(value){prefs.edit().putBoolean("board-visible",value).apply()}
    val soundEnabled:Boolean
        get()=prefs.getBoolean("sound-enabled",true)
    var ribbon:Int
        get()=prefs.getInt("ribbon",0).coerceIn(0,2)
        set(value){prefs.edit().putInt("ribbon",value.coerceIn(0,2)).apply()}
}
