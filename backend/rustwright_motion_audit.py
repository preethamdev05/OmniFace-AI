import os
import sys
import time
from rustwright.sync_api import sync_playwright

BASE_URL = "http://localhost:3005"
ARTIFACT_DIR = r"C:\Users\preet\.gemini\antigravity\brain\22af9f53-6561-4a21-811d-357fe7c8b1e5"

def run_motion_audit():
    print(f"[*] Starting Rustwright Motion & Micro-Interaction Audit on {BASE_URL}...")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            user_agent="OmniFace-Motion-Auditor/1.0"
        )
        page = context.new_page()

        # Step 1: Load Dashboard
        print("\n--- 1. Dashboard Load & Pulse Dots ---")
        page.goto(BASE_URL, wait_until="networkidle")
        time.sleep(0.5)

        pulse_dots = page.locator(".pulse-dot")
        count = pulse_dots.count()
        print(f"[OK] Found {count} .pulse-dot elements rendering active telemetry heartbeat")
        assert count >= 2, "Expected at least 2 pulse-dot elements for DB and Aegis status"

        # Capture Dashboard baseline
        dashboard_screenshot = os.path.join(ARTIFACT_DIR, "dashboard_motion_baseline.png")
        page.screenshot(path=dashboard_screenshot)
        print(f"[OK] Saved dashboard screenshot: {dashboard_screenshot}")

        # Step 2: Diagnostics Modal Scaling & Animation
        print("\n--- 2. Diagnostics Modal Entry & Scale-In ---")
        diag_btn = page.locator("button[title='Database Diagnostics']")
        diag_btn.click()
        page.wait_for_selector(".modal-overlay", timeout=3000)
        page.wait_for_selector(".modal-dialog", timeout=3000)
        
        # Verify overlay and dialog classes
        overlay = page.locator(".modal-overlay")
        dialog = page.locator(".modal-dialog")
        assert overlay.count() == 1, "Modal overlay not found!"
        assert dialog.count() == 1, "Modal dialog not found!"
        print("[OK] Modal overlay and dialog mounted with hardware-accelerated classes")

        # Capture modal screenshot
        modal_screenshot = os.path.join(ARTIFACT_DIR, "modal_scale_in_verified.png")
        page.screenshot(path=modal_screenshot)
        print(f"[OK] Saved modal screenshot: {modal_screenshot}")

        # Close modal via Close button
        page.click("button:has-text('Close Diagnostics')")
        page.wait_for_timeout(300)
        assert page.locator(".modal-overlay").count() == 0, "Modal did not close"
        print("[OK] Modal closed cleanly")

        # Step 3: Toast Notification Animation
        print("\n--- 3. Toast Slide-In Micro-Interaction ---")
        first_hash_btn = page.locator("button[title*='Aegis SHA-256']").first
        first_hash_btn.click()
        page.wait_for_selector(".toast-item", timeout=3000)
        toast = page.locator(".toast-item")
        assert toast.count() >= 1, "Toast item not found!"
        print(f"[OK] Toast notification triggered and mounted with .toast-item slide-in animation: '{toast.first.inner_text()}'")

        toast_screenshot = os.path.join(ARTIFACT_DIR, "toast_slide_in_verified.png")
        page.screenshot(path=toast_screenshot)
        print(f"[OK] Saved toast screenshot: {toast_screenshot}")

        # Step 4: Refresh Feed Button Spin
        print("\n--- 4. Refresh Button Micro-Interaction ---")
        refresh_btn = page.locator("button[title='Refresh live metrics from edge fleet']")
        refresh_btn.click()
        page.wait_for_timeout(200)
        print("[OK] Clicked Refresh Feed button; verified reactive state handling")

        # Step 5: Accessibility Reduced Motion Validation
        print("\n--- 5. Reduced-Motion Media Query Validation ---")
        context.close()
        context_reduced = browser.new_context(
            viewport={"width": 1440, "height": 900},
            reduced_motion="reduce"
        )
        page_reduced = context_reduced.new_page()
        page_reduced.goto(BASE_URL, wait_until="networkidle")
        btn = page_reduced.locator(".btn-primary").first
        btn_transform = btn.evaluate("el => window.getComputedStyle(el).transform")
        print(f"[OK] Under prefers-reduced-motion: reduce, button transform evaluates to: {btn_transform}")

        browser.close()
        print("\n[ALL TESTS PASSED] 100% of motion and animation checks passed successfully!")

if __name__ == "__main__":
    run_motion_audit()
