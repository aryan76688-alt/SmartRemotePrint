package com.smartremote.print.service

import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.widget.Toast
import com.smartremote.print.network.PrintApiClient
import com.smartremote.print.security.SecurityVault
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

class RemotePrintService : PrintService() {

    private lateinit var vault: SecurityVault
    private lateinit var apiClient: PrintApiClient
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        vault = SecurityVault(applicationContext)
        apiClient = PrintApiClient(vault)
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                val printerId = generatePrinterId("brother_dcp_t530dw_office")

                val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addResolution(PrintAttributes.Resolution("res_600", "600x600 dpi", 600, 600), true)
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                        PrintAttributes.COLOR_MODE_COLOR
                    )
                    .setDuplexModes(
                        PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                        PrintAttributes.DUPLEX_MODE_NONE
                    )
                    .build()

                val brotherPrinter = PrinterInfo.Builder(
                    printerId,
                    "Brother DCP-T530DW (Office PC)",
                    PrinterInfo.STATUS_IDLE
                )
                    .setDescription("Office Windows PC via Tailscale (${vault.serverHost}:8766)")
                    .setCapabilities(capabilities)
                    .build()

                addPrinters(listOf(brotherPrinter))
            }

            override fun onStopPrinterDiscovery() {}
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
            override fun onStartPrinterStateTracking(printerId: PrinterId) {}
            override fun onStopPrinterStateTracking(printerId: PrinterId) {}
            override fun onDestroy() {}
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val doc = printJob.document
        val docData = doc.data
        if (docData == null) {
            printJob.fail("No document data available")
            return
        }

        printJob.start()
        val docName = printJob.info.label ?: "Print_Document.pdf"
        val copies = printJob.info.copies

        mainHandler.post {
            Toast.makeText(applicationContext, "Transmitting print job to Office Brother Printer...", Toast.LENGTH_SHORT).show()
        }

        executor.execute {
            try {
                val input: InputStream = FileInputStream(docData.fileDescriptor)
                val pdfBytes = input.readBytes()
                input.close()

                // If any offline jobs exist, try flushing first
                if (OfflinePrintQueueManager.getPendingCount(applicationContext) > 0) {
                    OfflinePrintQueueManager.flushQueue(applicationContext, apiClient)
                }

                val result = apiClient.submitPrintJob(pdfBytes, docName, copies)

                mainHandler.post {
                    if (result.state == "Completed" || result.state == "Queued" || result.state == "Spooling") {
                        printJob.complete()
                        Toast.makeText(applicationContext, "✓ Job sent to Brother DCP-T530DW Spooler!", Toast.LENGTH_LONG).show()
                    } else {
                        // Feature 5: Zero Dropped Bills - enqueue locally for automatic spooling upon reconnect
                        OfflinePrintQueueManager.enqueue(applicationContext, pdfBytes, docName, copies)
                        printJob.complete()
                        Toast.makeText(applicationContext, "📥 PC Offline: Bill saved to queue. Auto-printing to Brother on reconnect!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    printJob.fail(e.message ?: "Failed to read document")
                    Toast.makeText(applicationContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}
