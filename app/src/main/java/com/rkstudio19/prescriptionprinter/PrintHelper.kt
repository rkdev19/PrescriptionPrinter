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
 * PRINTER_IP must be set to each printer's static/reserved LAN IP -
 * ask him to set a DHCP reservation for both printers on his router so
 * these don't drift.
 */
object PrintHelper {

    private const val TAG = "PrintHelper"
    private const val IPP_PORT = 631

    // TODO: fill in once his printers are on the network - use a DHCP
    // reservation so these don't change.
    private const val EPSON_IP = "192.168.1.50"
    private const val CANON_IP = "192.168.1.51"

    fun printPdf(context: android.content.Context, pdfPath: String, jobName: String): Boolean {
        val pdfBytes = java.io.File(pdfPath).readBytes()
        // Try Epson first, then Canon, as a simple "whichever printer is on" strategy.
        // Refine to explicit printer selection once he confirms which printer
        // should handle prescriptions vs other jobs.
        return sendIppPrintJob(EPSON_IP, pdfBytes, jobName)
            || sendIppPrintJob(CANON_IP, pdfBytes, jobName)
    }

    private fun sendIppPrintJob(printerIp: String, pdfBytes: ByteArray, jobName: String): Boolean {
        return try {
            val url = URL("http://$printerIp:$IPP_PORT/ipp/print")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val ippRequest = buildIppPrintJobRequest(printerIp, jobName, pdfBytes)
            DataOutputStream(conn.outputStream).use { it.write(ippRequest) }

            val responseCode = conn.responseCode
            Log.d(TAG, "IPP response from $printerIp: $responseCode")
            responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "IPP print to $printerIp failed: ${e.message}")
            false
        }
    }

    /**
     * Builds a minimal IPP/1.1 Print-Job request (operation id 0x0002) per
     * RFC 8010 - version, operation, request-id, operation-attributes-group,
     * end-of-attributes-tag, then the raw PDF as the document body.
     */
    private fun buildIppPrintJobRequest(printerIp: String, jobName: String, pdfBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)

        d.writeByte(0x01); d.writeByte(0x01)      // IPP version 1.1
        d.writeShort(0x0002)                       // operation-id: Print-Job
        d.writeInt(1)                               // request-id

        d.writeByte(0x01)                           // operation-attributes-tag
        writeAttribute(d, 0x47, "attributes-charset", "utf-8")
        writeAttribute(d, 0x48, "attributes-natural-language", "en")
        writeAttribute(d, 0x45, "printer-uri", "ipp://$printerIp:$IPP_PORT/ipp/print")
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
