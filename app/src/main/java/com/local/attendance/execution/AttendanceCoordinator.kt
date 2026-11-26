package com.local.attendance.execution

import android.os.*
import com.local.attendance.AttendanceApp
import com.local.attendance.automation.*
import com.local.attendance.core.*
import com.local.attendance.system.Readiness
import kotlinx.coroutines.*
import java.time.LocalDate

class AttendanceCoordinator(private val app: AttendanceApp) {
    private var activeKey: String? = null
    private var job: Job? = null
    private val silentCancellations = mutableSetOf<String>()
    private val dao get() = app.repository.dao
    fun isRunning(key: String) = activeKey == key && job?.isActive == true
    fun cancel(key: String, silent: Boolean = false) {
        if (silent && activeKey == key) silentCancellations += key
        if (activeKey == key) job?.cancel()
    }
    fun disconnected() { activeKey?.let { key ->
        cancel(key)
        app.scope.launch { app.alarms.fail(key, "无障碍服务断开") }
    } }
    fun serviceStopped() { activeKey?.let { key ->
        cancel(key)
        app.scope.launch { app.alarms.fail(key, "执行服务被系统停止") }
    } }
    fun start(key: String, completed: () -> Unit) {
        if (job?.isCompleted == false) {
            if (activeKey != key) app.scope.launch { app.alarms.fail(key, "其他任务正在执行") }
            return
        }
        activeKey = key
        job = app.scope.launch(start = CoroutineStart.LAZY) {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val task = dao.get(key) ?: return@launch
                if (task.status != TaskState.SCHEDULED) return@launch
                val settings = app.settings.get()
                val mode = ExecutionMode.valueOf(task.mode)
                if (mode == ExecutionMode.LIVE && !settings.enabled) {
                    app.repository.transition(key, TaskState.CANCELLED, "自动任务已暂停"); return@launch
                }
                val issues = Readiness.issues(app, settings, mode == ExecutionMode.LIVE)
                if (issues.isNotEmpty()) error(issues.joinToString("；"))
                check(!task.submitReserved) { "曾进入提交阶段，请手动核对" }
                if (!app.repository.transition(key, TaskState.STARTING)) return@launch
                val startWall = System.currentTimeMillis()
                val deadlineElapsed = SystemClock.elapsedRealtime() + SchedulePolicy.TIMEOUT_MS
                dao.setDeadline(key, startWall, startWall + SchedulePolicy.TIMEOUT_MS)
                app.scheduler.armWatchdog(key, startWall + SchedulePolicy.TIMEOUT_MS)
                wakeLock = app.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "attendance:execution")
                    .apply { acquire(SchedulePolicy.TIMEOUT_MS + 5_000) }
                withTimeout(SchedulePolicy.TIMEOUT_MS) {
                    val service = FeishuAccessibilityService.current ?: error("无障碍服务未连接")
                    check(withTimeout(10_000) { service.wakeAndOpen(key, settings.packageName).await() }) { "唤醒或打开飞书失败" }
                    move(key, TaskState.NAVIGATING)
                    val adapter = FeishuPageAdapter(settings)
                    adapter.navigateToAttendance()
                    move(key, TaskState.INSPECTING)
                    var page = withTimeout(10_000) { awaitValid(adapter) }
                    val date = LocalDate.parse(task.date)
                    check(page.date == date) { "考勤日期不符" }
                    if (mode == ExecutionMode.NAVIGATION_ONLY) {
                        app.probe.value = ProbeEvidence(page, settings.fingerprint, Readiness.version(app, settings.packageName), System.currentTimeMillis())
                        move(key, TaskState.SUCCEEDED, "仅导航测试结束，未提交打卡")
                        return@withTimeout
                    }
                    var decision = AttendancePolicy.decide(page, date, task.expectedShift, false)
                    if (decision == Decision.WAIT_RANGE) {
                        move(key, TaskState.WAITING_RANGE)
                        delay(30_000)
                        move(key, TaskState.INSPECTING)
                        page = withTimeout(10_000) { awaitValid(adapter) }
                        decision = AttendancePolicy.decide(page, date, task.expectedShift, true)
                    }
                    when (decision) {
                        Decision.SKIP -> move(key, TaskState.ALREADY_DONE, "对应班次已存在记录")
                        Decision.SUBMIT -> {
                            check(Readiness.version(app, settings.packageName) == settings.verifiedVersion) { "飞书版本发生变化" }
                            check(app.repository.reserveSubmit(key)) { "提交状态已改变或超时" }
                            // No suspending calls between this cancellation check and the actual click.
                            currentCoroutineContext().ensureActive()
                            check(SystemClock.elapsedRealtime() < deadlineElapsed) { "任务超时" }
                            check(SchedulePolicy.date(System.currentTimeMillis()) == date) { "任务已跨日" }
                            check(adapter.submit(date, task.expectedShift, mode)) { "提交动作未确认，请核对记录；不会重复点击" }
                            move(key, TaskState.VERIFYING)
                            withTimeout(20_000) {
                                while (!AttendancePolicy.success(adapter.readSnapshot(), date, task.expectedShift)) delay(250)
                            }
                            move(key, TaskState.SUCCEEDED)
                        }
                        else -> error("范围、按钮或班次无法确认，请手动处理")
                    }
                }
                app.scheduler.cancel(key)
            } catch (_: CancellationException) {
                // TimeoutCancellationException is also cancellation; distinguish a live parent.
                withContext(NonCancellable) {
                    val task = dao.get(key)
                    if (task != null && task.status.active && key !in silentCancellations)
                        app.alarms.fail(key, "执行超时或中断，请手动核对")
                }
            } catch (e: Exception) {
                app.alarms.fail(key, e.message?.take(160) ?: "执行失败")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
                if (activeKey == key) activeKey = null
                silentCancellations.remove(key)
                completed()
            }
        }
        job!!.start()
    }
    private suspend fun move(key: String, state: TaskState, reason: String = "") {
        currentCoroutineContext().ensureActive()
        check(app.repository.transition(key, state, reason)) { "任务状态已改变" }
    }
    private suspend fun awaitValid(adapter: FeishuPageAdapter): PageSnapshot {
        while (true) {
            val page = adapter.readSnapshot()
            if (page.valid) return page
            delay(250)
        }
    }
}
