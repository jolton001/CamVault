package com.example.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class RecordingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {
                val cameraNames = intent?.getStringArrayListExtra(EXTRA_CAMERA_NAMES) ?: arrayListOf()
                val cameraName = intent?.getStringExtra(EXTRA_CAMERA_NAME)
                if (cameraName != null && !cameraNames.contains(cameraName)) {
                    cameraNames.add(cameraName)
                }
                val notification = buildNotification(cameraNames)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamVault:RecordingWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(cameraNames: List<String>): Notification {
        ensureNotificationChannel()

        val text = when {
            cameraNames.isEmpty() -> "Recording active in background"
            cameraNames.size == 1 -> "Recording active: ${cameraNames.first()}"
            else -> "Recording active on ${cameraNames.size} cameras (${cameraNames.joinToString(", ")})"
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamVault 24/7 Security Recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "24/7 Security Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CamVault network sockets and storage recorder active when screen is off"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "camvault_247_recording_channel"
        const val NOTIFICATION_ID = 5129
        const val ACTION_START = "com.example.media.ACTION_START_RECORDING_SERVICE"
        const val ACTION_UPDATE = "com.example.media.ACTION_UPDATE_RECORDING_SERVICE"
        const val ACTION_STOP = "com.example.media.ACTION_STOP_RECORDING_SERVICE"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
        const val EXTRA_CAMERA_NAMES = "extra_camera_names"

        fun startOrUpdate(context: Context, cameraNames: List<String>) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_CAMERA_NAMES, ArrayList(cameraNames))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

