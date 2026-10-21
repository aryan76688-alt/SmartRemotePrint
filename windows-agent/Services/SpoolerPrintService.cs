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

    public string ResolvePrinterName(string? requestedName)
    {
        var available = GetPrinters();
        if (available == null || available.Count == 0)
        {
            return requestedName ?? "Brother DCP-T530DW Printer";
        }

        // 1. Exact match
        if (!string.IsNullOrWhiteSpace(requestedName))
        {
            var exact = available.FirstOrDefault(p => string.Equals(p.Name, requestedName, StringComparison.OrdinalIgnoreCase));
            if (exact != null) return exact.Name;
        }

        // 2. Specific Brother T530 match (e.g. "Brother DCP-T530DW series", "Brother DCP-T530DW Printer")
        var brotherT530 = available.FirstOrDefault(p =>
            (p.Name.Contains("Brother", StringComparison.OrdinalIgnoreCase) && (p.Name.Contains("T530", StringComparison.OrdinalIgnoreCase) || p.Name.Contains("DCP-T530DW", StringComparison.OrdinalIgnoreCase))) ||
            (p.DriverName.Contains("Brother", StringComparison.OrdinalIgnoreCase) && (p.DriverName.Contains("T530", StringComparison.OrdinalIgnoreCase) || p.DriverName.Contains("DCP-T530DW", StringComparison.OrdinalIgnoreCase)))
        );
        if (brotherT530 != null) return brotherT530.Name;

        // 3. Any Brother printer
        var anyBrother = available.FirstOrDefault(p => p.Name.Contains("Brother", StringComparison.OrdinalIgnoreCase));
        if (anyBrother != null) return anyBrother.Name;

        // 4. Default printer
        var def = available.FirstOrDefault(p => p.IsDefault);
        if (def != null) return def.Name;

        return available[0].Name;
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
}
