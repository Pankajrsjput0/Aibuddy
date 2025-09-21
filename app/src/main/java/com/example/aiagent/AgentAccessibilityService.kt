package com.example.aiagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {
    companion object { var INSTANCE: AgentAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); INSTANCE = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { INSTANCE = null; return super.onUnbind(intent) }

    fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull()
    }
}