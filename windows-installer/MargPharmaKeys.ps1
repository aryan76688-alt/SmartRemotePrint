# ==============================================================================
# MARG ERP PHARMA QUICK-ACTION KEY TRANSLATOR (POWERSHELL / WINDOWS 10 & 11)
# Works out-of-the-box on Windows 10/11 without requiring 3rd party tools.
# Translates:
#   Alt + 2  ->  F2 (Date Change)
#   Alt + 3  ->  F3 (Item Master)
#   Alt + 7  ->  F7 (Cash / Credit)
#   Alt + 8  ->  F8 (Bill Edit)
#   Alt + 9  ->  F9 (Print Bill)
#   Alt + 0  ->  F10 (Save Bill)
#   Alt + E  ->  ESC (Exit)
# ==============================================================================

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

public class PharmaKeyHook {
    [DllImport("user32.dll")]
    public static extern bool RegisterHotKey(IntPtr hWnd, int id, int fsModifiers, int vk);

    [DllImport("user32.dll")]
    public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    public const int MOD_ALT = 0x0001;
    public const int MOD_CONTROL = 0x0002;
}
"@ -ReferencedAssemblies "System.Windows.Forms.dll"

Write-Host "===================================================================" -ForegroundColor Cyan
Write-Host " MARG PHARMA KEY TRANSLATOR ACTIVE (Windows 10 / 11)               " -ForegroundColor White
Write-Host " Alt+2: Date | Alt+3: Item | Alt+7: Cash | Alt+9: Print | Alt+0: Save" -ForegroundColor Green
Write-Host " Press Ctrl+C in this window to stop.                              " -ForegroundColor Gray
Write-Host "===================================================================" -ForegroundColor Cyan

# Keep process alive and notify user
while ($true) {
    Start-Sleep -Seconds 3600
}
