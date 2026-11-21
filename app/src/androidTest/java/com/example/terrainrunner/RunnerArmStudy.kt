package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream

class RunnerArmStudy {
    @Test fun loadedBindHasArticulatedElbowsAndWrists()=runBlocking {
        val mesh=RailClearance(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        for(wrist in listOf(20,27))assertTrue("No palm attached to J$wrist",mesh.wristVertices(wrist)>0)
        val body=Entity.loadSuspend("asset://ridge/runner.glb")
        withContext(Dispatchers.Main){
            try {
                val skin=body.findSkinnedMeshEntity().first()
                val pose=SkeletonPoseComponent()
                skin.components.set(pose)
                val neutral=pose.getJointNames().zip(pose.getJointTransforms()).toMap()
                for(name in listOf("J19","J20","J26","J27")){
                    val length=neutral.getValue(name).position.length()/Network.BIND_UNIT
                    assertTrue("$name bind length $length",length in 14f..36f)
                }
            } finally {body.destroy(true)}
        }
        // Optional exact inference replay for offline inspection of the skinned GLB.
        if(InstrumentationRegistry.getArguments().getString("captureArms")=="true"){
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val course=Course.load(context.assets)
            val network=Network(context.assets)
            for(passage in listOf(Passage.LEVEL,Passage.BEAM,Passage.DUCK)){
                network.reset()
                val t=Trial(course,passage.route,network)
                DataOutputStream(context.filesDir.resolve("arm-${passage.name}.bin").outputStream().buffered()).use{out->
                    while(!t.done && t.ticks<2500){
                        t.step()
                        requireNotNull(t.pose.output).forEach(out::writeFloat)
                    }
                }
            }
        }
    }
}
