import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { classes, departments, auditLogs } from '@/db/schema';
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
      return NextResponse.json({ success: true, classes: [], totalCount: 0 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: true, classes: [], totalCount: 0 });
    }

    await ensureDefaultOrganization(database);
    const rows = await database
      .select({
        id: classes.id,
        organizationId: classes.organizationId,
        departmentId: classes.departmentId,
        name: classes.name,
        section: classes.section,
        scheduleStartTime: classes.scheduleStartTime,
        scheduleEndTime: classes.scheduleEndTime,
        graceMinutes: classes.graceMinutes,
        createdAt: classes.createdAt,
      })
      .from(classes)
      .where(eq(classes.organizationId, orgId))
      .orderBy(desc(classes.createdAt));

    return NextResponse.json({
      success: true,
      classes: rows,
      totalCount: rows.length,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch classes' },
      { status: 500 }
    );
  }
}

export async function POST(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'TEACHER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    const body = await req.json();
    const {
      name,
      section = 'A',
      departmentId,
      scheduleStartTime = '09:00',
      scheduleEndTime = '17:00',
      graceMinutes = 15,
    } = body;

    if (!name || typeof name !== 'string') {
      return NextResponse.json(
        { success: false, error: 'Class name is required' },
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
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    await ensureDefaultOrganization(database);
    const inserted = await database
      .insert(classes)
      .values({
        organizationId: orgId,
        departmentId: departmentId || null,
        name,
        section,
        scheduleStartTime,
        scheduleEndTime,
        graceMinutes: Number(graceMinutes) || 15,
      })
      .returning();

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'CLASS_CREATED',
      entityType: 'CLASS',
      entityId: inserted[0].id,
      newValues: JSON.stringify({ name, section, departmentId }),
      reason: 'Class created via web management dashboard',
    });

    return NextResponse.json({
      success: true,
      message: `Class ${name} (Section ${section}) created successfully`,
      class: inserted[0],
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to create class' },
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
    const { id, name, section, departmentId, scheduleStartTime, scheduleEndTime, graceMinutes } = body;

    if (!id) {
      return NextResponse.json({ success: false, error: 'Class id is required' }, { status: 400 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    const updated = await database
      .update(classes)
      .set({
        name: name || undefined,
        section: section || undefined,
        departmentId: departmentId || undefined,
        scheduleStartTime: scheduleStartTime || undefined,
        scheduleEndTime: scheduleEndTime || undefined,
        graceMinutes: graceMinutes !== undefined ? Number(graceMinutes) : undefined,
        updatedAt: new Date(),
      })
      .where(and(eq(classes.id, id), eq(classes.organizationId, orgId)))
      .returning();

    if (updated.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Class not found or unauthorized' },
        { status: 404 }
      );
    }

    return NextResponse.json({
      success: true,
      message: 'Class updated successfully',
      class: updated[0],
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to update class' },
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
      return NextResponse.json({ success: false, error: 'Class id is required' }, { status: 400 });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database offline' }, { status: 500 });
    }

    const deleted = await database
      .delete(classes)
      .where(and(eq(classes.id, id), eq(classes.organizationId, orgId)))
      .returning({ id: classes.id, name: classes.name });

    if (deleted.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Class not found or unauthorized' },
        { status: 404 }
      );
    }

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'CLASS_DELETED',
      entityType: 'CLASS',
      entityId: id,
      reason: `Class ${deleted[0].name} deleted by administrator`,
    });

    return NextResponse.json({
      success: true,
      message: `Class ${deleted[0].name} deleted successfully`,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to delete class' },
      { status: 500 }
    );
  }
}
