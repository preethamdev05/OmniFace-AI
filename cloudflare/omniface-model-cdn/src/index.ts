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
  'facemap_3dmm':   'qualcomm_suite/facemap_3dmm/facemap_3dmm-tflite-float/facemap_3dmm.tflite',
  'face_attrib_net':'qualcomm_suite/face_attrib_net/face_attrib_net-tflite-float/face_attrib_net.tflite',
  'eyegaze':        'qualcomm_suite/eyegaze/eyegaze-tflite-float/eyegaze.tflite',
  'hrnet_face':     'qualcomm_suite/hrnet_face/hrnet_face-tflite-float/hrnet_face.tflite',
  'mediapipe_face_mesh': 'qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_landmark_detector.tflite',
  'mediapipe_face_detector': 'qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_detector.tflite',
  'cavaface':       'qualcomm_suite/cavaface/cavaface-tflite-float/cavaface.tflite',
  'mobilefacenet_fp16': 'core/mobilefacenet_512d_fp16.tflite',
  'mobilefacenet_int8': 'core/mobilefacenet_512d_int8.tflite',
  'mobilefacenet_fp32': 'core/mobilefacenet_512d_fp32.tflite',
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Headers': 'X-OmniFace-Secret, X-App-Version',
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
      }))
      return json({ models, version: '1' })
    }

    // ── Auth check for all download routes ────────────────────────────────
    const clientSecret = request.headers.get('X-OmniFace-Secret') ?? ''
    if (!timingSafeEqual(clientSecret, env.APP_SECRET)) {
      return error(401, 'Unauthorized — invalid secret')
    }

    // Version gate
    const clientVersion = parseInt(request.headers.get('X-App-Version') ?? '0', 10)
    const minVersion = parseInt(env.APP_VERSION_MIN ?? '1', 10)
    if (clientVersion < minVersion) {
      return error(426, `App update required — minimum version ${minVersion}`)
    }

    // ── /download/:modelId ─────────────────────────────────────────────────
    const match = url.pathname.match(/^\/download\/([a-z0-9_]+)$/)
    if (request.method === 'GET' && match) {
      const modelId = match[1]
      const r2Key = ALLOWED_MODELS[modelId]

      if (!r2Key) {
        return error(404, `Unknown model: ${modelId}. Valid: ${Object.keys(ALLOWED_MODELS).join(', ')}`)
      }

      const object = await env.MODELS.get(r2Key)

      if (!object) {
        return error(404, `Model '${modelId}' not yet uploaded to R2 bucket. Upload it first.`)
      }

      const headers = new Headers()
      object.writeHttpMetadata(headers)
      headers.set('Content-Type', 'application/octet-stream')
      headers.set('Content-Disposition', `attachment; filename="${modelId}.tflite"`)
      if (object.size) headers.set('Content-Length', String(object.size))
      headers.set('Cache-Control', 'private, max-age=86400')
      headers.set('X-Model-Id', modelId)
      headers.set('X-Model-Size', String(object.size ?? 0))
      headers.set('X-Etag', object.etag ?? '')
      headers.set('Access-Control-Allow-Origin', '*')

      return new Response(object.body, { status: 200, headers })
    }

    // ── /metadata/:modelId ─────────────────────────────────────────────────
    const metaMatch = url.pathname.match(/^\/metadata\/([a-z0-9_]+)$/)
    if (request.method === 'GET' && metaMatch) {
      const modelId = metaMatch[1]
      const r2Key = ALLOWED_MODELS[modelId]

      if (!r2Key) return error(404, `Unknown model: ${modelId}`)

      const head = await env.MODELS.head(r2Key)
      if (!head) return error(404, `Model '${modelId}' not found in bucket`)

      return json({
        id: modelId,
        size: head.size,
        etag: head.etag,
        uploaded: head.uploaded,
      })
    }

    return error(404, 'Not found. Valid routes: /health, /models, /download/:id, /metadata/:id')
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
