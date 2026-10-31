package com.gamefibre.stores

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class StoreDownloadService:Service() {
    private val channel="store_downloads"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var downloads:StoreDownloads
    @Volatile private var running=false
    @Volatile private var latestStart=0
    private var wake:PowerManager.WakeLock?=null
    private var lastNotification=0L
    override fun onCreate() {
        super.onCreate(); downloads=StoreDownloads.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Game downloads",NotificationManager.IMPORTANCE_LOW))
        scope.launch { downloads.state.collect { transfer ->
            val now=android.os.SystemClock.elapsedRealtime()
            if(running && transfer!=null && now-lastNotification>=1000 && (Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)) {
                lastNotification=now; getSystemService(NotificationManager::class.java).notify(32,notification(transfer.request.game.name,transfer.message))
            }
        } }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        latestStart=startId
        if(intent?.action in listOf("pause","cancel")) {
            runCatching { if(intent?.action=="pause")downloads.pause()else downloads.cancel() }
            if(!running)stopSelf(startId); return START_NOT_STICKY
        }
        startForeground(32,notification(intent?.getStringExtra("name")?:"Game download","Preparing game files…"))
        val id=intent?.getStringExtra("runId")
        if(id.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        if(running)return START_NOT_STICKY
        running=true
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GameFibre:store-transfer").apply { acquire(6*60*60*1000L) }
        downloads.run(id) {
            running=false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStart)
        }
        return START_NOT_STICKY
    }
    private fun notification(name:String,message:String):Notification {
        val open=PendingIntent.getActivity(this,0,com.gamefibre.NotificationIntents.openDownloads(this),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,StoreDownloadService::class.java).setAction("pause"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getService(this,2,Intent(this,StoreDownloadService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,channel).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(name).setContentText(message)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Pause",pause).build()).addAction(Notification.Action.Builder(null,"Cancel",cancel).build()).build()
    }
    override fun onTimeout(startId:Int,fgsType:Int) { downloads.cancel("Android reached its background download time limit. Resume to continue.",true); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        if(running)downloads.cancel("Android stopped the download. Resume to continue.",true)
        scope.cancel(); wake?.let { if(it.isHeld)it.release() }; super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
