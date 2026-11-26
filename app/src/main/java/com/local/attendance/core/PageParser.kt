package com.local.attendance.core

import java.time.LocalDate

// Geometry is used only to associate a visible heading with its following content.
// It never supplies a tap coordinate. IDs are ephemeral within a fresh tree read.
data class UiElement(
    val id: Int, val text: String, val top: Int, val bottom: Int,
    val enabled: Boolean = true, val actionId: Int? = null
)
object PageParser {
    private val datePattern = Regex("^(\\d{4})[.年/\\-](\\d{1,2})[.月/\\-](\\d{1,2})日?$")
    private val recordPattern = Regex("^已打卡\\s*(\\d{1,2}:\\d{2})(?::\\d{2})?$")
    private val timePattern = Regex("^(\\d{1,2}:\\d{2})(?::\\d{2})?$")
    fun parse(elements: List<UiElement>): PageSnapshot {
        val nodes = elements.filter { it.text.isNotBlank() && it.bottom > it.top }
            .sortedWith(compareBy<UiElement> { it.top }.thenBy { it.id })
        val dates = nodes.mapNotNull { node -> datePattern.matchEntire(node.text.trim())?.let {
            runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }}.distinct()
        val date = dates.singleOrNull()
        if (date == null) return PageSnapshot(null, emptyList(), RangeStatus.UNKNOWN, false, "日期缺失或不唯一")
        val headings = nodes.filter { it.text == "上班" || it.text == "下班" }
        if (headings.isEmpty() || headings.zipWithNext().any { (a,b) -> a.top == b.top })
            return PageSnapshot(date, emptyList(), RangeStatus.UNKNOWN, false, "班次分组缺失或重叠")
        var error = ""
        val sections = headings.mapIndexed { index, heading ->
            val shift = if (heading.text == "上班") Shift.ON else Shift.OFF
            val limit = headings.getOrNull(index + 1)?.top ?: Int.MAX_VALUE
            val content = nodes.filter { it.top > heading.top && it.top < limit }
            val records = content.mapNotNull { recordPattern.matchEntire(it.text.trim())?.groupValues?.get(1) }.toMutableList()
            val separate = content.filter { it.text == "已打卡" }
            separate.forEach { label ->
                val possible = content.filter { it.top >= label.top && it.top <= label.bottom && timePattern.matches(it.text) }
                if (possible.size == 1) records += timePattern.matchEntire(possible.single().text)!!.groupValues[1]
                else error = "打卡记录时间无法唯一识别"
            }
            val buttons = content.filter { it.text == "${shift.label}打卡" }
            val opposite = content.any { it.text == "${if (shift == Shift.ON) "下班" else "上班"}打卡" }
            if (records.size > 1 || buttons.size > 1 || opposite || (records.isNotEmpty() && buttons.isNotEmpty()))
                error = "班次内记录或按钮有歧义"
            val record = records.singleOrNull()
            if (record != null && !validTime(record)) error = "记录时间不合法"
            val button = buttons.singleOrNull()
            Section(shift, record, button?.actionId, button?.enabled == true)
        }
        val outside = nodes.any { it.text.contains("不在打卡范围") }
        val inside = nodes.any { it.text.startsWith("已进入打卡范围") }
        val range = when {
            outside && !inside -> RangeStatus.OUTSIDE
            inside && !outside -> RangeStatus.INSIDE
            else -> RangeStatus.UNKNOWN
        }
        return PageSnapshot(date, sections, range, error.isEmpty(), error)
    }
    private fun validTime(value: String): Boolean {
        val pieces = value.split(':')
        val hour = pieces[0].toIntOrNull() ?: return false
        val minute = pieces[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }
}
