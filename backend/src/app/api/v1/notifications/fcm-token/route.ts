import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { fcmTokens } from '@/db/schema';
import { eq, and } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'VIEWER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const body = await req.json();
    const token = (body.token || body.fcmToken || '').trim();
    const { deviceId, platform = 'ANDROID' } = body;

    if (!token || token.length < 10) {
      return NextResponse.json(
        { success: false, error: 'Valid FCM registration token is required.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        message: 'FCM token registered (sandbox mode).',
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    // Upsert FCM token
    const existing = await database
      .select()
      .from(fcmTokens)
      .where(eq(fcmTokens.token, token.trim()))
      .limit(1);

    if (existing.length > 0) {
      await database
        .update(fcmTokens)
        .set({
          organizationId: orgId,
          userId: user.userId,
          deviceId: deviceId || existing[0].deviceId,
          platform,
          updatedAt: new Date(),
        })
        .where(eq(fcmTokens.id, existing[0].id));
    } else {
      await database.insert(fcmTokens).values({
        organizationId: orgId,
        userId: user.userId,
        deviceId: deviceId || null,
        token: token.trim(),
        platform,
      });
    }

    return NextResponse.json({
      success: true,
      message: 'FCM push registration token saved successfully.',
    });
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Failed to save FCM token' }, { status: 500 });
  }
}

export async function DELETE(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'VIEWER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }

    const { searchParams } = new URL(req.url);
    const token = searchParams.get('token');

    if (!token) {
      return NextResponse.json(
        { success: false, error: 'Token query parameter is required for deletion.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({ success: true, message: 'FCM token removed (sandbox mode).' });
    }

    const database = getDb();
    if (database) {
      await database.delete(fcmTokens).where(eq(fcmTokens.token, token.trim()));
    }

    return NextResponse.json({
      success: true,
      message: 'FCM token unregistered successfully.',
    });
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Failed to remove FCM token' }, { status: 500 });
  }
}
