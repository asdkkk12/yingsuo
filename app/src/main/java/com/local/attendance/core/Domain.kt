package com.local.attendance.core

import java.time.*

val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
enum class Shift(val label: String, val hour: Int) { ON("上班", 9), OFF("下班", 22) }
enum class ExecutionMode { NAVIGATION_ONLY, LIVE }
enum class TaskState {
    SCHEDULED, STARTING, NAVIGATING, INSPECTING, WAITING_RANGE, SUBMITTING, VERIFYING,
    SUCCEEDED, ALREADY_DONE, FAILED_RINGING, FAILED_ACKNOWLEDGED, CANCELLED;
    val active get() = this in setOf(STARTING, NAVIGATING, INSPECTING, WAITING_RANGE, SUBMITTING, VERIFYING)
    val terminal get() = !active && this != SCHEDULED
}
enum class RangeStatus { INSIDE, OUTSIDE, UNKNOWN }
enum class Decision { SKIP, WAIT_RANGE, SUBMIT, FAIL }
object NavigationPolicy {
    fun safeLabel(label: String): Boolean = label.isNotBlank() &&
        listOf("打卡", "签到", "签退", "提交", "确认", "确定", "补卡", "同意", "支付", "删除").none { label.contains(it) }
}
data class Section(val shift: Shift, val recordTime: String?, val buttonId: Int?, val enabled: Boolean)
data class PageSnapshot(
    val date: LocalDate?, val sections: List<Section>, val range: RangeStatus,
    val valid: Boolean, val error: String = ""
) {
    fun target(shift: Shift): Section? = sections.filter { it.shift == shift }.singleOrNull()
    fun summary() = "日期：${date ?: "无法识别"}\n范围：$range\n" +
        sections.joinToString("\n") { "${it.shift.label}：${it.recordTime?.let { t -> "已打卡 $t" } ?: if (it.buttonId != null) "待打卡（按钮${if (it.enabled) "可用" else "不可用"}）" else "无明确记录或按钮"}" } +
        if (valid) "" else "\n无法适配：$error"
}
object AttendancePolicy {
    fun decide(page: PageSnapshot, date: LocalDate, shift: Shift, retried: Boolean): Decision {
        if (!page.valid || page.date != date) return Decision.FAIL
        val target = page.target(shift) ?: return Decision.FAIL
        if (target.recordTime != null) return Decision.SKIP
        if (page.range == RangeStatus.OUTSIDE) return if (retried) Decision.FAIL else Decision.WAIT_RANGE
        return if (page.range == RangeStatus.INSIDE && target.buttonId != null && target.enabled)
            Decision.SUBMIT else Decision.FAIL
    }
    fun success(page: PageSnapshot, date: LocalDate, shift: Shift): Boolean =
        page.valid && page.date == date && page.target(shift)?.recordTime != null
}
object SchedulePolicy {
    const val TIMEOUT_MS = 120_000L
    const val CATCH_UP_MS = 300_000L
    fun at(date: LocalDate, shift: Shift): Long = date.atTime(shift.hour, 0).atZone(BEIJING).toInstant().toEpochMilli()
    fun next(now: Long, shift: Shift): Long {
        val date = Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate()
        val today = at(date, shift)
        return if (today > now) today else at(date.plusDays(1), shift)
    }
    fun date(epoch: Long): LocalDate = Instant.ofEpochMilli(epoch).atZone(BEIJING).toLocalDate()
    fun key(date: LocalDate, shift: Shift) = "$date-${shift.name}"
    fun catchUp(now: Long, scheduled: Long) = now - scheduled in 0..CATCH_UP_MS
}

object TransitionRules {
    fun allows(from: TaskState, to: TaskState): Boolean {
        if (to == TaskState.FAILED_RINGING) return from == TaskState.SCHEDULED || from.active
        if (to == TaskState.CANCELLED) return from == TaskState.SCHEDULED || from.active
        return when (from) {
            TaskState.SCHEDULED -> to == TaskState.STARTING
            TaskState.STARTING -> to == TaskState.NAVIGATING
            TaskState.NAVIGATING -> to == TaskState.INSPECTING
            TaskState.INSPECTING -> to in setOf(TaskState.WAITING_RANGE, TaskState.SUBMITTING, TaskState.ALREADY_DONE, TaskState.SUCCEEDED)
            TaskState.WAITING_RANGE -> to == TaskState.INSPECTING
            TaskState.SUBMITTING -> to == TaskState.VERIFYING
            TaskState.VERIFYING -> to == TaskState.SUCCEEDED
            TaskState.FAILED_RINGING -> to == TaskState.FAILED_ACKNOWLEDGED
            else -> false
        }
    }
}
