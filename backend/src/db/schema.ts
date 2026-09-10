import { pgTable, text, varchar, timestamp, integer, real, bigint, customType, uuid, index } from 'drizzle-orm/pg-core';

// Custom pgvector type for 512-dimensional ArcFace mathematical embeddings
// Strictly stores mathematical vectors, NEVER raw camera face images.
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

// ── 1. Organizations (Multi-Tenant Root) ──
export const organizations = pgTable('organizations', {
  id: uuid('id').defaultRandom().primaryKey(),
  name: varchar('name', { length: 255 }).notNull(),
  type: varchar('type', { length: 64 }).notNull().default('SCHOOL'), // SCHOOL, COLLEGE, COACHING, CORPORATE, GYM_EVENT
  tier: varchar('tier', { length: 32 }).notNull().default('FREE'), // FREE, PREMIUM, PRO, INSTITUTION
  maxPeople: integer('max_people').notNull().default(25),
  maxDevices: integer('max_devices').notNull().default(1),
  maxKiosks: integer('max_kiosks').notNull().default(1), // backward compatibility
  contactEmail: varchar('contact_email', { length: 255 }).notNull(),
  contactPhone: varchar('contact_phone', { length: 32 }),
  defaultStartTime: varchar('default_start_time', { length: 16 }).default('09:00'),
  graceMinutes: integer('grace_minutes').default(15),
  autoEvaluateStatus: integer('auto_evaluate_status').default(1),
  dpdpCompliance: integer('dpdp_compliance').default(1),
  status: varchar('status', { length: 32 }).notNull().default('ACTIVE'), // ACTIVE, GRACE_PERIOD, ARCHIVE_READ_ONLY, SUSPENDED
  gracePeriodEnd: timestamp('grace_period_end'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── 2. Users (System Admins & Staff Users) ──
export const users = pgTable('users', {
  id: uuid('id').defaultRandom().primaryKey(),
  firebaseUid: varchar('firebase_uid', { length: 128 }).unique(),
  email: varchar('email', { length: 255 }).notNull().unique(),
  fullName: varchar('full_name', { length: 255 }).notNull(),
  avatarUrl: text('avatar_url'),
  orgId: uuid('org_id').references(() => organizations.id, { onDelete: 'cascade' }), // backward compat
  role: varchar('role', { length: 32 }).notNull().default('ADMIN'), // OWNER, ADMIN, TEACHER, VIEWER
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── 3. Organization Members (RBAC: OWNER, ADMIN, TEACHER, VIEWER) ──
export const organizationMembers = pgTable('organization_members', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  userId: uuid('user_id').references(() => users.id, { onDelete: 'cascade' }).notNull(),
  role: varchar('role', { length: 32 }).notNull().default('VIEWER'), // OWNER, ADMIN, TEACHER, VIEWER
  status: varchar('status', { length: 32 }).notNull().default('ACTIVE'), // ACTIVE, INVITED, SUSPENDED
  joinedAt: timestamp('joined_at').defaultNow().notNull(),
});

// ── 4. Departments ──
export const departments = pgTable('departments', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  name: varchar('name', { length: 128 }).notNull(),
  code: varchar('code', { length: 32 }).notNull(),
  description: text('description'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── 5. Classes & Sections ──
export const classes = pgTable('classes', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  departmentId: uuid('department_id').references(() => departments.id, { onDelete: 'set null' }),
  name: varchar('name', { length: 128 }).notNull(),
  section: varchar('section', { length: 32 }).notNull().default('A'),
  scheduleStartTime: varchar('schedule_start_time', { length: 16 }).notNull().default('09:00'),
  scheduleEndTime: varchar('schedule_end_time', { length: 16 }).notNull().default('17:00'),
  graceMinutes: integer('grace_minutes').notNull().default(15),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
}, (table) => [
  index('idx_classes_org_dept').on(table.organizationId, table.departmentId),
]);

// ── 6. Students / People Roster ──
export const students = pgTable('students', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  departmentId: uuid('department_id').references(() => departments.id, { onDelete: 'set null' }),
  rollNumber: varchar('roll_number', { length: 64 }).notNull(),
  fullName: varchar('full_name', { length: 255 }).notNull(),
  email: varchar('email', { length: 255 }),
  phone: varchar('phone', { length: 32 }),
  status: varchar('status', { length: 32 }).notNull().default('ACTIVE'), // ACTIVE, INACTIVE, SUSPENDED
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
}, (table) => [
  index('idx_students_org_dept').on(table.organizationId, table.departmentId),
  index('idx_students_org_roll').on(table.organizationId, table.rollNumber),
]);

// ── 7. Student Classes (Many-to-Many Enrollment) ──
export const studentClasses = pgTable('student_classes', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  studentId: uuid('student_id').references(() => students.id, { onDelete: 'cascade' }).notNull(),
  classId: uuid('class_id').references(() => classes.id, { onDelete: 'cascade' }).notNull(),
  enrolledAt: timestamp('enrolled_at').defaultNow().notNull(),
}, (table) => [
  index('idx_student_classes_org_class').on(table.organizationId, table.classId),
]);

// ── 8. Devices (Hardware Kiosks & Fleet) ──
export const devices = pgTable('devices', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  deviceIdentifier: varchar('device_identifier', { length: 128 }).notNull().unique(),
  deviceName: varchar('device_name', { length: 255 }).notNull(),
  pairingCode: varchar('pairing_code', { length: 16 }),
  pairingCodeExpiresAt: timestamp('pairing_code_expires_at'),
  deviceToken: text('device_token'),
  appVersion: varchar('app_version', { length: 32 }).default('v2.0.0'),
  lastSyncAt: timestamp('last_sync_at'),
  lastAttendanceAt: timestamp('last_attendance_at'),
  pendingEventsCount: integer('pending_events_count').notNull().default(0),
  status: varchar('status', { length: 32 }).notNull().default('OFFLINE'), // ONLINE, OFFLINE, PAIRED, REVOKED
  isPaired: integer('is_paired').notNull().default(0),
  hardwareHash: varchar('hardware_hash', { length: 128 }),
  pairedAt: timestamp('paired_at'),
  batteryPct: integer('battery_pct'),
  temperature: real('temperature'),
  thermalState: varchar('thermal_state', { length: 32 }).default('NOMINAL'), // NOMINAL, WARM, CRITICAL
  activeFps: integer('active_fps').default(30),
  lastHeartbeatAt: timestamp('last_heartbeat_at'),
  ipAddress: varchar('ip_address', { length: 64 }),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
}, (table) => [
  index('idx_devices_org_status').on(table.organizationId, table.status),
  index('idx_devices_org_heartbeat').on(table.organizationId, table.lastHeartbeatAt),
]);

// ── 9. Face Templates (Mathematical 512-D Vectors, Zero Raw Images) ──
export const faceTemplates = pgTable('face_templates', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }),
  orgId: uuid('org_id'), // backward compat alias
  studentId: uuid('student_id').references(() => students.id, { onDelete: 'cascade' }),
  studentRoll: varchar('student_roll', { length: 64 }).notNull(),
  fullName: varchar('full_name', { length: 255 }).notNull(),
  department: varchar('department', { length: 128 }).notNull().default('General'),
  semester: varchar('semester', { length: 32 }).notNull().default('I'),
  angleType: varchar('angle_type', { length: 32 }).notNull().default('FRONTAL'), // FRONTAL, LEFT_15, RIGHT_15, UP_10, DOWN_10
  embedding: vector512('embedding').notNull(),
  qualityScore: real('quality_score').notNull().default(100.0),
  templateVersion: varchar('template_version', { length: 32 }).notNull().default('v2.0'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
}, (table) => [
  index('idx_face_templates_org_roll').on(table.organizationId, table.studentRoll),
]);
export const faceEmbeddings = faceTemplates; // backward compatibility alias

// ── 10. Attendance Sessions ──
export const attendanceSessions = pgTable('attendance_sessions', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  classId: uuid('class_id').references(() => classes.id, { onDelete: 'cascade' }),
  sessionDate: varchar('session_date', { length: 16 }).notNull(), // yyyy-MM-dd
  startTime: timestamp('start_time'),
  endTime: timestamp('end_time'),
  status: varchar('status', { length: 32 }).notNull().default('OPEN'), // OPEN, CLOSED, CANCELLED
  createdBy: uuid('created_by').references(() => users.id, { onDelete: 'set null' }),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 11. Attendance Events (Idempotent Event Log) ──
export const attendanceEvents = pgTable('attendance_events', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }),
  orgId: uuid('org_id'), // backward compat
  eventId: varchar('event_id', { length: 128 }).notNull().unique(),
  recordId: varchar('record_id', { length: 128 }), // backward compat
  personId: uuid('person_id').references(() => students.id, { onDelete: 'set null' }),
  studentRoll: varchar('student_roll', { length: 64 }).notNull().default('UNKNOWN'),
  studentName: varchar('student_name', { length: 255 }).notNull().default('Student'),
  deviceId: varchar('device_id', { length: 128 }).notNull().default('kiosk-alpha'),
  kioskId: varchar('kiosk_id', { length: 128 }), // backward compat
  sessionId: uuid('session_id').references(() => attendanceSessions.id, { onDelete: 'set null' }),
  timestamp: bigint('timestamp', { mode: 'number' }).notNull(),
  sessionDate: varchar('session_date', { length: 16 }).notNull(), // yyyy-MM-dd
  status: varchar('status', { length: 32 }).notNull().default('PRESENT'), // PRESENT, LATE, ABSENT, EXCUSED
  confidencePct: integer('confidence_pct').notNull().default(95),
  securityTier: varchar('security_tier', { length: 32 }).notNull().default('HIGH'),
  sha256Hash: varchar('sha256_hash', { length: 128 }).notNull().default(''),
  hardwareHash: varchar('hardware_hash', { length: 128 }).notNull().default(''),
  offlineFlag: integer('offline_flag').notNull().default(1),
  serverEvaluated: integer('server_evaluated').notNull().default(1),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  syncedAt: timestamp('synced_at').defaultNow().notNull(),
}, (table) => [
  index('idx_attendance_events_org_date').on(table.organizationId, table.sessionDate),
  index('idx_attendance_events_event_id').on(table.eventId),
]);
export const attendanceRecords = attendanceEvents; // backward compatibility alias

// ── 12. Attendance Adjustments (Audit Trail for Manual Status Changes) ──
export const attendanceAdjustments = pgTable('attendance_adjustments', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  attendanceEventId: uuid('attendance_event_id').references(() => attendanceEvents.id, { onDelete: 'cascade' }).notNull(),
  adjustedByUserId: uuid('adjusted_by_user_id').references(() => users.id, { onDelete: 'set null' }),
  previousStatus: varchar('previous_status', { length: 32 }).notNull(),
  newStatus: varchar('new_status', { length: 32 }).notNull(),
  reason: text('reason').notNull(), // Mandatory reason required
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 13. Sync Operations ──
export const syncOperations = pgTable('sync_operations', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  deviceId: varchar('device_id', { length: 128 }).notNull(),
  batchId: varchar('batch_id', { length: 128 }).notNull(),
  eventsCount: integer('events_count').notNull().default(0),
  status: varchar('status', { length: 32 }).notNull().default('SUCCESS'), // SUCCESS, PARTIAL, FAILED
  errorDetails: text('error_details'),
  syncedAt: timestamp('synced_at').defaultNow().notNull(),
});

// ── 14. Subscriptions & Licensing ──
export const subscriptions = pgTable('subscriptions', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }),
  orgId: uuid('org_id'), // backward compat
  tier: varchar('tier', { length: 32 }).notNull().default('FREE'), // FREE, PREMIUM, PRO, INSTITUTION
  status: varchar('status', { length: 32 }).notNull().default('ACTIVE'), // ACTIVE, GRACE_PERIOD, ARCHIVE_READ_ONLY, CANCELLED
  billingProvider: varchar('billing_provider', { length: 32 }).notNull().default('GOOGLE_PLAY'), // GOOGLE_PLAY, WEBSITE_CUSTOM, RAZORPAY
  externalSubscriptionId: text('external_subscription_id'),
  purchaseToken: text('purchase_token'),
  amountInr: integer('amount_inr').notNull().default(0),
  peopleLimit: integer('people_limit').notNull().default(25),
  deviceLimit: integer('device_limit').notNull().default(1),
  currentPeriodStart: timestamp('current_period_start').defaultNow().notNull(),
  currentPeriodEnd: timestamp('current_period_end'),
  validUntil: timestamp('valid_until').notNull(),
  graceUntil: timestamp('grace_until'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// ── 15. Subscription Events ──
export const subscriptionEvents = pgTable('subscription_events', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  subscriptionId: uuid('subscription_id').references(() => subscriptions.id, { onDelete: 'cascade' }).notNull(),
  eventType: varchar('event_type', { length: 64 }).notNull(), // CREATED, RENEWED, CANCELLED, GRACE_ENTERED, ARCHIVED
  providerPayload: text('provider_payload'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 16. Payments ──
export const payments = pgTable('payments', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  subscriptionId: uuid('subscription_id').references(() => subscriptions.id, { onDelete: 'set null' }),
  amountInr: integer('amount_inr').notNull().default(0),
  currency: varchar('currency', { length: 8 }).notNull().default('INR'),
  paymentMethod: varchar('payment_method', { length: 32 }).notNull().default('UPI'),
  status: varchar('status', { length: 32 }).notNull().default('SUCCESS'),
  transactionId: varchar('transaction_id', { length: 128 }),
  receiptUrl: text('receipt_url'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 17. Invoices ──
export const invoices = pgTable('invoices', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  invoiceNumber: varchar('invoice_number', { length: 64 }).notNull().unique(),
  amountInr: integer('amount_inr').notNull().default(0),
  status: varchar('status', { length: 32 }).notNull().default('DRAFT'), // DRAFT, SENT, PAID, OVERDUE
  dueDate: timestamp('due_date'),
  paidAt: timestamp('paid_at'),
  pdfUrl: text('pdf_url'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 18. Audit Logs ──
export const auditLogs = pgTable('audit_logs', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  userId: uuid('user_id').references(() => users.id, { onDelete: 'set null' }),
  action: varchar('action', { length: 64 }).notNull(), // ATTENDANCE_ADJUSTED, DEVICE_PAIRED, DEVICE_REVOKED, USER_ROLE_CHANGED, etc.
  entityType: varchar('entity_type', { length: 64 }).notNull(),
  entityId: varchar('entity_id', { length: 128 }),
  oldValues: text('old_values'),
  newValues: text('new_values'),
  reason: text('reason'), // Mandatory for manual adjustments
  ipAddress: varchar('ip_address', { length: 64 }),
  userAgent: text('user_agent'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
}, (table) => [
  index('idx_audit_logs_org_created').on(table.organizationId, table.createdAt),
]);

// ── 19. Staff Invitations (Cryptographic Hashed Token Invite Flow) ──
export const staffInvitations = pgTable('staff_invitations', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationId: uuid('organization_id').references(() => organizations.id, { onDelete: 'cascade' }).notNull(),
  email: varchar('email', { length: 255 }).notNull(),
  fullName: varchar('full_name', { length: 255 }),
  role: varchar('role', { length: 32 }).notNull().default('TEACHER'),
  tokenHash: varchar('token_hash', { length: 128 }).notNull().unique(),
  status: varchar('status', { length: 32 }).notNull().default('PENDING'), // PENDING, ACCEPTED, REVOKED
  expiresAt: timestamp('expires_at').notNull(),
  invitedByUserId: uuid('invited_by_user_id').references(() => users.id, { onDelete: 'set null' }),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

// ── 20. Institution Sales Leads (Real B2B Enterprise Lead Pipeline) ──
export const institutionLeads = pgTable('institution_leads', {
  id: uuid('id').defaultRandom().primaryKey(),
  organizationName: varchar('organization_name', { length: 255 }).notNull(),
  contactName: varchar('contact_name', { length: 255 }).notNull(),
  email: varchar('email', { length: 255 }).notNull(),
  phone: varchar('phone', { length: 32 }),
  expectedSeats: integer('expected_seats').notNull().default(500),
  status: varchar('status', { length: 32 }).notNull().default('NEW'), // NEW, CONTACTED, DEMO, TRIAL, NEGOTIATION, ACTIVE, LOST
  notes: text('notes'),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});
