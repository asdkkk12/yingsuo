package com.local.attendance.alarm

import android.content.*
import com.local.attendance.core.TaskState
import com.local.attendance.graph
import kotlinx.coroutines.*

class FailureAlarmController(private val context: Context) {
    suspend fun fail(key: String, reason: String) {
        context.graph.coordinator.cancel(key)
        withContext(NonCancellable) {
            val changed = context.graph.repository.transition(key, TaskState.FAILED_RINGING, reason)
            if (changed || context.graph.repository.dao.get(key)?.status == TaskState.FAILED_RINGING) {
                context.graph.scheduler.cancel(key)
                restore()
            }
        }
    }
    suspend fun acknowledge(key: String) {
        if (context.graph.repository.transition(key, TaskState.FAILED_ACKNOWLEDGED, "用户明确关闭闹钟"))
            context.graph.scheduler.cancel(key)
        refreshService()
    }
    suspend fun acknowledgeAll() {
        context.graph.repository.dao.ringing().forEach {
            context.graph.repository.transition(it.key, TaskState.FAILED_ACKNOWLEDGED, "用户明确关闭闹钟")
            context.graph.scheduler.cancel(it.key)
        }
        refreshService()
    }
    suspend fun restore() {
        if (context.graph.repository.dao.ringing().isNotEmpty()) refreshService()
    }
    private fun refreshService() {
        context.startForegroundService(Intent(context, FailureAlarmService::class.java))
    }
}
