#!/usr/bin/env bash
# ==============================================================================
# ☁️ OmniFace AI — Cloudflare Edge Zero-Trust Model Gateway Deployer
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=================================================================="
echo "☁️ OMNIFACE AI — CLOUDFLARE EDGE ZERO-TRUST GATEWAY DEPLOYER"
echo "=================================================================="

# Check wrangler
if ! command -v wrangler &> /dev/null; then
    echo "❌ Wrangler CLI not found. Installing..."
    npm install -g wrangler
fi

# Detect saved Hugging Face token
HF_TOKEN=""
if [ -f "/root/.cache/huggingface/token" ]; then
    HF_TOKEN=$(cat /root/.cache/huggingface/token | tr -d '[:space:]')
    echo "🤗 Found active Hugging Face authentication token."
fi

# Check login status
echo "[+] Verifying Cloudflare authentication status..."
if ! wrangler whoami &> /dev/null; then
    echo ""
    echo "⚠️ You are not currently logged in to Cloudflare."
    echo "👉 Choose authentication method:"
    echo "   1) Browser Login ('wrangler login')"
    echo "   2) Cloudflare API Token (from https://dash.cloudflare.com/profile/api-tokens)"
    read -rp "Enter choice [1/2]: " AUTH_CHOICE

    if [ "$AUTH_CHOICE" = "2" ]; then
        read -rsp "Enter Cloudflare API Token: " CF_TOKEN
        echo ""
        export CLOUDFLARE_API_TOKEN="$CF_TOKEN"
    else
        wrangler login
    fi
fi

echo ""
echo "[+] Cloudflare Account Verified:"
wrangler whoami || true

# Put secret
if [ -z "$HF_TOKEN" ]; then
    echo ""
    read -rp "Enter your Hugging Face Access Token (hf_...): " HF_TOKEN
fi

if [ -n "$HF_TOKEN" ]; then
    echo ""
    echo "[+] Storing HF_TOKEN as an encrypted server-side secret in Cloudflare..."
    echo "$HF_TOKEN" | wrangler secret put HF_TOKEN
fi

echo ""
echo "[+] Deploying Cloudflare Worker to global Edge CDN..."
wrangler deploy

echo ""
echo "=================================================================="
echo "✅ DEPLOYMENT COMPLETE!"
echo "🚀 Your Zero-Trust Model Gateway is now live worldwide on Cloudflare."
echo "📱 Open OmniFace AI -> Settings -> Zero-Trust Model Gateway & Vault"
echo "   Paste your worker URL into 'Cloudflare Gateway URL (Token-Free)'"
echo "=================================================================="
