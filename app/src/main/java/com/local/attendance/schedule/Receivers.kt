package com.local.attendance.schedule

import android.content.*
import com.local.attendance.core.*
import com.local.attendance.execution.ExecutionService
import com.local.attendance.graph
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        // Stop actions immediately on the main thread, before the asynchronous DB check.
        if (intent.action == ScheduleManager.WATCHDOG) context.graph.coordinator.cancel(key)
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                val task = context.graph.repository.dao.get(key) ?: return@launch
                when (intent.action) {
                    ScheduleManager.RUN -> {
                        if (task.status == TaskState.SCHEDULED && context.graph.settings.get().enabled) {
                            if (SchedulePolicy.catchUp(System.currentTimeMillis(), task.scheduledAt))
                                ExecutionService.start(context, key)
                            else context.graph.alarms.fail(key, "触发延迟超过 5 分钟")
                        }
                        context.graph.scheduler.reconcile()
                    }
                    ScheduleManager.WATCHDOG -> if (task.status.active || task.status == TaskState.SCHEDULED)
                        context.graph.alarms.fail(key, "执行超时或未能启动，请手动核对考勤")
                }
            } catch (e: Exception) {
                context.graph.alarms.fail(key, "调度执行异常")
            } finally { pending.finish() }
        }
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                context.graph.scheduler.reconcile()
                context.graph.alarms.restore()
            } finally { pending.finish() }
        }
    }
}
