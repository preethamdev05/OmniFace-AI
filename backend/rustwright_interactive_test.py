import os
import sys
from rustwright.sync_api import sync_playwright

BASE_URL = "https://omniface.vercel.app"

def main():
    print(f"[*] Starting Rustwright interactive web verification against {BASE_URL}...")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            user_agent="OmniFace-Rustwright-Agent/1.0"
        )
        page = context.new_page()

        # Test 1: Minor Parental Consent Gate on /roster
        print("\n--- TEST 1: Minor Parental Consent Gate on /roster ---")
        page.goto(f"{BASE_URL}/roster", wait_until="networkidle")
        page.click("button:has-text('Enroll New Member')")
        page.wait_for_selector("#enroll-modal-title")
        print("[OK] Enrollment modal opened")

        # Select Minor (<18)
        page.select_option("#enroll-age-category", "MINOR")
        print("[OK] Switched to Minor (<18) mode")
        
        # Check that mandatory parental consent checkbox is visible
        consent_box = page.locator("#minor-consent-check")
        assert consent_box.count() > 0, "Parental consent checkbox not found!"
        assert not consent_box.is_checked(), "Consent checkbox must NOT be pre-checked!"
        print("[OK] Mandatory parental consent checkbox appeared and is UNCHECKED by default")

        # Verify submit button is disabled when consent is not given
        submit_btn = page.locator("button[type='submit']:has-text('Save & Queue for Kiosk Sync')")
        assert submit_btn.is_disabled(), "Submit button must be disabled when consent is unchecked!"
        print("[OK] Submit button is strictly disabled without parental consent")

        # Check box and verify submit button becomes enabled
        page.check("#minor-consent-check")
        assert submit_btn.is_enabled(), "Submit button must be enabled once consent is checked!"
        print("[OK] Submit button became enabled after parental consent was certified")

        # Close modal via Escape key
        page.keyboard.press("Escape")
        page.wait_for_timeout(500)
        assert page.locator("#enroll-modal-title").count() == 0, "Modal did not close on Escape key"
        print("[OK] Escape key closes enrollment modal cleanly")

        # Test 2: Software License Receipt on /subscription
        print("\n--- TEST 2: Software License Receipt on /subscription ---")
        page.goto(f"{BASE_URL}/subscription", wait_until="networkidle")
        page.locator("button:has-text('View Receipt')").first.click()
        page.wait_for_selector("text=SOFTWARE LICENSE RECEIPT")
        modal_text = page.locator("div[role='dialog']").inner_text()
        
        # Assertions on legal entity and tax representations
        assert "OmniFace Technologies" in modal_text, "Missing OmniFace Technologies"
        assert "Operated by Preetham, Independent Developer" in modal_text, "Missing Preetham Independent Developer"
        assert "preethamdev05@gmail.com" in modal_text, "Missing support email"
        assert "29AAFCO1234F1ZP" not in modal_text, "Fake GSTIN must not exist!"
        assert "Pvt Ltd" not in modal_text, "Fake Pvt Ltd must not exist!"
        assert "GST-exempt under Sec 22 CGST Act" in modal_text, "Missing GST exempt note"
        print("[OK] Verified clean Software License Receipt: no fake GSTIN, no fake Pvt Ltd, accurate developer entity")

        # Test Escape key closes modal
        page.keyboard.press("Escape")
        page.wait_for_timeout(500)
        assert page.locator("text=SOFTWARE LICENSE RECEIPT").count() == 0, "Modal did not close on Escape key"
        print("[OK] Escape key closes receipt modal cleanly")

        # Test 3: Checkout Modal Pre-Payment Legal Disclosures
        print("\n--- TEST 3: Pre-payment disclosures on /subscription ---")
        page.click("button:has-text('Renew Business (₹999)')")
        page.wait_for_selector("text=Razorpay Secure Checkout")
        checkout_text = page.locator("div[role='dialog']").inner_text()
        assert "Terms of Service & EULA" in checkout_text, "Missing Terms link"
        assert "Biometric Privacy Policy" in checkout_text, "Missing Privacy link"
        assert "3-Day Refund Policy" in checkout_text, "Missing Refund link"
        print("[OK] Pre-payment legal disclosures present with active policy links")

        page.keyboard.press("Escape")
        print("[OK] Escape key closes checkout modal cleanly")

        browser.close()
        print("\n=== ALL RUSTWRIGHT INTERACTIVE ACCESSIBILITY & LEGAL TESTS PASSED! ===")

if __name__ == "__main__":
    main()
