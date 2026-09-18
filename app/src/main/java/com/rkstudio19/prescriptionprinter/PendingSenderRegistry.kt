package com.rkstudio19.prescriptionprinter

/**
 * WhatsAppNotificationListener knows the sender's name the instant a
 * notification arrives - but the actual image file sometimes gets
 * discovered by the periodic safety-net poll instead of the fast
 * notification-triggered lookup (a race, since both can independently
 * find the same file). The poll has no sender info of its own.
 *
 * This bridges the two: the notification listener registers "a message
 * from X arrived around time T" here immediately, before it even starts
 * looking for the file. Whichever path (fast notification lookup or the
 * 10s safety poll) actually finds the image, it can look up the nearest
 * registered sender by timestamp and use that - so sender attribution no
 * longer depends on which of the two paths wins the race.
 */
object PendingSenderRegistry {

    private data class Entry(val timestampMs: Long, val sender: String)

    private val pending = mutableListOf<Entry>()
    private const val MAX_AGE_MS = 2 * 60 * 1000L // drop stale entries after 2 minutes

    @Synchronized
    fun addPending(timestampMs: Long, sender: String) {
        pending.add(Entry(timestampMs, sender))
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        pending.removeAll { it.timestampMs < cutoff }
    }

    /**
     * Finds and consumes the closest unclaimed sender entry within
     * [windowMs] of [mediaTimestampMs]. Consuming (removing) it means a
     * burst of several images from different senders each get matched to
     * their own nearest entry rather than all claiming the same one.
     */
    @Synchronized
    fun consumeNearestSender(mediaTimestampMs: Long, windowMs: Long = 15_000L): String? {
        val candidate = pending.minByOrNull { kotlin.math.abs(it.timestampMs - mediaTimestampMs) } ?: return null
        return if (kotlin.math.abs(candidate.timestampMs - mediaTimestampMs) <= windowMs) {
            pending.remove(candidate)
            candidate.sender
        } else null
    }
}
