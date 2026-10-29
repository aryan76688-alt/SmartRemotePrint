package com.smartremote.print.service

import android.content.Context
import com.smartremote.print.network.PrintApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Feature 5: Offline Print Queue & Auto-Resume (Zero Dropped Bills)
 *
 * Saves print jobs locally when Windows PC or network is offline/unreachable.
 * Automatically flushes and spools all saved bills in order as soon as connection is restored.
 */
object OfflinePrintQueueManager {

    private const val QUEUE_DIR_NAME = "offline_print_queue"
    private const val QUEUE_INDEX_FILE = "queue_index.json"
    private val lock = Any()

    data class QueuedPrintJob(
        val jobId: String,
        val documentName: String,
        val copies: Int,
        val filePath: String,
        val timestamp: Long,
        val fileSizeBytes: Long
    )

    private fun getQueueDir(context: Context): File {
        val dir = File(context.filesDir, QUEUE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getIndexFile(context: Context): File {
        return File(getQueueDir(context), QUEUE_INDEX_FILE)
    }

    private fun loadIndex(context: Context): MutableList<QueuedPrintJob> {
        val list = mutableListOf<QueuedPrintJob>()
        val indexFile = getIndexFile(context)
        if (!indexFile.exists()) return list

        try {
            val content = indexFile.readText(Charsets.UTF_8)
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val job = QueuedPrintJob(
                    jobId = obj.getString("jobId"),
                    documentName = obj.optString("documentName", "Marg_Invoice.pdf"),
                    copies = obj.optInt("copies", 1),
                    filePath = obj.getString("filePath"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L)
                )
                if (File(job.filePath).exists()) {
                    list.add(job)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveIndex(context: Context, list: List<QueuedPrintJob>) {
        try {
            val arr = JSONArray()
            for (job in list) {
                val obj = JSONObject().apply {
                    put("jobId", job.jobId)
                    put("documentName", job.documentName)
                    put("copies", job.copies)
                    put("filePath", job.filePath)
                    put("timestamp", job.timestamp)
                    put("fileSizeBytes", job.fileSizeBytes)
                }
                arr.put(obj)
            }
            getIndexFile(context).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Enqueues a print job into persistent local storage.
     */
    fun enqueue(context: Context, pdfBytes: ByteArray, documentName: String, copies: Int = 1): QueuedPrintJob {
        synchronized(lock) {
            val dir = getQueueDir(context)
            val jobId = UUID.randomUUID().toString()
            val safeDocName = documentName.trim().ifBlank { "Marg_Invoice.pdf" }
            val cleanDocName = if (safeDocName.endsWith(".pdf", ignoreCase = true)) safeDocName else "$safeDocName.pdf"
            val file = File(dir, "${jobId}.pdf")

            FileOutputStream(file).use { fos ->
                fos.write(pdfBytes)
                fos.flush()
            }

            val job = QueuedPrintJob(
                jobId = jobId,
                documentName = cleanDocName,
                copies = copies.coerceAtLeast(1),
                filePath = file.absolutePath,
                timestamp = System.currentTimeMillis(),
                fileSizeBytes = pdfBytes.size.toLong()
            )

            val current = loadIndex(context)
            current.add(job)
            saveIndex(context, current)
            return job
        }
    }

    /**
     * Returns count of pending offline print jobs.
     */
    fun getPendingCount(context: Context): Int {
        synchronized(lock) {
            return loadIndex(context).size
        }
    }

    /**
     * Returns list of pending offline print jobs.
     */
    fun getPendingJobs(context: Context): List<QueuedPrintJob> {
        synchronized(lock) {
            return loadIndex(context).toList()
        }
    }

    /**
     * Flushes queued jobs to the Windows Agent.
     * Stops if a connection/network error is encountered so no jobs are dropped.
     * Returns the count of successfully printed jobs.
     */
    fun flushQueue(context: Context, apiClient: PrintApiClient): Int {
        synchronized(lock) {
            val current = loadIndex(context)
            if (current.isEmpty()) return 0

            var successfulFlushes = 0
            val remaining = mutableListOf<QueuedPrintJob>()

            for (job in current) {
                val file = File(job.filePath)
                if (!file.exists()) {
                    continue
                }

                try {
                    val bytes = file.readBytes()
                    val result = apiClient.submitPrintJob(bytes, job.documentName, job.copies)

                    if (result.state == "Completed" || result.state == "Queued" || result.state == "Spooling") {
                        file.delete()
                        successfulFlushes++
                    } else {
                        // Connection failed or PC offline - stop flushing and keep remaining jobs
                        remaining.add(job)
                        val idx = current.indexOf(job)
                        if (idx + 1 < current.size) {
                            remaining.addAll(current.subList(idx + 1, current.size))
                        }
                        break
                    }
                } catch (e: Exception) {
                    remaining.add(job)
                    val idx = current.indexOf(job)
                    if (idx + 1 < current.size) {
                        remaining.addAll(current.subList(idx + 1, current.size))
                    }
                    break
                }
            }

            saveIndex(context, remaining)
            return successfulFlushes
        }
    }

    /**
     * Clears all queued offline jobs.
     */
    fun clearQueue(context: Context) {
        synchronized(lock) {
            val dir = getQueueDir(context)
            dir.listFiles()?.forEach { it.delete() }
            getIndexFile(context).delete()
        }
    }
}
