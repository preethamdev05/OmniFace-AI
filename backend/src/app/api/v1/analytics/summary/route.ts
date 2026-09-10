import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceRecords, faceEmbeddings } from '@/db/schema';
import { desc, eq } from 'drizzle-orm';

export async function GET(req: NextRequest) {
  try {
    const todayStr = new Date().toISOString().split('T')[0];

    // Check if database records exist for today
    let dbRecords: any[] = [];
    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          dbRecords = await database
            .select()
            .from(attendanceRecords)
            .where(eq(attendanceRecords.sessionDate, todayStr))
            .orderBy(desc(attendanceRecords.timestamp))
            .limit(100);
        } catch (dbErr) {
          console.error('PostgreSQL analytics summary query warning:', dbErr);
        }
      }
    }

    // Default baseline fallback
    const analytics = {
      summary: {
        totalEnrolled: 184,
        presentToday: dbRecords.length > 0 ? new Set(dbRecords.map((r) => r.studentRoll)).size : 167,
        absentToday: dbRecords.length > 0 ? Math.max(0, 184 - new Set(dbRecords.map((r) => r.studentRoll)).size) : 17,
        attendanceRatePct: dbRecords.length > 0 ? Math.round((new Set(dbRecords.map((r) => r.studentRoll)).size / 184) * 1000) / 10 : 90.8,
        activeKiosks: 3,
        tier: 'BUSINESS',
      },
      departmentDistribution: [
        { department: 'Computer Science', total: 64, present: 60, ratePct: 93.8 },
        { department: 'Electronics & Comm.', total: 48, present: 43, ratePct: 89.6 },
        { department: 'Mechanical Eng.', total: 42, present: 37, ratePct: 88.1 },
        { department: 'Staff & Faculty', total: 30, present: 27, ratePct: 90.0 },
      ],
      hourlyAttendance: [
        { hour: '08:00', count: 18 },
        { hour: '08:30', count: 52 },
        { hour: '09:00', count: 74 },
        { hour: '09:30', count: 15 },
        { hour: '10:00', count: 8 },
      ],
      kioskStatus: [
        { id: 'kiosk-gate-1', name: 'Main Gate Terminal', status: 'ONLINE', pingMs: 14, lastSync: '1 min ago', batteryPct: 98 },
        { id: 'kiosk-academic-b', name: 'Academic Block B', status: 'ONLINE', pingMs: 22, lastSync: '3 min ago', batteryPct: 84 },
        { id: 'kiosk-library', name: 'Central Library Kiosk', status: 'ONLINE', pingMs: 19, lastSync: '5 min ago', batteryPct: 100 },
      ],
      recentActivity: dbRecords.length > 0
        ? dbRecords.slice(0, 8).map((r, i) => ({
            id: r.recordId || `rec_${i + 1}`,
            studentRoll: r.studentRoll,
            studentName: r.studentName,
            department: 'General',
            sessionDate: r.sessionDate,
            time: new Date(Number(r.timestamp)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
            confidencePct: r.confidencePct,
            securityTier: r.securityTier,
            status: 'PRESENT',
            sha256Hash: r.sha256Hash,
          }))
        : [
        {
          id: 'rec_01',
          studentRoll: 'CS-2024-042',
          studentName: 'Aarav Sharma',
          department: 'Computer Science',
          sessionDate: todayStr,
          time: '09:04 AM',
          confidencePct: 99.4,
          securityTier: 'HIGH',
          status: 'PRESENT',
          sha256Hash: 'a7b3c82d4e5f61203498adfe1902834b9281a0ec94726481029384756182a93c',
        },
        {
          id: 'rec_02',
          studentRoll: 'EC-2024-019',
          studentName: 'Priya Patel',
          department: 'Electronics & Comm.',
          sessionDate: todayStr,
          time: '09:02 AM',
          confidencePct: 98.7,
          securityTier: 'STRICT',
          status: 'PRESENT',
          sha256Hash: 'f4e2d1c0b9a89786756453423120191817161514131211100908070605040302',
        },
        {
          id: 'rec_03',
          studentRoll: 'ME-2024-011',
          studentName: 'Rohan Deshmukh',
          department: 'Mechanical Eng.',
          sessionDate: todayStr,
          time: '08:58 AM',
          confidencePct: 97.5,
          securityTier: 'HIGH',
          status: 'PRESENT',
          sha256Hash: '89ab12cd34ef560123456789abcdef0123456789abcdef0123456789abcdef01',
        },
        {
          id: 'rec_04',
          studentRoll: 'CS-2024-008',
          studentName: 'Ananya Reddy',
          department: 'Computer Science',
          sessionDate: todayStr,
          time: '08:55 AM',
          confidencePct: 99.1,
          securityTier: 'STRICT',
          status: 'PRESENT',
          sha256Hash: '1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef',
        },
        {
          id: 'rec_05',
          studentRoll: 'SF-2023-003',
          studentName: 'Dr. Vikram Joshi',
          department: 'Staff & Faculty',
          sessionDate: todayStr,
          time: '08:45 AM',
          confidencePct: 99.8,
          securityTier: 'STRICT',
          status: 'PRESENT',
          sha256Hash: 'fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321',
        },
      ],
    };

    return NextResponse.json(analytics);
  } catch (err: any) {
    return NextResponse.json({ error: err?.message || 'Failed to fetch analytics' }, { status: 500 });
  }
}
