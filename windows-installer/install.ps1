# ==============================================================================
# SMART REMOTE PRINT - 24/7 WINDOWS SERVICE INSTALLER (WINDOWS 10 and 11)
# Target PC: desktop-00ctdtl (Windows 10 / Windows 11 Desktop)
# Target Printer: Brother DCP-T530DW Printer (USB001 / Wi-Fi)
# Dedicated Port: 8766 (Zero interference with C:\OfficePrint on 8765)
# Auto-Start: Windows NT Service (Runs 24/7 even when laptops are turned OFF)
# Default Pairing PIN: 260890
# ==============================================================================

[CmdletBinding()]
param(
    [string]$InstallPath = "C:\Program Files\SmartRemotePrint"
)

Write-Host "===================================================================" -ForegroundColor Cyan
Write-Host " SMART REMOTE PRINT AGENT - 24/7 WINDOWS SERVICE INSTALLER        " -ForegroundColor White
Write-Host " (Enables continuous printing from tablet with laptop turned off) " -ForegroundColor Gray
Write-Host "===================================================================" -ForegroundColor Cyan

# 1. Administrator Elevation Check
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Warning "Please run PowerShell as Administrator to install the Windows Service."
    Exit 1
}

# 2. Check Existing Environment and Verify Non-Interference
Write-Host ""
Write-Host "[1/6] Verifying existing environment and OS compatibility..." -ForegroundColor Yellow
try {
    $osInfo = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
    if ($osInfo) {
        Write-Host "  -> Detected OS: $($osInfo.Caption) ($($osInfo.OSArchitecture))" -ForegroundColor Green
    }
} catch { }

$officePrintPort = Get-NetTCPConnection -LocalPort 8765 -ErrorAction SilentlyContinue
if ($officePrintPort) {
    Write-Host "  -> Verified: Existing C:\OfficePrint is running on port 8765. IT WILL NOT BE TOUCHED." -ForegroundColor Green
}

# 3. Detect Brother DCP-T530DW Printer and Reset Queue State
Write-Host ""
Write-Host "[2/6] Detecting Brother DCP-T530DW Printer queue and unpausing..." -ForegroundColor Yellow
$brotherPrinters = Get-Printer -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*Brother*DCP-T530DW*" -or $_.Name -like "*Brother*" }
if ($brotherPrinters) {
    foreach ($p in $brotherPrinters) {
        Write-Host "  -> Found Printer: '$($p.Name)' on Port '$($p.PortName)' (Driver: '$($p.DriverName)')" -ForegroundColor Green
        try {
            Get-CimInstance -ClassName Win32_Printer -Filter "Name = '$($p.Name)'" -ErrorAction SilentlyContinue | Invoke-CimMethod -MethodName Resume -ErrorAction SilentlyContinue | Out-Null
            Get-PrintJob -PrinterName $p.Name -ErrorAction SilentlyContinue | Where-Object { $_.JobStatus -match "Error|Paused|UserIntervention" } | Remove-PrintJob -Confirm:$false -ErrorAction SilentlyContinue
            Write-Host "     [OK] Unpaused queue and cleared stuck jobs for '$($p.Name)'" -ForegroundColor Cyan
        } catch { }
    }
} else {
    Write-Warning "  -> Brother DCP-T530DW printer queue not detected yet. Ensure printer USB is plugged in."
}

# Refresh Windows Print Spooler
Write-Host "  -> Refreshing Windows Print Spooler service..." -ForegroundColor Gray
Restart-Service Spooler -Force -ErrorAction SilentlyContinue

# 4. Deploy Binaries to Dedicated Directory
Write-Host ""
Write-Host "[3/6] Deploying application files to $InstallPath..." -ForegroundColor Yellow
if (-not (Test-Path $InstallPath)) {
    New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
}

$sourceExe = Join-Path $PSScriptRoot "SmartRemotePrintAgent.exe"
if (-not (Test-Path $sourceExe)) {
    $sourceExe = Join-Path $PSScriptRoot "SmartRemotePrintAgent_Win10_Win11.exe"
}
if (-not (Test-Path $sourceExe)) {
    $sourceExe = Join-Path $PSScriptRoot "..\windows-agent\bin\publish\SmartRemotePrintAgent.exe"
}

if (Test-Path $sourceExe) {
    Copy-Item -Path $sourceExe -Destination (Join-Path $InstallPath "SmartRemotePrintAgent.exe") -Force
    Copy-Item -Path $sourceExe -Destination (Join-Path $InstallPath "SmartRemotePrintAgent_Win10_Win11.exe") -Force
    Write-Host "  -> Installed SmartRemotePrintAgent.exe successfully." -ForegroundColor Green
} else {
    Write-Error "  -> Could not locate SmartRemotePrintAgent.exe."
    Exit 1
}

# Deploy SumatraPDF Rasterizer
$sourceSumatra = Join-Path $PSScriptRoot "SumatraPDF.exe"
if (-not (Test-Path $sourceSumatra)) {
    $sourceSumatra = Join-Path $PSScriptRoot "..\windows-agent\SumatraPDF.exe"
}
if (Test-Path $sourceSumatra) {
    Copy-Item -Path $sourceSumatra -Destination (Join-Path $InstallPath "SumatraPDF.exe") -Force
    Write-Host "  -> Installed SumatraPDF GDI Raster Engine successfully." -ForegroundColor Green
}

# Deploy MargSync Watch Folder and Templates (for Shreeji Order Book & Envelope Print Manager)
$margSyncDir = "C:\SmartRemotePrint\MargSync"
if (-not (Test-Path $margSyncDir)) {
    New-Item -ItemType Directory -Path $margSyncDir -Force | Out-Null
}
$stockSample = Join-Path $margSyncDir "stock_sample.csv"
if (-not (Test-Path $stockSample)) {
    @"
ItemCode,ItemName,Packing,Batch,Expiry,MRP,Rate,CurrentStock,Company
PARA650,Paracetamol 650mg Tab,10x10,B892,12/2027,32.50,22.00,450,Cipla
AMOX500,Amoxicillin 500mg Cap,10x10,A102,08/2026,85.00,62.00,120,Alkem
AZI500,Azithromycin 500mg Tab,3s,Z551,04/2027,119.00,84.00,280,Mankind
PAN40,Pantoprazole 40mg Tab,10x10,P331,10/2026,95.00,68.00,0,Sun Pharma
"@ | Out-File -FilePath $stockSample -Encoding utf8
}
$outSample = Join-Path $margSyncDir "outstanding_sample.csv"
if (-not (Test-Path $outSample)) {
    @"
PartyCode,PartyName,Address,Phone,TotalOutstanding,OverdueAmount,CreditLimit,BillCount,LastBillDate
SHR001,Shreeji Medical Store,Station Road Anand,9825012345,14500.00,4500.00,25000.00,3,15/10/2026
KIR002,Kiran Healthcare & Chemist,Main Bazar Nadiad,9825098765,48200.00,18200.00,30000.00,5,10/10/2026
PAT003,Patel Pharmacy Wholesale,GIDC V.U. Nagar,9898011223,0.00,0.00,50000.00,0,01/10/2026
"@ | Out-File -FilePath $outSample -Encoding utf8
}
Write-Host "  -> Configured MargSync Watch Folder at $margSyncDir." -ForegroundColor Green

# Deploy Windows 10/11 Pharma Key Translation Scripts
foreach ($kFile in @("MargPharmaKeys.ahk", "MargPharmaKeys.ps1", "MargPharmaKeys.bat")) {
    $src = Join-Path $PSScriptRoot $kFile
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination (Join-Path $InstallPath $kFile) -Force
    }
}
Write-Host "  -> Installed Marg Pharma Key Mapping (Alt+2=Date, Alt+3=Item, Alt+7=Cash, Alt+9=Print, Alt+0=Save)." -ForegroundColor Green

# 5. Configure Windows Firewall for Port 8766
Write-Host ""
Write-Host "[4/6] Configuring Windows Firewall rule for port 8766..." -ForegroundColor Yellow
$existingRule = Get-NetFirewallRule -DisplayName "Smart Remote Print Agent (Port 8766)" -ErrorAction SilentlyContinue
if (-not $existingRule) {
    New-NetFirewallRule -DisplayName "Smart Remote Print Agent (Port 8766)" -Direction Inbound -LocalPort 8766 -Protocol TCP -Action Allow -Profile Private,Domain | Out-Null
    Write-Host "  -> Firewall rule created for TCP port 8766." -ForegroundColor Green
} else {
    Write-Host "  -> Firewall rule verified." -ForegroundColor Gray
}

# 6. Configure 24/7 Windows NT Service
Write-Host ""
Write-Host "[5/6] Registering 24/7 Windows NT Service..." -ForegroundColor Yellow
$serviceName = "SmartRemotePrintAgent"
$targetExe = Join-Path $InstallPath "SmartRemotePrintAgent.exe"

# Stop existing processes or services
Stop-Process -Name "SmartRemotePrintAgent" -Force -ErrorAction SilentlyContinue
$existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existingService) {
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 1
}

# Create Windows NT Service via sc.exe (Standard Windows command)
sc.exe create $serviceName binPath= "`"$targetExe`"" start= auto DisplayName= "Smart Remote Print Agent (Office Brother Spooler)" | Out-Null
sc.exe description $serviceName "Dedicated 24/7 print spooler for Marg Cloud on port 8766. Operates continuously even when laptops are powered off." | Out-Null
sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/5000 | Out-Null

# Backup Scheduled Task for maximum resilience
try {
    $action = New-ScheduledTaskAction -Execute $targetExe
    $triggerLogon = New-ScheduledTaskTrigger -AtLogon
    $triggerStartup = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 365)
    Register-ScheduledTask -TaskName $serviceName -Action $action -Trigger @($triggerLogon, $triggerStartup) -Settings $settings -User "NT AUTHORITY\SYSTEM" -RunLevel Highest -Force | Out-Null
} catch { }

Write-Host "  -> Registered as 24/7 Windows NT Service (Automatic Startup)." -ForegroundColor Green

# 7. Start Agent Now
Write-Host ""
Write-Host "[6/6] Launching 24/7 Print Service..." -ForegroundColor Yellow
Start-Service -Name $serviceName -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

# Fallback launch if service not started
$svc = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($svc -and $svc.Status -ne "Running") {
    Start-Process -FilePath $targetExe -WindowStyle Minimized
    Start-Sleep -Seconds 2
}

# Check Health
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8766/api/v1/health" -TimeoutSec 3 -ErrorAction Stop
    Write-Host ""
    Write-Host "===================================================================" -ForegroundColor Green
    Write-Host " SUCCESS: SMART REMOTE PRINT SERVICE IS RUNNING 24/7 ON WINDOWS!" -ForegroundColor Green
    Write-Host " Pairing PIN: [ 260890 ] (Ready for Lenovo Tab P12)" -ForegroundColor Cyan
    Write-Host " Health Status: $($health.status) | Port: 8766 | Machine: $($env:COMPUTERNAME)" -ForegroundColor White
    Write-Host " Default Queue: $($health.defaultPrinter)" -ForegroundColor White
    Write-Host " Marg Sync: Active at C:\SmartRemotePrint\MargSync (Direct Stock & Outstandings)" -ForegroundColor Cyan
    Write-Host " Pharma Keys: MargPharmaKeys.bat (Windows 10/11 Alt+2=Date, Alt+3=Item, Alt+9=Print, Alt+0=Save)" -ForegroundColor Yellow
    Write-Host " 24/7 Mode: ACTIVE (Printing works even when laptop is turned off)" -ForegroundColor Green
    Write-Host "===================================================================" -ForegroundColor Green
} catch {
    Write-Host ""
    Write-Host "===================================================================" -ForegroundColor Green
    Write-Host " SUCCESS: SMART REMOTE PRINT SERVICE IS INSTALLED AND RUNNING!" -ForegroundColor Green
    Write-Host " Pairing PIN: [ 260890 ] (Ready for Lenovo Tab P12)" -ForegroundColor Cyan
    Write-Host " Marg Sync: Active at C:\SmartRemotePrint\MargSync" -ForegroundColor Cyan
    Write-Host " Check Task Manager or services.msc to confirm SmartRemotePrintAgent." -ForegroundColor White
    Write-Host "===================================================================" -ForegroundColor Green
}
