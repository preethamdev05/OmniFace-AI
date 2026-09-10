/**
 * OmniFace Stage 7: Monitoring & SRE Audit Test Suite
 * 
 * Verifies production observability, Prometheus metrics export, request correlation IDs,
 * hardware telemetry heartbeat ingestion, and automated SRE threshold alerting.
 */

import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';
import pg from 'pg';
import crypto from 'crypto';

function loadEnvLocal() {
  const envPath = path.resolve(process.cwd(), '.env.local');
  if (fs.existsSync(envPath)) {
    const content = fs.readFileSync(envPath, 'utf8');
    for (const line of content.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      const eqIdx = trimmed.indexOf('=');
      if (eqIdx > 0) {
        const key = trimmed.slice(0, eqIdx).trim();
        let val = trimmed.slice(eqIdx + 1).trim();
        if (val.startsWith('"') && val.endsWith('"')) val = val.slice(1, -1);
        if (!process.env[key]) {
          process.env[key] = val;
        }
      }
    }
  }
}
loadEnvLocal();

const { Pool } = pg;

const PORT = 3009;
const BASE_URL = `http://localhost:${PORT}`;

const DB_URL = process.env.DATABASE_URL;
const SESSION_SECRET = 'audit_sre_session_secret_cryptographically_secure_2026';
const MIGRATION_SECRET = 'audit_sre_migration_secret_2026';

process.env.OMNIFACE_SESSION_SECRET = SESSION_SECRET;
process.env.MIGRATION_SECRET = MIGRATION_SECRET;
process.env.ENABLE_TEST_BILLING = 'true';
process.env.NODE_ENV = 'production';

let serverProcess;
let pool;

async function waitForServer(timeoutMs = 25000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`${BASE_URL}/api/v1/health`);
      if (res.status === 200 || res.status === 503) return true;
    } catch {
      // Retry
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error('Server did not start within timeout');
}

async function setup() {
  console.log('🚀 Starting Stage 7 SRE Audit Test Server on port', PORT);
  pool = new Pool({ connectionString: DB_URL });

  // Ensure DB migration is up to date with telemetry columns
  try {
    const client = await pool.connect();
    try {
      await client.query(`
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "battery_pct" integer;
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "temperature" real;
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "thermal_state" varchar(32) DEFAULT 'NOMINAL';
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "active_fps" integer DEFAULT 30;
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "last_heartbeat_at" timestamp;
        ALTER TABLE "devices" ADD COLUMN IF NOT EXISTS "ip_address" varchar(64);
        CREATE INDEX IF NOT EXISTS "idx_devices_org_heartbeat" ON "devices" ("organization_id", "last_heartbeat_at");
      `);
      console.log('✅ Database telemetry columns verified in PostgreSQL');
    } finally {
      client.release();
    }
  } catch (err) {
    console.warn('⚠️ Direct DB connection warning (may run in fallback):', err.message);
  }

  serverProcess = spawn('npx', ['next', 'start', '-p', String(PORT)], {
    cwd: process.cwd(),
    shell: true,
    stdio: 'ignore',
    env: {
      ...process.env,
      DATABASE_URL: DB_URL,
      OMNIFACE_SESSION_SECRET: SESSION_SECRET,
      MIGRATION_SECRET: MIGRATION_SECRET,
      ENABLE_TEST_BILLING: 'true',
    },
  });

  await waitForServer();
  console.log(`✅ Test server listening at ${BASE_URL}\n`);
}

async function teardown() {
  if (serverProcess) {
    try {
      serverProcess.kill('SIGTERM');
      process.kill(serverProcess.pid);
    } catch {}
  }
  if (pool) {
    await pool.end();
  }
  console.log('🛑 SRE Test server shut down cleanly');
}

function createSessionCookie() {
  const payload = {
    userId: '00000000-0000-0000-0000-000000000002',
    email: 'admin@omniface.internal',
    fullName: 'Platform Admin',
    role: 'ADMIN',
    orgId: '00000000-0000-0000-0000-000000000001',
    orgName: 'OmniFace Enterprise Default',
    tier: 'INSTITUTION',
    exp: Math.floor(Date.now() / 1000) + 3600,
  };

  const dataB64 = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const hmac = crypto.createHmac('sha256', SESSION_SECRET);
  hmac.update(dataB64);
  const sigB64 = hmac.digest('base64url');

  return `${dataB64}.${sigB64}`;
}

let totalTests = 0;
let passedTests = 0;

function assert(condition, message) {
  totalTests++;
  if (condition) {
    passedTests++;
    console.log(`  ✅ PASS: ${message}`);
  } else {
    console.error(`  ❌ FAIL: ${message}`);
    throw new Error(`Assertion failed: ${message}`);
  }
}

async function runSreAudit() {
  const sessionCookie = createSessionCookie();

  console.log('═══════════════════════════════════════════════════════════════');
  console.log('       STAGE 7: MONITORING & SRE AUDIT TEST SUITE');
  console.log('═══════════════════════════════════════════════════════════════\n');

  // TEST 1: Request Correlation ID roundtrip & generation
  console.log('▶ [1/7] Request Correlation ID & Tracing Audit');
  {
    const traceId = `trace-audit-${Date.now()}`;
    const res1 = await fetch(`${BASE_URL}/api/v1/health`, {
      headers: { 'x-request-id': traceId },
    });
    assert(res1.status === 200 || res1.status === 503, 'Health endpoint responds');
    assert(res1.headers.get('x-request-id') === traceId, `Correlation ID propagated: got ${res1.headers.get('x-request-id')}`);

    // Request without x-request-id should get an automatically generated ID
    const res2 = await fetch(`${BASE_URL}/api/v1/health`);
    const autoReqId = res2.headers.get('x-request-id');
    assert(autoReqId && autoReqId.startsWith('req-'), `Auto-generated correlation ID: ${autoReqId}`);
  }

  // TEST 2: Comprehensive SRE Health Probe
  console.log('\n▶ [2/7] Comprehensive SRE Health Probe Audit');
  {
    const res = await fetch(`${BASE_URL}/api/v1/health`);
    const data = await res.json();

    assert(data.status === 'healthy' || data.status === 'degraded', `System health status reported: ${data.status}`);
    assert(data.runtime && typeof data.runtime.uptimeSeconds === 'number' || typeof data.uptimeSeconds === 'number', 'Runtime uptime reported');
    assert(data.runtime && data.runtime.memory && data.runtime.memory.rssMb > 0, `Memory RSS reported: ${data.runtime?.memory?.rssMb} MB`);
    assert(data.database && data.database.pool, 'Database pool statistics reported');
    assert(data.database && typeof data.database.latencyMs === 'number', `DB latency reported: ${data.database.latencyMs} ms`);

    if (data.database.status === 'connected') {
      assert(data.database.schema.totalEnterpriseTables === 18, `All 18 enterprise tables registered in probe schema`);
      assert(data.database.schema.verifiedTables === 18, `All 18 enterprise tables verified in PostgreSQL (${data.database.schema.verifiedTables}/18)`);
      assert(data.database.schema.allTablesCreated === true, '18-table schema integrity is 100%');
    }
  }

  // TEST 3: Prometheus / OpenTelemetry Metrics Exporter
  console.log('\n▶ [3/7] Prometheus & OpenTelemetry Metrics Export Audit');
  {
    const res = await fetch(`${BASE_URL}/api/v1/metrics`);
    assert(res.status === 200, 'Metrics endpoint returned HTTP 200');
    assert(res.headers.get('content-type')?.includes('text/plain'), 'Content-Type is text/plain');

    const text = await res.text();
    assert(text.includes('omniface_process_uptime_seconds'), 'Contains omniface_process_uptime_seconds gauge');
    assert(text.includes('omniface_process_memory_rss_bytes'), 'Contains omniface_process_memory_rss_bytes gauge');
    assert(text.includes('omniface_sync_events_total'), 'Contains omniface_sync_events_total counter');
    assert(text.includes('omniface_kiosk_heartbeats_total'), 'Contains omniface_kiosk_heartbeats_total counter');
    assert(text.includes('omniface_thermal_alerts_total'), 'Contains omniface_thermal_alerts_total counter');
    assert(text.includes('http_requests_total'), 'Contains http_requests_total metric');
    assert(text.includes('http_request_duration_ms_summary'), 'Contains http_request_duration_ms_summary quantile metric');
  }

  // TEST 4: Kiosk Hardware Telemetry & Heartbeat Ingestion
  console.log('\n▶ [4/7] Kiosk Hardware Telemetry & Heartbeat Ingestion Audit');
  const testDeviceId = `kiosk_sre_${Date.now()}`;
  const deviceToken = `tok_sre_${crypto.randomUUID()}`;
  {
    // Seed device
    const client = await pool.connect();
    try {
      await client.query(`
        INSERT INTO "devices" (
          "id", "organization_id", "device_identifier", "device_name", 
          "device_token", "app_version", "status", "is_paired"
        ) VALUES (
          gen_random_uuid(), '00000000-0000-0000-0000-000000000001', $1, 'Auditor Kiosk A1',
          $2, 'v2.0.0', 'ONLINE', 1
        )
      `, [testDeviceId, deviceToken]);
    } finally {
      client.release();
    }

    // Ingest nominal telemetry
    const hbRes = await fetch(`${BASE_URL}/api/v1/devices/heartbeat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Device-ID': testDeviceId,
        'X-Device-Token': deviceToken,
      },
      body: JSON.stringify({
        deviceId: testDeviceId,
        batteryPct: 88,
        temperature: 34.2,
        thermalState: 'NOMINAL',
        activeFps: 30,
        pendingEventsCount: 5,
        appVersion: 'v2.0.0-sre',
      }),
    });

    assert(hbRes.status === 200, 'Heartbeat ingestion succeeded (HTTP 200)');
    const hbData = await hbRes.json();
    assert(hbData.success === true, 'Heartbeat returned success: true');

    // Verify in devices list API
    const listRes = await fetch(`${BASE_URL}/api/v1/devices`, {
      headers: { Cookie: `omniface_session=${sessionCookie}` },
    });
    const listData = await listRes.json();
    assert(listData.success === true, 'Devices list returned success');

    const dev = listData.devices.find((d) => d.deviceIdentifier === testDeviceId);
    assert(dev !== undefined, 'Device present in fleet console');
    assert(dev.batteryPct === 88, `Battery % correctly persisted: ${dev.batteryPct}%`);
    assert(dev.thermalState === 'NOMINAL', `Thermal state correctly persisted: ${dev.thermalState}`);
    assert(dev.temperature >= 34.0 && dev.temperature <= 35.0, `Temperature correctly persisted: ${dev.temperature}°C`);
    assert(dev.activeFps === 30, `Active FPS correctly persisted: ${dev.activeFps} FPS`);
  }

  // TEST 5: SRE Thermal Alerting Threshold Breach Audit
  console.log('\n▶ [5/7] SRE Thermal Alerting Threshold Breach Audit');
  {
    // Post critical thermal reading (> 52°C, CRITICAL)
    const alertRes = await fetch(`${BASE_URL}/api/v1/devices/heartbeat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Device-ID': testDeviceId,
        'X-Device-Token': deviceToken,
      },
      body: JSON.stringify({
        deviceId: testDeviceId,
        batteryPct: 65,
        temperature: 54.7,
        thermalState: 'CRITICAL',
        activeFps: 10,
        pendingEventsCount: 2,
      }),
    });

    assert(alertRes.status === 200, 'Critical thermal heartbeat accepted');

    // Verify audit log has SRE_ALERT_THERMAL_CRITICAL
    const client = await pool.connect();
    try {
      const logRes = await client.query(`
        SELECT action, reason, new_values 
        FROM "audit_logs" 
        WHERE action = 'SRE_ALERT_THERMAL_CRITICAL' 
        ORDER BY created_at DESC 
        LIMIT 1;
      `);
      assert(logRes.rows.length > 0, 'SRE_ALERT_THERMAL_CRITICAL logged to audit_logs');
      assert(logRes.rows[0].reason.includes('54.7'), `Audit log records exact temperature breach: ${logRes.rows[0].reason}`);
    } finally {
      client.release();
    }

    // Verify Health Probe captures active alert
    const healthRes = await fetch(`${BASE_URL}/api/v1/health`);
    const healthData = await healthRes.json();
    assert(healthData.fleet.thermalCriticalDevices >= 1, `Health probe reports ${healthData.fleet.thermalCriticalDevices} thermal critical device(s)`);
    assert(
      healthData.activeAlerts.some((a) => a.includes('FLEET_THERMAL_ALERT')),
      'Active alert triggered in health probe'
    );
  }

  // TEST 6: Offline Kiosk Staleness Detection Audit
  console.log('\n▶ [6/7] Offline Kiosk Staleness Detection Audit (> 60m)');
  {
    const staleDeviceId = `kiosk_stale_${Date.now()}`;
    const client = await pool.connect();
    try {
      await client.query(`
        INSERT INTO "devices" (
          "id", "organization_id", "device_identifier", "device_name", 
          "device_token", "app_version", "status", "is_paired", "last_heartbeat_at", "last_sync_at"
        ) VALUES (
          gen_random_uuid(), '00000000-0000-0000-0000-000000000001', $1, 'Stale Kiosk B2',
          'tok_stale', 'v2.0.0', 'ONLINE', 1, NOW() - interval '90 minutes', NOW() - interval '90 minutes'
        )
      `, [staleDeviceId]);
    } finally {
      client.release();
    }

    // Check devices route marks device as OFFLINE and isStale: true
    const devRes = await fetch(`${BASE_URL}/api/v1/devices`, {
      headers: { Cookie: `omniface_session=${sessionCookie}` },
    });
    const devData = await devRes.json();
    const staleDev = devData.devices.find((d) => d.deviceIdentifier === staleDeviceId);
    assert(staleDev !== undefined, 'Stale device found in list');
    assert(staleDev.status === 'OFFLINE', `Stale device marked OFFLINE: ${staleDev.status}`);
    assert(staleDev.isStale === true, 'Stale device has isStale: true');

    // Health probe captures offline device alert
    const healthRes = await fetch(`${BASE_URL}/api/v1/health`);
    const healthData = await healthRes.json();
    assert(healthData.fleet.offlineDevices >= 1, `Health probe reports ${healthData.fleet.offlineDevices} offline device(s)`);
    assert(
      healthData.activeAlerts.some((a) => a.includes('FLEET_OFFLINE_DEVICES')),
      'FLEET_OFFLINE_DEVICES alert triggered in health probe'
    );
  }

  // TEST 7: Database Pool Saturation Resiliency Audit
  console.log('\n▶ [7/7] Database Pool Saturation & Resiliency Audit');
  {
    const poolRes = await fetch(`${BASE_URL}/api/v1/db/status`);
    const poolData = await poolRes.json();
    assert(poolData.status === 'connected', 'Database status reports connected');
    assert(poolData.pool !== undefined, 'Database pool statistics returned');
    assert(typeof poolData.pool.total === 'number', `Pool total count: ${poolData.pool.total}`);
    assert(poolData.pool.waiting === 0, `Pool waiting queries: ${poolData.pool.waiting}`);

    // Fire 30 concurrent health requests to stress connection checkout/checkin
    const promises = Array.from({ length: 30 }, () => fetch(`${BASE_URL}/api/v1/health`));
    const results = await Promise.all(promises);
    assert(results.every((r) => r.status === 200 || r.status === 503), 'All 30 concurrent health checks succeeded');

    const postBurstRes = await fetch(`${BASE_URL}/api/v1/db/status`);
    const postBurstData = await postBurstRes.json();
    assert(postBurstData.pool && postBurstData.pool.waiting === 0, 'Zero lingering waiting connections after burst');
  }

  console.log('\n═══════════════════════════════════════════════════════════════');
  console.log(`🎉 SRE AUDIT COMPLETE: ${passedTests}/${totalTests} TESTS PASSED (100%)`);
  console.log('═══════════════════════════════════════════════════════════════\n');
}

async function main() {
  try {
    await setup();
    await runSreAudit();
    await teardown();
    process.exit(0);
  } catch (err) {
    console.error('\n❌ SRE AUDIT FAILED WITH ERROR:', err);
    await teardown();
    process.exit(1);
  }
}

main();
