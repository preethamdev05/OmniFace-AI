import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { organizations, organizationMembers, users, auditLogs } from '@/db/schema';
import { eq, and } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

const TransferOwnershipSchema = z.object({
  newOwnerUserId: z.string().uuid('Invalid user ID format').optional(),
  newOwnerEmail: z.string().email('Invalid email format').optional(),
}).refine((data) => data.newOwnerUserId || data.newOwnerEmail, {
  message: 'Either newOwnerUserId or newOwnerEmail must be provided',
});

export async function POST(req: NextRequest) {
  try {
    // 1. Enforce OWNER-only authorization
    const auth = await requireSession(req, 'OWNER');
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

    if (user.role !== 'OWNER') {
      return NextResponse.json(
        { success: false, error: 'Forbidden: Only the current organization OWNER can transfer ownership.' },
        { status: 403 }
      );
    }

    const body = await req.json();
    const parsed = TransferOwnershipSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        { success: false, error: parsed.error.issues[0]?.message || 'Validation failed' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database is not configured; ownership transfer cannot be processed.' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json(
        { success: false, error: 'Database connection failed' },
        { status: 500 }
      );
    }

    const { newOwnerUserId, newOwnerEmail } = parsed.data;

    // 2. Resolve target user
    let targetUser;
    if (newOwnerUserId) {
      const rows = await database.select().from(users).where(eq(users.id, newOwnerUserId)).limit(1);
      targetUser = rows[0];
    } else if (newOwnerEmail) {
      const rows = await database.select().from(users).where(eq(users.email, newOwnerEmail.trim().toLowerCase())).limit(1);
      targetUser = rows[0];
    }

    if (!targetUser) {
      return NextResponse.json(
        { success: false, error: 'Target user does not exist' },
        { status: 404 }
      );
    }

    // 3. Prevent transferring to self
    if (targetUser.id === user.userId) {
      return NextResponse.json(
        { success: false, error: 'You are already the OWNER of this organization.' },
        { status: 400 }
      );
    }

    // 4. Verify target user is an active member of this organization
    const membershipRows = await database
      .select()
      .from(organizationMembers)
      .where(
        and(
          eq(organizationMembers.organizationId, orgId),
          eq(organizationMembers.userId, targetUser.id)
        )
      )
      .limit(1);

    if (membershipRows.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Target user must be a member of this organization before ownership can be transferred.' },
        { status: 400 }
      );
    }

    const targetMembership = membershipRows[0];
    if (targetMembership.status !== 'ACTIVE') {
      return NextResponse.json(
        { success: false, error: `Target user membership status is ${targetMembership.status}; must be ACTIVE.` },
        { status: 400 }
      );
    }

    // 5. Transactionally promote target to OWNER and demote current to ADMIN
    await database.transaction(async (tx) => {
      // Elevate target member to OWNER
      await tx
        .update(organizationMembers)
        .set({ role: 'OWNER' })
        .where(eq(organizationMembers.id, targetMembership.id));

      await tx
        .update(users)
        .set({ role: 'OWNER' })
        .where(eq(users.id, targetUser.id));

      // Demote current owner to ADMIN
      await tx
        .update(organizationMembers)
        .set({ role: 'ADMIN' })
        .where(
          and(
            eq(organizationMembers.organizationId, orgId),
            eq(organizationMembers.userId, user.userId)
          )
        );

      await tx
        .update(users)
        .set({ role: 'ADMIN' })
        .where(
          and(
            eq(users.id, user.userId),
            eq(users.orgId, orgId)
          )
        );

      // Audit log the transfer
      await tx.insert(auditLogs).values({
        organizationId: orgId,
        userId: user.userId,
        action: 'OWNERSHIP_TRANSFERRED',
        entityType: 'ORGANIZATION',
        entityId: orgId,
        reason: `Ownership transferred from ${user.email} (${user.userId}) to ${targetUser.email} (${targetUser.id})`,
      });
    });

    return NextResponse.json({
      success: true,
      message: `Organization ownership successfully transferred to ${targetUser.email}. Your role is now ADMIN.`,
      newOwner: {
        userId: targetUser.id,
        email: targetUser.email,
        fullName: targetUser.fullName,
        role: 'OWNER',
      },
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to transfer organization ownership' },
      { status: 500 }
    );
  }
}
