import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { students, attendanceEvents, devices, departments, organizations, subscriptions } from '@/db/schema';
import { desc, eq, and, inArray } from 'drizzle-orm';
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

    const todayStr = new Date().toISOString().split('T')[0];

    // Safe zeroed defaults
    let totalEnrolled = 0;
    let presentToday = 0;
    let absentToday = 0;
    let attendanceRatePct = 0;
    let activeKiosks = 0;
    let tier = 'PRO';
    let deptDistribution: Array<{ department: string; total: number; present: number; ratePct: number }> = [];
    let hourlyMap = new Map<string, number>();
    let kioskList: Array<any> = [];
    let recentActivityList: Array<any> = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          // 1. Total enrolled students for this org
          const enrolledStudents = await database
            .select({
              id: students.id,
              rollNumber: students.rollNumber,
              fullName: students.fullName,
              departmentId: students.departmentId,
            })
            .from(students)
            .where(eq(students.organizationId, orgId));

          totalEnrolled = enrolledStudents.length;

          // 2. Organization tier
          const orgRows = await database
            .select({ tier: organizations.tier })
            .from(organizations)
            .where(eq(organizations.id, orgId))
            .limit(1);
          if (orgRows.length > 0 && orgRows[0].tier) {
            tier = orgRows[0].tier;
          }

          // 3. Today's attendance events
          const todayEvents = await database
            .select({
              id: attendanceEvents.id,
              eventId: attendanceEvents.eventId,
              recordId: attendanceEvents.recordId,
              studentRoll: attendanceEvents.studentRoll,
              studentName: attendanceEvents.studentName,
              timestamp: attendanceEvents.timestamp,
              sessionDate: attendanceEvents.sessionDate,
              status: attendanceEvents.status,
              confidencePct: attendanceEvents.confidencePct,
              securityTier: attendanceEvents.securityTier,
              sha256Hash: attendanceEvents.sha256Hash,
            })
            .from(attendanceEvents)
            .where(
              and(
                eq(attendanceEvents.organizationId, orgId),
                eq(attendanceEvents.sessionDate, todayStr)
              )
            )
            .orderBy(desc(attendanceEvents.timestamp));

          // Set of students present today
          const presentRolls = new Set<string>();
          for (const ev of todayEvents) {
            if (ev.status === 'PRESENT' || ev.status === 'LATE') {
              presentRolls.add(ev.studentRoll);
            }

            // Hourly breakdown
            const evDate = new Date(Number(ev.timestamp));
            if (!isNaN(evDate.getTime())) {
              const hour = String(evDate.getHours()).padStart(2, '0');
              const min = evDate.getMinutes() >= 30 ? '30' : '00';
              const bucket = `${hour}:${min}`;
              hourlyMap.set(bucket, (hourlyMap.get(bucket) || 0) + 1);
            }
          }

          presentToday = presentRolls.size;
          absentToday = Math.max(0, totalEnrolled - presentToday);
          attendanceRatePct = totalEnrolled > 0
            ? Math.round((presentToday / totalEnrolled) * 1000) / 10
            : 0;

          // 4. Department distribution
          const deptRows = await database
            .select({
              id: departments.id,
              name: departments.name,
              code: departments.code,
            })
            .from(departments)
            .where(eq(departments.organizationId, orgId));

          const deptStudentMap = new Map<string, typeof enrolledStudents>();
          for (const s of enrolledStudents) {
            const dId = s.departmentId || 'unassigned';
            if (!deptStudentMap.has(dId)) {
              deptStudentMap.set(dId, []);
            }
            deptStudentMap.get(dId)!.push(s);
          }

          deptDistribution = deptRows.map((d) => {
            const deptStudents = deptStudentMap.get(d.id) || [];
            const deptTotal = deptStudents.length;
            const deptPresent = deptStudents.filter((s) => presentRolls.has(s.rollNumber)).length;
            const rate = deptTotal > 0 ? Math.round((deptPresent / deptTotal) * 1000) / 10 : 0;
            return {
              department: d.name,
              total: deptTotal,
              present: deptPresent,
              ratePct: rate,
            };
          });

          // 5. Active kiosks/devices
          const deviceRows = await database
            .select({
              id: devices.id,
              deviceIdentifier: devices.deviceIdentifier,
              deviceName: devices.deviceName,
              status: devices.status,
              lastSyncAt: devices.lastSyncAt,
              appVersion: devices.appVersion,
            })
            .from(devices)
            .where(eq(devices.organizationId, orgId));

          activeKiosks = deviceRows.filter((d) => d.status === 'ONLINE').length;

          kioskList = deviceRows.map((d) => ({
            id: d.deviceIdentifier,
            name: d.deviceName,
            status: d.status,
            lastSync: d.lastSyncAt ? new Date(d.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Never',
            appVersion: d.appVersion || 'v2.0.0',
          }));

          // 6. Recent activity (latest 8 attendance events for this org)
          const recentRows = await database
            .select({
              id: attendanceEvents.id,
              eventId: attendanceEvents.eventId,
              recordId: attendanceEvents.recordId,
              studentRoll: attendanceEvents.studentRoll,
              studentName: attendanceEvents.studentName,
              timestamp: attendanceEvents.timestamp,
              sessionDate: attendanceEvents.sessionDate,
              confidencePct: attendanceEvents.confidencePct,
              securityTier: attendanceEvents.securityTier,
              status: attendanceEvents.status,
              sha256Hash: attendanceEvents.sha256Hash,
            })
            .from(attendanceEvents)
            .where(eq(attendanceEvents.organizationId, orgId))
            .orderBy(desc(attendanceEvents.timestamp))
            .limit(8);

          recentActivityList = recentRows.map((r, i) => {
            const evDate = new Date(Number(r.timestamp));
            const timeStr = isNaN(evDate.getTime())
              ? '—'
              : evDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

            return {
              id: r.id || r.eventId || `rec_${i + 1}`,
              studentRoll: r.studentRoll,
              studentName: r.studentName,
              department: 'Enrolled Member',
              sessionDate: r.sessionDate,
              time: timeStr,
              confidencePct: r.confidencePct,
              securityTier: r.securityTier,
              status: r.status,
              sha256Hash: r.sha256Hash || '',
            };
          });
        } catch (dbErr) {
          console.error('Analytics summary query warning:', dbErr);
        }
      }
    }

    // Convert hourly map to sorted array
    const hourlyAttendance = Array.from(hourlyMap.entries())
      .map(([hour, count]) => ({ hour, count }))
      .sort((a, b) => a.hour.localeCompare(b.hour));

    return NextResponse.json({
      success: true,
      summary: {
        totalEnrolled,
        presentToday,
        absentToday,
        attendanceRatePct,
        activeKiosks,
        tier,
      },
      departmentDistribution: deptDistribution,
      hourlyAttendance,
      kioskStatus: kioskList,
      recentActivity: recentActivityList,
    }, { status: 200 });
  } catch (error: any) {
    console.error('Analytics summary failed:', error);
    return NextResponse.json(
      { success: false, error: error?.message || 'Failed to generate analytics summary' },
      { status: 500 }
    );
  }
}
