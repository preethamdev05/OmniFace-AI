/**
 * OmniFace SRE Metrics Store
 * In-memory Prometheus & OpenTelemetry compatible metrics collector
 */

interface RequestMetric {
  method: string;
  path: string;
  status: number;
  durationMs: number;
  timestamp: number;
}

class SreMetricsCollector {
  private startTime: number = Date.now();
  private requestMetrics: RequestMetric[] = [];
  private readonly MAX_SAMPLES = 5000;

  private syncEventsTotal: number = 0;
  private heartbeatsTotal: number = 0;
  private thermalAlertsTotal: number = 0;

  recordRequest(method: string, path: string, status: number, durationMs: number) {
    // Normalize path to prevent cardinality explosion (e.g. /api/v1/students?id=... -> /api/v1/students)
    const cleanPath = path.split('?')[0];
    if (this.requestMetrics.length >= this.MAX_SAMPLES) {
      this.requestMetrics.splice(0, 1000); // Evict oldest samples
    }
    this.requestMetrics.push({
      method: method.toUpperCase(),
      path: cleanPath,
      status,
      durationMs,
      timestamp: Date.now(),
    });
  }

  incrementSyncEvents(count: number = 1) {
    this.syncEventsTotal += count;
  }

  incrementHeartbeats() {
    this.heartbeatsTotal += 1;
  }

  incrementThermalAlerts() {
    this.thermalAlertsTotal += 1;
  }

  getMetricsSummary() {
    const totalRequests = this.requestMetrics.length;
    const uptimeSec = Math.floor((Date.now() - this.startTime) / 1000);
    const mem = process.memoryUsage();

    // Calculate percentiles
    const durations = this.requestMetrics.map((m) => m.durationMs).sort((a, b) => a - b);
    const p50 = durations.length > 0 ? durations[Math.floor(durations.length * 0.5)] : 0;
    const p95 = durations.length > 0 ? durations[Math.floor(durations.length * 0.95)] : 0;
    const p99 = durations.length > 0 ? durations[Math.floor(durations.length * 0.99)] : 0;

    return {
      uptimeSec,
      totalRequests,
      p50DurationMs: p50,
      p95DurationMs: p95,
      p99DurationMs: p99,
      syncEventsTotal: this.syncEventsTotal,
      heartbeatsTotal: this.heartbeatsTotal,
      thermalAlertsTotal: this.thermalAlertsTotal,
      memory: {
        rss: mem.rss,
        heapTotal: mem.heapTotal,
        heapUsed: mem.heapUsed,
        external: mem.external,
      },
    };
  }

  /**
   * Generates standard Prometheus OpenTelemetry text representation
   */
  toPrometheusFormat(extraDbMetrics?: { total: number; idle: number; waiting: number }): string {
    const summary = this.getMetricsSummary();
    const lines: string[] = [];

    lines.push('# HELP omniface_process_uptime_seconds The total uptime of the OmniFace backend in seconds.');
    lines.push('# TYPE omniface_process_uptime_seconds gauge');
    lines.push(`omniface_process_uptime_seconds ${summary.uptimeSec}`);

    lines.push('# HELP omniface_process_memory_rss_bytes Resident memory set bytes.');
    lines.push('# TYPE omniface_process_memory_rss_bytes gauge');
    lines.push(`omniface_process_memory_rss_bytes ${summary.memory.rss}`);

    lines.push('# HELP omniface_process_memory_heap_used_bytes Memory heap used bytes.');
    lines.push('# TYPE omniface_process_memory_heap_used_bytes gauge');
    lines.push(`omniface_process_memory_heap_used_bytes ${summary.memory.heapUsed}`);

    lines.push('# HELP omniface_sync_events_total Total biometric attendance events synchronized.');
    lines.push('# TYPE omniface_sync_events_total counter');
    lines.push(`omniface_sync_events_total ${summary.syncEventsTotal}`);

    lines.push('# HELP omniface_kiosk_heartbeats_total Total Android kiosk heartbeats received.');
    lines.push('# TYPE omniface_kiosk_heartbeats_total counter');
    lines.push(`omniface_kiosk_heartbeats_total ${summary.heartbeatsTotal}`);

    lines.push('# HELP omniface_thermal_alerts_total Total kiosk critical thermal alerts triggered.');
    lines.push('# TYPE omniface_thermal_alerts_total counter');
    lines.push(`omniface_thermal_alerts_total ${summary.thermalAlertsTotal}`);

    if (extraDbMetrics) {
      lines.push('# HELP omniface_db_pool_total Total database pool connections.');
      lines.push('# TYPE omniface_db_pool_total gauge');
      lines.push(`omniface_db_pool_total ${extraDbMetrics.total}`);

      lines.push('# HELP omniface_db_pool_idle Idle database pool connections.');
      lines.push('# TYPE omniface_db_pool_idle gauge');
      lines.push(`omniface_db_pool_idle ${extraDbMetrics.idle}`);

      lines.push('# HELP omniface_db_pool_waiting Number of queries waiting for a free database connection.');
      lines.push('# TYPE omniface_db_pool_waiting gauge');
      lines.push(`omniface_db_pool_waiting ${extraDbMetrics.waiting}`);
    }

    // Group requests by method, path, and status code class
    const agg: Record<string, number> = {};
    for (const req of this.requestMetrics) {
      const key = `method="${req.method}",path="${req.path}",status="${req.status}"`;
      agg[key] = (agg[key] || 0) + 1;
    }

    lines.push('# HELP http_requests_total Total number of HTTP requests processed.');
    lines.push('# TYPE http_requests_total counter');
    for (const [labels, count] of Object.entries(agg)) {
      lines.push(`http_requests_total{${labels}} ${count}`);
    }

    lines.push('# HELP http_request_duration_ms_summary Latency quantiles for HTTP requests in milliseconds.');
    lines.push('# TYPE http_request_duration_ms_summary summary');
    lines.push(`http_request_duration_ms_summary{quantile="0.5"} ${summary.p50DurationMs}`);
    lines.push(`http_request_duration_ms_summary{quantile="0.95"} ${summary.p95DurationMs}`);
    lines.push(`http_request_duration_ms_summary{quantile="0.99"} ${summary.p99DurationMs}`);

    return lines.join('\n') + '\n';
  }
}

export const sreMetrics = new SreMetricsCollector();
