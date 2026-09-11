import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { auditLogs, users } from '@/db/schema';
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
    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'User does not belong to an organization. Please complete onboarding.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        logs: [],
        totalCount: 0,
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({
        success: true,
        logs: [],
        totalCount: 0,
      });
    }

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
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch audit logs' },
      { status: 500 }
    );
  }
}
