using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using SmartRemotePrint.Agent.Models;

namespace SmartRemotePrint.Agent.Services;

public interface ISpoolerPrintService
{
    List<PrinterQueueInfo> GetPrinters();
    Task<PrintJobStatus> EnqueueJobAsync(PrintJobSubmission metadata, Stream pdfStream);
    PrintJobStatus? GetJobStatus(string jobId);
    bool CancelJob(string jobId);
    string ResolvePrinterName(string? requestedName);
    ActivePrinterConfig GetPrinterConfig();
    bool SetActivePrinter(string printerName, bool setAsWindowsDefault = false);
    Task<PrintJobStatus> PrintLocalFileAsync(string filePath, string? printerName = null, int copies = 1, string? documentName = null);
    PrinterTelemetry GetPrinterTelemetry(string? printerName = null);
}

public class SpoolerPrintService : ISpoolerPrintService
{
    private readonly ILogger<SpoolerPrintService> _logger;
    private readonly ConcurrentDictionary<string, PrintJobStatus> _jobs = new();
    private readonly string _spoolDir;

    // WinSpool P/Invoke signatures for RAW fallback
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private class DOCINFOA
    {
        [MarshalAs(UnmanagedType.LPStr)] public string? pDocName;
        [MarshalAs(UnmanagedType.LPStr)] public string? pOutputFile;
        [MarshalAs(UnmanagedType.LPStr)] public string? pDataType;
    }

    [DllImport("winspool.Drv", EntryPoint = "OpenPrinterA", SetLastError = true, CharSet = CharSet.Ansi, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool OpenPrinter([MarshalAs(UnmanagedType.LPStr)] string szPrinter, out IntPtr hPrinter, IntPtr pd);

    [DllImport("winspool.Drv", EntryPoint = "ClosePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool ClosePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "StartDocPrinterA", SetLastError = true, CharSet = CharSet.Ansi, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern int StartDocPrinter(IntPtr hPrinter, int level, [In] DOCINFOA di);

    [DllImport("winspool.Drv", EntryPoint = "EndDocPrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool EndDocPrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "StartPagePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool StartPagePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "EndPagePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool EndPagePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "WritePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool WritePrinter(IntPtr hPrinter, IntPtr pBytes, int dwCount, out int dwWritten);

    [DllImport("winspool.Drv", EntryPoint = "GetPrinterA", SetLastError = true, CharSet = CharSet.Ansi, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
    private static extern bool GetPrinter(IntPtr hPrinter, int dwLevel, IntPtr pPrinter, int cbBuf, out int pcbNeeded);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    private struct PRINTER_INFO_2A
    {
        public string? pServerName;
        public string? pPrinterName;
        public string? pShareName;
        public string? pPortName;
        public string? pDriverName;
        public string? pComment;
        public string? pLocation;
        public IntPtr pDevMode;
        public string? pSepFile;
        public string? pPrintProcessor;
        public string? pDatatype;
        public string? pParameters;
        public IntPtr pSecurityDescriptor;
        public uint Attributes;
        public uint Priority;
        public uint DefaultPriority;
        public uint StartTime;
        public uint UntilTime;
        public uint Status;
        public uint cJobs;
        public uint AveragePPM;
    }

    private const uint PRINTER_STATUS_PAUSED            = 0x00000001;
    private const uint PRINTER_STATUS_ERROR             = 0x00000002;
    private const uint PRINTER_STATUS_PENDING_DELETION  = 0x00000004;
    private const uint PRINTER_STATUS_PAPER_JAM         = 0x00000008;
    private const uint PRINTER_STATUS_PAPER_OUT         = 0x00000010;
    private const uint PRINTER_STATUS_MANUAL_FEED       = 0x00000020;
    private const uint PRINTER_STATUS_PAPER_PROBLEM     = 0x00000040;
    private const uint PRINTER_STATUS_OFFLINE           = 0x00000080;
    private const uint PRINTER_STATUS_IO_ACTIVE         = 0x00000100;
    private const uint PRINTER_STATUS_BUSY              = 0x00000200;
    private const uint PRINTER_STATUS_PRINTING          = 0x00000400;
    private const uint PRINTER_STATUS_OUTPUT_BIN_FULL   = 0x00000800;
    private const uint PRINTER_STATUS_NOT_AVAILABLE     = 0x00001000;
    private const uint PRINTER_STATUS_WAITING           = 0x00002000;
    private const uint PRINTER_STATUS_PROCESSING        = 0x00004000;
    private const uint PRINTER_STATUS_INITIALIZING      = 0x00008000;
    private const uint PRINTER_STATUS_WARMING_UP        = 0x00010000;
    private const uint PRINTER_STATUS_TONER_LOW         = 0x00020000;
    private const uint PRINTER_STATUS_NO_TONER          = 0x00040000;
    private const uint PRINTER_STATUS_PAGE_PUNT         = 0x00080000;
    private const uint PRINTER_STATUS_USER_INTERVENTION = 0x00100000;
    private const uint PRINTER_STATUS_OUT_OF_MEMORY     = 0x00200000;
    private const uint PRINTER_STATUS_DOOR_OPEN         = 0x00400000;
    private const uint PRINTER_STATUS_SERVER_UNKNOWN    = 0x00800000;
    private const uint PRINTER_STATUS_POWER_SAVE        = 0x01000000;

    public SpoolerPrintService(ILogger<SpoolerPrintService> logger)
    {
        _logger = logger;
        _spoolDir = Path.Combine(Path.GetTempPath(), "SmartRemotePrint_Spool");
        if (!Directory.Exists(_spoolDir))
        {
            Directory.CreateDirectory(_spoolDir);
        }
    }

    public List<PrinterQueueInfo> GetPrinters()
    {
        var list = new List<PrinterQueueInfo>();

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = "-NoProfile -Command \"Get-Printer | Select-Object Name,DriverName,PortName,PrinterStatus,Default | ConvertTo-Json -Compress\"",
                    RedirectStandardOutput = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                using var proc = Process.Start(psi);
                if (proc != null)
                {
                    var output = proc.StandardOutput.ReadToEnd();
                    proc.WaitForExit(3000);

                    if (!string.IsNullOrWhiteSpace(output))
                    {
                        using var doc = System.Text.Json.JsonDocument.Parse(output);
                        var elements = doc.RootElement.ValueKind == System.Text.Json.JsonValueKind.Array
                            ? doc.RootElement.EnumerateArray().ToList()
                            : new List<System.Text.Json.JsonElement> { doc.RootElement };

                        foreach (var elem in elements)
                        {
                            var name = elem.GetProperty("Name").GetString() ?? "Unknown";
                            var driver = elem.GetProperty("DriverName").GetString() ?? "";
                            var port = elem.GetProperty("PortName").GetString() ?? "";
                            var isDef = elem.GetProperty("Default").GetBoolean();
                            var status = elem.GetProperty("PrinterStatus").GetInt32();

                            var connType = port.StartsWith("USB", StringComparison.OrdinalIgnoreCase) ? "USB" :
                                           (port.Contains(".") || port.StartsWith("WSD") || port.StartsWith("TCP")) ? "Network_WiFi" : "Other";

                            list.Add(new PrinterQueueInfo(
                                Name: name,
                                DriverName: driver,
                                PortName: port,
                                IsDefault: isDef,
                                IsOnline: status == 3 || status == 0,
                                Status: status == 3 ? "Ready" : "Normal",
                                ConnectionType: connType,
                                SupportedPaperSizes: new List<string> { "A4", "Letter", "Legal" },
                                SupportsColor: true,
                                SupportsDuplex: true
                            ));
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning("Windows printer query warning: {Msg}. Falling back to default list.", ex.Message);
            }
        }

        // Ensure Brother DCP-T530DW Printer entries are present if not already added
        if (!list.Any(p => p.Name.Contains("Brother", StringComparison.OrdinalIgnoreCase) && p.Name.Contains("T530", StringComparison.OrdinalIgnoreCase)))
        {
            list.Add(new PrinterQueueInfo(
                Name: "Brother DCP-T530DW Printer",
                DriverName: "Brother DCP-T530DW Printer",
                PortName: "USB001",
                IsDefault: true,
                IsOnline: true,
                Status: "Ready",
                ConnectionType: "USB",
                SupportedPaperSizes: new List<string> { "A4", "Letter", "Legal" },
                SupportsColor: true,
                SupportsDuplex: true
            ));

            list.Add(new PrinterQueueInfo(
                Name: "Brother DCP-T530DW Printer (Wi-Fi)",
                DriverName: "Brother DCP-T530DW Printer",
                PortName: "WSD-Brother",
                IsDefault: false,
                IsOnline: true,
                Status: "Ready",
                ConnectionType: "Network_WiFi",
                SupportedPaperSizes: new List<string> { "A4", "Letter", "Legal" },
                SupportsColor: true,
                SupportsDuplex: true
            ));
        }

        return list;
    }

    private static string GetConfigFilePath()
    {
        string[] candidates = {
            @"C:\Program Files\SmartRemotePrint\printer_config.json",
            @"C:\SmartRemotePrint\printer_config.json",
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "printer_config.json")
        };

        foreach (var c in candidates)
        {
            if (File.Exists(c)) return c;
            var dir = Path.GetDirectoryName(c);
            if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir)) return c;
        }

        return Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "printer_config.json");
    }

    public ActivePrinterConfig GetPrinterConfig()
    {
        try
        {
            var path = GetConfigFilePath();
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var cfg = System.Text.Json.JsonSerializer.Deserialize<ActivePrinterConfig>(json);
                if (cfg != null) return cfg;
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Failed reading printer_config.json: {Msg}", ex.Message);
        }

        return new ActivePrinterConfig("SYSTEM_DEFAULT");
    }

    public bool SetActivePrinter(string printerName, bool setAsWindowsDefault = false)
    {
        try
        {
            var config = new ActivePrinterConfig(
                ActivePrinter: string.IsNullOrWhiteSpace(printerName) ? "SYSTEM_DEFAULT" : printerName.Trim(),
                FallbackPrinter: "Brother DCP-T530DW Printer",
                FitToPage: true,
                Copies: 1,
                LastChangedUtc: DateTime.UtcNow
            );

            var json = System.Text.Json.JsonSerializer.Serialize(config, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });

            var primary = GetConfigFilePath();
            var dir = Path.GetDirectoryName(primary);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
            {
                try { Directory.CreateDirectory(dir); } catch { }
            }
            File.WriteAllText(primary, json);

            var secondary = @"C:\SmartRemotePrint\printer_config.json";
            if (Directory.Exists(@"C:\SmartRemotePrint") && !string.Equals(primary, secondary, StringComparison.OrdinalIgnoreCase))
            {
                try { File.WriteAllText(secondary, json); } catch { }
            }

            _logger.LogInformation("Active printer set to: {Printer} (Config path: {Path})", config.ActivePrinter, primary);

            if (setAsWindowsDefault && !string.Equals(config.ActivePrinter, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase) && RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                try
                {
                    var clean = config.ActivePrinter.Replace("'", "''");
                    var psScript = $"(New-Object -ComObject WScript.Network).SetDefaultPrinter('{clean}')";
                    var psi = new ProcessStartInfo
                    {
                        FileName = "powershell.exe",
                        Arguments = $"-NoProfile -Command \"{psScript}\"",
                        CreateNoWindow = true,
                        UseShellExecute = false
                    };
                    using var proc = Process.Start(psi);
                    proc?.WaitForExit(3000);
                    _logger.LogInformation("Windows default printer updated to: {Printer}", config.ActivePrinter);
                }
                catch (Exception ex)
                {
                    _logger.LogWarning("Could not set Windows default printer: {Msg}", ex.Message);
                }
            }

            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to set active printer");
            return false;
        }
    }

    public string ResolvePrinterName(string? requestedName)
    {
        var available = GetPrinters();
        if (available == null || available.Count == 0)
        {
            return requestedName ?? "Brother DCP-T530DW Printer";
        }

        // 1. Explicit request from caller (if not generic "default" or "SYSTEM_DEFAULT")
        if (!string.IsNullOrWhiteSpace(requestedName) &&
            !string.Equals(requestedName, "default", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(requestedName, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase))
        {
            var exact = available.FirstOrDefault(p => string.Equals(p.Name, requestedName, StringComparison.OrdinalIgnoreCase));
            if (exact != null) return exact.Name;

            var partial = available.FirstOrDefault(p => p.Name.Contains(requestedName, StringComparison.OrdinalIgnoreCase));
            if (partial != null) return partial.Name;
        }

        // 2. Check Active Printer Config (Allows user to select any printer via Desktop .exe)
        var config = GetPrinterConfig();
        if (!string.IsNullOrWhiteSpace(config.ActivePrinter) &&
            !string.Equals(config.ActivePrinter, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase))
        {
            var configured = available.FirstOrDefault(p => string.Equals(p.Name, config.ActivePrinter, StringComparison.OrdinalIgnoreCase))
                ?? available.FirstOrDefault(p => p.Name.Contains(config.ActivePrinter, StringComparison.OrdinalIgnoreCase));
            if (configured != null) return configured.Name;
        }

        // 3. Dynamic Windows Default Printer (Whenever user changes default printer in Windows)
        var winDefault = available.FirstOrDefault(p => p.IsDefault);
        if (winDefault != null) return winDefault.Name;

        // 4. Configured Fallback printer (e.g. Brother DCP-T530DW)
        if (!string.IsNullOrWhiteSpace(config.FallbackPrinter))
        {
            var fallback = available.FirstOrDefault(p => p.Name.Contains(config.FallbackPrinter, StringComparison.OrdinalIgnoreCase));
            if (fallback != null) return fallback.Name;
        }

        // 5. Any Brother printer
        var anyBrother = available.FirstOrDefault(p => p.Name.Contains("Brother", StringComparison.OrdinalIgnoreCase));
        if (anyBrother != null) return anyBrother.Name;

        // 6. First available printer
        return available[0].Name;
    }

    public async Task<PrintJobStatus> PrintLocalFileAsync(string filePath, string? printerName = null, int copies = 1, string? documentName = null)
    {
        if (!File.Exists(filePath))
        {
            throw new FileNotFoundException($"File not found: {filePath}");
        }

        var resolvedPrinter = ResolvePrinterName(printerName);
        var docName = documentName ?? Path.GetFileName(filePath);
        var jobId = Guid.NewGuid().ToString("D");

        var ext = Path.GetExtension(filePath).ToLowerInvariant();
        if (ext == ".pdf")
        {
            await using var stream = File.OpenRead(filePath);
            var metadata = new PrintJobSubmission(
                JobId: jobId,
                ClientTimestamp: DateTime.UtcNow,
                PrinterName: resolvedPrinter,
                DocumentName: docName,
                Copies: Math.Max(1, copies)
            );
            return await EnqueueJobAsync(metadata, stream);
        }

        // For non-PDF files (.txt, .csv, images), spool using Windows native print mechanisms
        var status = new PrintJobStatus(
            JobId: jobId,
            WindowsSpoolerJobId: Random.Shared.Next(1000, 9999),
            State: "Queued",
            ErrorMessage: null,
            PagesSubmitted: 1,
            PagesPrinted: 0,
            SubmittedAtUtc: DateTime.UtcNow,
            CompletedAtUtc: null
        );
        _jobs[jobId] = status;

        _ = Task.Run(async () =>
        {
            try
            {
                EnsurePrinterReady(resolvedPrinter);
                _jobs[jobId] = status with { State = "Spooling" };

                bool success = false;
                if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                {
                    var cleanFile = filePath.Replace("'", "''");
                    var cleanPrinter = resolvedPrinter.Replace("'", "''");

                    if (ext == ".txt" || ext == ".csv" || ext == ".log")
                    {
                        var ps = $"Get-Content -Path '{cleanFile}' | Out-Printer -Name '{cleanPrinter}'";
                        var psi = new ProcessStartInfo
                        {
                            FileName = "powershell.exe",
                            Arguments = $"-NoProfile -Command \"{ps}\"",
                            CreateNoWindow = true,
                            UseShellExecute = false
                        };
                        using var proc = Process.Start(psi);
                        if (proc != null)
                        {
                            await proc.WaitForExitAsync();
                            success = proc.ExitCode == 0;
                        }
                    }
                    else
                    {
                        var ps = $"Start-Process -FilePath '{cleanFile}' -Verb PrintTo -ArgumentList '\"{cleanPrinter}\"' -PassThru | ForEach-Object {{ Start-Sleep -Seconds 5; if (!$_.HasExited) {{ Stop-Process -Id $_.Id -Force }} }}";
                        var psi = new ProcessStartInfo
                        {
                            FileName = "powershell.exe",
                            Arguments = $"-NoProfile -Command \"{ps}\"",
                            CreateNoWindow = true,
                            UseShellExecute = false
                        };
                        using var proc = Process.Start(psi);
                        if (proc != null)
                        {
                            await proc.WaitForExitAsync();
                            success = true;
                        }
                    }
                }

                if (success)
                {
                    _jobs[jobId] = status with { State = "Completed", PagesPrinted = 1, CompletedAtUtc = DateTime.UtcNow };
                    _logger.LogInformation("Local file {File} printed successfully to {Printer}", filePath, resolvedPrinter);
                }
                else
                {
                    _jobs[jobId] = status with { State = "Failed", ErrorMessage = "Print command failed or timed out." };
                }
            }
            catch (Exception ex)
            {
                _jobs[jobId] = status with { State = "Failed", ErrorMessage = ex.Message };
            }
        });

        return status;
    }

    private string? FindSumatraPdf()
    {
        var appDir = AppDomain.CurrentDomain.BaseDirectory;
        var searchPaths = new List<string>
        {
            Path.Combine(appDir, "SumatraPDF.exe"),
            Path.Combine(appDir, "SumatraPDF-3.6.1-64.exe"),
            @"C:\Program Files\SmartRemotePrint\SumatraPDF.exe",
            @"C:\Program Files\SumatraPDF\SumatraPDF.exe",
            @"C:\Program Files (x86)\SumatraPDF\SumatraPDF.exe",
            @"C:\OfficePrint\SumatraPDF.exe",
            @"C:\MargPrint\SumatraPDF.exe",
            Path.Combine(Directory.GetCurrentDirectory(), "SumatraPDF.exe")
        };

        return searchPaths.FirstOrDefault(File.Exists);
    }

    private void EnsurePrinterReady(string printerName)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;

        try
        {
            var cleanName = printerName.Replace("'", "''");
            var cleanupScript = $"Get-PrintJob -PrinterName '{cleanName}' -ErrorAction SilentlyContinue | Where-Object {{ $_.JobStatus -match 'Error|Paused|UserIntervention' }} | Remove-PrintJob -ErrorAction SilentlyContinue; Resume-PrintJob -PrinterName '{cleanName}' -ErrorAction SilentlyContinue";
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -Command \"{cleanupScript}\"",
                CreateNoWindow = true,
                UseShellExecute = false
            };
            using var proc = Process.Start(psi);
            proc?.WaitForExit(3000);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Printer readiness check warning: {Msg}", ex.Message);
        }
    }

    public async Task<PrintJobStatus> EnqueueJobAsync(PrintJobSubmission metadata, Stream pdfStream)
    {
        var tempFile = Path.Combine(_spoolDir, $"{metadata.JobId}.pdf");
        await using (var fs = File.Create(tempFile))
        {
            await pdfStream.CopyToAsync(fs);
        }

        // Validate PDF signature
        var buffer = new byte[8];
        await using (var fs = File.OpenRead(tempFile))
        {
            _ = await fs.ReadAsync(buffer);
        }
        var header = System.Text.Encoding.ASCII.GetString(buffer);
        if (!header.StartsWith("%PDF-"))
        {
            File.Delete(tempFile);
            throw new InvalidOperationException("Uploaded file is not a valid PDF document (missing %PDF- header).");
        }

        var status = new PrintJobStatus(
            JobId: metadata.JobId,
            WindowsSpoolerJobId: Random.Shared.Next(1000, 9999),
            State: "Queued",
            ErrorMessage: null,
            PagesSubmitted: 1,
            PagesPrinted: 0,
            SubmittedAtUtc: DateTime.UtcNow,
            CompletedAtUtc: null
        );

        _jobs[metadata.JobId] = status;

        // Process in background
        _ = Task.Run(async () =>
        {
            try
            {
                var resolvedPrinter = ResolvePrinterName(metadata.PrinterName);
                _jobs[metadata.JobId] = status with { State = "Spooling" };
                _logger.LogInformation("Spooling job {JobId} ({DocName}) to target queue: {Printer} (Requested: {Req})...",
                    metadata.JobId, metadata.DocumentName, resolvedPrinter, metadata.PrinterName);

                // Proactively unpause queue & clear dead error jobs
                EnsurePrinterReady(resolvedPrinter);

                await Task.Delay(300); // Small spool buffer delay

                bool spooledSuccess = false;

                if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                {
                    var sumatraExe = FindSumatraPdf();
                    if (!string.IsNullOrEmpty(sumatraExe))
                    {
                        _logger.LogInformation("Using SumatraPDF raster engine: {Path}", sumatraExe);
                        // Fit to printable page margins, silent background execution
                        var psi = new ProcessStartInfo
                        {
                            FileName = sumatraExe,
                            Arguments = $"-print-to \"{resolvedPrinter}\" -print-settings \"fit\" -silent \"{tempFile}\"",
                            CreateNoWindow = true,
                            UseShellExecute = false
                        };
                        using var proc = Process.Start(psi);
                        if (proc != null)
                        {
                            await proc.WaitForExitAsync();
                            spooledSuccess = proc.ExitCode == 0;
                            if (!spooledSuccess)
                            {
                                _logger.LogWarning("SumatraPDF returned exit code: {Code}. Falling back to secondary methods...", proc.ExitCode);
                            }
                        }
                    }

                    // Secondary fallback: PowerShell PrintTo verb
                    if (!spooledSuccess)
                    {
                        _logger.LogInformation("Attempting PowerShell PrintTo verb for {Printer}...", resolvedPrinter);
                        try
                        {
                            var cleanFile = tempFile.Replace("'", "''");
                            var cleanPrinter = resolvedPrinter.Replace("'", "''");
                            var psScript = $"Start-Process -FilePath '{cleanFile}' -Verb PrintTo -ArgumentList '\"{cleanPrinter}\"' -PassThru | ForEach-Object {{ Start-Sleep -Seconds 5; if (!$_.HasExited) {{ Stop-Process -Id $_.Id -Force }} }}";
                            var psi = new ProcessStartInfo
                            {
                                FileName = "powershell.exe",
                                Arguments = $"-NoProfile -Command \"{psScript}\"",
                                CreateNoWindow = true,
                                UseShellExecute = false
                            };
                            using var proc = Process.Start(psi);
                            if (proc != null)
                            {
                                await proc.WaitForExitAsync();
                                spooledSuccess = proc.ExitCode == 0;
                            }
                        }
                        catch (Exception ex)
                        {
                            _logger.LogWarning(ex, "PowerShell PrintTo attempt failed.");
                        }
                    }

                    // Last resort fallback: WinSpool Direct RAW
                    if (!spooledSuccess)
                    {
                        _logger.LogWarning("Attempting direct WinSpool RAW API for {Printer}...", resolvedPrinter);
                        spooledSuccess = RawSpoolPdfToPrinter(resolvedPrinter, metadata.DocumentName, tempFile);
                    }
                }
                else
                {
                    // Simulated / Linux test mode
                    spooledSuccess = true;
                }

                if (spooledSuccess)
                {
                    _jobs[metadata.JobId] = status with
                    {
                        State = "Completed",
                        PagesPrinted = 1,
                        CompletedAtUtc = DateTime.UtcNow
                    };
                    _logger.LogInformation("Job {JobId} successfully printed to {Printer}.", metadata.JobId, resolvedPrinter);
                }
                else
                {
                    _jobs[metadata.JobId] = status with
                    {
                        State = "Failed",
                        ErrorMessage = $"Windows Print Spooler rejected document or printer '{resolvedPrinter}' is offline/paused."
                    };
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Failed to print job {JobId}", metadata.JobId);
                _jobs[metadata.JobId] = status with
                {
                    State = "Failed",
                    ErrorMessage = ex.Message
                };
            }
            finally
            {
                // Give Windows spooler 5 seconds before cleaning up temp PDF
                _ = Task.Run(async () =>
                {
                    await Task.Delay(5000);
                    try { if (File.Exists(tempFile)) File.Delete(tempFile); } catch { }
                });
            }
        });

        return status;
    }

    public PrintJobStatus? GetJobStatus(string jobId)
    {
        _jobs.TryGetValue(jobId, out var status);
        return status;
    }

    public bool CancelJob(string jobId)
    {
        if (_jobs.TryGetValue(jobId, out var status))
        {
            if (status.State is "Queued" or "Spooling")
            {
                _jobs[jobId] = status with { State = "Cancelled", CompletedAtUtc = DateTime.UtcNow };
                return true;
            }
        }
        return false;
    }

    private bool RawSpoolPdfToPrinter(string printerName, string docName, string filePath)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return true;

        var fileBytes = File.ReadAllBytes(filePath);
        if (!OpenPrinter(printerName, out var hPrinter, IntPtr.Zero))
        {
            _logger.LogWarning("OpenPrinter failed for {Printer}. Error: {Error}", printerName, Marshal.GetLastWin32Error());
            return false;
        }

        try
        {
            var di = new DOCINFOA
            {
                pDocName = docName,
                pDataType = "RAW"
            };

            if (StartDocPrinter(hPrinter, 1, di) == 0)
            {
                return false;
            }

            if (!StartPagePrinter(hPrinter))
            {
                EndDocPrinter(hPrinter);
                return false;
            }

            var pUnmanagedBytes = Marshal.AllocCoTaskMem(fileBytes.Length);
            Marshal.Copy(fileBytes, 0, pUnmanagedBytes, fileBytes.Length);

            var success = WritePrinter(hPrinter, pUnmanagedBytes, fileBytes.Length, out var written);
            Marshal.FreeCoTaskMem(pUnmanagedBytes);

            EndPagePrinter(hPrinter);
            EndDocPrinter(hPrinter);

            return success && written == fileBytes.Length;
        }
        finally
        {
            ClosePrinter(hPrinter);
        }
    }

    public PrinterTelemetry GetPrinterTelemetry(string? printerName = null)
    {
        var resolvedName = ResolvePrinterName(printerName);
        var now = DateTime.UtcNow;

        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            return new PrinterTelemetry(
                PrinterName: resolvedName,
                IsOnline: true,
                State: "Ready",
                StateDetails: "Simulated Ready on non-Windows environment",
                PaperStatus: "OK",
                InkStatus: "OK",
                CoverStatus: "Closed",
                QueuedJobsCount: 0,
                LastCheckedUtc: now
            );
        }

        try
        {
            if (OpenPrinter(resolvedName, out IntPtr hPrinter, IntPtr.Zero))
            {
                try
                {
                    GetPrinter(hPrinter, 2, IntPtr.Zero, 0, out int bytesNeeded);
                    if (bytesNeeded > 0)
                    {
                        IntPtr pBuf = Marshal.AllocHGlobal(bytesNeeded);
                        try
                        {
                            if (GetPrinter(hPrinter, 2, pBuf, bytesNeeded, out _))
                            {
                                var info = Marshal.PtrToStructure<PRINTER_INFO_2A>(pBuf);
                                uint s = info.Status;
                                int jobCount = (int)info.cJobs;

                                string paper = "OK";
                                if ((s & PRINTER_STATUS_PAPER_OUT) != 0) paper = "OutOfPaper";
                                else if ((s & PRINTER_STATUS_PAPER_JAM) != 0) paper = "PaperJam";

                                string ink = "OK";
                                if ((s & PRINTER_STATUS_NO_TONER) != 0) ink = "NoInk";
                                else if ((s & PRINTER_STATUS_TONER_LOW) != 0) ink = "LowInk";

                                string cover = (s & PRINTER_STATUS_DOOR_OPEN) != 0 ? "DoorOpen" : "Closed";

                                bool isOnline = (s & PRINTER_STATUS_OFFLINE) == 0;
                                string state = "Ready";
                                string details = "Printer is idle and ready to print.";

                                if (!isOnline)
                                {
                                    state = "Offline";
                                    details = "Printer is offline or disconnected.";
                                }
                                else if (paper == "OutOfPaper")
                                {
                                    state = "PaperOut";
                                    details = "Printer is OUT OF PAPER. Please reload paper tray.";
                                }
                                else if (paper == "PaperJam")
                                {
                                    state = "PaperJam";
                                    details = "PAPER JAM detected in printer mechanism. Please clear paper.";
                                }
                                else if (cover == "DoorOpen")
                                {
                                    state = "DoorOpen";
                                    details = "Printer door / cover is open. Please close cover.";
                                }
                                else if (ink == "NoInk")
                                {
                                    state = "NoInk";
                                    details = "Ink cartridge / toner is empty.";
                                }
                                else if (ink == "LowInk")
                                {
                                    state = "LowInk";
                                    details = "Ink or toner level is low.";
                                }
                                else if ((s & PRINTER_STATUS_PAUSED) != 0)
                                {
                                    state = "Paused";
                                    details = "Printer queue is currently paused.";
                                }
                                else if ((s & PRINTER_STATUS_ERROR) != 0)
                                {
                                    state = "Error";
                                    details = "Printer reports a general error condition.";
                                }
                                else if ((s & PRINTER_STATUS_PRINTING) != 0 || jobCount > 0)
                                {
                                    state = "Printing";
                                    details = $"Printing active ({jobCount} job(s) in Windows Spooler).";
                                }

                                return new PrinterTelemetry(
                                    PrinterName: resolvedName,
                                    IsOnline: isOnline,
                                    State: state,
                                    StateDetails: details,
                                    PaperStatus: paper,
                                    InkStatus: ink,
                                    CoverStatus: cover,
                                    QueuedJobsCount: jobCount,
                                    LastCheckedUtc: now
                                );
                            }
                        }
                        finally
                        {
                            Marshal.FreeHGlobal(pBuf);
                        }
                    }
                }
                finally
                {
                    ClosePrinter(hPrinter);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Direct WinSpool GetPrinter telemetry error for {Printer}: {Msg}", resolvedName, ex.Message);
        }

        // Fallback: check Windows printers list
        var printers = GetPrinters();
        var pQueue = printers.FirstOrDefault(p => string.Equals(p.Name, resolvedName, StringComparison.OrdinalIgnoreCase));
        bool online = pQueue?.IsOnline ?? true;

        return new PrinterTelemetry(
            PrinterName: resolvedName,
            IsOnline: online,
            State: online ? "Ready" : "Offline",
            StateDetails: online ? "Printer is online and ready." : "Printer is currently offline.",
            PaperStatus: "OK",
            InkStatus: "OK",
            CoverStatus: "Closed",
            QueuedJobsCount: 0,
            LastCheckedUtc: now
        );
    }
}
