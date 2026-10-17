/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.lunaris.dolby.DolbyConstants

class DolbyNotificationListener : NotificationListenerService() {

    private var lastActivePackage: String? = null

    override fun onCreate() {
        super.onCreate()
        DolbyConstants.dlog(TAG, "NotificationListener created")
        startAppProfileMonitoringIfEnabled()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DolbyConstants.dlog(TAG, "NotificationListener connected")
        startAppProfileMonitoringIfEnabled()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DolbyConstants.dlog(TAG, "NotificationListener disconnected")
        requestRebind(android.content.ComponentName(this, DolbyNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.packageName?.let { packageName ->
            if (packageName != lastActivePackage && packageName != this.packageName) {
                lastActivePackage = packageName
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun startAppProfileMonitoringIfEnabled() {
        val prefs = getSharedPreferences("dolby_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("app_profile_monitoring_enabled", false)
        if (isEnabled) {
            DolbyConstants.dlog(TAG, "Starting app profile monitoring")
            AppProfileMonitorService.startMonitoring(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DolbyConstants.dlog(TAG, "NotificationListener destroyed")
    }

    companion object {
        private const val TAG = "DolbyNotificationListener"
    }
}
