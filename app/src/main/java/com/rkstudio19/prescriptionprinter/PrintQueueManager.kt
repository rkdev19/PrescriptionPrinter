package com.rkstudio19.prescriptionprinter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the "2 items per A4 sheet, nothing left unprinted, nothing printed
 * twice" rule.
 *
 * Flow:
 *  - New item arrives -> dedupe check -> insert as PENDING
 *  - If pending count reaches 2 -> compose both onto one A4 sheet -> print -> mark PRINTED
 *  - If only 1 is pending, start (or restart) a timeout. If nothing new
 *    arrives before it fires, print that single item alone so it never
 *    sits unprinted indefinitely.
 *
 * Every print attempt marks rows PRINTED only AFTER the print job is
 * successfully handed to PrintHelper - if printing fails, rows stay
 * PENDING and get retried, so nothing silently disappears.
 */
class PrintQueueManager(private val context: Context) {

    private val dao = AppDatabase.get(context).queueDao()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var timeoutJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "PrintQueueManager"
        private const val LONE_ITEM_TIMEOUT_MS = 3 * 60 * 1000L // 3 minutes
    }

    /** Call this whenever the accessibility service detects a new image or text. */
    fun onItemReceived(
        sourceKey: String,
        type: String,
        senderNumber: String?,
        imagePath: String?,
        textBody: String?
    ) {
        scope.launch {
            val existing = dao.findBySourceKey(sourceKey)
            if (existing != null) {
                Log.d(TAG, "Duplicate item, skipping: $sourceKey")
                return@launch
            }

            dao.insert(
                QueueItem(
                    sourceKey = sourceKey,
                    type = type,
                    senderNumber = senderNumber,
                    imagePath = imagePath,
                    textBody = textBody,
                    receivedAt = System.currentTimeMillis()
                )
            )

            checkAndProcessQueue()
        }
    }

    private suspend fun checkAndProcessQueue() {
        val pending = dao.getPending()

        when {
            pending.size >= 2 -> {
                // Sheet full - print the oldest 2 immediately, cancel any lone-item timer
                timeoutJob?.cancel()
                printPair(pending[0], pending[1])
                // If a 3rd arrived in the meantime, loop again for it
                if (dao.pendingCount() >= 2) checkAndProcessQueue()
                else if (dao.pendingCount() == 1) armLoneItemTimeout()
            }
            pending.size == 1 -> {
                armLoneItemTimeout()
            }
            else -> {
                timeoutJob?.cancel()
            }
        }
    }

    private fun armLoneItemTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(LONE_ITEM_TIMEOUT_MS)
            val pending = dao.getPending()
            if (pending.size == 1) {
                Log.d(TAG, "No pair arrived in time, printing single item alone")
                printSingle(pending[0])
            }
            // if pending.size became 2+ while we waited, checkAndProcessQueue()
            // already handled it and cancelled this job before it fired
        }
    }

    private suspend fun printPair(a: QueueItem, b: QueueItem) {
        val pdfPath = PdfComposer.composeTwoUp(context, a, b)
        val sent = PrintHelper.printPdf(context, pdfPath, jobName = "Prescriptions ${a.id}-${b.id}")
        if (sent) {
            dao.markPrinted(listOf(a.id, b.id), System.currentTimeMillis())
        } else {
            Log.e(TAG, "Print job failed to submit, leaving items PENDING for retry")
        }
    }

    private suspend fun printSingle(item: QueueItem) {
        val pdfPath = PdfComposer.composeSingle(context, item)
        val sent = PrintHelper.printPdf(context, pdfPath, jobName = "Prescription ${item.id}")
        if (sent) {
            dao.markPrinted(listOf(item.id), System.currentTimeMillis())
        } else {
            Log.e(TAG, "Print job failed to submit, leaving item PENDING for retry")
        }
    }
}
