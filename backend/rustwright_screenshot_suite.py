import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
import time
from rustwright.sync_api import sync_playwright

BASE_URL = os.environ.get("OMNIFACE_TEST_URL", "http://localhost:3000")
ARTIFACT_DIR = r"C:\Users\preet\.gemini\antigravity\brain\22af9f53-6561-4a21-811d-357fe7c8b1e5"
SCREENSHOT_DIR = os.path.join(ARTIFACT_DIR, "screenshots")
os.makedirs(SCREENSHOT_DIR, exist_ok=True)

PAGES = [
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

def capture_all():
    print(f"[*] Starting Rustwright visual inspection & screenshot capture against {BASE_URL}...")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        
        # 1. Desktop Context (1440x900)
        desktop_ctx = browser.new_context(
            viewport={"width": 1440, "height": 900},
            user_agent="OmniFace-Rustwright-Desktop/1.0"
        )
        desktop_page = desktop_ctx.new_page()

        for pg in PAGES:
            name = pg["name"]
            url = f"{BASE_URL}{pg['path']}"
            desktop_page.goto(url, wait_until="networkidle")
            path = os.path.join(SCREENSHOT_DIR, f"desktop_{name}.png")
            desktop_page.screenshot(path=path, full_page=True)
            print(f"  [SAVED] Desktop: {name} -> {path}")

        # Desktop Interactive Modals
        # Enrollment Minor Modal
        desktop_page.goto(f"{BASE_URL}/roster", wait_until="networkidle")
        desktop_page.click("button:has-text('Enroll New Member')")
        desktop_page.wait_for_selector("#enroll-modal-title")
        desktop_page.select_option("#enroll-age-category", "MINOR")
        time.sleep(0.3)
        modal_minor_path = os.path.join(SCREENSHOT_DIR, "desktop_modal_enrollment_minor.png")
        desktop_page.screenshot(path=modal_minor_path)
        print(f"  [SAVED] Desktop Modal: Enrollment Minor -> {modal_minor_path}")
        desktop_page.keyboard.press("Escape")
        time.sleep(0.3)

        # Subscription Receipt Modal
        desktop_page.goto(f"{BASE_URL}/subscription", wait_until="networkidle")
        desktop_page.locator("button:has-text('View Receipt')").first.click()
        desktop_page.wait_for_selector("text=SOFTWARE LICENSE RECEIPT")
        time.sleep(0.3)
        receipt_path = os.path.join(SCREENSHOT_DIR, "desktop_modal_license_receipt.png")
        desktop_page.screenshot(path=receipt_path)
        print(f"  [SAVED] Desktop Modal: License Receipt -> {receipt_path}")
        desktop_page.keyboard.press("Escape")
        time.sleep(0.3)

        # Checkout Modal
        desktop_page.click("button:has-text('Renew Business (₹999)')")
        desktop_page.wait_for_selector("text=Razorpay Secure Checkout")
        time.sleep(0.3)
        checkout_path = os.path.join(SCREENSHOT_DIR, "desktop_modal_razorpay_checkout.png")
        desktop_page.screenshot(path=checkout_path)
        print(f"  [SAVED] Desktop Modal: Razorpay Checkout -> {checkout_path}")
        desktop_page.keyboard.press("Escape")
        desktop_ctx.close()

        # 2. Mobile Context (390x844 - iPhone 14 / Xiaomi 14 viewport)
        mobile_ctx = browser.new_context(
            viewport={"width": 390, "height": 844},
            is_mobile=True,
            has_touch=True,
            user_agent="OmniFace-Rustwright-Mobile/1.0"
        )
        mobile_page = mobile_ctx.new_page()

        for pg in PAGES:
            name = pg["name"]
            url = f"{BASE_URL}{pg['path']}"
            mobile_page.goto(url, wait_until="networkidle")
            path = os.path.join(SCREENSHOT_DIR, f"mobile_{name}.png")
            mobile_page.screenshot(path=path, full_page=True)
            print(f"  [SAVED] Mobile: {name} -> {path}")

        # Mobile Navigation Drawer Open State
        mobile_page.goto(f"{BASE_URL}/", wait_until="networkidle")
        mobile_page.click(".mobile-menu-toggle")
        mobile_page.wait_for_selector(".sidebar.sidebar-open")
        time.sleep(0.3)
        drawer_path = os.path.join(SCREENSHOT_DIR, "mobile_nav_drawer_open.png")
        mobile_page.screenshot(path=drawer_path)
        print(f"  [SAVED] Mobile: Navigation Drawer Open -> {drawer_path}")
        # Close via close button
        mobile_page.click(".mobile-sidebar-close")
        time.sleep(0.3)

        # Mobile Enrollment Modal
        mobile_page.goto(f"{BASE_URL}/roster", wait_until="networkidle")
        mobile_page.click("button:has-text('Enroll New Member')")
        mobile_page.wait_for_selector("#enroll-modal-title")
        mobile_page.select_option("#enroll-age-category", "MINOR")
        time.sleep(0.3)
        mobile_modal_path = os.path.join(SCREENSHOT_DIR, "mobile_modal_enrollment_minor.png")
        mobile_page.screenshot(path=mobile_modal_path)
        print(f"  [SAVED] Mobile Modal: Enrollment Minor -> {mobile_modal_path}")
        mobile_page.keyboard.press("Escape")
        time.sleep(0.3)

        # Mobile Checkout Modal
        mobile_page.goto(f"{BASE_URL}/subscription", wait_until="networkidle")
        mobile_page.click("button:has-text('Renew Business (₹999)')")
        mobile_page.wait_for_selector("text=Razorpay Secure Checkout")
        time.sleep(0.3)
        mobile_checkout_path = os.path.join(SCREENSHOT_DIR, "mobile_modal_razorpay_checkout.png")
        mobile_page.screenshot(path=mobile_checkout_path)
        print(f"  [SAVED] Mobile Modal: Razorpay Checkout -> {mobile_checkout_path}")
        mobile_page.keyboard.press("Escape")
        time.sleep(0.3)

        # Mobile License Receipt Modal
        mobile_page.locator("button:has-text('View Receipt')").first.click()
        mobile_page.wait_for_selector("text=SOFTWARE LICENSE RECEIPT")
        time.sleep(0.3)
        mobile_receipt_path = os.path.join(SCREENSHOT_DIR, "mobile_modal_license_receipt.png")
        mobile_page.screenshot(path=mobile_receipt_path)
        print(f"  [SAVED] Mobile Modal: License Receipt -> {mobile_receipt_path}")
        mobile_page.keyboard.press("Escape")

        mobile_ctx.close()
        browser.close()

    print("\n[+] All visual captures successfully recorded via Rustwright!")

if __name__ == "__main__":
    capture_all()
