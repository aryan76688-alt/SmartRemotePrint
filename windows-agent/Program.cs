using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using SmartRemotePrint.Agent.Models;
using SmartRemotePrint.Agent.Services;

var builder = WebApplication.CreateBuilder(args);

// Enable running as a true Windows NT Service (24/7 background operation)
builder.Host.UseWindowsService();

// Configure Kestrel on dedicated port 8766 (preserving port 8765 for existing C:\OfficePrint)
builder.WebHost.ConfigureKestrel(serverOptions =>
{
    serverOptions.ListenAnyIP(8766);
    serverOptions.Limits.MaxRequestBodySize = 55 * 1024 * 1024; // 55MB (allows 50MB PDF + headers)
});

// Register services
builder.Services.AddSingleton<ISecurityService, SecurityService>();
builder.Services.AddSingleton<ISpoolerPrintService, SpoolerPrintService>();
builder.Services.AddCors(options =>
{
    options.AddDefaultPolicy(policy =>
    {
        policy.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod();
    });
});

var app = builder.Build();

app.UseCors();

// Rate limiting & Security middleware
app.Use(async (context, next) =>
{
    var sec = context.RequestServices.GetRequiredService<ISecurityService>();
    var clientIp = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    if (!sec.CheckRateLimit(clientIp))
    {
        context.Response.StatusCode = 429;
        await context.Response.WriteAsJsonAsync(new { error = "Rate limit exceeded (max 30 requests/min)." });
        return;
    }

    await next();
});

var startTime = DateTime.UtcNow;

// 1. Health & Telemetry Status
app.MapGet("/api/v1/health", (ISpoolerPrintService spooler) =>
{
    var printers = spooler.GetPrinters();
    var defaultPrinter = printers.FirstOrDefault(p => p.IsDefault)?.Name ?? "Brother DCP-T530DW Printer";

    return Results.Ok(new HealthResponse(
        Status: "healthy",
        Service: "SmartRemotePrintAgent",
        Version: "1.0.0",
        MachineName: Environment.MachineName,
        TailscaleHostname: "desktop-00ctdtl.tailfc14cd.ts.net",
        Port: 8766,
        UptimeSeconds: Math.Round((DateTime.UtcNow - startTime).TotalSeconds, 1),
        DefaultPrinter: defaultPrinter
    ));
});

// 2. Device Pairing
app.MapPost("/api/v1/pair", ([FromBody] PairingRequest req, ISecurityService sec) =>
{
    if (string.IsNullOrWhiteSpace(req.PairingPin) || !sec.ValidatePairingPin(req.PairingPin))
    {
        return Results.Unauthorized();
    }

    var token = sec.GenerateAuthToken(req.DeviceId, req.DeviceName);
    return Results.Ok(new PairingResponse(
        Success: true,
        AuthToken: token,
        ExpiresAtUtc: DateTime.UtcNow.AddDays(90),
        ServerFingerprint: sec.ServerFingerprint
    ));
});

// 3. Enumerate Windows Printers (Authenticated)
app.MapGet("/api/v1/printers", (HttpRequest request, ISecurityService sec, ISpoolerPrintService spooler) =>
{
    var authHeader = request.Headers["Authorization"].FirstOrDefault();
    if (!sec.ValidateAuthToken(authHeader))
    {
        return Results.Unauthorized();
    }

    var printers = spooler.GetPrinters();
    return Results.Ok(printers);
});

// 4. Submit PDF Print Job (Supports Multipart and Direct Raw PDF Stream)
app.MapPost("/api/v1/print", async (HttpRequest request, ISecurityService sec, ISpoolerPrintService spooler) =>
{
    var authHeader = request.Headers["Authorization"].FirstOrDefault();
    if (!sec.ValidateAuthToken(authHeader))
    {
        return Results.Unauthorized();
    }

    Stream? pdfStream = null;
    string docName = "Marg_Document.pdf";
    string? sha256 = null;
    string? targetPrinter = "Brother DCP-T530DW Printer";
    long fileLength = 0;

    if (request.HasFormContentType)
    {
        var form = await request.ReadFormAsync();
        var file = form.Files["file"] ?? form.Files.FirstOrDefault();
        var metadataRaw = form["metadata"].FirstOrDefault();

        if (file == null || file.Length == 0)
        {
            return Results.BadRequest(new { error = "Missing or empty PDF file payload." });
        }

        fileLength = file.Length;
        pdfStream = file.OpenReadStream();
        docName = file.FileName ?? "Marg_Document.pdf";

        if (!string.IsNullOrEmpty(metadataRaw))
        {
            try
            {
                var meta = JsonSerializer.Deserialize<PrintJobSubmission>(metadataRaw);
                if (meta != null)
                {
                    if (!string.IsNullOrEmpty(meta.DocumentName)) docName = meta.DocumentName;
                    if (!string.IsNullOrEmpty(meta.FileSha256)) sha256 = meta.FileSha256;
                    if (!string.IsNullOrEmpty(meta.PrinterName)) targetPrinter = meta.PrinterName;
                }
            }
            catch { }
        }
    }
    else
    {
        // Direct binary stream (application/pdf or octet-stream fallback)
        var memStream = new MemoryStream();
        await request.Body.CopyToAsync(memStream);
        if (memStream.Length == 0)
        {
            return Results.BadRequest(new { error = "Expected multipart/form-data or application/pdf payload." });
        }
        memStream.Position = 0;
        fileLength = memStream.Length;
        pdfStream = memStream;

        var headerDoc = request.Headers["X-Document-Name"].FirstOrDefault();
        if (!string.IsNullOrEmpty(headerDoc)) docName = headerDoc;

        var headerPrinter = request.Headers["X-Printer-Name"].FirstOrDefault();
        if (!string.IsNullOrEmpty(headerPrinter)) targetPrinter = headerPrinter;

        sha256 = request.Headers["X-File-SHA256"].FirstOrDefault();
    }

    if (fileLength > 50 * 1024 * 1024)
    {
        return Results.BadRequest(new { error = "File size exceeds 50MB limit." });
    }

    var metadata = new PrintJobSubmission(
        JobId: Guid.NewGuid().ToString("D"),
        ClientTimestamp: DateTime.UtcNow,
        PrinterName: targetPrinter,
        DocumentName: docName,
        FileSha256: sha256
    );

    // Duplicate check
    if (!string.IsNullOrEmpty(metadata.FileSha256) && sec.IsDuplicateJob(metadata.FileSha256))
    {
        return Results.Conflict(new { error = "Duplicate print job detected within recent window." });
    }

    try
    {
        var status = await spooler.EnqueueJobAsync(metadata, pdfStream);

        if (!string.IsNullOrEmpty(metadata.FileSha256))
        {
            sec.RegisterSubmittedJob(metadata.FileSha256);
        }

        return Results.Ok(status);
    }
    catch (InvalidOperationException ex)
    {
        return Results.BadRequest(new { error = ex.Message });
    }
    catch (Exception ex)
    {
        return Results.Problem(detail: ex.Message, statusCode: 500);
    }
    finally
    {
        if (pdfStream != null)
        {
            await pdfStream.DisposeAsync();
        }
    }
});

// 5. Query Job Status (Authenticated)
app.MapGet("/api/v1/jobs/{jobId}", (string jobId, HttpRequest request, ISecurityService sec, ISpoolerPrintService spooler) =>
{
    var authHeader = request.Headers["Authorization"].FirstOrDefault();
    if (!sec.ValidateAuthToken(authHeader))
    {
        return Results.Unauthorized();
    }

    var status = spooler.GetJobStatus(jobId);
    return status != null ? Results.Ok(status) : Results.NotFound(new { error = "Job not found." });
});

// 6. Cancel Job (Authenticated)
app.MapPost("/api/v1/jobs/{jobId}/cancel", (string jobId, HttpRequest request, ISecurityService sec, ISpoolerPrintService spooler) =>
{
    var authHeader = request.Headers["Authorization"].FirstOrDefault();
    if (!sec.ValidateAuthToken(authHeader))
    {
        return Results.Unauthorized();
    }

    var cancelled = spooler.CancelJob(jobId);
    return Results.Ok(new { jobId, cancelled });
});

Console.WriteLine("===================================================================");
Console.WriteLine(" SMART REMOTE PRINT AGENT - WINDOWS 10 SPOOLER SERVICE");
Console.WriteLine(" Dedicated Port: 8766 (C:\\OfficePrint on 8765 preserved)");
Console.WriteLine(" Target Printer: Brother DCP-T530DW Printer");
Console.WriteLine(" Tailscale Host: desktop-00ctdtl.tailfc14cd.ts.net");
Console.WriteLine("===================================================================");

app.Run();
