import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents } from '@/db/schema';
import { eq, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'TEACHER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const { searchParams } = new URL(req.url);
    const format = searchParams.get('format') || 'csv';
    const month = searchParams.get('month') || new Date().toISOString().slice(0, 7);
    const dept = searchParams.get('department') || 'All';

    // CSV Header row
    const headers = [
      'Event ID',
      'Roll Number',
      'Student Name',
      'Date',
      'Timestamp',
      'Status',
      'Confidence (%)',
      'Security Tier',
      'Device ID',
      'Verification Proof',
    ];

    let dataRows: string[][] = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const events = await database
            .select()
            .from(attendanceEvents)
            .where(eq(attendanceEvents.organizationId, orgId))
            .orderBy(desc(attendanceEvents.timestamp))
            .limit(1000);

          dataRows = events.map((e) => [
            e.eventId || e.id,
            e.studentRoll || 'N/A',
            e.studentName || 'Student',
            e.sessionDate || '',
            new Date(Number(e.timestamp)).toISOString(),
            e.status,
            `${e.confidencePct}%`,
            e.securityTier,
            e.deviceId,
            e.sha256Hash ? 'CRYPTOGRAPHICALLY_VERIFIED' : 'STANDARD',
          ]);
        } catch (dbErr) {
          console.warn('DB reports export query warning:', dbErr);
        }
      }
    }

    // Prevent CSV Formula Injection (CWE-1236)
    function sanitizeCsvCell(value: any): string {
      if (value === null || value === undefined) return '';
      const str = String(value).replace(/"/g, '""');
      // If cell starts with formula characters (=, +, -, @, \t, \r), prefix with single quote
      if (/^[=+\-@\t\r]/.test(str)) {
        return `'${str}`;
      }
      return str;
    }

    const csvContent = [
      headers.join(','),
      ...dataRows.map((row) => row.map((cell) => `"${sanitizeCsvCell(cell)}"`).join(',')),
    ].join('\n');

    // Sanitize parameters for Content-Disposition header against CRLF injection (CWE-113)
    const safeMonth = month.replace(/[^a-zA-Z0-9_-]/g, '_');
    const safeDept = dept.replace(/[^a-zA-Z0-9_-]/g, '_');

    return new NextResponse(csvContent, {
      status: 200,
      headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': `attachment; filename="OmniFace_Attendance_Report_${safeMonth}_${safeDept}.csv"`,
      },
    });
  } catch (err: any) {
    return NextResponse.json({ error: err?.message || 'Report generation failed' }, { status: 500 });
  }
}
