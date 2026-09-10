import crypto from 'crypto';
import { spawn } from 'child_process';
import http from 'http';

const PORT = 3005;
const BASE_URL = `http://127.0.0.1:${PORT}`;
const SECRET = process.env.OMNIFACE_SESSION_SECRET || 'omniface_integration_test_secret_key_8832a';
const MIGRATION_SECRET = process.env.MIGRATION_SECRET || 'omniface_integration_migration_key_7719b';

function signToken(payload) {
  const exp = payload.exp || Math.floor(Date.now() / 1000) + 7 * 24 * 60 * 60;
  const sessionData = { ...payload, exp };
  const dataB64 = Buffer.from(JSON.stringify(sessionData)).toString('base64url');
  const signature = crypto
    .createHmac('sha256', SECRET)
    .update(dataB64)
    .digest('base64url');
  return `${dataB64}.${signature}`;
}

function verifyToken(token) {
  if (!token || typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [dataB64, sigB64] = parts;
  try {
    const expectedSig = crypto
      .createHmac('sha256', SECRET)
      .update(dataB64)
      .digest('base64url');
    const sigBuf = Buffer.from(sigB64);
    const expBuf = Buffer.from(expectedSig);
    if (sigBuf.length !== expBuf.length || !crypto.timingSafeEqual(sigBuf, expBuf)) {
      return null;
    }
    const payload = JSON.parse(Buffer.from(dataB64, 'base64url').toString('utf8'));
    if (payload.exp && payload.exp < Math.floor(Date.now() / 1000)) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

async function fetchUrl(path, options = {}) {
  const url = `${BASE_URL}${path}`;
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const req = http.request(
      {
        hostname: parsed.hostname,
        port: parsed.port,
        path: parsed.pathname + parsed.search,
        method: options.method || 'GET',
        headers: options.headers || {},
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const buffer = Buffer.concat(chunks);
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: buffer.toString('utf8'),
            rawBody: buffer,
          });
        });
      }
    );
    req.on('error', reject);
    if (options.body) {
      req.write(typeof options.body === 'string' ? options.body : JSON.stringify(options.body));
    }
    req.end();
  });
}

async function waitForServer(timeoutMs = 25000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetchUrl('/login');
      if (res.status === 200) return true;
    } catch {
      // Retry
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error('Server did not start within timeout');
}

async function runTests() {
  console.log('='.repeat(65));
  console.log('🚀 OMNIFACE PRODUCTION VERIFICATION & AUDIT SUITE');
  console.log('='.repeat(65));

  let passed = 0;
  let failed = 0;

  function assert(name, condition, extra = '') {
    if (condition) {
      console.log(` ✅ PASS: ${name}`);
      passed++;
    } else {
      console.error(` ❌ FAIL: ${name} -> ${extra}`);
      failed++;
    }
  }

  // --- UNIT: CRYPTOGRAPHIC TOKEN VERIFICATION ---
  console.log('\n--- 1. Cryptographic Session Token Verification ---');
  const testUser = {
    userId: '00000000-0000-0000-0000-000000000002',
    email: 'admin@omniface.internal',
    fullName: 'Platform Admin',
    role: 'ADMIN',
    orgId: '00000000-0000-0000-0000-000000000001',
    orgName: 'OmniFace Enterprise',
    tier: 'INSTITUTION',
  };

  const freeUser = {
    userId: '00000000-0000-0000-0000-000000000003',
    email: 'free@omniface.internal',
    fullName: 'Free Plan Starter',
    role: 'OWNER',
    orgId: '00000000-0000-0000-0000-000000000001',
    orgName: 'OmniFace Starter Org',
    tier: 'FREE',
  };

  const validToken = signToken(testUser);
  const authHeaders = { Cookie: `omniface_session=${validToken}` };
  const freeToken = signToken(freeUser);
  const freeAuthHeaders = { Cookie: `omniface_session=${freeToken}` };

  const verifiedPayload = verifyToken(validToken);
  assert('Valid HMAC session token parses correctly', verifiedPayload?.userId === testUser.userId);
  assert('Verified token contains correct organization ID', verifiedPayload?.orgId === testUser.orgId);

  const tamperedToken = 'eyJ1c2VySWQiOiJmYWtlIn0.' + validToken.split('.')[1];
  assert('Tampered session token payload is rejected', verifyToken(tamperedToken) === null);

  const expiredToken = signToken({ ...testUser, exp: Math.floor(Date.now() / 1000) - 3600 });
  assert('Expired session token is rejected', verifyToken(expiredToken) === null);

  // --- INTEGRATION: LIVE HTTP ROUTE SECURITY ---
  console.log('\n--- 2. Starting Next.js Production Server on port ' + PORT + ' ---');
  const serverProcess = spawn('npx', ['next', 'start', '-p', String(PORT)], {
    cwd: process.cwd(),
    shell: true,
    stdio: 'inherit',
    env: {
      ...process.env,
      OMNIFACE_SESSION_SECRET: SECRET,
      MIGRATION_SECRET: MIGRATION_SECRET,
      ENABLE_TEST_BILLING: 'true',
    },
  });

  try {
    await waitForServer();
    console.log(' ✅ Next.js server live on ' + BASE_URL);

    // --- 3. EDGE MIDDLEWARE GATING TESTS ---
    console.log('\n--- 3. Edge Middleware Route Protection ---');
    const routesToTest = ['/students', '/classes', '/devices', '/attendance', '/reports', '/staff', '/settings'];
    for (const r of routesToTest) {
      const res = await fetchUrl(r);
      assert(
        `Unauthenticated GET ${r} redirects to /login`,
        res.status === 307 && (res.headers.location || '').includes('/login'),
        `(status: ${res.status})`
      );
    }

    const authStudents = await fetchUrl('/students', { headers: authHeaders });
    assert('Authenticated GET /students allows access for Institution tier (HTTP 200)', authStudents.status === 200);

    const freeStudents = await fetchUrl('/students', { headers: freeAuthHeaders });
    assert(
      'Free tier GET /students redirects to /subscription?upgrade=required (HTTP 307)',
      freeStudents.status === 307 && (freeStudents.headers.location || '').includes('/subscription?upgrade=required'),
      `(status: ${freeStudents.status}, location: ${freeStudents.headers.location})`
    );

    // --- 4. SENSITIVE API LOCKDOWN TESTS ---
    console.log('\n--- 4. Sensitive API Endpoint Lockdown ---');

    // Attendance sync without device authentication
    const syncUnauth = await fetchUrl('/api/v1/attendance/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ records: [] }),
    });
    assert('Unauthenticated POST /api/v1/attendance/sync returns 401 Unauthorized', syncUnauth.status === 401);

    // Kiosks provision without session
    const provUnauth = await fetchUrl('/api/v1/kiosks/provision', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ location: 'Gate' }),
    });
    assert('Unauthenticated POST /api/v1/kiosks/provision returns 401 Unauthorized', provUnauth.status === 401);

    // Sync push without device token or key
    const syncPushUnauth = await fetchUrl('/api/v1/sync/push', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ records: [] }),
    });
    assert('Unauthenticated POST /api/v1/sync/push returns 401 Unauthorized', syncPushUnauth.status === 401);

    // Student delete without session
    const deleteUnauth = await fetchUrl('/api/v1/users?id=usr_mock', { method: 'DELETE' });
    assert('Unauthenticated DELETE /api/v1/users returns 401 Unauthorized', deleteUnauth.status === 401);

    // Classes create without session
    const createClassUnauth = await fetchUrl('/api/v1/classes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'CS101' }),
    });
    assert('Unauthenticated POST /api/v1/classes returns 401 Unauthorized', createClassUnauth.status === 401);

    // Departments create without session
    const createDeptUnauth = await fetchUrl('/api/v1/departments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'CS', code: 'CS' }),
    });
    assert('Unauthenticated POST /api/v1/departments returns 401 Unauthorized', createDeptUnauth.status === 401);

    // Database migrate without migration secret
    const migrateUnauth = await fetchUrl('/api/v1/db/migrate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    assert('POST /api/v1/db/migrate without X-Migration-Secret returns 403 Forbidden', migrateUnauth.status === 403);

    // Run database migration with authorized secret
    const migrateAuth = await fetchUrl('/api/v1/db/migrate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Migration-Secret': MIGRATION_SECRET,
      },
      body: JSON.stringify({}),
    });
    assert(
      'Authorized POST /api/v1/db/migrate executes successfully (HTTP 200 or 400 without DB)',
      migrateAuth.status === 200 || (migrateAuth.status === 400 && migrateAuth.body.includes('DATABASE_URL is not configured')),
      `(status: ${migrateAuth.status}, body: ${migrateAuth.body})`
    );

    // Audit logs without session
    const auditLogsUnauth = await fetchUrl('/api/v1/audit-logs');
    assert('Unauthenticated GET /api/v1/audit-logs returns 401 Unauthorized', auditLogsUnauth.status === 401);

    // --- 5. AUTHENTICATED MULTI-TENANT API OPERATIONS ---
    console.log('\n--- 5. Authenticated Multi-Tenant API Operations ---');

    // /api/v1/auth/me
    const meRes = await fetchUrl('/api/v1/auth/me', { headers: authHeaders });
    assert('Authenticated GET /api/v1/auth/me returns 200 OK', meRes.status === 200);

    // GET /api/v1/classes
    const classesRes = await fetchUrl('/api/v1/classes', { headers: authHeaders });
    assert('Authenticated GET /api/v1/classes returns 200 OK', classesRes.status === 200);

    // GET /api/v1/departments
    const deptsRes = await fetchUrl('/api/v1/departments', { headers: authHeaders });
    assert('Authenticated GET /api/v1/departments returns 200 OK', deptsRes.status === 200);

    // GET /api/v1/devices
    const devicesRes = await fetchUrl('/api/v1/devices', { headers: authHeaders });
    assert('Authenticated GET /api/v1/devices returns 200 OK', devicesRes.status === 200);

    // POST /api/v1/devices/generate-code
    const genCodeRes = await fetchUrl('/api/v1/devices/generate-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({}),
    });
    assert('Authenticated POST /api/v1/devices/generate-code returns 200 OK', genCodeRes.status === 200);
    const genCodeData = JSON.parse(genCodeRes.body);
    assert('Generated pairing code is a 6-digit string', typeof genCodeData?.pairingCode === 'string' && genCodeData.pairingCode.length === 6);

    // --- 6. REAL SETTINGS PERSISTENCE TESTS ---
    console.log('\n--- 6. Real Settings Persistence ---');
    const settingsGet = await fetchUrl('/api/v1/settings', { headers: authHeaders });
    assert('GET /api/v1/settings returns 200 OK', settingsGet.status === 200, `(status: ${settingsGet.status}, body: ${settingsGet.body})`);
    let settingsGetData = {};
    try { settingsGetData = JSON.parse(settingsGet.body); } catch {}
    assert('Settings contain defaultStartTime', typeof settingsGetData?.settings?.defaultStartTime === 'string', `(body: ${settingsGet.body})`);

    const settingsPut = await fetchUrl('/api/v1/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        orgName: 'OmniFace Enterprise',
        contactEmail: 'admin@omniface.internal',
        defaultStartTime: '08:45',
        graceMinutes: 20,
        autoEvaluateStatus: true,
        dpdpCompliance: true,
      }),
    });
    assert('PUT /api/v1/settings returns 200 OK', settingsPut.status === 200, `(status: ${settingsPut.status}, body: ${settingsPut.body})`);
    let settingsPutData = {};
    try { settingsPutData = JSON.parse(settingsPut.body); } catch {}
    assert('Settings updated with new values', settingsPutData?.settings?.defaultStartTime === '08:45' && settingsPutData?.settings?.graceMinutes === 20, `(body: ${settingsPut.body})`);

    // --- 7. REAL STAFF INVITATION & ACCEPTANCE TESTS ---
    console.log('\n--- 7. Real Staff Management & Secure Invitations ---');
    const testEmail = `prof.test.${Date.now()}@university.edu`;
    const inviteRes = await fetchUrl('/api/v1/staff/invite', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        email: testEmail,
        fullName: 'Prof. Test Evaluator',
        role: 'TEACHER',
      }),
    });
    assert('POST /api/v1/staff/invite returns 201 Created', inviteRes.status === 201, `(status: ${inviteRes.status}, body: ${inviteRes.body})`);
    let inviteData = {};
    try { inviteData = JSON.parse(inviteRes.body); } catch {}
    const rawInviteToken = inviteData?.invitation?.inviteToken || inviteData?.inviteToken || inviteData?.token;
    assert('Invitation returns valid invitation link and raw token', typeof rawInviteToken === 'string' && rawInviteToken.length >= 32, `(body: ${inviteRes.body})`);

    const acceptRes = await fetchUrl('/api/v1/staff/accept-invite', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        inviteToken: rawInviteToken || 'fake_tok',
        fullName: 'Prof. Test Evaluator',
        password: 'SecurePassword123!',
      }),
    });
    assert('POST /api/v1/staff/accept-invite returns 200 OK', acceptRes.status === 200, `(status: ${acceptRes.status}, body: ${acceptRes.body})`);
    let acceptData = {};
    try { acceptData = JSON.parse(acceptRes.body); } catch {}
    const hasSession = Boolean(
      acceptData?.user?.sessionToken ||
      acceptData?.sessionToken ||
      (acceptRes.headers['set-cookie'] && (Array.isArray(acceptRes.headers['set-cookie']) ? acceptRes.headers['set-cookie'].some(c => c.includes('omniface_session')) : acceptRes.headers['set-cookie'].includes('omniface_session')))
    );
    assert('Accepted staff member receives valid session token', hasSession, `(body: ${acceptRes.body})`);

    // --- 8. REAL ATTENDANCE EVENT QUERY TESTS ---
    console.log('\n--- 8. Real Attendance Event Query API ---');
    const attendanceRes = await fetchUrl('/api/v1/attendance?date=ALL', { headers: authHeaders });
    assert('GET /api/v1/attendance returns 200 OK', attendanceRes.status === 200);
    const attendanceData = JSON.parse(attendanceRes.body);
    assert('Attendance response returns array without error', Array.isArray(attendanceData?.records));

    // --- 9. REAL REPORTS AGGREGATION TESTS ---
    console.log('\n--- 9. Real Reports Aggregation API ---');
    const reportsRes = await fetchUrl('/api/v1/reports?month=2026-09&department=ALL', { headers: authHeaders });
    assert('GET /api/v1/reports returns 200 OK', reportsRes.status === 200);
    const reportsData = JSON.parse(reportsRes.body);
    assert('Reports response returns summary and rows', typeof reportsData?.summary?.cohortAverage === 'number' && Array.isArray(reportsData?.rows));

    // Export tests: Free tier blocked (403)
    const exportFreeRes = await fetchUrl('/api/v1/reports/export?format=csv', { headers: freeAuthHeaders });
    assert('Free tier GET /api/v1/reports/export is rejected (HTTP 403 Forbidden)', exportFreeRes.status === 403, `(status: ${exportFreeRes.status})`);

    // Export CSV with Institution session (200)
    const exportCsvRes = await fetchUrl('/api/v1/reports/export?format=csv', { headers: authHeaders });
    assert(
      'Institution GET /api/v1/reports/export?format=csv returns 200 OK with text/csv',
      exportCsvRes.status === 200 && (exportCsvRes.headers['content-type'] || '').includes('text/csv'),
      `(status: ${exportCsvRes.status}, type: ${exportCsvRes.headers['content-type']}, body: ${exportCsvRes.body})`
    );

    // Export XLSX with Institution session (200 with ExcelJS binary)
    const exportXlsxRes = await fetchUrl('/api/v1/reports/export?format=xlsx', { headers: authHeaders });
    assert(
      'Institution GET /api/v1/reports/export?format=xlsx returns 200 OK with application/vnd.openxmlformats',
      exportXlsxRes.status === 200 && (exportXlsxRes.headers['content-type'] || '').includes('spreadsheetml.sheet') && (exportXlsxRes.rawBody?.length || 0) > 100,
      `(status: ${exportXlsxRes.status}, size: ${exportXlsxRes.rawBody?.length}, body: ${exportXlsxRes.body})`
    );

    // Export PDF with Institution session (200 with PDFKit binary)
    const exportPdfRes = await fetchUrl('/api/v1/reports/export?format=pdf', { headers: authHeaders });
    assert(
      'Institution GET /api/v1/reports/export?format=pdf returns 200 OK with application/pdf',
      exportPdfRes.status === 200 && (exportPdfRes.headers['content-type'] || '').includes('application/pdf') && (exportPdfRes.rawBody?.length || 0) > 100,
      `(status: ${exportPdfRes.status}, size: ${exportPdfRes.rawBody?.length}, body: ${exportPdfRes.body})`
    );

    // --- 10. REAL INSTITUTION B2B LEAD PIPELINE TESTS ---
    console.log('\n--- 10. Real Institution B2B Sales Lead API ---');
    const leadRes = await fetchUrl('/api/v1/institution/contact', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        organizationName: 'Indian Institute of Science',
        contactName: 'Dean of Technology',
        email: 'dean.tech@iisc.ac.in',
        phone: '+91 9988776655',
        expectedSeats: 1500,
        notes: 'Requirement for 12 turnstile biometric kiosks across main gates.',
      }),
    });
    assert('POST /api/v1/institution/contact returns 201 Created', leadRes.status === 201, `(status: ${leadRes.status}, body: ${leadRes.body})`);
    let leadData = {};
    try { leadData = JSON.parse(leadRes.body); } catch {}
    assert('Institution inquiry returns leadId and confirmation message', Boolean(leadData?.leadId), `(body: ${leadRes.body})`);

    // --- 11. BILLING & GOOGLE PLAY TOKEN VERIFICATION TESTS ---
    console.log('\n--- 11. Commercial Subscriptions & Google Play Verification ---');
    const subGetRes = await fetchUrl('/api/v1/subscriptions', { headers: authHeaders });
    assert('GET /api/v1/subscriptions returns 200 OK', subGetRes.status === 200);
    const subGetData = JSON.parse(subGetRes.body);
    assert('Subscription details returned with available plans', Array.isArray(subGetData?.availablePlans));

    // Valid Google Play purchase token verification
    const testPlayToken = 'gplay_tok_' + crypto.randomBytes(24).toString('hex');
    const subPostRes = await fetchUrl('/api/v1/subscriptions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        tier: 'PRO',
        provider: 'GOOGLE_PLAY',
        purchaseToken: testPlayToken,
      }),
    });
    assert('POST /api/v1/subscriptions with Google Play token activates tier (HTTP 200)', subPostRes.status === 200);
    const subPostData = JSON.parse(subPostRes.body);
    assert('Pro subscription activated with order ID', subPostData?.subscription?.tier === 'PRO' && Boolean(subPostData?.subscription?.orderId));

    // Direct institution self-activation without quote rejected for non-owners
    const subInstUnauth = await fetchUrl('/api/v1/subscriptions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: `omniface_session=${signToken({ ...testUser, role: 'ADMIN' })}`,
      },
      body: JSON.stringify({
        tier: 'INSTITUTION',
        provider: 'WEBSITE_CUSTOM',
      }),
    });
    assert('Self-upgrading to INSTITUTION without sales agreement returns 400 Bad Request', subInstUnauth.status === 400);

    // Restore INSTITUTION tier for org so downstream tests have full institutional capacity
    await fetchUrl('/api/v1/subscriptions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: `omniface_session=${signToken({ ...testUser, role: 'OWNER' })}`,
      },
      body: JSON.stringify({
        tier: 'INSTITUTION',
        provider: 'WEBSITE_CUSTOM',
      }),
    });

    // --- 12. HARDENED KIOSK PROVISIONING TESTS ---
    console.log('\n--- 12. Hardened Kiosk Provisioning ---');
    const provAuth = await fetchUrl('/api/v1/kiosks/provision', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        location: 'North Gate Turnstile',
        model: 'OmniFace Enterprise Terminal K10',
      }),
    });
    assert('Authenticated POST /api/v1/kiosks/provision returns 201 Created', provAuth.status === 201, `(status: ${provAuth.status}, body: ${provAuth.body})`);
    let provData = {};
    try { provData = JSON.parse(provAuth.body); } catch {}
    assert('Provisioned kiosk contains API key and QR config bound to orgId', Boolean(provData?.kiosk?.apiKey) && provData?.kiosk?.qrConfig?.orgId === testUser.orgId, `(body: ${provAuth.body})`);

    // Clean up provisioned test kiosk
    if (provData?.kiosk?.kioskId) {
      await fetchUrl(`/api/v1/devices?deviceId=${encodeURIComponent(provData.kiosk.kioskId)}`, {
        method: 'DELETE',
        headers: authHeaders,
      });
    }

    // --- 13. SELF-SERVE ONBOARDING & FCM TOKEN TESTS ---
    console.log('\n--- 13. Self-Serve Institutional Onboarding & FCM Token API ---');
    const onboardRes = await fetchUrl('/api/v1/onboarding', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        orgName: 'Delhi Public School Test',
        adminFullName: 'Principal Test',
        adminEmail: `principal.${Date.now()}@dps.edu.in`,
        password: 'Password123!',
      }),
    });
    assert(
      'POST /api/v1/onboarding returns 201 Created or 200',
      onboardRes.status === 201 || onboardRes.status === 200,
      `(status: ${onboardRes.status}, body: ${onboardRes.body})`
    );

    const fcmRes = await fetchUrl('/api/v1/notifications/fcm-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify({
        fcmToken: 'fcm_mock_registration_token_' + Date.now(),
        platform: 'android',
        deviceId: 'TEST-DEVICE-01',
      }),
    });
    assert(
      'POST /api/v1/notifications/fcm-token returns 200 OK',
      fcmRes.status === 200 || fcmRes.status === 201,
      `(status: ${fcmRes.status}, body: ${fcmRes.body})`
    );

  } finally {
    console.log('\n--- Shutting down test server ---');
    serverProcess.kill('SIGTERM');
    try {
      process.kill(serverProcess.pid);
    } catch {}
  }

  console.log('\n' + '='.repeat(65));
  console.log(`VERIFICATION COMPLETE: ${passed} Passed, ${failed} Failed`);
  console.log('='.repeat(65));

  if (failed > 0) {
    process.exit(1);
  }
}

runTests().catch((err) => {
  console.error('Fatal error during verification:', err);
  process.exit(1);
});
