import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { users, organizationMembers, auditLogs } from '@/db/schema';
import { eq, and, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';
import { UserRole } from '@/lib/auth';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req);
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const orgId = auth.user.orgId;

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'No organization affiliated with session' },
        { status: 403 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({ success: true, staff: [], totalCount: 0 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: true, staff: [], totalCount: 0 });
    }

    const members = await database
      .select({
        membershipId: organizationMembers.id,
        userId: users.id,
        email: users.email,
        fullName: users.fullName,
        avatarUrl: users.avatarUrl,
        role: organizationMembers.role,
        status: organizationMembers.status,
        joinedAt: organizationMembers.joinedAt,
      })
      .from(organizationMembers)
      .innerJoin(users, eq(organizationMembers.userId, users.id))
      .where(eq(organizationMembers.organizationId, orgId))
      .orderBy(desc(organizationMembers.joinedAt));

    return NextResponse.json({
      success: true,
      staff: members,
      totalCount: members.length,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch staff' },
      { status: 500 }
    );
  }
}

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

    if (!email || typeof email !== 'string') {
      return NextResponse.json({ success: false, error: 'Valid staff email is required' }, { status: 400 });
    }

    const validRoles: UserRole[] = ['OWNER', 'ADMIN', 'TEACHER', 'VIEWER'];
    if (!validRoles.includes(role)) {
      return NextResponse.json(
        { success: false, error: `Invalid role. Must be one of: ${validRoles.join(', ')}` },
        { status: 400 }
      );
    }

    // Only OWNER can grant OWNER or ADMIN roles
    if ((role === 'OWNER' || role === 'ADMIN') && user.role !== 'OWNER') {
      return NextResponse.json(
        { success: false, error: 'Only an organization OWNER can assign OWNER or ADMIN privileges.' },
        { status: 403 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    // 1. Find or create user record
    let targetUserId: string;
    const existing = await database
      .select()
      .from(users)
      .where(eq(users.email, email.trim().toLowerCase()))
      .limit(1);

    if (existing.length > 0) {
      targetUserId = existing[0].id;
    } else {
      const insertedUser = await database
        .insert(users)
        .values({
          email: email.trim().toLowerCase(),
          fullName: fullName || email.split('@')[0],
          orgId,
          role,
        })
        .returning({ id: users.id });
      targetUserId = insertedUser[0].id;
    }

    // 2. Add or update membership record
    const existingMembership = await database
      .select()
      .from(organizationMembers)
      .where(
        and(
          eq(organizationMembers.organizationId, orgId),
          eq(organizationMembers.userId, targetUserId)
        )
      )
      .limit(1);

    if (existingMembership.length > 0) {
      await database
        .update(organizationMembers)
        .set({ role, status: 'ACTIVE' })
        .where(eq(organizationMembers.id, existingMembership[0].id));
    } else {
      await database.insert(organizationMembers).values({
        organizationId: orgId,
        userId: targetUserId,
        role,
        status: 'ACTIVE',
      });
    }

    // 3. Audit log
    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'STAFF_ADDED',
      entityType: 'STAFF',
      entityId: `${email} (${role})`,
      reason: `Staff member invited/updated with role ${role}`,
    });

    return NextResponse.json({
      success: true,
      message: `Staff member ${email} successfully assigned role ${role}`,
      staff: {
        userId: targetUserId,
        email,
        fullName: fullName || email.split('@')[0],
        role,
        status: 'ACTIVE',
      },
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to add staff member' },
      { status: 500 }
    );
  }
}

export async function DELETE(req: NextRequest) {
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

    const { searchParams } = new URL(req.url);
    const userId = searchParams.get('userId');
    const membershipId = searchParams.get('membershipId');

    if (!userId && !membershipId) {
      return NextResponse.json({ success: false, error: 'userId or membershipId required' }, { status: 400 });
    }

    // Prevent self-revocation
    if (userId === user.userId) {
      return NextResponse.json(
        { success: false, error: 'Cannot revoke your own administrative membership.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 503 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    if (membershipId) {
      await database
        .delete(organizationMembers)
        .where(
          and(
            eq(organizationMembers.id, membershipId),
            eq(organizationMembers.organizationId, orgId)
          )
        );
    } else if (userId) {
      await database
        .delete(organizationMembers)
        .where(
          and(
            eq(organizationMembers.userId, userId),
            eq(organizationMembers.organizationId, orgId)
          )
        );
    }

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'STAFF_REMOVED',
      entityType: 'STAFF',
      entityId: userId || membershipId || '',
      reason: 'Staff membership revoked by administrator',
    });

    return NextResponse.json({
      success: true,
      message: 'Staff member removed from organization successfully.',
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to remove staff member' },
      { status: 500 }
    );
  }
}
