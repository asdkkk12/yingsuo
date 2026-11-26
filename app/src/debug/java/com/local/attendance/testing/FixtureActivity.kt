package com.local.attendance.testing

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.local.attendance.core.SchedulePolicy

/** Local synthetic UI for instrumentation. Never included in release builds. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30,80,30,30) }
        fun text(value: String) { layout.addView(TextView(this).apply { text=value; textSize=20f; setPadding(0,12,0,12) }) }
        text(SchedulePolicy.date(System.currentTimeMillis()).toString().replace('-','.'))
        text("上班")
        text("已打卡 09:00")
        text("下班")
        if (clicks > 0) text("已打卡 22:00")
        else layout.addView(Button(this).apply { text="下班打卡"; setOnClickListener { clicks++; render() } })
        text(if (outside) "不在打卡范围" else "已进入打卡范围：模拟办公室")
        setContentView(layout)
    }
    companion object { var clicks = 0; var outside = false }
}
