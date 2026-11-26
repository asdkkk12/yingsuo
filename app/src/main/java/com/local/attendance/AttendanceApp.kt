package com.local.attendance

import android.app.Application
import android.content.Context
import com.local.attendance.alarm.FailureAlarmController
import com.local.attendance.automation.ProbeEvidence
import com.local.attendance.data.*
import com.local.attendance.execution.AttendanceCoordinator
import com.local.attendance.schedule.ScheduleManager
import com.local.attendance.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AttendanceApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { TaskRepository(this) }
    val settings by lazy { SettingsStore(this) }
    val scheduler by lazy { ScheduleManager(this) }
    val alarms by lazy { FailureAlarmController(this) }
    val coordinator by lazy { AttendanceCoordinator(this) }
    val probe = MutableStateFlow<ProbeEvidence?>(null)
    override fun onCreate() {
        super.onCreate()
        AppNotifications.createChannels(this)
    }
}
val Context.graph: AttendanceApp get() = applicationContext as AttendanceApp
