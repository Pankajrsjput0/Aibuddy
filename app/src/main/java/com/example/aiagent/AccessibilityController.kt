package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.Exception

object AccessibilityController {
    fun openApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Thread.sleep(800) // wait a bit
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    fun clickByText(context: Context, text: String): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val node = svc.findNodeByText(root, text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun fillText(context: Context, text: String): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }
}