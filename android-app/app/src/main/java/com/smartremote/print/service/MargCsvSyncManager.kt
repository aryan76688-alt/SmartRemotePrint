package com.smartremote.print.service

import android.content.Context
import android.os.Environment
import com.smartremote.print.network.PrintApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StockItemData(
    val itemCode: String,
    val itemName: String,
    val packing: String,
    val batch: String,
    val expiry: String,
    val mrp: Double,
    val rate: Double,
    val currentStock: Int,
    val company: String
)

data class PartyData(
    val partyCode: String,
    val partyName: String,
    val address: String,
    val phone: String,
    val totalOutstanding: Double,
    val overdueAmount: Double,
    val creditLimit: Double,
    val billCount: Int,
    val lastBillDate: String
)

data class CsvAnalysisResult(
    val isStock: Boolean,
    val isParty: Boolean,
    val totalStockItems: Int,
    val totalStockUnits: Int,
    val totalInventoryValuation: Double,
    val totalParties: Int,
    val totalOutstanding: Double,
    val totalOverdue: Double,
    val backupFile: File,
    val syncedOnline: Boolean,
    val summaryMessage: String
)

object MargCsvSyncManager {

    private const val OFFLINE_CACHE_FILE = "marg_offline_cache.json"
    private const val PENDING_SYNC_FILE = "pending_sync.json"

    fun getMargCloudDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "Marg Cloud")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBackupsDir(): File {
        val dir = File(getMargCloudDir(), "Backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAnalysisDir(): File {
        val dir = File(getMargCloudDir(), "Analysis")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSyncDir(): File {
        val dir = File(getMargCloudDir(), "Sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun ensureAllDirectories(): Map<String, File> {
        return mapOf(
            "root" to getMargCloudDir(),
            "backups" to getBackupsDir(),
            "analysis" to getAnalysisDir(),
            "sync" to getSyncDir()
        )
    }

    /**
     * Creates a dated, non-destructive backup of any downloaded CSV file.
     */
    fun createTimestampedBackup(sourceFile: File): File {
        val backupsDir = getBackupsDir()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val baseName = sourceFile.nameWithoutExtension
        val ext = sourceFile.extension.ifBlank { "csv" }
        val backupFile = File(backupsDir, "${timestamp}_${baseName}.${ext}")
        sourceFile.copyTo(backupFile, overwrite = true)
        return backupFile
    }

    /**
     * Processes, backs up, analyzes, and synchronizes a downloaded CSV file.
     */
    fun processDownloadedCsv(
        context: Context,
        csvFile: File,
        apiClient: PrintApiClient,
        isOnline: Boolean
    ): CsvAnalysisResult {
        ensureAllDirectories()

        // 1. Immediately create timestamped backup
        val backupFile = createTimestampedBackup(csvFile)

        // 2. Parse CSV content
        val text = csvFile.readText(Charsets.UTF_8)
        val isStock = text.contains("item", ignoreCase = true) || text.contains("stock", ignoreCase = true) || text.contains("qty", ignoreCase = true)
        val isParty = text.contains("party", ignoreCase = true) || text.contains("ledger", ignoreCase = true) || text.contains("outstanding", ignoreCase = true)

        var stockList = emptyList<StockItemData>()
        var partyList = emptyList<PartyData>()

        if (isStock) {
            stockList = parseStockCsv(text)
        }
        if (isParty) {
            partyList = parsePartyCsv(text)
        }

        // 3. Compute Analytics
        val totalStockItems = stockList.size
        val totalUnits = stockList.sumOf { it.currentStock }
        val valuation = stockList.sumOf { it.currentStock * (if (it.rate > 0) it.rate else it.mrp) }

        val totalParties = partyList.size
        val totalOutstanding = partyList.sumOf { it.totalOutstanding }
        val totalOverdue = partyList.sumOf { it.overdueAmount }

        // 4. Update Local Offline Cache
        updateOfflineCache(context, stockList, partyList)

        // 5. Build payload for synchronization
        val payloadObj = JSONObject().apply {
            if (stockList.isNotEmpty()) {
                val sArr = JSONArray()
                for (s in stockList) {
                    sArr.put(JSONObject().apply {
                        put("ItemCode", s.itemCode)
                        put("ItemName", s.itemName)
                        put("Packing", s.packing)
                        put("Batch", s.batch)
                        put("Expiry", s.expiry)
                        put("Mrp", s.mrp)
                        put("Rate", s.rate)
                        put("CurrentStock", s.currentStock)
                        put("Company", s.company)
                    })
                }
                put("StockItems", sArr)
            }
            if (partyList.isNotEmpty()) {
                val pArr = JSONArray()
                for (p in partyList) {
                    pArr.put(JSONObject().apply {
                        put("PartyCode", p.partyCode)
                        put("PartyName", p.partyName)
                        put("Address", p.address)
                        put("Phone", p.phone)
                        put("TotalOutstanding", p.totalOutstanding)
                        put("OverdueAmount", p.overdueAmount)
                        put("CreditLimit", p.creditLimit)
                        put("BillCount", p.billCount)
                        put("LastBillDate", p.lastBillDate)
                    })
                }
                put("Parties", pArr)
            }
        }

        // 6. Push to Windows Agent if online, else queue for auto-sync
        var syncedOnline = false
        val payloadString = payloadObj.toString()

        if (isOnline) {
            syncedOnline = apiClient.pushSyncData(payloadString)
        }

        if (!syncedOnline) {
            queuePendingSync(context, payloadString)
        }

        // 7. Save Analysis report file in Analysis/
        val summaryFile = File(getAnalysisDir(), "last_analysis.json")
        val analysisJson = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("sourceFile", csvFile.name)
            put("backupFile", backupFile.name)
            put("totalStockItems", totalStockItems)
            put("totalUnits", totalUnits)
            put("totalValuation", valuation)
            put("totalParties", totalParties)
            put("totalOutstanding", totalOutstanding)
            put("totalOverdue", totalOverdue)
            put("syncedOnline", syncedOnline)
        }
        summaryFile.writeText(analysisJson.toString(2))

        val summary = if (isStock && isParty) {
            "📊 Analyzed: $totalStockItems items (₹${String.format(Locale.US, "%.0f", valuation)}), $totalParties parties (₹${String.format(Locale.US, "%.0f", totalOutstanding)} bal)"
        } else if (isStock) {
            "📦 Stock Analyzed: $totalStockItems items, $totalUnits units (₹${String.format(Locale.US, "%.0f", valuation)} valuation)"
        } else if (isParty) {
            "👥 Parties Analyzed: $totalParties parties, ₹${String.format(Locale.US, "%.0f", totalOutstanding)} total (₹${String.format(Locale.US, "%.0f", totalOverdue)} overdue)"
        } else {
            "📄 CSV Backed Up: ${backupFile.name}"
        }

        return CsvAnalysisResult(
            isStock = isStock,
            isParty = isParty,
            totalStockItems = totalStockItems,
            totalStockUnits = totalUnits,
            totalInventoryValuation = valuation,
            totalParties = totalParties,
            totalOutstanding = totalOutstanding,
            totalOverdue = totalOverdue,
            backupFile = backupFile,
            syncedOnline = syncedOnline,
            summaryMessage = summary
        )
    }

    private fun parseStockCsv(csv: String): List<StockItemData> {
        val list = mutableListOf<StockItemData>()
        val lines = csv.split(Regex("[\r\n]+")).filter { it.isNotBlank() }
        if (lines.size < 2) return list

        val headers = lines[0].split(",").map { it.trim().lowercase(Locale.ROOT) }
        val idxCode = findHeader(headers, "code", "itemcode", "item_code", "pcode")
        val idxName = findHeader(headers, "name", "itemname", "item_name", "particular", "description")
        val idxPack = findHeader(headers, "pack", "packing", "pkg")
        val idxBatch = findHeader(headers, "batch", "batchno", "bno")
        val idxExp = findHeader(headers, "exp", "expiry", "expdate")
        val idxMrp = findHeader(headers, "mrp")
        val idxRate = findHeader(headers, "rate", "salerate", "prate", "price")
        val idxStock = findHeader(headers, "stock", "qty", "currentstock", "balance", "clqty")
        val idxCompany = findHeader(headers, "company", "mfg", "manufacturer")

        for (i in 1 until lines.size) {
            val parts = parseCsvLine(lines[i])
            if (parts.size <= maxOf(idxCode, idxName)) continue

            var code = getVal(parts, idxCode)
            val name = getVal(parts, idxName)
            if (code.isBlank() && name.isBlank()) continue
            if (code.isBlank()) code = name

            val pack = getVal(parts, idxPack)
            val batch = getVal(parts, idxBatch)
            val exp = getVal(parts, idxExp)
            val comp = getVal(parts, idxCompany)

            val mrp = getVal(parts, idxMrp).toDoubleOrNull() ?: 0.0
            val rate = getVal(parts, idxRate).toDoubleOrNull() ?: 0.0
            val stock = getVal(parts, idxStock).toIntOrNull() ?: 0

            list.add(StockItemData(code, name, pack, batch, exp, mrp, rate, stock, comp))
        }
        return list
    }

    private fun parsePartyCsv(csv: String): List<PartyData> {
        val list = mutableListOf<PartyData>()
        val lines = csv.split(Regex("[\r\n]+")).filter { it.isNotBlank() }
        if (lines.size < 2) return list

        val headers = lines[0].split(",").map { it.trim().lowercase(Locale.ROOT) }
        val idxCode = findHeader(headers, "code", "partycode", "pcode", "acc_code")
        val idxName = findHeader(headers, "name", "partyname", "party", "ledger", "account", "customer")
        val idxAddr = findHeader(headers, "address", "addr", "city")
        val idxPhone = findHeader(headers, "phone", "mobile", "tel")
        val idxTotal = findHeader(headers, "totaloutstanding", "outstanding", "balance", "bal", "net_balance")
        val idxOverdue = findHeader(headers, "overdue", "due", "overdueamount")
        val idxLimit = findHeader(headers, "limit", "creditlimit", "cr_limit")
        val idxBills = findHeader(headers, "bills", "billcount", "nobills")
        val idxDate = findHeader(headers, "lastbilldate", "lastdate", "billdate")

        for (i in 1 until lines.size) {
            val parts = parseCsvLine(lines[i])
            if (parts.size <= maxOf(idxCode, idxName)) continue

            var code = getVal(parts, idxCode)
            val name = getVal(parts, idxName)
            if (code.isBlank() && name.isBlank()) continue
            if (code.isBlank()) code = name

            val addr = getVal(parts, idxAddr)
            val phone = getVal(parts, idxPhone)
            val date = getVal(parts, idxDate)

            val total = getVal(parts, idxTotal).toDoubleOrNull() ?: 0.0
            val overdue = getVal(parts, idxOverdue).toDoubleOrNull() ?: 0.0
            val limit = getVal(parts, idxLimit).toDoubleOrNull() ?: 0.0
            val bills = getVal(parts, idxBills).toIntOrNull() ?: 0

            list.add(PartyData(code, name, addr, phone, total, overdue, limit, bills, date))
        }
        return list
    }

    private fun findHeader(headers: List<String>, vararg candidates: String): Int {
        for (i in headers.indices) {
            val h = headers[i]
            for (c in candidates) {
                if (h.contains(c)) return i
            }
        }
        return -1
    }

    private fun getVal(parts: List<String>, idx: Int): String {
        return if (idx in parts.indices) parts[idx].trim().trim('"') else ""
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val sb = StringBuilder()
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }

    private fun updateOfflineCache(context: Context, newStock: List<StockItemData>, newParties: List<PartyData>) {
        try {
            val file = File(context.filesDir, OFFLINE_CACHE_FILE)
            val cacheObj = if (file.exists()) JSONObject(file.readText()) else JSONObject()

            val stockMap = mutableMapOf<String, StockItemData>()
            val partyMap = mutableMapOf<String, PartyData>()

            // Load existing
            if (cacheObj.has("stock")) {
                val sArr = cacheObj.getJSONArray("stock")
                for (i in 0 until sArr.length()) {
                    val o = sArr.getJSONObject(i)
                    val s = StockItemData(
                        o.getString("code"), o.getString("name"), o.optString("pack"),
                        o.optString("batch"), o.optString("exp"), o.optDouble("mrp"),
                        o.optDouble("rate"), o.optInt("stock"), o.optString("company")
                    )
                    stockMap[s.itemCode] = s
                }
            }
            if (cacheObj.has("parties")) {
                val pArr = cacheObj.getJSONArray("parties")
                for (i in 0 until pArr.length()) {
                    val o = pArr.getJSONObject(i)
                    val p = PartyData(
                        o.getString("code"), o.getString("name"), o.optString("addr"),
                        o.optString("phone"), o.optDouble("total"), o.optDouble("overdue"),
                        o.optDouble("limit"), o.optInt("bills"), o.optString("date")
                    )
                    partyMap[p.partyCode] = p
                }
            }

            // Merge new
            for (s in newStock) stockMap[s.itemCode] = s
            for (p in newParties) partyMap[p.partyCode] = p

            // Serialize
            val outStock = JSONArray()
            stockMap.values.forEach { s ->
                outStock.put(JSONObject().apply {
                    put("code", s.itemCode); put("name", s.itemName); put("pack", s.packing)
                    put("batch", s.batch); put("exp", s.expiry); put("mrp", s.mrp)
                    put("rate", s.rate); put("stock", s.currentStock); put("company", s.company)
                })
            }
            val outParties = JSONArray()
            partyMap.values.forEach { p ->
                outParties.put(JSONObject().apply {
                    put("code", p.partyCode); put("name", p.partyName); put("addr", p.address)
                    put("phone", p.phone); put("total", p.totalOutstanding); put("overdue", p.overdueAmount)
                    put("limit", p.creditLimit); put("bills", p.billCount); put("date", p.lastBillDate)
                })
            }

            val finalObj = JSONObject().apply {
                put("stock", outStock)
                put("parties", outParties)
                put("lastUpdated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            }
            file.writeText(finalObj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getOfflineSummary(context: Context): JSONObject {
        val file = File(context.filesDir, OFFLINE_CACHE_FILE)
        if (!file.exists()) return JSONObject().apply {
            put("stockCount", 0)
            put("partyCount", 0)
            put("totalOutstanding", 0.0)
            put("lastUpdated", "None")
        }
        return try {
            val obj = JSONObject(file.readText())
            val stock = obj.optJSONArray("stock")
            val parties = obj.optJSONArray("parties")

            var totalOut = 0.0
            var totalOverdue = 0.0
            if (parties != null) {
                for (i in 0 until parties.length()) {
                    val p = parties.getJSONObject(i)
                    totalOut += p.optDouble("total")
                    totalOverdue += p.optDouble("overdue")
                }
            }

            JSONObject().apply {
                put("stockCount", stock?.length() ?: 0)
                put("partyCount", parties?.length() ?: 0)
                put("totalOutstanding", totalOut)
                put("totalOverdue", totalOverdue)
                put("lastUpdated", obj.optString("lastUpdated", "Unknown"))
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun queuePendingSync(context: Context, payloadString: String) {
        try {
            val file = File(context.filesDir, PENDING_SYNC_FILE)
            file.writeText(payloadString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasPendingSync(context: Context): Boolean {
        val file = File(context.filesDir, PENDING_SYNC_FILE)
        return file.exists() && file.length() > 0
    }

    fun flushPendingSync(context: Context, apiClient: PrintApiClient): Boolean {
        val file = File(context.filesDir, PENDING_SYNC_FILE)
        if (!file.exists() || file.length() == 0L) return false

        return try {
            val payload = file.readText()
            val success = apiClient.pushSyncData(payload)
            if (success) {
                file.delete()
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans Download/Marg Cloud for any un-synced CSV files and processes them.
     */
    fun scanAndSyncLocalFolder(context: Context, apiClient: PrintApiClient, isOnline: Boolean): String {
        val dir = getMargCloudDir()
        val files = dir.listFiles { _, name -> name.endsWith(".csv", ignoreCase = true) } ?: emptyArray()
        if (files.isEmpty()) return "No CSV files found in Download/Marg Cloud."

        var count = 0
        for (f in files) {
            processDownloadedCsv(context, f, apiClient, isOnline)
            count++
        }
        return "Processed and backed up $count CSV files."
    }
}
