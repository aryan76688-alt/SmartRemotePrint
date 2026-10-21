# ==============================================================================
# SMART REMOTE PRINT - UNINSTALLATION SCRIPT
# ==============================================================================

Write-Host "Stopping and removing Smart Remote Print Agent..." -ForegroundColor Yellow

# Stop and delete Windows NT Service
$serviceName = "SmartRemotePrintAgent"
Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
sc.exe delete $serviceName | Out-Null

# Stop any running process
Stop-Process -Name "SmartRemotePrintAgent" -Force -ErrorAction SilentlyContinue

# Unregister Scheduled Task
Unregister-ScheduledTask -TaskName $serviceName -Confirm:$false -ErrorAction SilentlyContinue

# Remove Firewall Rule
Remove-NetFirewallRule -DisplayName "Smart Remote Print Agent (Port 8766)" -ErrorAction SilentlyContinue

# Remove Files
$installPath = "C:\Program Files\SmartRemotePrint"
if (Test-Path $installPath) {
    Remove-Item -Path $installPath -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Smart Remote Print Agent service successfully uninstalled." -ForegroundColor Green
