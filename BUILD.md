# Smart Remote Print - Build Instructions

## Building Windows Agent (win-x64 Standalone EXE)
Run:
```bash
./scripts/build-windows.sh
```
Output: `windows-agent/bin/publish/SmartRemotePrintAgent.exe` (PE32+ 64-bit Windows executable).

## Building Android APK
Run:
```bash
./scripts/build-android.sh
```
Output: `SmartRemotePrint.apk` (Installable Android APK).

## Running Automated Tests
Run:
```bash
python3 tests/agent_tests.py
```
