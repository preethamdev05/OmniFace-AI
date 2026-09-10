import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';

export async function POST(req: NextRequest) {
  try {
    const { email, password } = await req.json();

    // Verification check (in production, check against bcrypt / argon2 hash in users table)
    if (!email || !password) {
      return NextResponse.json({ error: 'Email and password are required' }, { status: 400 });
    }

    if (password.length < 6) {
      return NextResponse.json({ error: 'Invalid credentials' }, { status: 401 });
    }

    const sessionToken = crypto.randomBytes(32).toString('hex');
    const adminUser = {
      id: 'usr_admin_01',
      email,
      fullName: email.split('@')[0].toUpperCase() + ' (Admin)',
      role: 'SUPERADMIN',
      orgId: 'org_001',
      orgName: 'National Institute of Technology',
      tier: 'BUSINESS',
    };

    const response = NextResponse.json({
      success: true,
      user: adminUser,
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
    return NextResponse.json({ error: err?.message || 'Authentication failed' }, { status: 500 });
  }
}
