import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { organizations, auditLogs } from '@/db/schema';
import { ensureDefaultOrganization } from '@/db/helpers';
import { eq } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

const SettingsUpdateSchema = z.object({
  orgName: z.string().min(2, 'Organization name must be at least 2 characters'),
  orgType: z.enum(['SCHOOL', 'COLLEGE', 'COACHING', 'CORPORATE', 'GYM_EVENT']).default('SCHOOL'),
  contactEmail: z.string().email('Valid contact email is required'),
  contactPhone: z.string().optional().default(''),
  defaultStartTime: z.string().regex(/^([01]\d|2[0-3]):([0-5]\d)$/, 'Time must be in HH:MM format').default('09:00'),
  graceMinutes: z.number().int().min(0).max(120).default(15),
  autoEvaluateStatus: z.boolean().default(true),
  dpdpCompliance: z.boolean().default(true),
});

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req);
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        settings: {
          orgName: user.orgName || 'OmniFace Campus',
          orgType: 'SCHOOL',
          contactEmail: user.email,
          contactPhone: '',
          defaultStartTime: '09:00',
          graceMinutes: 15,
          autoEvaluateStatus: true,
          dpdpCompliance: true,
        },
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    await ensureDefaultOrganization(database);
    const rows = await database
      .select()
      .from(organizations)
      .where(eq(organizations.id, orgId))
      .limit(1);

    if (rows.length === 0) {
      return NextResponse.json({ success: false, error: 'Organization not found' }, { status: 404 });
    }

    const org = rows[0];
    return NextResponse.json({
      success: true,
      settings: {
        orgName: org.name,
        orgType: org.type,
        contactEmail: org.contactEmail,
        contactPhone: org.contactPhone || '',
        defaultStartTime: (org as any).defaultStartTime || '09:00',
        graceMinutes: (org as any).graceMinutes ?? 15,
        autoEvaluateStatus: (org as any).autoEvaluateStatus !== 0,
        dpdpCompliance: (org as any).dpdpCompliance !== 0,
      },
    });
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Failed to fetch settings' }, { status: 500 });
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
    const result = SettingsUpdateSchema.safeParse(body);
    if (!result.success) {
      return NextResponse.json(
        { success: false, error: 'Validation failed', details: result.error.flatten() },
        { status: 400 }
      );
    }

    const {
      orgName,
      orgType,
      contactEmail,
      contactPhone,
      defaultStartTime,
      graceMinutes,
      autoEvaluateStatus,
      dpdpCompliance,
    } = result.data;

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        message: 'Organization settings and attendance policy updated successfully (sandbox mode).',
        settings: result.data,
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    await ensureDefaultOrganization(database);

    await database
      .update(organizations)
      .set({
        name: orgName,
        type: orgType,
        contactEmail,
        contactPhone,
        defaultStartTime,
        graceMinutes,
        autoEvaluateStatus: autoEvaluateStatus ? 1 : 0,
        dpdpCompliance: dpdpCompliance ? 1 : 0,
        updatedAt: new Date(),
      })
      .where(eq(organizations.id, orgId));

    // Audit log
    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'SETTINGS_CHANGED',
      entityType: 'ORGANIZATION',
      entityId: orgId,
      newValues: JSON.stringify({ orgName, orgType, defaultStartTime, graceMinutes, autoEvaluateStatus }),
      reason: 'Organization policy settings updated by administrator',
    });

    return NextResponse.json({
      success: true,
      message: 'Organization settings and attendance policy updated successfully.',
      settings: result.data,
    });
  } catch (err: any) {
    return NextResponse.json({ success: false, error: err?.message || 'Failed to update settings' }, { status: 500 });
  }
}
