import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { devices, auditLogs } from '@/db/schema';
import { eq, and } from 'drizzle-orm';
import { sreMetrics } from '@/lib/metrics';
import { runLog } from '@/lib/logger';

interface HeartbeatPayload {
  deviceId?: string;
  deviceIdentifier?: string;
  batteryPct?: number;
  temperature?: number;
  thermalState?: 'NOMINAL' | 'WARM' | 'CRITICAL';
  activeFps?: number;
  pendingEventsCount?: number;
  appVersion?: string;
  ipAddress?: string;
}

export async function POST(req: NextRequest) {
  const startTime = Date.now();
  const requestId = req.headers.get('x-request-id') || `req-${Math.random().toString(36).substring(2, 10)}`;
  const log = runLog('http_api', requestId);

  try {
    const deviceTokenHeader = req.headers.get('X-Device-Token') || req.headers.get('Authorization')?.replace(/^Bearer\s+/i, '');
    const deviceIdHeader = req.headers.get('X-Device-ID');

    let body: HeartbeatPayload = {};
    try {
      body = await req.json();
    } catch {
      // Empty or non-JSON body
    }

    const deviceIdentifier = body.deviceId || body.deviceIdentifier || deviceIdHeader;

    if (!deviceIdentifier) {
      return NextResponse.json(
        { success: false, error: 'deviceIdentifier or X-Device-ID header required' },
        { status: 400, headers: { 'x-request-id': requestId } }
      );
    }

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: true, status: 'acknowledged_unconfigured', timestamp: Date.now() },
        { status: 200, headers: { 'x-request-id': requestId } }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json(
        { success: false, error: 'Database unavailable' },
        { status: 503, headers: { 'x-request-id': requestId } }
      );
    }

    // Find the device
    const matching = await database
      .select()
      .from(devices)
      .where(eq(devices.deviceIdentifier, deviceIdentifier))
      .limit(1);

    if (matching.length === 0) {
      return NextResponse.json(
        { success: false, error: 'Device not recognized' },
        { status: 404, headers: { 'x-request-id': requestId } }
      );
    }

    const targetDevice = matching[0];

    // Check revocation
    if (targetDevice.status === 'REVOKED') {
      return NextResponse.json(
        { success: false, error: 'Device authorization has been revoked' },
        { status: 403, headers: { 'x-request-id': requestId } }
      );
    }

    // Verify token if device has an issued token
    if (targetDevice.deviceToken && deviceTokenHeader && targetDevice.deviceToken !== deviceTokenHeader) {
      return NextResponse.json(
        { success: false, error: 'Invalid device credentials' },
        { status: 401, headers: { 'x-request-id': requestId } }
      );
    }

    const batteryPct = typeof body.batteryPct === 'number' ? Math.round(body.batteryPct) : targetDevice.batteryPct;
    const temperature = typeof body.temperature === 'number' ? body.temperature : targetDevice.temperature;
    const thermalState = body.thermalState || targetDevice.thermalState || 'NOMINAL';
    const activeFps = typeof body.activeFps === 'number' ? body.activeFps : (targetDevice.activeFps || 30);
    const pendingEventsCount = typeof body.pendingEventsCount === 'number' ? body.pendingEventsCount : targetDevice.pendingEventsCount;
    const appVersion = body.appVersion || targetDevice.appVersion || 'v2.0.0';
    const ipAddress = body.ipAddress || req.headers.get('x-forwarded-for') || req.headers.get('x-real-ip') || targetDevice.ipAddress;

    const now = new Date();

    // Update device record with current telemetry
    await database
      .update(devices)
      .set({
        status: 'ONLINE',
        batteryPct,
        temperature,
        thermalState,
        activeFps,
        pendingEventsCount,
        appVersion,
        ipAddress,
        lastHeartbeatAt: now,
        updatedAt: now,
      })
      .where(eq(devices.id, targetDevice.id));

    // Record SRE metric
    sreMetrics.incrementHeartbeats();

    // Automated Alerting Triggers
    if (thermalState === 'CRITICAL' || (temperature !== null && temperature !== undefined && temperature > 52.0)) {
      sreMetrics.incrementThermalAlerts();
      log.warn('SRE_ALERT_DEVICE_THERMAL_CRITICAL', {
        deviceId: targetDevice.id,
        deviceIdentifier,
        temperature,
        thermalState,
      });

      try {
        await database.insert(auditLogs).values({
          organizationId: targetDevice.organizationId,
          userId: null,
          action: 'SRE_ALERT_THERMAL_CRITICAL',
          entityType: 'DEVICE',
          entityId: targetDevice.id,
          reason: `Device ${deviceIdentifier} triggered thermal alarm: ${temperature}°C (${thermalState})`,
          newValues: JSON.stringify({ temperature, thermalState, batteryPct, activeFps }),
        });
      } catch (logErr) {
        console.warn('Failed to insert SRE thermal audit log:', logErr);
      }
    }

    if (batteryPct !== null && batteryPct !== undefined && batteryPct < 15) {
      log.warn('SRE_ALERT_DEVICE_BATTERY_LOW', {
        deviceId: targetDevice.id,
        deviceIdentifier,
        batteryPct,
      });

      try {
        await database.insert(auditLogs).values({
          organizationId: targetDevice.organizationId,
          userId: null,
          action: 'SRE_ALERT_BATTERY_LOW',
          entityType: 'DEVICE',
          entityId: targetDevice.id,
          reason: `Device ${deviceIdentifier} battery critical: ${batteryPct}%`,
          newValues: JSON.stringify({ batteryPct, temperature, activeFps }),
        });
      } catch (logErr) {
        console.warn('Failed to insert SRE battery audit log:', logErr);
      }
    }

    const duration = Date.now() - startTime;
    sreMetrics.recordRequest('POST', '/api/v1/devices/heartbeat', 200, duration);

    return NextResponse.json(
      {
        success: true,
        deviceIdentifier,
        status: 'ONLINE',
        lastHeartbeatAt: now.toISOString(),
        serverTime: Date.now(),
      },
      {
        status: 200,
        headers: { 'x-request-id': requestId },
      }
    );
  } catch (error: any) {
    const duration = Date.now() - startTime;
    sreMetrics.recordRequest('POST', '/api/v1/devices/heartbeat', 500, duration);
    log.error('HEARTBEAT_PROCESSING_FAILED', {}, error);

    return NextResponse.json(
      { success: false, error: error?.message || 'Internal server error' },
      { status: 500, headers: { 'x-request-id': requestId } }
    );
  }
}
