package com.local.attendance.execution

import android.app.Service
import android.content.*
import com.local.attendance.graph
import com.local.attendance.system.AppNotifications

class ExecutionService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { graph.coordinator.serviceStopped(); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(AppNotifications.EXECUTION_ID, AppNotifications.execution(this))
        val key = intent?.getStringExtra("key")
        if (key == null) stopSelf(startId)
        else graph.coordinator.start(key) { stopSelf() }
        return START_NOT_STICKY
    }
    companion object {
        fun start(context: Context, key: String) {
            context.startForegroundService(Intent(context, ExecutionService::class.java).putExtra("key", key))
        }
    }
}
