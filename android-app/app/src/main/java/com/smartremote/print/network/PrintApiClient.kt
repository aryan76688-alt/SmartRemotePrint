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

    data class PrinterTelemetry(
        val printerName: String,
        val isOnline: Boolean,
        val state: String,
        val stateDetails: String,
        val paperStatus: String,
        val inkStatus: String,
        val coverStatus: String,
        val queuedJobsCount: Int
    )

    data class HealthInfo(
        val status: String,
        val machineName: String,
        val defaultPrinter: String,
        val uptimeSeconds: Double,
        val printerTelemetry: PrinterTelemetry? = null
    )
    data class PrinterQueue(val name: String, val port: String, val isOnline: Boolean, val status: String)
    data class SpoolResult(val jobId: String, val state: String, val errorMessage: String?)

    fun checkHealth(): HealthInfo? {
        val discovery = autoDiscoverAndConnect()
        return discovery?.second
    }

    fun autoDiscoverAndConnect(): Pair<String, HealthInfo>? {
        val candidates = linkedSetOf(
            vault.serverHost.trim(),
            "100.111.100.122",
            "desktop-00ctdtl.tailfc14cd.ts.net",
            "127.0.0.1"
        )

        for (host in candidates) {
            if (host.isBlank()) continue
            try {
                val url = URL("http://$host:${vault.serverPort}/api/v1/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val telemObj = json.optJSONObject("printerTelemetry")
                    val telemetry = if (telemObj != null) {
                        PrinterTelemetry(
                            printerName = telemObj.optString("printerName", json.optString("defaultPrinter", "Brother DCP-T530DW Printer")),
                            isOnline = telemObj.optBoolean("isOnline", true),
                            state = telemObj.optString("state", "Ready"),
                            stateDetails = telemObj.optString("stateDetails", "Ready"),
                            paperStatus = telemObj.optString("paperStatus", "OK"),
                            inkStatus = telemObj.optString("inkStatus", "OK"),
                            coverStatus = telemObj.optString("coverStatus", "Closed"),
                            queuedJobsCount = telemObj.optInt("queuedJobsCount", 0)
                        )
                    } else null

                    val health = HealthInfo(
                        status = json.getString("status"),
                        machineName = json.getString("machineName"),
                        defaultPrinter = json.optString("defaultPrinter", "Brother DCP-T530DW Printer"),
                        uptimeSeconds = json.optDouble("uptimeSeconds", 0.0),
                        printerTelemetry = telemetry
                    )

                    if (vault.serverHost != host) {
                        vault.serverHost = host
                    }
                    return Pair(host, health)
                }
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    fun getPrinterTelemetry(): PrinterTelemetry? {
        return try {
            val url = URL("${baseUrl()}/printer/telemetry")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val telemObj = JSONObject(conn.inputStream.bufferedReader().readText())
                PrinterTelemetry(
                    printerName = telemObj.optString("printerName", "Brother DCP-T530DW Printer"),
                    isOnline = telemObj.optBoolean("isOnline", true),
                    state = telemObj.optString("state", "Ready"),
                    stateDetails = telemObj.optString("stateDetails", "Ready"),
                    paperStatus = telemObj.optString("paperStatus", "OK"),
                    inkStatus = telemObj.optString("inkStatus", "OK"),
                    coverStatus = telemObj.optString("coverStatus", "Closed"),
                    queuedJobsCount = telemObj.optInt("queuedJobsCount", 0)
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
