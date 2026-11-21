package com.example.terrainrunner

import androidx.compose.runtime.*
import kotlin.math.ceil
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


private fun ridgePoint(x:Float)=Course.local(x,-.106f-.21f*x)
private fun trailPoint(x:Float)=Course.local(x,.18f)

// Join the scree to the plank with continuous tangents, arriving along its centreline.
private val screeRun=buildList {
    add(ridgePoint(-.245f))
    fun curve(b:Point,c:Point,end:Point){
        val a=last()
        val steps=ceil((a.distance(b)+b.distance(c)+c.distance(end))/.035f).toInt()
        for(i in 1..steps){
            val t=i.toFloat()/steps
            val ab=a.toward(b,t);val bc=b.toward(c,t);val cd=c.toward(end,t)
            add(ab.toward(bc,t).toward(bc.toward(cd,t),t))
        }
    }
    curve(Course.local(-.266f,-.036f),Course.local(-.248f,.016f),Course.local(-.258f,.036f))
    curve(Course.local(-.268f,.056f),Course.local(-.294f,.069f),Course.local(-.301f,.100f))
    curve(Course.local(-.308f,.131f),Course.local(-.315f,.167f),Course.local(-.302f,.210f))
    curve(Course.local(-.293f,.240f),Course.local(-.275f,.264f),Course.local(-.248f,.261f))
    curve(Course.local(-.224f,.2583f),Course.local(-.218f,.233f),Course.local(-.205f,.207f))
    curve(Course.local(-.196f,.189f),trailPoint(-.199f),trailPoint(-.185f))
}

// Round the left end of the stone wall, then align with the high rail.
private val railRun=buildList {
    for(i in 0..32){
        val u=i/32f
        val z=.113f-.097f*u
        val bump=kotlin.math.sin(Math.PI*u).toFloat()
        add(Course.local(.279f+.0013f*u-.036f*bump*bump,z))
    }
    add(Course.local(.282f,-.107f))
}

enum class Passage(val title:String,val route:List<Point>){
    ALL("全程",railRun+listOf(ridgePoint(.218f),ridgePoint(.02f))+screeRun+
        listOf(trailPoint(.267f),Course.local(.279f,.113f))),
    RAILS("岩墙与高栏",railRun),
    LEVEL("平跑",listOf(trailPoint(-.30f),trailPoint(-.183f))),
    CLIMB("攀登",listOf(ridgePoint(-.176f),ridgePoint(.032f))),
    DESCENT("下坡",listOf(ridgePoint(.048f),ridgePoint(.218f))),
    BEAM("独木桥",listOf(trailPoint(-.225f),trailPoint(.006f))),
    JUMP("跳障",listOf(trailPoint(.025f),trailPoint(.148f))),
    DUCK("低门",listOf(trailPoint(.14f),trailPoint(.276f))),
    ROUGH("乱石弯",screeRun)
}
interface ResettableDriver:MotionDriver { fun reset() }

class RidgeSession(val network:Network,private val original:Course,internal val journal:RunJournal?=null,
    initialRunner:RunnerStyle=RunnerStyle.ORIGINAL, private val saveRunner:(RunnerStyle)->Unit={}){
    internal var fieldBook:FieldBook?=null
    internal var expedition by mutableStateOf<ExpeditionRun?>(null);private set
    internal var questRevision by mutableIntStateOf(0);private set
    internal var questMessage by mutableStateOf("");private set
    internal var totalStars by mutableLongStateOf(0L);private set
    private var openedBadge by mutableStateOf<TrailBadge?>(null)
    internal var selectedBadge:TrailBadge?
        get()=openedBadge
        set(value){
            if(value!=null && !value.isEarned(earnedDays))return
            if(value!=openedBadge){badgeYaw=0f;openedBadge=value}
        }
    internal var badgeYaw=0f;private set
    internal fun rotateBadge(degrees:Float){
        if(openedBadge!=null)badgeYaw=(badgeYaw+degrees)%360f
    }
    internal var earnedDays by mutableIntStateOf(0);private set
    internal var ribbon by mutableIntStateOf(0);private set
    internal val questActive get()=expedition?.result==null && expedition!=null
    internal val hasQuestFlags get()=(expedition?.flags?:0)>0
    internal var reachedFirstFlag by mutableStateOf(false);private set
    internal var boardVisible by mutableStateOf(true);private set
    internal var soundEnabled by mutableStateOf(true);private set
    internal fun toggleBoard(){
        boardVisible=!boardVisible
        selectedBadge=null
        fieldBook?.boardVisible=boardVisible
    }
    private var lastStars=emptyList<Point>()
    internal fun loadBook(book:FieldBook){
        fieldBook=book;reachedFirstFlag=book.reachedFirstFlag;boardVisible=book.boardVisible;soundEnabled=book.soundEnabled;ribbon=book.ribbon;earnedDays=book.results().size;totalStars=book.collectedStars
    }
    internal suspend fun startExpedition(date:LocalDate=LocalDate.now()):Boolean{
        val ground=course
        val start=trial.position
        val previous=lastStars
        val plan=withContext(Dispatchers.Default){ExpeditionPlan.create(date,ground,start,previous)}?:return false
        if(course!==ground)return false
        lastStars=plan.stars.map{it.point}
        expedition=ExpeditionRun(plan)
        placing=true;editing=false;choosingRunner=false
        editCaption=""
        questMessage=""
        questRevision++
        return true
    }
    internal fun leaveExpedition(){
        val ownsRoute=hasQuestFlags && following && legs.isNotEmpty()
        expedition=null;placing=false
        if(ownsRoute && !joinTour()){legs=emptyList();paused=true;motionCaption="无法接回全程"}
    }
    internal fun queueStar(index:Int){
        val run=expedition?:return
        if(!questActive || index !in run.plan.stars.indices || index in run.collected)return
        append(run.plan.stars[index].point)
    }
    var runner by mutableStateOf(initialRunner);private set
    var choosingRunner by mutableStateOf(false);private set
    var candidate by mutableStateOf(initialRunner);private set
    fun openRunners(){candidate=runner;choosingRunner=true}
    fun previewRunner(style:RunnerStyle){if(choosingRunner)candidate=style}
    fun closeRunners(){choosingRunner=false}
    fun applyRunner(){
        if(!choosingRunner)return
        if(candidate!=runner){
            trial.useRunner(network.contactFor(candidate))
            runner=candidate
            saveRunner(runner)
        }
        closeRunners()
    }
    var course by mutableStateOf(original);private set
    var passage by mutableStateOf(Passage.ALL);private set
    var paused by mutableStateOf(false)
    private val speeds=listOf(.25f,.5f,.75f,1f,1.25f,1.5f,2f)
    private var speedIndex by mutableIntStateOf(3)
    val speed get()=speeds[speedIndex]
    val canSlow get()=!questActive && speedIndex>0
    val canAccelerate get()=!questActive && speedIndex<speeds.lastIndex
    fun slower(){if(canSlow)speedIndex--}
    fun faster(){if(canAccelerate)speedIndex++}
    var editing by mutableStateOf(false);private set
    var placing by mutableStateOf(false);private set
    var following by mutableStateOf(false);private set
    // Each pending flag owns its actual incoming path and its destination index in Trial.route.
    internal class FlagLeg(val path:List<Point>,val stop:Int){val flag get()=path.last()}
    internal var legs by mutableStateOf(emptyList<FlagLeg>());private set
    val visiblePoints by derivedStateOf { legs.map{it.flag} }
    var tool by mutableStateOf(Tool.LOWER)
    private var motionCaption by mutableStateOf("平地起跑")
    private var editCaption by mutableStateOf("")
    val caption get()=if(placing || editing)editCaption else motionCaption
    private val history=ArrayDeque<Course>()
    private fun onRockTrail(p:Point):Boolean{
        if(!following && passage!=Passage.ALL)
            return passage==Passage.CLIMB || passage==Passage.DESCENT || passage==Passage.ROUGH
        // The ridge and scree join west of the stream. The level runway along
        // the bridge centreline belongs to the existing log contact solver.
        val ridge=p.mx in -.28f.. .23f && kotlin.math.abs(p.mz+.106f+.21f*p.mx)<.04f
        val logApproach=p.mx>=-.225f && kotlin.math.abs(p.mz-.18f)<.001f
        return ridge || (p.mx<-.182f && p.mz in -.08f.. .32f && !logApproach)
    }
    var trial=Trial(course,passage.route,network,network.contactFor(runner),::onRockTrail);private set

    // For focused motion verification. The public toolbar always starts on the full tour.
    internal fun replay(segment:Passage){
        network.reset();following=false;editing=false;placing=false;paused=false
        passage=segment;trial=Trial(course,segment.route,network,network.contactFor(runner),::onRockTrail)
        legs=emptyList();motionCaption=segment.title
    }

    private fun flagCount(){
        editCaption=when{
            questActive->"剩余 ${legs.size} 面红旗"
            legs.isEmpty()->"试着在跑者前方插一面旗。"
            else->"正跑向红旗 · 还剩 ${legs.size} 面，可继续插旗。"
        }
    }

    fun step(){
        if(paused)return
        if(following && legs.isEmpty()){
            if(!joinTour()){paused=true;motionCaption="无法接回全程";return}
        }
        if(trial.done){
            if(trial.position==passage.route.first()){

                trial.followFromHere(passage.route)
            }else{
                val link=WaypointRoute.leg(course,trial.position,passage.route.first())
                if(link==null){paused=true;motionCaption="无法返回起点";return}
                trial.followFromHere(link+passage.route.drop(1))
            }
        }
        val start=trial.position
        trial.step()
        journal?.record(course,start,trial.position)
        if(hasQuestFlags)expedition?.move(start,trial.position)
        if(trial.done && !following && passage==Passage.ALL)
            android.util.Log.i("RidgeLap","finished runner=$runner ticks=${trial.ticks}")
        if(following && legs.isNotEmpty() && trial.lastVisitedIndex>=legs.first().stop){
            val reached=legs.takeWhile{it.stop<=trial.lastVisitedIndex}
            val run=expedition
            if(hasQuestFlags && questActive && run!=null){
                for(leg in reached){
                    val star=run.arrive(leg.flag)?:continue
                    totalStars=fieldBook?.addCollectedStar()?:totalStars+1
                    questMessage="找到了「${run.plan.stars[star].name}」 · ${run.collected.size}/3"
                    questRevision++
                }
                run.result?.let{result->
                    val firstVisit=fieldBook?.best(result.day)==null
                    val improved=fieldBook?.record(result)==true
                    questMessage=if(firstVisit && improved)"今日最佳已保存 · 探索天数 +1"
                        else if(improved)"刷新了今日最佳"
                        else "已完成 · 今日最佳仍为原纪录"
                    earnedDays=fieldBook?.results()?.size?:earnedDays
                    if(firstVisit && earnedDays in listOf(3,7)){
                        ribbon=if(earnedDays==7)2 else 1
                        fieldBook?.ribbon=ribbon
                    }
                    placing=false;legs=emptyList()
                }
            }
            legs=legs.dropWhile{it.stop<=trial.lastVisitedIndex}
            if(!reachedFirstFlag){
                reachedFirstFlag=true
                fieldBook?.reachedFirstFlag=true
            }
            if(placing){
                if(!questActive && legs.isEmpty())editCaption="已到红旗！再选一处地面，或退出继续巡跑。"
                else flagCount()
            }

        }
        if(trial.stalled || !trial.pose.phase.isFinite()){
            paused=true;motionCaption="此处无法继续 · 可调整路线";return
        }
        if(trial.ticks%6==0){
            val p=trial.position
            val d=trial.heading
            val front=Point(p.x+.28f*d.x,p.z+.28f*d.z)
            val back=Point(p.x-.28f*d.x,p.z-.28f*d.z)
            val grade=(course.height(front)-course.height(back))/.56f
            motionCaption=when{
                p.mx in .230f.. .257f && p.mz in .048f.. .089f->"绕过岩墙"
                p.mx<-.23f && p.mz in .018f.. .120f->"沿溪转弯"
                course.onDeck(p)->"独木桥平衡"
                course.jump(p)>.25f->"跳越障碍"
                course.crouch(p)>.25f->"俯身通过"
                p.mx<-.16f && p.mz>.216f->"乱石踏步"
                grade>.11f -> "攀上岩阶"
                grade<-.09f -> "下坡落脚"
                course.height(p)*Course.CELL>.032f -> "越过坡肩"
                else -> "平地跑动"
            }
        }
    }
    fun editPoints(){
        if(expedition!=null)return
        editing=false;placing=true;flagCount()
    }
    fun append(point:Point){
        if(!placing)return
        val run=expedition
        val p=if(questActive && run!=null)run.plan.stars.withIndex()
            .filter{it.index !in run.collected}
            .minByOrNull{it.value.point.distance(point)}
            ?.value?.point?.takeIf{it.distance(point)<.65f}?:point else point
        if(!course.supports(p)){editCaption="请选择可通行地面";return}
        val replace=questActive && !hasQuestFlags
        val pending=if(replace)emptyList() else legs
        if(questActive && pending.any{it.flag.distance(p)<.03f})return
        val previous=pending.lastOrNull()
        val start=previous?.flag?:trial.position
        val starHere=questActive && run?.plan?.stars?.any{it.point==p}==true
        if(start.distance(p)<.12f && !starHere){editCaption="距离太近，请换个位置";return}
        val path=WaypointRoute.leg(course,start,p)
        if(path==null){editCaption="没有可通行路线，请换个位置";return}
        val route=if(previous==null)path else trial.route+path.drop(1)
        if(replace)requireNotNull(run).startAt(start)
        val next=pending+FlagLeg(path,route.lastIndex)
        if(previous==null)trial.followFromHere(route,listOf(route.lastIndex))
        else trial.replaceTail(route,next.map{it.stop})
        legs=next;following=true;paused=false;flagCount()
        if(questActive){run?.placeFlag();questRevision++}
    }
    fun removePoint(){
        if(!placing || legs.isEmpty() || (questActive && !hasQuestFlags))return
        legs=legs.dropLast(1)
        if(legs.isEmpty()){
            if(!joinTour()){paused=true;editCaption="无法接回全程，请重新插旗";return}
        }else{
            trial.replaceTail(trial.route.take(legs.last().stop+1),legs.map{it.stop})
        }
        flagCount()
    }
    fun exitPoints(){if(!questActive)placing=false}
    private fun joinTour():Boolean{
        val tour=Passage.ALL.route
        val anchors=(0 until tour.lastIndex).sortedBy{trial.position.distance(tour[it])}
        for(i in anchors){
            val link=WaypointRoute.leg(course,trial.position,tour[i])?:continue
            val route=if(trial.position==tour[i])listOf(trial.position)+tour.drop(i+1) else link+tour.drop(i+1)
            if(route.size<2)continue
            trial.followFromHere(route)
            passage=Passage.ALL;following=false;legs=emptyList()
            return true
        }
        return false
    }
    fun editMode(){if(expedition!=null)return;placing=false;editing=true;editCaption="捏住拖动塑形"}
    fun finishEditing(){editing=false}
    private fun applyGround(next:Course):Boolean{
        // Validate the remaining custom itinerary before publishing ground to simulation and rendering.
        if(legs.isNotEmpty())terrainEditStage("route-rebuild"){
            val route=mutableListOf(trial.position)
            val rebuilt=mutableListOf<FlagLeg>()
            for(leg in legs){
                val path=WaypointRoute.leg(next,route.last(),leg.flag)
                if(path==null){editCaption="此次修改会封住路径 · 请调整笔刷";return false}
                route.addAll(path.drop(1))
                rebuilt+=FlagLeg(path,route.lastIndex)
            }
            trial.followFromHere(route,rebuilt.map{it.stop})
            legs=rebuilt
        }
        course=next;trial.useGround(next);return true
    }
    fun commit(stroke:Course.Stroke){
        if(!editing || stroke.original!==course || stroke.draft===course)return
        val before=course
        if(!applyGround(stroke.draft))return
        if(history.size>=32)history.removeFirst()
        history.addLast(before);editCaption="地形已更新"
    }
    fun undo(){
        if(!editing)return
        val prev=history.lastOrNull()?:return
        if(applyGround(prev)){history.removeLast();editCaption="已撤销一笔"}
    }
    fun restore(){if(editing && applyGround(original)){history.clear();editCaption="已恢复地形"}}
}
