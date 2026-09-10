import { drizzle } from 'drizzle-orm/node-postgres';
import { Pool } from 'pg';
import * as schema from './schema';

declare global {
  // eslint-disable-next-line no-var
  var __omniDbPool: Pool | undefined;
}

export function isDbConfigured(): boolean {
  const url = process.env.DATABASE_URL;
  return Boolean(url && (url.startsWith('postgres://') || url.startsWith('postgresql://')));
}

export function getPool(): Pool {
  if (globalThis.__omniDbPool) {
    return globalThis.__omniDbPool;
  }

  const connectionString = process.env.DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/omniface_db';
  const isCloud = connectionString.includes('supabase') || connectionString.includes('neon.tech') || connectionString.includes('pooler') || process.env.NODE_ENV === 'production';

  const pool = new Pool({
    connectionString,
    max: 10,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 5000,
    ssl: isCloud && !connectionString.includes('localhost') ? { rejectUnauthorized: false } : false,
  });

  // Attach error handler to prevent crashing on unhandled pool errors
  pool.on('error', (err) => {
    console.error('Unexpected error on idle PostgreSQL client', err);
  });

  if (process.env.NODE_ENV !== 'production') {
    globalThis.__omniDbPool = pool;
  }

  return pool;
}

export const pool = getPool();
export const db = drizzle(pool, { schema });
export type Database = typeof db;

export function getDb() {
  if (!isDbConfigured()) {
    return null;
  }
  return db;
}
