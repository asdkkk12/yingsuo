package com.local.attendance.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.local.attendance.core.*
import com.local.attendance.data.AppSettings
import kotlinx.coroutines.*

class FeishuPageAdapter(private val settings: AppSettings) {
    private fun tree() = FeishuAccessibilityService.current?.readTree(settings.packageName)
    fun readSnapshot(): PageSnapshot = tree()?.let { PageParser.parse(it.elements) }
        ?: PageSnapshot(null, emptyList(), RangeStatus.UNKNOWN, false, "当前不是飞书页面或节点不可读取")

    suspend fun navigateToAttendance() = withTimeout(40_000) {
        check(settings.path.all(NavigationPolicy::safeLabel)) { "导航路径包含提交类操作，已停止" }
        if (readSnapshot().let { it.valid && it.sections.isNotEmpty() }) return@withTimeout
        for (label in settings.path) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (readSnapshot().let { it.valid && it.sections.isNotEmpty() }) return@withTimeout
                val tree = tree()
                val matches = tree?.elements?.filter { it.text == label && it.enabled && it.actionId != null }
                    ?.mapNotNull { it.actionId }?.distinct().orEmpty()
                if (matches.size > 1) error("导航入口不唯一")
                if (matches.size == 1) {
                    check(tree!!.nodes[matches.single()]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "无法点击导航入口" }
                    delay(700)
                    break
                }
                delay(250)
            }
        }
        while (!readSnapshot().let { it.valid && it.sections.isNotEmpty() }) delay(250)
    }

    // Must be called on the main thread with no suspension after the persistent reservation.
    // Always parse a fresh tree and resolve the actionable node from that same tree.
    fun submit(date: java.time.LocalDate, shift: Shift, mode: ExecutionMode): Boolean {
        check(mode == ExecutionMode.LIVE) { "仅导航测试禁止提交" }
        val tree = tree() ?: return false
        val page = PageParser.parse(tree.elements)
        if (AttendancePolicy.decide(page, date, shift, true) != Decision.SUBMIT) return false
        val id = page.target(shift)?.buttonId ?: return false
        val node = tree.nodes[id] ?: return false
        if (!node.isEnabled || !node.isVisibleToUser || !node.isClickable) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
