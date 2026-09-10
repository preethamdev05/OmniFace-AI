CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
--> statement-breakpoint
CREATE EXTENSION IF NOT EXISTS vector;
--> statement-breakpoint
CREATE TABLE "attendance_records" (
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
--> statement-breakpoint
CREATE TABLE "face_embeddings" (
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
--> statement-breakpoint
CREATE TABLE "organizations" (
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
--> statement-breakpoint
CREATE TABLE "subscriptions" (
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
--> statement-breakpoint
CREATE TABLE "users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"org_id" uuid NOT NULL,
	"email" varchar(255) NOT NULL,
	"full_name" varchar(255) NOT NULL,
	"role" varchar(32) DEFAULT 'ADMIN' NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "users_email_unique" UNIQUE("email")
);
--> statement-breakpoint
ALTER TABLE "attendance_records" ADD CONSTRAINT "attendance_records_org_id_organizations_id_fk" FOREIGN KEY ("org_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "face_embeddings" ADD CONSTRAINT "face_embeddings_org_id_organizations_id_fk" FOREIGN KEY ("org_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "subscriptions" ADD CONSTRAINT "subscriptions_org_id_organizations_id_fk" FOREIGN KEY ("org_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "users" ADD CONSTRAINT "users_org_id_organizations_id_fk" FOREIGN KEY ("org_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;