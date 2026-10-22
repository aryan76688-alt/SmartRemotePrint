namespace SmartRemotePrint.Agent.Models;

public record StockItem(
    string ItemCode,
    string ItemName,
    string Packing,
    string Batch,
    string Expiry,
    decimal Mrp,
    decimal Rate,
    int CurrentStock,
    string Company,
    DateTime UpdatedAtUtc
);

public record PartyOutstanding(
    string PartyCode,
    string PartyName,
    string Address,
    string Phone,
    decimal TotalOutstanding,
    decimal OverdueAmount,
    decimal CreditLimit,
    int BillCount,
    string LastBillDate,
    DateTime UpdatedAtUtc
);

public record SyncStatus(
    string Service,
    string WatchFolder,
    DateTime? LastSyncUtc,
    int TotalStockItems,
    int TotalParties,
    decimal TotalOutstandingAmount,
    bool IsAutoSyncActive,
    string? LastSyncError
);

public record SyncPushPayload(
    List<StockItem>? StockItems,
    List<PartyOutstanding>? Parties
);

public record CsvAnalyticsSummary(
    int TotalStockItems,
    int TotalStockUnits,
    decimal TotalInventoryValuation,
    int TotalParties,
    decimal TotalOutstandingAmount,
    decimal TotalOverdueAmount,
    int TotalBackupFiles,
    string? LatestBackupFile,
    DateTime? LastSyncUtc,
    string BackupFolder
);

public record BackupFileInfo(
    string FileName,
    long SizeBytes,
    DateTime CreatedUtc,
    string RelativePath
);
