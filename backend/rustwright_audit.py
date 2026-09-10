import os
import json
import time
from rustwright.sync_api import sync_playwright

ARTIFACT_DIR = r"C:\Users\preet\.gemini\antigravity\brain\22af9f53-6561-4a21-811d-357fe7c8b1e5"
BASE_URL = "https://omniface.vercel.app"

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

def run_audit():
    results = {}
    print(f"[*] Starting Rustwright full-surface audit on {BASE_URL}...")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 900},
            user_agent="OmniFace-Rustwright-Auditor/1.0"
        )
        page = context.new_page()

        for pg in PAGES:
            name = pg["name"]
            url = f"{BASE_URL}{pg['path']}"
            print(f"[*] Auditing {name} at {url}...")

            console_logs = []
            errors = []

            page.on("console", lambda msg: console_logs.append({"type": msg.type, "text": msg.text}))
            page.on("pageerror", lambda err: errors.append(str(err)))

            start_t = time.time()
            response = page.goto(url, wait_until="networkidle", timeout=30000)
            load_time_ms = int((time.time() - start_t) * 1000)

            status_code = response.status if response else 0
            title = page.title()

            screenshot_path = os.path.join(ARTIFACT_DIR, f"rw_{name}.png")
            page.screenshot(path=screenshot_path, full_page=True)

            # Analyze DOM elements
            headings = page.locator("h1, h2, h3").all_inner_texts()
            buttons = page.locator("button, a.btn").all_inner_texts()
            tables = page.locator("table").count()
            cards = page.locator("[class*='card'], [class*='rounded-'], [class*='bg-']").count()

            results[name] = {
                "url": url,
                "status": status_code,
                "title": title,
                "load_time_ms": load_time_ms,
                "headings_sample": headings[:5],
                "buttons_count": len(buttons),
                "tables_count": tables,
                "cards_count": cards,
                "console_logs_count": len(console_logs),
                "page_errors": errors,
                "screenshot": screenshot_path
            }
            print(f"    [OK] Status: {status_code}, Load: {load_time_ms}ms, Title: '{title}', Headings: {len(headings)}, Errors: {len(errors)}")

        browser.close()

    summary_file = os.path.join(ARTIFACT_DIR, "rustwright_audit_summary.json")
    with open(summary_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\n[+] Audit completed! Summary written to: {summary_file}")
    return results

if __name__ == "__main__":
    run_audit()
