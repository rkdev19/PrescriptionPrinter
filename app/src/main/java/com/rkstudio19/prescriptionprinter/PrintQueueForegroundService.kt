package com.rkstudio19.prescriptionprinter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class PrintQueueForegroundService : Service() {

    private lateinit var queueManager: PrintQueueManager
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastPollTimeMs = System.currentTimeMillis()

    companion object {
        private const val CHANNEL_ID = "prescription_printer_active"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 10_000L // safety-net check every 10s
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        queueManager = PrintQueueManager(applicationContext)
        lastPollTimeMs = System.currentTimeMillis()
        startSafetyNetPolling()
        PrinterDiscovery.start(applicationContext)
    }

    /**
     * Independent backup to the notification listener - queries MediaStore
     * every 10s for any WhatsApp image saved since the last check that
     * wasn't already caught by the notification-triggered path. Given the
     * high expected volume across many contacts/groups, this ensures an
     * occasional missed or delayed notification never means a missed
     * print - the shared "media-id:<id>" dedupe key means this never
     * causes a duplicate print either.
     */
    private fun startSafetyNetPolling() {
        pollHandler.postDelayed(object : Runnable {
            override fun run() {
                val checkStartedAt = System.currentTimeMillis()
                WhatsAppImageScanner.scanForNewImages(
                    context = applicationContext,
                    queueManager = queueManager,
                    sinceMs = lastPollTimeMs
                )
                lastPollTimeMs = checkStartedAt
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }, POLL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: Android restarts this service if it gets killed,
        // which matters for a "must never miss a print" daily-use app.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacksAndMessages(null)
        PrinterDiscovery.stop(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
}
