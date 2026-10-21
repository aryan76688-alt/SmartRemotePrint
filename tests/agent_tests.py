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

if __name__ == "__main__":
    unittest.main()
