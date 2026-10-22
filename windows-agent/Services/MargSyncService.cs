namespace SmartRemotePrint.Agent.Services;

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using SmartRemotePrint.Agent.Models;

public class MargSyncService : IMargSyncService, IDisposable
{
    private readonly ILogger<MargSyncService> _logger;
    private readonly string _watchFolder;
    private readonly string _backupFolder;
    private FileSystemWatcher? _watcher;
    private FileSystemWatcher? _downloadsWatcher;
    private readonly ConcurrentDictionary<string, StockItem> _stockMap = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, PartyOutstanding> _partyMap = new(StringComparer.OrdinalIgnoreCase);
    private readonly HttpClient _httpClient = new() { Timeout = TimeSpan.FromSeconds(5) };
    private DateTime? _lastSyncUtc;
    private string? _lastSyncError;
    private int _isSyncing = 0;

    public MargSyncService(ILogger<MargSyncService> logger)
    {
        _logger = logger;

        // Resolve watch folder: prefer C:\SmartRemotePrint\MargSync on Windows, or ./MargSync
        var customFolder = Environment.GetEnvironmentVariable("MARG_SYNC_FOLDER");
        if (!string.IsNullOrWhiteSpace(customFolder))
        {
            _watchFolder = customFolder;
        }
        else if (OperatingSystem.IsWindows())
        {
            _watchFolder = @"C:\SmartRemotePrint\MargSync";
        }
        else
        {
            _watchFolder = Path.Combine(AppContext.BaseDirectory, "MargSync");
        }

        _backupFolder = Path.Combine(_watchFolder, "Backup");

        try
        {
            if (!Directory.Exists(_watchFolder))
            {
                Directory.CreateDirectory(_watchFolder);
            }
            if (!Directory.Exists(_backupFolder))
            {
                Directory.CreateDirectory(_backupFolder);
            }
            EnsureSampleFiles();
            InitializeWatcher();
            InitializeDownloadsWatcher();
            // Initial scan in background
            Task.Run(TriggerSyncAsync);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to initialize MargSyncService on {Folder}", _watchFolder);
            _lastSyncError = ex.Message;
        }
    }

    private void EnsureSampleFiles()
    {
        try
        {
            var stockSample = Path.Combine(_watchFolder, "stock_sample.csv");
            if (!File.Exists(stockSample))
            {
                var content = "ItemCode,ItemName,Packing,Batch,Expiry,MRP,Rate,CurrentStock,Company\n" +
                              "PARA650,Paracetamol 650mg Tab,10x10,B892,12/2027,32.50,22.00,450,Cipla\n" +
                              "AMOX500,Amoxicillin 500mg Cap,10x10,A102,08/2026,85.00,62.00,120,Alkem\n" +
                              "AZI500,Azithromycin 500mg Tab,3s,Z551,04/2027,119.00,84.00,280,Mankind\n" +
                              "PAN40,Pantoprazole 40mg Tab,10x10,P331,10/2026,95.00,68.00,0,Sun Pharma\n";
                File.WriteAllText(stockSample, content);
            }

            var outstandingSample = Path.Combine(_watchFolder, "outstanding_sample.csv");
            if (!File.Exists(outstandingSample))
            {
                var content = "PartyCode,PartyName,Address,Phone,TotalOutstanding,OverdueAmount,CreditLimit,BillCount,LastBillDate\n" +
                              "SHR001,Shreeji Medical Store,Station Road Anand,9825012345,14500.00,4500.00,25000.00,3,15/10/2026\n" +
                              "KIR002,Kiran Healthcare & Chemist,Main Bazar Nadiad,9825098765,48200.00,18200.00,30000.00,5,10/10/2026\n" +
                              "PAT003,Patel Pharmacy Wholesale,GIDC V.U. Nagar,9898011223,0.00,0.00,50000.00,0,01/10/2026\n";
                File.WriteAllText(outstandingSample, content);
            }
        }
        catch { }
    }

    private void InitializeWatcher()
    {
        try
        {
            _watcher = new FileSystemWatcher(_watchFolder)
            {
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size,
                Filter = "*.*",
                EnableRaisingEvents = true
            };

            _watcher.Changed += OnFileChanged;
            _watcher.Created += OnFileChanged;
            _watcher.Renamed += OnFileChanged;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not start FileSystemWatcher on {Folder}", _watchFolder);
        }
    }

    private void OnFileChanged(object sender, FileSystemEventArgs e)
    {
        var ext = Path.GetExtension(e.FullPath).ToLowerInvariant();
        if (ext == ".csv" || ext == ".json")
        {
            // Debounce and trigger sync
            _ = Task.Run(async () =>
            {
                await Task.Delay(500);
                await TriggerSyncAsync();
            });
        }
    }

    private void InitializeDownloadsWatcher()
    {
        try
        {
            var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            var downloadsPath = Path.Combine(userProfile, "Downloads");
            if (Directory.Exists(downloadsPath))
            {
                _downloadsWatcher = new FileSystemWatcher(downloadsPath)
                {
                    NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size,
                    Filter = "*.csv",
                    EnableRaisingEvents = true
                };

                _downloadsWatcher.Created += OnDownloadFileDetected;
                _downloadsWatcher.Changed += OnDownloadFileDetected;
                _logger.LogInformation("Watching user Downloads folder for Marg CSVs: {Path}", downloadsPath);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not start FileSystemWatcher on user Downloads");
        }
    }

    private void OnDownloadFileDetected(object sender, FileSystemEventArgs e)
    {
        var name = Path.GetFileName(e.FullPath).ToLowerInvariant();
        if (name.Contains("marg") || name.Contains("stock") || name.Contains("outstanding") || name.Contains("ledger") || name.Contains("item") || name.Contains("party"))
        {
            _ = Task.Run(async () =>
            {
                await Task.Delay(1000); // Allow download completion
                await IngestExternalCsvFileAsync(e.FullPath);
            });
        }
    }

    public async Task IngestExternalCsvFileAsync(string filePath)
    {
        try
        {
            if (!File.Exists(filePath)) return;
            CreateTimestampedBackup(filePath);
            await ProcessFileAsync(filePath);
            _lastSyncUtc = DateTime.UtcNow;
            _lastSyncError = null;
            await NotifyWebhookAsync();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error processing external CSV from {Path}", filePath);
        }
    }

    public string CreateTimestampedBackup(string sourceFilePath)
    {
        try
        {
            if (!Directory.Exists(_backupFolder)) Directory.CreateDirectory(_backupFolder);
            var timestamp = DateTime.UtcNow.ToString("yyyy-MM-dd_HH-mm-ss");
            var originalName = Path.GetFileName(sourceFilePath);
            var backupName = $"{timestamp}_{originalName}";
            var targetPath = Path.Combine(_backupFolder, backupName);
            File.Copy(sourceFilePath, targetPath, overwrite: true);
            _logger.LogInformation("Saved non-destructive CSV backup: {Backup}", backupName);
            return targetPath;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to create timestamped backup of {File}", sourceFilePath);
            return sourceFilePath;
        }
    }

    public SyncStatus GetStatus()
    {
        decimal totalOutstanding = _partyMap.Values.Sum(p => p.TotalOutstanding);
        return new SyncStatus(
            Service: "MargSyncService",
            WatchFolder: _watchFolder,
            LastSyncUtc: _lastSyncUtc,
            TotalStockItems: _stockMap.Count,
            TotalParties: _partyMap.Count,
            TotalOutstandingAmount: totalOutstanding,
            IsAutoSyncActive: _watcher != null && _watcher.EnableRaisingEvents,
            LastSyncError: _lastSyncError
        );
    }

    public IReadOnlyList<StockItem> GetStock(string? search = null, int limit = 100, int minStock = -1)
    {
        IEnumerable<StockItem> query = _stockMap.Values;

        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            query = query.Where(s =>
                s.ItemName.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                s.ItemCode.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                s.Company.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                s.Batch.Contains(term, StringComparison.OrdinalIgnoreCase));
        }

        if (minStock >= 0)
        {
            query = query.Where(s => s.CurrentStock >= minStock);
        }

        return query.OrderBy(s => s.ItemName).Take(Math.Max(1, Math.Min(limit, 500))).ToList();
    }

    public IReadOnlyList<PartyOutstanding> GetOutstandings(string? search = null, bool overdueOnly = false, int limit = 100)
    {
        IEnumerable<PartyOutstanding> query = _partyMap.Values;

        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            query = query.Where(p =>
                p.PartyName.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                p.PartyCode.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                p.Phone.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                p.Address.Contains(term, StringComparison.OrdinalIgnoreCase));
        }

        if (overdueOnly)
        {
            query = query.Where(p => p.OverdueAmount > 0);
        }

        return query.OrderByDescending(p => p.TotalOutstanding).Take(Math.Max(1, Math.Min(limit, 500))).ToList();
    }

    public Task IngestPayloadAsync(SyncPushPayload payload)
    {
        var now = DateTime.UtcNow;
        if (payload.StockItems != null)
        {
            foreach (var item in payload.StockItems)
            {
                if (!string.IsNullOrWhiteSpace(item.ItemCode))
                {
                    _stockMap[item.ItemCode] = item with { UpdatedAtUtc = now };
                }
            }
        }

        if (payload.Parties != null)
        {
            foreach (var party in payload.Parties)
            {
                if (!string.IsNullOrWhiteSpace(party.PartyCode))
                {
                    _partyMap[party.PartyCode] = party with { UpdatedAtUtc = now };
                }
            }
        }

        _lastSyncUtc = now;
        _lastSyncError = null;

        try
        {
            if (!Directory.Exists(_backupFolder)) Directory.CreateDirectory(_backupFolder);
            var timestamp = DateTime.UtcNow.ToString("yyyy-MM-dd_HH-mm-ss");
            var backupPath = Path.Combine(_backupFolder, $"{timestamp}_tablet_push.json");
            File.WriteAllText(backupPath, JsonSerializer.Serialize(payload, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }

        _ = NotifyWebhookAsync();
        return Task.CompletedTask;
    }

    public async Task TriggerSyncAsync()
    {
        if (Interlocked.CompareExchange(ref _isSyncing, 1, 0) != 0)
        {
            return; // Already running
        }

        try
        {
            if (!Directory.Exists(_watchFolder)) return;

            var files = Directory.GetFiles(_watchFolder, "*.*")
                .Where(f => f.EndsWith(".csv", StringComparison.OrdinalIgnoreCase) ||
                            f.EndsWith(".json", StringComparison.OrdinalIgnoreCase));

            foreach (var filePath in files)
            {
                await ProcessFileAsync(filePath);
            }

            _lastSyncUtc = DateTime.UtcNow;
            _lastSyncError = null;

            await NotifyWebhookAsync();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error scanning MargSync folder");
            _lastSyncError = ex.Message;
        }
        finally
        {
            Interlocked.Exchange(ref _isSyncing, 0);
        }
    }

    private async Task ProcessFileAsync(string filePath)
    {
        CreateTimestampedBackup(filePath);
        string? text = null;
        for (int retry = 0; retry < 3; retry++)
        {
            try
            {
                using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                using var reader = new StreamReader(fs);
                text = await reader.ReadToEndAsync();
                break;
            }
            catch
            {
                await Task.Delay(150);
            }
        }

        if (string.IsNullOrWhiteSpace(text)) return;

        var fileName = Path.GetFileName(filePath).ToLowerInvariant();

        if (fileName.EndsWith(".json"))
        {
            ProcessJson(text);
        }
        else if (fileName.Contains("stock") || fileName.Contains("item"))
        {
            ProcessStockCsv(text);
        }
        else if (fileName.Contains("outstanding") || fileName.Contains("part") || fileName.Contains("ledger"))
        {
            ProcessOutstandingCsv(text);
        }
    }

    private void ProcessJson(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.ValueKind == JsonValueKind.Object)
            {
                var payload = JsonSerializer.Deserialize<SyncPushPayload>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                if (payload != null)
                {
                    IngestPayloadAsync(payload);
                }
            }
            else if (doc.RootElement.ValueKind == JsonValueKind.Array)
            {
                // Inspect first element to determine type
                var first = doc.RootElement.EnumerateArray().FirstOrDefault();
                if (first.ValueKind == JsonValueKind.Object)
                {
                    if (first.TryGetProperty("ItemCode", out _) || first.TryGetProperty("itemCode", out _) || first.TryGetProperty("CurrentStock", out _))
                    {
                        var items = JsonSerializer.Deserialize<List<StockItem>>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                        if (items != null) IngestPayloadAsync(new SyncPushPayload(items, null));
                    }
                    else if (first.TryGetProperty("PartyCode", out _) || first.TryGetProperty("partyCode", out _) || first.TryGetProperty("TotalOutstanding", out _))
                    {
                        var parties = JsonSerializer.Deserialize<List<PartyOutstanding>>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                        if (parties != null) IngestPayloadAsync(new SyncPushPayload(null, parties));
                    }
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to parse Marg JSON");
        }
    }

    private void ProcessStockCsv(string csv)
    {
        var lines = csv.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length < 2) return;

        var headers = lines[0].Split(',').Select(h => h.Trim().ToLowerInvariant()).ToArray();
        int idxCode = FindHeader(headers, "code", "itemcode", "item_code", "pcode");
        int idxName = FindHeader(headers, "name", "itemname", "item_name", "particular", "description");
        int idxPack = FindHeader(headers, "pack", "packing", "pkg");
        int idxBatch = FindHeader(headers, "batch", "batchno", "bno");
        int idxExp = FindHeader(headers, "exp", "expiry", "expdate");
        int idxMrp = FindHeader(headers, "mrp");
        int idxRate = FindHeader(headers, "rate", "salerate", "prate", "price");
        int idxStock = FindHeader(headers, "stock", "qty", "currentstock", "balance", "clqty");
        int idxCompany = FindHeader(headers, "company", "mfg", "manufacturer");

        var now = DateTime.UtcNow;
        for (int i = 1; i < lines.Length; i++)
        {
            var parts = ParseCsvLine(lines[i]);
            if (parts.Length <= Math.Max(idxCode, idxName)) continue;

            var code = GetVal(parts, idxCode);
            var name = GetVal(parts, idxName);
            if (string.IsNullOrWhiteSpace(code) && string.IsNullOrWhiteSpace(name)) continue;
            if (string.IsNullOrWhiteSpace(code)) code = name;

            var pack = GetVal(parts, idxPack);
            var batch = GetVal(parts, idxBatch);
            var exp = GetVal(parts, idxExp);
            var company = GetVal(parts, idxCompany);

            decimal.TryParse(GetVal(parts, idxMrp), NumberStyles.Any, CultureInfo.InvariantCulture, out var mrp);
            decimal.TryParse(GetVal(parts, idxRate), NumberStyles.Any, CultureInfo.InvariantCulture, out var rate);
            int.TryParse(GetVal(parts, idxStock), NumberStyles.Any, CultureInfo.InvariantCulture, out var stock);

            _stockMap[code] = new StockItem(code, name, pack, batch, exp, mrp, rate, stock, company, now);
        }
    }

    private void ProcessOutstandingCsv(string csv)
    {
        var lines = csv.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length < 2) return;

        var headers = lines[0].Split(',').Select(h => h.Trim().ToLowerInvariant()).ToArray();
        int idxCode = FindHeader(headers, "code", "partycode", "pcode", "acc_code");
        int idxName = FindHeader(headers, "name", "partyname", "party", "ledger", "account", "customer");
        int idxAddr = FindHeader(headers, "address", "addr", "city");
        int idxPhone = FindHeader(headers, "phone", "mobile", "tel");
        int idxTotal = FindHeader(headers, "totaloutstanding", "outstanding", "balance", "bal", "net_balance");
        int idxOverdue = FindHeader(headers, "overdue", "due", "overdueamount");
        int idxLimit = FindHeader(headers, "limit", "creditlimit", "cr_limit");
        int idxBills = FindHeader(headers, "bills", "billcount", "nobills");
        int idxDate = FindHeader(headers, "lastbilldate", "lastdate", "billdate");

        var now = DateTime.UtcNow;
        for (int i = 1; i < lines.Length; i++)
        {
            var parts = ParseCsvLine(lines[i]);
            if (parts.Length <= Math.Max(idxCode, idxName)) continue;

            var code = GetVal(parts, idxCode);
            var name = GetVal(parts, idxName);
            if (string.IsNullOrWhiteSpace(code) && string.IsNullOrWhiteSpace(name)) continue;
            if (string.IsNullOrWhiteSpace(code)) code = name;

            var addr = GetVal(parts, idxAddr);
            var phone = GetVal(parts, idxPhone);
            var date = GetVal(parts, idxDate);

            decimal.TryParse(GetVal(parts, idxTotal), NumberStyles.Any, CultureInfo.InvariantCulture, out var total);
            decimal.TryParse(GetVal(parts, idxOverdue), NumberStyles.Any, CultureInfo.InvariantCulture, out var overdue);
            decimal.TryParse(GetVal(parts, idxLimit), NumberStyles.Any, CultureInfo.InvariantCulture, out var limit);
            int.TryParse(GetVal(parts, idxBills), NumberStyles.Any, CultureInfo.InvariantCulture, out var bills);

            _partyMap[code] = new PartyOutstanding(code, name, addr, phone, total, overdue, limit, bills, date, now);
        }
    }

    private static int FindHeader(string[] headers, params string[] targets)
    {
        for (int i = 0; i < headers.Length; i++)
        {
            foreach (var t in targets)
            {
                if (headers[i].Contains(t, StringComparison.OrdinalIgnoreCase))
                {
                    return i;
                }
            }
        }
        return -1;
    }

    private static string GetVal(string[] parts, int index)
    {
        if (index >= 0 && index < parts.Length)
        {
            return parts[index].Trim().Trim('"');
        }
        return string.Empty;
    }

    private static string[] ParseCsvLine(string line)
    {
        var result = new List<string>();
        bool inQuotes = false;
        var cur = new System.Text.StringBuilder();

        for (int i = 0; i < line.Length; i++)
        {
            char c = line[i];
            if (c == '"')
            {
                inQuotes = !inQuotes;
            }
            else if (c == ',' && !inQuotes)
            {
                result.Add(cur.ToString());
                cur.Clear();
            }
            else
            {
                cur.Append(c);
            }
        }
        result.Add(cur.ToString());
        return result.ToArray();
    }

    private async Task NotifyWebhookAsync()
    {
        var webhookUrl = Environment.GetEnvironmentVariable("MARG_SYNC_WEBHOOK_URL") ?? "http://127.0.0.1:8080/api/sync";
        try
        {
            var summary = new
            {
                event_type = "marg_sync_update",
                timestamp_utc = DateTime.UtcNow,
                total_stock_items = _stockMap.Count,
                total_parties = _partyMap.Count,
                total_outstanding = _partyMap.Values.Sum(p => p.TotalOutstanding)
            };
            var content = new StringContent(JsonSerializer.Serialize(summary), System.Text.Encoding.UTF8, "application/json");
            await _httpClient.PostAsync(webhookUrl, content);
        }
        catch
        {
            // Webhook destination might be offline or optional; fail silently
        }
    }

    public CsvAnalyticsSummary GetAnalyticsSummary()
    {
        int totalUnits = _stockMap.Values.Sum(s => s.CurrentStock);
        decimal valuation = _stockMap.Values.Sum(s => s.CurrentStock * (s.Rate > 0 ? s.Rate : s.Mrp));
        decimal totalOutstanding = _partyMap.Values.Sum(p => p.TotalOutstanding);
        decimal totalOverdue = _partyMap.Values.Sum(p => p.OverdueAmount);

        var backups = GetBackupFiles();
        var latestBackup = backups.FirstOrDefault()?.FileName;

        return new CsvAnalyticsSummary(
            TotalStockItems: _stockMap.Count,
            TotalStockUnits: totalUnits,
            TotalInventoryValuation: valuation,
            TotalParties: _partyMap.Count,
            TotalOutstandingAmount: totalOutstanding,
            TotalOverdueAmount: totalOverdue,
            TotalBackupFiles: backups.Count,
            LatestBackupFile: latestBackup,
            LastSyncUtc: _lastSyncUtc,
            BackupFolder: _backupFolder
        );
    }

    public IReadOnlyList<BackupFileInfo> GetBackupFiles()
    {
        try
        {
            if (!Directory.Exists(_backupFolder)) return Array.Empty<BackupFileInfo>();

            var dir = new DirectoryInfo(_backupFolder);
            return dir.GetFiles("*.*")
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .Select(f => new BackupFileInfo(
                    FileName: f.Name,
                    SizeBytes: f.Length,
                    CreatedUtc: f.CreationTimeUtc,
                    RelativePath: Path.Combine("MargSync", "Backup", f.Name)
                ))
                .ToList();
        }
        catch
        {
            return Array.Empty<BackupFileInfo>();
        }
    }

    public void Dispose()
    {
        _watcher?.Dispose();
        _downloadsWatcher?.Dispose();
        _httpClient.Dispose();
    }
}
