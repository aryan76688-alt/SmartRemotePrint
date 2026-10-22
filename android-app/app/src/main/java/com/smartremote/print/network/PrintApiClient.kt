package com.smartremote.print.network

import com.smartremote.print.security.SecurityVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class PrintApiClient(private val vault: SecurityVault) {

    private fun baseUrl(): String {
        return "http://${vault.serverHost}:${vault.serverPort}/api/v1"
    }

    data class HealthInfo(val status: String, val machineName: String, val defaultPrinter: String, val uptimeSeconds: Double)
    data class PrinterQueue(val name: String, val port: String, val isOnline: Boolean, val status: String)
    data class SpoolResult(val jobId: String, val state: String, val errorMessage: String?)

    fun checkHealth(): HealthInfo? {
        return try {
            val url = URL("${baseUrl()}/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                HealthInfo(
                    status = json.getString("status"),
                    machineName = json.getString("machineName"),
                    defaultPrinter = json.optString("defaultPrinter", "Brother DCP-T530DW Printer"),
                    uptimeSeconds = json.optDouble("uptimeSeconds", 0.0)
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun pairWithPin(pin: String, deviceId: String, deviceName: String): Pair<Boolean, String?> {
        return try {
            val url = URL("${baseUrl()}/pair")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("pairingPin", pin.trim())
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val token = json.getString("authToken")
                vault.saveAuthToken(token)
                Pair(true, "Paired successfully")
            } else {
                Pair(false, "Invalid pairing PIN or unauthorized (HTTP ${conn.responseCode})")
            }
        } catch (e: Exception) {
            Pair(false, "Connection error: ${e.message}")
        }
    }

    fun getPrinters(): List<PrinterQueue> {
        val list = mutableListOf<PrinterQueue>()
        val token = vault.getAuthToken() ?: return list
        try {
            val url = URL("${baseUrl()}/printers")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")

            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        PrinterQueue(
                            name = obj.getString("name"),
                            port = obj.optString("portName", "USB001"),
                            isOnline = obj.optBoolean("isOnline", true),
                            status = obj.optString("status", "Ready")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun submitPrintJob(pdfBytes: ByteArray, documentName: String, copies: Int = 1): SpoolResult {
        val token = vault.getAuthToken() ?: return SpoolResult("", "Failed", "Not paired with Windows Agent")
        return try {
            // RFC-compliant boundary without invalid characters like '='
            val boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "").take(16)
            val url = URL("${baseUrl()}/print")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val sha256 = calculateSha256(pdfBytes)
            val jobId = UUID.randomUUID().toString()
            val safeDocName = documentName.replace("\"", "").trim().ifBlank { "Marg_Invoice.pdf" }

            // Secondary metadata headers
            conn.setRequestProperty("X-Document-Name", safeDocName)
            conn.setRequestProperty("X-Printer-Name", vault.targetPrinter)
            conn.setRequestProperty("X-File-SHA256", sha256)
            conn.setRequestProperty("X-Copies", copies.toString())
            conn.doOutput = true

            val metadataJson = JSONObject().apply {
                put("jobId", jobId)
                put("printerName", vault.targetPrinter)
                put("documentName", safeDocName)
                put("copies", copies)
                put("fileSha256", sha256)
            }.toString()

            val baos = ByteArrayOutputStream()

            // Part 1: Metadata
            baos.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
            baos.write("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            baos.write(metadataJson.toByteArray(Charsets.UTF_8))
            baos.write("\r\n".toByteArray(Charsets.UTF_8))

            // Part 2: File
            baos.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
            baos.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeDocName\"\r\n".toByteArray(Charsets.UTF_8))
            baos.write("Content-Type: application/pdf\r\n\r\n".toByteArray(Charsets.UTF_8))
            baos.write(pdfBytes)
            baos.write("\r\n".toByteArray(Charsets.UTF_8))

            // End boundary
            baos.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))

            val payload = baos.toByteArray()
            conn.setFixedLengthStreamingMode(payload.size)

            conn.outputStream.use { os ->
                os.write(payload)
                os.flush()
            }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                SpoolResult(
                    jobId = json.getString("jobId"),
                    state = json.getString("state"),
                    errorMessage = null
                )
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                SpoolResult("", "Failed", "Server error (HTTP ${conn.responseCode}): $err")
            }
        } catch (e: Exception) {
            SpoolResult("", "Failed", "Network error: ${e.message}")
        }
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun pushSyncData(payloadJson: String): Boolean {
        return try {
            val url = URL("${baseUrl()}/sync/push")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 15000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            val token = vault.getAuthToken()
            if (!token.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.doOutput = true
            conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    fun getSyncStatus(): JSONObject? {
        return try {
            val url = URL("${baseUrl()}/sync/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
