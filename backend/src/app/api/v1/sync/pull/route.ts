import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { faceTemplates, students as studentsTable, departments } from '@/db/schema';
import { eq, and, or, gte } from 'drizzle-orm';
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
    const sinceDate = sinceTimestamp > 0 ? new Date(sinceTimestamp) : null;

    let templates: any[] = [];
    let students: any[] = [];
    let tombstones: string[] = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const templateConditions = [
            or(eq(faceTemplates.organizationId, orgId), eq(faceTemplates.orgId, orgId)),
          ];
          if (sinceDate && !isNaN(sinceDate.getTime())) {
            templateConditions.push(
              or(gte(faceTemplates.updatedAt, sinceDate), gte(faceTemplates.createdAt, sinceDate))!
            );
          }

          const rows = await database
            .select()
            .from(faceTemplates)
            .where(and(...templateConditions));

          templates = rows.map((r) => ({
            id: r.id,
            studentRoll: r.studentRoll,
            fullName: r.fullName,
            role: r.role || 'STUDENT',
            angleType: r.angleType,
            embedding: r.embedding,
            qualityScore: r.qualityScore,
          }));

          const studentConditions = [
            eq(studentsTable.organizationId, orgId),
          ];
          if (sinceDate && !isNaN(sinceDate.getTime())) {
            studentConditions.push(
              or(gte(studentsTable.updatedAt, sinceDate), gte(studentsTable.createdAt, sinceDate))!
            );
          }

          const studentRows = await database
            .select({
              id: studentsTable.id,
              rollNumber: studentsTable.rollNumber,
              fullName: studentsTable.fullName,
              role: studentsTable.role,
              status: studentsTable.status,
              departmentName: departments.name,
            })
            .from(studentsTable)
            .leftJoin(departments, eq(studentsTable.departmentId, departments.id))
            .where(and(...studentConditions));

          for (const s of studentRows) {
            if (s.status === 'INACTIVE') {
              tombstones.push(s.rollNumber);
            } else {
              students.push({
                roll: s.rollNumber,
                name: s.fullName,
                role: s.role || 'STUDENT',
                department: s.departmentName || 'General',
                semester: 'I',
              });
            }
          }
        } catch (dbErr) {
          console.error('PostgreSQL sync pull query warning:', dbErr);
        }
      }
    }

    // Returns incremental roster updates: students, 512-D float vectors, and tombstones
    return NextResponse.json(
      {
        success: true,
        orgId,
        sinceTimestamp,
        students,
        faceTemplates: templates,
        tombstones,
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
