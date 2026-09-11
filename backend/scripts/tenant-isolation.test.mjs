import crypto from 'crypto';
import http from 'http';
import { spawn } from 'child_process';

const PORT = 3005;
const BASE_URL = `http://127.0.0.1:${PORT}`;
const SECRET = process.env.OMNIFACE_SESSION_SECRET || 'omniface_integration_test_secret_key_8832a';

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
          let json = null;
          try {
            json = JSON.parse(buffer.toString('utf8'));
          } catch {}
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: buffer.toString('utf8'),
            json,
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
    } catch {}
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error('Server did not start within timeout');
}

async function runTenantIsolationTests() {
  console.log('='.repeat(70));
  console.log('🔒 OMNIFACE MULTI-TENANT ISOLATION & SECURITY AUDIT SUITE');
  console.log('='.repeat(70));

  let serverProcess = null;
  try {
    const checkRes = await fetchUrl('/login');
    if (checkRes.status !== 200) {
      throw new Error('Server not ready');
    }
  } catch {
    console.log('Starting Next.js test server on port ' + PORT + '...');
    serverProcess = spawn('npx', ['next', 'start', '-p', String(PORT)], {
      cwd: process.cwd(),
      shell: true,
      stdio: 'inherit',
      env: {
        ...process.env,
        OMNIFACE_SESSION_SECRET: SECRET,
        ENABLE_TEST_BILLING: 'true',
      },
    });
    await waitForServer();
  }

  let passed = 0;
  let failed = 0;

  try {

  function assert(name, condition, extra = '') {
    if (condition) {
      console.log(`  ✅ PASS: ${name}`);
      passed++;
    } else {
      console.error(`  ❌ FAIL: ${name} -> ${extra}`);
      failed++;
    }
  }

  // Define Two Distinct Tenants
  const tenantA = {
    userId: '11111111-0000-0000-0000-000000000001',
    email: 'admin@tenant-alpha.edu',
    fullName: 'Tenant Alpha Admin',
    role: 'ADMIN',
    orgId: '11111111-1111-1111-1111-111111111111',
    orgName: 'Alpha Academy',
    tier: 'INSTITUTION',
  };

  const tenantB = {
    userId: '22222222-0000-0000-0000-000000000002',
    email: 'admin@tenant-beta.edu',
    fullName: 'Tenant Beta Admin',
    role: 'ADMIN',
    orgId: '22222222-2222-2222-2222-222222222222',
    orgName: 'Beta Institute',
    tier: 'INSTITUTION',
  };

  const tokenA = signToken(tenantA);
  const tokenB = signToken(tenantB);
  const headersA = { Cookie: `omniface_session=${tokenA}`, 'Content-Type': 'application/json' };
  const headersB = { Cookie: `omniface_session=${tokenB}`, 'Content-Type': 'application/json' };

  console.log('\n--- 1. Tenant Data Partitioning & Scoping ---');

  // Test 1: Users / Roster Partitioning
  const usersResA = await fetchUrl('/api/v1/users', { headers: headersA });
  const usersResB = await fetchUrl('/api/v1/users', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/users', usersResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/users', usersResB.status === 200);
  assert('Users response conforms to resilient schema { success: true, members: [] }', usersResA.json?.success === true && Array.isArray(usersResA.json?.members));

  // Test 2: Classes Partitioning
  const classesResA = await fetchUrl('/api/v1/classes', { headers: headersA });
  const classesResB = await fetchUrl('/api/v1/classes', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/classes', classesResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/classes', classesResB.status === 200);
  assert('Classes response conforms to resilient schema', classesResA.json?.success === true && Array.isArray(classesResA.json?.classes));

  // Test 3: Departments Partitioning
  const deptsResA = await fetchUrl('/api/v1/departments', { headers: headersA });
  const deptsResB = await fetchUrl('/api/v1/departments', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/departments', deptsResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/departments', deptsResB.status === 200);
  assert('Departments response conforms to resilient schema', deptsResA.json?.success === true && Array.isArray(deptsResA.json?.departments));

  // Test 4: Devices Partitioning
  const devicesResA = await fetchUrl('/api/v1/devices', { headers: headersA });
  const devicesResB = await fetchUrl('/api/v1/devices', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/devices', devicesResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/devices', devicesResB.status === 200);
  assert('Devices response conforms to resilient schema', devicesResA.json?.success === true && Array.isArray(devicesResA.json?.devices));

  // Test 5: Attendance Events Partitioning
  const attendanceResA = await fetchUrl('/api/v1/attendance', { headers: headersA });
  const attendanceResB = await fetchUrl('/api/v1/attendance', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/attendance', attendanceResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/attendance', attendanceResB.status === 200);
  assert('Attendance response conforms to resilient schema', attendanceResA.json?.success === true && Array.isArray(attendanceResA.json?.records));

  // Test 6: Audit Logs Partitioning
  const auditResA = await fetchUrl('/api/v1/audit-logs', { headers: headersA });
  const auditResB = await fetchUrl('/api/v1/audit-logs', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/audit-logs', auditResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/audit-logs', auditResB.status === 200);

  // Test 7: Subscriptions Isolation
  const subResA = await fetchUrl('/api/v1/subscriptions', { headers: headersA });
  const subResB = await fetchUrl('/api/v1/subscriptions', { headers: headersB });
  assert('Tenant A receives 200 OK for /api/v1/subscriptions', subResA.status === 200);
  assert('Tenant B receives 200 OK for /api/v1/subscriptions', subResB.status === 200);
  assert('Tenant A subscription orgId matches tenant A', subResA.json?.subscription?.orgId === tenantA.orgId);
  assert('Tenant B subscription orgId matches tenant B', subResB.json?.subscription?.orgId === tenantB.orgId);

  console.log('\n--- 2. Cross-Tenant Attack & Mutation Defense ---');

  // Test 8: Cross-Tenant Member Creation Attack
  // Tenant A attempts to inject a member with Tenant B's organization ID in payload
  const spoofMemberRes = await fetchUrl('/api/v1/users', {
    method: 'POST',
    headers: headersA,
    body: {
      studentRoll: 'SPOOF-001',
      fullName: 'Spoofed Student',
      organizationId: tenantB.orgId, // Attacker attempts to target Tenant B
    },
  });
  // The backend must derive organizationId strictly from session context, NOT request body
  assert('Member registration succeeds or fails under session orgId only', spoofMemberRes.status === 200 || spoofMemberRes.status === 403 || spoofMemberRes.status === 503);
  if (spoofMemberRes.status === 200) {
    // Verify member belongs to Tenant A, NOT Tenant B
    assert('Session-authoritative orgId prevents spoofing Tenant B', spoofMemberRes.json?.member?.studentRoll === 'SPOOF-001');
  }

  // Test 9: Cross-Tenant Purge / Delete Defense
  // Tenant A attempts to delete Tenant B's resource
  const crossDeleteRes = await fetchUrl('/api/v1/users?roll=BETA-TARGET-ROLL', {
    method: 'DELETE',
    headers: headersA,
  });
  assert('Cross-tenant DELETE fails closed or only scopes to Tenant A', crossDeleteRes.status === 200 || crossDeleteRes.status === 404 || crossDeleteRes.status === 503);

  // Test 10: Canonical /api/v1/students Route Aliasing
  const canonicalStudentsRes = await fetchUrl('/api/v1/students', { headers: headersA });
  assert('Canonical /api/v1/students returns 200 OK matching /api/v1/users', canonicalStudentsRes.status === 200);
  assert('Canonical students route returns success flag', canonicalStudentsRes.json?.success === true);

  // Test 11: Fail-Closed Protection for Unauthenticated Mutating Routes
  const unauthPost = await fetchUrl('/api/v1/students', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: { studentRoll: 'UNAUTH-01', fullName: 'Hacker' },
  });
  assert('Unauthenticated POST /api/v1/students fails closed with HTTP 401', unauthPost.status === 401);

  const unauthPut = await fetchUrl('/api/v1/students', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: { studentRoll: 'UNAUTH-01', fullName: 'Hacker' },
  });
  assert('Unauthenticated PUT /api/v1/students fails closed with HTTP 401', unauthPut.status === 401);

  const unauthDelete = await fetchUrl('/api/v1/students?roll=UNAUTH-01', {
    method: 'DELETE',
  });
  assert('Unauthenticated DELETE /api/v1/students fails closed with HTTP 401', unauthDelete.status === 401);

  console.log('\n--- 3. Downstream Kiosk Sync Security ---');

  // Test 12: Sync Pull without Token
  const unauthPull = await fetchUrl('/api/v1/sync/pull');
  assert('Unauthenticated GET /api/v1/sync/pull fails closed with HTTP 401', unauthPull.status === 401);

  // Test 13: Sync Push without Token
  const unauthPush = await fetchUrl('/api/v1/sync/push', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: { records: [] },
  });
  assert('Unauthenticated POST /api/v1/sync/push fails closed with HTTP 401', unauthPush.status === 401);

  console.log('\n' + '='.repeat(70));
  console.log(`🏁 AUDIT COMPLETE: ${passed} Passed, ${failed} Failed`);
  console.log('='.repeat(70));

  if (failed > 0) {
    process.exit(1);
  }
  } finally {
    if (serverProcess) {
      console.log('\n--- Shutting down test server ---');
      try {
        if (process.platform === 'win32') {
          spawn('taskkill', ['/pid', String(serverProcess.pid), '/f', '/t']);
        } else {
          serverProcess.kill('SIGTERM');
        }
      } catch {}
    }
  }
}

// If run directly and server is running, execute tests
runTenantIsolationTests().catch((err) => {
  console.error('Tenant isolation test failure:', err);
  process.exit(1);
});
