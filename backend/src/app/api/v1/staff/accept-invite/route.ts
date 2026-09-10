import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { staffInvitations, users, organizationMembers, auditLogs } from '@/db/schema';
import { ensureDefaultOrganization } from '@/db/helpers';
import { eq, and, gt } from 'drizzle-orm';
import { signSessionToken, UserRole } from '@/lib/auth';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { inviteToken, fullName, password } = body;

    if (!inviteToken || typeof inviteToken !== 'string') {
      return NextResponse.json(
        { success: false, error: 'Invitation token is required.' },
        { status: 400 }
      );
    }

    const tokenHash = crypto.createHash('sha256').update(inviteToken.trim()).digest('hex');

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        message: 'Invitation accepted (sandbox mode)',
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    await ensureDefaultOrganization(database);

    // Locate valid, unexpired pending invitation
    const invites = await database
      .select()
      .from(staffInvitations)
      .where(
        and(
          eq(staffInvitations.tokenHash, tokenHash),
          eq(staffInvitations.status, 'PENDING'),
          gt(staffInvitations.expiresAt, new Date())
        )
      )
      .limit(1);

    if (invites.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Invalid or expired invitation token.' },
        { status: 400 }
      );
    }

    const invite = invites[0];

    // Find or create user
    let targetUserId: string;
    const existingUsers = await database
      .select()
      .from(users)
      .where(eq(users.email, invite.email))
      .limit(1);

    if (existingUsers.length > 0) {
      targetUserId = existingUsers[0].id;
    } else {
      const newUser = await database
        .insert(users)
        .values({
          email: invite.email,
          fullName: fullName?.trim() || invite.fullName || invite.email.split('@')[0],
          orgId: invite.organizationId,
          role: invite.role as any,
        })
        .returning({ id: users.id });
      targetUserId = newUser[0].id;
    }

    // Add or activate organization membership
    const existingMember = await database
      .select()
      .from(organizationMembers)
      .where(
        and(
          eq(organizationMembers.organizationId, invite.organizationId),
          eq(organizationMembers.userId, targetUserId)
        )
      )
      .limit(1);

    if (existingMember.length > 0) {
      await database
        .update(organizationMembers)
        .set({ role: invite.role as any, status: 'ACTIVE' })
        .where(eq(organizationMembers.id, existingMember[0].id));
    } else {
      await database.insert(organizationMembers).values({
        organizationId: invite.organizationId,
        userId: targetUserId,
        role: invite.role as any,
        status: 'ACTIVE',
      });
    }

    // Mark invitation as ACCEPTED
    await database
      .update(staffInvitations)
      .set({ status: 'ACCEPTED' })
      .where(eq(staffInvitations.id, invite.id));

    // Audit log
    await database.insert(auditLogs).values({
      organizationId: invite.organizationId,
      userId: targetUserId,
      action: 'INVITATION_ACCEPTED',
      entityType: 'STAFF_INVITATION',
      entityId: invite.id,
      reason: `Staff member ${invite.email} accepted invitation for role ${invite.role}`,
    });

    // Generate authenticated session token
    const sessionToken = signSessionToken({
      userId: targetUserId,
      email: invite.email,
      fullName: fullName?.trim() || invite.fullName || invite.email.split('@')[0],
      role: invite.role as UserRole,
      orgId: invite.organizationId,
      orgName: 'OmniFace Campus',
      tier: 'INSTITUTION',
    });

    const response = NextResponse.json({
      success: true,
      message: 'Invitation accepted successfully. Your membership is now active.',
      sessionToken,
      user: {
        id: targetUserId,
        email: invite.email,
        role: invite.role,
        orgId: invite.organizationId,
        sessionToken,
      },
    });

    response.cookies.set({
      name: 'omniface_session',
      value: sessionToken,
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 7 * 24 * 60 * 60,
    });

    return response;
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to process invitation acceptance' },
      { status: 500 }
    );
  }
}
