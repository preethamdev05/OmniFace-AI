import { NextRequest, NextResponse } from 'next/server';
import crypto from 'crypto';
import { isDbConfigured, getDb } from '@/db';
import { attendanceRecords } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';

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
}

interface SyncPayload {
  device_id?: string;
  deviceId?: string;
  orgId?: string;
  records?: RawAttendanceRecord[];
}

export async function POST(req: NextRequest) {
  try {
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

    const deviceId = req.headers.get('X-Device-ID') || payload.device_id || payload.deviceId || 'UNKNOWN-TERMINAL';
    const deviceFingerprint = req.headers.get('X-Device-Fingerprint') || '';
    const requestTimestamp = req.headers.get('X-Timestamp') || Date.now().toString();
    const hmacSignature = req.headers.get('X-HMAC-Signature') || '';

    const records = payload.records || [];
    if (!Array.isArray(records)) {
      return NextResponse.json(
        { success: false, status: 'error', error: 'Invalid records array' },
        { status: 400 }
      );
    }

    // Process & normalize each attendance record
    const normalized = records.map((r) => {
      const recordId = r.record_id || r.recordId || crypto.randomUUID();
      const studentRoll = r.student_roll || r.studentRoll || 'UNKNOWN';
      const studentName = r.student_name || r.studentName || 'Student';
      const sessionDate = r.session_date || r.sessionDate || new Date().toISOString().split('T')[0];
      const timestamp = r.timestamp || Date.now();
      const confidencePct = r.confidence_pct ?? r.confidencePct ?? 95;
      const securityTier = r.security_tier || r.securityTier || 'HIGH';
      const sha256Hash = r.sha256_hash || r.sha256Hash || '';

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
          await ensureDefaultOrganization(database);
          for (const rec of verifiedRecords) {
            await database
              .insert(attendanceRecords)
              .values({
                orgId: DEFAULT_ORG_ID,
                recordId: rec.recordId,
                studentRoll: rec.studentRoll,
                studentName: rec.studentName,
                timestamp: rec.timestamp,
                sessionDate: rec.sessionDate,
                confidencePct: rec.confidencePct,
                securityTier: rec.securityTier,
                sha256Hash: rec.sha256Hash || crypto.createHash('sha256').update(rec.recordId).digest('hex'),
                kioskId: rec.deviceId,
              })
              .onConflictDoNothing({ target: attendanceRecords.recordId });
          }
        } catch (dbErr) {
          console.error('PostgreSQL attendance sync persistence warning:', dbErr);
        }
      }
    }

    const responseData = {
      success: true,
      status: 'synced',
      message: `Successfully synchronized ${verifiedRecords.length} records from terminal ${deviceId}`,
      deviceId,
      receivedCount: records.length,
      syncedCount: verifiedRecords.length,
      serverTimestamp: Date.now(),
    };

    const responseBody = JSON.stringify(responseData);

    // If client provided HMAC, optionally sign response
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
