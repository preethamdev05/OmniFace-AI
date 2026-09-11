import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_ROUTES = [
  '/',
  '/roster',
  '/students',
  '/classes',
  '/departments',
  '/attendance',
  '/reports',
  '/devices',
  '/staff',
  '/subscription',
  '/settings',
  '/audit-log',
  '/api-keys',
];

function getSessionSecret(): string {
  const secret = process.env.OMNIFACE_SESSION_SECRET || process.env.NEXTAUTH_SECRET;
  if (!secret) {
    if (process.env.NODE_ENV === 'production') {
      throw new Error(
        '[CRITICAL SECURITY FATAL] OMNIFACE_SESSION_SECRET or NEXTAUTH_SECRET environment variable is mandatory in production.'
      );
    }
    return 'omniface_production_hmac_session_secret_2026_d7a8f9c2';
  }
  if (process.env.NODE_ENV === 'production' && secret.includes('d7a8f9c2')) {
    throw new Error(
      '[CRITICAL SECURITY FATAL] Default fallback session secret detected in production environment. A cryptographically random secret must be configured.'
    );
  }
  return secret;
}

/**
 * Edge-compatible cryptographic HMAC-SHA256 session verification using Web Crypto.
 */
async function verifySessionCookie(cookieValue: string | undefined): Promise<any | null> {
  if (!cookieValue || typeof cookieValue !== 'string') return null;

  const parts = cookieValue.split('.');
  if (parts.length !== 2) return null;

  const [dataB64, sigB64] = parts;

  try {
    const encoder = new TextEncoder();
    const secret = getSessionSecret();
    const key = await crypto.subtle.importKey(
      'raw',
      encoder.encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['verify']
    );

    let base64 = sigB64.replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) {
      base64 += '=';
    }
    const binary = atob(base64);
    const sigBytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      sigBytes[i] = binary.charCodeAt(i);
    }

    const isValid = await crypto.subtle.verify('HMAC', key, sigBytes, encoder.encode(dataB64));
    if (!isValid) return null;

    let dataStr = dataB64.replace(/-/g, '+').replace(/_/g, '/');
    while (dataStr.length % 4) {
      dataStr += '=';
    }
    const payload = JSON.parse(atob(dataStr));
    const now = Math.floor(Date.now() / 1000);

    if (payload.exp && payload.exp > now && payload.userId) {
      return payload;
    }
    return null;
  } catch {
    return null;
  }
}

export async function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;

  // Generate or propagate SRE correlation request ID
  const requestId = request.headers.get('x-request-id') || `req-${crypto.randomUUID().slice(0, 12)}`;
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-request-id', requestId);

  // If this is an API route, allow it through with correlation headers
  if (pathname.startsWith('/api')) {
    const response = NextResponse.next({
      request: {
        headers: requestHeaders,
      },
    });
    response.headers.set('x-request-id', requestId);
    return response;
  }

  const isProtected = PROTECTED_ROUTES.some(
    (route) => pathname === route || (route !== '/' && pathname.startsWith(route))
  );

  const sessionCookie = request.cookies.get('omniface_session')?.value;
  const session = await verifySessionCookie(sessionCookie);
  const isSessionValid = Boolean(session);

  if (isProtected && !isSessionValid) {
    const loginUrl = new URL('/login', request.url);
    const destination = pathname + (search || '');
    if (destination !== '/') {
      loginUrl.searchParams.set('redirect', destination);
    }
    const response = NextResponse.redirect(loginUrl);
    response.headers.set('x-request-id', requestId);
    if (sessionCookie) {
      response.cookies.delete('omniface_session');
    }
    return response;
  }

  if (pathname === '/login' && isSessionValid) {
    const redirectParam = request.nextUrl.searchParams.get('redirect');
    const destination = redirectParam && redirectParam.startsWith('/') ? redirectParam : '/';
    const response = NextResponse.redirect(new URL(destination, request.url));
    response.headers.set('x-request-id', requestId);
    return response;
  }

  // ── Enforce Onboarding for Unassigned Users ──
  if (isSessionValid && (!session.orgId || session.orgId.trim() === '')) {
    if (pathname !== '/onboarding' && !pathname.startsWith('/onboarding')) {
      const onboardingUrl = new URL('/onboarding', request.url);
      const response = NextResponse.redirect(onboardingUrl);
      response.headers.set('x-request-id', requestId);
      return response;
    }
  }

  // ── Redirect Already-Onboarded Users Away From Onboarding ──
  if (isSessionValid && session.orgId && session.orgId.trim() !== '' && pathname === '/onboarding') {
    const dest = session.tier === 'FREE' ? '/subscription' : '/';
    const response = NextResponse.redirect(new URL(dest, request.url));
    response.headers.set('x-request-id', requestId);
    return response;
  }

  // ── Enforce Free-Tier Operational Dashboard Lock ──
  // Free plan includes Android attendance only; operational web dashboard is restricted to Premium+.
  // Free users are allowed to access /subscription and /onboarding to manage billing.
  if (isSessionValid && session.tier === 'FREE') {
    const isOperationalDashboard = isProtected && pathname !== '/subscription';
    if (isOperationalDashboard) {
      const upgradeUrl = new URL('/subscription', request.url);
      upgradeUrl.searchParams.set('upgrade', 'required');
      const response = NextResponse.redirect(upgradeUrl);
      response.headers.set('x-request-id', requestId);
      return response;
    }
  }

  const response = NextResponse.next({
    request: {
      headers: requestHeaders,
    },
  });
  response.headers.set('x-request-id', requestId);
  return response;
}

export const config = {
  matcher: [
    /*
     * Match all request paths except for static files:
     * - _next/static (static assets)
     * - _next/image (image optimization files)
     * - favicon.ico (favicon)
     */
    '/((?!_next/static|_next/image|favicon.ico).*)',
  ],
};

