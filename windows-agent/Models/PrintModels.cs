using System.Text.Json.Serialization;

namespace SmartRemotePrint.Agent.Models;

public record HealthResponse(
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("service")] string Service,
    [property: JsonPropertyName("version")] string Version,
    [property: JsonPropertyName("machineName")] string MachineName,
    [property: JsonPropertyName("tailscaleHostname")] string TailscaleHostname,
    [property: JsonPropertyName("port")] int Port,
    [property: JsonPropertyName("uptimeSeconds")] double UptimeSeconds,
    [property: JsonPropertyName("defaultPrinter")] string DefaultPrinter
);

public record PairingRequest(
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("deviceName")] string DeviceName,
    [property: JsonPropertyName("pairingPin")] string PairingPin
);

public record PairingResponse(
    [property: JsonPropertyName("success")] bool Success,
    [property: JsonPropertyName("authToken")] string AuthToken,
    [property: JsonPropertyName("expiresAtUtc")] DateTime ExpiresAtUtc,
    [property: JsonPropertyName("serverFingerprint")] string ServerFingerprint
);

public record PrinterQueueInfo(
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("driverName")] string DriverName,
    [property: JsonPropertyName("portName")] string PortName,
    [property: JsonPropertyName("isDefault")] bool IsDefault,
    [property: JsonPropertyName("isOnline")] bool IsOnline,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("connectionType")] string ConnectionType,
    [property: JsonPropertyName("supportedPaperSizes")] List<string> SupportedPaperSizes,
    [property: JsonPropertyName("supportsColor")] bool SupportsColor,
    [property: JsonPropertyName("supportsDuplex")] bool SupportsDuplex
);

public record PrintJobSubmission(
    [property: JsonPropertyName("jobId")] string JobId,
    [property: JsonPropertyName("clientTimestamp")] DateTime ClientTimestamp,
    [property: JsonPropertyName("printerName")] string PrinterName,
    [property: JsonPropertyName("documentName")] string DocumentName,
    [property: JsonPropertyName("copies")] int Copies = 1,
    [property: JsonPropertyName("colorMode")] string ColorMode = "Color",
    [property: JsonPropertyName("duplex")] string Duplex = "Simplex",
    [property: JsonPropertyName("paperSize")] string PaperSize = "A4",
    [property: JsonPropertyName("fileSha256")] string FileSha256 = ""
);

public record PrintJobStatus(
    [property: JsonPropertyName("jobId")] string JobId,
    [property: JsonPropertyName("windowsSpoolerJobId")] int WindowsSpoolerJobId,
    [property: JsonPropertyName("state")] string State,
    [property: JsonPropertyName("errorMessage")] string? ErrorMessage,
    [property: JsonPropertyName("pagesSubmitted")] int PagesSubmitted,
    [property: JsonPropertyName("pagesPrinted")] int PagesPrinted,
    [property: JsonPropertyName("submittedAtUtc")] DateTime SubmittedAtUtc,
    [property: JsonPropertyName("completedAtUtc")] DateTime? CompletedAtUtc
);

public record ActivePrinterConfig(
    [property: JsonPropertyName("activePrinter")] string ActivePrinter,
    [property: JsonPropertyName("fallbackPrinter")] string FallbackPrinter = "Brother DCP-T530DW Printer",
    [property: JsonPropertyName("fitToPage")] bool FitToPage = true,
    [property: JsonPropertyName("copies")] int Copies = 1,
    [property: JsonPropertyName("lastChangedUtc")] DateTime? LastChangedUtc = null
);

public record SelectPrinterRequest(
    [property: JsonPropertyName("printerName")] string PrinterName,
    [property: JsonPropertyName("setAsWindowsDefault")] bool SetAsWindowsDefault = false
);

public record DirectPrintFileRequest(
    [property: JsonPropertyName("filePath")] string? FilePath,
    [property: JsonPropertyName("printerName")] string? PrinterName,
    [property: JsonPropertyName("documentName")] string? DocumentName,
    [property: JsonPropertyName("copies")] int Copies = 1
);

