import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getPool, getDb } from '@/db';
import { devices } from '@/db/schema';
import { sreMetrics } from '@/lib/metrics';
import { sql } from 'drizzle-orm';

const ALL_18_ENTERPRISE_TABLES = [
  'organizations',
  'users',
  'organization_members',
  'departments',
  'classes',
  'students',
  'student_classes',
  'devices',
  'face_templates',
  'attendance_sessions',
  'attendance_events',
  'attendance_adjustments',
  'sync_operations',
  'subscriptions',
  'subscription_events',
  'payments',
  'invoices',
  'audit_logs',
];

export async function GET(req: NextRequest) {
  const startTime = Date.now();
  const requestId = req.headers.get('x-request-id') || `req-${Math.random().toString(36).substring(2, 10)}`;
  const alerts: string[] = [];

  const configured = isDbConfigured();
  let dbStatus: 'connected' | 'unconfigured' | 'error' = configured ? 'connected' : 'unconfigured';
  let dbLatencyMs = 0;
  let poolStats = { total: 0, idle: 0, waiting: 0 };
  let schemaValidated = false;
  let missingTables: string[] = [];
  let pgvectorActive = false;

  let fleetSummary = {
    totalDevices: 0,
    onlineDevices: 0,
    offlineDevices: 0,
    thermalCriticalDevices: 0,
    totalPendingQueue: 0,
  };

  if (configured) {
    try {
      const pool = getPool();
      poolStats = {
        total: pool.totalCount,
        idle: pool.idleCount,
        waiting: pool.waitingCount,
      };

      if (poolStats.waiting > 5) {
        alerts.push('DB_POOL_SATURATION: Query waiting queue exceeds 5 connections');
      }

      const client = await pool.connect();
      const pingStart = Date.now();
      try {
        await client.query('SELECT 1');
        dbLatencyMs = Date.now() - pingStart;

        // Verify enterprise schema tables
        const tableRes = await client.query(`
          SELECT table_name 
          FROM information_schema.tables 
          WHERE table_schema = 'public';
        `);
        const existingTables = new Set(tableRes.rows.map((r: any) => r.table_name));
        missingTables = ALL_18_ENTERPRISE_TABLES.filter((t) => !existingTables.has(t));
        schemaValidated = missingTables.length === 0;

        // Check pgvector
        const extRes = await client.query("SELECT extname FROM pg_extension WHERE extname = 'vector'");
        pgvectorActive = extRes.rows.length > 0;
      } finally {
        client.release();
      }

      // Query fleet device health
      const database = getDb();
      if (database) {
        const deviceRows = await database.select().from(devices);
        fleetSummary.totalDevices = deviceRows.length;

        const sixtyMinsAgo = Date.now() - 60 * 60 * 1000;

        for (const dev of deviceRows) {
          const lastActivity = Math.max(
            dev.lastHeartbeatAt ? new Date(dev.lastHeartbeatAt).getTime() : 0,
            dev.lastSyncAt ? new Date(dev.lastSyncAt).getTime() : 0
          );

          if (dev.status === 'ONLINE' && (lastActivity === 0 || lastActivity < sixtyMinsAgo)) {
            fleetSummary.offlineDevices += 1;
          } else if (dev.status === 'ONLINE') {
            fleetSummary.onlineDevices += 1;
          }

          if (dev.thermalState === 'CRITICAL' || (dev.temperature && dev.temperature > 52.0)) {
            fleetSummary.thermalCriticalDevices += 1;
          }

          fleetSummary.totalPendingQueue += dev.pendingEventsCount || 0;
        }

        if (fleetSummary.offlineDevices > 0) {
          alerts.push(`FLEET_OFFLINE_DEVICES: ${fleetSummary.offlineDevices} kiosks have missed heartbeats for > 60 minutes`);
        }
        if (fleetSummary.thermalCriticalDevices > 0) {
          alerts.push(`FLEET_THERMAL_ALERT: ${fleetSummary.thermalCriticalDevices} kiosks are in CRITICAL thermal state`);
        }
        if (fleetSummary.totalPendingQueue > 500) {
          alerts.push(`FLEET_QUEUE_BACKLOG: Kiosk sync queue backlog exceeds 500 events (${fleetSummary.totalPendingQueue} pending)`);
        }
      }
    } catch (err: any) {
      dbStatus = 'error';
      alerts.push(`DB_CONNECTION_ERROR: ${err?.message || 'Database ping failure'}`);
    }
  }

  // Determine overall status
  let overallStatus: 'healthy' | 'degraded' | 'unhealthy' = 'healthy';
  if (dbStatus === 'error' || missingTables.length > 5) {
    overallStatus = 'unhealthy';
  } else if (
    dbStatus === 'unconfigured' ||
    missingTables.length > 0 ||
    fleetSummary.thermalCriticalDevices > 0 ||
    fleetSummary.offlineDevices > 0 ||
    poolStats.waiting > 0
  ) {
    overallStatus = 'degraded';
  }

  const mem = process.memoryUsage();
  const summary = sreMetrics.getMetricsSummary();

  const responseBody = {
    status: overallStatus,
    timestamp: Date.now(),
    serverTime: new Date().toISOString(),
    uptimeSeconds: summary.uptimeSec,
    version: '2.0.0-enterprise',
    runtime: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      memory: {
        rssMb: Math.round((mem.rss / 1024 / 1024) * 100) / 100,
        heapTotalMb: Math.round((mem.heapTotal / 1024 / 1024) * 100) / 100,
        heapUsedMb: Math.round((mem.heapUsed / 1024 / 1024) * 100) / 100,
      },
    },
    database: {
      status: dbStatus,
      latencyMs: dbLatencyMs,
      pool: poolStats,
      schema: {
        totalEnterpriseTables: ALL_18_ENTERPRISE_TABLES.length,
        verifiedTables: ALL_18_ENTERPRISE_TABLES.length - missingTables.length,
        missingTables,
        allTablesCreated: schemaValidated,
        pgvectorActive,
      },
    },
    fleet: fleetSummary,
    metrics: {
      totalHttpRequests: summary.totalRequests,
      p95LatencyMs: summary.p95DurationMs,
      totalSyncEvents: summary.syncEventsTotal,
      totalHeartbeats: summary.heartbeatsTotal,
      totalThermalAlerts: summary.thermalAlertsTotal,
    },
    activeAlerts: alerts,
  };

  const duration = Date.now() - startTime;
  sreMetrics.recordRequest('GET', '/api/v1/health', overallStatus === 'unhealthy' ? 503 : 200, duration);

  return NextResponse.json(responseBody, {
    status: overallStatus === 'unhealthy' ? 503 : 200,
    headers: {
      'x-request-id': requestId,
      'Cache-Control': 'no-store, max-age=0',
    },
  });
}
