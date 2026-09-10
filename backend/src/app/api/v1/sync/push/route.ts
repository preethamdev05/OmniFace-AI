import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents, devices } from '@/db/schema';
import { eq } from 'drizzle-orm';
import { authenticateDevice } from '@/lib/api-auth';

const AttendanceRecordSchema = z.object({
  recordId: z.string().min(1),
  studentRoll: z.string().min(1),
  studentName: z.string().min(1),
  timestamp: z.number().int().positive(),
  sessionDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  confidencePct: z.number().int().min(0).max(100),
  securityTier: z.string(),
  sha256Hash: z.string().length(64),
  kioskId: z.string().default('kiosk-alpha'),
});

const SyncPushPayloadSchema = z.object({
  orgId: z.string().uuid().optional(),
  kioskApiKey: z.string().min(8).optional(),
  deviceModel: z.string().optional(),
  records: z.array(AttendanceRecordSchema),
});

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.json();
    const parseResult = SyncPushPayloadSchema.safeParse(rawBody);

    if (!parseResult.success) {
      return NextResponse.json(
        {
          success: false,
          error: 'Invalid sync payload',
          details: parseResult.error.flatten(),
        },
        { status: 400 }
      );
    }

    const { kioskApiKey, records } = parseResult.data;

    let orgId: string | null = null;
    let deviceId: string = 'kiosk-device';

    // 1. Try header-based device authentication
    const device = await authenticateDevice(req);
    if (device) {
      orgId = device.organizationId;
      deviceId = device.deviceId;
    } else if (kioskApiKey && isDbConfigured()) {
      // 2. Validate kioskApiKey against registered devices in database
      const database = getDb();
      if (database) {
        const found = await database
          .select({
            id: devices.id,
            organizationId: devices.organizationId,
            deviceIdentifier: devices.deviceIdentifier,
            status: devices.status,
          })
          .from(devices)
          .where(eq(devices.deviceToken, kioskApiKey))
          .limit(1);

        if (found.length > 0 && found[0].status !== 'REVOKED') {
          orgId = found[0].organizationId;
          deviceId = found[0].deviceIdentifier;
        }
      }
    }

    // Fail closed: no unauthenticated access or DEFAULT_ORG_ID fallback
    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'Unauthorized kiosk key or invalid device token' },
        { status: 401 }
      );
    }

    // Process & store records (with ON CONFLICT DO NOTHING on eventId)
    const validRecords = records.filter((rec) => {
      return rec.sha256Hash && rec.sha256Hash.length === 64;
    });

    if (isDbConfigured() && validRecords.length > 0) {
      const database = getDb();
      if (database) {
        try {
          for (const rec of validRecords) {
            await database
              .insert(attendanceEvents)
              .values({
                organizationId: orgId,
                eventId: rec.recordId,
                recordId: rec.recordId,
                studentRoll: rec.studentRoll,
                studentName: rec.studentName,
                timestamp: rec.timestamp,
                sessionDate: rec.sessionDate,
                confidencePct: rec.confidencePct,
                securityTier: rec.securityTier,
                sha256Hash: rec.sha256Hash,
                deviceId: rec.kioskId || deviceId,
                status: 'PRESENT',
                offlineFlag: 1,
                serverEvaluated: 1,
              })
              .onConflictDoNothing({ target: attendanceEvents.eventId });
          }
        } catch (dbErr) {
          console.error('PostgreSQL sync push persistence warning:', dbErr);
        }
      }
    }

    return NextResponse.json(
      {
        success: true,
        message: 'Kiosk attendance ledger batch synchronized successfully',
        orgId,
        receivedCount: records.length,
        processedCount: validRecords.length,
        serverTimestamp: Date.now(),
      },
      { status: 200 }
    );
  } catch (error: any) {
    return NextResponse.json(
      {
        success: false,
        error: 'Sync processing failed',
        message: error?.message || 'Internal Server Error',
      },
      { status: 500 }
    );
  }
}
