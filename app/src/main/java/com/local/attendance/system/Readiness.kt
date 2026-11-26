package com.local.attendance.system

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import com.local.attendance.automation.FeishuAccessibilityService
import com.local.attendance.data.AppSettings

object Readiness {
    fun version(context: Context, packageName: String): Long = runCatching {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
    }.getOrDefault(-1)
    fun issues(context: Context, settings: AppSettings, live: Boolean): List<String> = buildList {
        if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add("未允许闹钟和提醒")
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) add("未允许通知")
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(AppNotifications.FAILURE)?.importance == NotificationManager.IMPORTANCE_NONE)
            add("失败闹钟通知分类被关闭")
        if (FeishuAccessibilityService.current == null) add("无障碍服务未连接")
        val version = version(context, settings.packageName)
        if (version < 0) add("未找到配置的飞书应用")
        if (context.getSystemService(KeyguardManager::class.java).isDeviceSecure) add("手机仍设置了密码锁")
        if (context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0)
            add("闹钟音量为零")
        if (live) {
            if (!settings.verified(version)) add("当前飞书版本和路径尚未完成适配核对")
            if (!settings.vivoConfigured) add("尚未确认 vivo 后台运行设置")
            if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
                add("电池设置尚未允许不受限制运行")
        }
    }
}
