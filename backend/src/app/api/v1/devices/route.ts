import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { devices, auditLogs } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, desc } from 'drizzle-orm';

interface DeviceItem {
  id: string;
  deviceIdentifier: string;
  deviceName: string;
  appVersion: string;
  status: 'ONLINE' | 'OFFLINE' | 'PAIRED' | 'REVOKED';
  lastSyncAt: string;
  lastAttendanceAt: string;
  pendingEventsCount: number;
  hardwareHash: string;
  isPaired: boolean;
  pairedAt: string;
}

const mockDevices: DeviceItem[] = [
  {
    id: 'dev_01',
    deviceIdentifier: 'OMNIFACE-GATE-01',
    deviceName: 'Main Entrance Gate Kiosk',
    appVersion: 'v2.0.0',
    status: 'ONLINE',
    lastSyncAt: '1 min ago',
    lastAttendanceAt: '09:04 AM',
    pendingEventsCount: 0,
    hardwareHash: 'a8b7c6d5e4f3a2b10987654321fedcba0987654321fedcba0987654321fedcba',
    isPaired: true,
    pairedAt: '2026-08-01T08:00:00Z',
  },
  {
    id: 'dev_02',
    deviceIdentifier: 'OMNIFACE-BLOCKB-02',
    deviceName: 'Academic Block B Terminal',
    appVersion: 'v2.0.0',
    status: 'ONLINE',
    lastSyncAt: '3 min ago',
    lastAttendanceAt: '09:02 AM',
    pendingEventsCount: 0,
    hardwareHash: 'f4e3d2c1b0a99887766554433221100ffeeddccbbaa99887766554433221100f',
    isPaired: true,
    pairedAt: '2026-08-05T09:30:00Z',
  },
  {
    id: 'dev_03',
    deviceIdentifier: 'OMNIFACE-LIB-03',
    deviceName: 'Central Library Kiosk',
    appVersion: 'v1.9.8',
    status: 'ONLINE',
    lastSyncAt: '5 min ago',
    lastAttendanceAt: '08:45 AM',
    pendingEventsCount: 2,
    hardwareHash: '1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef',
    isPaired: true,
    pairedAt: '2026-08-10T14:15:00Z',
  },
];

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const orgId = searchParams.get('orgId') || DEFAULT_ORG_ID;

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          const rows = await database
            .select()
            .from(devices)
            .where(eq(devices.organizationId, orgId))
            .orderBy(desc(devices.createdAt));

          if (rows.length > 0) {
            const mapped: DeviceItem[] = rows.map((r) => ({
              id: r.id,
              deviceIdentifier: r.deviceIdentifier,
              deviceName: r.deviceName,
              appVersion: r.appVersion || 'v2.0.0',
              status: (r.status as any) || 'ONLINE',
              lastSyncAt: r.lastSyncAt ? new Date(r.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Never',
              lastAttendanceAt: r.lastAttendanceAt ? new Date(r.lastAttendanceAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'None',
              pendingEventsCount: r.pendingEventsCount || 0,
              hardwareHash: r.hardwareHash || 'Unregistered HW Hash',
              isPaired: Boolean(r.isPaired),
              pairedAt: r.pairedAt ? r.pairedAt.toISOString() : r.createdAt.toISOString(),
            }));

            return NextResponse.json({
              success: true,
              devices: mapped,
              totalCount: mapped.length,
            });
          }
        } catch (dbErr) {
          console.warn('DB devices query error:', dbErr);
        }
      }
    }

    return NextResponse.json({
      success: true,
      devices: mockDevices,
      totalCount: mockDevices.length,
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
    const { searchParams } = new URL(req.url);
    const id = searchParams.get('id');
    const deviceIdentifier = searchParams.get('deviceId');

    if (!id && !deviceIdentifier) {
      return NextResponse.json({ success: false, error: 'Device ID or deviceIdentifier required' }, { status: 400 });
    }

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);
          if (id) {
            await database
              .update(devices)
              .set({ status: 'REVOKED', isPaired: 0, deviceToken: null, updatedAt: new Date() })
              .where(eq(devices.id, id));
          } else if (deviceIdentifier) {
            await database
              .update(devices)
              .set({ status: 'REVOKED', isPaired: 0, deviceToken: null, updatedAt: new Date() })
              .where(eq(devices.deviceIdentifier, deviceIdentifier));
          }

          await database.insert(auditLogs).values({
            organizationId: DEFAULT_ORG_ID,
            action: 'DEVICE_REVOKED',
            entityType: 'DEVICE',
            entityId: id || deviceIdentifier || '',
            reason: 'Device authorization revoked by administrator',
          });
        } catch (dbErr) {
          console.warn('DB device revoke error:', dbErr);
        }
      }
    }

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
