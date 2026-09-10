import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { devices, organizations } from '@/db/schema';
import { ensureDefaultOrganization } from '@/db/helpers';
import { requireSession } from '@/lib/api-auth';

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    // Generate 6-digit numeric one-time code (valid 15 mins)
    const pairingCode = Math.floor(100000 + Math.random() * 900000).toString();
    const expiresAtDate = new Date(Date.now() + 15 * 60 * 1000); // 15 minutes
    const pairingCodeExpiresAt = expiresAtDate.toISOString();

    const tempDeviceId = `kiosk_pending_${crypto.randomBytes(4).toString('hex')}`;
    const syncEndpoint = 'https://omniface.vercel.app/api/v1/devices/pair';

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          await database.insert(devices).values({
            organizationId: orgId,
            deviceIdentifier: tempDeviceId,
            deviceName: `Kiosk Terminal ${pairingCode}`,
            pairingCode,
            pairingCodeExpiresAt: expiresAtDate,
            status: 'OFFLINE',
            isPaired: 0,
            pendingEventsCount: 0,
          });
        } catch (dbErr) {
          console.warn('DB device insert warning:', dbErr);
        }
      }
    }

    // Dual pairing mode: 6-digit code + Scannable QR code payload
    const qrPayload = {
      v: 2,
      pairingCode,
      orgId,
      endpoint: syncEndpoint,
      expiresAt: pairingCodeExpiresAt,
      issuedAt: Date.now(),
    };

    const qrString = `omniface://kiosk/pair?code=${pairingCode}&orgId=${orgId}&config=${encodeURIComponent(JSON.stringify(qrPayload))}`;

    return NextResponse.json({
      success: true,
      pairingCode,
      expiresAt: pairingCodeExpiresAt,
      validMinutes: 15,
      orgId,
      qrString,
      qrPayload,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to generate device pairing code' },
      { status: 500 }
    );
  }
}
