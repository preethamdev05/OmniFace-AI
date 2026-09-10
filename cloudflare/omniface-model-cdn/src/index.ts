/**
 * OmniFace AI — Model CDN Worker (Zero-Card Private GitHub Proxy)
 *
 * Architecture:
 * Client (Android / Browser / curl)
 *   ↓
 * Cloudflare Worker (Free Edge Tier) [Authenticates & hides GH_TOKEN]
 *   ↓ (GitHub Releases Asset API + Bearer GH_TOKEN)
 * GitHub Release Asset (Private repo: preethamdev05/OmniFace-AI)
 *   ↓ (302 Redirect to High-Speed Azure/S3 CDN Blob)
 * Client downloads directly at 100+ Mbps with resumable byte-ranges!
 */

interface Env {
  GH_TOKEN?: string
  APP_SECRET?: string
  APP_VERSION_MIN?: string
  GITHUB_REPO?: string
  DEFAULT_ASSET_ID?: string
}

const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': 'X-OmniFace-Secret, X-App-Version, Range, Authorization',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges, Location, X-Model-Id, X-Model-Size',
  'Access-Control-Max-Age': '86400',
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // ── 1. CORS Preflight ──────────────────────────────────────────────────
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS })
    }

    const url = new URL(request.url)
    const pathname = url.pathname

    // ── 2. Health Endpoint ────────────────────────────────────────────────
    if (pathname === '/health') {
      return json({
        status: 'ok',
        service: 'OmniFace AI Model CDN (Private GitHub Proxy)',
        repo: env.GITHUB_REPO ?? 'preethamdev05/OmniFace-AI',
        hasToken: !!env.GH_TOKEN,
        ts: Date.now(),
      })
    }

    // ── 3. Models Catalog Endpoint ─────────────────────────────────────────
    if (pathname === '/models') {
      return json({
        flagshipModel: 'unified',
        flagshipDownloadUrl: '/download/unified',
        version: 'v2.0.0-model',
        models: [
          {
            id: 'unified',
            name: 'unified_omniface.tflite',
            sizeBytes: 380182456,
            sizeMb: '362.57',
            downloadUrl: '/download/unified',
            description: 'Unified OmniFace AI (Qualcomm CavaFace 512-D + 6 Auxiliary Biometric Heads)',
          },
        ],
      })
    }

    // ── 4. Route Normalization (/unified -> /download/unified) ─────────────
    let effectivePath = pathname
    if (effectivePath === '/unified') {
      effectivePath = '/download/unified'
    }

    // ── 5. Download Route: /download/:id ──────────────────────────────────
    const downloadMatch = effectivePath.match(/^\/download\/([a-zA-Z0-9_-]+)$/)
    if (downloadMatch && (request.method === 'GET' || request.method === 'HEAD')) {
      const modelId = downloadMatch[1].toLowerCase()

      if (!modelId.startsWith('unified')) {
        return error(404, `Model '${modelId}' not found. Available: 'unified'`)
      }

      // Check optional client authentication
      const clientSecret = request.headers.get('X-OmniFace-Secret') ?? url.searchParams.get('secret') ?? ''
      if (env.APP_SECRET && clientSecret.length > 0) {
        if (!timingSafeEqual(clientSecret, env.APP_SECRET)) {
          return error(401, 'Invalid X-OmniFace-Secret authorization header or parameter.')
        }
      }

      const token = env.GH_TOKEN
      if (!token) {
        return error(500, 'Cloudflare Worker error: GH_TOKEN secret not configured in Worker.')
      }

      const repo = env.GITHUB_REPO || 'preethamdev05/OmniFace-AI'
      const assetId = env.DEFAULT_ASSET_ID || '546902091'
      const ghAssetUrl = `https://api.github.com/repos/${repo}/releases/assets/${assetId}`

      // Forward request to GitHub Release Asset API
      // GitHub API redirects with 302 Found to signed Azure/S3 CDN blob storage
      const ghRes = await fetch(ghAssetUrl, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Accept': 'application/octet-stream',
          'User-Agent': 'OmniFace-Model-CDN/2.0',
        },
        redirect: 'manual',
      })

      if (ghRes.status === 302 || ghRes.status === 301 || ghRes.status === 307) {
        const targetLocation = ghRes.headers.get('Location')
        if (!targetLocation) {
          return error(502, 'GitHub API responded with redirect but missing Location header.')
        }

        // If client explicitly requested direct streaming through worker:
        if (url.searchParams.get('stream') === '1') {
          const upstreamHeaders = new Headers()
          const clientRange = request.headers.get('range')
          if (clientRange) {
            upstreamHeaders.set('Range', clientRange)
          }

          const streamRes = await fetch(targetLocation, {
            method: request.method,
            headers: upstreamHeaders,
          })

          const responseHeaders = new Headers(streamRes.headers)
          for (const [k, v] of Object.entries(CORS_HEADERS)) {
            responseHeaders.set(k, v)
          }
          responseHeaders.set('Content-Disposition', 'attachment; filename="unified_omniface.tflite"')
          responseHeaders.set('Cache-Control', 'public, max-age=1800')

          return new Response(streamRes.body, {
            status: streamRes.status,
            headers: responseHeaders,
          })
        }

        // Default & Optimal: Return HTTP 302 Redirect
        // Android OkHttpClient automatically follows 302 redirects at line-rate speed
        const redirectHeaders = new Headers()
        for (const [k, v] of Object.entries(CORS_HEADERS)) {
          redirectHeaders.set(k, v)
        }
        redirectHeaders.set('Location', targetLocation)
        redirectHeaders.set('Content-Disposition', 'attachment; filename="unified_omniface.tflite"')
        redirectHeaders.set('Cache-Control', 'public, max-age=1800')

        return new Response(null, {
          status: 302,
          headers: redirectHeaders,
        })
      }

      // If GitHub returned an error status
      const errorText = await ghRes.text()
      return error(ghRes.status, `GitHub Releases API error (${ghRes.status}): ${errorText}`)
    }

    // ── 6. Metadata Route: /metadata/:id ──────────────────────────────────
    const metaMatch = pathname.match(/^\/metadata\/([a-zA-Z0-9_-]+)$/)
    if (metaMatch && request.method === 'GET') {
      const modelId = metaMatch[1].toLowerCase()
      if (modelId.startsWith('unified')) {
        return json({
          id: 'unified',
          filename: 'unified_omniface.tflite',
          sizeBytes: 380182456,
          sizeMb: '362.57',
          version: 'v2.0.0-model',
          sha256: 'da27e41ede2ec43dc003a9655dbc3c79d8edc471987afa9f221a959a46a677bd',
          releaseTag: 'v2.0.0-model',
          repo: env.GITHUB_REPO || 'preethamdev05/OmniFace-AI',
        })
      }
      return error(404, `Metadata not found for '${modelId}'`)
    }

    return error(404, 'Endpoint not found. Valid: /health, /models, /unified, /download/unified, /metadata/unified')
  },
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      ...CORS_HEADERS,
      'Content-Type': 'application/json; charset=utf-8',
    },
  })
}

function error(status: number, message: string): Response {
  return json({ error: message, status }, status)
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let result = 0
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i)
  }
  return result === 0
}

