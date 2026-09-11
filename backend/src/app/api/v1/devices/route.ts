import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { devices, auditLogs } from '@/db/schema';
import { eq, and, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

interface DeviceItem {
  id: string;
  deviceIdentifier: string;
  deviceName: string;
  appVersion: string;
  status: 'ONLINE' | 'OFFLINE' | 'PAIRED' | 'REVOKED';
  lastSyncAt: string;
  lastAttendanceAt: string;
  lastHeartbeatAt?: string;
  pendingEventsCount: number;
  hardwareHash: string;
  isPaired: boolean;
  pairedAt: string;
  batteryPct?: number | null;
  temperature?: number | null;
  thermalState?: 'NOMINAL' | 'WARM' | 'CRITICAL';
  activeFps?: number;
  ipAddress?: string | null;
  isStale?: boolean;
}

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req);
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;
    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'User does not belong to an organization. Please complete onboarding.' },
        { status: 400 }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json({
        success: true,
        devices: [],
        totalCount: 0,
      });
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    const rows = await database
      .select()
      .from(devices)
      .where(eq(devices.organizationId, orgId))
      .orderBy(desc(devices.createdAt));

    const sixtyMinsAgo = Date.now() - 60 * 60 * 1000;
    const mapped: DeviceItem[] = rows.map((r) => {
      const lastActivityTime = Math.max(
        r.lastHeartbeatAt ? new Date(r.lastHeartbeatAt).getTime() : 0,
        r.lastSyncAt ? new Date(r.lastSyncAt).getTime() : 0
      );
      const isStale = r.status === 'ONLINE' && (lastActivityTime === 0 || lastActivityTime < sixtyMinsAgo);

      return {
        id: r.id,
        deviceIdentifier: r.deviceIdentifier,
        deviceName: r.deviceName,
        appVersion: r.appVersion || 'v2.0.0',
        status: isStale ? 'OFFLINE' : ((r.status as any) || 'ONLINE'),
        lastSyncAt: r.lastSyncAt ? new Date(r.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Never',
        lastAttendanceAt: r.lastAttendanceAt ? new Date(r.lastAttendanceAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'None',
        lastHeartbeatAt: r.lastHeartbeatAt ? new Date(r.lastHeartbeatAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Never',
        pendingEventsCount: r.pendingEventsCount || 0,
        hardwareHash: r.hardwareHash || 'Unregistered HW Hash',
        isPaired: Boolean(r.isPaired),
        pairedAt: r.pairedAt ? r.pairedAt.toISOString() : r.createdAt.toISOString(),
        batteryPct: r.batteryPct ?? null,
        temperature: r.temperature ?? null,
        thermalState: (r.thermalState as any) || 'NOMINAL',
        activeFps: r.activeFps || 30,
        ipAddress: r.ipAddress || null,
        isStale,
      };
    });

    return NextResponse.json({
      success: true,
      devices: mapped,
      totalCount: mapped.length,
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to fetch devices' },
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
    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'User does not belong to an organization. Please complete onboarding.' },
        { status: 400 }
      );
    }

    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    const deviceIdentifier = searchParams.get('deviceId');

    if (!id && !deviceIdentifier) {
      return NextResponse.json({ success: false, error: 'Device ID or deviceIdentifier required' }, { status: 400 });
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

    if (id) {
      await database
        .update(devices)
        .set({ status: 'REVOKED', isPaired: 0, deviceToken: null, updatedAt: new Date() })
        .where(and(eq(devices.organizationId, orgId), eq(devices.id, id)));
    } else if (deviceIdentifier) {
      await database
        .update(devices)
        .set({ status: 'REVOKED', isPaired: 0, deviceToken: null, updatedAt: new Date() })
        .where(and(eq(devices.organizationId, orgId), eq(devices.deviceIdentifier, deviceIdentifier)));
    }

    await database.insert(auditLogs).values({
      organizationId: orgId,
      userId: user.userId,
      action: 'DEVICE_REVOKED',
      entityType: 'DEVICE',
      entityId: id || deviceIdentifier || '',
      reason: 'Device authorization revoked by administrator',
    });

    return NextResponse.json({
      success: true,
      message: 'Device revoked successfully. Cloud sync from this terminal is now blocked.',
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to revoke device' },
      { status: 500 }
    );
  }
}
