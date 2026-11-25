package com.example.terrainrunner.content

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.terrainrunner.*
import com.example.terrainrunner.R
import com.pico.spatial.ui.design.*

@Composable
private fun Hint(text:String){
    Text(text,style=PicoTheme.typography.labelMedium,color=PicoTheme.colorScheme.labelSecondary)
}

@Composable
internal fun HuntTitle(text:String){
    // User-requested hierarchy: the hunt heading is larger than the instructions and feedback.
    Text(text,style=PicoTheme.typography.titleLarge)
}

@Composable
internal fun StarRating(count:Int,modifier:Modifier=Modifier){
    Row(modifier.semantics{contentDescription="$count / 3 星"},horizontalArrangement=Arrangement.spacedBy(6.dp)){
        repeat(3){i->
            val star=painterResource(R.drawable.ic_trail_star)
            if(i<count)Image(star,null,Modifier.size(28.dp))
            else Icon(star,null,Modifier.size(28.dp),tint=PicoTheme.colorScheme.labelSecondary.copy(alpha=.45f))
        }
    }
}

@Composable
internal fun ExpeditionPanel(s:RidgeSession,onStart:()->Unit){
    val run=s.expedition?:return
    val result=run.result
    val collected=remember(run,s.questRevision){run.collected.size}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        HuntTitle(if(result==null)"今日寻星" else "三颗星，收齐了")
        Spacer(Modifier.weight(1f))
        Text(if(result==null)"${run.plan.title} · $collected / 3" else "今日已打卡",
            style=PicoTheme.typography.labelMedium,color=PicoTheme.colorScheme.labelSecondary)
    }
    if(result!=null){
        val best=remember(result){s.fieldBook?.best(result.day)?.flags?:result.flags}
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            StarRating(result.medal)
            Text(result.title,style=PicoTheme.typography.titleSmall)
        }
        Hint(if(result.flags==3)"一星一旗，完美领航" else "用了 ${result.flags} 面旗；只用 3 面旗即可获得三星评价")
        Hint("今日最佳 ${best} 旗 · 陪跑 ${result.distanceLabel} · 累计打卡 ${s.earnedDays} 天")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick=onStart,modifier=Modifier.weight(1f),
                colors=ButtonDefaults.buttonColors(containerColor=PicoTheme.colorScheme.interaction,
                    contentColor=PicoTheme.colorScheme.labelPrimaryLight)){Text("再找三颗星")}
            Button(onClick=s::leaveExpedition,modifier=Modifier.weight(1f)){Text(BACK_HOME)}
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        run.plan.stars.forEach{star->
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp,Alignment.CenterHorizontally)){
                Image(painterResource(R.drawable.ic_trail_star),null,Modifier.size(26.dp))
                Text(star.name,style=PicoTheme.typography.titleMedium)
            }
        }
    }
    val instruction=when{
        !s.hasQuestFlags->"看向光圈跳动的星星，捏一下再松开"
        collected+s.legs.count{leg->run.plan.stars.any{it.point==leg.flag}}>=3->"旗已插好，等跑者依次抵达就能收齐"
        s.legs.any{leg->run.plan.stars.any{it.point==leg.flag}}->"跑者出发了！可以继续给另一颗星插旗"
        else->"再给剩下的星星插旗，集齐三颗完成打卡"
    }
    Text(instruction,style=PicoTheme.typography.bodySmall)
    val feedback=when{
        s.caption.isNotEmpty() && !s.caption.startsWith("剩余")->s.caption
        !s.hasQuestFlags->"松手就出发 · 每颗星插一面旗即可"
        else->"已用 ${run.flags} 面旗 · 3 旗集齐可获三星评价"
    }
    Hint(feedback)
    Button(onClick=s::leaveExpedition){Text(BACK_HOME)}
}
