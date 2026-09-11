import { App, initializeApp, getApps, cert, applicationDefault } from 'firebase-admin/app';
import { getMessaging, MulticastMessage } from 'firebase-admin/messaging';

let firebaseAdminApp: App | null = null;

/**
 * Initializes and returns the Firebase Admin SDK singleton.
 */
export function getFirebaseAdmin(): App | null {
  if (firebaseAdminApp) {
    return firebaseAdminApp;
  }

  const existingApps = getApps();
  if (existingApps.length > 0) {
    firebaseAdminApp = existingApps[0]!;
    return firebaseAdminApp;
  }

  const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  const projectId = process.env.FIREBASE_PROJECT_ID || process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID || 'omniface-ai-prod';

  if (serviceAccountJson) {
    try {
      const serviceAccount = JSON.parse(serviceAccountJson);
      firebaseAdminApp = initializeApp({
        credential: cert(serviceAccount),
        projectId,
      });
      return firebaseAdminApp;
    } catch (err) {
      console.error('Failed to parse FIREBASE_SERVICE_ACCOUNT_KEY JSON:', err);
    }
  }

  // Fallback to application default credentials if available
  try {
    firebaseAdminApp = initializeApp({
      credential: applicationDefault(),
      projectId,
    });
    return firebaseAdminApp;
  } catch {
    // In CI or local sandbox without credentials, don't crash at startup
    return null;
  }
}

export interface FcmDispatchResult {
  successCount: number;
  failureCount: number;
  invalidTokens: string[];
}

/**
 * Dispatches an FCM multicast push notification using Firebase Admin HTTP v1.
 * Automatically identifies dead or unregistered tokens for database pruning.
 */
export async function sendFcmMulticast(
  tokens: string[],
  payload: {
    title: string;
    body: string;
    data?: Record<string, string>;
  }
): Promise<FcmDispatchResult> {
  if (!tokens || tokens.length === 0) {
    return { successCount: 0, failureCount: 0, invalidTokens: [] };
  }

  const app = getFirebaseAdmin();
  if (!app) {
    console.warn('[FCM] Firebase Admin SDK is unconfigured. Dispatch skipped; 0 notifications sent.');
    return {
      successCount: 0,
      failureCount: tokens.length,
      invalidTokens: [],
    };
  }

  const message: MulticastMessage = {
    tokens,
    notification: {
      title: payload.title,
      body: payload.body,
    },
    data: payload.data || {},
    android: {
      priority: 'high',
      notification: {
        channelId: 'omniface_alerts',
        sound: 'default',
        icon: 'ic_stat_notification',
      },
    },
  };

  try {
    const messaging = getMessaging(app);
    const response = await messaging.sendEachForMulticast(message);
    const invalidTokens: string[] = [];

    response.responses.forEach((resp, idx) => {
      if (!resp.success && resp.error) {
        const code = resp.error.code;
        if (
          code === 'messaging/registration-token-not-registered' ||
          code === 'messaging/invalid-registration-token'
        ) {
          invalidTokens.push(tokens[idx]);
        }
      }
    });

    return {
      successCount: response.successCount,
      failureCount: response.failureCount,
      invalidTokens,
    };
  } catch (err) {
    console.error('FCM multicast dispatch error:', err);
    return {
      successCount: 0,
      failureCount: tokens.length,
      invalidTokens: [],
    };
  }
}
