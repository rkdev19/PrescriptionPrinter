package com.rkstudio19.prescriptionprinter

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One received WhatsApp item (an image or a text message) waiting to be
 * paired onto an A4 sheet and printed.
 *
 * [sourceKey] is how we dedupe: for images it's the media file's path +
 * last-modified timestamp, for text it's a hash of (sender + body + rough
 * timestamp). We check this BEFORE inserting a new row so a re-triggered
 * screen read never queues the same message twice.
 */
@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val type: String,          // "IMAGE" or "TEXT"
    val senderNumber: String?,
    val imagePath: String?,    // set when type == IMAGE
    val textBody: String?,     // set when type == TEXT
    val receivedAt: Long,      // System.currentTimeMillis()
    val status: String = "PENDING", // PENDING -> PRINTED
    val printedAt: Long? = null
)
