import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { devices, auditLogs } from '@/db/schema';
import { eq, and, gt } from 'drizzle-orm';

// IP-based sliding window rate limiter to prevent brute-force attacks on 6-digit OTP
const pairAttempts = new Map<string, { count: number; resetAt: number }>();

function checkRateLimit(ip: string): { allowed: boolean; retryAfterSec?: number } {
  const now = Date.now();
  const windowMs = 60 * 1000; // 1 minute
  const maxAttempts = 5;

  const record = pairAttempts.get(ip);
  if (!record || now > record.resetAt) {
    pairAttempts.set(ip, { count: 1, resetAt: now + windowMs });
    return { allowed: true };
  }

  if (record.count >= maxAttempts) {
    const retryAfterSec = Math.ceil((record.resetAt - now) / 1000);
    return { allowed: false, retryAfterSec };
  }

  record.count++;
  return { allowed: true };
}

export async function POST(req: NextRequest) {
  try {
    // Check IP rate limit
    const clientIp = req.headers.get('x-forwarded-for')?.split(',')[0].trim() || '127.0.0.1';
    const rateCheck = checkRateLimit(clientIp);
    if (!rateCheck.allowed) {
      return NextResponse.json(
        { success: false, error: `Too many pairing attempts. Please retry after ${rateCheck.retryAfterSec} seconds.` },
        { status: 429, headers: { 'Retry-After': String(rateCheck.retryAfterSec) } }
      );
    }

    const body = await req.json();
    const {
      pairingCode,
      deviceIdentifier,
      deviceName,
      hardwareHash,
      appVersion = 'v2.0.0',
      model = 'Android Attendance Kiosk',
    } = body;

    if (!pairingCode || pairingCode.trim().length !== 6) {
      return NextResponse.json(
        { success: false, error: 'Valid 6-digit pairing code is required' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database is not configured; device pairing cannot be verified.' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json(
        { success: false, error: 'Database connection unavailable.' },
        { status: 503 }
      );
    }

    // Verify pairing code exists in devices table and is within 15-minute window
    const matchingCodes = await database
      .select()
      .from(devices)
      .where(
        and(
          eq(devices.pairingCode, pairingCode.trim()),
          gt(devices.pairingCodeExpiresAt, new Date())
        )
      )
      .limit(1);

    if (matchingCodes.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Pairing code is expired or invalid. Please generate a new code from the Web Dashboard.' },
        { status: 400 }
      );
    }

    const matchedDevice = matchingCodes[0];
    const orgId = matchedDevice.organizationId;

    // Enforce device count limits based on organization subscription entitlement
    const { resolveOrgEntitlements } = await import('@/lib/entitlements');
    const entitlements = await resolveOrgEntitlements(orgId);
    const existingActiveDevices = await database
      .select()
      .from(devices)
      .where(and(eq(devices.organizationId, orgId), eq(devices.isPaired, 1)));

    if (existingActiveDevices.length >= entitlements.maxDevices) {
      return NextResponse.json(
        {
          success: false,
          error: `Device limit reached (${existingActiveDevices.length}/${entitlements.maxDevices}). Upgrade your plan to pair more devices.`,
          code: 'ENTITLEMENT_DEVICE_LIMIT_EXCEEDED',
        },
        { status: 403 }
      );
    }

    const deviceId = deviceIdentifier || `OMNIFACE-${crypto.randomBytes(4).toString('hex').toUpperCase()}`;
    const name = deviceName || `${model} (${deviceId.slice(-4)})`;
    const hwHash = hardwareHash || crypto.createHash('sha256').update(deviceId + Date.now()).digest('hex');
    const deviceToken = `omni_hw_${crypto.randomBytes(32).toString('hex')}`;

    // Update matched row with hardware credentials and mark as paired
    await database
      .update(devices)
      .set({
        deviceIdentifier: deviceId,
        deviceName: name,
        deviceToken,
        hardwareHash: hwHash,
        appVersion,
        status: 'ONLINE',
        isPaired: 1,
        pairedAt: new Date(),
        pairingCode: null,
        pairingCodeExpiresAt: null,
        updatedAt: new Date(),
      })
      .where(eq(devices.id, matchedDevice.id));

    // Write immutable audit log
    await database.insert(auditLogs).values({
      organizationId: orgId,
      action: 'DEVICE_PAIRED',
      entityType: 'DEVICE',
      entityId: deviceId,
      newValues: JSON.stringify({ deviceName: name, hardwareHash: hwHash.substring(0, 16) + '...', appVersion }),
      reason: 'Hardware kiosk successfully paired via 6-digit OTP / scannable QR token',
    });

    return NextResponse.json({
      success: true,
      status: 'paired',
      message: 'Device paired successfully. Hardware-bound device token issued.',
      deviceToken,
      deviceId,
      deviceName: name,
      orgId,
      pairedAt: new Date().toISOString(),
      syncEndpoint: 'https://omniface.vercel.app/api/v1/attendance/sync',
    }, { status: 200 });
  } catch (err: any) {
    console.error('Device pairing failed:', err);
    return NextResponse.json(
      { success: false, error: err?.message || 'Device pairing failed' },
      { status: 500 }
    );
  }
}
