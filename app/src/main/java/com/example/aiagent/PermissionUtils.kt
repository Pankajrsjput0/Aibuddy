package com.example.aiagent

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {
    fun hasPermissions(activity: Activity, vararg perms: String): Boolean {
        return perms.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }
    }
    fun requestPermissions(activity: Activity, reqCode: Int, vararg perms: String) {
        ActivityCompat.requestPermissions(activity, perms, reqCode)
    }
}