import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { subscriptions, organizations, faceTemplates, students, invoices } from '@/db/schema';
import { eq, desc } from 'drizzle-orm';
import { requireSession, authenticateDevice, authenticateSession } from '@/lib/api-auth';
import { verifyGooglePlayPurchase } from '@/lib/google-play-billing';

const VerifySubscriptionSchema = z.object({
  tier: z.enum(['FREE', 'PREMIUM', 'PRO', 'INSTITUTION']),
  provider: z.enum(['GOOGLE_PLAY', 'WEBSITE_CUSTOM', 'RAZORPAY', 'OFFLINE_LICENSE']).default('GOOGLE_PLAY'),
  purchaseToken: z.string().optional(),
  razorpayPaymentId: z.string().optional(),
  razorpaySubscriptionId: z.string().optional(),
  deviceId: z.string().optional(),
});

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'VIEWER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const orgId = auth.user.orgId;
    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'User does not belong to an organization. Please complete onboarding.' },
        { status: 400 }
      );
    }

    // Plans: Free (25), Premium (250, ₹199), Pro (500, ₹349), Institution (500+, Custom)
    let activeTier = 'FREE';
    let validUntilDate = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
    let graceUntilDate = new Date(validUntilDate.getTime() + 14 * 24 * 60 * 60 * 1000); // 14-day grace period
    let provider = 'GOOGLE_PLAY';
    let status = 'ACTIVE';
    let enrolledCount = 0;
    let invoiceList: any[] = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
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

          // Query organization invoices
          invoiceList = await database
            .select({
              id: invoices.id,
              invoiceNumber: invoices.invoiceNumber,
              amountInr: invoices.amountInr,
              status: invoices.status,
              dueDate: invoices.dueDate,
              paidAt: invoices.paidAt,
              createdAt: invoices.createdAt,
            })
            .from(invoices)
            .where(eq(invoices.organizationId, orgId))
            .orderBy(desc(invoices.createdAt))
            .limit(10);
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
        case 'PRO': return { peopleLimit: 500, priceInr: 349, billing: 'Google Play' };
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
        message: `${enrolledCount} / 250 capacity reached. Upgrade to Pro (₹349/mo) for 500 people & priority support.`,
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
      invoices: invoiceList,
      availablePlans: [
        { tier: 'FREE', title: 'Free Starter', limit: '25 users', priceInr: 0, billing: '—', allowsDashboard: false },
        { tier: 'PREMIUM', title: 'Premium', limit: '250 users', priceInr: 199, billing: 'Google Play', allowsDashboard: true },
        { tier: 'PRO', title: 'Pro', limit: '500 users', priceInr: 349, billing: 'Google Play', allowsDashboard: true },
        {
          tier: 'INSTITUTION',
          title: 'Institution',
          limit: '500+ users',
          priceInr: null,
          billing: 'Custom Pricing / Sales',
          allowsDashboard: true,
          features: [
            '500+ users',
            'Unlimited devices',
            'Multi-admin',
            'Departments',
            'Classes',
            'Staff roles',
            'Audit logs',
            'Advanced reporting',
            'Custom onboarding',
            'Custom pricing',
          ],
        },
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
    // Dual authentication: either web user session (ADMIN) or paired Android device
    let orgId: string | null = null;
    let isOwner = false;

    const session = await authenticateSession(req);
    if (session) {
      if (session.role !== 'ADMIN' && session.role !== 'OWNER') {
        return NextResponse.json(
          { success: false, error: 'Forbidden: ADMIN or OWNER role required' },
          { status: 403 }
        );
      }
      orgId = session.orgId;
      isOwner = session.role === 'OWNER';
    } else {
      const device = await authenticateDevice(req);
      if (device) {
        orgId = device.organizationId;
      }
    }

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'Unauthorized: Valid administrative session or registered kiosk device token required' },
        { status: 401 }
      );
    }

    const rawBody = await req.json();
    const result = VerifySubscriptionSchema.safeParse(rawBody);

    if (!result.success) {
      return NextResponse.json(
        { success: false, error: 'Invalid subscription verification payload', details: result.error.flatten() },
        { status: 400 }
      );
    }

    let { tier, provider, purchaseToken, razorpayPaymentId, razorpaySubscriptionId } = result.data;

    // Institution plan requires sales agreement or OWNER activation
    if (tier === 'INSTITUTION' && !isOwner) {
      return NextResponse.json(
        {
          success: false,
          error: 'Institution tier (500+ seats) requires customized sales agreement. Please submit an institutional inquiry.',
        },
        { status: 400 }
      );
    }

    // Verify Google Play purchase token
    let verifiedOrderId: string | undefined = undefined;
    if (provider === 'GOOGLE_PLAY' && tier !== 'FREE') {
      if (!purchaseToken) {
        return NextResponse.json(
          {
            success: false,
            error: 'Google Play purchase token is required to verify subscription.',
          },
          { status: 400 }
        );
      }

      const verification = await verifyGooglePlayPurchase(purchaseToken, tier);
      if (!verification.valid) {
        return NextResponse.json(
          {
            success: false,
            error: verification.error || 'Google Play purchase token verification failed.',
          },
          { status: 400 }
        );
      }
      verifiedOrderId = verification.orderId;
    } else if (
      tier !== 'FREE' &&
      !purchaseToken &&
      !razorpayPaymentId &&
      !razorpaySubscriptionId &&
      !isOwner
    ) {
      return NextResponse.json(
        {
          success: false,
          error: 'Proof of purchase (Google Play purchase token or transaction ID) is required to activate paid tier.',
        },
        { status: 400 }
      );
    }

    const validUntilDate = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
    const graceUntilDate = new Date(validUntilDate.getTime() + 14 * 24 * 60 * 60 * 1000); // 14-day grace

    const priceMap: Record<string, number> = {
      FREE: 0,
      PREMIUM: 199,
      PRO: 349,
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
          // 1. Insert subscription record
          await database.insert(subscriptions).values({
            organizationId: orgId,
            orgId: orgId,
            tier,
            status: 'ACTIVE',
            billingProvider: provider,
            externalSubscriptionId: verifiedOrderId || razorpaySubscriptionId || razorpayPaymentId || purchaseToken || null,
            purchaseToken: purchaseToken || null,
            amountInr: paidAmount,
            peopleLimit,
            maxPeople: peopleLimit,
            deviceLimit: tier === 'INSTITUTION' ? 100 : (tier === 'PRO' ? 3 : 1),
            maxDevices: tier === 'INSTITUTION' ? 100 : (tier === 'PRO' ? 3 : 1),
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
          orderId: verifiedOrderId,
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
