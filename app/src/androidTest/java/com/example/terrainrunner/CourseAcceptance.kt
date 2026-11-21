package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CourseAcceptance {
    private val assets get()=InstrumentationRegistry.getInstrumentation().targetContext.assets
    private fun session()=RidgeSession(Network(assets),Course.load(assets))
    private fun logPose(k:Int,sample:Int,t:Trial,original:Boolean=false){
        val skeleton=if(t.swingCorrected && !original)t.swingSkeleton else PfnnSkeleton().apply{decode(requireNotNull(t.pose.output))}
        val ground=t.displayBaseY
        val bones=skeleton.positions.indices.joinToString(" "){j->
            val v=skeleton.positions[j];val q=skeleton.rotations[j]
            "${v.x},${v.y},${v.z},${q.x},${q.y},${q.z},${q.w}"
        }
        Log.i("RailPose","$k $sample ${t.position.mx} $ground ${t.position.mz} ${t.heading.x} ${t.heading.z} $bones")
    }
    @Test fun ridgeTraversalAndFocusedLoops(){
        val s=session()
        for(segment in Passage.entries){
            s.replay(segment)
            val labels=mutableSetOf<String>()
            var previous=0f
            var ascent=0f;var descent=0f
            while(!s.trial.done && !s.paused && s.trial.ticks<15000){
                s.step()
                val y=s.course.height(s.trial.position)*Course.CELL
                if(s.trial.ticks>1){ascent+=max(0f,y-previous);descent+=max(0f,previous-y)}
                previous=y
                labels+=s.caption
            }
            assertTrue("unfinished: $segment",s.trial.done)
            if(segment==Passage.ALL){
                assertTrue(ascent>.035f && descent>.035f)
                assertTrue(labels.containsAll(listOf("攀上岩阶","下坡落脚","平地跑动")))
            }
            val end=s.trial
            s.step()
            assertSame(end,s.trial)
            assertFalse(s.trial.done)
            assertEquals(segment,s.passage)
            s.paused=true
            val ticks=s.trial.ticks;s.step()
            assertEquals(ticks,s.trial.ticks)
            Log.i("RidgeTest","$segment ascent=$ascent descent=$descent labels=$labels finished=true")
        }
    }
    @Test fun railsAndDescentDecodeThePredictedSkeleton(){
        val s=session()
        val decoded=PfnnSkeleton()
        for(segment in listOf(Passage.RAILS,Passage.DESCENT)){
            s.replay(segment)
            var frames=0
            while(!s.trial.done && !s.paused && frames++<4000){
                s.step()
                val y=requireNotNull(s.trial.pose.output)
                decoded.decode(y)
                for(i in 0 until 31){
                    val joint=decoded.positions[i];val offset=32+i*3
                    assertEquals(y[offset],joint.x,0f)
                    assertEquals(y[offset+1],joint.y,0f)
                    assertEquals(y[offset+2],joint.z,0f)
                    val r=218+i*3
                    val a=sqrt(y[r]*y[r]+y[r+1]*y[r+1]+y[r+2]*y[r+2])
                    val k=if(a<1e-7f)1f else sin(a)/a
                    val q=decoded.rotations[i]
                    assertEquals(y[r]*k,q.x,.00001f);assertEquals(y[r+1]*k,q.y,.00001f)
                    assertEquals(y[r+2]*k,q.z,.00001f);assertEquals(cos(a),q.w,.00001f)
                }
            }
            assertTrue("$segment frames=$frames",s.trial.done && frames>10)
        }
    }
    @Test fun railMeshClearanceAndScreeTurn(){
        val s=session();val fixtures=requireNotNull(s.course.crossings)
        val mesh=RailClearance(assets)
        // Removing a hurdle also removes its posts, takeoff geometry and gait cue.
        val removed=Course.local(fixtures.laneX,.004f)
        assertEquals(1,fixtures.bars.size)
        val wall=fixtures.walls.single()
        assertFalse(s.course.supports(Course.local(fixtures.laneX,wall.z)))
        assertEquals(0f,s.course.jump(removed),0f)
        assertEquals(fixtures.laneY,s.course.height(removed)*Course.CELL,.00001f)
        assertTrue(s.course.supports(Course.local(fixtures.laneX+fixtures.halfSpan,.004f)))
        val record=InstrumentationRegistry.getArguments().getString("capturePoses")=="true"
        val bridge=fixtures.bars.size+1
        val captures=IntArray(bridge+1)
        val minima=FloatArray(bridge){Float.POSITIVE_INFINITY};val frames=IntArray(bridge)
        // Include both a cold start and the phase carried into the next lap.
        for(lap in 0..2){
            do{
                s.step()
                val t=s.trial
                if(record && lap==0){
                    for(k in captures.indices){
                        val sample=captures[k]
                        if(sample>=5)continue
                        val inFrame=if(k==bridge){
                            abs(t.position.mz-fixtures.centerZ)<.001f &&
                                t.position.mx in -.145f..-.060f && t.position.mx>=-.14f+.007f*sample
                        }else{
                            val d=t.position.mz-(if(k==0)wall.z else fixtures.bars[k-1].z)
                            abs(t.position.mx-fixtures.laneX)<.004f && d<.016f-.008f*sample && d>-.024f
                        }
                        if(inFrame){
                            logPose(k,sample,t)
                            captures[k]++
                        }
                    }
                }
                if(abs(t.position.mz-wall.z)<=wall.depth+.003f && abs(t.position.mx-fixtures.laneX)<.05f){
                    minima[0]=min(minima[0],mesh.bypassGap(t,wall));frames[0]++
                }
                for((i,bar) in fixtures.bars.withIndex()){
                    if(abs(t.position.mx-fixtures.laneX)>.01f || abs(t.position.mz-bar.z)>.022f)continue
                    val gap=mesh.sample(t,fixtures,bar)
                    if(gap.isFinite()){minima[i+1]=min(minima[i+1],gap);frames[i+1]++}
                }
            }while(!s.trial.done && !s.paused && s.trial.ticks<23000)
            assertTrue("lap $lap",s.trial.done)
        }
        Log.i("RidgeTest","rail clearances local metres=${minima.toList()} sampled frames=${frames.toList()}")
        assertTrue(s.course.supports(Course.local(-.29f,.036f)))
        assertTrue(s.course.supports(Course.local(-.27f,.095f)))
        var turn=0f
        for((a,b,c) in Passage.ROUGH.route.windowed(3)){
            val ux=b.x-a.x;val uz=b.z-a.z;val vx=c.x-b.x;val vz=c.z-b.z
            turn=max(turn,abs(atan2(ux*vz-uz*vx,ux*vx+uz*vz)))
            assertTrue(s.course.supports(b))
        }
        Log.i("RidgeTest","scree max heading increment=${turn*180/Math.PI}")
        assertTrue("turn=$turn",turn<.12f)
        assertTrue("gaps=${minima.toList()}",frames.all{it>0} && minima.all{it>=0f})
    }
    @Test fun bridgeStanceSamplesFollowTheLogCrown(){
        val s=session();val fixtures=requireNotNull(s.course.crossings)
        val samples=mutableListOf<Float>()
        val sole=mutableListOf<Float>();val mesh=RailClearance(assets)
        s.replay(Passage.BEAM)
        while(!s.trial.done && s.trial.ticks<2000){
            s.step()
            val t=s.trial;val y=requireNotNull(t.pose.output)
            if(abs(t.position.mx+.1f)>.03f)continue
            for(foot in 0..1){
                if(max(y[4+2*foot],y[5+2*foot])<.65f)continue
                val ankle=32+3*(4+5*foot)
                val side=y[ankle]*Network.MODEL_TO_METRES
                val shoe=Course.local(t.position.mx+y[ankle+2]*Network.MODEL_TO_METRES,t.position.mz-side)
                samples+=abs(shoe.mz-fixtures.centerZ)
                sole+=mesh.soleGap(t,foot)
                assertTrue(fixtures.onDeck(shoe))
                assertEquals(fixtures.deckHeight(s.course,shoe)*Course.CELL,s.course.height(shoe)*Course.CELL,.00001f)
            }
        }
        Log.i("RidgeTest","bridge soleGap=${sole.minOrNull()}..${sole.maxOrNull()} stance samples=${samples.size} max ankle offset=${samples.maxOrNull()} half-width=${fixtures.halfWidth}")
        assertTrue(samples.size>10)
        assertTrue("stance shoe penetration: ${sole.minOrNull()}",sole.minOrNull()!! >= 0f)
    }

    @Test fun swingLegClearance(){
        val s=session();val mesh=RailClearance(assets)
        val raw=PfnnSkeleton()
        val record=InstrumentationRegistry.getArguments().getString("capturePoses")=="true"
        for(segment in listOf(Passage.CLIMB,Passage.DESCENT,Passage.ROUGH,Passage.ALL)){
            s.replay(segment)
            val worst=FloatArray(3){Float.POSITIVE_INFINITY}
            var samples=0;var changed=0;var originalWorst=0f;var maxBoneError=0f
            while(!s.trial.done && s.trial.ticks<10000){
                s.step();val t=s.trial;val y=t.pose.output!!
                if(segment==Passage.ALL){
                    val p=t.position
                    val ridge=p.mx in -.17f.. .215f && abs(p.mz+.106f+.21f*p.mx)<.02f
                    val scree=p.mx in -.32f..-.198f && p.mz in .225f.. .28f
                    if(!ridge && !scree)continue
                }
                raw.decode(y)
                val shown=if(t.swingCorrected)t.swingSkeleton else raw
                for(j in 11..30){
                    assertEquals(raw.positions[j],shown.positions[j])
                    assertEquals(raw.rotations[j],shown.rotations[j])
                }
                for(foot in 0..1){
                    val hip=2+5*foot;val ankle=hip+2
                    val confidence=max(y[4+2*foot],y[5+2*foot])
                    if(confidence>=.65f){
                        for(j in hip..ankle+1){
                            assertEquals(raw.positions[j],shown.positions[j])
                            assertEquals(raw.rotations[j],shown.rotations[j])
                        }
                        continue
                    }
                    assertEquals(raw.rotations[ankle],shown.rotations[ankle])
                    assertEquals(raw.positions[hip],shown.positions[hip])
                    for(j in hip..hip+1){
                        val before=(raw.positions[j]-raw.positions[j+1]).length()
                        val after=(shown.positions[j]-shown.positions[j+1]).length()
                        maxBoneError=max(maxBoneError,abs(before-after))
                    }
                    val gaps=mesh.swingGaps(t,foot)
                    for(k in gaps.indices)worst[k]=min(worst[k],gaps[k])
                    samples++
                    if((shown.positions[ankle]-raw.positions[ankle]).length()>.0001f){
                        changed++
                        if(record && segment!=Passage.ALL){
                            val gap=mesh.swingGaps(t,foot,true).min()
                            if(gap<originalWorst-.00012f){
                                logPose(20+segment.ordinal,t.ticks,t,true)
                                logPose(40+segment.ordinal,t.ticks,t)
                                Log.i("SwingView","group=${segment.ordinal} tick=${t.ticks} foot=$foot gap=$gap")
                                originalWorst=gap
                            }
                        }
                    }
                }
            }
            Log.i("SwingTest","$segment samples=$samples changed=$changed minShinAnkleToe=${worst.toList()} boneError=$maxBoneError")
            assertTrue(s.trial.done && changed in 1 until samples)
            assertTrue("$segment bone lengths $maxBoneError",maxBoneError<.001f)
            assertTrue("$segment swing gaps ${worst.toList()}",worst.all{it>=-1e-6f})
        }
    }

    @Test fun ridgeSoleContact(){
        val s=session();val mesh=RailClearance(assets)
        val rawNetwork=Network(assets)
        val record=InstrumentationRegistry.getArguments().getString("capturePoses")=="true"
        for(segment in listOf(Passage.CLIMB,Passage.DESCENT,Passage.ROUGH,Passage.ALL)){
            s.replay(segment)
            rawNetwork.reset()
            val raw=Trial(s.course,segment.route,rawNetwork)
            val gaps=mutableListOf<Float>()
            var doubleSupport=0
            var worstPenetration=0f;var worstHover=0f
            while(!s.trial.done && s.trial.ticks<12000){
                s.step();raw.step()
                val t=s.trial;val y=requireNotNull(t.pose.output)
                assertArrayEquals("PFNN frame ${t.ticks}",raw.pose.output!!,y,0f)
                assertEquals(raw.position,t.position)
                val p=t.position
                if(segment==Passage.ALL){
                    val ridge=p.mx in -.17f.. .215f && abs(p.mz+.106f+.21f*p.mx)<.02f
                    val scree=p.mx in -.32f..-.198f && p.mz in .225f.. .28f
                    if(!ridge && !scree)continue
                }
                var contacts=0
                for(foot in 0..1)if(max(y[4+foot*2],y[5+foot*2])>.65f){
                    val gap=mesh.soleGap(t,foot)
                    gaps+=gap;contacts++
                    val originalGap=gap-(t.displayBaseY-raw.predictedBaseY)
                    if(record && segment!=Passage.ALL &&
                        (originalGap<worstPenetration-.00015f || originalGap>worstHover+.00015f)){
                        val group=10+segment.ordinal
                        logPose(group,t.ticks,t)
                        Log.i("TerrainView","$group ${t.ticks} $originalGap $gap ${raw.predictedBaseY}")
                        worstPenetration=min(originalGap,worstPenetration);worstHover=max(originalGap,worstHover)
                    }
                }
                if(contacts==2)doubleSupport++
            }
            assertTrue(s.trial.done && raw.done && gaps.size>10 && gaps.all{it.isFinite()})
            Log.i("TerrainContact","$segment range=${gaps.minOrNull()}..${gaps.maxOrNull()} supports=${gaps.size} double=$doubleSupport identicalFrames=${raw.ticks}")
            assertTrue("$segment penetration ${gaps.minOrNull()}",gaps.minOrNull()!! >= -1e-6f)
            assertTrue("$segment hover ${gaps.maxOrNull()}",gaps.maxOrNull()!! < .00020f)
        }
    }

    @Test fun continuousStrokeIsOneUndo(){
        val s=session()
        s.editMode()
        val original=s.course
        val start=Point(4f,6f);val end=Point(11f,5f)
        for(tool in Tool.entries){
            s.tool=tool
            val long=Course.Stroke(original,tool,start)
            long.extend(end)
            val many=Course.Stroke(original,tool,start)
            for(i in 1..100)many.extend(start.toward(end,i/100f))
            many.extend(start)
            assertArrayEquals(long.draft.heights,many.draft.heights,.00001f)
            assertTrue(abs(original.height(end)-long.draft.height(end))>.4f)
            s.commit(long);s.commit(long)
            assertSame(long.draft,s.course)
            s.undo();assertSame(original,s.course)
        }
        s.finishEditing()
        assertFalse(s.editing)
    }
    @Test fun visitPointsInOrderEvenWhenReturningToStart(){
        val s=session()
        s.editPoints()
        val a=Point(5f,6.3f);val b=Point(9f,5.7f)
        s.append(a);s.append(a);s.append(b);s.append(a)
        assertEquals(listOf(a,b,a),s.visiblePoints)
        assertEquals(listOf(a,b,a),s.legs.map{it.path.last()})
        assertEquals(listOf(s.trial.position,a,b),s.legs.map{it.path.first()})
        s.exitPoints()
        assertTrue(s.caption,s.following)
        assertFalse(s.placing)
        val visits=mutableListOf<Point>()
        var count=s.visiblePoints.size
        for(i in 1..9000){
            s.step()
            if(s.visiblePoints.size!=count){
                visits.add(s.trial.position);count=s.visiblePoints.size
                assertEquals(listOf(a,b,a).takeLast(count),s.visiblePoints)
            }
            if(s.trial.done || s.paused)break
        }
        assertEquals(listOf(a,b,a),visits)
        assertTrue(s.trial.done)
        assertTrue(s.visiblePoints.isEmpty())
        val end=s.trial.position;s.step()
        assertFalse(s.following);assertFalse(s.paused)
        assertTrue(end.distance(s.trial.position)<.15f)
        s.editPoints();assertTrue(s.visiblePoints.isEmpty());s.exitPoints()
        assertEquals(.084672f,2.16f*Course.FLOOR_FILL*Course.RUNNER_HEIGHT/Course.WIDTH,.0001f)
    }
    @Test fun routeAvoidsMissingSurfaceAndRejectsDisconnectedWaypoints(){
        val samples=FloatArray(Course.SAMPLES*Course.SAMPLES)
        for(z in 0..70)for(x in 74..86)samples[z*Course.SAMPLES+x]=-.09f
        val a=Point(5f,5f);val b=Point(10f,5f)
        val course=Course.ridge(samples)
        val route=requireNotNull(WaypointRoute.leg(course,a,b))
        assertTrue(route.size>2)
        assertTrue(route.zipWithNext().all{(p,q)->WaypointRoute.walkable(course,p,q)})
        for(z in 71 until Course.SAMPLES)for(x in 74..86)samples[z*Course.SAMPLES+x]=-.09f
        val s=RidgeSession(Network(assets),Course.ridge(samples))
        s.editPoints();s.append(b);s.append(a)
        assertTrue(s.placing)
        assertTrue(s.caption.contains("没有可通行路线"))
        assertEquals(listOf(b),s.visiblePoints)
        s.removePoint();assertTrue(s.visiblePoints.isEmpty())
    }
    @Test fun paperTerrainStimuliAndPredictedPoses(){
        val s=session()
        val beam=Course.local(-.1f,.18f)
        val sampleOffset=25f/Network.UNIT
        val center=s.course.height(beam)
        for(side in listOf(-1f,1f)){
            val lateral=Point(beam.x,beam.z+side*sampleOffset)
            val drop=(center-s.course.height(lateral))*Network.UNIT
            assertTrue("bridge-side drop in PFNN units: $drop",drop in 100f..118f)
            assertFalse(s.course.supports(lateral))
        }
        assertTrue(WaypointRoute.walkable(s.course,Passage.BEAM.route.first(),Passage.BEAM.route.last()))
        assertFalse(s.course.supports(Course.local(.21f,.154f)))
        val headByTerrain=mutableMapOf<Passage,Float>()
        val armSpan=mutableMapOf<Passage,Float>()
        val lowerAnkle=mutableMapOf<Passage,Float>()
        for(segment in listOf(Passage.LEVEL,Passage.BEAM,Passage.JUMP,Passage.DUCK)){
            s.replay(segment)
            val record=InstrumentationRegistry.getArguments().getString("capturePoses")=="true"
            val phases=mutableSetOf<Int>()
            var heads=0f;var frames=0;var air=Float.NEGATIVE_INFINITY;var span=0f
            while(!s.trial.done && !s.paused && s.trial.ticks<2500){
                s.step()
                val p=s.trial.position
                val active=when(segment){
                    Passage.BEAM->abs(p.mx+.1f)<.035f
                    Passage.DUCK->abs(p.mx-.21f)<.012f
                    Passage.JUMP->abs(p.mx-.083f)<.022f
                    else->s.trial.ticks>20
                }
                if(record && segment==Passage.BEAM && s.trial.ticks%4==0)logPose(6,s.trial.ticks,s.trial)
                if(!active)continue
                if(record && segment in listOf(Passage.LEVEL,Passage.BEAM)){
                    val phase=s.trial.pose.phase
                    for((index,target) in listOf(.15f,.40f,.65f,.90f).withIndex()){
                        if(abs(phase-target)<.013f && phases.add(index))
                            logPose(if(segment==Passage.LEVEL)4 else 5,index,s.trial)
                    }
                }
                val y=requireNotNull(s.trial.pose.output)
                heads+=y[32+16*3+1];frames++
                air=max(air,min(y[32+4*3+1],y[32+9*3+1]))
                span+=abs(y[32+20*3]-y[32+27*3])
            }
            assertTrue("$segment failed",s.trial.done && frames>0)
            headByTerrain[segment]=heads/frames
            armSpan[segment]=span/frames
            lowerAnkle[segment]=air
            Log.i("RidgeTest","segment=$segment head=${heads/frames} maxLowerAnkle=$air wristSpan=${span/frames} frames=$frames")
        }
        assertTrue("bridge balance: $armSpan",armSpan.getValue(Passage.BEAM)>1.5f*armSpan.getValue(Passage.LEVEL))
        assertTrue("jump clearance: $lowerAnkle",lowerAnkle.getValue(Passage.JUMP)>lowerAnkle.getValue(Passage.LEVEL)+5f)
        assertTrue("crouched head: $headByTerrain",headByTerrain.getValue(Passage.DUCK)<headByTerrain.getValue(Passage.LEVEL)-10)
    }
    @Test fun liveEditorsPreserveMotionSpeedAndConsumeFlags(){
        val s=session()
        val original=s.course
        val trial=s.trial
        val start=trial.position
        s.editPoints()
        s.append(Course.local(-.25f,.18f))
        repeat(30){s.step()}
        assertSame(trial,s.trial);assertTrue(trial.position!=start);assertEquals(1,s.visiblePoints.size)
        while(!trial.done && !s.paused && trial.ticks<12000)s.step()
        assertTrue(trial.done);s.step()
        assertTrue(s.placing);assertTrue(s.visiblePoints.isEmpty());s.exitPoints()
        s.editMode()
        val brush=Course.Stroke(original,Tool.RAISE,Course.local(-.23f,.27f))
        val ticks=trial.ticks
        repeat(20){s.step()}
        assertTrue(trial.ticks>ticks)
        val position=trial.position;val pose=trial.pose
        s.commit(brush)
        assertSame(s.course,trial.course);assertNotSame(original,s.course)
        assertEquals(position,trial.position);assertSame(pose,trial.pose)
        s.undo();assertSame(original,trial.course)
        s.finishEditing();assertSame(trial,s.trial)
        s.faster();assertEquals(1.25f,s.speed,0f);s.slower()
        repeat(12){s.slower()};assertFalse(s.canSlow)
        repeat(12){s.faster()};assertFalse(s.canAccelerate)
        s.paused=true;s.editPoints();val before=trial.ticks;s.step()
        s.exitPoints();s.editMode();s.finishEditing()
        assertTrue(s.paused);assertEquals(before,trial.ticks)
    }
    @Test fun appendUndoAndConsumeWhileMoving(){
        val s=session()
        val start=s.trial.position
        val a=Point(start.x+.28f,start.z)
        val b=Point(start.x+.68f,start.z)
        val c=Point(start.x+1f,start.z)
        s.paused=true;s.editPoints();s.append(a)
        assertFalse(s.paused);assertTrue(s.following)
        val original=s.legs.first()
        repeat(3){s.step()}
        val current=s.trial.position
        s.append(b);s.append(c);s.removePoint()
        assertSame(original,s.legs.first());assertEquals(current,s.trial.position)
        assertEquals(listOf(a,b),s.visiblePoints)
        s.exitPoints()
        repeat(300){
            if(s.visiblePoints.size==2)s.step()
        }
        assertEquals(a,s.trial.position)
        assertEquals(listOf(b),s.visiblePoints)
        assertEquals(a,s.legs.single().path.first())
        s.editPoints();s.removePoint()
        assertTrue(s.legs.isEmpty());assertFalse(s.following);assertFalse(s.paused)
    }

    @Test fun ribbonFollowsDetourAndEditedGround()=runBlocking {
        val terrain=Course.load(assets)
        val a=Course.local(-.29f,.20f);val b=Course.local(-.24f,.26f)
        val path=listOf(a,Course.local(-.285f,.245f),b)
        val raised=Course.Stroke(terrain,Tool.RAISE,path[1]).draft
        withContext(Dispatchers.Main){
            for(c in listOf(terrain,raised)){
                val mesh=flagTrail(path,c)
                try{
                    val xyz=mesh.getPositions()
                    assertTrue(xyz.size>18 && mesh.getTriangleIndices().isNotEmpty())
                    for(i in xyz.indices step 3){
                        val p=Course.local(xyz[i],xyz[i+2])
                        assertEquals(c.height(p)*Course.CELL+.00048f,xyz[i+1],1e-6f)
                    }
                }finally{mesh.close()}
            }
        }
    }

    @Test fun restoreImportedTerrainMeshAfterConsecutiveEdits()=runBlocking {
        val original=session().course
        val brush=Course.Stroke(original,Tool.RAISE,Course.local(-.23f,.27f))
        val scene=Entity.loadSuspend("asset://ridge/ridge.glb")
        withContext(Dispatchers.Main){
            fun nodes(e:Entity):Sequence<Entity> = sequence {
                yield(e);for(child in e.getChildren())yieldAll(nodes(child))
            }
            val owner=nodes(scene).first{it.components.get<ModelComponent>()!=null}
            val model=requireNotNull(owner.components.get<ModelComponent>())
            val editable=SculptedGround(owner)
            val positions=model.mesh.getPositions()
            try{
                repeat(2){
                    editable.paint(brush.draft,false)
                    editable.paint(brush.draft,true)
                    assertFalse(positions.contentEquals(model.mesh.getPositions()))
                    editable.paint(original,true)
                    assertArrayEquals(positions,model.mesh.getPositions(),0f)
                }
            }finally{scene.destroy(true);editable.close()}
        }
    }
    @Test fun nativeMatchesReference(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bytes=instrumentation.context.assets.open("linear-phase035.bin").use{it.readBytes()}
        val buf=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val x=FloatArray(342){buf.float};val expected=FloatArray(311){buf.float}
        assertArrayEquals(expected,Network(assets).evaluate(.35f,x),.001f)
    }
}
