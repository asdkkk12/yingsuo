package com.local.attendance.schedule

import android.app.*
import android.content.*
import android.net.Uri
import com.local.attendance.core.*
import com.local.attendance.data.TaskEntity
import com.local.attendance.execution.ExecutionService
import com.local.attendance.graph
import com.local.attendance.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScheduleManager(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()
    private val graph get() = context.graph
    private fun operation(key: String, kind: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(kind)
            .setData(Uri.parse("attendance://task/${Uri.encode(key)}/$kind")).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun show(key: String) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("attendance://show/${Uri.encode(key)}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun armWatchdog(key: String, at: Long) {
        check(alarms.canScheduleExactAlarms()) { "未允许精确闹钟" }
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show(key)), operation(key, WATCHDOG))
    }
    fun cancel(key: String) {
        alarms.cancel(operation(key, RUN))
        alarms.cancel(operation(key, WATCHDOG))
    }
    suspend fun reconcile(now: Long = System.currentTimeMillis()) = lock.withLock {
        val settings = graph.settings.get()
        val unfinished = graph.repository.dao.unfinished()
        unfinished.filter { it.mode == ExecutionMode.NAVIGATION_ONLY.name && !graph.coordinator.isRunning(it.key) && it.status.active }.forEach {
            graph.alarms.fail(it.key, "上次导航测试中断")
        }
        if (!settings.enabled) {
            unfinished.filter { it.mode == ExecutionMode.LIVE.name }.forEach {
                cancel(it.key)
                graph.coordinator.cancel(it.key, silent = true)
                graph.repository.transition(it.key, TaskState.CANCELLED, "用户暂停")
            }
            return@withLock
        }
        if (!alarms.canScheduleExactAlarms()) {
            unfinished.filter { it.mode == ExecutionMode.LIVE.name }.forEach {
                graph.alarms.fail(it.key, "精确闹钟权限不可用")
            }
            return@withLock
        }
        unfinished.filter { it.mode == ExecutionMode.LIVE.name }.forEach { task ->
            when {
                task.status.active && graph.coordinator.isRunning(task.key) -> Unit
                task.status.active -> graph.alarms.fail(task.key, if (task.submitReserved) "执行中断，请先核对是否已打卡" else "上次执行中断")
                task.scheduledAt <= now -> {
                    if (SchedulePolicy.catchUp(now, task.scheduledAt)) ExecutionService.start(context, task.key)
                    else graph.alarms.fail(task.key, "错过任务，超过 5 分钟，不自动补打")
                }
                else -> arm(task)
            }
        }
        Shift.entries.forEach { shift ->
            var at = SchedulePolicy.next(now, shift)
            var key = SchedulePolicy.key(SchedulePolicy.date(at), shift)
            graph.repository.dao.replanFuture(key, now)
            // Clock rollback must never re-open an already completed day's task.
            while (graph.repository.dao.get(key)?.status?.terminal == true) {
                at = SchedulePolicy.at(SchedulePolicy.date(at).plusDays(1), shift)
                key = SchedulePolicy.key(SchedulePolicy.date(at), shift)
            }
            val entity = TaskEntity(key, SchedulePolicy.date(at).toString(), shift.name, at)
            graph.repository.dao.insert(entity)
            graph.repository.dao.get(key)?.takeIf { it.status == TaskState.SCHEDULED }?.let { arm(it) }
        }
        graph.repository.prune()
    }
    private fun arm(task: TaskEntity) {
        check(alarms.canScheduleExactAlarms()) { "未允许精确闹钟" }
        // Register the independent failure alarm first, before the execution alarm.
        armWatchdog(task.key, task.scheduledAt + SchedulePolicy.TIMEOUT_MS)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.scheduledAt, operation(task.key, RUN))
    }
    companion object {
        const val RUN = "com.local.attendance.RUN"
        const val WATCHDOG = "com.local.attendance.WATCHDOG"
    }
}
