# ☁️ OmniFace AI — Cloudflare Zero-Trust Edge Model Gateway

This Cloudflare Worker acts as a **Zero-Trust streaming edge proxy** between the OmniFace AI Android app and your private Hugging Face model repository.

---

## 🔒 Security Architecture (Zero Token Exposure)

```
[ Android App ]
       │  (100% Tokenless GET /model)
       ▼
[ Cloudflare Edge Worker (Global CDN across 330+ cities) ]
       │  (Attaches env.HF_TOKEN encrypted secret)
       ▼
[ Hugging Face Hub (Private Model Repository) ]
```

### Why this is completely leak-proof:
1. **Zero Client Secrets**: No Hugging Face token is hardcoded, stored in SharedPreferences, or compiled into the APK.
2. **Reverse Engineering Proof**: Even if an attacker decompiles the APK (`jadx`, `apktool`, `ghidra`), there is no token to find.
3. **MITM Proof**: Network proxies (Charles, Wireshark, mitmproxy) only see requests to your Cloudflare Worker URL with no authorization headers.
4. **Resumable Streaming**: Implements HTTP `Range` request forwarding for mobile networks and edge caching (`Cache-Control`).

---

## 🚀 1-Step Deployment

Run the automated deployer:
```bash
bash /storage/emulated/0/AI-HUB/FR/cloudflare-worker/deploy.sh
```

Or execute manually with Wrangler:
```bash
cd /storage/emulated/0/AI-HUB/FR/cloudflare-worker

# 1. Login to Cloudflare
wrangler login

# 2. Store your private Hugging Face token as an encrypted server-side secret
echo "hf_your_token_here" | wrangler secret put HF_TOKEN

# 3. Deploy to Cloudflare Edge
wrangler deploy
```

---

## 📱 Connecting in OmniFace AI App

1. Copy your deployed worker URL (e.g. `https://omniface-model-gateway.<your-subdomain>.workers.dev`).
2. Open **OmniFace AI** $\to$ **Settings** $\to$ **Zero-Trust Model Gateway & Vault**.
3. Paste the URL into **Cloudflare Gateway URL (Token-Free)** and tap **Save & Apply**.
4. Tap **Download** on the AntelopeV2 model!
