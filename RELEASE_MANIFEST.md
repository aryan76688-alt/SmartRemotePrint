# Smart Remote Print & Marg Cloud - Release Manifest

**Generated:** 2026-10-22 UTC

| Deliverable | Platform | Size | SHA-256 Checksum |
|---|---|---|---|
| `Marg Cloud.apk` | Android (Lenovo Tab P12) | 2.6M | `cc0276944003d6cf33a54e112a345fda0a8404f465bbce1cc897643b9e864856` |
| `MargCloud.apk` | Android (Lenovo Tab P12) | 2.6M | `cc0276944003d6cf33a54e112a345fda0a8404f465bbce1cc897643b9e864856` |
| `SmartRemotePrint.apk` | Android (Lenovo Tab P12) | 2.6M | `cc0276944003d6cf33a54e112a345fda0a8404f465bbce1cc897643b9e864856` |
| `SmartRemotePrintAgent.exe` | Windows 10 & 11 x64 | 83M | `5c02a1d264991929def6399cf9e3ade1a70dca39440818cbbf6ad9ac64876b03` |
| `SmartRemotePrintAgent_Win10_Win11.exe` | Windows 10 & 11 x64 | 83M | `5c02a1d264991929def6399cf9e3ade1a70dca39440818cbbf6ad9ac64876b03` |
| `windows-installer.zip` | Windows 10 & 11 Suite | 85M | `b5e8615c5010df96fd27bfb74d6fbb61f4c58b8aca7d5899a256c54b7152beb6` |

### New Features in this Release:
1. **Pharma Quick-Action Bar & Hardware Key Mapping (Tablet):**
   - Slim dockable ribbon with one-touch buttons for `ESC`, `F2 Date`, `F3 Item`, `F7 Cash`, `F8 Edit`, `F9 Print`, `F10 Save`, and `Enter`.
   - Hardware translation for Lenovo Tab P12 keyboard (no F1-F12 keys): `Alt+2`->Date, `Alt+3`->Item, `Alt+7`->Cash, `Alt+8`->Edit, `Alt+9`/`Ctrl+P`->Print, `Alt+0`/`Ctrl+S`->Save.
2. **Direct Stock & Chemist Outstanding Auto-Sync (Windows 10/11):**
   - 24/7 background worker watching `C:\SmartRemotePrint\MargSync`.
   - Real-time endpoints for stock query (`/api/v1/sync/stock`) and chemist outstanding balance (`/api/v1/sync/outstanding`).
   - Seamless integration with *Shreeji Healthcare Order Book* & *Marg ERP Envelope Print Manager*.
3. **Windows 10/11 Companion Key Translator:**
   - `MargPharmaKeys.ahk`, `MargPharmaKeys.ps1`, `MargPharmaKeys.bat` for Windows users with compact 60% keyboards.
