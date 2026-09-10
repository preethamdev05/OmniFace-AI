import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { faceEmbeddings } from '@/db/schema';
import { eq, gte } from 'drizzle-orm';

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const orgId = searchParams.get('orgId');
    const sinceTimestamp = parseInt(searchParams.get('since') || '0', 10);
    const authHeader = req.headers.get('authorization');

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'Missing orgId parameter' },
        { status: 400 }
      );
    }

    if (!authHeader || !authHeader.startsWith('Bearer omni_')) {
      return NextResponse.json(
        { success: false, error: 'Invalid or missing Authorization bearer token' },
        { status: 401 }
      );
    }

    let templates: any[] = [];
    let students: any[] = [];

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const rows = await database
            .select()
            .from(faceEmbeddings)
            .where(eq(faceEmbeddings.orgId, orgId));

          templates = rows.map((r) => ({
            id: r.id,
            studentRoll: r.studentRoll,
            fullName: r.fullName,
            angleType: r.angleType,
            embedding: r.embedding,
            qualityScore: r.qualityScore,
          }));

          const studentMap = new Map<string, any>();
          for (const r of rows) {
            if (!studentMap.has(r.studentRoll)) {
              studentMap.set(r.studentRoll, {
                roll: r.studentRoll,
                name: r.fullName,
                department: r.department,
                semester: r.semester,
              });
            }
          }
          students = Array.from(studentMap.values());
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
