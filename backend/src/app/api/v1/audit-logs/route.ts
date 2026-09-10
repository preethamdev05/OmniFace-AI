import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { auditLogs, users } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, desc } from 'drizzle-orm';

interface AuditLogEntry {
  id: string;
  action: string;
  entityType: string;
  entityId: string;
  performedBy: string;
  oldValues: string | null;
  newValues: string | null;
  reason: string | null;
  timestamp: string;
}

const mockAuditLogs: AuditLogEntry[] = [
  {
    id: 'aud_01',
    action: 'ATTENDANCE_STATUS_ADJUSTED',
    entityType: 'ATTENDANCE_EVENT',
    entityId: 'rec_01 (Aarav Sharma)',
    performedBy: 'Dr. Vikram Joshi (Admin)',
    oldValues: JSON.stringify({ status: 'ABSENT' }),
    newValues: JSON.stringify({ status: 'PRESENT' }),
    reason: 'Student arrived with approved medical fitness certificate; camera turnstile was under calibration at 08:30.',
    timestamp: '2026-09-10T09:15:00Z',
  },
  {
    id: 'aud_02',
    action: 'DEVICE_PAIRED',
    entityType: 'DEVICE',
    entityId: 'OMNIFACE-GATE-01',
    performedBy: 'System Fleet Controller',
    oldValues: null,
    newValues: JSON.stringify({ name: 'Main Gate Terminal', model: 'Xiaomi 14', hwHash: 'a8b7c6d5e4f3...' }),
    reason: 'Dual pairing completed via 6-digit OTP code 839201',
    timestamp: '2026-09-10T08:00:00Z',
  },
  {
    id: 'aud_03',
    action: 'MEMBER_ENROLLED',
    entityType: 'STUDENT',
    entityId: 'CS-2024-042 (Tanvi Iyer)',
    performedBy: 'Prof. Sunita Rao (Teacher)',
    oldValues: null,
    newValues: JSON.stringify({ roll: 'CS-2024-042', dept: 'Computer Science', vectors: 5 }),
    reason: 'New student semester registration',
    timestamp: '2026-09-09T14:30:00Z',
  },
  {
    id: 'aud_04',
    action: 'DEVICE_REVOKED',
    entityType: 'DEVICE',
    entityId: 'OMNIFACE-OLD-TABLET-99',
    performedBy: 'Fleet Administrator',
    oldValues: JSON.stringify({ status: 'ONLINE' }),
    newValues: JSON.stringify({ status: 'REVOKED' }),
    reason: 'Hardware tablet decommissioned due to battery retirement',
    timestamp: '2026-09-08T11:20:00Z',
  },
];

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const orgId = searchParams.get('orgId') || DEFAULT_ORG_ID;

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          const rows = await database
            .select()
            .from(auditLogs)
            .where(eq(auditLogs.organizationId, orgId))
            .orderBy(desc(auditLogs.createdAt))
            .limit(100);

          if (rows.length > 0) {
            const mapped: AuditLogEntry[] = rows.map((r) => ({
              id: r.id,
              action: r.action,
              entityType: r.entityType,
              entityId: r.entityId || 'N/A',
              performedBy: r.userId ? `User (${r.userId.slice(0, 8)})` : 'Admin Operator',
              oldValues: r.oldValues,
              newValues: r.newValues,
              reason: r.reason,
              timestamp: r.createdAt.toISOString(),
            }));

            return NextResponse.json({
              success: true,
              logs: mapped,
              totalCount: mapped.length,
            });
          }
        } catch (dbErr) {
          console.warn('DB audit logs fetch warning:', dbErr);
        }
      }
    }

    return NextResponse.json({
      success: true,
      logs: mockAuditLogs,
      totalCount: mockAuditLogs.length,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch audit logs' },
      { status: 500 }
    );
  }
}
