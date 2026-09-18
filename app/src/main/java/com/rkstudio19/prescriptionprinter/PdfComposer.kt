package com.rkstudio19.prescriptionprinter

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Composes queue items onto a single A4 page (595 x 842 pt at 72dpi) split
 * into a top half and bottom half, so exactly 2 items share one printed
 * sheet. A single leftover item gets the top half only, bottom half blank.
 */
object PdfComposer {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 24f

    fun composeTwoUp(context: Context, a: QueueItem, b: QueueItem): String {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val halfHeight = (PAGE_HEIGHT - MARGIN * 3) / 2
        drawItem(canvas, a, RectF(MARGIN, MARGIN, PAGE_WIDTH - MARGIN, MARGIN + halfHeight))
        drawItem(canvas, b, RectF(MARGIN, MARGIN * 2 + halfHeight, PAGE_WIDTH - MARGIN, MARGIN * 2 + halfHeight * 2))

        document.finishPage(page)
        return saveAndClose(context, document, "sheet_${a.id}_${b.id}")
    }

    fun composeSingle(context: Context, item: QueueItem): String {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val halfHeight = (PAGE_HEIGHT - MARGIN * 3) / 2
        drawItem(canvas, item, RectF(MARGIN, MARGIN, PAGE_WIDTH - MARGIN, MARGIN + halfHeight))

        document.finishPage(page)
        return saveAndClose(context, document, "single_${item.id}")
    }

    private fun drawItem(canvas: Canvas, item: QueueItem, bounds: RectF) {
        val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = 0xFFCCCCCC.toInt() }
        canvas.drawRect(bounds, borderPaint)

        if (item.type == "IMAGE" && item.imagePath != null) {
            val bitmap = BitmapFactory.decodeFile(item.imagePath) ?: return
            val scale = minOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = bounds.left + (bounds.width() - w) / 2
            val top = bounds.top + (bounds.height() - h) / 2
            val destRect = RectF(left, top, left + w, top + h)
            canvas.drawBitmap(bitmap, null, destRect, null)
        } else if (item.type == "TEXT") {
            val headerPaint = Paint().apply { textSize = 12f; isFakeBoldText = true; color = 0xFF000000.toInt() }
            val bodyPaint = Paint().apply { textSize = 11f; color = 0xFF000000.toInt() }
            var y = bounds.top + 20f
            val header = "From: ${item.senderNumber ?: "Unknown"}"
            canvas.drawText(header, bounds.left + 8f, y, headerPaint)
            y += 20f
            // naive word-wrap within the box width
            val maxWidth = bounds.width() - 16f
            for (line in wrapText(item.textBody.orEmpty(), bodyPaint, maxWidth)) {
                if (y > bounds.bottom - 8f) break
                canvas.drawText(line, bounds.left + 8f, y, bodyPaint)
                y += 16f
            }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val trial = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(trial) > maxWidth) {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(trial)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun saveAndClose(context: Context, document: PdfDocument, name: String): String {
        val dir = File(context.filesDir, "print_jobs").apply { mkdirs() }
        val file = File(dir, "$name.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file.absolutePath
    }
}
