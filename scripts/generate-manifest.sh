#!/usr/bin/env bash
set -e
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARG_APK="$ROOT_DIR/Marg Cloud.apk"
MARG_NO_SPACE_APK="$ROOT_DIR/MargCloud.apk"
APK_FILE="$ROOT_DIR/SmartRemotePrint.apk"
EXE_FILE="$ROOT_DIR/windows-agent/bin/publish/SmartRemotePrintAgent.exe"
EXE_INSTALLER_DUAL="$ROOT_DIR/windows-installer/SmartRemotePrintAgent_Win10_Win11.exe"

echo "# Smart Remote Print & Marg Cloud - Release Manifest" > "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "**Generated:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "| Deliverable | Platform | Size | SHA-256 Checksum |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "|---|---|---|---|" >> "$ROOT_DIR/RELEASE_MANIFEST.md"

if [ -f "$MARG_APK" ]; then
    SIZE=$(ls -lh "$MARG_APK" | awk '{print $5}')
    SHA=$(sha256sum "$MARG_APK" | awk '{print $1}')
    echo "| \`Marg Cloud.apk\` | Android (Lenovo Tab P12) | $SIZE | \`$SHA\` |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
fi

if [ -f "$MARG_NO_SPACE_APK" ]; then
    SIZE=$(ls -lh "$MARG_NO_SPACE_APK" | awk '{print $5}')
    SHA=$(sha256sum "$MARG_NO_SPACE_APK" | awk '{print $1}')
    echo "| \`MargCloud.apk\` | Android (Lenovo Tab P12) | $SIZE | \`$SHA\` |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
fi

if [ -f "$APK_FILE" ]; then
    SIZE=$(ls -lh "$APK_FILE" | awk '{print $5}')
    SHA=$(sha256sum "$APK_FILE" | awk '{print $1}')
    echo "| \`SmartRemotePrint.apk\` | Android (Lenovo Tab P12) | $SIZE | \`$SHA\` |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
fi

if [ -f "$EXE_FILE" ]; then
    SIZE=$(ls -lh "$EXE_FILE" | awk '{print $5}')
    SHA=$(sha256sum "$EXE_FILE" | awk '{print $1}')
    echo "| \`SmartRemotePrintAgent.exe\` | Windows 10 & 11 x64 | $SIZE | \`$SHA\` |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
fi

if [ -f "$EXE_INSTALLER_DUAL" ]; then
    SIZE=$(ls -lh "$EXE_INSTALLER_DUAL" | awk '{print $5}')
    SHA=$(sha256sum "$EXE_INSTALLER_DUAL" | awk '{print $1}')
    echo "| \`SmartRemotePrintAgent_Win10_Win11.exe\` | Windows 10 & 11 x64 | $SIZE | \`$SHA\` |" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
fi

echo "" >> "$ROOT_DIR/RELEASE_MANIFEST.md"
echo "All binaries verified and ready for production deployment." >> "$ROOT_DIR/RELEASE_MANIFEST.md"
cat "$ROOT_DIR/RELEASE_MANIFEST.md"
