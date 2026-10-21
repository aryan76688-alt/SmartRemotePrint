# Smart Remote Print
### Direct Android Tablet Remote Printing & Marg Cloud ERP Browser

**Smart Remote Print** is a production-grade enterprise software suite enabling seamless, authenticated remote printing from a Lenovo Tab P12 tablet directly to an office Windows 10 computer running a Brother DCP-T530DW printer over a private Tailscale mesh network.

---

## 🌟 Core Architecture & Features

### 1. Genuine Android `PrintService`
- Registers the office Brother DCP-T530DW printer directly into Android's system Print menu.
- Enables printing from any app on the tablet (Marg Cloud, PDF viewers, Google Chrome, WhatsApp).
- Streaming PDF upload to Windows Spooler over Tailscale (`http://100.111.100.122:8766`).

### 2. Marg Cloud ERP Browser & Hardware Escape
- Default home URL: `https://account.margcloud.com/login`.
- True immersive edge-to-edge fullscreen mode.
- Physical keyboard interceptor: remaps Lenovo keyboard case Back and Esc keys to Windows `Escape` (`keyCode: 27`) events without triggering Android back navigation or app exit.
- Quick floating menu with **🖨️ Direct Print** button.

### 3. Windows Print Agent (Port 8766)
- Native, standalone Windows 10 x64 binary (`SmartRemotePrintAgent.exe`).
- Zero interference with existing private dashboard at `C:\OfficePrint` (Port 8765).
- Direct Windows Spooler integration via `winspool.drv`.
- Support for Brother DCP-T530DW USB001 and Wi-Fi queues.

### 4. Zero-Trust Security & Android Keystore
- Pairing with 6-digit OTP code.
- Cryptographically secure Bearer tokens stored in hardware-backed `AndroidKeyStore`.
- Zero plaintext credential storage.
- Zero collection of Marg Cloud passwords or sensitive ERP data.
- Payload validation, magic bytes verification (`%PDF-`), and duplicate job suppression.

---

## 📁 Repository Structure
```
SmartRemotePrint/
├── android-app/          # Android Studio project (Kotlin, MinSDK 26)
├── windows-agent/        # Windows C# .NET 6 Print Agent source code
├── windows-installer/    # Automated install.ps1 & standalone EXE
├── shared-protocol/      # Standardized JSON Schemas & OpenAPI spec
├── ui-stitch/            # Google Stitch tablet designs & HTML screens
├── scripts/              # Build & release automation scripts
├── tests/                # Automated test suite
└── docs/                 # Detailed setup & troubleshooting guides
```
