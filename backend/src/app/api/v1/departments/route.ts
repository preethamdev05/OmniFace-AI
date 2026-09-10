import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { departments, auditLogs } from '@/db/schema';
import { ensureDefaultOrganization } from '@/db/helpers';
import { eq, and, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req);
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const orgId = auth.user.orgId;

    if (!isDbConfigured()) {
      return NextResponse.json({ success: true, departments: [], totalCount: 0 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: true, departments: [], totalCount: 0 });
    }

    await ensureDefaultOrganization(database);
    const rows = await database
      .select({
        id: departments.id,
        organizationId: departments.organizationId,
        name: departments.name,
        code: departments.code,
        description: departments.description,
        createdAt: departments.createdAt,
      })
      .from(departments)
      .where(eq(departments.organizationId, orgId))
      .orderBy(desc(departments.createdAt));

    return NextResponse.json({
      success: true,
      departments: rows,
      totalCount: rows.length,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch departments' },
      { status: 500 }
    );
  }
}

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const body = await req.json();
    const { name, code, description } = body;

    if (!name || !code) {
      return NextResponse.json(
        { success: false, error: 'Department name and unique code are required' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database is not configured' },
        { status: 500 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    await ensureDefaultOrganization(database);
    const inserted = await database
      .insert(departments)
      .values({
        organizationId: orgId,
        name,
        code: code.toUpperCase().trim(),
        description: description || null,
      })
      .returning();

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'DEPARTMENT_CREATED',
      entityType: 'DEPARTMENT',
      entityId: inserted[0].id,
      newValues: JSON.stringify({ name, code: code.toUpperCase().trim() }),
      reason: 'Department created via administrative console',
    });

    return NextResponse.json({
      success: true,
      message: `Department ${name} (${code}) created successfully`,
      department: inserted[0],
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to create department' },
      { status: 500 }
    );
  }
}

export async function PUT(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const body = await req.json();
    const { id, name, code, description } = body;

    if (!id) {
      return NextResponse.json({ success: false, error: 'Department id is required' }, { status: 400 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    const updated = await database
      .update(departments)
      .set({
        name: name || undefined,
        code: code ? code.toUpperCase().trim() : undefined,
        description: description !== undefined ? description : undefined,
        updatedAt: new Date(),
      })
      .where(and(eq(departments.id, id), eq(departments.organizationId, orgId)))
      .returning();

    if (updated.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Department not found or unauthorized' },
        { status: 404 }
      );
    }

    return NextResponse.json({
      success: true,
      message: 'Department updated successfully',
      department: updated[0],
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to update department' },
      { status: 500 }
    );
  }
}

export async function DELETE(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'ADMIN');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');

    if (!id) {
      return NextResponse.json({ success: false, error: 'Department id is required' }, { status: 400 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    const deleted = await database
      .delete(departments)
      .where(and(eq(departments.id, id), eq(departments.organizationId, orgId)))
      .returning({ id: departments.id, name: departments.name });

    if (deleted.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Department not found or unauthorized' },
        { status: 404 }
      );
    }

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'DEPARTMENT_DELETED',
      entityType: 'DEPARTMENT',
      entityId: id,
      reason: `Department ${deleted[0].name} deleted by administrator`,
    });

    return NextResponse.json({
      success: true,
      message: `Department ${deleted[0].name} deleted successfully`,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to delete department' },
      { status: 500 }
    );
  }
}
