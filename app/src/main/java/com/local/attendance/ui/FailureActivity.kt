package com.local.attendance.ui

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.*
import com.local.attendance.databinding.ActivityFailureBinding
import com.local.attendance.graph
import kotlinx.coroutines.*

class FailureActivity : Activity() {
    private lateinit var binding: ActivityFailureBinding
    private var refresh: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true); setShowWhenLocked(true)
        binding = ActivityFailureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.stopAll.setOnClickListener {
            graph.scope.launch { graph.alarms.acknowledgeAll(); finish() }
        }
        binding.openFeishu.setOnClickListener {
            graph.scope.launch {
                val settings = graph.settings.get()
                packageManager.getLaunchIntentForPackage(settings.packageName)?.let { startActivity(it) }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        refresh = graph.scope.launch {
            while (isActive) {
                val tasks = graph.repository.dao.ringing()
                binding.failureDetail.text = if (tasks.isEmpty()) "没有未关闭的闹钟" else tasks.joinToString("\n\n") {
                    "${it.date} ${it.expectedShift.label}\n${it.reason}"
                }
                binding.stopAll.isEnabled = tasks.isNotEmpty()
                delay(500)
            }
        }
    }
    override fun onStop() { refresh?.cancel(); super.onStop() }
}
