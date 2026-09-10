import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { users, organizations, organizationMembers } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { signSessionToken, UserRole } from '@/lib/auth';
import { eq } from 'drizzle-orm';

export async function POST(req: NextRequest) {
  try {
    const { email, password } = await req.json();

    if (!email || !password) {
      return NextResponse.json({ error: 'Email and password are required' }, { status: 400 });
    }

    if (password.length < 6) {
      return NextResponse.json({ error: 'Invalid credentials' }, { status: 401 });
    }

    let userId = 'usr_admin_01';
    let userRole: UserRole = 'ADMIN';
    let orgId = DEFAULT_ORG_ID;
    let orgName = 'National Institute of Technology';
    let tier = 'PRO';

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          const existing = await database
            .select()
            .from(users)
            .where(eq(users.email, email))
            .limit(1);

          if (existing.length > 0) {
            userId = existing[0].id;
            userRole = (existing[0].role as UserRole) || 'ADMIN';
            if (existing[0].orgId) orgId = existing[0].orgId;
          } else {
            const inserted = await database
              .insert(users)
              .values({
                email,
                fullName: email.split('@')[0],
                orgId: DEFAULT_ORG_ID,
                role: 'ADMIN',
              })
              .returning({ id: users.id });

            userId = inserted[0]?.id || userId;
            await database.insert(organizationMembers).values({
              organizationId: DEFAULT_ORG_ID,
              userId,
              role: 'ADMIN',
              status: 'ACTIVE',
            });
          }

          const org = await database
            .select()
            .from(organizations)
            .where(eq(organizations.id, orgId))
            .limit(1);

          if (org.length > 0) {
            orgName = org[0].name;
            tier = org[0].tier;
          }
        } catch (dbErr) {
          console.warn('Login database user lookup warning:', dbErr);
        }
      }
    }

    const adminUser = {
      id: userId,
      email,
      fullName: email.split('@')[0].toUpperCase() + ' (Admin)',
      role: userRole,
      orgId,
      orgName,
      tier,
    };

    const sessionToken = signSessionToken({
      userId,
      email,
      fullName: adminUser.fullName,
      role: userRole,
      orgId,
      orgName,
      tier,
    });

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
