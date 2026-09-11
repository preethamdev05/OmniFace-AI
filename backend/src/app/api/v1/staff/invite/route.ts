import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { staffInvitations, auditLogs } from '@/db/schema';
import { requireSession } from '@/lib/api-auth';
import { UserRole } from '@/lib/auth';

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'No organization affiliated with session' },
        { status: 403 }
      );
    }

    const body = await req.json();
    const { email, fullName, role = 'TEACHER' } = body;

    if (!email || typeof email !== 'string' || !email.includes('@')) {
      return NextResponse.json(
        { success: false, error: 'Valid email address is required for invitation.' },
        { status: 400 }
      );
    }

    const validRoles: UserRole[] = ['OWNER', 'ADMIN', 'TEACHER', 'VIEWER'];
    if (!validRoles.includes(role)) {
      return NextResponse.json(
        { success: false, error: `Invalid role. Must be one of: ${validRoles.join(', ')}` },
        { status: 400 }
      );
    }

    // Only OWNER can invite another OWNER or ADMIN
    if ((role === 'OWNER' || role === 'ADMIN') && user.role !== 'OWNER') {
      return NextResponse.json(
        { success: false, error: 'Only an organization OWNER can invite OWNER or ADMIN roles.' },
        { status: 403 }
      );
    }

    // Generate cryptographically secure token and SHA-256 hash
    const rawToken = crypto.randomBytes(32).toString('hex');
    const tokenHash = crypto.createHash('sha256').update(rawToken).digest('hex');
    const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000); // 7 days

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

    const inserted = await database
      .insert(staffInvitations)
      .values({
        organizationId: orgId,
        email: email.trim().toLowerCase(),
        fullName: fullName?.trim() || email.split('@')[0],
        role,
        tokenHash,
        status: 'PENDING',
        expiresAt,
        invitedByUserId: user.userId,
      })
      .returning();

    // Audit logging
    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'STAFF_INVITED',
      entityType: 'STAFF_INVITATION',
      entityId: inserted[0]?.id || email,
      reason: `Invited ${email} with role ${role}`,
    });

    return NextResponse.json(
      {
        success: true,
        message: `Invitation successfully issued to ${email}`,
        invitation: {
          id: inserted[0]?.id,
          email: email.trim().toLowerCase(),
          role,
          expiresAt: expiresAt.toISOString(),
          // Secure invitation token provided to the administrator for dispatch
          inviteToken: rawToken,
          inviteUrl: `/login?invite=${rawToken}`,
        },
      },
      { status: 201 }
    );
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to issue invitation' },
      { status: 500 }
    );
  }
}
