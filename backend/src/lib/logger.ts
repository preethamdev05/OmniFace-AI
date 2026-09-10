import crypto from 'crypto';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface StructuredLogPayload {
  timestamp: string;
  level: LogLevel;
  event: string;
  requestId?: string;
  entryPoint?: string;
  durationMs?: number;
  data?: Record<string, any>;
  error?: {
    message: string;
    stack?: string;
    code?: string | number;
  };
}

class StructuredLogger {
  private baseContext: Record<string, any> = {};

  constructor(baseContext: Record<string, any> = {}) {
    this.baseContext = baseContext;
  }

  child(context: Record<string, any>): StructuredLogger {
    return new StructuredLogger({ ...this.baseContext, ...context });
  }

  private log(level: LogLevel, event: string, data?: Record<string, any>, err?: any, durationMs?: number) {
    const payload: StructuredLogPayload = {
      timestamp: new Date().toISOString(),
      level,
      event,
      requestId: this.baseContext.requestId || (data?.requestId as string),
      entryPoint: this.baseContext.entryPoint || (data?.entryPoint as string) || 'http_api',
      durationMs,
      data: {
        ...this.baseContext,
        ...data,
      },
    };

    if (err) {
      payload.error = {
        message: err?.message || String(err),
        stack: process.env.NODE_ENV !== 'production' ? err?.stack : undefined,
        code: err?.code,
      };
    }

    const output = JSON.stringify(payload);
    switch (level) {
      case 'error':
        console.error(output);
        break;
      case 'warn':
        console.warn(output);
        break;
      case 'debug':
        if (process.env.NODE_ENV !== 'production' || process.env.DEBUG === 'true') {
          console.debug(output);
        }
        break;
      case 'info':
      default:
        console.log(output);
        break;
    }
  }

  info(event: string, data?: Record<string, any>, durationMs?: number) {
    this.log('info', event, data, undefined, durationMs);
  }

  warn(event: string, data?: Record<string, any>, err?: any) {
    this.log('warn', event, data, err);
  }

  error(event: string, data?: Record<string, any>, err?: any) {
    this.log('error', event, data, err);
  }

  debug(event: string, data?: Record<string, any>) {
    this.log('debug', event, data);
  }
}

export const logger = new StructuredLogger();

export const runLog = (entryPoint: 'http_api' | 'scheduler' | 'sync_worker' | 'cli' | 'audit', requestId?: string) => {
  const reqId = requestId || `req-${crypto.randomUUID().slice(0, 8)}`;
  return logger.child({ entryPoint, requestId: reqId });
};
