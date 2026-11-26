package com.local.attendance.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.*
import com.local.attendance.core.*
import com.local.attendance.graph
import com.local.attendance.ui.WakeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

data class TreeRead(val packageName: String, val elements: List<UiElement>, val nodes: Map<Int, AccessibilityNodeInfo>)
data class ProbeEvidence(val snapshot: PageSnapshot, val fingerprint: String, val version: Long, val at: Long)

class FeishuAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { current = this; connected.value = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Trees are read only during an authorized task. */ }
    override fun onInterrupt() { disconnect() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (current === this) { current = null; connected.value = false; graph.coordinator.disconnected() }
    }
    fun readTree(expectedPackage: String): TreeRead? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != expectedPackage) return null
        val elements = mutableListOf<UiElement>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        var nextId = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int, parentAction: Int?, parentEnabled: Boolean) {
            if (nextId >= 3000 || depth > 40 || !node.isVisibleToUser) return
            val id = nextId++
            nodes[id] = node
            val actionId = if (node.isClickable) id else parentAction
            val enabled = parentEnabled && node.isEnabled
            val text = node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            elements += UiElement(id, text, bounds.top, bounds.bottom, enabled, actionId)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1, actionId, enabled) }
        }
        visit(root, 0, null, true)
        return TreeRead(expectedPackage, elements, nodes)
    }
    fun wakeAndOpen(key: String, packageName: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        WakeBridge.request?.result?.complete(false)
        WakeBridge.request = WakeBridge.Request(key, packageName, deferred)
        try {
            startActivity(Intent(this, WakeActivity::class.java).putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { deferred.complete(false) }
        return deferred
    }
    companion object {
        var current: FeishuAccessibilityService? = null
            private set
        val connected = MutableStateFlow(false)
    }
}
object WakeBridge {
    data class Request(val key: String, val packageName: String, val result: CompletableDeferred<Boolean>)
    var request: Request? = null
}
