# Troubleshooting & Diagnostics Guide

## 1. Status Shows "Office PC: OFFLINE"
- **Cause**: Office PC is asleep, or Tailscale is disconnected.
- **Resolution**:
  1. On Windows PC, ensure PC sleep settings are set to "Never sleep when plugged in".
  2. In Windows system tray, verify Tailscale is connected.
  3. On tablet, verify Tailscale app is active.

## 2. Printer Status Shows "Offline" or Jobs Do Not Print
- **Cause**: Brother DCP-T530DW is turned off or USB cable disconnected.
- **Resolution**:
  1. Confirm printer power LED is solid green.
  2. Verify USB cable is firmly plugged into PC port `USB001`.
  3. In Windows Settings -> Printers, check if "Brother DCP-T530DW Printer" has "Use Printer Offline" checked. Uncheck it.

## 3. Pairing Fails (401 Unauthorized)
- **Cause**: Incorrect 6-digit PIN.
- **Resolution**:
  1. Check the console output or logs of `SmartRemotePrintAgent.exe`.
  2. PIN changes when the service is restarted. Enter the current PIN shown on screen.

## 4. Marg Cloud Page Navigation When Pressing Back Key
- **Cause**: Esc mapping toggle is switched off.
- **Resolution**:
  1. Open top menu pill in the app.
  2. Verify checkbox **Tab Back = Esc Key** is checked.
