using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace SmartRemotePrint.Desktop;

static class Program
{
    private static readonly HttpClient HttpClient = new() { Timeout = TimeSpan.FromSeconds(4) };

    [STAThread]
    static void Main(string[] args)
    {
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // Check if launched with a file argument (CLI or drag-and-drop onto exe)
        if (args.Length > 0 && File.Exists(args[0]))
        {
            var filePath = args[0];
            bool success = PrintFileDirectly(filePath);
            if (success)
            {
                MessageBox.Show($"✓ Successfully spooled to active printer:\n\n{Path.GetFileName(filePath)}",
                    "Smart Remote Print", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            else
            {
                MessageBox.Show($"Failed to print:\n\n{filePath}",
                    "Smart Remote Print - Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            return;
        }

        Application.Run(new MainForm());
    }

    public static string GetConfigFilePath()
    {
        string[] candidates = {
            @"C:\Program Files\SmartRemotePrint\printer_config.json",
            @"C:\SmartRemotePrint\printer_config.json",
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "printer_config.json")
        };

        foreach (var c in candidates)
        {
            if (File.Exists(c)) return c;
            var dir = Path.GetDirectoryName(c);
            if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir)) return c;
        }

        return Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "printer_config.json");
    }

    public static string GetActiveConfiguredPrinter()
    {
        try
        {
            var path = GetConfigFilePath();
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                using var doc = JsonDocument.Parse(json);
                if (doc.RootElement.TryGetProperty("activePrinter", out var p))
                {
                    var val = p.GetString();
                    if (!string.IsNullOrWhiteSpace(val)) return val;
                }
            }
        }
        catch { }
        return "SYSTEM_DEFAULT";
    }

    public static void SaveActiveConfiguredPrinter(string printerName, bool setAsWindowsDefault = false)
    {
        try
        {
            var path = GetConfigFilePath();
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }

            var dict = new Dictionary<string, object>
            {
                ["activePrinter"] = printerName,
                ["fallbackPrinter"] = "Brother DCP-T530DW Printer",
                ["fitToPage"] = true,
                ["copies"] = 1,
                ["lastChangedUtc"] = DateTime.UtcNow.ToString("o")
            };

            var json = JsonSerializer.Serialize(dict, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(path, json);

            var mirror = @"C:\SmartRemotePrint\printer_config.json";
            if (Directory.Exists(@"C:\SmartRemotePrint") && !string.Equals(path, mirror, StringComparison.OrdinalIgnoreCase))
            {
                try { File.WriteAllText(mirror, json); } catch { }
            }

            // Also notify background agent if running
            _ = Task.Run(async () =>
            {
                try
                {
                    var payload = JsonSerializer.Serialize(new { printerName = printerName, setAsWindowsDefault = setAsWindowsDefault });
                    var content = new StringContent(payload, Encoding.UTF8, "application/json");
                    await HttpClient.PostAsync("http://127.0.0.1:8766/api/v1/printers/active", content);
                }
                catch { }
            });

            if (setAsWindowsDefault && !string.Equals(printerName, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase))
            {
                SetWindowsDefaultPrinter(printerName);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Failed to save printer configuration: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    public static void SetWindowsDefaultPrinter(string printerName)
    {
        try
        {
            var clean = printerName.Replace("'", "''");
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -Command \"(New-Object -ComObject WScript.Network).SetDefaultPrinter('{clean}')\"",
                CreateNoWindow = true,
                UseShellExecute = false
            };
            using var proc = Process.Start(psi);
            proc?.WaitForExit(3000);
        }
        catch { }
    }

    public static List<string> GetInstalledWindowsPrinters(out string defaultPrinter)
    {
        var list = new List<string>();
        defaultPrinter = "";

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -Command \"Get-Printer | Select-Object Name,Default | ConvertTo-Json -Compress\"",
                RedirectStandardOutput = true,
                CreateNoWindow = true,
                UseShellExecute = false
            };
            using var proc = Process.Start(psi);
            if (proc != null)
            {
                var output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit(3000);

                if (!string.IsNullOrWhiteSpace(output))
                {
                    using var doc = JsonDocument.Parse(output);
                    var elements = doc.RootElement.ValueKind == JsonValueKind.Array
                        ? doc.RootElement.EnumerateArray().ToList()
                        : new List<JsonElement> { doc.RootElement };

                    foreach (var elem in elements)
                    {
                        var name = elem.GetProperty("Name").GetString();
                        var isDef = elem.GetProperty("Default").GetBoolean();
                        if (!string.IsNullOrEmpty(name))
                        {
                            list.Add(name);
                            if (isDef) defaultPrinter = name;
                        }
                    }
                }
            }
        }
        catch { }

        if (list.Count == 0)
        {
            // Fallback via System.Drawing.Printing
            foreach (string p in System.Drawing.Printing.PrinterSettings.InstalledPrinters)
            {
                list.Add(p);
            }
            try
            {
                var settings = new System.Drawing.Printing.PrinterSettings();
                defaultPrinter = settings.PrinterName;
            }
            catch { }
        }

        return list;
    }

    public static string ResolveActivePrinterName(string configured)
    {
        var installed = GetInstalledWindowsPrinters(out var def);
        if (string.Equals(configured, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(configured))
        {
            return !string.IsNullOrEmpty(def) ? def : (installed.FirstOrDefault() ?? "Default Printer");
        }

        var exact = installed.FirstOrDefault(p => string.Equals(p, configured, StringComparison.OrdinalIgnoreCase));
        if (exact != null) return exact;

        var partial = installed.FirstOrDefault(p => p.Contains(configured, StringComparison.OrdinalIgnoreCase));
        if (partial != null) return partial;

        return !string.IsNullOrEmpty(def) ? def : (installed.FirstOrDefault() ?? configured);
    }

    public static bool PrintFileDirectly(string filePath, string? targetPrinter = null, int copies = 1)
    {
        if (!File.Exists(filePath)) return false;

        var configured = GetActiveConfiguredPrinter();
        var printer = !string.IsNullOrWhiteSpace(targetPrinter) ? targetPrinter : ResolveActivePrinterName(configured);

        // Try agent API first if running on port 8766
        try
        {
            var reqObj = new
            {
                filePath = Path.GetFullPath(filePath),
                printerName = printer,
                documentName = Path.GetFileName(filePath),
                copies = copies
            };
            var content = new StringContent(JsonSerializer.Serialize(reqObj), Encoding.UTF8, "application/json");
            var resp = HttpClient.PostAsync("http://127.0.0.1:8766/api/v1/print/file", content).GetAwaiter().GetResult();
            if (resp.IsSuccessStatusCode) return true;
        }
        catch { }

        // Local fallback: SumatraPDF raster engine
        var sumatra = FindSumatra();
        var ext = Path.GetExtension(filePath).ToLowerInvariant();
        if (ext == ".pdf" && !string.IsNullOrEmpty(sumatra))
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = sumatra,
                    Arguments = $"-print-to \"{printer}\" -print-settings \"fit\" -silent \"{filePath}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };
                using var proc = Process.Start(psi);
                proc?.WaitForExit(10000);
                if (proc?.ExitCode == 0) return true;
            }
            catch { }
        }

        // Secondary fallback: PowerShell PrintTo or Out-Printer
        try
        {
            var cleanFile = filePath.Replace("'", "''");
            var cleanPrinter = printer.Replace("'", "''");

            string ps;
            if (ext == ".txt" || ext == ".csv" || ext == ".log")
            {
                ps = $"Get-Content -Path '{cleanFile}' | Out-Printer -Name '{cleanPrinter}'";
            }
            else
            {
                ps = $"Start-Process -FilePath '{cleanFile}' -Verb PrintTo -ArgumentList '\"{cleanPrinter}\"' -PassThru | ForEach-Object {{ Start-Sleep -Seconds 5; if (!$_.HasExited) {{ Stop-Process -Id $_.Id -Force }} }}";
            }

            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -Command \"{ps}\"",
                CreateNoWindow = true,
                UseShellExecute = false
            };
            using var proc = Process.Start(psi);
            proc?.WaitForExit(8000);
            return true;
        }
        catch { }

        return false;
    }

    private static string? FindSumatra()
    {
        string[] searchPaths = {
            @"C:\Program Files\SmartRemotePrint\SumatraPDF.exe",
            @"C:\SmartRemotePrint\windows-installer\SumatraPDF.exe",
            @"C:\Program Files\SumatraPDF\SumatraPDF.exe",
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "SumatraPDF.exe")
        };
        return searchPaths.FirstOrDefault(File.Exists);
    }
}

public class MainForm : Form
{
    private ComboBox cmbPrinters = null!;
    private CheckBox chkSetWindowsDefault = null!;
    private Button btnSavePrinter = null!;
    private Button btnRefreshPrinters = null!;
    private Label lblCurrentActive = null!;
    private Label lblServiceStatus = null!;

    private Panel dropZone = null!;
    private Label lblDropText = null!;
    private Label lblSelectedFile = null!;
    private Button btnBrowse = null!;
    private Button btnPrint = null!;
    private Button btnTestPrint = null!;
    private NumericUpDown numCopies = null!;

    private string? selectedFilePath;

    public MainForm()
    {
        InitializeUi();
        LoadPrintersAndConfig();
        CheckServiceStatusAsync();
    }

    private void InitializeUi()
    {
        Text = "Smart Remote Print - Desktop Quick Printer & Switcher";
        Width = 720;
        Height = 650;
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        BackColor = Color.FromArgb(248, 250, 252);
        Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);

        // Header Panel
        var header = new Panel
        {
            Dock = DockStyle.Top,
            Height = 75,
            BackColor = Color.FromArgb(15, 23, 42), // Deep Navy
            Padding = new Padding(20, 12, 20, 10)
        };

        var title = new Label
        {
            Text = "🖨️ Smart Remote Print - Desktop Manager",
            Font = new Font("Segoe UI", 13.5f, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Location = new Point(18, 12)
        };

        lblServiceStatus = new Label
        {
            Text = "Checking Agent Service (Port 8766)...",
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            ForeColor = Color.FromArgb(148, 163, 184),
            AutoSize = true,
            Location = new Point(22, 42)
        };

        header.Controls.Add(title);
        header.Controls.Add(lblServiceStatus);
        Controls.Add(header);

        // Main Container
        var mainPanel = new Panel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(20, 15, 20, 15),
            AutoScroll = true
        };

        // --- GROUP 1: PRINTER SELECTOR & SWITCHER ---
        var grpPrinter = new GroupBox
        {
            Text = "  1. Active Printer Selection (Switch Anytime)  ",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(30, 41, 59),
            Location = new Point(20, 90),
            Size = new Size(660, 140)
        };

        var lblSelect = new Label
        {
            Text = "Send Tablet & Desktop Print Jobs To:",
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            Location = new Point(15, 28),
            AutoSize = true
        };

        cmbPrinters = new ComboBox
        {
            Location = new Point(15, 50),
            Size = new Size(460, 28),
            DropDownStyle = ComboBoxStyle.DropDownList,
            Font = new Font("Segoe UI", 10f, FontStyle.Regular)
        };

        btnRefreshPrinters = new Button
        {
            Text = "🔄 Refresh",
            Location = new Point(485, 48),
            Size = new Size(160, 32),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(241, 245, 249),
            Cursor = Cursors.Hand
        };
        btnRefreshPrinters.Click += (s, e) => LoadPrintersAndConfig();

        chkSetWindowsDefault = new CheckBox
        {
            Text = "Also set as Windows System Default Printer",
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            Location = new Point(15, 85),
            AutoSize = true
        };

        btnSavePrinter = new Button
        {
            Text = "✓ Apply Active Printer",
            Location = new Point(485, 85),
            Size = new Size(160, 32),
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            BackColor = Color.FromArgb(16, 185, 129), // Emerald
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand
        };
        btnSavePrinter.FlatAppearance.BorderSize = 0;
        btnSavePrinter.Click += BtnSavePrinter_Click;

        lblCurrentActive = new Label
        {
            Text = "Active: Resolving...",
            Font = new Font("Segoe UI", 8.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(5, 150, 105),
            Location = new Point(15, 112),
            AutoSize = true
        };

        grpPrinter.Controls.Add(lblSelect);
        grpPrinter.Controls.Add(cmbPrinters);
        grpPrinter.Controls.Add(btnRefreshPrinters);
        grpPrinter.Controls.Add(chkSetWindowsDefault);
        grpPrinter.Controls.Add(btnSavePrinter);
        grpPrinter.Controls.Add(lblCurrentActive);
        Controls.Add(grpPrinter);

        // --- GROUP 2: PRINT ANYTHING DROP ZONE ---
        var grpDrop = new GroupBox
        {
            Text = "  2. Print Anything (PDF, TXT, CSV, Image, Document)  ",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(30, 41, 59),
            Location = new Point(20, 245),
            Size = new Size(660, 255)
        };

        dropZone = new Panel
        {
            Location = new Point(15, 30),
            Size = new Size(630, 95),
            BackColor = Color.FromArgb(241, 245, 249),
            BorderStyle = BorderStyle.FixedSingle,
            AllowDrop = true
        };
        dropZone.DragEnter += DropZone_DragEnter;
        dropZone.DragDrop += DropZone_DragDrop;

        lblDropText = new Label
        {
            Text = "📥 DRAG & DROP ANY FILE HERE TO PRINT\n(Or click Browse below)",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 10.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(100, 116, 139)
        };
        dropZone.Controls.Add(lblDropText);

        lblSelectedFile = new Label
        {
            Text = "No file selected.",
            Font = new Font("Segoe UI", 9f, FontStyle.Italic),
            ForeColor = Color.FromArgb(71, 85, 105),
            Location = new Point(15, 133),
            Size = new Size(630, 20),
            AutoEllipsis = true
        };

        btnBrowse = new Button
        {
            Text = "📂 Select File...",
            Location = new Point(15, 160),
            Size = new Size(135, 36),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(226, 232, 240),
            Cursor = Cursors.Hand
        };
        btnBrowse.Click += BtnBrowse_Click;

        var lblCopies = new Label
        {
            Text = "Copies:",
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            Location = new Point(165, 170),
            AutoSize = true
        };

        numCopies = new NumericUpDown
        {
            Location = new Point(220, 167),
            Size = new Size(55, 26),
            Minimum = 1,
            Maximum = 99,
            Value = 1
        };

        btnTestPrint = new Button
        {
            Text = "📄 Print Test Page",
            Location = new Point(290, 160),
            Size = new Size(140, 36),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(241, 245, 249),
            Cursor = Cursors.Hand
        };
        btnTestPrint.Click += BtnTestPrint_Click;

        btnPrint = new Button
        {
            Text = "🖨️ PRINT TO ACTIVE PRINTER",
            Location = new Point(440, 160),
            Size = new Size(205, 36),
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            BackColor = Color.FromArgb(37, 99, 235), // Royal Blue
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand
        };
        btnPrint.FlatAppearance.BorderSize = 0;
        btnPrint.Click += BtnPrint_Click;

        var lblHint = new Label
        {
            Text = "Tip: You can also drag files directly onto the Desktop icon to print instantly!",
            Font = new Font("Segoe UI", 8.5f, FontStyle.Regular),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(15, 215),
            AutoSize = true
        };

        grpDrop.Controls.Add(dropZone);
        grpDrop.Controls.Add(lblSelectedFile);
        grpDrop.Controls.Add(btnBrowse);
        grpDrop.Controls.Add(lblCopies);
        grpDrop.Controls.Add(numCopies);
        grpDrop.Controls.Add(btnTestPrint);
        grpDrop.Controls.Add(btnPrint);
        grpDrop.Controls.Add(lblHint);
        Controls.Add(grpDrop);

        // --- GROUP 3: SYSTEM UTILITIES ---
        var grpUtils = new GroupBox
        {
            Text = "  3. Spooler & Marg ERP Tools  ",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(30, 41, 59),
            Location = new Point(20, 510),
            Size = new Size(660, 80)
        };

        var btnClearQueue = new Button
        {
            Text = "🧹 Clear / Unpause Queue",
            Location = new Point(15, 28),
            Size = new Size(195, 34),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(241, 245, 249),
            Cursor = Cursors.Hand
        };
        btnClearQueue.Click += (s, e) =>
        {
            var active = Program.ResolveActivePrinterName(Program.GetActiveConfiguredPrinter());
            var clean = active.Replace("'", "''");
            var ps = $"Get-PrintJob -PrinterName '{clean}' -ErrorAction SilentlyContinue | Where-Object {{ $_.JobStatus -match 'Error|Paused' }} | Remove-PrintJob -Confirm:$false; Resume-PrintJob -PrinterName '{clean}' -ErrorAction SilentlyContinue";
            Process.Start(new ProcessStartInfo("powershell.exe", $"-NoProfile -Command \"{ps}\"") { CreateNoWindow = true, UseShellExecute = false });
            MessageBox.Show($"Queue refreshed and unpaused for '{active}'.", "Smart Remote Print", MessageBoxButtons.OK, MessageBoxIcon.Information);
        };

        var btnOpenFolder = new Button
        {
            Text = "📁 Marg Sync Folder",
            Location = new Point(225, 28),
            Size = new Size(195, 34),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(241, 245, 249),
            Cursor = Cursors.Hand
        };
        btnOpenFolder.Click += (s, e) =>
        {
            var dir = @"C:\SmartRemotePrint\MargSync";
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
            Process.Start("explorer.exe", dir);
        };

        var btnOpenAnalysis = new Button
        {
            Text = "📊 View CSV Analysis",
            Location = new Point(435, 28),
            Size = new Size(210, 34),
            Font = new Font("Segoe UI", 9f, FontStyle.Regular),
            BackColor = Color.FromArgb(241, 245, 249),
            Cursor = Cursors.Hand
        };
        btnOpenAnalysis.Click += (s, e) =>
        {
            Process.Start(new ProcessStartInfo("http://127.0.0.1:8766/api/v1/sync/analysis") { UseShellExecute = true });
        };

        grpUtils.Controls.Add(btnClearQueue);
        grpUtils.Controls.Add(btnOpenFolder);
        grpUtils.Controls.Add(btnOpenAnalysis);
        Controls.Add(grpUtils);
    }

    private void LoadPrintersAndConfig()
    {
        cmbPrinters.Items.Clear();
        cmbPrinters.Items.Add("⭐ [Follow Windows Default Printer (Auto)]");

        var printers = Program.GetInstalledWindowsPrinters(out var defPrinter);
        var configured = Program.GetActiveConfiguredPrinter();

        int selectedIndex = 0;
        for (int i = 0; i < printers.Count; i++)
        {
            var p = printers[i];
            var display = string.Equals(p, defPrinter, StringComparison.OrdinalIgnoreCase)
                ? $"{p}  (Default in Windows)"
                : p;

            cmbPrinters.Items.Add(display);

            if (!string.Equals(configured, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase) &&
                string.Equals(p, configured, StringComparison.OrdinalIgnoreCase))
            {
                selectedIndex = i + 1; // +1 for auto item
            }
        }

        cmbPrinters.SelectedIndex = selectedIndex;

        var resolved = Program.ResolveActivePrinterName(configured);
        lblCurrentActive.Text = $"Active Printer: {resolved}  {(string.Equals(configured, "SYSTEM_DEFAULT", StringComparison.OrdinalIgnoreCase) ? "[Auto Windows Default]" : "[Custom Override]")}";
    }

    private async void CheckServiceStatusAsync()
    {
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
            var resp = await client.GetAsync("http://127.0.0.1:8766/api/v1/health");
            if (resp.IsSuccessStatusCode)
            {
                lblServiceStatus.Text = "🟢 Windows Service: ONLINE on Port 8766 (Connected to Tablet)";
                lblServiceStatus.ForeColor = Color.FromArgb(74, 222, 128); // Green
                return;
            }
        }
        catch { }

        lblServiceStatus.Text = "🟡 Windows Service: Standalone / Offline Mode (Direct printing active)";
        lblServiceStatus.ForeColor = Color.FromArgb(251, 191, 36); // Amber
    }

    private void BtnSavePrinter_Click(object? sender, EventArgs e)
    {
        string target;
        if (cmbPrinters.SelectedIndex <= 0)
        {
            target = "SYSTEM_DEFAULT";
        }
        else
        {
            var raw = cmbPrinters.SelectedItem?.ToString() ?? "";
            target = raw.Replace("  (Default in Windows)", "").Trim();
        }

        bool setDef = chkSetWindowsDefault.Checked;
        Program.SaveActiveConfiguredPrinter(target, setDef);

        var resolved = Program.ResolveActivePrinterName(target);
        lblCurrentActive.Text = $"Active Printer: {resolved}  {(target == "SYSTEM_DEFAULT" ? "[Auto Windows Default]" : "[Custom Override]")}";

        MessageBox.Show($"✓ Active Printer updated to:\n\n{resolved}\n\nBoth Tablet (Marg Cloud) and Desktop print jobs will now print to this printer.",
            "Printer Configured", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void DropZone_DragEnter(object? sender, DragEventArgs e)
    {
        if (e.Data != null && e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            e.Effect = DragDropEffects.Copy;
            dropZone.BackColor = Color.FromArgb(224, 231, 255); // Indigo highlight
        }
    }

    private void DropZone_DragDrop(object? sender, DragEventArgs e)
    {
        dropZone.BackColor = Color.FromArgb(241, 245, 249);
        if (e.Data != null && e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = (string[])e.Data.GetData(DataFormats.FileDrop);
            if (files != null && files.Length > 0)
            {
                SetSelectedFile(files[0]);
            }
        }
    }

    private void BtnBrowse_Click(object? sender, EventArgs e)
    {
        using var ofd = new OpenFileDialog
        {
            Title = "Select Any Document or File to Print",
            Filter = "All Supported Files (*.pdf;*.txt;*.csv;*.png;*.jpg)|*.pdf;*.txt;*.csv;*.png;*.jpg;*.jpeg;*.bmp|PDF Documents (*.pdf)|*.pdf|Text/CSV Files (*.txt;*.csv)|*.txt;*.csv|All Files (*.*)|*.*"
        };
        if (ofd.ShowDialog() == DialogResult.OK)
        {
            SetSelectedFile(ofd.FileName);
        }
    }

    private void SetSelectedFile(string path)
    {
        selectedFilePath = path;
        try
        {
            var fi = new FileInfo(path);
            var sizeKb = Math.Max(1, fi.Length / 1024);
            lblSelectedFile.Text = $"📄 Selected: {fi.Name} ({sizeKb} KB) - {fi.DirectoryName}";
            lblSelectedFile.ForeColor = Color.FromArgb(15, 23, 42);
        }
        catch
        {
            lblSelectedFile.Text = $"📄 Selected: {path}";
        }
    }

    private void BtnPrint_Click(object? sender, EventArgs e)
    {
        if (string.IsNullOrEmpty(selectedFilePath) || !File.Exists(selectedFilePath))
        {
            MessageBox.Show("Please select or drag-and-drop a file first.", "No File Selected", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        var configured = Program.GetActiveConfiguredPrinter();
        var printer = Program.ResolveActivePrinterName(configured);
        int copies = (int)numCopies.Value;

        btnPrint.Enabled = false;
        btnPrint.Text = "⏳ Spooling...";

        Task.Run(() =>
        {
            bool success = Program.PrintFileDirectly(selectedFilePath, printer, copies);

            Invoke((Action)(() =>
            {
                btnPrint.Enabled = true;
                btnPrint.Text = "🖨️ PRINT TO ACTIVE PRINTER";

                if (success)
                {
                    MessageBox.Show($"✓ Print job successfully sent to '{printer}'!\n\nDocument: {Path.GetFileName(selectedFilePath)}\nCopies: {copies}",
                        "Print Success", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                else
                {
                    MessageBox.Show($"Failed to print to '{printer}'. Check if printer is turned on and ready.",
                        "Print Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }));
        });
    }

    private void BtnTestPrint_Click(object? sender, EventArgs e)
    {
        var configured = Program.GetActiveConfiguredPrinter();
        var printer = Program.ResolveActivePrinterName(configured);

        var tempTxt = Path.Combine(Path.GetTempPath(), $"TestPrint_{DateTime.Now:yyyyMMdd_HHmmss}.txt");
        var sb = new StringBuilder();
        sb.AppendLine("==================================================================");
        sb.AppendLine(" SMART REMOTE PRINT - TEST PRINT RECEIPT");
        sb.AppendLine("==================================================================");
        sb.AppendLine($"Printer:    {printer}");
        sb.AppendLine($"Timestamp:  {DateTime.Now:dd/MM/yyyy HH:mm:ss}");
        sb.AppendLine($"PC Name:    {Environment.MachineName}");
        sb.AppendLine($"Port:       8766 (Smart Remote Print Agent Service)");
        sb.AppendLine("Status:     SUCCESS - Printer is Online & Spooling Correctly");
        sb.AppendLine("Device:     Ready for Lenovo Tab P12 Marg Cloud Direct Print");
        sb.AppendLine("==================================================================");
        File.WriteAllText(tempTxt, sb.ToString());

        bool ok = Program.PrintFileDirectly(tempTxt, printer, 1);
        if (ok)
        {
            MessageBox.Show($"✓ Test page successfully sent to:\n\n{printer}", "Test Print", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        else
        {
            MessageBox.Show($"Could not send test page to '{printer}'.", "Test Print Error", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
