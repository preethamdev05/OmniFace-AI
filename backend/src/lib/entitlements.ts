import { isDbConfigured, getDb } from '@/db';
import { organizations, subscriptions } from '@/db/schema';
import { eq, desc, or } from 'drizzle-orm';

export type SubscriptionTier = 'FREE' | 'PREMIUM' | 'PRO' | 'INSTITUTION';
export type ExportFormat = 'csv' | 'xlsx' | 'pdf';

export interface Entitlements {
  tier: SubscriptionTier;
  maxUsers: number;
  maxDevices: number;
  hasDashboardAccess: boolean;
  hasCloudSync: boolean;
  allowedExportFormats: ExportFormat[];
  hasAdvancedReporting: boolean;
  showAdvertisements: boolean;
  hasMultiAdmin: boolean;
  hasAuditLogs: boolean;
  isGracePeriod: boolean;
}

/**
 * Calculates deterministic server-authoritative entitlements from tier and optional custom overrides.
 */
export function getTierEntitlements(
  tier: SubscriptionTier = 'FREE',
  overrides?: { maxPeople?: number | null; maxDevices?: number | null; isGracePeriod?: boolean }
): Entitlements {
  const normalizedTier = (tier || 'FREE').toUpperCase() as SubscriptionTier;
  const isGrace = Boolean(overrides?.isGracePeriod);

  switch (normalizedTier) {
    case 'INSTITUTION':
      return {
        tier: 'INSTITUTION',
        maxUsers: overrides?.maxPeople && overrides.maxPeople > 500 ? overrides.maxPeople : 10000,
        maxDevices: overrides?.maxDevices && overrides.maxDevices > 3 ? overrides.maxDevices : 100,
        hasDashboardAccess: true,
        hasCloudSync: true,
        allowedExportFormats: ['csv', 'xlsx', 'pdf'],
        hasAdvancedReporting: true,
        showAdvertisements: false,
        hasMultiAdmin: true,
        hasAuditLogs: true,
        isGracePeriod: isGrace,
      };

    case 'PRO':
      return {
        tier: 'PRO',
        maxUsers: 500,
        maxDevices: 3,
        hasDashboardAccess: true,
        hasCloudSync: true,
        allowedExportFormats: ['csv', 'xlsx', 'pdf'],
        hasAdvancedReporting: true,
        showAdvertisements: false,
        hasMultiAdmin: false,
        hasAuditLogs: true,
        isGracePeriod: isGrace,
      };

    case 'PREMIUM':
      return {
        tier: 'PREMIUM',
        maxUsers: 250,
        maxDevices: 1,
        hasDashboardAccess: true,
        hasCloudSync: true,
        allowedExportFormats: ['csv'],
        hasAdvancedReporting: false,
        showAdvertisements: false,
        hasMultiAdmin: false,
        hasAuditLogs: false,
        isGracePeriod: isGrace,
      };

    case 'FREE':
    default:
      return {
        tier: 'FREE',
        maxUsers: 25,
        maxDevices: 1,
        hasDashboardAccess: false,
        hasCloudSync: false,
        allowedExportFormats: [],
        hasAdvancedReporting: false,
        showAdvertisements: true,
        hasMultiAdmin: false,
        hasAuditLogs: false,
        isGracePeriod: false,
      };
  }
}

/**
 * Resolves current entitlements for an organization from PostgreSQL.
 * Queries active subscriptions and organization overrides.
 */
export async function resolveOrgEntitlements(orgId: string): Promise<Entitlements> {
  if (!orgId) {
    return getTierEntitlements('FREE');
  }

  if (!isDbConfigured()) {
    // Sandbox default: allow full capabilities for integration verification
    return getTierEntitlements('PRO');
  }

  const database = getDb();
  if (!database) {
    return getTierEntitlements('FREE');
  }

  try {
    // 1. Fetch organization record
    const orgRows = await database
      .select()
      .from(organizations)
      .where(eq(organizations.id, orgId))
      .limit(1);

    if (orgRows.length === 0) {
      return getTierEntitlements('FREE');
    }

    const org = orgRows[0];
    const orgTier = (org.tier || 'FREE').toUpperCase() as SubscriptionTier;

    // 2. Fetch latest subscription record
    const subRows = await database
      .select()
      .from(subscriptions)
      .where(or(eq(subscriptions.organizationId, orgId), eq(subscriptions.orgId, orgId)))
      .orderBy(desc(subscriptions.createdAt))
      .limit(1);

    let effectiveTier = orgTier;
    let isGracePeriod = false;

    if (subRows.length > 0) {
      const sub = subRows[0];
      const now = new Date();
      const validUntil = sub.validUntil ? new Date(sub.validUntil) : null;
      const graceUntil = sub.graceUntil ? new Date(sub.graceUntil) : null;

      if (sub.status === 'ACTIVE') {
        if (!validUntil || validUntil > now) {
          effectiveTier = (sub.tier || orgTier).toUpperCase() as SubscriptionTier;
        } else if (graceUntil && graceUntil > now) {
          effectiveTier = (sub.tier || orgTier).toUpperCase() as SubscriptionTier;
          isGracePeriod = true;
        } else {
          // Expired past grace period: drop to FREE read-only archive
          effectiveTier = 'FREE';
        }
      }
    }

    return getTierEntitlements(effectiveTier, {
      maxPeople: org.maxPeople,
      maxDevices: org.maxDevices,
      isGracePeriod,
    });
  } catch (err) {
    console.error(`Failed to resolve entitlements for org ${orgId}:`, err);
    return getTierEntitlements('FREE');
  }
}

/**
 * Server guard: Checks if the organization is entitled to access the operational web dashboard.
 */
export function assertCanAccessDashboard(entitlements: Entitlements): void {
  if (!entitlements.hasDashboardAccess) {
    const error = new Error('Dashboard access requires a Premium, Pro, or Institution plan.');
    (error as any).status = 403;
    (error as any).code = 'ENTITLEMENT_DASHBOARD_REQUIRED';
    throw error;
  }
}

/**
 * Server guard: Checks if the organization is entitled to export data in the requested format.
 */
export function assertCanExportFormat(entitlements: Entitlements, format: ExportFormat): void {
  if (!entitlements.allowedExportFormats.includes(format)) {
    const requiredPlan = format === 'csv' ? 'Premium' : 'Pro';
    const error = new Error(`Exporting in ${format.toUpperCase()} format requires a ${requiredPlan} or higher plan.`);
    (error as any).status = 403;
    (error as any).code = 'ENTITLEMENT_EXPORT_REQUIRED';
    throw error;
  }
}

/**
 * Server guard: Checks if the organization can pair another device.
 */
export function assertCanPairDevice(entitlements: Entitlements, currentDeviceCount: number): void {
  if (currentDeviceCount >= entitlements.maxDevices) {
    const error = new Error(
      `Device limit reached (${currentDeviceCount}/${entitlements.maxDevices}). Upgrade to Pro (3 devices) or Institution (Unlimited).`
    );
    (error as any).status = 403;
    (error as any).code = 'ENTITLEMENT_DEVICE_LIMIT_EXCEEDED';
    throw error;
  }
}

/**
 * Server guard: Checks if the organization can enroll another student.
 */
export function assertCanEnrollStudent(entitlements: Entitlements, currentStudentCount: number): void {
  if (currentStudentCount >= entitlements.maxUsers) {
    const error = new Error(
      `Student limit reached (${currentStudentCount}/${entitlements.maxUsers}). Upgrade your subscription tier to enroll more students.`
    );
    (error as any).status = 403;
    (error as any).code = 'ENTITLEMENT_USER_LIMIT_EXCEEDED';
    throw error;
  }
}
