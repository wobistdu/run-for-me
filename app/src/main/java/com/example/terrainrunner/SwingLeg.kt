package com.example.terrainrunner

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.*

/** Two-link reach in the predicted bend plane. The sole keeps its predicted orientation. */
internal class SwingLeg(private val raw:PfnnSkeleton, side:Int) {
    private val hip=2+5*side
    private val knee=hip+1
    private val ankle=hip+2
    private val h=raw.positions[hip]
    private val k=raw.positions[knee]
    private val a=raw.positions[ankle]
    private val thigh=k-h
    private val shin=a-k
    private val upper=thigh.length()
    private val lower=shin.length()

    val maxLift=max(0f,h.y-a.y-lower*.22f)

    private fun turn(from:Vector3,to:Vector3):Quat {
        val axis=Vector3.cross(from,to)
        val length=axis.length()
        return if(length<1e-7f)Quat() else Quat(axis*(1f/length),atan2(length,Vector3.dot(from,to)))
    }

    fun place(pose:PfnnSkeleton,lift:Float){
        val delta=a+Vector3(0f,lift,0f)-h
        val distance=delta.length().coerceAtLeast(.0001f)
        val axis=delta*(1f/distance)
        val reach=distance.coerceIn(abs(upper-lower)+.0001f,upper+lower-.0001f)
        val along=(upper*upper-lower*lower+reach*reach)/(2f*reach)
        val oldAxis=(a-h)*(1f/(a-h).length().coerceAtLeast(.0001f))
        var bend=thigh-oldAxis*Vector3.dot(thigh,oldAxis)
        bend=turn(oldAxis,axis).rotateVector(bend)
        if(bend.length()<.0001f){
            val forward=Vector3(0f,0f,1f)
            bend=forward-axis*Vector3.dot(forward,axis)
        }
        val newK=h+axis*along+bend*(sqrt(max(0f,upper*upper-along*along))/bend.length().coerceAtLeast(.0001f))
        val newA=h+axis*reach
        pose.positions[knee]=newK
        pose.positions[ankle]=newA
        pose.positions[ankle+1]=raw.positions[ankle+1]+(newA-a)
        pose.rotations[hip]=turn(thigh,newK-h)*raw.rotations[hip]
        pose.rotations[knee]=turn(shin,newA-newK)*raw.rotations[knee]
    }
}
