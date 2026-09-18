package com.rkstudio19.prescriptionprinter

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.security.MessageDigest

/**
 * Scoped (see accessibility_service_config.xml) to only receive events from
 * com.whatsapp and com.whatsapp.w4b - it cannot see any other app.
 *
 * Two jobs:
 *  1. On a WhatsApp notification/window event, query MediaStore for any
 *     WhatsApp image saved since the last check -> queue it. Uses
 *     MediaStore (not direct file access) because Android 13's scoped
 *     storage silently blocks plain folder listing without it, which
 *     is why an earlier version of this found nothing.
 *  2. Read visible text nodes for a plain-text message bubble -> queue it
 *     with the sender's number (still being tuned against real devices -
 *     see maybeQueueText below).
 *
 * MediaStore queries work the same whether WhatsApp is open or
 * backgrounded, and typeNotificationStateChanged in the accessibility
 * config means a new-message notification alone is enough to trigger a
 * check even if the user never opens the chat.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    private lateinit var queueManager: PrintQueueManager
    private var lastCheckedAtMs: Long = 0L

    companion object {
        private const val TAG = "WAAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        queueManager = PrintQueueManager(applicationContext)
        // Baseline: don't treat photos already on the phone before the
        // service started as "new" - only images saved from this point on.
        lastCheckedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Service connected, baseline time: $lastCheckedAtMs")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detection now happens in WhatsAppNotificationListener, which is
        // more reliable (works backgrounded, gives real sender names via
        // MessagingStyle, doesn't misread stray UI text as a message).
        // This service is kept installed/enabled as a dormant fallback
        // only - intentionally not acting on events right now.
        return
    }

    override fun onInterrupt() {}

    /**
     * Queries MediaStore for images under WhatsApp's media folders whose
     * "date added" is after the last check. This is the scoped-storage-safe
     * way to find new WhatsApp photos - plain File().listFiles() on
     * Android 11+ without All-Files-Access returns nothing, which was the
     * root cause of images not being detected.
     */
    private fun checkForNewImagesViaMediaStore() {
        val checkStartedAt = System.currentTimeMillis()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        // RELATIVE_PATH covers both regular WhatsApp and WhatsApp Business image folders.
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
        val selectionArgs = arrayOf(
            (lastCheckedAtMs / 1000).toString(), // DATE_ADDED is in seconds
            "%WhatsApp Images%",
            "%WhatsApp Business%"
        )

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri: Uri = ContentUris.withAppendedId(collection, id)
                    queueManager.onItemReceived(
                        sourceKey = "mediastore:$id",
                        type = "IMAGE",
                        senderNumber = null, // MediaStore doesn't carry sender; refine later if needed
                        imagePath = contentUri.toString(), // PdfComposer needs to accept content:// URIs, not just file paths
                        textBody = null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed: ${e.message}")
        }

        lastCheckedAtMs = checkStartedAt
    }

    // NOTE: still being tuned against real WhatsApp screens - filters out
    // obvious UI chrome, but sender-number extraction isn't wired up yet.
    private val uiChromeBlocklist = setOf(
        "whatsapp", "type a message", "online", "typing...", "tap to type",
        "search", "camera", "attach", "voice message", "send"
    )

    private fun maybeQueueText(text: String, sourcePackage: String?) {
        if (sourcePackage != "com.whatsapp" && sourcePackage != "com.whatsapp.w4b") return
        if (text.length < 8) return // skip short UI chrome/labels/timestamps
        if (uiChromeBlocklist.any { text.lowercase().contains(it) }) return
        if (text.matches(Regex("^\\d{1,2}:\\d{2}\\s?(AM|PM|am|pm)?$"))) return // timestamps

        val hash = sha256("$sourcePackage:$text:${System.currentTimeMillis() / 60000}")
        queueManager.onItemReceived(
            sourceKey = hash,
            type = "TEXT",
            senderNumber = null,
            imagePath = null,
            textBody = text
        )
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
