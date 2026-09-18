package com.rkstudio19.prescriptionprinter

import android.content.ContentUris
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Real-time trigger: fires whenever WhatsApp posts or updates a
 * notification, whether the app is open, backgrounded, or the screen is
 * off. Needs "Notification access" granted once in Settings.
 *
 * IMPORTANT: WhatsApp bundles rapid messages into ONE updated notification
 * (e.g. showing "2 new messages" as the visible summary) rather than
 * always posting a separate notification per message. Reading just the
 * top-level title/text - which an earlier version of this did - only
 * captures that summary line and loses the individual messages.
 *
 * The fix: notifications from messaging apps carry a structured
 * MessagingStyle payload with EACH individual message (sender, text, and
 * for media messages, a direct content:// URI to the actual file) even
 * when the visible summary is collapsed. We extract that instead of the
 * summary text, so every message in a burst is captured individually,
 * in order, with its real sender name - not just the last-seen summary.
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

        // Skip pure summary/group-bundle notifications - the real content
        // comes from the per-conversation notification's MessagingStyle,
        // which we read below regardless of whether it's also "grouped".
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "Skipping group summary notification")
            return
        }

        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)

        if (messagingStyle != null) {
            handleMessagingStyle(messagingStyle, sbn.postTime)
        } else {
            // Not a conversation-style notification (e.g. WhatsApp's own
            // "backup complete" or generic app notification) - ignore.
            Log.d(TAG, "Notification has no MessagingStyle payload, skipping")
        }
    }

    private fun handleMessagingStyle(style: NotificationCompat.MessagingStyle, postTimeMs: Long) {
        // style.messages contains the full recent history shown in this
        // notification, INCLUDING ones we may have already queued on a
        // previous update - dedupe by (sender + text + timestamp).
        for (message in style.messages) {
            val sender = message.person?.name?.toString()
                ?: style.conversationTitle?.toString()
                ?: "Unknown"
            val timestamp = message.timestamp
            val text = message.text?.toString()
            val imageUri = message.dataUri
            val isImage = imageUri != null && message.dataMimeType?.startsWith("image/") == true

            val dedupeKey = "$sender:${text ?: imageUri}:$timestamp"
            if (dedupeKey in processedMessageKeys) continue
            processedMessageKeys.add(dedupeKey)
            trimProcessedKeysIfNeeded()

            if (isImage) {
                queueManager.onItemReceived(
                    sourceKey = "notif-msg:$dedupeKey",
                    type = "IMAGE",
                    senderNumber = sender,
                    imagePath = imageUri.toString(),
                    textBody = null
                )
            } else if (!text.isNullOrBlank()) {
                queueManager.onItemReceived(
                    sourceKey = "notif-msg:$dedupeKey",
                    type = "TEXT",
                    senderNumber = sender,
                    imagePath = null,
                    textBody = text
                )
            } else {
                // Some WhatsApp versions send an image message with no
                // dataUri in the notification at all - fall back to the
                // MediaStore lookup as a best-effort for those cases.
                val fallback = findNewestWhatsAppImageSince(postTimeMs - 5000)
                if (fallback != null) {
                    queueManager.onItemReceived(
                        sourceKey = "mediastore:${fallback.first}",
                        type = "IMAGE",
                        senderNumber = sender,
                        imagePath = fallback.second,
                        textBody = null
                    )
                }
            }
        }
    }

    // Keep the dedupe set from growing forever across a long-running service.
    private fun trimProcessedKeysIfNeeded() {
        if (processedMessageKeys.size > 500) {
            val excess = processedMessageKeys.size - 300
            processedMessageKeys.toList().take(excess).forEach { processedMessageKeys.remove(it) }
        }
    }

    private fun findNewestWhatsAppImageSince(sinceMs: Long): Pair<Long, String>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
        val selectionArgs = arrayOf((sinceMs / 1000).toString(), "%WhatsApp Images%", "%WhatsApp Business%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = ContentUris.withAppendedId(collection, id)
                return id to uri.toString()
            }
        }
        return null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }
}
