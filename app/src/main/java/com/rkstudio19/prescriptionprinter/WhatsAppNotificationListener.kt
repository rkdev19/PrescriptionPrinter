package com.rkstudio19.prescriptionprinter

import android.app.Notification
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Replaces screen-reading as the primary trigger. This is a proper Android
 * API for "tell me when a notification arrives" - unlike the Accessibility
 * approach, it fires reliably whether WhatsApp is open, backgrounded, or
 * the phone screen is off, and WhatsApp notifications already carry the
 * contact name (as the notification title) and the message text, so we
 * get sender attribution for free instead of guessing it from the screen.
 *
 * This needs a SEPARATE permission from Accessibility - "Notification
 * access", granted once via Settings > Apps > Special access >
 * Notification access > Prescription Printer.
 *
 * For images: WhatsApp's notification text usually just says "Photo" (or
 * similar) rather than embedding the image, so on an image notification we
 * cross-check MediaStore for the newest WhatsApp image saved in the last
 * few seconds and attach the sender name from the notification to it -
 * this is what finally gives images a real sender name too.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var queueManager: PrintQueueManager

    companion object {
        private const val TAG = "WANotificationListener"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        // Words WhatsApp uses in the notification body when the message is
        // actually an image, not text - so we know to go check MediaStore.
        private val IMAGE_INDICATOR_WORDS = setOf("photo", "image", "📷")
    }

    override fun onCreate() {
        super.onCreate()
        queueManager = PrintQueueManager(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val extras = sbn.notification.extras
        val senderName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        if (senderName.isNullOrEmpty()) return

        // Group/summary notifications (Android bundles multiple messages
        // under one "N new messages" notification) don't carry a single
        // real message - skip those, we only want the per-message ones.
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) &&
            body.isNullOrEmpty()) return

        val looksLikeImage = body != null && IMAGE_INDICATOR_WORDS.any {
            body.lowercase().contains(it)
        }

        if (looksLikeImage) {
            handleImageNotification(senderName, sbn.postTime)
        } else if (!body.isNullOrEmpty()) {
            handleTextNotification(senderName, body, sbn.postTime)
        }
    }

    private fun handleTextNotification(senderName: String, body: String, postTimeMs: Long) {
        val sourceKey = "notif:text:$senderName:$body:${postTimeMs / 1000}"
        queueManager.onItemReceived(
            sourceKey = sourceKey,
            type = "TEXT",
            senderNumber = senderName,
            imagePath = null,
            textBody = body
        )
    }

    /**
     * The notification tells us "a photo arrived from X" but not the file
     * itself, so we look up the most recently added WhatsApp image within
     * a short window around the notification's timestamp and attach the
     * sender name to it. A tiny grace delay lets WhatsApp finish writing
     * the file before we query.
     */
    private fun handleImageNotification(senderName: String, postTimeMs: Long) {
        android.os.Handler(mainLooper).postDelayed({
            val newestImage = findNewestWhatsAppImageSince(postTimeMs - 5000)
            if (newestImage != null) {
                queueManager.onItemReceived(
                    sourceKey = "mediastore:${newestImage.first}",
                    type = "IMAGE",
                    senderNumber = senderName,
                    imagePath = newestImage.second,
                    textBody = null
                )
            } else {
                Log.w(TAG, "Image notification from $senderName but no matching MediaStore entry found yet")
            }
        }, 1500) // small grace window for WhatsApp to finish saving the file
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
