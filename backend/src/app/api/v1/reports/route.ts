import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { students, attendanceEvents, departments } from '@/db/schema';
import { eq, and, sql, like } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'TEACHER');
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
    const month = searchParams.get('month') || new Date().toISOString().slice(0, 7); // YYYY-MM
    const deptFilter = searchParams.get('department') || 'ALL';

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        month,
        workingDays: 0,
        summary: { cohortAverage: 0, perfectCount: 0, defaulterCount: 0 },
        rows: [],
      }, { status: 200 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({
        success: true,
        month,
        workingDays: 0,
        summary: { cohortAverage: 0, perfectCount: 0, defaulterCount: 0 },
        rows: [],
      }, { status: 200 });
    }

    // 1. Fetch departments for this org
    const deptList = await database
      .select({ id: departments.id, name: departments.name })
      .from(departments)
      .where(eq(departments.organizationId, orgId));
    const deptMap = new Map<string, string>();
    for (const d of deptList) {
      deptMap.set(d.id, d.name);
    }

    // 2. Fetch students for this org
    const studentList = await database
      .select({
        id: students.id,
        rollNumber: students.rollNumber,
        fullName: students.fullName,
        departmentId: students.departmentId,
      })
      .from(students)
      .where(eq(students.organizationId, orgId));

    // 3. Fetch attendance events for this month
    const monthPattern = `${month}-%`;
    const events = await database
      .select({
        studentRoll: attendanceEvents.studentRoll,
        sessionDate: attendanceEvents.sessionDate,
        status: attendanceEvents.status,
      })
      .from(attendanceEvents)
      .where(
        and(
          eq(attendanceEvents.organizationId, orgId),
          like(attendanceEvents.sessionDate, monthPattern)
        )
      );

    // Calculate distinct working session dates in this month
    const distinctDates = new Set<string>();
    for (const ev of events) {
      if (ev.sessionDate) {
        distinctDates.add(ev.sessionDate);
      }
    }
    const workingDays = distinctDates.size;

    // Group events by studentRoll
    const studentEventsMap = new Map<string, typeof events>();
    for (const ev of events) {
      if (!studentEventsMap.has(ev.studentRoll)) {
        studentEventsMap.set(ev.studentRoll, []);
      }
      studentEventsMap.get(ev.studentRoll)!.push(ev);
    }

    // Aggregate metrics per student
    const rows = studentList
      .map((s) => {
        const studentDept = s.departmentId ? (deptMap.get(s.departmentId) || 'General') : 'General';
        const evs = studentEventsMap.get(s.rollNumber) || [];

        let presentDays = 0;
        let lateMarks = 0;

        for (const ev of evs) {
          if (ev.status === 'PRESENT') presentDays++;
          else if (ev.status === 'LATE') lateMarks++;
        }

        const effectivePresent = presentDays + lateMarks;
        const absentDays = Math.max(0, workingDays - effectivePresent);
        const ratePct = workingDays > 0
          ? Math.round((effectivePresent / workingDays) * 1000) / 10
          : 0;

        let status = 'GOOD';
        if (ratePct >= 95) status = 'EXCELLENT';
        else if (ratePct >= 85) status = 'GOOD';
        else if (ratePct >= 75) status = 'WARNING';
        else status = 'CRITICAL';

        return {
          roll: s.rollNumber,
          name: s.fullName,
          department: studentDept,
          departmentId: s.departmentId,
          totalDays: workingDays,
          presentDays,
          absentDays,
          lateMarks,
          ratePct,
          status,
        };
      })
      .filter((r) => {
        if (deptFilter === 'ALL') return true;
        return r.department === deptFilter || r.departmentId === deptFilter;
      });

    // Summary calculation
    let totalRate = 0;
    let perfectCount = 0;
    let defaulterCount = 0;

    for (const r of rows) {
      totalRate += r.ratePct;
      if (r.ratePct >= 100) perfectCount++;
      if (r.ratePct < 85) defaulterCount++;
    }

    const cohortAverage = rows.length > 0
      ? Math.round((totalRate / rows.length) * 10) / 10
      : 0;

    return NextResponse.json({
      success: true,
      month,
      workingDays,
      summary: {
        cohortAverage,
        perfectCount,
        defaulterCount,
        totalStudents: rows.length,
      },
      rows,
    }, { status: 200 });
  } catch (error: any) {
    console.error('Reports aggregation failed:', error);
    return NextResponse.json(
      { success: false, error: error?.message || 'Failed to aggregate reports' },
      { status: 500 }
    );
  }
}
