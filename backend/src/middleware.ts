import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_ROUTES = [
  '/',
  '/roster',
  '/api-keys',
  '/reports',
  '/subscription',
];

export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;

  const isProtected = PROTECTED_ROUTES.some(
    (route) => pathname === route || (route !== '/' && pathname.startsWith(route))
  );

  const sessionCookie = request.cookies.get('omniface_session')?.value;

  if (isProtected && !sessionCookie) {
    const loginUrl = new URL('/login', request.url);
    const destination = pathname + (search || '');
    if (destination !== '/') {
      loginUrl.searchParams.set('redirect', destination);
    }
    return NextResponse.redirect(loginUrl);
  }

  if (pathname === '/login' && sessionCookie) {
    const redirectParam = request.nextUrl.searchParams.get('redirect');
    const destination = redirectParam && redirectParam.startsWith('/') ? redirectParam : '/';
    return NextResponse.redirect(new URL(destination, request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    /*
     * Match all request paths except for the ones starting with:
     * - api (API routes handle their own security/kiosk tokens)
     * - _next/static (static assets)
     * - _next/image (image optimization files)
     * - favicon.ico (favicon)
     */
    '/((?!api|_next/static|_next/image|favicon.ico).*)',
  ],
};
