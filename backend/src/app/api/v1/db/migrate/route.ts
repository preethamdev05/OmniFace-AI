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
	"contact_email" varchar(255) NOT NULL,
	"contact_phone" varchar(32),
	"max_kiosks" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"org_id" uuid NOT NULL,
	"email" varchar(255) NOT NULL,
	"full_name" varchar(255) NOT NULL,
	"role" varchar(32) DEFAULT 'ADMIN' NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "users_email_unique" UNIQUE("email")
);

CREATE TABLE IF NOT EXISTS "face_embeddings" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"org_id" uuid NOT NULL,
	"student_roll" varchar(64) NOT NULL,
	"full_name" varchar(255) NOT NULL,
	"department" varchar(128) DEFAULT 'General' NOT NULL,
	"semester" varchar(32) DEFAULT 'I' NOT NULL,
	"angle_type" varchar(32) DEFAULT 'FRONTAL' NOT NULL,
	"embedding" vector(512) NOT NULL,
	"quality_score" real DEFAULT 100 NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "attendance_records" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"org_id" uuid NOT NULL,
	"record_id" varchar(64) NOT NULL,
	"student_roll" varchar(64) NOT NULL,
	"student_name" varchar(255) NOT NULL,
	"timestamp" bigint NOT NULL,
	"session_date" varchar(16) NOT NULL,
	"confidence_pct" integer NOT NULL,
	"security_tier" varchar(32) NOT NULL,
	"sha256_hash" varchar(64) NOT NULL,
	"kiosk_id" varchar(64) DEFAULT 'kiosk-alpha' NOT NULL,
	"synced_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "attendance_records_record_id_unique" UNIQUE("record_id")
);

CREATE TABLE IF NOT EXISTS "subscriptions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"org_id" uuid NOT NULL,
	"tier" varchar(32) NOT NULL,
	"status" varchar(32) DEFAULT 'ACTIVE' NOT NULL,
	"billing_provider" varchar(32) NOT NULL,
	"external_subscription_id" text,
	"purchase_token" text,
	"amount_inr" integer DEFAULT 0 NOT NULL,
	"valid_until" timestamp NOT NULL,
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
        message: 'PostgreSQL schema and pgvector extension successfully migrated and seeded.',
        tables: ['organizations', 'users', 'face_embeddings', 'attendance_records', 'subscriptions'],
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
