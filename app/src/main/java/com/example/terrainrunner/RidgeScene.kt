package com.example.terrainrunner

import android.util.Log
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import kotlin.math.*

internal val Entity.tr get()=requireNotNull(components.get<TransformComponent>())
internal fun Entity.tree():Sequence<Entity> = sequence { yield(this@tree);for(child in getChildren())yieldAll(child.tree()) }

internal class RidgeScene(private val content:SpatialViewContent,val state:RidgeSession,land:Entity,bodies:Map<RunnerStyle,Entity>,private val panel:Entity,
    private val board:Entity,private val boardWidth:Float,badgeModels:Map<TrailBadge,Entity>){
    private val badges=TrailBadges(board,boardWidth/com.example.terrainrunner.content.BOARD_WIDTH_DP,badgeModels)
    internal val badgeHit get()=badges.previewHit
    private val mount=Entity()
    private val island=Entity()
    private val actor=Entity()
    private val grasses=GrassTufts(state.course)
    private val expeditionScenery=ExpeditionScenery(island,state)
    internal fun starIndex(target:Entity?)=expeditionScenery.starIndex(target)
    internal fun focusStar(index:Int){expeditionScenery.focusedStar=index}
    internal val ground=land.tree().first{it.components.get<ModelComponent>()!=null}
    private val groundMesh=SculptedGround(ground)
    private val vertices=groundMesh.vertices
    private class Body(val root:Entity){
        private val entities=root.tree().toList()
        fun setVisible(visible:Boolean){entities.forEach{it.enabled=visible}}
        val skin=root.findSkinnedMeshEntity().single()
        val skeleton=SkeletonPoseComponent().also{skin.components.set(it)}
        val order=skeleton.getJointNames().map{it.substringAfterLast("J").toInt()}.toIntArray()
        val bind=skeleton.getJointTransforms()
        val knees=if(requireNotNull(skin.components.get<ModelComponent>()).mesh.getBlendShapeNames().isEmpty())null
            else skin.components.get<BlendShapeControllerComponent>() ?: BlendShapeControllerComponent().also{skin.components.set(it)}
        init{require(order.toSet()==(0..30).toSet() && bind.size==31)}
    }
    // Clone while both imported hierarchies are enabled and still in their authored rest pose.
    private val previews=bodies.mapValues{(_,root)->
        requireNotNull(root.clone(Entity.CloneOptions(recursive=true,shouldShareMaterialInstance=true)))
    }
    private val runners=bodies.mapValues{Body(it.value)}
    private var active=state.runner
    private val body get()=runners.getValue(active)
    private val previewStand=Entity()
    private val turntable=Entity()
    internal val previewHit=Entity()
    private var displayedPreview:RunnerStyle?=null
    private var previewYaw=0f
    private var panelHeight=0f
    private var boardHeight=0f
    private val dotMesh=MeshResource.createSphere(.5f).apply{toGlobal()}
    private val dotMat=UnlitMaterial.create().apply{toGlobal();setBaseColor(Color4(1f,.74f,.23f,1f))}
    private val flagMesh=waypointFlagMesh()
    private val markerMat=UnlitMaterial.create().apply{toGlobal()}
    private val markers=mutableListOf<ModelEntity>()
    private var markerCourse:Course?=null
    private val trails=mutableMapOf<RidgeSession.FlagLeg,Pair<ModelEntity,MeshResource>>()
    private var shownLegs:List<RidgeSession.FlagLeg>?=null
    private val predicted=PfnnSkeleton()
    private val brush=Entity()
    private val dots=List(24){ModelEntity(dotMesh,dotMat).also{e->brush.addChild(e);e.tr.scaleVector=Vector3(.0025f)}}
    init{
        content.addEntity(mount);mount.addChild(island);island.addChild(land);island.addChild(grasses.entity)

        island.addChild(actor);actor.tr.scaleVector=Vector3(Network.BODY_SCALE)
        runners.forEach{(style,model)->actor.addChild(model.root);model.setVisible(style==active)}
        mount.addChild(previewStand)
        previewStand.addChild(turntable)
        previews.values.forEach{model->turntable.addChild(model);model.tree().forEach{it.enabled=false}}
        previewStand.addChild(previewHit)
        val bindHeight=160f*Network.BIND_UNIT
        previewHit.tr.position=Vector3(0f,bindHeight*.5f,0f)
        previewHit.components.set(CollisionComponent(
            listOf(ShapeResource.createBox(Vector3(bindHeight*.85f,bindHeight*1.05f,bindHeight*.38f))),
            PhysicsMaterialResource()))
        previewHit.components.set(InteractableComponent())
        previewStand.enabled=false
        mount.addChild(board)
        mount.addChild(panel);panel.tr.scaleVector=Vector3(1.15f)
        island.addChild(brush);brush.enabled=false
        ground.components.set(InteractableComponent())
        paint(state.course,true)
        val bodyMaterial=requireNotNull(body.skin.components.get<ModelComponent>()).materials[0]
        Log.i("RidgeScene","runnerMaterial=${bodyMaterial.javaClass.simpleName}")
        Log.i("RidgeScene","groundTriangles=${groundMesh.triangleCount} grassTriangles=${grasses.triangleCount} runnerTriangles=${requireNotNull(body.skin.components.get<ModelComponent>()).mesh.getTriangleIndices().size/3} mappedJoints=${body.order.size} style=$active")
    }
    private fun updatePreview(){
        val next=if(state.choosingRunner)state.candidate else null
        if(next==displayedPreview)return
        displayedPreview?.let{previews.getValue(it).tree().forEach{node->node.enabled=false}}
        next?.let{previews.getValue(it).tree().forEach{node->node.enabled=true}}
        previewStand.enabled=next!=null
        if(displayedPreview==null){previewYaw=0f;turnPreview(0f)}
        Log.i("RidgePreview","candidate=$next")
        displayedPreview=next
    }
    internal fun turnPreview(horizontalDp:Float){
        previewYaw=(previewYaw+horizontalDp*.9f)%360f
        turntable.tr.eulerAngles=EulerAngles(0f,previewYaw,0f)
    }
    internal fun finishPreviewDrag(){Log.i("RidgePreview","drag ended yaw=$previewYaw")}
    internal fun resizeDesk(height:Float){
        if(height==panelHeight)return
        panelHeight=height
        if(previousHalf.y>0f){placeDesk(previousHalf);placePreview(previousHalf)}
    }
    internal fun resizeBoard(height:Float){
        if(height==boardHeight)return
        boardHeight=height
        if(previousHalf.y>0f)placeBoard(previousHalf)
    }
    private fun placeDesk(half:Vector3){
        // Restore the original compact desk size; it must not grow with the terrain width.
        panel.tr.scaleVector=Vector3(minOf(1.15f,(2*half.y-.08f)/panelHeight.coerceAtLeast(.001f)))
        // The whole panel lives in the reserved front lane, outside the terrain footprint.
        val bottomSafe=-half.y+.015f+panelHeight*panel.tr.scaleVector.y*.5f
        panel.tr.position=Vector3(0f,max(island.tr.position.y+.10f,bottomSafe),half.z-.035f)
    }
    private fun placeBoard(half:Vector3){
        val islandScale=island.tr.scaleVector.x
        val span=minOf(.60f*Course.WIDTH*islandScale,half.x*1.65f)
        val bottom=island.tr.position.y+maxY*islandScale+.07f
        val nativeHeight=boardHeight.takeIf{it>0f}?:boardWidth
        val scale=minOf(span/boardWidth,(half.y-.035f-bottom).coerceAtLeast(.05f)/nativeHeight)
        badges.place(boardWidth,nativeHeight)
        board.tr.scaleVector=Vector3(scale)
        board.tr.position=Vector3(0f,bottom+nativeHeight*scale*.5f,
            island.tr.position.z-.25f*Course.WIDTH*islandScale)
        Log.i("RidgeScene","runBoard width=${boardWidth*scale} bottom=$bottom top=${bottom+nativeHeight*scale}")
    }
    private fun placePreview(half:Vector3){
        val previewScale=2f*Network.BODY_SCALE*island.tr.scaleVector.y
        previewStand.tr.scaleVector=Vector3(previewScale)
        val height=160f*Network.BIND_UNIT*previewScale
        val desk=panel.tr.position
        // Keep the front of the hit box inside the Volume, just behind the desk plane.
        previewStand.tr.position=Vector3(desk.x,
            minOf(half.y-height-.004f,desk.y+.5f*panelHeight*panel.tr.scaleVector.y+.012f),
            minOf(desk.z,half.z-height*.20f-.004f))
    }
    fun hit(entity:Entity?,pixel:Vector3):Point?{
        if(entity?.id!=ground.id)return null
        val view=content.convertPosition(pixel,ViewCoordinateSpace.Local,content.localSpatialCoordinateSpace)
        val p=island.convertPositionFrom(view,mount)
        return Course.local(p.x,p.z)
    }
    fun cursor(p:Point?,c:Course){
        brush.enabled=p!=null && state.editing
        if(p==null)return
        dots.forEachIndexed{i,e->
            val a=i*2*PI/24
            val q=Point(p.x+cos(a).toFloat()*Course.RADIUS,p.z+sin(a).toFloat()*Course.RADIUS)
            e.tr.position=Vector3(q.mx,c.height(q)*Course.CELL+.001f,q.mz)
        }
    }
    private var grassCourse=state.course
    fun paint(c:Course,commit:Boolean){
        groundMesh.paint(c,commit)
        if(grassCourse!==c){terrainEditStage("grass"){grasses.followGround(c)};grassCourse=c}
    }
    private var fitCounter=0
    private var previousHalf=Vector3.ZERO
    private val minY=vertices.minOf{it.y}-.65f*Course.CELL
    private val maxY=vertices.maxOf{it.y}+1.8f*Course.CELL+Course.RUNNER_HEIGHT*1.5f
    private fun fit(){
        // This unpadded SpatialView is the entire WindowContainer. Query its actual
        // local clipping region, including when the compositor restores a saved size.
        val corner=content.convertPosition(Vector3.ZERO,ViewCoordinateSpace.Local,content.localSpatialCoordinateSpace)
        val half=Vector3(abs(corner.x),abs(corner.y),abs(corner.z))
        if(half==previousHalf || minOf(half.x,half.y,half.z)<=0f)return
        previousHalf=half
        val frontLane=minOf(.22f,half.z*.25f)
        val scale=minOf(min(half.x*2,half.z*2-frontLane)*Course.FLOOR_FILL/Course.WIDTH,
            (2*half.y-.10f)/(maxY-minY))
        if(scale<=0f)return
        island.tr.scaleVector=Vector3(scale)
        // The square plinth rests on the horizontal floor, independently of its relief.
        val baseY=vertices.minOf{it.y}
        val centerY=-half.y+.002f-baseY*scale
        island.tr.position=Vector3(0f,centerY,-frontLane*.5f)
        placeDesk(half)
        placePreview(half)
        placeBoard(half)
        Log.i("RidgeScene","deskTerrainGap=${panel.tr.position.z-island.tr.position.z-Course.WIDTH*.5f*scale}")
        Log.i("RidgeScene","floor=${2*half.x}x${2*half.z} terrain=${Course.WIDTH*scale}x${Course.WIDTH*scale} runner=${Course.RUNNER_HEIGHT*scale} headroom=${half.y-centerY-maxY*scale}")
    }
    private fun updateMarkers(){
        val points=state.visiblePoints
        val legs=state.legs
        if(shownLegs===legs && state.course===markerCourse)return
        val keep=legs.toHashSet()
        val iter=trails.iterator()
        while(iter.hasNext()){
            val entry=iter.next()
            if(entry.key !in keep || state.course!==markerCourse){
                entry.value.first.destroy(true);entry.value.second.close();iter.remove()
            }
        }
        for(leg in legs)trails.getOrPut(leg){
            val mesh=flagTrail(leg.path,state.course)
            val entity=ModelEntity(mesh,markerMat)
            island.addChild(entity)
            entity to mesh
        }
        shownLegs=legs;markerCourse=state.course
        while(markers.size<points.size){
            val flag=ModelEntity(flagMesh,markerMat)
            island.addChild(flag);markers.add(flag)
        }
        markers.forEachIndexed{i,flag->
            flag.enabled=i<points.size
            if(i<points.size){
                val p=points[i]
                flag.tr.scaleVector=Vector3(if(i==0)1.15f else 1f)
                flag.tr.position=Vector3(p.mx,state.course.height(p)*Course.CELL,p.mz)
            }
        }
    }
    private data class Frame(val root:Vector3,val yaw:Float,val joints:List<Transform>,val knee0:Float,val knee1:Float)
    private val actorTransform=actor.tr
    private val unitScale=Vector3(1f)
    private var last:Pose?=null
    private var posedGround:Course?=null
    private var owner:Trial?=null
    private var before:Frame?=null
    private var after:Frame?=null
    private var shown:Frame?=null
    private var shownAlpha=-1f
    private var shownYaw=Float.NaN

    internal val submittedRoot get()=actorTransform.position

    fun resetInterpolation(){owner=null;before=null;after=null}

    // Keep the last two corrected simulation poses, even when one callback advances twice.
    fun capturePose(){
        if(active!=state.runner){
            body.setVisible(false)
            active=state.runner;body.setVisible(true)
            resetInterpolation()
        }
        val trial=state.trial
        if(last===trial.pose && posedGround===state.course && owner===trial)return
        val continuous=owner===trial && posedGround===state.course
        owner=trial;posedGround=state.course;last=trial.pose
        val p=trial.position
        val displayed=if(trial.swingCorrected)trial.swingSkeleton else predicted.apply {
            decode(trial.pose.output?:state.network.idle)
        }
        val positions=displayed.positions;val rotations=displayed.rotations
        val joints=body.order.mapIndexed{slot,i->
            val parent=state.network.parents[i]
            if(parent<0)Transform(positions[i]*Network.BIND_UNIT,rotations[i],unitScale)
            else{
                val inv=rotations[parent].conjugate()
                // Keep the skinned elbow and wrist joined at the model's hinges as the PFNN rotates them.
                val offset=if(active.bindOffset(i))body.bind[slot].position
                    else inv.rotateVector((positions[i]-positions[parent])*Network.BIND_UNIT)
                Transform(offset,inv*rotations[i],unitScale)
            }
        }
        val next=Frame(Vector3(p.mx,trial.displayBaseY,p.mz),atan2(trial.heading.x,trial.heading.z),joints,
            if(body.knees!=null)displayed.kneeFlex(0) else 0f,if(body.knees!=null)displayed.kneeFlex(1) else 0f)
        before=if(continuous)after else next
        after=next
    }

    fun animate(fraction:Float):Boolean{
        if(fitCounter++%30==0)fit()
        updatePreview()
        updateMarkers()
        if(board.enabled!=state.boardVisible)board.enabled=state.boardVisible
        if(state.boardVisible)badges.update(state.earnedDays,state.selectedBadge,state.badgeYaw,!state.showLeaderboard)
        expeditionScenery.update()
        capturePose()
        val end=after?:return false
        if(state.paused)before=end
        val start=before?:end
        val t=if(start===end)1f else fraction.coerceIn(0f,1f)
        if(shown===end && shownAlpha==t)return false
        shown=end;shownAlpha=t
        actorTransform.position=Vector3.lerp(start.root,end.root,t)
        val turn=end.yaw-start.yaw
        val yaw=if(t==1f)end.yaw else start.yaw+t*atan2(sin(turn),cos(turn))
        if(yaw!=shownYaw){
            actorTransform.eulerAngles=EulerAngles(0f,yaw*57.29578f,0f);shownYaw=yaw
        }
        val joints=if(t==1f)end.joints else if(t==0f)start.joints else end.joints.mapIndexed{i,b->
            val a=start.joints[i]
            Transform(Vector3.lerp(a.position,b.position,t),Quat.slerp(a.quaternion,b.quaternion,t),unitScale)
        }
        check(body.skeleton.setJointTransforms(joints))
        body.knees?.setBlendShapeWeights(listOf(start.knee0+t*(end.knee0-start.knee0),start.knee1+t*(end.knee1-start.knee1)))
        return true
    }
    fun close(){mount.destroy(true);trails.values.forEach{it.second.close()};trails.clear();expeditionScenery.close();badges.close();grasses.close();groundMesh.close();dotMesh.close();dotMat.close();flagMesh.close();markerMat.close()}
}
