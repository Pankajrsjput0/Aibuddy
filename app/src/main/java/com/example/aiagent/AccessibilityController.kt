package com.example.aiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
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

    fun closeApp(context: Context, packageName: String): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun clickById(context: Context, resourceId: String): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        val node = nodes?.firstOrNull() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun clickByCoords(x: Int, y: Int): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun clickByXPath(context: Context, xpath: String): Boolean {
        // XPath not natively supported by Android Accessibility API
        android.util.Log.w("AccessibilityController", "XPath selectors not supported in Android Accessibility API")
        return false
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Boolean {
        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val path = Path()
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        path.lineTo(toX.toFloat(), toY.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun clickBySelector(context: Context, by: String, value: String): Boolean {
        return when (by) {
            "text" -> clickByText(context, value)
            "resource_id" -> clickById(context, value)
            "content_desc" -> {
                val svc = AgentAccessibilityService.INSTANCE ?: return false
                val root = svc.rootInActiveWindow ?: return false
                val node = findNodeByContentDesc(root, value) ?: return false
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            else -> false
        }
    }

    private fun findNodeByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString() == desc) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDesc(child, desc)
            if (found != null) return found
        }
        return null
    }

    fun fillBySelector(context: Context, selector: JSONObject?, text: String): Boolean {
        if (selector == null) {
            return fillText(context, text)
        }

        val by = selector.optString("by", "text")
        val value = selector.optString("value", "")

        val svc = AgentAccessibilityService.INSTANCE ?: return false
        val root = svc.rootInActiveWindow ?: return false

        val node = when (by) {
            "text" -> svc.findNodeByText(root, value)
            "resource_id" -> root.findAccessibilityNodeInfosByViewId(value)?.firstOrNull()
            "content_desc" -> findNodeByContentDesc(root, value)
            else -> null
        } ?: return false

        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}