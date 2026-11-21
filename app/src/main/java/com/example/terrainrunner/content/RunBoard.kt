package com.example.terrainrunner.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.design.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import com.example.terrainrunner.R
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.dp
import com.example.terrainrunner.Course
import com.example.terrainrunner.TrailBadge
import com.example.terrainrunner.RidgeSession
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import java.util.Locale
import java.time.LocalDate
import kotlinx.coroutines.delay

internal const val BOARD_WIDTH_DP=600
private fun decimal(value:Double,places:Int)=String.format(Locale.ROOT,"%.${places}f",value)
private fun distance(metres:Double,places:Int)=if(metres<1000) "${decimal(metres,0)} m" else "${decimal(metres/1000,places)} km"

@Composable
private fun RidgeProfile(course:Course,modifier:Modifier){
    val heights=remember(course){
        FloatArray(72){i->
            val x=-.245f+.463f*i/71
            course.height(Course.local(x,-.106f-.21f*x))
        }
    }
    val strata=ImageBitmap.imageResource(R.drawable.ridge_strata)
    val ink=PicoTheme.colorScheme.labelPrimary
    Canvas(modifier.semantics{contentDescription="当前沙盘山脊的海拔剖面"}){
        val low=heights.min();val range=(heights.max()-low).coerceAtLeast(.001f)
        fun point(i:Int)=Offset(size.width*i/heights.lastIndex,
            size.height*(.88f-.72f*(heights[i]-low)/range))
        for(i in 1..3){
            val y=size.height*i/4
            drawLine(ink.copy(alpha=.13f),Offset(0f,y),Offset(size.width,y),1.dp.toPx())
        }
        val ridge=Path()
        for(i in heights.indices){val p=point(i);if(i==0)ridge.moveTo(p.x,p.y) else ridge.lineTo(p.x,p.y)}
        val area=Path().apply{addPath(ridge);lineTo(size.width,size.height);lineTo(0f,size.height);close()}
        clipPath(area){drawImage(strata,dstSize=IntSize(size.width.toInt(),size.height.toInt()))}
        drawPath(ridge,ink,style=Stroke(1.6.dp.toPx()))
        drawCircle(ink,3.dp.toPx(),point(heights.indices.maxBy{heights[it]}))
    }
}

@Composable
private fun Reading(label:String,style:TextStyle,secondary:Boolean=false,value:()->Pair<String,String>){
    val primary=PicoTheme.colorScheme.labelPrimary
    val muted=PicoTheme.colorScheme.labelSecondary
    val unitStyle=PicoTheme.typography.labelMedium.toSpanStyle().copy(color=muted)
    val numeric=style.copy(color=if(secondary)muted else primary,fontFeatureSettings="tnum")
    val height=with(LocalDensity.current){
        if(style.lineHeight.isUnspecified)style.fontSize.toDp()*1.35f else style.lineHeight.toDp()
    }
    val measurer=rememberTextMeasurer()
    // Only pixels and accessibility values change on the one-second tick. Keep layout fixed.
    Spacer(Modifier.fillMaxWidth().height(height).semantics {
        val (number,unit)=value()
        contentDescription="$label $number $unit"
    }.drawWithCache {
        val (number,unit)=value()
        val line=buildAnnotatedString {
            append(number)
            if(unit.isNotEmpty())withStyle(unitStyle){append("  ");append(unit)}
        }
        val text=measurer.measure(line,numeric,softWrap=false,maxLines=1)
        onDrawBehind{drawText(text,topLeft=Offset(0f,(size.height-text.size.height)*.5f))}
    })
}

@Composable
private fun Caption(text:String){
    Text(text,style=PicoTheme.typography.labelMedium,color=PicoTheme.colorScheme.labelSecondary,maxLines=1)
}

@Composable
internal fun RunBoard(s:RidgeSession,onHeight:(Float)->Unit){
    val journal=requireNotNull(s.journal)
    val course=s.course
    val density=LocalDensity.current
    val converter=LocalPhysicalLengthConverter.current
    var today by remember{mutableStateOf(LocalDate.now())}
    var totals by remember(journal){mutableStateOf(journal.snapshot())}
    LaunchedEffect(journal){
        try{
            while(true){
                totals=journal.snapshot()
                today=LocalDate.now()
                delay(1000)
            }
        }finally{journal.save()}
    }
    Column(Modifier.width(BOARD_WIDTH_DP.dp).onSizeChanged{size->
        onHeight(converter.dpToLength(with(density){size.height.toDp()},LengthUnit.Meters))
    }.clip(RoundedCornerShape(24.dp))
        .backgroundMaterial(enable=true,style=Material.Regular)
        .padding(horizontal=22.dp,vertical=14.dp)){
        val selected=s.selectedBadge
        if(selected!=null){
            Row(Modifier.fillMaxWidth().height(156.dp),verticalAlignment=Alignment.CenterVertically){
                Spacer(Modifier.width(182.dp).fillMaxHeight().semantics{
                    contentDescription="${selected.title}徽章模型"
                    customActions=listOf(
                        CustomAccessibilityAction("向左旋转"){s.rotateBadge(-45f);true},
                        CustomAccessibilityAction("向右旋转"){s.rotateBadge(45f);true})
                })
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("${selected.title}徽章",style=PicoTheme.typography.titleMedium)
                    Caption("在 ${selected.days} 个不同日期集齐三颗星")
                    Caption("捏住徽章，左右拖动查看")
                    Text("已获得 · 已打卡 ${s.earnedDays} 天",
                        style=PicoTheme.typography.bodyMedium)
                    Button(onClick={s.selectedBadge=null}){Text("返回数据板")}
                }
            }
            return@Column
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Caption("${today.monthValue}月${today.dayOfMonth}日 · 今日角色跑动")
                Reading("今日跑动",PicoTheme.typography.displayMedium){
                    val km=totals.todayMetres>=1000
                    decimal(if(km)totals.todayMetres/1000 else totals.todayMetres,if(km)3 else 0) to (if(km)"km" else "m")
                }
                Reading("",PicoTheme.typography.labelMedium,true){"累计 ${distance(totals.totalMetres,2)}" to ""}
            }
            Column(Modifier.width(148.dp),horizontalAlignment=Alignment.End){
                Caption("山脊剖面")
                RidgeProfile(course,Modifier.fillMaxWidth().height(40.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        val rule=PicoTheme.colorScheme.dividerLine
        Canvas(Modifier.fillMaxWidth().height(1.dp)){drawRect(rule)}
        Spacer(Modifier.height(9.dp))
        Row{
            Column(Modifier.weight(1f)){
                Caption("今日爬升")
                Reading("今日爬升",PicoTheme.typography.titleLarge){decimal(totals.ascentMetres,if(totals.ascentMetres<100)1 else 0) to "m"}
            }
            Column(Modifier.weight(1f)){
                Caption("今日消耗 · 估算")
                Reading("今日消耗",PicoTheme.typography.titleLarge){decimal(totals.kcal,if(totals.kcal<100)1 else 0) to "kcal"}
            }
            Column(Modifier.weight(1f)){
                Caption("累计寻星")
                Reading("累计寻星",PicoTheme.typography.titleLarge){s.totalStars.toString() to "颗"}
            }
            Column(Modifier.weight(1f)){
                Caption("累计跑")
                Reading("累计跑",PicoTheme.typography.titleLarge){totals.days.toString() to "天"}
                Reading("",PicoTheme.typography.labelMedium,true){"已连续跑 ${totals.streak} 天" to ""}
            }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(1.dp)){drawRect(rule)}
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(TrailBadge.ROW_DP.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("山野成就 · 已打卡 ${s.earnedDays} 天",style=PicoTheme.typography.titleSmall)
                val next=TrailBadge.entries.firstOrNull{!it.isEarned(s.earnedDays)}
                Caption(if(next==null)"三枚徽章已集齐 · 山野常伴"
                    else if(s.earnedDays==0)"集齐三颗星，点亮晨光徽章"
                    else "再打卡 ${next.days-s.earnedDays} 天，点亮${next.title}")
            }
            TrailBadge.entries.forEach{badge->
                val earned=badge.isEarned(s.earnedDays)
                val interaction=if(earned) Modifier.spatialHoverEffect()
                    .clickable(onClickLabel="查看${badge.title}徽章"){s.selectedBadge=badge} else Modifier
                Box(Modifier.width(TrailBadge.SLOT_DP.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .then(interaction).semantics(mergeDescendants=true){
                    contentDescription="${badge.title}，打卡 ${badge.days} 天，${if(earned)"已获得" else "未获得"}"
                },contentAlignment=Alignment.BottomCenter){Caption("${badge.title} · ${badge.days}天")}
            }
        }
    }
}
