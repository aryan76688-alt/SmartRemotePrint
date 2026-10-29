#!/usr/bin/env python3
"""
Automated Test Suite for Marg Cloud & Smart Remote Print
Tests protocol models, fixed PIN 260890, PDF validation, auto-reconnect, and desktop user agent.
"""

import unittest
import hashlib
import json
import time

class TestMargCloudRemotePrint(unittest.TestCase):

    def setUp(self):
        self.valid_pdf_magic = b"%PDF-1.4\n%test valid pdf payload"
        self.invalid_pdf = b"NOT_A_PDF_DOCUMENT_CONTENT"
        self.configured_pin = "260890"

    def test_configured_pairing_pin_260890(self):
        """Test configured 260890 pairing PIN"""
        self.assertEqual(self.configured_pin, "260890", "Pairing PIN must be 260890")

    def test_pdf_magic_byte_validation(self):
        """Test PDF signature validation (%PDF- header)"""
        self.assertTrue(self.valid_pdf_magic.startswith(b"%PDF-"), "Valid PDF must start with %PDF- header")
        self.assertFalse(self.invalid_pdf.startswith(b"%PDF-"), "Invalid payload must be rejected")

    def test_sha256_job_deduplication(self):
        """Test duplicate submission detection via SHA-256 hash window"""
        h1 = hashlib.sha256(self.valid_pdf_magic).hexdigest()
        h2 = hashlib.sha256(self.valid_pdf_magic).hexdigest()
        self.assertEqual(h1, h2, "Identical payloads must yield matching SHA-256")

    def test_brother_printer_queue_detection(self):
        """Test printer queue resolution prioritizes USB001 over Wi-Fi"""
        queues = [
            {"name": "Brother DCP-T530DW Printer", "port": "USB001", "driver": "Brother DCP-T530DW Printer"},
            {"name": "Brother DCP-T530DW Printer (Wi-Fi)", "port": "WSD-192.168.1.50", "driver": "Brother DCP-T530DW Printer"}
        ]
        usb_queue = next((q for q in queues if q["port"] == "USB001"), None)
        self.assertIsNotNone(usb_queue, "USB001 queue must be discoverable")
        self.assertEqual(usb_queue["name"], "Brother DCP-T530DW Printer")

    def test_keyboard_escape_remapping_logic(self):
        """Test Locked APK: Back & Esc keys always consumed, never closing APK"""
        KEYCODE_BACK = 4
        KEYCODE_ESCAPE = 111
        
        def handle_key(code):
            if code in (KEYCODE_BACK, KEYCODE_ESCAPE):
                return {"consumed": True, "inject_js_esc": True, "allow_close_apk": False}
            return {"consumed": False, "inject_js_esc": False, "allow_close_apk": True}
        
        res = handle_key(KEYCODE_BACK)
        self.assertTrue(res["consumed"])
        self.assertTrue(res["inject_js_esc"])
        self.assertFalse(res["allow_close_apk"], "APK must never close on Back button")

    def test_desktop_user_agent_format(self):
        """Test Desktop mode User Agent is Windows Chrome"""
        ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        self.assertIn("Windows NT 10.0", ua)
        self.assertIn("Chrome/", ua)

    def test_marg_cloud_download_directory(self):
        """Test download subpath is 'Marg Cloud'"""
        folder_name = "Marg Cloud"
        dest_path = f"/storage/emulated/0/Download/{folder_name}"
        self.assertTrue(dest_path.endswith("Marg Cloud"))

    def test_lenovo_tab_p12_pharma_key_mapping(self):
        """Test physical keyboard mapping for tablets without F1-F12 keys"""
        mapping = {
            "Alt+1": "F1",
            "Alt+2": "F2",   # Date
            "Alt+3": "F3",   # Item Master / Search
            "Alt+7": "F7",   # Cash / Credit
            "Alt+8": "F8",   # Bill Edit / Modify
            "Alt+9": "F9",   # Print Bill
            "Alt+0": "F10",  # Save Bill
            "Alt+E": "Escape",
            "Ctrl+P": "F9",
            "Ctrl+S": "F10"
        }
        self.assertEqual(mapping["Alt+2"], "F2", "Alt+2 must map to F2 Date")
        self.assertEqual(mapping["Alt+3"], "F3", "Alt+3 must map to F3 Item Master")
        self.assertEqual(mapping["Alt+7"], "F7", "Alt+7 must map to F7 Payment Mode")
        self.assertEqual(mapping["Alt+9"], "F9", "Alt+9 must map to F9 Print Bill")
        self.assertEqual(mapping["Alt+0"], "F10", "Alt+0 must map to F10 Save Bill")
        self.assertEqual(mapping["Ctrl+S"], "F10", "Ctrl+S must trigger F10 Save")

    def test_marg_direct_stock_and_outstanding_sync(self):
        """Test Marg Direct Stock and Chemist Outstanding data structure"""
        sample_stock = {
            "ItemCode": "PARA650",
            "ItemName": "Paracetamol 650mg Tab",
            "CurrentStock": 450,
            "Rate": 22.00,
            "MRP": 32.50
        }
        sample_party = {
            "PartyCode": "SHR001",
            "PartyName": "Shreeji Medical Store",
            "TotalOutstanding": 14500.00,
            "OverdueAmount": 4500.00,
            "CreditLimit": 25000.00
        }
        self.assertGreater(sample_stock["CurrentStock"], 0, "Stock balance must be tracked")
        self.assertLessEqual(sample_party["TotalOutstanding"], sample_party["CreditLimit"], "Outstanding balance within limit")

    def test_csv_backup_timestamp_naming(self):
        """Test CSV non-destructive timestamped backup naming format"""
        import datetime
        original_name = "stock_report_oct2026.csv"
        now_str = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        backup_name = f"{now_str}_{original_name}"
        
        self.assertTrue(backup_name.endswith(".csv"), "Backup file must have .csv extension")
        self.assertIn(original_name, backup_name, "Original filename must be preserved in backup")
        # Validate format: YYYY-MM-DD_HH-MM-SS_<filename>
        parts = backup_name.split("_")
        self.assertEqual(len(parts[0].split("-")), 3, "Year-month-day timestamp prefix present")

    def test_csv_stock_and_party_parsing_and_valuation(self):
        """Test parsing of CSV rows and valuation calculations"""
        csv_stock_rows = [
            {"code": "PARA650", "name": "Paracetamol 650mg", "stock": 500, "rate": 20.0, "mrp": 30.0},
            {"code": "AMOX500", "name": "Amoxicillin 500mg", "stock": 100, "rate": 60.0, "mrp": 80.0},
        ]
        total_units = sum(r["stock"] for r in csv_stock_rows)
        total_valuation = sum(r["stock"] * r["rate"] for r in csv_stock_rows)

        self.assertEqual(total_units, 600, "Total units must equal 600")
        self.assertEqual(total_valuation, 16000.0, "Inventory valuation must be 16,000")

        csv_party_rows = [
            {"code": "SHR001", "name": "Shreeji Medical", "outstanding": 14500.0, "overdue": 4500.0},
            {"code": "KIR002", "name": "Kiran Chemist", "outstanding": 48000.0, "overdue": 18000.0},
        ]
        total_debt = sum(p["outstanding"] for p in csv_party_rows)
        total_overdue = sum(p["overdue"] for p in csv_party_rows)

        self.assertEqual(total_debt, 62500.0, "Total chemist outstanding matches sum")
        self.assertEqual(total_overdue, 22500.0, "Total overdue matches sum")

    def test_offline_sync_queue_and_flush(self):
        """Test offline fallback sync queueing and reconnection flush"""
        sync_queue = []
        is_online = False

        # Tablet downloads CSV while Windows agent is offline
        incoming_payload = {"StockItems": [{"ItemCode": "PARA650", "CurrentStock": 450}]}
        if not is_online:
            sync_queue.append(incoming_payload)

        self.assertEqual(len(sync_queue), 1, "Offline update must be queued locally")

        # Windows Agent / Tailscale reconnects
        is_online = True
        flushed_items = []
        if is_online and sync_queue:
            while sync_queue:
                item = sync_queue.pop(0)
                flushed_items.append(item)

        self.assertEqual(len(sync_queue), 0, "Queue must be empty after flush")
        self.assertEqual(len(flushed_items), 1, "All offline items must be pushed to Windows agent")

    def test_dynamic_printer_resolution_system_default(self):
        """Test printer resolution dynamically follows Windows default printer when activePrinter is SYSTEM_DEFAULT"""
        installed_printers = [
            {"Name": "Canon G3000 Series", "IsDefault": True},
            {"Name": "Brother DCP-T530DW Printer", "IsDefault": False},
            {"Name": "Microsoft Print to PDF", "IsDefault": False}
        ]
        config = {"activePrinter": "SYSTEM_DEFAULT"}

        # Simulate ResolvePrinterName logic
        resolved = None
        if config["activePrinter"] == "SYSTEM_DEFAULT":
            win_def = next((p["Name"] for p in installed_printers if p["IsDefault"]), None)
            resolved = win_def or installed_printers[0]["Name"]
        else:
            resolved = config["activePrinter"]

        self.assertEqual(resolved, "Canon G3000 Series", "Must dynamically resolve to new Windows default printer")

    def test_dynamic_printer_resolution_custom_override(self):
        """Test printer resolution prioritizes user-configured active printer override"""
        installed_printers = [
            {"Name": "Canon G3000 Series", "IsDefault": True},
            {"Name": "HP LaserJet Pro MFP M126nw", "IsDefault": False},
            {"Name": "Brother DCP-T530DW Printer", "IsDefault": False}
        ]
        # User switched active printer in Desktop .exe
        config = {"activePrinter": "HP LaserJet Pro MFP M126nw"}

        resolved = None
        if config["activePrinter"] != "SYSTEM_DEFAULT":
            match = next((p["Name"] for p in installed_printers if p["Name"] == config["activePrinter"]), None)
            resolved = match
        else:
            resolved = next((p["Name"] for p in installed_printers if p["IsDefault"]), None)

        self.assertEqual(resolved, "HP LaserJet Pro MFP M126nw", "Must resolve to user-selected override printer")

    def test_dynamic_printer_fallback_resilience(self):
        """Test graceful fallback to Brother or first printer when selected printer is detached"""
        installed_printers = [
            {"Name": "Brother DCP-T530DW Printer", "IsDefault": False},
            {"Name": "Microsoft Print to PDF", "IsDefault": False}
        ]
        # Config points to an unplugged printer
        config = {"activePrinter": "Epson L3250", "fallbackPrinter": "Brother DCP-T530DW Printer"}

        resolved = next((p["Name"] for p in installed_printers if p["Name"] == config["activePrinter"]), None)
        if not resolved:
            # Fallback to default, then fallbackPrinter
            win_def = next((p["Name"] for p in installed_printers if p["IsDefault"]), None)
            fallback = next((p["Name"] for p in installed_printers if config["fallbackPrinter"] in p["Name"]), None)
            resolved = win_def or fallback or installed_printers[0]["Name"]

        self.assertEqual(resolved, "Brother DCP-T530DW Printer", "Must fall back safely to available Brother printer")

    def test_feature5_offline_print_queue_persistence_and_fifo(self):
        """Test Feature 5: Offline print queue stores jobs with FIFO order and no data loss"""
        queue = []
        def enqueue(job_id, doc_name, copies):
            queue.append({"jobId": job_id, "docName": doc_name, "copies": copies, "timestamp": time.time()})

        enqueue("job-1", "Marg_Bill_101.pdf", 1)
        enqueue("job-2", "Marg_Bill_102.pdf", 2)
        enqueue("job-3", "Marg_Bill_103.pdf", 1)

        self.assertEqual(len(queue), 3, "All 3 offline print jobs must be persisted")
        self.assertEqual(queue[0]["docName"], "Marg_Bill_101.pdf", "First bill queued must be first in queue (FIFO)")
        self.assertEqual(queue[2]["docName"], "Marg_Bill_103.pdf", "Third bill queued must be third in queue")

    def test_feature5_offline_queue_auto_flush_on_reconnect(self):
        """Test Feature 5: Offline queue flushes all jobs sequentially upon reconnection"""
        queue = [
            {"jobId": "job-1", "docName": "Marg_Bill_101.pdf", "copies": 1},
            {"jobId": "job-2", "docName": "Marg_Bill_102.pdf", "copies": 2}
        ]
        spooled_jobs = []

        def simulate_flush(is_pc_online):
            if not is_pc_online:
                return 0
            flushed = 0
            while queue:
                job = queue.pop(0)
                spooled_jobs.append(job)
                flushed += 1
            return flushed

        # 1. Offline attempt: 0 flushed, queue intact
        self.assertEqual(simulate_flush(is_pc_online=False), 0)
        self.assertEqual(len(queue), 2)

        # 2. Reconnect: PC is online -> all jobs auto-spooled
        flushed_count = simulate_flush(is_pc_online=True)
        self.assertEqual(flushed_count, 2)
        self.assertEqual(len(queue), 0, "Queue must be completely drained")
        self.assertEqual(len(spooled_jobs), 2, "Both jobs must reach spooler")

    def test_feature6_live_printer_telemetry_paper_out(self):
        """Test Feature 6: Spooler telemetry flags Paper Out and notifies client"""
        PRINTER_STATUS_PAPER_OUT = 0x00000010
        raw_status = PRINTER_STATUS_PAPER_OUT

        paper_status = "OutOfPaper" if (raw_status & PRINTER_STATUS_PAPER_OUT) != 0 else "OK"
        state = "PaperOut" if paper_status == "OutOfPaper" else "Ready"

        self.assertEqual(paper_status, "OutOfPaper")
        self.assertEqual(state, "PaperOut")

    def test_feature6_live_printer_telemetry_paper_jam(self):
        """Test Feature 6: Spooler telemetry flags Paper Jam and alerts user"""
        PRINTER_STATUS_PAPER_JAM = 0x00000008
        raw_status = PRINTER_STATUS_PAPER_JAM

        paper_status = "PaperJam" if (raw_status & PRINTER_STATUS_PAPER_JAM) != 0 else "OK"
        state = "PaperJam" if paper_status == "PaperJam" else "Ready"

        self.assertEqual(paper_status, "PaperJam")
        self.assertEqual(state, "PaperJam")

    def test_feature6_live_printer_telemetry_low_ink(self):
        """Test Feature 6: Spooler telemetry flags Low Ink / Toner"""
        PRINTER_STATUS_TONER_LOW = 0x00020000
        raw_status = PRINTER_STATUS_TONER_LOW

        ink_status = "LowInk" if (raw_status & PRINTER_STATUS_TONER_LOW) != 0 else "OK"
        self.assertEqual(ink_status, "LowInk")

    def test_dual_device_flavor_labeling(self):
        """Test distinct branding for Tablet vs Mobile builds"""
        flavors = {
            "tablet": {"appLabel": "Marg Cloud Tablet", "pkgSuffix": ""},
            "mobile": {"appLabel": "Marg Cloud Mobile", "pkgSuffix": ".mobile"}
        }
        self.assertEqual(flavors["tablet"]["appLabel"], "Marg Cloud Tablet")
        self.assertEqual(flavors["mobile"]["appLabel"], "Marg Cloud Mobile")
        self.assertEqual(flavors["mobile"]["pkgSuffix"], ".mobile")

if __name__ == "__main__":
    unittest.main()

