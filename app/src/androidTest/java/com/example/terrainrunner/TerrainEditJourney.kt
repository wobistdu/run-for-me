package com.example.terrainrunner

import android.util.Log
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrainrunner.platform.LaunchActivity
import com.example.terrainrunner.platform.SpatialApplication
import com.pico.spatial.core.ecs.*
import java.io.FileInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production preview/commit path with the real imported terrain. */
class TerrainEditJourney {
    private inline fun <T> timed(label:String,block:()->T):T{
        val start=java.lang.System.nanoTime()
        return block().also{Log.i("TerrainEditJourney","$label ms=${(java.lang.System.nanoTime()-start)/1e6}")}
    }
    @Test fun repeatedPreviewCommitAndRestore()=runBlocking {
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val original=Course.load(assets)
        val session=RidgeSession(Network(assets),original)
        val root=Entity.loadSuspend("asset://ridge/ridge.glb")
        withContext(Dispatchers.Main){
            fun nodes(e:Entity):Sequence<Entity> = sequence{
                yield(e);for(child in e.getChildren())yieldAll(nodes(child))
            }
            val owner=nodes(root).first{it.components.get<ModelComponent>()!=null}
            val model=requireNotNull(owner.components.get<ModelComponent>())
            val before=model.mesh.getPositions()
            val uv=model.mesh.getUv0()
            val normals=model.mesh.getNormals()
            val indices=model.mesh.getTriangleIndices()
            val ground=SculptedGround(owner)
            val grass=GrassTufts(original)
            root.addChild(grass.entity)
            session.editMode()
            try{
                ground.paint(original,true)
                repeat(3){round->
                    for(tool in Tool.entries){
                        session.tool=tool
                        val start=Course.local(-.23f,.27f)
                        val stroke=timed("$round/$tool/stroke"){Course.Stroke(session.course,tool,start)}
                        timed("$round/$tool/preview"){ground.paint(stroke.draft,false)}
                        timed("$round/$tool/grass"){grass.followGround(stroke.draft)}
                        stroke.extend(start.toward(Course.local(-.18f,.24f),.5f))
                        timed("$round/$tool/drag"){ground.paint(stroke.draft,false)}
                        val deformed=model.mesh.getPositions()
                        for(i in before.indices step 3){
                            assertEquals(before[i],deformed[i],0f)
                            assertEquals(before[i+2],deformed[i+2],0f)
                            assertEquals(before[i+1]+Course.CELL*stroke.draft.displacement(
                                Course.local(before[i],before[i+2])),deformed[i+1],1e-6f)
                        }
                        assertArrayEquals(indices,model.mesh.getTriangleIndices())
                        assertArrayEquals(uv,model.mesh.getUv0(),0f)
                        assertArrayEquals(normals,model.mesh.getNormals(),0f)
                        timed("$round/$tool/commit"){session.commit(stroke);ground.paint(session.course,true)}
                        assertSame(stroke.draft,session.course)
                        timed("$round/$tool/restore"){session.restore();ground.paint(session.course,true)}
                        assertSame(original,session.course)
                        assertArrayEquals(before,model.mesh.getPositions(),0f)
                    }
                }
            }finally{root.destroy(true);grass.close();ground.close()}
        }
    }

    @Test fun mountedTerrainPreviewCommitUndoAndRestore()=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as SpatialApplication
        context.startActivity(Intent(context,LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run=app.activeRun()
        val session=run.ready.await()
        val field=ActiveRun::class.java.getDeclaredField("scene").apply{isAccessible=true}
        val view=withTimeout(45000){
            var mounted:RidgeScene?=null
            while(mounted==null){
                mounted=withContext(Dispatchers.Main){field.get(run) as? RidgeScene}
                if(mounted==null)delay(100)
            }
            mounted
        }
        suspend fun capture(name:String){
            delay(500)
            instrumentation.uiAutomation.executeShellCommand("screencap -p").use{descriptor->
                FileInputStream(descriptor.fileDescriptor).use{input->
                    context.filesDir.resolve("terrain-$name.png").outputStream().use{input.copyTo(it)}
                }
            }
        }
        withContext(Dispatchers.Main){session.leaveExpedition();session.paused=true;session.editMode();session.restore()}
        capture("before")
        val original=session.course
        try{
            for(tool in Tool.entries){
                val stroke=withContext(Dispatchers.Main){
                    session.tool=tool
                    Course.Stroke(session.course,tool,Course.local(-.23f,.27f))
                }
                repeat(10){i->
                    withContext(Dispatchers.Main){
                        stroke.extend(Course.local(-.23f+i*.006f,.27f-i*.003f))
                        timed("mounted/$tool/preview-$i"){view.paint(stroke.draft,false)}
                    }
                    delay(130) // Same preview interval as the actual spatial pointer handler.
                }
                withContext(Dispatchers.Main){
                    timed("mounted/$tool/commit"){session.commit(stroke);view.paint(session.course,true)}
                }
                capture(tool.name.lowercase())
                withContext(Dispatchers.Main){
                    session.undo();view.paint(session.course,true)
                    assertSame(original,session.course)
                }
                delay(150)
            }
        }finally{
            withContext(Dispatchers.Main){
                session.restore();view.paint(session.course,true);session.finishEditing();session.paused=false
            }
        }
    }
}
