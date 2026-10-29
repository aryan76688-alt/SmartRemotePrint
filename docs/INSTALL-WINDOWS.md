# Windows 10 & 11 Setup Guide: Smart Remote Print Agent

## Quick 1-Command Installation (PowerShell Administrator)

Run this single command in **PowerShell (Run as Administrator)**:
```powershell
Invoke-WebRequest -Uri "https://github.com/aryan76688-alt/SmartRemotePrint/releases/download/v1.2.0/windows-installer.zip" -OutFile "$env:TEMP\windows-installer.zip"
Expand-Archive -Path "$env:TEMP\windows-installer.zip" -DestinationPath "C:\SmartRemotePrint" -Force
Set-Location "C:\SmartRemotePrint\windows-installer"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\install.ps1
```

---

## Prerequisites
- **Operating System**: Windows 10 or Windows 11 64-bit on office computer.
- **Printer**: Brother DCP-T530DW or any Windows Default Printer (USB, Wi-Fi, or Network).
- **Tailscale**: Optional / recommended for printing anywhere outside local Wi-Fi.
- **Port**: TCP 8766 (Preserves existing legacy services on 8765).

---

## Installation Options

### Option 1: Automated Git Clone or Pull
If you already cloned the repository to `C:\SmartRemotePrint`:
```powershell
cd "C:\SmartRemotePrint"
git pull origin main
cd "windows-installer"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\install.ps1
```
> **Note**: `install.ps1` automatically detects if binaries are present locally, and will auto-download them from GitHub Release v1.2.0 if missing.

### Option 2: Pre-Packaged Offline Installer (.zip)
1. Download **[windows-installer.zip](https://github.com/aryan76688-alt/SmartRemotePrint/releases/download/v1.2.0/windows-installer.zip)** from GitHub Releases.
2. Extract to `C:\SmartRemotePrint\`.
3. Open `windows-installer` folder, right-click `install.bat` and click **Run as administrator** (or run `.\install.ps1` in PowerShell).

---

## What Gets Installed
1. **24/7 Windows NT Service (`SmartRemotePrintAgent`)**:
   - Runs in background even when user logs off or screen locks.
   - Listens on dedicated port `8766`.
   - Dynamic printer switching: follows your Windows Default Printer or active printer selected from tablet/desktop.
2. **Desktop Print Manager GUI (`SmartRemotePrintDesktop.exe`)**:
   - Desktop shortcut: *Smart Remote Print (Print Anything)*
   - Right-click context menu: *🖨️ Print with Smart Remote Print* for PDFs, CSVs, TXT files.
   - Real-time printer switcher dropdown.
3. **Marg ERP Pharma Automation**:
   - Background CSV Watch folder: `C:\SmartRemotePrint\MargSync\`
   - Pharma keyboard hotkey helpers (`MargPharmaKeys.bat`)

---

## Uninstallation
To cleanly remove the service and files:
```powershell
cd "C:\SmartRemotePrint\windows-installer"
.\uninstall.ps1
```
```
