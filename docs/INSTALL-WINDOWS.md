# Windows 10 Setup Guide: Smart Remote Print Agent

## Prerequisites
- **Operating System**: Windows 10 (or Windows 11) 64-bit on office computer (`desktop-00ctdtl`).
- **Printer**: Brother DCP-T530DW Printer connected via USB001 (or Wi-Fi).
- **Tailscale**: Installed and connected to the tailnet (`tailfc14cd.ts.net`).
- **Port**: TCP 8766 (Preserves existing `C:\OfficePrint` on 8765).

---

## Installation Steps

### Option 1: Automated Installation via PowerShell (Recommended)
1. Copy the `windows-installer/` folder to your office PC.
2. Open PowerShell as **Administrator**.
3. Run the installer:
   ```powershell
   cd "path\to\windows-installer"
   .\install.ps1
   ```
4. The script will:
   - Create `C:\Program Files\SmartRemotePrint\`
   - Copy `SmartRemotePrintAgent.exe`
   - Open Windows Firewall for TCP port 8766
   - Register a Windows Scheduled Task to auto-start at logon
   - Launch the agent immediately in the background

---

### Option 2: Manual Standalone Execution
If you prefer running without installation:
1. Double click `SmartRemotePrintAgent.exe`.
2. A console window will appear displaying:
   ```
   =================================================
   SMART REMOTE PRINT - PAIRING PIN: [549210]
   Enter this 6-digit PIN on Lenovo Tab P12 to pair.
   =================================================
   ```
3. Take note of the 6-digit PIN to pair your Lenovo Tab P12.

---

## Verifying Office Printer Queue
To check your active printer queue in PowerShell:
```powershell
Get-Printer | Where-Object { $_.Name -like "*Brother DCP-T530DW*" }
```
You should see:
- Name: `Brother DCP-T530DW Printer`
- PortName: `USB001`
- DriverName: `Brother DCP-T530DW Printer`

---

## Uninstallation
To cleanly remove the agent:
```powershell
cd "path\to\windows-installer"
.\uninstall.ps1
```
