import { NextResponse } from 'next/server';
import { isDbConfigured, getPool } from '@/db';

const ALL_ENTERPRISE_TABLES = [
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

export async function GET() {
  const configured = isDbConfigured();
  if (!configured) {
    return NextResponse.json({
      status: 'unconfigured',
      message: 'DATABASE_URL is not configured. Running in high-availability edge fallback mode.',
      configured: false,
      timestamp: Date.now(),
    });
  }

  try {
    const pool = getPool();
    const startTime = Date.now();
    const client = await pool.connect();
    try {
      const resTables = await client.query(`
        SELECT table_name 
        FROM information_schema.tables 
        WHERE table_schema = 'public';
      `);

      const allFoundTables = resTables.rows.map((r: any) => r.table_name);
      const tablesSet = new Set(allFoundTables);
      
      const legacyTables = ['organizations', 'users', 'face_embeddings', 'attendance_records', 'subscriptions']
        .filter((t) => tablesSet.has(t));
      
      const missingEnterpriseTables = ALL_ENTERPRISE_TABLES.filter((t) => !tablesSet.has(t));
      const latencyMs = Date.now() - startTime;

      let vectorEnabled = false;
      try {
        const resExt = await client.query("SELECT extname FROM pg_extension WHERE extname = 'vector'");
        vectorEnabled = resExt.rows.length > 0;
      } catch {
        vectorEnabled = false;
      }

      return NextResponse.json({
        status: 'connected',
        configured: true,
        tables: allFoundTables,
        allTablesCreated: legacyTables.length >= 2, // Backward compatibility
        allEnterpriseTablesCreated: missingEnterpriseTables.length === 0,
        enterpriseTables: {
          total: ALL_ENTERPRISE_TABLES.length,
          verified: ALL_ENTERPRISE_TABLES.length - missingEnterpriseTables.length,
          missing: missingEnterpriseTables,
        },
        pgvectorEnabled: vectorEnabled,
        latencyMs,
        pool: {
          total: pool.totalCount,
          idle: pool.idleCount,
          waiting: pool.waitingCount,
        },
        timestamp: Date.now(),
      });
    } finally {
      client.release();
    }
  } catch (error: any) {
    return NextResponse.json(
      {
        status: 'error',
        configured: true,
        error: error?.message || 'Database connection error',
        timestamp: Date.now(),
      },
      { status: 500 }
    );
  }
}
