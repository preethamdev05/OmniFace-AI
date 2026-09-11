import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { organizations, users, organizationMembers, subscriptions, auditLogs } from '@/db/schema';
import { eq } from 'drizzle-orm';
import { authenticateSession } from '@/lib/api-auth';
import { signSessionToken } from '@/lib/auth';

const OnboardingSchema = z.object({
  orgName: z.string().min(2, 'Organization name must be at least 2 characters'),
  orgType: z.enum(['SCHOOL', 'COLLEGE', 'COACHING', 'CORPORATE', 'GYM_EVENT']).default('SCHOOL'),
  contactEmail: z.string().email().optional(),
  adminEmail: z.string().email().optional(),
  adminFullName: z.string().optional(),
  contactPhone: z.string().optional().default(''),
  defaultStartTime: z.string().regex(/^([01]\d|2[0-3]):([0-5]\d)$/).default('09:00'),
  graceMinutes: z.number().int().min(0).max(120).default(15),
  password: z.string().optional(),
});

export async function GET(req: NextRequest) {
  try {
    const user = await authenticateSession(req);
    if (!user) {
      return NextResponse.json(
        { success: false, error: 'Unauthorized: Valid session required.' },
        { status: 401 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database service is unavailable' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database unavailable' }, { status: 500 });
    }

    // Check if user has an existing active organization
    const memberRows = await database
      .select({
        orgId: organizationMembers.organizationId,
        role: organizationMembers.role,
        orgName: organizations.name,
        onboardingCompleted: organizations.onboardingCompleted,
      })
      .from(organizationMembers)
      .innerJoin(organizations, eq(organizationMembers.organizationId, organizations.id))
      .where(eq(organizationMembers.userId, user.userId))
      .limit(1);

    if (memberRows.length === 0) {
      return NextResponse.json({
        success: true,
        onboardingCompleted: false,
      });
    }

    const m = memberRows[0];
    return NextResponse.json({
      success: true,
      onboardingCompleted: m.onboardingCompleted === 1,
      orgId: m.orgId,
      orgName: m.orgName,
      role: m.role,
    });
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Failed to query onboarding state' }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  try {
    const sessionUser = await authenticateSession(req);
    const body = await req.json();
    const parsed = OnboardingSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        { success: false, error: 'Validation failed', details: parsed.error.flatten() },
        { status: 400 }
      );
    }

    const { orgName, orgType, contactPhone, defaultStartTime, graceMinutes } = parsed.data;
    const targetEmail = (parsed.data.adminEmail || parsed.data.contactEmail || sessionUser?.email || '').trim().toLowerCase();
    const targetFullName = (parsed.data.adminFullName || sessionUser?.fullName || 'Administrator').trim();

    if (!targetEmail) {
      return NextResponse.json(
        { success: false, error: 'Valid adminEmail or contactEmail is required.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database service is unavailable. Cannot onboard organization without persistent storage.' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database unavailable' }, { status: 500 });
    }

    let activeUserId = sessionUser?.userId;

    // 1. Create Organization
    const newOrg = await database
      .insert(organizations)
      .values({
        name: orgName.trim(),
        type: orgType,
        tier: 'FREE',
        maxPeople: 25,
        maxDevices: 1,
        contactEmail: targetEmail,
        contactPhone: contactPhone?.trim() || null,
        defaultStartTime,
        graceMinutes,
        onboardingCompleted: 1,
        status: 'ACTIVE',
      })
      .returning();

    const orgId = newOrg[0].id;

    // 2. Find or create user with non-null orgId
    if (!activeUserId) {
      const existingUser = await database
        .select()
        .from(users)
        .where(eq(users.email, targetEmail))
        .limit(1);

      if (existingUser.length > 0) {
        activeUserId = existingUser[0].id;
        await database
          .update(users)
          .set({
            orgId,
            role: 'OWNER',
            updatedAt: new Date(),
          })
          .where(eq(users.id, activeUserId));
      } else {
        const createdUser = await database
          .insert(users)
          .values({
            email: targetEmail,
            fullName: targetFullName,
            orgId,
            role: 'OWNER',
          })
          .returning();
        activeUserId = createdUser[0].id;
      }
    } else {
      await database
        .update(users)
        .set({
          orgId,
          role: 'OWNER',
          updatedAt: new Date(),
        })
        .where(eq(users.id, activeUserId));
    }

    // 3. Create organization member entry as OWNER
    await database
      .insert(organizationMembers)
      .values({
        organizationId: orgId,
        userId: activeUserId,
        role: 'OWNER',
        status: 'ACTIVE',
      })
      .onConflictDoNothing();

    // 4. Create Initial FREE Subscription
    const oneYearLater = new Date();
    oneYearLater.setFullYear(oneYearLater.getFullYear() + 1);

    await database
      .insert(subscriptions)
      .values({
        organizationId: orgId,
        orgId: orgId,
        tier: 'FREE',
        status: 'ACTIVE',
        billingProvider: 'WEBSITE_CUSTOM',
        amountInr: 0,
        peopleLimit: 25,
        deviceLimit: 1,
        validUntil: oneYearLater,
      })
      .onConflictDoNothing();

    // 5. Audit Log
    try {
      await database.insert(auditLogs).values({
        organizationId: orgId,
        userId: activeUserId,
        action: 'ORGANIZATION_ONBOARDED',
        entityType: 'ORGANIZATION',
        entityId: orgId,
        newValues: JSON.stringify({ orgName, orgType, role: 'OWNER', tier: 'FREE' }),
        reason: 'New organization created via self-serve onboarding stepper',
      });
    } catch (_auditErr) {
      // Non-critical audit logging
    }

    // 6. Generate refreshed session token with new orgId and OWNER role
    const newToken = signSessionToken({
      userId: activeUserId,
      email: targetEmail,
      fullName: targetFullName,
      role: 'OWNER',
      orgId,
      orgName: orgName.trim(),
      tier: 'FREE',
    });

    const response = NextResponse.json(
      {
        success: true,
        message: 'Organization onboarded successfully. You are now the OWNER.',
        orgId,
        organization: newOrg[0],
        sessionToken: newToken,
      },
      { status: 201 }
    );

    response.cookies.set({
      name: 'omniface_session',
      value: newToken,
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 7 * 24 * 60 * 60,
    });

    return response;
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Onboarding failed' }, { status: 500 });
  }
}
