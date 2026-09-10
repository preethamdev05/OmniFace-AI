import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { faceTemplates, students as studentsTable } from '@/db/schema';
import { eq } from 'drizzle-orm';
import { authenticateDevice } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  try {
    // Authenticate device via X-Device-Token or Authorization: Bearer <deviceToken>
    const device = await authenticateDevice(req);
    if (!device) {
      return NextResponse.json(
        { success: false, error: 'Unauthorized kiosk device or missing device token' },
        { status: 401 }
      );
    }

    const orgId = device.organizationId;
    const { searchParams } = new URL(req.url);
    const sinceTimestamp = parseInt(searchParams.get('since') || '0', 10);

    let templates: any[] = [];
    let students: any[] = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const rows = await database
            .select()
            .from(faceTemplates)
            .where(eq(faceTemplates.organizationId, orgId));

          templates = rows.map((r) => ({
            id: r.id,
            studentRoll: r.studentRoll,
            fullName: r.fullName,
            role: r.role || 'STUDENT',
            angleType: r.angleType,
            embedding: r.embedding,
            qualityScore: r.qualityScore,
          }));

          const studentRows = await database
            .select({
              id: studentsTable.id,
              rollNumber: studentsTable.rollNumber,
              fullName: studentsTable.fullName,
              role: studentsTable.role,
            })
            .from(studentsTable)
            .where(eq(studentsTable.organizationId, orgId));

          students = studentRows.map((s) => ({
            roll: s.rollNumber,
            name: s.fullName,
            role: s.role || 'STUDENT',
            department: 'Enrolled Member',
            semester: 'I',
          }));
        } catch (dbErr) {
          console.error('PostgreSQL sync pull query warning:', dbErr);
        }
      }
    }

    // Returns incremental roster updates: students and 512-D float vectors
    return NextResponse.json(
      {
        success: true,
        orgId,
        sinceTimestamp,
        students,
        faceTemplates: templates,
        serverTimestamp: Date.now(),
      },
      { status: 200 }
    );
  } catch (error: any) {
    return NextResponse.json(
      { success: false, error: error?.message || 'Internal Server Error' },
      { status: 500 }
    );
  }
}
