@echo off
setlocal
cd /d "%~dp0"
title Smart Remote Print 24/7 Service Installer

echo ===================================================================
echo  SMART REMOTE PRINT - WINDOWS SERVICE INSTALLER (Win 10 / Win 11)
echo ===================================================================
echo.

:: Check for Administrator privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [INFO] Administrator privileges required.
    echo [INFO] Requesting UAC elevation...
    powershell.exe -NoProfile -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"%~dp0install.ps1\"'"
    exit /b
)

:: If already running as Administrator, execute directly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
if %errorLevel% neq 0 (
    echo.
    echo [Notice] Installation finished with status code %errorLevel%.
    pause
)
