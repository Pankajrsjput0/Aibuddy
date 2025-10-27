package com.example.aiagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object ConnectivityUtils {
    fun isOnUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_WIFI ||
            networkInfo?.type == ConnectivityManager.TYPE_ETHERNET
        }
    }
}
