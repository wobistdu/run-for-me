package com.example.terrainrunner

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3

/** Decode the authors' global joint positions and quaternion logarithms in the facing frame. */
internal class PfnnSkeleton {
    val positions=Array(31){Vector3.ZERO}
    val rotations=Array(31){Quat()}

    fun kneeFlex(foot:Int):Float {
        val hip=2+5*foot
        val thigh=positions[hip+1]-positions[hip]
        val shin=positions[hip+2]-positions[hip+1]
        val lengths=thigh.length()*shin.length()
        if(lengths<1e-6f)return 0f
        val cosine=Vector3.dot(thigh,shin)/lengths
        val t=((.90f-cosine)/.74f).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }

    fun decode(output:FloatArray) {
        for(i in positions.indices){
            val p=32+3*i
            positions[i]=Vector3(output[p],output[p+1],output[p+2])
            val r=218+3*i
            val axis=Vector3(output[r],output[r+1],output[r+2])
            val halfAngle=axis.length()
            rotations[i]=if(halfAngle<1e-7f)Quat() else Quat(axis,2*halfAngle)
        }
    }
}
