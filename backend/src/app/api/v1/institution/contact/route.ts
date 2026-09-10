import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { isDbConfigured, getDb } from '@/db';
import { institutionLeads, auditLogs } from '@/db/schema';
import { authenticateSession } from '@/lib/api-auth';

const ContactSchema = z.object({
  organizationName: z.string().trim().min(2, 'Organization name must be at least 2 characters'),
  contactName: z.string().trim().min(2, 'Contact person name must be at least 2 characters'),
  email: z.string().trim().email('Valid email address is required'),
  phone: z.string().trim().optional(),
  expectedSeats: z.number().int().min(50).default(500),
  notes: z.string().trim().optional(),
});

export async function POST(req: NextRequest) {
  try {
    const raw = await req.json();
    const parseResult = ContactSchema.safeParse(raw);

    if (!parseResult.success) {
      return NextResponse.json(
        {
          success: false,
          error: 'Validation failed',
          details: parseResult.error.flatten(),
        },
        { status: 400 }
      );
    }

    const { organizationName, contactName, email, phone, expectedSeats, notes } = parseResult.data;

    // Optional authenticated session context
    const session = await authenticateSession(req);

    let leadId: string = crypto.randomUUID();

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        const inserted = await database
          .insert(institutionLeads)
          .values({
            organizationName,
            contactName,
            email,
            phone: phone || null,
            expectedSeats,
            status: 'NEW',
            notes: notes || null,
          })
          .returning({ id: institutionLeads.id });

        if (inserted.length > 0) {
          leadId = inserted[0].id;
        }

        // Audit log if in session
        if (session) {
          await database.insert(auditLogs).values({
            organizationId: session.orgId,
            userId: session.userId,
            action: 'INSTITUTION_LEAD_SUBMITTED',
            entityType: 'INSTITUTION_LEAD',
            entityId: leadId,
            newValues: JSON.stringify({ organizationName, expectedSeats, email }),
            reason: 'Inquired about 500+ seat Institution deployment',
          });
        }
      }
    }

    return NextResponse.json(
      {
        success: true,
        leadId,
        message: 'Your institutional deployment request has been received. Our enterprise engineering team will contact you within 24 business hours.',
      },
      { status: 201 }
    );
  } catch (err: any) {
    console.error('Institution contact submission failed:', err);
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to submit inquiry' },
      { status: 500 }
    );
  }
}
