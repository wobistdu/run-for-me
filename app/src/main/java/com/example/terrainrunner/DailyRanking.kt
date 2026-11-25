package com.example.terrainrunner

import android.content.Context
import android.util.Log
import com.bytedance.pico.platformsdk.business.Platform
import com.pico.pps.sdk.base.MatrixResult
import com.pico.pps.sdk.base.OnFailureListener
import com.pico.pps.sdk.base.OnSuccessListener
import com.pico.pps.sdk.base.Task
import com.pico.pps.sdk.leaderboard.LeaderboardClient
import com.pico.pps.sdk.auth.PicoSignInClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor

internal data class DailyRank(val rank:Int,val username:String,val metres:Long)

/** PPS scores are whole metres. Each date has its own server-provisioned leaderboard. */
internal class DailyRanking(private val context:Context,private val journal:RunJournal){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val lock=Mutex()
    private val prefix=context.getString(R.string.pps_daily_board_prefix).trim()
    val configured=context.getString(R.string.pps_app_id).isNotBlank() && prefix.isNotBlank()
    private val initializationLock=Mutex()
    private var platformInitialized=false
    private var initialized=false
    private var nextSync=0L
    private var sentDay:LocalDate?=null
    private var sentMetres=-1L

    fun syncIfDue(){
        val now=android.os.SystemClock.elapsedRealtime()
        if(!configured || now<nextSync)return
        nextSync=now+60_000
        scope.launch {
            try { submit() }
            catch(e:CancellationException){throw e}
            catch(_:PpsSignInRequiredException){ /* First authorization happens only from the ranking UI. */ }
            catch(e:Exception){Log.w("DailyRanking","PPS sync failed",e)}
        }
    }

    private suspend fun client(host:Context=context,interactive:Boolean=false):com.pico.pps.sdk.leaderboard.ILeaderboardClient = initializationLock.withLock {
        if(!platformInitialized){
            Platform.getCoreApi().initializePlatformAsynchronous(context.getString(R.string.pps_app_id),host)
                .awaitStatus("platform")
            platformInitialized=true
        }
        // Follow LeaderboardClient's platform + account initialization. Do not gate
        // leaderboard access on the separate GameApi initialization.
        if(!initialized){
            val auth=PicoSignInClient.getSignInClient(host)
            val token=if(interactive)auth.getAccessToken().await(180_000).accessToken.orEmpty()
                else auth.getAuthorizedScopes().await().accessToken.orEmpty()
            if(token.isBlank())throw PpsSignInRequiredException()
            initialized=true
        }
        LeaderboardClient.getLeaderboardClient(context)
    }

    private suspend fun submit()=lock.withLock {
        val now=System.currentTimeMillis()
        val date=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val metres=floor(journal.snapshot(now).todayMetres).toLong().coerceAtLeast(0)
        if(metres>0 && (sentDay!=date || metres>sentMetres)){
            client().writeEntry(board(date),metres,byteArrayOf(),false).await()
            sentDay=date;sentMetres=metres
            Log.i("DailyRanking","PPS synced day=$date metres=$metres")
        }
    }

    suspend fun topTen(date:LocalDate,host:Context=context,interactive:Boolean=true):List<DailyRank>{
        check(configured){"PPS 排行榜尚未连接"}
        client(host,interactive)
        if(date==LocalDate.now())submit()
        val entries=client().getEntries(board(date),10,0,0,0).await().entryList
        return entries.take(10).map {
            DailyRank(it.rank,it.user?.displayName?.takeIf(String::isNotBlank)?:"跑者",it.score)
        }
    }

    private fun board(date:LocalDate)=prefix+"_"+date.format(DateTimeFormatter.BASIC_ISO_DATE)
}

private suspend fun <T> Task<T>.await(timeoutMillis:Long=15_000):T=withTimeoutOrNull(timeoutMillis){
    suspendCancellableCoroutine { continuation->
        continuation.invokeOnCancellation{this@await.cancel()}
        addOnSuccessListener(object:OnSuccessListener<T>{
            override fun onSuccess(result:MatrixResult<T>){
                if(!continuation.isActive)return
                val data=result.data
                if(result.isSuccess() && data!=null)continuation.resume(data)
                else continuation.resumeWithException(IllegalStateException("PPS request rejected"))
            }
        })
        addOnFailureListener(object:OnFailureListener{
            override fun onFailure(exc:Exception){
                if(continuation.isActive)continuation.resumeWithException(exc)
            }
        })
    }
} ?: throw IllegalStateException("PPS request timed out")

private class PpsSignInRequiredException:IllegalStateException("Open rankings to authorize PPS")

internal class PpsServiceException(val code:Int,message:String):IllegalStateException(message)

private suspend fun com.bytedance.pico.platformsdk.framework.Request<Int>.awaitStatus(stage:String){
    val result=withTimeoutOrNull(15_000){
        suspendCancellableCoroutine<Int>{ continuation->
            onComplete { message->
                if(continuation.isActive){
                    if(message.isError)continuation.resumeWithException(
                        PpsServiceException(message.error?.code?:-1,"PPS $stage ${message.error?.code}: ${message.error?.message}"))
                    else continuation.resume(message.data?:-1)
                }
            }
        }
    } ?: throw IllegalStateException("PPS $stage initialization timed out")
    check(result==0){"PPS $stage initialization returned $result"}
}
