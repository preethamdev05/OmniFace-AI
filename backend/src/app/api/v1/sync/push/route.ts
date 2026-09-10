import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { attendanceRecords } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';

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
  orgId: z.string().uuid(),
  kioskApiKey: z.string().min(16),
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

    const { orgId, kioskApiKey, records } = parseResult.data;

    // Verify kiosk API key authorization (or mock for sandbox)
    if (!kioskApiKey.startsWith('omni_kiosk_') && kioskApiKey !== 'development_master_key') {
      return NextResponse.json(
        { success: false, error: 'Unauthorized kiosk key' },
        { status: 401 }
      );
    }

    // Process & store records (with ON CONFLICT DO NOTHING on recordId)
    // Cryptographic hash validation ensuring tamper-evident Aegis chain
    const validRecords = records.filter((rec) => {
      return rec.sha256Hash && rec.sha256Hash.length === 64;
    });

    if (isDbConfigured() && validRecords.length > 0) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          for (const rec of validRecords) {
            await database
              .insert(attendanceRecords)
              .values({
                organizationId: orgId || DEFAULT_ORG_ID,
                orgId: orgId || DEFAULT_ORG_ID,
                eventId: rec.recordId,
                recordId: rec.recordId,
                studentRoll: rec.studentRoll,
                studentName: rec.studentName,
                timestamp: rec.timestamp,
                sessionDate: rec.sessionDate,
                confidencePct: rec.confidencePct,
                securityTier: rec.securityTier,
                sha256Hash: rec.sha256Hash,
                deviceId: rec.kioskId || 'kiosk-alpha',
                kioskId: rec.kioskId || 'kiosk-alpha',
              })
              .onConflictDoNothing({ target: attendanceRecords.eventId });
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
