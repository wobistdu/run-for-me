package com.example.terrainrunner.content

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.terrainrunner.*
import com.example.terrainrunner.platform.SpatialApplication
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import com.pico.spatial.ui.platform.Material

@Composable
private fun RunStatus(s:RidgeSession){
    val speed=s.speed.toString().removeSuffix(".0")
    // Re-measuring the spatial text panel at every terrain transition competes with animation.
    val status=when{
        s.paused || s.placing || s.editing->s.caption
        s.following->"路径巡跑"
        else->"全程巡跑"
    }
    Text("$status  ·  ${speed}×",color=PicoTheme.colorScheme.labelPrimary)
}

@Composable
private fun Desk(s:RidgeSession,onHeight:(Float)->Unit){
    val density=LocalDensity.current
    val converter=LocalPhysicalLengthConverter.current
    var menuPage by remember(s){mutableStateOf(TrailPage.HOME)}
    val scope=rememberCoroutineScope()
    var preparing by remember{mutableStateOf(false)}
    var huntError by remember{mutableStateOf(false)}
    val startHunt={
        if(!preparing){
            preparing=true
            huntError=false
            scope.launch{
                try{huntError=!s.startExpedition()}
                catch(e:CancellationException){throw e}
                catch(e:Exception){Log.e("TrailMenu","Could not prepare hunt",e);huntError=true}
                finally{preparing=false}
            }
        }
    }
    Column(Modifier.width(520.dp).onSizeChanged{size->onHeight(converter.dpToLength(with(density){size.height.toDp()},LengthUnit.Meters))}.clip(RoundedCornerShape(24.dp)).backgroundMaterial(enable=true,style=Material.Regular).padding(horizontal=20.dp,vertical=16.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(s.choosingRunner){
            DeskHeading("设置主角", "${s.candidate.title} · 捏住上方模型左右转动", Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                RunnerStyle.entries.forEach{style->
                    val selected=s.candidate==style
                    Button(onClick={s.previewRunner(style)},colors=if(selected)
                        ButtonDefaults.buttonColors(containerColor=PicoTheme.colorScheme.interaction,
                            contentColor=PicoTheme.colorScheme.labelPrimaryLight) else ButtonDefaults.buttonColors()){
                        Text((if(selected)"✓ " else "")+style.title)
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Button(onClick=s::closeRunners){Text("取消")}
                Button(onClick=s::applyRunner){Text("使用主角")}
            }
            return@Column
        }
        if(preparing){
            Text("今日寻星",style=PicoTheme.typography.titleMedium)
            Text("正在山野中寻找可抵达的星位…")
            return@Column
        }
        if(huntError){
            Text("当前地形放不下三颗可抵达的星星",style=PicoTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Button(onClick=startHunt){Text("重新寻找")}
                Button(onClick={huntError=false;s.leaveExpedition()}){Text(BACK_HOME)}
            }
            return@Column
        }
        if(s.expedition!=null){ExpeditionPanel(s,startHunt);return@Column}
        if(!s.placing && !s.editing){TrailMenu(s,menuPage,{menuPage=it},startHunt);return@Column}
        if(s.placing){
            DeskHeading("插旗带路", "看向沙盘上的一块草地，拇指和食指捏一下再松开。", Modifier.fillMaxWidth())
            Text(s.caption,style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Button(onClick=s::removePoint,enabled=s.visiblePoints.isNotEmpty()){Text("撤销一旗")}
                Button(onClick=s::exitPoints){Text("退出")}
            }
        }else if(s.editing){
            DeskHeading("捏山修路", "选压低或抬高，捏住地面拖动，松手保存这一笔。", Modifier.fillMaxWidth())
            if(s.caption!="捏住拖动塑形")RunStatus(s)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                for((tool,label) in listOf(Tool.LOWER to "压低",Tool.RAISE to "抬高")){
                    Button(onClick={s.tool=tool}){Text((if(s.tool==tool)"✓ " else "")+label)}
                }
                Button(onClick=s::undo){Text("撤销")}
                Button(onClick=s::restore){Text("复原")}
                Button(onClick=s::finishEditing){Text("完成")}
            }

        }
    }
}

@Composable
fun HomeVolume(){
    val context=LocalContext.current
    val boardWidth=BOARD_WIDTH_DP/LocalPhysicalLengthConverter.current.lengthToDp(1f,LengthUnit.Meters).value
    val window=LocalSpatialContainerStateManager.current
    val onstage by window.isOnstage
    var session by remember{mutableStateOf<RidgeSession?>(null)}
    var scene by remember{mutableStateOf<RidgeScene?>(null)}
    var deskHeight by remember{mutableFloatStateOf(0f)}
    var boardHeight by remember{mutableFloatStateOf(0f)}
    LaunchedEffect(scene,deskHeight){scene?.resizeDesk(deskHeight)}
    LaunchedEffect(scene,boardHeight){scene?.resizeBoard(boardHeight)}
    var error by remember{mutableStateOf<String?>(null)}
    val run=remember{(context.applicationContext as SpatialApplication).activeRun()}
    LaunchedEffect(run){
        try{session=run.ready.await()}
        catch(e:CancellationException){throw e}
        catch(e:Exception){Log.e("RidgeScene","Session load failed",e);error="山野暂时没有准备好，请关闭应用后重新打开。"}
    }
    val s=session
    if(error!=null || s==null){Text(error?:"正在加载山脊和跑者");return}
    DisposableEffect(scene,run,window){
        val view=scene
        if(view!=null)run.attach(view,{window.isOnstage.value}){
            "focus=${window.isFocused.value} onstage=${window.isOnstage.value} sighted=${window.isSighted.value}"
        }
        onDispose{if(view!=null)run.detach(view)}
    }
    DisposableEffect(Unit){onDispose{scene?.let{run.detach(it);it.close()};scene=null}}
    SpatialView(modifier=Modifier.fillMaxSize().pointerInput(scene,onstage,s.selectedBadge){
        val view=scene?:return@pointerInput
        if(onstage && s.selectedBadge!=null){
            detectSpatialDragGesture(context,targetedToEntity=TargetEntity.hit(view.badgeHit)){drag->
                s.rotateBadge(.9f*drag.dragAmount.x/density)
            }
        }
    }.pointerInput(scene,onstage,s.choosingRunner,s.placing,s.expedition,s.editing,s.tool,s.course){
        val view=scene?:return@pointerInput
        if(s.choosingRunner){
            detectSpatialDragGesture(context,targetedToEntity=TargetEntity.hit(view.previewHit),onDragEnd={view.finishPreviewDrag()}){drag->
                view.turnPreview(drag.dragAmount.x / density)
            }
            return@pointerInput
        }
        if(onstage && s.placing){
            val target=if(s.questActive)TargetEntity.any{it.id==view.ground.id || view.starIndex(it)>=0}
                else TargetEntity.hit(view.ground)
            var owner:PointerId?=null
            var destination:Point?=null
            var star=-1
            try{detectSpatialPointerEvent(context,targetedToEntity=target){events->
                if(events.none{it.pointerId==owner}){
                    owner=null;destination=null;star=-1;view.focusStar(-1)
                }
                var handled=false
                for(e in events){
                    if(e.isDownEvent() && owner==null){
                        star=if(s.questActive)view.starIndex(e.targetedEntity) else -1
                        destination=view.hit(e.targetedEntity,Vector3(e.x,e.y,e.z))
                        if(star>=0 || destination!=null)owner=e.pointerId
                        view.focusStar(star)
                    }
                    if(owner!=e.pointerId)continue
                    handled=true
                    if(e.isUpEvent()){
                        if(star>=0)s.queueStar(star) else destination?.let(s::append)
                        Log.d("RidgeFlags","release star=$star pending=${s.legs.size} status=${s.caption}")
                        owner=null;destination=null;star=-1;view.focusStar(-1)
                    }
                }
                handled
            }}finally{view.focusStar(-1)}
            return@pointerInput
        }
        if(!onstage || !s.editing)return@pointerInput
        var owner:String?=null;var stroke:Course.Stroke?=null;var lastPaint=0L
        fun cancel(){stroke=null;owner=null;scene?.paint(s.course,true);scene?.cursor(null,s.course)}
        try{
            detectSpatialPointerEvent(context=context){events->
                var used=false
                if(events.isEmpty())cancel()
                for(e in events){
                    val id=e.pointerId.toString()
                    if(owner!=null && owner!=id)continue
                    val p=terrainEditStage("hit-test"){scene?.hit(e.targetedEntity,Vector3(e.x,e.y,e.z))}
                    if(p==null || !s.course.editable(p)){if(owner!=null)cancel();continue}
                    if(e.isDownEvent() && owner==null){
                        owner=id;stroke=terrainEditStage("brush-create"){Course.Stroke(s.course,s.tool,p)};lastPaint=0
                    }
                    if(owner!=id){scene?.cursor(p,s.course);continue}
                    val brush=stroke?:continue
                    used=true
                    when{
                        e.isUpEvent()->terrainEditStage("gesture-up"){
                            terrainEditStage("brush-extend"){brush.extend(p)}
                            s.commit(brush);cancel()
                        }
                        e.pressed->terrainEditStage("gesture-drag"){
                            terrainEditStage("brush-extend"){brush.extend(p)}
                            if(e.uptimeMillis-lastPaint>=130){scene?.paint(brush.draft,false);lastPaint=e.uptimeMillis}
                            scene?.cursor(p,brush.draft)
                        }
                        else->cancel()
                    }
                }
                used
            }
        }finally{cancel()}
    },attachments={
        AttachmentPanel("run-board"){PicoTheme{if(s.boardVisible)RunBoard(s){boardHeight=it} else Spacer(Modifier.size(1.dp))}}
        AttachmentPanel("desk"){PicoTheme{Desk(s){deskHeight=it}}}
    },
        initial={content,panels->
            try{
                val land=Entity.loadSuspend("asset://ridge/ridge.glb")
                val bodies=RunnerStyle.entries.associateWith{Entity.loadSuspend(it.modelUri)}
                val badges=TrailBadge.entries.associateWith{Entity.loadSuspend(it.modelUri)}
                scene=RidgeScene(content,s,land,bodies,requireNotNull(panels.entity("desk")),requireNotNull(panels.entity("run-board")),boardWidth,badges)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){Log.e("RidgeScene","load failed",e);error="山野暂时没有准备好，请关闭应用后重新打开。"}
        })
    LaunchedEffect(scene,run){
        val view=scene?:return@LaunchedEffect
        while(true)withFrameNanos{now->run.display(s,view,now)}
    }
    LaunchedEffect(s.course){scene?.paint(s.course,true)}
}
