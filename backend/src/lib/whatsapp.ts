/**
 * OmniFace Sovereign Cloud Fleet — WhatsApp Business Notification Service
 *
 * Implements asynchronous notification dispatch following the Transactional Outbox Pattern:
 * 1. Mobile Kiosk commits attendance + Aegis outbox locally (100% offline, zero network latency).
 * 2. Background AttendanceSyncWorker pushes outbox batch to /api/v1/attendance/sync.
 * 3. Backend persists to PostgreSQL and asynchronously triggers WhatsApp parent/student alerts.
 *
 * INVARIANT: WhatsApp delivery NEVER blocks or rolls back biometric recognition or attendance persistence.
 */

export interface WhatsAppAttendanceAlert {
  studentRoll: string;
  studentName: string;
  sessionDate: string;
  timestamp: number;
  status: string;
  recipientPhone?: string | null;
}

export interface WhatsAppDispatchResult {
  success: boolean;
  mode: 'cloud_api' | 'sandbox' | 'skipped';
  messageId?: string;
  error?: string;
}

/**
 * Sends a single WhatsApp attendance notification.
 * Safe to call asynchronously — never throws unhandled rejections.
 */
export async function sendWhatsAppAttendanceAlert(
  alert: WhatsAppAttendanceAlert
): Promise<WhatsAppDispatchResult> {
  const token = process.env.WHATSAPP_API_TOKEN;
  const phoneNumberId = process.env.WHATSAPP_PHONE_NUMBER_ID;

  // Clean phone number (remove spaces, dashes, parentheses)
  const phone = (alert.recipientPhone || '').replace(/[^0-9+]/g, '');

  if (!phone || phone.length < 10) {
    return {
      success: false,
      mode: 'skipped',
      error: `No valid recipient phone number provided for student ${alert.studentRoll}`,
    };
  }

  const timeStr = new Date(alert.timestamp).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
  });

  const messageText = `OmniFace Attendance Alert: ${alert.studentName} (${alert.studentRoll}) checked in on ${alert.sessionDate} at ${timeStr}. Status: ${alert.status}.`;

  // Sandbox mode if credentials are not configured in environment
  if (!token || !phoneNumberId) {
    console.log(`[WhatsApp][Sandbox] To: ${phone} | Body: "${messageText}"`);
    return {
      success: true,
      mode: 'sandbox',
      messageId: `sandbox_${Date.now()}`,
    };
  }

  try {
    const url = `https://graph.facebook.com/v19.0/${phoneNumberId}/messages`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        messaging_product: 'whatsapp',
        recipient_type: 'individual',
        to: phone,
        type: 'text',
        text: {
          preview_url: false,
          body: messageText,
        },
      }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      console.warn(`[WhatsApp] Delivery failure (${response.status}):`, errorData);
      return {
        success: false,
        mode: 'cloud_api',
        error: `HTTP ${response.status}: ${errorData}`,
      };
    }

    const resJson: any = await response.json();
    const msgId = resJson?.messages?.[0]?.id || 'unknown';
    console.log(`[WhatsApp] Sent alert to ${phone} for ${alert.studentRoll} (ID: ${msgId})`);
    return {
      success: true,
      mode: 'cloud_api',
      messageId: msgId,
    };
  } catch (err: any) {
    console.error('[WhatsApp] Transport error:', err);
    return {
      success: false,
      mode: 'cloud_api',
      error: err?.message || 'Network transport failed',
    };
  }
}

/**
 * Dispatches a batch of attendance alerts asynchronously.
 * Runs in background without blocking the sync response.
 */
export async function dispatchBatchWhatsAppAlerts(
  alerts: WhatsAppAttendanceAlert[]
): Promise<{ dispatched: number; failed: number }> {
  let dispatched = 0;
  let failed = 0;

  for (const alert of alerts) {
    try {
      const result = await sendWhatsAppAttendanceAlert(alert);
      if (result.success) {
        dispatched++;
      } else {
        failed++;
      }
    } catch {
      failed++;
    }
  }

  return { dispatched, failed };
}