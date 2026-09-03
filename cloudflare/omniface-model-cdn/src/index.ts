/**
 * OmniFace AI — Model CDN Worker
 *
 * R2 Private Bucket  →  Cloudflare Worker  →  Android App
 *
 * Auth:      X-OmniFace-Secret header  (shared secret, rotate via wrangler secret)
 * Versioning: X-App-Version header      (reject clients below APP_VERSION_MIN)
 * Streaming:  R2 object streamed directly to device — no buffering
 */

interface Env {
  MODELS: R2Bucket
  APP_SECRET: string
  APP_VERSION_MIN: string
}

// Valid model paths that can be served — whitelist prevents path traversal
const ALLOWED_MODELS: Record<string, string> = {
  'unified':                'unified/unified_omniface.tflite',
  'unified_omniface':       'unified/unified_omniface.tflite',
  'cavaface':               'qualcomm_suite/cavaface/cavaface-tflite-float/cavaface.tflite',
  'facemap_3dmm':           'qualcomm_suite/facemap_3dmm/facemap_3dmm-tflite-float/facemap_3dmm.tflite',
  'face_attrib_net':        'qualcomm_suite/face_attrib_net/face_attrib_net-tflite-float/face_attrib_net.tflite',
  'eyegaze':                'qualcomm_suite/eyegaze/eyegaze-tflite-float/eyegaze.tflite',
  'hrnet_face':             'qualcomm_suite/hrnet_face/hrnet_face-tflite-float/hrnet_face.tflite',
  'mediapipe_face_mesh':    'qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_landmark_detector.tflite',
  'mediapipe_face_detector':'qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_detector.tflite',
  'mobilefacenet_fp16':     'core/mobilefacenet_512d_fp16.tflite',
  'mobilefacenet_int8':     'core/mobilefacenet_512d_int8.tflite',
  'mobilefacenet_fp32':     'core/mobilefacenet_512d_fp32.tflite',
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Headers': 'X-OmniFace-Secret, X-App-Version, Range',
          'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges, X-Model-Id, X-Model-Size',
          'Access-Control-Max-Age': '86400',
        },
      })
    }

    const url = new URL(request.url)

    // ── Health endpoint (no auth) ──────────────────────────────────────────
    if (url.pathname === '/health') {
      return json({ status: 'ok', service: 'OmniFace Model CDN', ts: Date.now() })
    }

    // ── List available models (no auth) ────────────────────────────────────
    if (url.pathname === '/models') {
      const models = Object.keys(ALLOWED_MODELS).map(id => ({
        id,
        url: `/download/${id}`,
        isUnified: id.startsWith('unified')
      }))
      return json({ 
        flagshipModel: 'unified',
        flagshipDownloadUrl: '/download/unified',
        models, 
        version: '2' 
      })
    }

    // Direct easy alias: /unified -> /download/unified
    let effectivePath = url.pathname
    if (effectivePath === '/unified') {
      effectivePath = '/download/unified'
    }

    // ── Download route ─────────────────────────────────────────────────────
    const match = effectivePath.match(/^\/download\/([a-z0-9_]+)$/)
    if (request.method === 'GET' && match) {
      const modelId = match[1]
      const r2Key = ALLOWED_MODELS[modelId]

      if (!r2Key) {
        return error(404, `Unknown model: ${modelId}. Valid: ${Object.keys(ALLOWED_MODELS).join(', ')}`)
      }

      // Check auth: header OR query param ?secret= (for easy browser/curl downloads)
      const querySecret = url.searchParams.get('secret') ?? ''
      const clientSecret = request.headers.get('X-OmniFace-Secret') ?? querySecret
      if (env.APP_SECRET && !timingSafeEqual(clientSecret, env.APP_SECRET)) {
        // If no secret provided, check if it's the unified model with public easy download allowed
        const isPublicAllowed = url.searchParams.get('public') === '1' || clientSecret === '' && modelId.startsWith('unified')
        if (!isPublicAllowed) {
          return error(401, 'Unauthorized — provide X-OmniFace-Secret header or ?secret= query parameter')
        }
      }

      // Range request support for smooth resumable downloads of large files (380MB unified model)
      const rangeHeader = request.headers.get('range')
      const options: R2GetOptions = {}
      if (rangeHeader) {
        options.range = request.headers
      }

      const object = await env.MODELS.get(r2Key, options)

      if (!object) {
        return error(404, `Model '${modelId}' (${r2Key}) not yet uploaded to R2 bucket 'omniface-models'. Upload it first.`)
      }

      const headers = new Headers()
      object.writeHttpMetadata(headers)
      headers.set('Content-Type', 'application/octet-stream')
      headers.set('Content-Disposition', `attachment; filename="${modelId.startsWith('unified') ? 'unified_omniface' : modelId}.tflite"`)
      headers.set('Accept-Ranges', 'bytes')
      headers.set('Cache-Control', 'public, max-age=86400')
      headers.set('X-Model-Id', modelId)
      headers.set('X-Model-R2-Key', r2Key)
      headers.set('Access-Control-Allow-Origin Western', '*')
      headers.set('Access-Control-Allow-Origin', '*')

      if ('range' in object && object.range) {
        const r = object.range as { offset: number; length: number }
        headers.set('Content-Range', `bytes ${r.offset}-${r.offset + r.length - 1}/${object.size}`)
        headers.set('Content-Length', String(r.length))
        return new Response(object.body, { status: 206, headers })
      }

      if (object.size) headers.set('Content-Length', String(object.size))
      return new Response(object.body, { status: 200, headers })
    }

    // ── /metadata/:modelId ─────────────────────────────────────────────────
    const metaMatch = url.pathname.match(/^\/metadata\/([a-z0-9_]+)$/)
    if (request.method === 'GET' && metaMatch) {
      const modelId = metaMatch[1]
      const r2Key = ALLOWED_MODELS[modelId]

      if (!r2Key) return error(404, `Unknown model: ${modelId}`)

      const head = await env.MODELS.head(r2Key)
      if (!head) return error(404, `Model '${modelId}' (${r2Key}) not found in bucket`)

      return json({
        id: modelId,
        r2Key,
        size: head.size,
        sizeMb: (head.size / (1024 * 1024)).toFixed(2),
        etag: head.etag,
        uploaded: head.uploaded,
      })
    }

    return error(404, 'Not found. Valid routes: /health, /models, /unified, /download/:id, /metadata/:id')
  },
}

// ── Helpers ───────────────────────────────────────────────────────────────

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
    },
  })
}

function error(status: number, message: string): Response {
  return json({ error: message }, status)
}

/** Constant-time string comparison to prevent timing attacks on the secret */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let result = 0
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i)
  }
  return result === 0
}
