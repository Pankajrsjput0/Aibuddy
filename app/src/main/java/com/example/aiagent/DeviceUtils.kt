package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs

object DeviceUtils {
    fun isCharging(context: Context): Boolean {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun freeSpaceMb(context: Context): Long {
        val stats = StatFs(context.filesDir.absolutePath)
        return (stats.availableBlocksLong * stats.blockSizeLong) / (1024 * 1024)
    }
}
