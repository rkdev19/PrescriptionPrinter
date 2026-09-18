package com.rkstudio19.prescriptionprinter

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a print job directly to the printer's IPP endpoint over HTTP,
 * bypassing Android's Print Framework entirely - no system print dialog,
 * no user tap needed. This is the same protocol AirPrint/Mopria use, and
 * both the Epson M1120 and Canon LBP6030W advertise Wi-Fi/AirPrint support,
 * so both should accept a Print-Job request with document-format
 * "application/pdf".
 *
 * IMPORTANT - this needs to be verified against his actual two printers
 * before going live. Some printers accept PDF directly over IPP; a few
 * older/cheaper models only accept PCL/PostScript and silently reject or
 * mis-render raw PDF. If testing shows a printer rejects it, the fallback
 * is printPdfViaSystemDialog below, which works everywhere but needs one
 * tap per sheet.
 *
 * Printer IP is no longer hardcoded - PrinterDiscovery finds it live via
 * mDNS on whatever network the phone is on (needed since the client's
 * printer sits on a Jio dongle's Wi-Fi network, which hands out dynamic
 * IPs, not a fixed home-router reservation).
 */
object PrintHelper {

    private const val TAG = "PrintHelper"

    // TEST MODE: instead of attempting a real print, saves the composed
    // A4 PDF to Downloads so the layout can be checked by eye. OFF now -
    // this build goes to the client to test against the real printer.
    private const val DEBUG_SAVE_INSTEAD_OF_PRINT = false

    fun printPdf(context: android.content.Context, pdfPath: String, jobName: String): Boolean {
        if (DEBUG_SAVE_INSTEAD_OF_PRINT) {
            return saveToDownloadsForPreview(context, pdfPath, jobName)
        }
        val pdfBytes = java.io.File(pdfPath).readBytes()

        val epson = PrinterDiscovery.findPrinter("epson")
        val canon = PrinterDiscovery.findPrinter("canon")

        if (epson == null && canon == null) {
            Log.w(TAG, "No printer discovered on the network yet - discovered so far: ${PrinterDiscovery.allDiscovered()}")
            return false
        }

        val epsonSent = epson?.let { sendIppPrintJob(it.host, it.port, pdfBytes, jobName) } ?: false
        if (epsonSent) return true
        return canon?.let { sendIppPrintJob(it.host, it.port, pdfBytes, jobName) } ?: false
    }

    /**
     * Copies the composed sheet into the public Downloads folder (visible
     * to any file manager / PDF viewer) instead of printing, and shows a
     * notification so it's easy to find. Lets you check exactly how 2
     * images (or an image + text) land on the A4 layout without needing
     * a printer connected at all.
     */
    private fun saveToDownloadsForPreview(context: android.content.Context, pdfPath: String, jobName: String): Boolean {
        return try {
            val safeName = "PRESCRIPTION_PREVIEW_${jobName.replace(" ", "_")}.pdf"
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false

            resolver.openOutputStream(uri)?.use { out ->
                java.io.File(pdfPath).inputStream().use { it.copyTo(out) }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            notifyPreviewSaved(context, safeName)
            Log.d(TAG, "TEST MODE: saved preview PDF to Downloads/$safeName instead of printing")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preview PDF: ${e.message}")
            false
        }
    }

    private fun notifyPreviewSaved(context: android.content.Context, fileName: String) {
        val channelId = "print_preview"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Print previews", android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(context, channelId)
            .setContentTitle("Prescription sheet ready (TEST MODE)")
            .setContentText("Saved to Downloads/$fileName — open it to check the layout")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.notify(fileName.hashCode(), notification)
    }

    private fun sendIppPrintJob(printerIp: String, printerPort: Int, pdfBytes: ByteArray, jobName: String): Boolean {
        return try {
            val url = URL("http://$printerIp:$printerPort/ipp/print")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val ippRequest = buildIppPrintJobRequest(printerIp, printerPort, jobName, pdfBytes)
            DataOutputStream(conn.outputStream).use { it.write(ippRequest) }

            val responseCode = conn.responseCode
            Log.d(TAG, "IPP response from $printerIp:$printerPort: $responseCode")
            responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "IPP print to $printerIp:$printerPort failed: ${e.message}")
            false
        }
    }

    /**
     * Builds a minimal IPP/1.1 Print-Job request (operation id 0x0002) per
     * RFC 8010 - version, operation, request-id, operation-attributes-group,
     * end-of-attributes-tag, then the raw PDF as the document body.
     */
    private fun buildIppPrintJobRequest(printerIp: String, printerPort: Int, jobName: String, pdfBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)

        d.writeByte(0x01); d.writeByte(0x01)      // IPP version 1.1
        d.writeShort(0x0002)                       // operation-id: Print-Job
        d.writeInt(1)                               // request-id

        d.writeByte(0x01)                           // operation-attributes-tag
        writeAttribute(d, 0x47, "attributes-charset", "utf-8")
        writeAttribute(d, 0x48, "attributes-natural-language", "en")
        writeAttribute(d, 0x45, "printer-uri", "ipp://$printerIp:$printerPort/ipp/print")
        writeAttribute(d, 0x42, "job-name", jobName)
        writeAttribute(d, 0x49, "document-format", "application/pdf")

        d.writeByte(0x03)                           // end-of-attributes-tag
        d.write(pdfBytes)                            // document data

        return out.toByteArray()
    }

    private fun writeAttribute(d: DataOutputStream, valueTag: Int, name: String, value: String) {
        d.writeByte(valueTag)
        d.writeShort(name.length); d.writeBytes(name)
        d.writeShort(value.length); d.writeBytes(value)
    }

    /** Fallback for any printer that rejects raw IPP/PDF - opens Android's system print dialog. */
    fun printPdfViaSystemDialog(context: android.content.Context, pdfPath: String, jobName: String): Boolean {
        return try {
            val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val adapter = SystemDialogPdfAdapter(pdfPath)
            printManager.print(
                jobName, adapter,
                android.print.PrintAttributes.Builder()
                    .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(android.print.PrintAttributes.COLOR_MODE_MONOCHROME)
                    .build()
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "System dialog print fallback failed: ${e.message}")
            false
        }
    }

    private class SystemDialogPdfAdapter(private val pdfPath: String) : android.print.PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: android.print.PrintAttributes?,
            newAttributes: android.print.PrintAttributes?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: android.os.Bundle?
        ) {
            val info = android.print.PrintDocumentInfo.Builder("prescription.pdf")
                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: android.os.ParcelFileDescriptor?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            try {
                java.io.FileInputStream(java.io.File(pdfPath)).use { input ->
                    java.io.FileOutputStream(destination?.fileDescriptor).use { input.copyTo(it) }
                }
                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }
}
