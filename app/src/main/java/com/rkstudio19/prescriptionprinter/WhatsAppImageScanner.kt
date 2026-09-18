package com.rkstudio19.prescriptionprinter

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * Single shared path for finding WhatsApp images via MediaStore, used by
 * BOTH the notification-triggered lookup and the periodic safety-net poll.
 * Both converge on the same "media-id:<id>" dedupe key for a given photo,
 * so whichever path finds an image first wins - the other is a no-op
 * thanks to PrintQueueManager's dedupe check. This guarantees an image is
 * never queued (and therefore never printed) twice, while still giving
 * two independent chances to catch it.
 */
object WhatsAppImageScanner {

    private const val TAG = "WhatsAppImageScanner"

    /**
     * @param senderNumber pass the sender name when known (notification
     *   path has it from MessagingStyle); pass null for the safety-net
     *   poll, which doesn't know who sent it - better an unattributed
     *   print than a missed one.
     */
    fun scanForNewImages(
        context: Context,
        queueManager: PrintQueueManager,
        sinceMs: Long,
        senderNumber: String? = null
    ) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
        val selectionArgs = arrayOf((sinceMs / 1000).toString(), "%WhatsApp Images%", "%WhatsApp Business%")

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    queueManager.onItemReceived(
                        sourceKey = "media-id:$id", // SAME key format used everywhere - this is what prevents double-printing
                        type = "IMAGE",
                        senderNumber = senderNumber,
                        imagePath = uri.toString(),
                        textBody = null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan failed: ${e.message}")
        }
    }
}
