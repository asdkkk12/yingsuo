package com.local.attendance.ui

import android.app.*
import android.os.Bundle
import android.widget.TextView
import com.local.attendance.automation.WakeBridge
import com.local.attendance.graph

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(TextView(this).apply { text = "正在唤醒并打开飞书…"; textSize = 22f; setPadding(40, 80, 40, 40) })
        val request = WakeBridge.request
        if (request == null || request.key != intent.getStringExtra("key") || !graph.coordinator.isRunning(request.key)) {
            finish(); return
        }
        val manager = getSystemService(KeyguardManager::class.java)
        if (manager.isDeviceSecure) { request.result.complete(false); finish(); return }
        fun open() {
            if (!graph.coordinator.isRunning(request.key) || request.result.isCompleted) { finish(); return }
            val launch = packageManager.getLaunchIntentForPackage(request.packageName)
            val success = launch != null && runCatching { startActivity(launch); true }.getOrDefault(false)
            request.result.complete(success)
            finish()
        }
        window.decorView.post {
            if (!manager.isKeyguardLocked) open()
            else manager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { open() }
                override fun onDismissError() { request.result.complete(false); finish() }
                override fun onDismissCancelled() { request.result.complete(false); finish() }
            })
        }
    }
}
