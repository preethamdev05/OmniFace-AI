import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getPool } from '@/db';
import { sreMetrics } from '@/lib/metrics';

export async function GET(req: NextRequest) {
  const startTime = Date.now();
  const requestId = req.headers.get('x-request-id') || `req-${Math.random().toString(36).substring(2, 10)}`;

  let dbPoolMetrics = undefined;
  if (isDbConfigured()) {
    try {
      const pool = getPool();
      dbPoolMetrics = {
        total: pool.totalCount,
        idle: pool.idleCount,
        waiting: pool.waitingCount,
      };
    } catch {
      // Ignore if pool cannot be read
    }
  }

  const prometheusText = sreMetrics.toPrometheusFormat(dbPoolMetrics);
  const duration = Date.now() - startTime;
  sreMetrics.recordRequest('GET', '/api/v1/metrics', 200, duration);

  return new NextResponse(prometheusText, {
    status: 200,
    headers: {
      'Content-Type': 'text/plain; version=0.0.4; charset=utf-8',
      'x-request-id': requestId,
      'Cache-Control': 'no-store, max-age=0',
    },
  });
}
