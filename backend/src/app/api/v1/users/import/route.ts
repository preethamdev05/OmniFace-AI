import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { students, auditLogs } from '@/db/schema';
import { eq, and } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

interface ImportRow {
  rollNumber: string;
  fullName: string;
  role?: string;
  department?: string;
  semester?: string;
  email?: string;
  phone?: string;
}

interface RowError {
  row: number;
  rollNumber: string;
  error: string;
}

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'TEACHER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'No organization affiliated with session' },
        { status: 403 }
      );
    }

    const body = await req.json();
    const rows: ImportRow[] = Array.isArray(body.rows) ? body.rows : [];
    const dryRun = Boolean(body.dryRun);

    if (rows.length === 0) {
      return NextResponse.json(
        { success: false, error: 'No data rows provided for import' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database service is unavailable' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    // 1. Check organization enrollment capacity
    const currentStudents = await database
      .select({ id: students.id })
      .from(students)
      .where(eq(students.organizationId, orgId));

    const currentCount = currentStudents.length;
    const maxAllowed = user.entitlements.maxUsers;

    if (currentCount + rows.length > maxAllowed) {
      return NextResponse.json(
        {
          success: false,
          error: `Import would exceed organization plan limit. Currently enrolled: ${currentCount}, Attempting to add: ${rows.length}, Plan maximum: ${maxAllowed}. Please upgrade your subscription.`,
          currentCount,
          attemptedAdd: rows.length,
          maxAllowed,
        },
        { status: 403 }
      );
    }

    // 2. Row-level validation
    const errors: RowError[] = [];
    const seenRolls = new Set<string>();

    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      const rowNum = i + 1;

      if (!r.rollNumber || typeof r.rollNumber !== 'string' || !r.rollNumber.trim()) {
        errors.push({ row: rowNum, rollNumber: r.rollNumber || '', error: 'Roll number / identifier is required' });
        continue;
      }

      const cleanRoll = r.rollNumber.trim();

      if (!r.fullName || typeof r.fullName !== 'string' || !r.fullName.trim()) {
        errors.push({ row: rowNum, rollNumber: cleanRoll, error: 'Full name is required' });
        continue;
      }

      // Check duplicate within upload batch
      if (seenRolls.has(cleanRoll.toLowerCase())) {
        errors.push({ row: rowNum, rollNumber: cleanRoll, error: `Duplicate roll number "${cleanRoll}" in upload batch` });
        continue;
      }
      seenRolls.add(cleanRoll.toLowerCase());
    }

    // 3. Check for existing roll numbers in the database
    if (seenRolls.size > 0) {
      const existingDbRecords = await database
        .select({ rollNumber: students.rollNumber })
        .from(students)
        .where(eq(students.organizationId, orgId));

      const existingDbSet = new Set(existingDbRecords.map((s) => s.rollNumber.toLowerCase()));

      for (let i = 0; i < rows.length; i++) {
        const r = rows[i];
        if (r.rollNumber && existingDbSet.has(r.rollNumber.trim().toLowerCase())) {
          errors.push({
            row: i + 1,
            rollNumber: r.rollNumber.trim(),
            error: `Member with roll number "${r.rollNumber.trim()}" already exists in organization`,
          });
        }
      }
    }

    // 4. In dryRun mode, return preview and validation diagnostics without touching the database
    if (dryRun) {
      return NextResponse.json({
        success: errors.length === 0,
        dryRun: true,
        totalRows: rows.length,
        validRows: rows.length - errors.length,
        errorCount: errors.length,
        errors,
        preview: rows.slice(0, 10),
      });
    }

    // 5. Fail-closed: Never partially apply an import that contains validation errors
    if (errors.length > 0) {
      return NextResponse.json(
        {
          success: false,
          error: `Import aborted: ${errors.length} validation errors detected across ${rows.length} rows. Fix errors to proceed with atomic import.`,
          errors,
        },
        { status: 400 }
      );
    }

    // 6. Execute atomic batch insertion
    const insertPayload = rows.map((r) => ({
      organizationId: orgId,
      rollNumber: r.rollNumber.trim(),
      fullName: r.fullName.trim(),
      role: (r.role || 'STUDENT').toUpperCase().trim(),
      email: r.email?.trim() || null,
      phone: r.phone?.trim() || null,
      status: 'ACTIVE',
    }));

    await database.insert(students).values(insertPayload);

    // 7. Audit log the bulk import
    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'MEMBERS_BULK_IMPORTED',
      entityType: 'STUDENT',
      entityId: `${rows.length} records`,
      newValues: JSON.stringify({ count: rows.length, firstRoll: rows[0].rollNumber, lastRoll: rows[rows.length - 1].rollNumber }),
      reason: `Bulk imported ${rows.length} people via CSV/XLSX`,
    });

    return NextResponse.json(
      {
        success: true,
        message: `Successfully imported ${rows.length} members into organization roster`,
        importedCount: rows.length,
      },
      { status: 201 }
    );
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Import failed' },
      { status: 500 }
    );
  }
}
