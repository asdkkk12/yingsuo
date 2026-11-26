package com.local.attendance.system

import android.app.*
import android.content.Context
import android.content.Intent
import com.local.attendance.ui.MainActivity

object AppNotifications {
    const val EXECUTION = "execution"
    const val FAILURE = "failure"
    const val EXECUTION_ID = 100
    const val FAILURE_ID = 200
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(EXECUTION, "考勤执行状态", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(FAILURE, "打卡失败闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "持续声音由闹钟服务播放，需明确关闭"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }
    fun execution(context: Context) = Notification.Builder(context, EXECUTION)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("正在执行考勤任务")
        .setContentText("正在打开飞书并核对考勤；失败会持续响铃")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
}
