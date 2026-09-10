import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getPool, getDb } from '@/db';
import { ensureDefaultOrganization } from '@/db/helpers';

const INIT_SQL = `
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS "organizations" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"name" varchar(255) NOT NULL,
	"type" varchar(64) DEFAULT 'SCHOOL' NOT NULL,
	"tier" varchar(32) DEFAULT 'FREE' NOT NULL,
	"max_people" integer DEFAULT 25 NOT NULL,
	"max_devices" integer DEFAULT 1 NOT NULL,
	"max_kiosks" integer DEFAULT 1 NOT NULL,
	"contact_email" varchar(255) NOT NULL,
	"contact_phone" varchar(32),
	"status" varchar(32) DEFAULT 'ACTIVE' NOT NULL,
	"grace_period_end" timestamp,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"firebase_uid" varchar(128) UNIQUE,
	"email" varchar(255) NOT NULL UNIQUE,
	"full_name" varchar(255) NOT NULL,
	"avatar_url" text,
	"org_id" uuid,
	"role" varchar(32) DEFAULT 'ADMIN' NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "organization_members" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"user_id" uuid NOT NULL REFERENCES "users"("id") ON DELETE CASCADE,
	"role" varchar(32) DEFAULT 'VIEWER' NOT NULL,
	"status" varchar(32) DEFAULT 'ACTIVE' NOT NULL,
	"joined_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "departments" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"name" varchar(128) NOT NULL,
	"code" varchar(32) NOT NULL,
	"description" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "classes" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"department_id" uuid REFERENCES "departments"("id") ON DELETE SET NULL,
	"name" varchar(128) NOT NULL,
	"section" varchar(32) DEFAULT 'A' NOT NULL,
	"schedule_start_time" varchar(16) DEFAULT '09:00' NOT NULL,
	"schedule_end_time" varchar(16) DEFAULT '17:00' NOT NULL,
	"grace_minutes" integer DEFAULT 15 NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "students" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"department_id" uuid REFERENCES "departments"("id") ON DELETE SET NULL,
	"roll_number" varchar(64) NOT NULL,
	"full_name" varchar(255) NOT NULL,
	"email" varchar(255),
	"phone" varchar(32),
	"status" varchar(32) DEFAULT 'ACTIVE' NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "student_classes" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"student_id" uuid NOT NULL REFERENCES "students"("id") ON DELETE CASCADE,
	"class_id" uuid NOT NULL REFERENCES "classes"("id") ON DELETE CASCADE,
	"enrolled_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "devices" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"device_identifier" varchar(128) NOT NULL UNIQUE,
	"device_name" varchar(255) NOT NULL,
	"pairing_code" varchar(16),
	"pairing_code_expires_at" timestamp,
	"device_token" text,
	"app_version" varchar(32) DEFAULT 'v2.0.0',
	"last_sync_at" timestamp,
	"last_attendance_at" timestamp,
	"pending_events_count" integer DEFAULT 0 NOT NULL,
	"status" varchar(32) DEFAULT 'OFFLINE' NOT NULL,
	"is_paired" integer DEFAULT 0 NOT NULL,
	"hardware_hash" varchar(128),
	"paired_at" timestamp,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "face_templates" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid REFERENCES "organizations"("id") ON DELETE CASCADE,
	"org_id" uuid,
	"student_id" uuid REFERENCES "students"("id") ON DELETE CASCADE,
	"student_roll" varchar(64) NOT NULL,
	"full_name" varchar(255) NOT NULL,
	"department" varchar(128) DEFAULT 'General' NOT NULL,
	"semester" varchar(32) DEFAULT 'I' NOT NULL,
	"angle_type" varchar(32) DEFAULT 'FRONTAL' NOT NULL,
	"embedding" vector(512) NOT NULL,
	"quality_score" real DEFAULT 100 NOT NULL,
	"template_version" varchar(32) DEFAULT 'v2.0' NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "attendance_sessions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"class_id" uuid REFERENCES "classes"("id") ON DELETE CASCADE,
	"session_date" varchar(16) NOT NULL,
	"start_time" timestamp,
	"end_time" timestamp,
	"status" varchar(32) DEFAULT 'OPEN' NOT NULL,
	"created_by" uuid REFERENCES "users"("id") ON DELETE SET NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "attendance_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid REFERENCES "organizations"("id") ON DELETE CASCADE,
	"org_id" uuid,
	"event_id" varchar(128) NOT NULL UNIQUE,
	"record_id" varchar(128),
	"person_id" uuid REFERENCES "students"("id") ON DELETE SET NULL,
	"student_roll" varchar(64) DEFAULT 'UNKNOWN' NOT NULL,
	"student_name" varchar(255) DEFAULT 'Student' NOT NULL,
	"device_id" varchar(128) DEFAULT 'kiosk-alpha' NOT NULL,
	"kiosk_id" varchar(128),
	"session_id" uuid REFERENCES "attendance_sessions"("id") ON DELETE SET NULL,
	"timestamp" bigint NOT NULL,
	"session_date" varchar(16) NOT NULL,
	"status" varchar(32) DEFAULT 'PRESENT' NOT NULL,
	"confidence_pct" integer DEFAULT 95 NOT NULL,
	"security_tier" varchar(32) DEFAULT 'HIGH' NOT NULL,
	"sha256_hash" varchar(128) DEFAULT '' NOT NULL,
	"hardware_hash" varchar(128) DEFAULT '' NOT NULL,
	"offline_flag" integer DEFAULT 1 NOT NULL,
	"server_evaluated" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"synced_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "attendance_adjustments" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"attendance_event_id" uuid NOT NULL REFERENCES "attendance_events"("id") ON DELETE CASCADE,
	"adjusted_by_user_id" uuid REFERENCES "users"("id") ON DELETE SET NULL,
	"previous_status" varchar(32) NOT NULL,
	"new_status" varchar(32) NOT NULL,
	"reason" text NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "sync_operations" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"device_id" varchar(128) NOT NULL,
	"batch_id" varchar(128) NOT NULL,
	"events_count" integer DEFAULT 0 NOT NULL,
	"status" varchar(32) DEFAULT 'SUCCESS' NOT NULL,
	"error_details" text,
	"synced_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "subscriptions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid REFERENCES "organizations"("id") ON DELETE CASCADE,
	"org_id" uuid,
	"tier" varchar(32) DEFAULT 'FREE' NOT NULL,
	"status" varchar(32) DEFAULT 'ACTIVE' NOT NULL,
	"billing_provider" varchar(32) DEFAULT 'GOOGLE_PLAY' NOT NULL,
	"external_subscription_id" text,
	"purchase_token" text,
	"amount_inr" integer DEFAULT 0 NOT NULL,
	"people_limit" integer DEFAULT 25 NOT NULL,
	"device_limit" integer DEFAULT 1 NOT NULL,
	"current_period_start" timestamp DEFAULT now() NOT NULL,
	"current_period_end" timestamp,
	"valid_until" timestamp NOT NULL,
	"grace_until" timestamp,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "subscription_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"subscription_id" uuid NOT NULL REFERENCES "subscriptions"("id") ON DELETE CASCADE,
	"event_type" varchar(64) NOT NULL,
	"provider_payload" text,
	"created_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "payments" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"subscription_id" uuid REFERENCES "subscriptions"("id") ON DELETE SET NULL,
	"amount_inr" integer DEFAULT 0 NOT NULL,
	"currency" varchar(8) DEFAULT 'INR' NOT NULL,
	"payment_method" varchar(32) DEFAULT 'UPI' NOT NULL,
	"status" varchar(32) DEFAULT 'SUCCESS' NOT NULL,
	"transaction_id" varchar(128),
	"receipt_url" text,
	"created_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "invoices" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"invoice_number" varchar(64) NOT NULL UNIQUE,
	"amount_inr" integer DEFAULT 0 NOT NULL,
	"status" varchar(32) DEFAULT 'DRAFT' NOT NULL,
	"due_date" timestamp,
	"paid_at" timestamp,
	"pdf_url" text,
	"created_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "audit_logs" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
	"user_id" uuid REFERENCES "users"("id") ON DELETE SET NULL,
	"action" varchar(64) NOT NULL,
	"entity_type" varchar(64) NOT NULL,
	"entity_id" varchar(128),
	"old_values" text,
	"new_values" text,
	"reason" text,
	"ip_address" varchar(64),
	"user_agent" text,
	"created_at" timestamp DEFAULT now() NOT NULL
);
`;

export async function POST(req: NextRequest) {
  if (!isDbConfigured()) {
    return NextResponse.json(
      {
        success: false,
        error: 'DATABASE_URL is not configured in environment variables',
      },
      { status: 400 }
    );
  }

  try {
    const pool = getPool();
    const client = await pool.connect();

    try {
      // Execute table creation statements
      await client.query(INIT_SQL);

      // Seed default organization
      const database = getDb();
      if (database) {
        await ensureDefaultOrganization(database);
      }

      return NextResponse.json({
        success: true,
        message: 'OmniFace AI 18-table schema and pgvector extension successfully migrated and seeded.',
        tables: [
          'organizations', 'users', 'organization_members', 'departments', 'classes',
          'students', 'student_classes', 'devices', 'face_templates', 'attendance_sessions',
          'attendance_events', 'attendance_adjustments', 'sync_operations', 'subscriptions',
          'subscription_events', 'payments', 'invoices', 'audit_logs'
        ],
        timestamp: Date.now(),
      });
    } finally {
      client.release();
    }
  } catch (error: any) {
    return NextResponse.json(
      {
        success: false,
        error: error?.message || 'Database migration execution failed',
      },
      { status: 500 }
    );
  }
}
