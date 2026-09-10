import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { verifyFirebaseIdToken, resolveUserFromFirebase, signSessionToken } from '@/lib/auth';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const idToken = body.idToken || body.token || req.headers.get('authorization')?.replace('Bearer ', '');

    if (!idToken) {
      return NextResponse.json(
        { success: false, error: 'Firebase ID token is required' },
        { status: 400 }
      );
    }

    // Verify token against Google Firebase Auth
    const firebasePayload = await verifyFirebaseIdToken(idToken);

    // Resolve user & RBAC role (OWNER, ADMIN, TEACHER, VIEWER)
    const authenticatedUser = await resolveUserFromFirebase(firebasePayload);

    const sessionToken = signSessionToken({
      userId: authenticatedUser.id,
      email: authenticatedUser.email,
      fullName: authenticatedUser.fullName,
      role: authenticatedUser.role,
      orgId: authenticatedUser.orgId,
      orgName: authenticatedUser.orgName,
      tier: authenticatedUser.tier,
    });

    const response = NextResponse.json({
      success: true,
      user: authenticatedUser,
      token: sessionToken,
    });

    response.cookies.set({
      name: 'omniface_session',
      value: sessionToken,
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 7, // 7 days
    });

    return response;
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Firebase authentication verification failed' },
      { status: 401 }
    );
  }
}
