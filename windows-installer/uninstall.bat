@echo off
setlocal
cd /d "%~dp0"
title Smart Remote Print Uninstaller

echo ===================================================================
echo  SMART REMOTE PRINT - UNINSTALLER
echo ===================================================================
echo.

:: Check for Administrator privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [INFO] Administrator privileges required.
    echo [INFO] Requesting UAC elevation...
    powershell.exe -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"%~dp0uninstall.ps1\"'"
    exit /b
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
if %errorLevel% neq 0 (
    echo.
    echo [Notice] Uninstallation finished with status code %errorLevel%.
    pause
)
