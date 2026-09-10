import { NextResponse } from 'next/server';
import { isDbConfigured, getPool } from '@/db';

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
    const client = await pool.connect();
    try {
      const resTables = await client.query(`
        SELECT table_name 
        FROM information_schema.tables 
        WHERE table_schema = 'public' 
          AND table_name IN ('organizations', 'users', 'face_embeddings', 'attendance_records', 'subscriptions');
      `);

      const tables = resTables.rows.map((r: any) => r.table_name);

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
        tables,
        allTablesCreated: tables.length === 5,
        pgvectorEnabled: vectorEnabled,
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
