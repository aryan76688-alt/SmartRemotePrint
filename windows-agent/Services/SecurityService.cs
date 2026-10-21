using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.Json;

namespace SmartRemotePrint.Agent.Services;

public interface ISecurityService
{
    string CurrentPairingPin { get; }
    string ServerFingerprint { get; }
    bool ValidatePairingPin(string pin);
    string GenerateAuthToken(string deviceId, string deviceName);
    bool ValidateAuthToken(string? token);
    bool CheckRateLimit(string clientIp);
    bool IsDuplicateJob(string sha256);
    void RegisterSubmittedJob(string sha256);
}

public class SecurityService : ISecurityService
{
    private readonly ILogger<SecurityService> _logger;
    private readonly string _storagePath;
    private readonly ConcurrentDictionary<string, TokenInfo> _activeTokens = new();
    private readonly ConcurrentDictionary<string, List<DateTime>> _rateLimits = new();
    private readonly ConcurrentDictionary<string, DateTime> _submittedHashes = new();

    // Default pairing PIN explicitly set to 260890
    public const string FIXED_PAIRING_PIN = "260890";
    public string CurrentPairingPin { get; private set; }
    public string ServerFingerprint { get; private set; }

    public record TokenInfo(string Token, string DeviceId, string DeviceName, DateTime CreatedAtUtc, DateTime ExpiresAtUtc);

    public SecurityService(ILogger<SecurityService> logger)
    {
        _logger = logger;
        _storagePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "SmartRemotePrint",
            "tokens.json"
        );

        ServerFingerprint = Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Environment.MachineName + "_SmartRemotePrint")));

        // Configured pairing PIN: 260890
        CurrentPairingPin = FIXED_PAIRING_PIN;
        _logger.LogInformation("=================================================");
        _logger.LogInformation("SMART REMOTE PRINT - PAIRING PIN: [{Pin}]", CurrentPairingPin);
        _logger.LogInformation("Lenovo Tab P12 auto-connect PIN set to: {Pin}", CurrentPairingPin);
        _logger.LogInformation("Compatible with Windows 10 and Windows 11 (Auto-Restart Enabled)");
        _logger.LogInformation("=================================================");

        LoadTokens();
    }

    public bool ValidatePairingPin(string pin)
    {
        if (string.IsNullOrWhiteSpace(pin)) return false;
        var clean = pin.Trim();
        return clean == FIXED_PAIRING_PIN || clean == CurrentPairingPin;
    }

    public string GenerateAuthToken(string deviceId, string deviceName)
    {
        var randomBytes = new byte[32];
        RandomNumberGenerator.Fill(randomBytes);
        var token = "srp_" + Convert.ToHexString(randomBytes).ToLowerInvariant();

        var tokenInfo = new TokenInfo(
            token,
            deviceId,
            deviceName,
            DateTime.UtcNow,
            DateTime.UtcNow.AddYears(1) // Long-lived token for auto-reconnect
        );

        _activeTokens[token] = tokenInfo;
        SaveTokens();

        _logger.LogInformation("Device paired successfully: {DeviceName} ({DeviceId}) with auto-reconnect enabled.", deviceName, deviceId);
        return token;
    }

    public bool ValidateAuthToken(string? token)
    {
        if (string.IsNullOrWhiteSpace(token)) return false;
        var cleanToken = token.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            ? token["Bearer ".Length..].Trim()
            : token.Trim();

        if (_activeTokens.TryGetValue(cleanToken, out var info))
        {
            if (DateTime.UtcNow <= info.ExpiresAtUtc)
            {
                return true;
            }
            _activeTokens.TryRemove(cleanToken, out _);
            SaveTokens();
        }
        return false;
    }

    public bool CheckRateLimit(string clientIp)
    {
        var now = DateTime.UtcNow;
        var window = now.AddMinutes(-1);

        var list = _rateLimits.GetOrAdd(clientIp, _ => new List<DateTime>());
        lock (list)
        {
            list.RemoveAll(t => t < window);
            if (list.Count >= 60) // Generous 60 requests/min for auto-polling & printing
            {
                return false;
            }
            list.Add(now);
            return true;
        }
    }

    public bool IsDuplicateJob(string sha256)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return false;
        var cutoff = DateTime.UtcNow.AddMinutes(-2);
        if (_submittedHashes.TryGetValue(sha256, out var submittedTime))
        {
            return submittedTime > cutoff;
        }
        return false;
    }

    public void RegisterSubmittedJob(string sha256)
    {
        if (!string.IsNullOrWhiteSpace(sha256))
        {
            _submittedHashes[sha256] = DateTime.UtcNow;
        }
    }

    private void SaveTokens()
    {
        try
        {
            var dir = Path.GetDirectoryName(_storagePath);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }
            var json = JsonSerializer.Serialize(_activeTokens.Values);
            File.WriteAllText(_storagePath, json);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Could not persist tokens: {Msg}", ex.Message);
        }
    }

    private void LoadTokens()
    {
        try
        {
            if (File.Exists(_storagePath))
            {
                var json = File.ReadAllText(_storagePath);
                var tokens = JsonSerializer.Deserialize<List<TokenInfo>>(json);
                if (tokens != null)
                {
                    foreach (var t in tokens)
                    {
                        if (DateTime.UtcNow <= t.ExpiresAtUtc)
                        {
                            _activeTokens[t.Token] = t;
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Could not load tokens: {Msg}", ex.Message);
        }
    }
}
