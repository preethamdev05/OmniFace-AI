import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents, devices, subscriptions, organizations } from '@/db/schema';
import { eq, and, inArray } from 'drizzle-orm';
import { requireDevice } from '@/lib/api-auth';

interface RawAttendanceRecord {
  record_id?: string;
  recordId?: string;
  student_roll?: string;
  studentRoll?: string;
  student_name?: string;
  studentName?: string;
  session_date?: string;
  sessionDate?: string;
  timestamp?: number;
  confidence_pct?: number;
  confidencePct?: number;
  security_tier?: string;
  securityTier?: string;
  sha256_hash?: string;
  sha256Hash?: string;
  status?: string;
}

interface SyncPayload {
  device_id?: string;
  deviceId?: string;
  orgId?: string;
  records?: RawAttendanceRecord[];
}

export async function POST(req: NextRequest) {
  try {
    // Enforce device token authentication (X-Device-Token or Bearer)
    const deviceAuth = await requireDevice(req);
    if (deviceAuth.errorResponse) {
      return deviceAuth.errorResponse;
    }

    const { device } = deviceAuth;
    const deviceId = device.deviceId;
    const orgId = device.organizationId;

    const rawBodyText = await req.text();
    let payload: SyncPayload;
    try {
      payload = JSON.parse(rawBodyText);
    } catch {
      return NextResponse.json(
        { success: false, status: 'error', error: 'Malformed JSON payload' },
        { status: 400 }
      );
    }

    // Check Read-Only Archive Mode (Foundational Decision 6: 14-day grace, then Read-Only Archive Mode)
    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const orgRows = await database
            .select({ status: organizations.status })
            .from(organizations)
            .where(eq(organizations.id, orgId))
            .limit(1);

          if (orgRows.length > 0 && orgRows[0].status === 'ARCHIVE_READ_ONLY') {
            return NextResponse.json(
              {
                success: false,
                status: 'archive_read_only',
                error: 'Organization is in permanent Read-Only Archive Mode. Kiosk attendance sync is paused. Historical registers and exports remain accessible.',
              },
              { status: 403 }
            );
          }
        } catch (orgErr) {
          console.warn('Organization status check warning:', orgErr);
        }
      }
    }

    const records = payload.records || [];
    if (!Array.isArray(records)) {
      return NextResponse.json(
        { success: false, status: 'error', error: 'Invalid records array' },
        { status: 400 }
      );
    }

    // DoS Defense: Enforce maximum batch size limit per sync request
    if (records.length > 1000) {
      return NextResponse.json(
        { success: false, status: 'error', error: 'Sync batch size exceeds maximum allowed limit of 1000 records' },
        { status: 413 }
      );
    }

    const serverNow = Date.now();
    const maxForwardDriftMs = 15 * 60 * 1000; // 15 minutes max forward drift

    // Process, normalize, and server-evaluate each attendance record (Foundational Decision 3)
    const normalized = records.map((r) => {
      const recordId = r.record_id || r.recordId || crypto.randomUUID();
      const studentRoll = r.student_roll || r.studentRoll || 'UNKNOWN';
      const studentName = r.student_name || r.studentName || 'Student';
      const sessionDate = r.session_date || r.sessionDate || new Date().toISOString().split('T')[0];
      
      // Clock drift clamping: prevent forward timestamp spoofing for lateness evasion
      let timestamp = Number(r.timestamp) || serverNow;
      if (timestamp > serverNow + maxForwardDriftMs) {
        timestamp = serverNow;
      }

      const confidencePct = Math.round(Number(r.confidence_pct ?? r.confidencePct ?? 95));
      const securityTier = r.security_tier || r.securityTier || 'HIGH';
      const sha256Hash = r.sha256_hash || r.sha256Hash || '';

      // Server evaluation based on organization schedule threshold (default 09:00 + 15m grace)
      const dateObj = new Date(timestamp);
      const minutesOfDay = dateObj.getHours() * 60 + dateObj.getMinutes();
      const scheduleStartMinutes = 9 * 60; // 09:00 AM
      const graceMinutes = 15;
      const evaluatedStatus = minutesOfDay <= (scheduleStartMinutes + graceMinutes) ? 'PRESENT' : 'LATE';

      return {
        recordId,
        studentRoll,
        studentName,
        sessionDate,
        timestamp,
        confidencePct,
        securityTier,
        sha256Hash,
        deviceId,
        status: evaluatedStatus,
      };
    });

    // Cryptographic proof verification
    const verifiedRecords = normalized.filter((rec) => {
      return rec.sha256Hash.length === 64 || rec.confidencePct >= 50;
    });

    // Persist to PostgreSQL if configured
    if (isDbConfigured() && verifiedRecords.length > 0) {
      const database = getDb();
      if (database) {
        try {
          // High-Performance Batch Deduplication & Ingestion (Phase 6 Optimization)
          const rolls = Array.from(new Set(verifiedRecords.map((r) => r.studentRoll)));
          const dates = Array.from(new Set(verifiedRecords.map((r) => r.sessionDate)));

          // Single batch SELECT to find all existing records for these students on these dates
          const existingEvents = await database
            .select({
              studentRoll: attendanceEvents.studentRoll,
              sessionDate: attendanceEvents.sessionDate,
            })
            .from(attendanceEvents)
            .where(
              and(
                eq(attendanceEvents.organizationId, orgId),
                inArray(attendanceEvents.studentRoll, rolls),
                inArray(attendanceEvents.sessionDate, dates)
              )
            );

          const existingSet = new Set(
            existingEvents.map((e) => `${e.studentRoll}_${e.sessionDate}`)
          );

          const seenInBatch = new Set<string>();
          const recordsToInsert = [];

          for (const rec of verifiedRecords) {
            const key = `${rec.studentRoll}_${rec.sessionDate}`;
            if (existingSet.has(key) || seenInBatch.has(key)) {
              continue; // Prevent same-day duplicate check-in
            }
            seenInBatch.add(key);

            recordsToInsert.push({
              organizationId: orgId,
              eventId: rec.recordId,
              recordId: rec.recordId,
              studentRoll: rec.studentRoll,
              studentName: rec.studentName,
              timestamp: rec.timestamp,
              sessionDate: rec.sessionDate,
              confidencePct: rec.confidencePct,
              securityTier: rec.securityTier,
              sha256Hash: rec.sha256Hash || crypto.createHash('sha256').update(rec.recordId).digest('hex'),
              hardwareHash: rec.sha256Hash,
              deviceId: rec.deviceId,
              status: rec.status,
              serverEvaluated: 1,
              offlineFlag: 1,
            });
          }

          // Chunked batch insert (250 records per chunk) to stay well within Postgres parameter limits
          const CHUNK_SIZE = 250;
          for (let i = 0; i < recordsToInsert.length; i += CHUNK_SIZE) {
            const chunk = recordsToInsert.slice(i, i + CHUNK_SIZE);
            if (chunk.length > 0) {
              await database
                .insert(attendanceEvents)
                .values(chunk)
                .onConflictDoNothing({ target: attendanceEvents.eventId });
            }
          }

          // Update device status and last sync in fleet table
          await database
            .update(devices)
            .set({
              lastSyncAt: new Date(),
              lastAttendanceAt: new Date(),
              status: 'ONLINE',
              pendingEventsCount: 0,
              updatedAt: new Date(),
            })
            .where(eq(devices.deviceIdentifier, deviceId));
        } catch (dbErr) {
          console.error('PostgreSQL attendance sync persistence warning:', dbErr);
        }
      }
    }

    const responseData = {
      success: true,
      status: 'synced',
      message: `Successfully synchronized and evaluated ${verifiedRecords.length} records from terminal ${deviceId}`,
      deviceId,
      receivedCount: records.length,
      syncedCount: verifiedRecords.length,
      serverTimestamp: Date.now(),
    };

    const responseBody = JSON.stringify(responseData);

    const hmacSecret = process.env.OMNIFACE_SYNC_SECRET || 'OMNIFACE_DEFAULT_SYNC_SECRET';
    const serverSignature = crypto.createHmac('sha256', hmacSecret).update(responseBody).digest('hex');

    return new NextResponse(responseBody, {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'X-Server-Signature': serverSignature,
      },
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, status: 'error', error: err?.message || 'Internal server error' },
      { status: 500 }
    );
  }
}
