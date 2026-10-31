package com.gamefibre.steam

import android.app.*
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

class DownloadService : Service() {
    private val channel = "steam_downloads"
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var engine: SteamEngine
    private var lastUpdate = 0L
    @Volatile private var running = false
    @Volatile private var latestStartId = 0
    private val observer: (JSONObject) -> Unit = { state ->
        val transfer = state.optJSONObject("transfer")
        val now = SystemClock.elapsedRealtime()
        if (running && transfer != null && now - lastUpdate > 1000) {
            lastUpdate = now
            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(NotificationManager::class.java).notify(31, notification(transfer.optString("name"), transfer.optString("message")))
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Steam downloads", NotificationManager.IMPORTANCE_LOW))
        engine = SteamEngine.get(this)
        engine.observe(observer)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action in listOf("pause", "cancel")) {
            runCatching { if (intent?.action == "pause") engine.pauseDownload() else engine.cancelDownload() }
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(31, notification(intent?.getStringExtra("name") ?: "GameFibre", "Preparing download…"))
        if (intent == null || intent.getStringExtra("runId").isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameFibre:transfer").apply { acquire(6 * 60 * 60 * 1000L) }
        engine.runDownload(intent.getStringExtra("runId")!!) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
        return START_NOT_STICKY
    }
    private fun notification(name: String, message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, com.gamefibre.NotificationIntents.openDownloads(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 2, Intent(this, DownloadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name).setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        if (engine.snapshot().optJSONObject("transfer")?.optBoolean("canPause") == true) builder.addAction(Notification.Action.Builder(null, "Pause", pause).build())
        return builder.build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.cancelDownload("Android reached its background download time limit. Resume this download to continue.", "failed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        engine.removeObserver(observer)
        if (running) engine.cancelDownload("Android stopped the download service. Resume this download to continue.", "failed")
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
