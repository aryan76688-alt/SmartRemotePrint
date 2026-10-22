# Smart Remote Print
### Direct Android Tablet Remote Printing, Marg Cloud ERP Browser & Direct Stock / Outstanding Auto-Sync

**Smart Remote Print** is a production-grade enterprise software suite enabling seamless, authenticated remote printing from a Lenovo Tab P12 tablet directly to an office Windows 10/11 computer running a Brother DCP-T530DW printer over a private Tailscale mesh network. It also includes real-time Marg ERP stock synchronization and chemist outstanding balance monitoring for **Shreeji Healthcare Order Book** and **Marg ERP Envelope Print Manager**.

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

### 3. Pharma Quick-Action Bar & Hardware Key Mapping (Tablet)
- **⚡ Pharma Quick-Action Ribbon**: Slim, dockable, high-visibility bar featuring one-touch buttons for:
  - `ESC` - Exit / Cancel modal
  - `F2` - Date change
  - `F3` - Item Master / Search
  - `F7` - Cash / Credit payment mode
  - `F8` - Bill Edit / Modify
  - `F9` - Print Bill
  - `F10` - Save Bill
  - `↵ Enter` - Advance line / Confirm
- **Custom Hardware Mapping for Lenovo Tab P12**:
  - Automatically translates physical keyboard combinations (since the tablet keyboard accessory has no F1-F12 key row):
    - `Alt + 2` / `Ctrl + 2` -> `F2` (Date)
    - `Alt + 3` / `Ctrl + 3` -> `F3` (Item Search)
    - `Alt + 7` / `Ctrl + 7` -> `F7` (Cash/Credit)
    - `Alt + 8` / `Ctrl + 8` -> `F8` (Bill Edit)
    - `Alt + 9` / `Ctrl + 9` / `Ctrl + P` -> `F9` (Print Bill)
    - `Alt + 0` / `Ctrl + 0` / `Ctrl + S` -> `F10` (Save Bill)
    - `Alt + 1` .. `Alt + 6` -> `F1` .. `F6`
    - `Alt + -` / `Alt + =` -> `F11` / `F12`
    - `Alt + E` / `Esc` -> `ESC`

### 4. Direct Stock & Chemist Outstanding Auto-Sync (Windows Agent)
- 24/7 background worker running as a Windows NT Service on port `8766`.
- Monitors `C:\SmartRemotePrint\MargSync` for Marg ERP exports (`stock.csv`, `outstanding.csv`, JSON).
- Provides instant REST API query endpoints:
  - `GET /api/v1/sync/stock?search=...` - Live item stock check (prevents order bouncing).
  - `GET /api/v1/sync/outstanding?search=...` - Chemist overdue & credit balance check (prevents overdue losses).
  - `GET /api/v1/sync/status` - Sync telemetry, total stock count, and total overdue amount.
  - `POST /api/v1/sync/push` - Direct webhook/JSON ingestion.
  - `POST /api/v1/sync/refresh` - Immediate folder re-scan.
- Seamlessly connects with **Shreeji Healthcare Order Book** and **Marg ERP Envelope Print Manager**.

### 5. Windows 10/11 Companion Key Translator
- Built-in scripts (`MargPharmaKeys.ahk`, `MargPharmaKeys.ps1`, `MargPharmaKeys.bat`) deployed to Windows.
- Allows Windows billing operators with 60% compact keyboards or tablets to use the exact same shortcuts (`Alt+2`=Date, `Alt+3`=Item, `Alt+9`=Print, `Alt+0`=Save).

### 6. Windows Print Agent (Port 8766)
- Native, standalone Windows 10 & 11 x64 binary (`SmartRemotePrintAgent.exe`).
- Zero interference with existing private dashboard at `C:\OfficePrint` (Port 8765).
- Direct Windows Spooler integration via `winspool.drv`.
- Support for Brother DCP-T530DW USB001 and Wi-Fi queues.

### 7. Zero-Trust Security & Android Keystore
- Pairing with 6-digit OTP code (`260890`).
- Cryptographically secure Bearer tokens stored in hardware-backed `AndroidKeyStore`.
- Zero plaintext credential storage.
- Zero collection of Marg Cloud passwords or sensitive ERP data.
- Payload validation, magic bytes verification (`%PDF-`), and duplicate job suppression.

---

## 📁 Repository Structure
```
SmartRemotePrint/
├── MargCloud.apk         # Production Android APK (with Pharma Quick Bar & Key Mappings)
├── windows-installer.zip # Ready-to-extract Windows 10/11 Service & Agent package
├── android-app/          # Android Studio project (Kotlin, MinSDK 26)
├── windows-agent/        # Windows C# .NET 6 Print Agent & MargSync source code
├── windows-installer/    # Automated install.ps1, standalone EXE & Pharma Keys
├── shared-protocol/      # Standardized JSON Schemas & OpenAPI spec
├── ui-stitch/            # Google Stitch tablet designs & HTML screens
├── scripts/              # Build & release automation scripts
├── tests/                # Automated test suite
└── docs/                 # Detailed setup & troubleshooting guides
```
