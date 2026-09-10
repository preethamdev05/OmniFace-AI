import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { auditLogs, users } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

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

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

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
        } catch (dbErr) {
          console.warn('DB audit logs fetch warning:', dbErr);
        }
      }
    }

    return NextResponse.json({
      success: true,
      logs: [],
      totalCount: 0,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch audit logs' },
      { status: 500 }
    );
  }
}
