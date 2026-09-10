import { pgTable, text, varchar, timestamp, integer, real, bigint, customType, uuid } from 'drizzle-orm/pg-core';

// Custom pgvector type for 512-dimensional ArcFace mathematical embeddings
// NOTE: We strictly store mathematical vectors, NEVER raw camera face images.
export const vector512 = customType<{ data: number[] }>({
  dataType() {
    return 'vector(512)';
  },
  toDriver(value: number[]): string {
    return `[${value.join(',')}]`;
  },
  fromDriver(value: unknown): number[] {
    if (typeof value === 'string') {
      return value
        .replace(/[\[\]]/g, '')
        .split(',')
        .map(Number);
    }
    return value as number[];
  },
});

// ── Organizations (Tenant Root) ──
export const organizations = pgTable('organizations', {
  id: uuid('id').defaultRandom().primaryKey(),
  name: varchar('name', { length: 255 }).notNull(),
  type: varchar('type', { length: 64 }).notNull().default('SCHOOL'), // SCHOOL, COACHING, CORPORATE, GYM_EVENT
  tier: varchar('tier', { length: 32 }).notNull().default('FREE'), // FREE, PREMIUM, BUSINESS
  contactEmail: varchar('contact_email', { length: 255 }).notNull(),
  contactPhone: varchar('contact_phone', { length: 32 }),
  maxKiosks: integer('max_kiosks').notNull().default(1),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── System Admins & Staff Users ──
export const users = pgTable('users', {
  id: uuid('id').defaultRandom().primaryKey(),
  orgId: uuid('org_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  email: varchar('email', { length: 255 }).notNull().unique(),
  fullName: varchar('full_name', { length: 255 }).notNull(),
  role: varchar('role', { length: 32 }).notNull().default('ADMIN'), // SUPERADMIN, ADMIN, VIEWER
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── Mathematical Biometric Face Embeddings (Zero Raw Images) ──
export const faceEmbeddings = pgTable('face_embeddings', {
  id: uuid('id').defaultRandom().primaryKey(),
  orgId: uuid('org_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  studentRoll: varchar('student_roll', { length: 64 }).notNull(),
  fullName: varchar('full_name', { length: 255 }).notNull(),
  department: varchar('department', { length: 128 }).notNull().default('General'),
  semester: varchar('semester', { length: 32 }).notNull().default('I'),
  angleType: varchar('angle_type', { length: 32 }).notNull().default('FRONTAL'), // FRONTAL, LEFT_15, RIGHT_15, UP_10, DOWN_10
  embedding: vector512('embedding').notNull(),
  qualityScore: real('quality_score').notNull().default(100.0),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── Immutable Cryptographic Attendance Records ──
export const attendanceRecords = pgTable('attendance_records', {
  id: uuid('id').defaultRandom().primaryKey(),
  orgId: uuid('org_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  recordId: varchar('record_id', { length: 64 }).notNull().unique(),
  studentRoll: varchar('student_roll', { length: 64 }).notNull(),
  studentName: varchar('student_name', { length: 255 }).notNull(),
  timestamp: bigint('timestamp', { mode: 'number' }).notNull(),
  sessionDate: varchar('session_date', { length: 16 }).notNull(), // yyyy-MM-dd
  confidencePct: integer('confidence_pct').notNull(),
  securityTier: varchar('security_tier', { length: 32 }).notNull(),
  sha256Hash: varchar('sha256_hash', { length: 64 }).notNull(), // Aegis SHA256 tamper-evident proof
  kioskId: varchar('kiosk_id', { length: 64 }).notNull().default('kiosk-alpha'),
  syncedAt: timestamp('synced_at').defaultNow().notNull(),
});

// ── Commercial Subscriptions & Web Licensing ──
export const subscriptions = pgTable('subscriptions', {
  id: uuid('id').defaultRandom().primaryKey(),
  orgId: uuid('org_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  tier: varchar('tier', { length: 32 }).notNull(), // FREE, PREMIUM, BUSINESS
  status: varchar('status', { length: 32 }).notNull().default('ACTIVE'), // ACTIVE, EXPIRED, CANCELLED
  billingProvider: varchar('billing_provider', { length: 32 }).notNull(), // GOOGLE_PLAY, RAZORPAY
  externalSubscriptionId: text('external_subscription_id'),
  purchaseToken: text('purchase_token'),
  amountInr: integer('amount_inr').notNull().default(0),
  validUntil: timestamp('valid_until').notNull(),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});
