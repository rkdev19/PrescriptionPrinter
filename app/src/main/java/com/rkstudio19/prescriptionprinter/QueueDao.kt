package com.rkstudio19.prescriptionprinter

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface QueueDao {

    // Dedup guard: called before every insert. If this returns non-null,
    // the item has already been seen and must NOT be queued again.
    @Query("SELECT * FROM queue_items WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): QueueItem?

    @Insert
    suspend fun insert(item: QueueItem): Long

    @Query("SELECT * FROM queue_items WHERE status = 'PENDING' ORDER BY receivedAt ASC")
    suspend fun getPending(): List<QueueItem>

    @Query("SELECT COUNT(*) FROM queue_items WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Update
    suspend fun update(item: QueueItem)

    @Query("UPDATE queue_items SET status = 'PRINTED', printedAt = :now WHERE id IN (:ids)")
    suspend fun markPrinted(ids: List<Long>, now: Long)
}
