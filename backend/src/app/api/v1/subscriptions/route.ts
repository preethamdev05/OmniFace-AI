import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { subscriptions, organizations, faceTemplates, students } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, desc } from 'drizzle-orm';

const VerifySubscriptionSchema = z.object({
  orgId: z.string().optional().default(DEFAULT_ORG_ID),
  tier: z.enum(['FREE', 'PREMIUM', 'PRO', 'PROFESSIONAL', 'INSTITUTION', 'BUSINESS']),
  provider: z.enum(['GOOGLE_PLAY', 'WEBSITE_CUSTOM', 'RAZORPAY', 'OFFLINE_LICENSE']).default('GOOGLE_PLAY'),
  purchaseToken: z.string().optional(),
  razorpayPaymentId: z.string().optional(),
  razorpaySubscriptionId: z.string().optional(),
  deviceId: z.string().optional(),
});

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const orgId = searchParams.get('orgId') || DEFAULT_ORG_ID;

    // Plans: Free (25), Premium (250, ₹199), Pro (500, ₹399), Institution (500+, Custom)
    let activeTier = 'PRO';
    let validUntilDate = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
    let graceUntilDate = new Date(validUntilDate.getTime() + 14 * 24 * 60 * 60 * 1000); // 14-day grace period
    let provider = 'GOOGLE_PLAY';
    let status = 'ACTIVE';
    let enrolledCount = 184;

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);

          // Get current student enrollment count
          const studentRows = await database
            .select({ id: students.id })
            .from(students)
            .where(eq(students.organizationId, orgId));
          if (studentRows.length > 0) {
            enrolledCount = studentRows.length;
          } else {
            const templateRows = await database
              .select({ roll: faceTemplates.studentRoll })
              .from(faceTemplates)
              .where(eq(faceTemplates.organizationId, orgId));
            if (templateRows.length > 0) {
              enrolledCount = new Set(templateRows.map((r) => r.roll)).size;
            }
          }

          // Check most recent subscription
          const subRows = await database
            .select()
            .from(subscriptions)
            .where(eq(subscriptions.organizationId, orgId))
            .orderBy(desc(subscriptions.validUntil))
            .limit(1);

          if (subRows.length > 0) {
            const sub = subRows[0];
            activeTier = sub.tier;
            validUntilDate = sub.validUntil;
            graceUntilDate = sub.graceUntil || new Date(sub.validUntil.getTime() + 14 * 24 * 60 * 60 * 1000);
            provider = sub.billingProvider;
            const now = new Date();

            if (now <= validUntilDate) {
              status = 'ACTIVE';
            } else if (now <= graceUntilDate) {
              status = 'GRACE_PERIOD';
            } else {
              status = 'ARCHIVE_READ_ONLY';
            }
          } else {
            // Check organization record
            const orgRows = await database
              .select({ tier: organizations.tier, status: organizations.status, gracePeriodEnd: organizations.gracePeriodEnd })
              .from(organizations)
              .where(eq(organizations.id, orgId))
              .limit(1);

            if (orgRows.length > 0 && orgRows[0].tier) {
              activeTier = orgRows[0].tier;
              if (orgRows[0].status) status = orgRows[0].status;
            }
          }
        } catch (dbErr) {
          console.warn('PostgreSQL subscription query warning:', dbErr);
        }
      }
    }

    // Normalize tier name (backward compatibility)
    if (activeTier === 'PROFESSIONAL') activeTier = 'PRO';
    if (activeTier === 'BUSINESS') activeTier = 'INSTITUTION';

    const getPlanLimits = (tier: string) => {
      switch (tier) {
        case 'FREE': return { peopleLimit: 25, priceInr: 0, billing: 'None' };
        case 'PREMIUM': return { peopleLimit: 250, priceInr: 199, billing: 'Google Play' };
        case 'PRO': return { peopleLimit: 500, priceInr: 399, billing: 'Google Play' };
        case 'INSTITUTION': return { peopleLimit: 500, priceInr: 0, isCustom: true, billing: 'Website / Sales' };
        default: return { peopleLimit: 25, priceInr: 0, billing: 'None' };
      }
    };

    const limits = getPlanLimits(activeTier);

    // Upgrade funnel alerts (247 / 250 warning, 500 / 500 institution trigger)
    let upgradeAlert = null;
    if (activeTier === 'FREE' && enrolledCount >= 20) {
      upgradeAlert = {
        level: 'WARNING',
        message: `${enrolledCount} / 25 capacity reached. Upgrade to Premium (₹199/mo) for 250 people & cloud sync.`,
        targetPlan: 'PREMIUM',
      };
    } else if (activeTier === 'PREMIUM' && enrolledCount >= 240) {
      upgradeAlert = {
        level: 'WARNING',
        message: `${enrolledCount} / 250 capacity reached. Upgrade to Pro (₹399/mo) for 500 people & priority support.`,
        targetPlan: 'PRO',
      };
    } else if (activeTier === 'PRO' && enrolledCount >= 490) {
      upgradeAlert = {
        level: 'CRITICAL',
        message: `${enrolledCount} / 500 capacity reached. Contact sales for custom Institution deployment (500+ seats).`,
        targetPlan: 'INSTITUTION',
      };
    }

    return NextResponse.json({
      success: true,
      subscription: {
        orgId,
        tier: activeTier,
        status,
        provider,
        amountInr: limits.priceInr,
        peopleLimit: limits.peopleLimit,
        enrolledCount,
        validUntil: validUntilDate.toISOString(),
        graceUntil: graceUntilDate.toISOString(),
        graceDaysRemaining: Math.max(0, Math.ceil((graceUntilDate.getTime() - Date.now()) / (24 * 60 * 60 * 1000))),
        isReadOnlyArchive: status === 'ARCHIVE_READ_ONLY',
        upgradeAlert,
      },
      availablePlans: [
        { tier: 'FREE', title: 'Free Starter', limit: '25 people', priceInr: 0, billing: '—', allowsDashboard: false },
        { tier: 'PREMIUM', title: 'Premium', limit: '250 people', priceInr: 199, billing: 'Google Play', allowsDashboard: true },
        { tier: 'PRO', title: 'Pro', limit: '500 people', priceInr: 399, billing: 'Google Play', allowsDashboard: true },
        { tier: 'INSTITUTION', title: 'Institution', limit: '500+ people', priceInr: null, billing: 'Website / Sales', allowsDashboard: true },
      ],
    }, { status: 200 });
  } catch (error: any) {
    return NextResponse.json(
      { success: false, error: error?.message || 'Internal Server Error' },
      { status: 500 }
    );
  }
}

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.json();
    const result = VerifySubscriptionSchema.safeParse(rawBody);

    if (!result.success) {
      return NextResponse.json(
        { success: false, error: 'Invalid subscription verification payload', details: result.error.flatten() },
        { status: 400 }
      );
    }

    let { orgId, tier, provider, purchaseToken, razorpayPaymentId, razorpaySubscriptionId } = result.data;

    // Normalize
    if (tier === 'PROFESSIONAL') tier = 'PRO';
    if (tier === 'BUSINESS') tier = 'INSTITUTION';

    const validUntilDate = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
    const graceUntilDate = new Date(validUntilDate.getTime() + 14 * 24 * 60 * 60 * 1000); // 14-day grace

    const priceMap: Record<string, number> = {
      FREE: 0,
      PREMIUM: 199,
      PRO: 399,
      INSTITUTION: 1499,
    };
    const peopleLimitMap: Record<string, number> = {
      FREE: 25,
      PREMIUM: 250,
      PRO: 500,
      INSTITUTION: 5000,
    };

    const paidAmount = priceMap[tier] || 0;
    const peopleLimit = peopleLimitMap[tier] || 25;

    // Persist to PostgreSQL
    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);

          // 1. Insert subscription record
          await database.insert(subscriptions).values({
            organizationId: orgId,
            tier,
            status: 'ACTIVE',
            billingProvider: provider,
            externalSubscriptionId: razorpaySubscriptionId || razorpayPaymentId || purchaseToken || null,
            purchaseToken: purchaseToken || null,
            amountInr: paidAmount,
            peopleLimit,
            validUntil: validUntilDate,
            graceUntil: graceUntilDate,
          });

          // 2. Update organization tier and status
          await database
            .update(organizations)
            .set({
              tier,
              status: 'ACTIVE',
              maxPeople: peopleLimit,
              updatedAt: new Date(),
            })
            .where(eq(organizations.id, orgId));
        } catch (dbErr) {
          console.warn('PostgreSQL subscription persistence warning:', dbErr);
        }
      }
    }

    return NextResponse.json(
      {
        success: true,
        message: `Subscription successfully activated for ${tier}`,
        subscription: {
          orgId,
          tier,
          status: 'ACTIVE',
          provider,
          amountInr: paidAmount,
          peopleLimit,
          validUntil: validUntilDate.toISOString(),
          graceUntil: graceUntilDate.toISOString(),
        },
      },
      { status: 200 }
    );
  } catch (error: any) {
    return NextResponse.json(
      { success: false, error: error?.message || 'Internal Server Error' },
      { status: 500 }
    );
  }
}
