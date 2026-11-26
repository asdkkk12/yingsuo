package com.local.attendance.alarm

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import com.local.attendance.R
import com.local.attendance.data.TaskEntity
import com.local.attendance.graph
import com.local.attendance.system.AppNotifications
import com.local.attendance.ui.FailureActivity
import kotlinx.coroutines.launch

class FailureAlarmService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    val isPlaying: Boolean get() = player?.isPlaying == true
    override fun onCreate() { super.onCreate(); current = this }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(AppNotifications.FAILURE_ID, notification("正在恢复失败提醒"))
        graph.scope.launch {
            if (intent?.action == STOP) graph.alarms.acknowledgeAll()
            val tasks = graph.repository.dao.ringing()
            if (tasks.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@launch
            }
            getSystemService(NotificationManager::class.java).notify(AppNotifications.FAILURE_ID,
                notification(tasks.joinToString("；") { "${it.date} ${it.expectedShift.label}：${it.reason}" }))
            runCatching { startSound() }.onFailure {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0))
                getSystemService(NotificationManager::class.java).notify(AppNotifications.FAILURE_ID,
                    notification("声音播放失败，请立即手动处理；振动和提醒将保持"))
            }
        }
        return START_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 30, Intent(this, FailureActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 31, Intent(this, FailureAlarmService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, AppNotifications.FAILURE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("打卡失败，请手动处理")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setAutoCancel(false)
            .setContentIntent(open).setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(null, "关闭全部闹钟", stop).build())
            .addAction(Notification.Action.Builder(null, "查看并打开飞书", open).build())
            .build()
    }
    private fun startSound() {
        if (player != null) return
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { /* System audio policy still applies. */ }.build()
        audio.requestAudioFocus(focus!!)
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            resources.openRawResourceFd(R.raw.failure_alarm).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setWakeMode(this@FailureAlarmService, PowerManager.PARTIAL_WAKE_LOCK)
            isLooping = true
            prepare()
            start()
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 1000), 0))
    }
    override fun onDestroy() {
        player?.release(); player = null
        vibrator.cancel()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        if (current === this) current = null
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.local.attendance.STOP_ALARMS"
        var current: FailureAlarmService? = null
            private set
    }
}
