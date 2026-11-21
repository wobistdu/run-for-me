package com.example.terrainrunner

import java.util.PriorityQueue
import kotlin.math.*

/** Route each consecutive pair independently so smoothing cannot skip a user waypoint. */
object WaypointRoute {
    private const val GRID=4
    private const val COLS=(Course.W-1)*GRID+1
    private const val ROWS=(Course.H-1)*GRID+1
    private const val MAX_GRADE=.92f

    fun walkable(course:Course,start:Point,end:Point):Boolean {
        val distance=start.distance(end)
        val samples=max(1,ceil(distance*12).toInt())
        var previous=course.height(start)
        if(!course.supports(start))return false
        for(i in 1..samples){
            val p=start.toward(end,i.toFloat()/samples)
            if(!course.supports(p))return false
            val height=course.height(p)
            if(abs(height-previous)>MAX_GRADE*distance/samples+.0001f)return false
            previous=height
        }
        return true
    }
    private fun point(id:Int)=Point((id%COLS).toFloat()/GRID,(id/COLS).toFloat()/GRID)
    private fun nearby(course:Course,p:Point):List<Int> {
        val x=(p.x*GRID).roundToInt();val z=(p.z*GRID).roundToInt()
        return buildList {
            for(j in max(0,z-1)..min(ROWS-1,z+1))
                for(i in max(0,x-1)..min(COLS-1,x+1)){
                    val id=j*COLS+i
                    if(walkable(course,p,point(id)))add(id)
                }
        }
    }

    /** Flood the same navigation grid used by flag routing, including diagonal connections. */
    internal fun reachable(course:Course,start:Point):List<Point>{
        val seen=BooleanArray(COLS*ROWS)
        val queue=ArrayDeque<Int>()
        for(id in nearby(course,start)){seen[id]=true;queue.addLast(id)}
        val result=ArrayList<Point>()
        while(queue.isNotEmpty()){
            val id=queue.removeFirst()
            val p=point(id)
            result.add(p)
            val x=id%COLS
            val z=id/COLS
            for(j in max(0,z-1)..min(ROWS-1,z+1))for(i in max(0,x-1)..min(COLS-1,x+1)){
                val next=i+j*COLS
                if(!seen[next] && walkable(course,p,point(next))){
                    seen[next]=true
                    queue.addLast(next)
                }
            }
        }
        return result
    }

    fun leg(course:Course,start:Point,end:Point):List<Point>? {
        if(walkable(course,start,end))return listOf(start,end)
        val ends=nearby(course,end).toSet()
        if(ends.isEmpty())return null
        val parent=IntArray(COLS*ROWS){-1}
        val cost=FloatArray(parent.size){Float.POSITIVE_INFINITY}
        data class Visit(val id:Int,val estimate:Float)
        val open=PriorityQueue(compareBy(Visit::estimate))
        for(id in nearby(course,start)){
            val p=point(id);cost[id]=start.distance(p)
            open.add(Visit(id,cost[id]+p.distance(end)))
        }
        val visited=BooleanArray(parent.size)
        while(open.isNotEmpty()){
            val id=open.remove().id
            if(visited[id])continue
            visited[id]=true
            val p=point(id)
            if(id in ends){
                val chain=mutableListOf(end)
                var cursor=id
                while(cursor>=0){chain.add(point(cursor));cursor=parent[cursor]}
                chain.add(start);chain.reverse()
                val smooth=mutableListOf(start)
                var from=0
                while(from<chain.lastIndex){
                    var to=chain.lastIndex
                    while(to>from+1 && !walkable(course,chain[from],chain[to]))to--
                    if(chain[to]!=smooth.last())smooth.add(chain[to])
                    from=to
                }
                return smooth
            }
            val x=id%COLS;val z=id/COLS
            for(j in max(0,z-1)..min(ROWS-1,z+1))for(i in max(0,x-1)..min(COLS-1,x+1)){
                val next=j*COLS+i
                if(visited[next])continue
                val q=point(next);val nextCost=cost[id]+p.distance(q)
                if(nextCost>=cost[next] || !walkable(course,p,q))continue
                cost[next]=nextCost;parent[next]=id;open.add(Visit(next,nextCost+q.distance(end)))
            }
        }
        return null
    }
}
