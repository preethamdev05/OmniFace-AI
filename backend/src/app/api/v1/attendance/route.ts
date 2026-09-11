import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents } from '@/db/schema';
import { eq, and, desc, or, ilike } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'VIEWER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const orgId = auth.user.orgId;

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'No organization affiliated with session' },
        { status: 403 }
      );
    }

    const { searchParams } = new URL(req.url);
    const dateParam = searchParams.get('date'); // 'YYYY-MM-DD' or 'ALL'
    const statusParam = searchParams.get('status'); // 'ALL' or specific status
    const queryParam = searchParams.get('q') || searchParams.get('search');
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 1000);
    const offset = Math.max(parseInt(searchParams.get('offset') || '0', 10), 0);

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        records: [],
        total: 0,
        message: 'Database not configured; returning clean empty records.',
      }, { status: 200 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({
        success: true,
        records: [],
        total: 0,
      }, { status: 200 });
    }

    // Build filter conditions
    const conditions = [eq(attendanceEvents.organizationId, orgId)];

    if (dateParam && dateParam !== 'ALL') {
      conditions.push(eq(attendanceEvents.sessionDate, dateParam));
    }

    if (statusParam && statusParam !== 'ALL') {
      conditions.push(eq(attendanceEvents.status, statusParam));
    }

    if (queryParam && queryParam.trim()) {
      const q = `%${queryParam.trim()}%`;
      conditions.push(
        or(
          ilike(attendanceEvents.studentRoll, q),
          ilike(attendanceEvents.studentName, q)
        )!
      );
    }

    const whereClause = and(...conditions);

    // Query attendance events
    const rows = await database
      .select({
        id: attendanceEvents.id,
        eventId: attendanceEvents.eventId,
        recordId: attendanceEvents.recordId,
        studentRoll: attendanceEvents.studentRoll,
        studentName: attendanceEvents.studentName,
        deviceId: attendanceEvents.deviceId,
        timestamp: attendanceEvents.timestamp,
        sessionDate: attendanceEvents.sessionDate,
        status: attendanceEvents.status,
        confidencePct: attendanceEvents.confidencePct,
        securityTier: attendanceEvents.securityTier,
        sha256Hash: attendanceEvents.sha256Hash,
        hardwareHash: attendanceEvents.hardwareHash,
        serverEvaluated: attendanceEvents.serverEvaluated,
        createdAt: attendanceEvents.createdAt,
      })
      .from(attendanceEvents)
      .where(whereClause)
      .orderBy(desc(attendanceEvents.timestamp))
      .limit(limit)
      .offset(offset);

    // Format for the UI
    const records = rows.map((r) => {
      const clockDate = new Date(Number(r.timestamp));
      const clockInTime = isNaN(clockDate.getTime())
        ? '—'
        : clockDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

      return {
        id: r.id,
        recordId: r.eventId || r.recordId || r.id,
        studentRoll: r.studentRoll,
        studentName: r.studentName,
        department: 'Enrolled Member',
        sessionDate: r.sessionDate,
        clockInTime,
        confidencePct: r.confidencePct,
        securityTier: r.securityTier,
        status: r.status,
        sha256Hash: r.sha256Hash || '',
        serverEvaluated: Boolean(r.serverEvaluated),
      };
    });

    return NextResponse.json({
      success: true,
      records,
      count: records.length,
      limit,
      offset,
    }, { status: 200 });
  } catch (error: any) {
    console.error('Attendance query failed:', error);
    return NextResponse.json(
      { success: false, error: error?.message || 'Failed to query attendance events' },
      { status: 500 }
    );
  }
}
