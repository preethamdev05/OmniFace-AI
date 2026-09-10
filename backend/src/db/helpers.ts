import { organizations, users, organizationMembers } from './schema';
import { eq } from 'drizzle-orm';
import type { Database } from './index';

export const DEFAULT_ORG_ID = '00000000-0000-0000-0000-000000000001';
export const DEFAULT_USER_ID = '00000000-0000-0000-0000-000000000002';

export async function ensureDefaultOrganization(db: Database): Promise<string> {
  try {
    const existingOrg = await db
      .select({ id: organizations.id })
      .from(organizations)
      .where(eq(organizations.id, DEFAULT_ORG_ID))
      .limit(1);

    if (existingOrg.length === 0) {
      await db.insert(organizations).values({
        id: DEFAULT_ORG_ID,
        name: 'OmniFace Enterprise Default',
        type: 'CORPORATE',
        tier: 'INSTITUTION',
        maxPeople: 1000,
        maxDevices: 10,
        maxKiosks: 10,
        contactEmail: 'admin@omniface.internal',
        contactPhone: '',
        status: 'ACTIVE',
      });
    }

    // Ensure default admin user exists
    const existingUser = await db
      .select({ id: users.id })
      .from(users)
      .where(eq(users.id, DEFAULT_USER_ID))
      .limit(1);

    if (existingUser.length === 0) {
      await db.insert(users).values({
        id: DEFAULT_USER_ID,
        email: 'admin@omniface.internal',
        fullName: 'Platform Admin',
        orgId: DEFAULT_ORG_ID,
        role: 'ADMIN',
      });
      await db.insert(organizationMembers).values({
        organizationId: DEFAULT_ORG_ID,
        userId: DEFAULT_USER_ID,
        role: 'ADMIN',
        status: 'ACTIVE',
      });
    }

    return DEFAULT_ORG_ID;
  } catch (error) {
    console.error('ensureDefaultOrganization error:', error);
    return DEFAULT_ORG_ID;
  }
}
