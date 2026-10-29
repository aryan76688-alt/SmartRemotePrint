#!/usr/bin/env bash
set -e
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=== 1. Building Smart Remote Print Windows Agent Service (win-x64) ==="
AGENT_DIR="$ROOT_DIR/windows-agent"
dotnet publish "$AGENT_DIR/SmartRemotePrintAgent.csproj" \
    -c Release \
    -r win-x64 \
    --self-contained true \
    -p:PublishSingleFile=true \
    -p:EnableCompressionInSingleFile=true \
    -p:IncludeNativeLibrariesForSelfExtract=true \
    -o "$AGENT_DIR/bin/publish"

echo "=== 2. Building Smart Remote Print Desktop Manager GUI (win-x64) ==="
DESKTOP_DIR="$ROOT_DIR/windows-desktop"
dotnet publish "$DESKTOP_DIR/SmartRemotePrintDesktop.csproj" \
    -c Release \
    -r win-x64 \
    --self-contained true \
    -p:PublishSingleFile=true \
    -p:EnableCompressionInSingleFile=true \
    -p:IncludeNativeLibrariesForSelfExtract=true \
    -o "$DESKTOP_DIR/bin/publish"

echo "=== 3. Deploying to windows-installer/ ==="
INSTALLER_DIR="$ROOT_DIR/windows-installer"
mkdir -p "$INSTALLER_DIR"
cp "$AGENT_DIR/bin/publish/SmartRemotePrintAgent.exe" "$INSTALLER_DIR/SmartRemotePrintAgent.exe"
cp "$AGENT_DIR/bin/publish/SmartRemotePrintAgent.exe" "$INSTALLER_DIR/SmartRemotePrintAgent_Win10_Win11.exe"
cp "$DESKTOP_DIR/bin/publish/SmartRemotePrintDesktop.exe" "$INSTALLER_DIR/SmartRemotePrintDesktop.exe"

echo "=== Build Complete ==="
echo "Agent:   $INSTALLER_DIR/SmartRemotePrintAgent.exe"
echo "Desktop: $INSTALLER_DIR/SmartRemotePrintDesktop.exe"

