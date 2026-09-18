package com.rkstudio19.prescriptionprinter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Environment
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.security.MessageDigest

/**
 * Scoped (see accessibility_service_config.xml) to only receive events from
 * com.whatsapp and com.whatsapp.w4b - it cannot see any other app.
 *
 * Two jobs:
 *  1. On a WhatsApp notification/window event, check the WhatsApp media
 *     folder for a new image file that wasn't there before -> queue it.
 *  2. Read visible text nodes for a plain-text message bubble -> queue it
 *     with the sender's number.
 *
 * Deliberately does NOT try to auto-open chats or simulate taps - reading
 * the notification + the already-synced WhatsApp media folder is more
 * reliable than driving the UI, and avoids interfering with the user
 * actively using WhatsApp at the same time.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    private lateinit var queueManager: PrintQueueManager
    private var knownMediaFiles: MutableSet<String> = mutableSetOf()

    companion object {
        private const val TAG = "WAAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        queueManager = PrintQueueManager(applicationContext)
        knownMediaFiles = listCurrentWhatsAppImages().toMutableSet()
        Log.d(TAG, "Service connected, baseline media count: ${knownMediaFiles.size}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Any WhatsApp window/content change is our cue to check for new media.
        checkForNewImages()

        // Only text-bubble events carry readable message text.
        val text = event.text?.joinToString(" ")?.trim()
        if (!text.isNullOrEmpty()) {
            maybeQueueText(text, event.packageName?.toString())
        }
    }

    override fun onInterrupt() {}

    private fun checkForNewImages() {
        val current = listCurrentWhatsAppImages()
        val newFiles = current - knownMediaFiles
        for (path in newFiles) {
            val file = File(path)
            val sourceKey = "${file.absolutePath}:${file.lastModified()}"
            queueManager.onItemReceived(
                sourceKey = sourceKey,
                type = "IMAGE",
                senderNumber = null, // WhatsApp media filenames don't carry sender; refined in v2 if he needs it
                imagePath = path,
                textBody = null
            )
        }
        knownMediaFiles = current.toMutableSet()
    }

    private fun listCurrentWhatsAppImages(): Set<String> {
        // Standard WhatsApp media path; WhatsApp Business uses a parallel folder.
        val roots = listOf(
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Images"),
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images")
        )
        val result = mutableSetOf<String>()
        for (root in roots) {
            root.listFiles { f -> f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".jpeg")) }
                ?.forEach { result.add(it.absolutePath) }
        }
        return result
    }

    // NOTE: reliably reading "this text bubble + this sender number" from
    // arbitrary WhatsApp screen nodes needs tuning against the real device -
    // this is the part most likely to need adjustment once we test on his phone.
    private fun maybeQueueText(text: String, sourcePackage: String?) {
        if (sourcePackage != "com.whatsapp" && sourcePackage != "com.whatsapp.w4b") return
        if (text.length < 3) return // skip UI chrome/button labels

        val hash = sha256("$sourcePackage:$text:${System.currentTimeMillis() / 60000}") // 1-min bucket dedupe
        queueManager.onItemReceived(
            sourceKey = hash,
            type = "TEXT",
            senderNumber = null, // filled in once we tune extraction against his real chats
            imagePath = null,
            textBody = text
        )
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
