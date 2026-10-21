#!/usr/bin/env bash
set -e
echo "Building Smart Remote Print Windows Agent (win-x64)..."
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../windows-agent" && pwd)"
dotnet publish "$PROJECT_DIR/SmartRemotePrintAgent.csproj" \
    -c Release \
    -r win-x64 \
    --self-contained true \
    -p:PublishSingleFile=true \
    -p:IncludeNativeLibrariesForSelfExtract=true \
    -o "$PROJECT_DIR/bin/publish"
echo "Windows executable successfully built at: $PROJECT_DIR/bin/publish/SmartRemotePrintAgent.exe"
