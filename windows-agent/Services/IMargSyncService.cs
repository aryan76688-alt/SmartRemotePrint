namespace SmartRemotePrint.Agent.Services;

using SmartRemotePrint.Agent.Models;

public interface IMargSyncService
{
    SyncStatus GetStatus();
    IReadOnlyList<StockItem> GetStock(string? search = null, int limit = 100, int minStock = -1);
    IReadOnlyList<PartyOutstanding> GetOutstandings(string? search = null, bool overdueOnly = false, int limit = 100);
    Task IngestPayloadAsync(SyncPushPayload payload);
    Task TriggerSyncAsync();
}
