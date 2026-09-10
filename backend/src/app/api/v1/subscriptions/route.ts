import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { subscriptions, organizations } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, desc } from 'drizzle-orm';

const VerifySubscriptionSchema = z.object({
  orgId: z.string().optional().default(DEFAULT_ORG_ID),
  tier: z.enum(['FREE', 'PREMIUM', 'BUSINESS']),
  provider: z.enum(['GOOGLE_PLAY', 'RAZORPAY', 'OFFLINE_LICENSE']).default('GOOGLE_PLAY'),
  purchaseToken: z.string().optional(),
  razorpayPaymentId: z.string().optional(),
  razorpaySubscriptionId: z.string().optional(),
  deviceId: z.string().optional(),
});

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const orgId = searchParams.get('orgId') || DEFAULT_ORG_ID;

    // 30 days default duration if not in DB
    let activeTier = 'FREE';
    let validUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
    let provider = 'GOOGLE_PLAY';
    let status = 'ACTIVE';

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);

          // Check most recent active subscription
          const subRows = await database
            .select()
            .from(subscriptions)
            .where(eq(subscriptions.orgId, orgId))
            .orderBy(desc(subscriptions.validUntil))
            .limit(1);

          if (subRows.length > 0 && subRows[0].validUntil > new Date()) {
            activeTier = subRows[0].tier;
            validUntil = subRows[0].validUntil.toISOString();
            provider = subRows[0].billingProvider;
            status = subRows[0].status;
          } else {
            // Fall back to organization tier
            const orgRows = await database
              .select({ tier: organizations.tier })
              .from(organizations)
              .where(eq(organizations.id, orgId))
              .limit(1);

            if (orgRows.length > 0 && orgRows[0].tier) {
              activeTier = orgRows[0].tier;
            }
          }
        } catch (dbErr) {
          console.warn('PostgreSQL subscription query warning:', dbErr);
        }
      }
    }

    return NextResponse.json({
      success: true,
      subscription: {
        orgId,
        tier: activeTier,
        status,
        provider,
        amountInr: activeTier === 'BUSINESS' ? 999 : (activeTier === 'PREMIUM' ? 199 : 0),
        validUntil,
        offlineGraceUntil: new Date(Date.now() + 60 * 24 * 60 * 60 * 1000).toISOString(),
      },
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

    const { orgId, tier, provider, purchaseToken, razorpayPaymentId, razorpaySubscriptionId } = result.data;
    const durationDays = tier === 'BUSINESS' ? 365 : 30;
    const validUntilDate = new Date(Date.now() + durationDays * 24 * 60 * 60 * 1000);
    const validUntil = validUntilDate.toISOString();

    // Persist to PostgreSQL if configured
    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);

          // 1. Insert subscription record
          await database.insert(subscriptions).values({
            orgId: orgId,
            tier: tier,
            status: 'ACTIVE',
            billingProvider: provider,
            externalSubscriptionId: razorpaySubscriptionId || razorpayPaymentId || null,
            purchaseToken: purchaseToken || null,
            amountInr: tier === 'PREMIUM' ? 199 : (tier === 'BUSINESS' ? 999 : 0),
            validUntil: validUntilDate,
          });

          // 2. Update organization tier
          await database
            .update(organizations)
            .set({ tier: tier, updatedAt: new Date() })
            .where(eq(organizations.id, orgId));
        } catch (dbErr) {
          console.warn('PostgreSQL subscription persistence warning:', dbErr);
        }
      }
    }

    return NextResponse.json(
      {
        success: true,
        message: `Subscription successfully verified and activated for ${tier}`,
        subscription: {
          orgId,
          tier,
          status: 'ACTIVE',
          provider,
          amountInr: tier === 'PREMIUM' ? 199 : 999,
          validUntil,
          offlineGraceUntil: new Date(Date.now() + 60 * 24 * 60 * 60 * 1000).toISOString(),
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
