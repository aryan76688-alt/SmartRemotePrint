# Windows 10 Setup Guide: Smart Remote Print Agent

## Prerequisites
- **Operating System**: Windows 10 (or Windows 11) 64-bit on office computer (`desktop-00ctdtl`).
- **Printer**: Brother DCP-T530DW Printer connected via USB001 (or Wi-Fi).
- **Tailscale**: Installed and connected to the tailnet (`tailfc14cd.ts.net`).
- **Port**: TCP 8766 (Preserves existing `C:\OfficePrint` on 8765).

---

## Installation Steps

### Option 1: 1-Click Batch Installer (Recommended for Command Prompt & Explorer)
1. Copy or extract `windows-installer/` to your PC (e.g. `C:\SmartRemotePrint\windows-installer`).
2. Simply double-click **`install.bat`** (or run `install.bat` in Command Prompt):
   ```cmd
   cd "C:\SmartRemotePrint\windows-installer"
   install.bat
   ```
3. If prompted by Windows UAC, click **"Yes"** to grant Administrator privileges.
4. The installer will automatically:
   - Configure port 8766 in Windows Firewall
   - Install `SmartRemotePrintAgent.exe` as an auto-starting 24/7 background Windows NT Service
   - Install `SmartRemotePrintDesktop.exe` ("Smart Remote Print (Print Anything)" desktop shortcut)
   - Auto-detect the Brother DCP-T530DW or default printer
   - Set up Marg ERP Auto-Sync at `C:\SmartRemotePrint\MargSync`

---

### Option 2: Run via PowerShell
If running directly in PowerShell:
```powershell
cd "C:\SmartRemotePrint\windows-installer"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\install.ps1
```

> **Note for Command Prompt (`cmd.exe`) users**:
> If you see `'Set-ExecutionPolicy' is not recognized as an internal or external command`, you are in standard Command Prompt instead of PowerShell. In Command Prompt, run:
> ```cmd
> powershell -ExecutionPolicy Bypass -File .\install.ps1
> ```
> Or simply run `install.bat`.

---

### Option 3: Manual Standalone Execution
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
- Double-click **`uninstall.bat`** (or run `uninstall.bat` in Command Prompt), OR
- In PowerShell:
  ```powershell
  cd "path\to\windows-installer"
  .\uninstall.ps1
  ```
