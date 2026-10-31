package com.gamefibre.assets

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** System restart entry point; the application repository remains the single job executor. */
class AssetPreparationService:JobService() {
    private var observation:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        running=true
        val repository=AssetRepository.get(this);repository.resume()
        observation=CoroutineScope(SupervisorJob()+Dispatchers.Main).launch {
            repository.library.collect { state ->
                if(!state.initialized)return@collect
                val delay=withContext(Dispatchers.IO){repository.nextWorkDelay()}
                if(state.pending==0 || (repository.running.get()==0 && delay!=null && delay>0)) {
                    running=false
                    jobFinished(params,false)
                    if(state.pending>0 && delay!=null)schedule(this@AssetPreparationService,delay,true)
                    cancel()
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {running=false;observation?.cancel();observation=null;return true}
    companion object {
        @Volatile private var running=false
        fun schedule(context:Context,delay:Long=0,replace:Boolean=false) {
            if(running && !replace)return
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(replace || scheduler.getPendingJob(7401)==null) scheduler.schedule(JobInfo.Builder(7401,ComponentName(context,AssetPreparationService::class.java))
                .setMinimumLatency(delay).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
