import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { users, organizations, organizationMembers } from '@/db/schema';
import { signSessionToken, UserRole } from '@/lib/auth';
import { eq } from 'drizzle-orm';

export async function POST(req: NextRequest) {
  try {
    const { email, password } = await req.json();

    if (!email || !password) {
      return NextResponse.json({ success: false, error: 'Email and password are required' }, { status: 400 });
    }

    if (password.length < 6) {
      return NextResponse.json({ success: false, error: 'Invalid credentials' }, { status: 401 });
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database service is unavailable' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    const cleanEmail = email.trim().toLowerCase();
    const existing = await database
      .select()
      .from(users)
      .where(eq(users.email, cleanEmail))
      .limit(1);

    if (existing.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Invalid credentials. User account does not exist.' },
        { status: 401 }
      );
    }

    const user = existing[0];
    const userId = user.id;
    let userRole: UserRole = (user.role as UserRole) || 'ADMIN';
    let userOrgId: string | null = user.orgId || null;
    let orgName: string | null = null;
    let tier = 'FREE';

    // Query organization membership
    const membership = await database
      .select({
        role: organizationMembers.role,
        organizationId: organizationMembers.organizationId,
      })
      .from(organizationMembers)
      .where(eq(organizationMembers.userId, userId))
      .limit(1);

    if (membership.length > 0) {
      userRole = (membership[0].role as UserRole) || userRole;
      if (membership[0].organizationId) {
        userOrgId = membership[0].organizationId;
      }
    }

    if (userOrgId) {
      const org = await database
        .select({ name: organizations.name, tier: organizations.tier })
        .from(organizations)
        .where(eq(organizations.id, userOrgId))
        .limit(1);

      if (org.length > 0) {
        orgName = org[0].name;
        tier = org[0].tier || 'FREE';
      } else {
        userOrgId = null;
      }
    }

    const authenticatedUser = {
      id: userId,
      email: user.email,
      fullName: user.fullName,
      role: userRole,
      orgId: userOrgId,
      orgName,
      tier,
    };

    const sessionToken = signSessionToken({
      userId,
      email: user.email,
      fullName: user.fullName,
      role: userRole,
      orgId: userOrgId,
      orgName,
      tier,
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
    return NextResponse.json({ success: false, error: err?.message || 'Authentication failed' }, { status: 500 });
  }
}
