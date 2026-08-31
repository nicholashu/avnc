/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gaurav.avnc.R

/**
 * Keeps active VNC sessions in Android's foreground execution class.
 *
 * The VNC socket/session itself remains owned by [RemoteSession]. This service
 * only gives the process the foreground lifetime it needs so Android does not
 * freeze or reclaim an otherwise healthy connection when the viewer activity
 * moves to the background.
 */
class SessionKeepAliveService : Service() {
    private val sessions = linkedMapOf<Int, String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getIntExtra(EXTRA_SESSION_ID, INVALID_SESSION_ID) ?: INVALID_SESSION_ID

        when (intent?.action) {
            ACTION_ACQUIRE -> {
                if (sessionId != INVALID_SESSION_ID) {
                    sessions[sessionId] = intent.getStringExtra(EXTRA_SESSION_LABEL).orEmpty()
                    showForegroundNotification()
                }
            }

            ACTION_RELEASE -> {
                if (sessionId != INVALID_SESSION_ID)
                    sessions.remove(sessionId)

                if (sessions.isEmpty()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    showForegroundNotification()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showForegroundNotification() {
        val activeLabel = sessions.values.lastOrNull().orEmpty()
        val text = when {
            sessions.size > 1 -> "${sessions.size} VNC sessions active"
            activeLabel.isNotBlank() -> "Connected to $activeLabel"
            else -> "VNC session active"
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_computer)
                .setContentTitle("AVNC connection active")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else
            0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return

        val channel = NotificationChannel(
                CHANNEL_ID,
                "Active VNC connections",
                NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps VNC connections active while AVNC is in the background"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "active_vnc_session"
        private const val NOTIFICATION_ID = 1001
        private const val INVALID_SESSION_ID = -1
        private const val ACTION_ACQUIRE = "com.gaurav.avnc.session.ACQUIRE"
        private const val ACTION_RELEASE = "com.gaurav.avnc.session.RELEASE"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_SESSION_LABEL = "session_label"

        fun acquire(context: Context, sessionId: Int, label: String) {
            val intent = Intent(context, SessionKeepAliveService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun release(context: Context, sessionId: Int) {
            val intent = Intent(context, SessionKeepAliveService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_SESSION_ID, sessionId)
            }

            // The service is already running in foreground at this point, so this
            // does not create a new background service; it only delivers RELEASE.
            runCatching { context.startService(intent) }
        }
    }
}
