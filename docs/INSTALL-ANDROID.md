# Android Tablet Setup Guide: Lenovo Tab P12

## Prerequisites
- **Device**: Lenovo Tab P12 (Android 13 / 14 / 15).
- **Accessory**: Lenovo Keyboard Case.
- **Tailscale**: Installed on tablet and connected to the tailnet.

---

## Installation Steps

### 1. Install the APK
1. Transfer `SmartRemotePrint.apk` to your tablet via USB cable, Google Drive, or ADB:
   ```bash
   adb install -r SmartRemotePrint.apk
   ```
2. Open the app **Smart Remote Print** from your app drawer.

---

### 2. Enable Android System PrintService
To make the office Brother printer show up in Android's system Print menu across all apps:
1. On your tablet, go to **Settings** -> **Connected devices** -> **Connection preferences** -> **Printing** (or search "Printing" in Settings).
2. Tap **Office Brother Remote Print** (Smart Remote Print service).
3. Toggle it to **ON**.
4. Now, any app on your tablet (Chrome, Adobe Acrobat, Files, WhatsApp) can select **"Brother DCP-T530DW (Office PC)"** when you tap Print!

---

### 3. Pairing with Office Windows PC
1. In the app, tap the top-right button **🖨️ Print & Menu**.
2. Tap **⚙ Pair PC**.
3. Verify the IP address: `100.111.100.122` (or `desktop-00ctdtl.tailfc14cd.ts.net`).
4. Enter the **6-digit PIN** shown on your Windows PC console.
5. Tap **Authorize & Connect**.
6. The status beacon will turn green: `Office PC: ONLINE (Brother DCP-T530DW Printer)`.

---

### 4. Marg Cloud & Hardware Esc Key Usage
- The browser opens directly to `https://account.margcloud.com/login`.
- Pressing **Esc** or the keyboard **Back** key on the Lenovo keyboard case:
  - Will **NOT** exit the app or navigate back.
  - Will immediately dispatch a Windows **Escape** key event (`keyCode: 27`) to Marg Cloud to close vouchers, exit menus, and cancel dialogs!
- Tap **🖨️ Direct Print** in the menu to print any active invoice or page directly to the Brother printer.
