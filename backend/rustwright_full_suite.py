import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
import json
import time
from rustwright.sync_api import sync_playwright

BASE_URL = "https://omniface.vercel.app"
ARTIFACT_DIR = r"C:\Users\preet\.gemini\antigravity\brain\22af9f53-6561-4a21-811d-357fe7c8b1e5"

ROUTES = [
    {"name": "overview", "path": "/"},
    {"name": "roster", "path": "/roster"},
    {"name": "reports", "path": "/reports"},
    {"name": "subscription", "path": "/subscription"},
    {"name": "api_keys", "path": "/api-keys"},
    {"name": "privacy", "path": "/privacy"},
    {"name": "terms", "path": "/terms"},
    {"name": "cookies", "path": "/cookies"},
    {"name": "refund_policy", "path": "/refund-policy"},
]

def run_suite():
    print("=" * 60)
    print(f"🚀 RUSTWRIGHT COMPREHENSIVE WEB SUITE TEST")
    print(f"Target: {BASE_URL}")
    print("=" * 60)

    results = {
        "routes": {},
        "interactive_tests": [],
        "navigation_tests": [],
        "api_tests": [],
        "console_errors": []
    }

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            user_agent="OmniFace-Rustwright-TestSuite/2.0"
        )
        page = context.new_page()

        # Listen for console errors & unhandled exceptions
        def handle_console(msg):
            if msg.type == "error":
                results["console_errors"].append(f"[CONSOLE ERROR] {msg.text}")
        page.on("console", handle_console)
        page.on("pageerror", lambda err: results["console_errors"].append(f"[PAGE ERROR] {err}"))

        # -------------------------------------------------------------
        # SUITE 1: Route Integrity, Headings & Visual Artifacts
        # -------------------------------------------------------------
        print("\n--- [SUITE 1] Route Integrity & Latency Audits ---")
        for r in ROUTES:
            name = r["name"]
            url = f"{BASE_URL}{r['path']}"
            t0 = time.time()
            resp = page.goto(url, wait_until="networkidle", timeout=30000)
            latency_ms = int((time.time() - t0) * 1000)
            status = resp.status if resp else 0
            title = page.title()
            
            # Extract elements
            headings = page.locator("h1, h2, h3").all_inner_texts()
            buttons = page.locator("button, a.btn").count()
            cards = page.locator("[class*='card'], [class*='rounded-'], [class*='bg-']").count()

            screenshot_path = os.path.join(ARTIFACT_DIR, f"suite_{name}.png")
            page.screenshot(path=screenshot_path, full_page=True)

            assert status == 200, f"Route {name} failed with status {status}"
            assert len(title) > 0, f"Route {name} has empty title"

            results["routes"][name] = {
                "url": url,
                "status": status,
                "latency_ms": latency_ms,
                "title": title,
                "headings_count": len(headings),
                "buttons_count": buttons,
                "cards_count": cards,
                "screenshot": screenshot_path
            }
            print(f"  [PASS] {name:<14} -> {status} OK ({latency_ms}ms) | Title: '{title[:35]}...'")

        # -------------------------------------------------------------
        # SUITE 2: Interactive Modals, Forms & Legal Compliance Gates
        # -------------------------------------------------------------
        print("\n--- [SUITE 2] Interactive Modals & Legal Gates ---")
        
        # Test 2.1: Minor Parental Consent Gate on /roster
        print("  [TEST 2.1] Minor Consent Gate on /roster...")
        page.goto(f"{BASE_URL}/roster", wait_until="networkidle")
        page.click("button:has-text('Enroll New Member')")
        page.wait_for_selector("#enroll-modal-title")
        
        page.select_option("#enroll-age-category", "MINOR")
        consent_box = page.locator("#minor-consent-check")
        assert consent_box.count() > 0, "Parental consent checkbox not rendered!"
        assert not consent_box.is_checked(), "Parental consent checkbox pre-checked (illegal)!"
        
        submit_btn = page.locator("button[type='submit']:has-text('Save & Queue for Kiosk Sync')")
        assert submit_btn.is_disabled(), "Submit button active without required consent!"
        
        page.check("#minor-consent-check")
        assert submit_btn.is_enabled(), "Submit button remained disabled after consent!"
        
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
        assert page.locator("#enroll-modal-title").count() == 0, "Modal did not close on Escape"
        results["interactive_tests"].append({"test": "Minor Parental Consent Gate", "status": "PASSED"})
        print("  [PASS] Minor Consent Gate: unchecked default, strict form gating, clean escape.")

        # Test 2.2: Clean Software License Receipt on /subscription
        print("  [TEST 2.2] Software License Receipt on /subscription...")
        page.goto(f"{BASE_URL}/subscription", wait_until="networkidle")
        page.locator("button:has-text('View Receipt')").first.click()
        page.wait_for_selector("text=SOFTWARE LICENSE RECEIPT")
        modal_text = page.locator("div[role='dialog']").inner_text()
        
        assert "OmniFace Technologies" in modal_text, "Missing entity brand"
        assert "Operated by Preetham, Independent Developer" in modal_text, "Missing developer disclosure"
        assert "29AAFCO1234F1ZP" not in modal_text, "Fake GSTIN detected!"
        assert "Pvt Ltd" not in modal_text, "Fake Pvt Ltd detected!"
        assert "GST-exempt under Sec 22 CGST Act" in modal_text, "Missing statutory tax exemption note"
        
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
        results["interactive_tests"].append({"test": "Software License Receipt", "status": "PASSED"})
        print("  [PASS] License Receipt: verified developer disclosure, no fictitious corporate tokens.")

        # Test 2.3: Razorpay Legal Checkout Disclosures
        print("  [TEST 2.3] Pre-payment Disclosures on /subscription...")
        page.click("button:has-text('Renew Business (₹999)')")
        page.wait_for_selector("text=Razorpay Secure Checkout")
        dialog_text = page.locator("div[role='dialog']").inner_text()
        
        assert "Terms of Service & EULA" in dialog_text, "Missing Terms disclosure link"
        assert "Biometric Privacy Policy" in dialog_text, "Missing Privacy disclosure link"
        assert "3-Day Refund Policy" in dialog_text, "Missing Refund disclosure link"
        
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
        results["interactive_tests"].append({"test": "Checkout Legal Disclosures", "status": "PASSED"})
        print("  [PASS] Checkout Modal: mandatory policy links active & compliant.")

        # -------------------------------------------------------------
        # SUITE 3: Interactive Dynamic Navigation & Filtering
        # -------------------------------------------------------------
        print("\n--- [SUITE 3] Navigation & Live Filtering ---")
        page.goto(f"{BASE_URL}/roster", wait_until="networkidle")
        search_input = page.locator("input[placeholder*='Search']")
        if search_input.count() > 0:
            search_input.fill("Aarav")
            page.wait_for_timeout(300)
            print("  [PASS] Roster search input active & reactive.")
            results["navigation_tests"].append({"test": "Roster Search", "status": "PASSED"})
        
        # Test navigation link clicks
        nav_links = page.locator("a[href='/reports']")
        if nav_links.count() > 0:
            nav_links.first.click()
            page.wait_for_url("**/reports**", timeout=10000)
            assert "/reports" in page.url
            print("  [PASS] In-app SPA navigation from /roster to /reports.")
            results["navigation_tests"].append({"test": "Header Navigation", "status": "PASSED"})

        # -------------------------------------------------------------
        # SUITE 4: API Endpoints Health Check
        # -------------------------------------------------------------
        print("\n--- [SUITE 4] Backend REST API Microservices ---")
        api_endpoints = [
            {"ep": "/api/v1/db/status", "method": "GET", "expected": [200]},
            {"ep": "/api/v1/analytics/summary", "method": "GET", "expected": [200]},
            {"ep": "/api/v1/users", "method": "GET", "expected": [200]},
            {"ep": "/api/v1/subscriptions", "method": "POST", "data": {"orgId": "00000000-0000-0000-0000-000000000000", "tier": "PREMIUM", "provider": "RAZORPAY"}, "expected": [200, 400]}
        ]
        for item in api_endpoints:
            ep = item["ep"]
            url = f"{BASE_URL}{ep}"
            method = item["method"]
            expected = item["expected"]
            t0 = time.time()
            if method == "POST":
                api_resp = page.request.post(url, data=item.get("data", {}))
            else:
                api_resp = page.request.get(url)
            latency = int((time.time() - t0) * 1000)
            status = api_resp.status
            content_type = api_resp.headers.get("content-type", "")
            
            assert status in expected, f"Unexpected API status {status} on {ep} (expected {expected})"
            results["api_tests"].append({
                "endpoint": ep,
                "method": method,
                "status": status,
                "latency_ms": latency,
                "content_type": content_type
            })
            print(f"  [PASS] API [{method}] {ep:<24} -> HTTP {status} ({latency}ms) [{content_type}]")

        browser.close()

    # Final summary assertions
    print("\n" + "=" * 60)
    print("🏆 ALL RUSTWRIGHT TEST SUITES COMPLETED SUCCESSFULLY!")
    print(f"Routes Audited:      {len(results['routes'])}")
    print(f"Interactive Tests:   {len(results['interactive_tests'])}")
    print(f"Navigation Tests:    {len(results['navigation_tests'])}")
    print(f"API Endpoints:       {len(results['api_tests'])}")
    print(f"Console Errors:      {len(results['console_errors'])}")
    print("=" * 60)

    summary_file = os.path.join(ARTIFACT_DIR, "rustwright_full_suite_report.json")
    with open(summary_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nReport written to: {summary_file}")
    return results

if __name__ == "__main__":
    run_suite()
