package com.rkstudio19.prescriptionprinter

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Real-time trigger: fires whenever WhatsApp posts or updates a
 * notification, whether the app is open, backgrounded, or the screen is
 * off. Needs "Notification access" granted once in Settings.
 *
 * IMAGES ONLY for this version - text handling is deferred to the next
 * version so image detection can be made fully reliable first, given the
 * expected volume across individual contacts and groups.
 *
 * WhatsApp bundles rapid messages into ONE updated notification (e.g.
 * showing "2 new messages" as the visible summary) rather than always
 * posting a separate notification per message. We read the structured
 * MessagingStyle payload (which lists each individual message) instead
 * of the summary text, so a burst of images is never collapsed into one
 * missed/garbled entry.
 *
 * For the actual image file, this always resolves through
 * WhatsAppImageScanner (a MediaStore query) rather than trusting
 * WhatsApp's own notification dataUri directly - that keeps the dedupe
 * key ("media-id:<id>") identical to what the periodic safety-net poll
 * uses, which is what guarantees an image is never queued (and never
 * printed) twice even though two independent paths can both find it.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var queueManager: PrintQueueManager
    private val processedMessageKeys = mutableSetOf<String>()

    companion object {
        private const val TAG = "WANotificationListener"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    override fun onCreate() {
        super.onCreate()
        queueManager = PrintQueueManager(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "Skipping group summary notification")
            return
        }

        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)

        if (messagingStyle != null) {
            val notificationTitle = sbn.notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            handleMessagingStyle(messagingStyle, sbn.postTime, notificationTitle)
        } else {
            Log.d(TAG, "Notification has no MessagingStyle payload, skipping")
        }
    }

    private fun handleMessagingStyle(
        style: NotificationCompat.MessagingStyle,
        postTimeMs: Long,
        notificationTitle: String?
    ) {
        for (message in style.messages) {
            // For 1-on-1 chats WhatsApp often leaves message.person empty
            // (only groups reliably tag the individual sender per-message)
            // - the notification's own title is the contact's name in
            // that case, so fall back to it before giving up to "Unknown".
            val sender = message.person?.name?.toString()
                ?: style.conversationTitle?.toString()
                ?: notificationTitle
                ?: "Unknown"
            val timestamp = message.timestamp
            val text = message.text?.toString()
            val looksLikeImage = message.dataMimeType?.startsWith("image/") == true ||
                    (text.isNullOrBlank()) // WhatsApp often leaves text empty for a pure image message

            val dedupeKey = "burst:$sender:${text ?: "image"}:$timestamp"
            if (dedupeKey in processedMessageKeys) continue
            processedMessageKeys.add(dedupeKey)
            trimProcessedKeysIfNeeded()

            if (looksLikeImage) {
                // Register the sender immediately, before we even go looking
                // for the file - this is what lets the safety-net poll (if
                // it happens to find the file first) still attribute it
                // correctly instead of showing "Unknown".
                PendingSenderRegistry.addPending(timestamp, sender)

                android.os.Handler(mainLooper).postDelayed({
                    WhatsAppImageScanner.scanForNewImages(
                        context = applicationContext,
                        queueManager = queueManager,
                        sinceMs = postTimeMs - 5000,
                        senderNumber = sender
                    )
                }, 1500)
            } else {
                Log.d(TAG, "Skipping text message (deferred to next version): $sender")
            }
        }
    }

    private fun trimProcessedKeysIfNeeded() {
        if (processedMessageKeys.size > 500) {
            val excess = processedMessageKeys.size - 300
            processedMessageKeys.toList().take(excess).forEach { processedMessageKeys.remove(it) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }
}
