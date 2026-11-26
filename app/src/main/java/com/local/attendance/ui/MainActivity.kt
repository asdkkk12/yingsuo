package com.local.attendance.ui

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.local.attendance.automation.*
import com.local.attendance.core.*
import com.local.attendance.data.TaskEntity
import com.local.attendance.databinding.ActivityMainBinding
import com.local.attendance.execution.ExecutionService
import com.local.attendance.graph
import com.local.attendance.system.Readiness
import kotlinx.coroutines.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        uiScope.launch {
            val settings = graph.settings.get()
            binding.packageInput.setText(settings.packageName)
            binding.pathInput.setText(settings.path.joinToString("\n"))
            binding.vivoConfigured.isChecked = settings.vivoConfigured
        }
        binding.notificationPermission.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        binding.exactPermission.setOnClickListener { openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        binding.accessibilityPermission.setOnClickListener { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.batteryPermission.setOnClickListener { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        binding.viewAlarm.setOnClickListener { startActivity(Intent(this, FailureActivity::class.java)) }
        binding.saveConfig.setOnClickListener { runUi {
            val pkg = binding.packageInput.text.toString().trim()
            val path = binding.pathInput.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "应用包名格式不正确" }
            require(path.size <= 8 && path.all { it.length <= 40 }) { "入口最多 8 步，每步不超过 40 字" }
            graph.settings.configure(pkg, path, binding.vivoConfigured.isChecked)
            graph.probe.value = null
            graph.scheduler.reconcile()
            message("配置已保存", "自动任务已暂停，请执行仅导航测试后重新核对适配。")
            render()
        } }
        binding.toggleSchedule.setOnClickListener { runUi {
            val settings = graph.settings.get()
            if (!settings.enabled) {
                val issues = Readiness.issues(this@MainActivity, settings, true)
                check(issues.isEmpty()) { issues.joinToString("\n") }
            }
            graph.settings.enable(!settings.enabled)
            graph.scheduler.reconcile()
            render()
        } }
        binding.navigationTest.setOnClickListener { runUi {
            val settings = graph.settings.get()
            val issues = Readiness.issues(this@MainActivity, settings, false)
            check(issues.isEmpty()) { issues.joinToString("\n") }
            check(!graph.repository.dao.unfinished().any { it.status.active }) { "已有任务正在执行" }
            graph.probe.value = null
            val now = System.currentTimeMillis()
            val date = SchedulePolicy.date(now)
            val task = TaskEntity("probe-${UUID.randomUUID()}", date.toString(), Shift.ON.name, now, ExecutionMode.NAVIGATION_ONLY.name)
            graph.repository.dao.insert(task)
            graph.scheduler.armWatchdog(task.key, now + SchedulePolicy.TIMEOUT_MS)
            ExecutionService.start(this@MainActivity, task.key)
        } }
        binding.verifyAdapter.setOnClickListener { runUi {
            val settings = graph.settings.get()
            val evidence = graph.probe.value ?: error("请先执行导航测试")
            check(evidence.fingerprint == settings.fingerprint && evidence.version == Readiness.version(this@MainActivity, settings.packageName)) { "配置或版本已改变，请重新测试" }
            check(System.currentTimeMillis() - evidence.at in 0..600_000L) { "测试结果已过期，请重新测试" }
            check(evidence.snapshot.date == SchedulePolicy.date(System.currentTimeMillis())) { "测试日期已过期" }
            check(evidence.snapshot.valid && evidence.snapshot.sections.isNotEmpty()) { "页面不能可靠识别" }
            AlertDialog.Builder(this@MainActivity).setTitle("请核对实际考勤页面")
                .setMessage(evidence.snapshot.summary() + "\n\n只有日期、上下班分组、记录和范围均与飞书一致时才能确认。此确认不代表已通过熄屏或真实打卡验收。")
                .setNegativeButton("返回检查", null)
                .setPositiveButton("识别结果一致") { _, _ -> runUi {
                    val current = graph.settings.get()
                    check(current.fingerprint == evidence.fingerprint && Readiness.version(this@MainActivity, current.packageName) == evidence.version) { "配置或版本已改变" }
                    graph.settings.verify(current, evidence.version); render()
                } }.show()
        } }
        binding.alarmTest.setOnClickListener { runUi {
            val now = System.currentTimeMillis()
            val key = "alarm-test-${UUID.randomUUID()}"
            graph.repository.dao.insert(TaskEntity(key, SchedulePolicy.date(now).toString(), Shift.ON.name, now, ExecutionMode.NAVIGATION_ONLY.name))
            graph.alarms.fail(key, "测试闹钟：请使用关闭按钮停止")
            startActivity(Intent(this@MainActivity, FailureActivity::class.java))
        } }
        uiScope.launch { graph.repository.dao.observeRecent().collect { tasks ->
            binding.history.text = if (tasks.isEmpty()) "暂无记录" else tasks.joinToString("\n\n") {
                "${it.date} ${it.expectedShift.label}${if (it.mode == ExecutionMode.NAVIGATION_ONLY.name) "（测试）" else ""}\n${stateLabel(it.status)}${if (it.reason.isEmpty()) "" else "\n${it.reason}"}"
            }
        } }
        uiScope.launch { graph.probe.collect { evidence ->
            binding.probeResult.text = evidence?.snapshot?.summary() ?: "尚未取得有效页面识别结果。导航结束后请返回这里。"
            binding.verifyAdapter.isEnabled = evidence != null
        } }
        uiScope.launch { FeishuAccessibilityService.connected.collect { render() } }
    }
    override fun onResume() {
        super.onResume()
        runUi { graph.scheduler.reconcile(); graph.alarms.restore(); render() }
    }
    private suspend fun render() {
        val settings = graph.settings.get()
        val issues = Readiness.issues(this, settings, true)
        val format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(BEIJING)
        binding.status.text = buildString {
            append(if (settings.enabled) "每日任务：已开启" else "每日任务：已暂停")
            append("\n")
            append(if (issues.isEmpty()) "权限及适配检查通过（仍需真机验收）" else issues.joinToString("\n"))
            if (settings.enabled) Shift.entries.forEach { append("\n下次${it.label}：${format.format(Instant.ofEpochMilli(SchedulePolicy.next(System.currentTimeMillis(), it)))}") }
        }
        binding.toggleSchedule.text = if (settings.enabled) "暂停每日自动打卡" else "启用每日自动打卡"
    }
    private fun runUi(action: suspend () -> Unit) { uiScope.launch {
        try { action() } catch (_: CancellationException) { } catch (e: Exception) { message("暂时无法完成", e.message ?: "操作失败") }
    } }
    private fun message(title: String, detail: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("知道了", null).show() }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { message("请手动设置", "请在系统设置中找到考勤助手并授予对应权限。") } }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
    private fun stateLabel(state: TaskState) = when (state) {
        TaskState.SCHEDULED -> "等待执行"; TaskState.STARTING -> "正在唤醒"; TaskState.NAVIGATING -> "进入考勤"
        TaskState.INSPECTING -> "核对页面"; TaskState.WAITING_RANGE -> "等待 30 秒复查范围"; TaskState.SUBMITTING -> "已进入提交阶段"
        TaskState.VERIFYING -> "核对提交结果"; TaskState.SUCCEEDED -> "执行成功"; TaskState.ALREADY_DONE -> "已有记录，已跳过"
        TaskState.FAILED_RINGING -> "失败，正在响铃"; TaskState.FAILED_ACKNOWLEDGED -> "失败，用户已关闭闹钟"; TaskState.CANCELLED -> "已暂停／取消"
    }
}
