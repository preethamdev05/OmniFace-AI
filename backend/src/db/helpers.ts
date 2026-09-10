import { organizations } from './schema';
import { eq } from 'drizzle-orm';
import type { Database } from './index';

export const DEFAULT_ORG_ID = '00000000-0000-0000-0000-000000000001';

export async function ensureDefaultOrganization(db: Database): Promise<string> {
  try {
    const existing = await db
      .select({ id: organizations.id })
      .from(organizations)
      .where(eq(organizations.id, DEFAULT_ORG_ID))
      .limit(1);

    if (existing.length === 0) {
      await db.insert(organizations).values({
        id: DEFAULT_ORG_ID,
        name: 'OmniFace Enterprise Default',
        type: 'CORPORATE',
        tier: 'INSTITUTION',
        maxPeople: 1000,
        maxDevices: 10,
        maxKiosks: 10,
        contactEmail: 'preethamdev05@gmail.com',
        contactPhone: '',
        status: 'ACTIVE',
      });
    }
    return DEFAULT_ORG_ID;
  } catch (error) {
    console.error('ensureDefaultOrganization error:', error);
    return DEFAULT_ORG_ID;
  }
}
