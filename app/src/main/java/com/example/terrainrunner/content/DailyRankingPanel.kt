package com.example.terrainrunner.content

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.terrainrunner.DailyRank
import com.example.terrainrunner.PpsServiceException
import com.example.terrainrunner.platform.SpatialApplication
import com.pico.spatial.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun DailyRankingPanel(onBack:()->Unit){
    val host=LocalContext.current
    val ranking=(host.applicationContext as SpatialApplication).dailyRanking
    var date by remember{mutableStateOf(LocalDate.now())}
    var rows by remember(date){mutableStateOf<List<DailyRank>>(emptyList())}
    var loading by remember(date){mutableStateOf(true)}
    var failure by remember(date){mutableStateOf<String?>(null)}
    var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(date,refresh){
        while(true){
            loading=true;failure=null
            try {
                rows=if(ranking.configured)ranking.topTen(date,host) else emptyList()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                android.util.Log.w("DailyRanking","Ranking load failed",e)
                rows=emptyList()
                failure=if(e is PpsServiceException && e.code==-98)"排行榜服务尚未就绪，请稍后刷新"
                    else "暂时无法连接排行榜，请稍后刷新"
            }
            loading=false
            if(failure!=null)return@LaunchedEffect
            delay(30_000)
            date=LocalDate.now()
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
            Text("今日跑量榜",style=PicoTheme.typography.titleMedium)
            Text("${date.monthValue}月${date.dayOfMonth}日 · 前 10 名",
                style=PicoTheme.typography.labelMedium,color=PicoTheme.colorScheme.labelSecondary)
        }
        Button(onClick={date=LocalDate.now();refresh++},enabled=!loading && ranking.configured){Text("刷新")}
        Spacer(Modifier.width(8.dp))
        Button(onClick=onBack){Text("返回")}
    }
    Spacer(Modifier.height(10.dp))
    when{
        !ranking.configured->Text("排行榜正在准备中",Modifier.padding(vertical=28.dp))
        failure!=null->Text(failure.orEmpty(),Modifier.padding(vertical=28.dp))
        loading && rows.isEmpty()->Text("正在加载今日排名…",Modifier.padding(vertical=28.dp))
        rows.isEmpty()->Text("今日还没有跑量记录，跑一段来上榜吧",Modifier.padding(vertical=28.dp))
        else->{
            Row(Modifier.fillMaxWidth().padding(vertical=4.dp)){
                Text("名次",Modifier.width(54.dp),style=PicoTheme.typography.labelMedium)
                Text("跑者",Modifier.weight(1f),style=PicoTheme.typography.labelMedium)
                Text("今日跑量",style=PicoTheme.typography.labelMedium)
            }
            rows.forEach { row->
                Row(Modifier.fillMaxWidth().height(28.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(row.rank.toString(),Modifier.width(54.dp),style=PicoTheme.typography.bodyMedium)
                    Text(row.username,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,
                        style=PicoTheme.typography.bodyMedium)
                    Text(if(row.metres>=1000)String.format(Locale.ROOT,"%.3f km",row.metres/1000.0) else "${row.metres} m",
                        style=PicoTheme.typography.bodyMedium)
                }
            }
        }
    }
    Text("按虚拟跑者今日累计距离排名",
        style=PicoTheme.typography.labelMedium,color=PicoTheme.colorScheme.labelSecondary)
}
