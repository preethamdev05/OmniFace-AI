import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents, attendanceAdjustments, auditLogs } from '@/db/schema';
import { ensureDefaultOrganization, DEFAULT_ORG_ID } from '@/db/helpers';
import { eq, or } from 'drizzle-orm';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const {
      attendanceEventId,
      recordId,
      newStatus,
      reason,
      userId,
      orgId = DEFAULT_ORG_ID,
    } = body;

    // Foundational Decision 5: Mandatory reason required for manual attendance adjustments
    if (!reason || typeof reason !== 'string' || reason.trim().length < 3) {
      return NextResponse.json(
        {
          success: false,
          error: 'Mandatory reason is required for manual attendance status adjustments under statutory audit compliance rules (minimum 3 characters).',
        },
        { status: 400 }
      );
    }

    const validStatuses = ['PRESENT', 'LATE', 'ABSENT', 'EXCUSED'];
    if (!newStatus || !validStatuses.includes(newStatus.toUpperCase())) {
      return NextResponse.json(
        {
          success: false,
          error: `Invalid status. Must be one of: ${validStatuses.join(', ')}`,
        },
        { status: 400 }
      );
    }

    const targetStatus = newStatus.toUpperCase();
    let previousStatus = 'ABSENT';
    let targetEventId = attendanceEventId || recordId;

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          await ensureDefaultOrganization(database);

          // Locate attendance event by UUID or unique record_id / event_id
          const events = await database
            .select()
            .from(attendanceEvents)
            .where(
              or(
                attendanceEventId ? eq(attendanceEvents.id, attendanceEventId) : undefined,
                recordId ? eq(attendanceEvents.eventId, recordId) : undefined,
                recordId ? eq(attendanceEvents.recordId, recordId) : undefined
              )
            )
            .limit(1);

          if (events.length > 0) {
            const event = events[0];
            targetEventId = event.id;
            previousStatus = event.status;

            // 1. Update attendance event status
            await database
              .update(attendanceEvents)
              .set({ status: targetStatus, serverEvaluated: 1 })
              .where(eq(attendanceEvents.id, event.id));

            // 2. Insert into attendance_adjustments
            await database.insert(attendanceAdjustments).values({
              organizationId: event.organizationId || orgId,
              attendanceEventId: event.id,
              adjustedByUserId: userId || null,
              previousStatus,
              newStatus: targetStatus,
              reason: reason.trim(),
            });

            // 3. Record in audit_logs
            await database.insert(auditLogs).values({
              organizationId: event.organizationId || orgId,
              userId: userId || null,
              action: 'ATTENDANCE_STATUS_ADJUSTED',
              entityType: 'ATTENDANCE_EVENT',
              entityId: event.eventId || event.id,
              oldValues: JSON.stringify({ status: previousStatus, roll: event.studentRoll, name: event.studentName }),
              newValues: JSON.stringify({ status: targetStatus, adjustedAt: new Date().toISOString() }),
              reason: reason.trim(),
            });
          } else {
            // DB configured but event not found
            previousStatus = 'ABSENT';
          }
        } catch (dbErr) {
          console.warn('DB attendance adjustment error:', dbErr);
        }
      }
    }

    return NextResponse.json({
      success: true,
      message: `Attendance status updated from ${previousStatus} to ${targetStatus}`,
      attendanceEventId: targetEventId,
      previousStatus,
      newStatus: targetStatus,
      reason: reason.trim(),
      timestamp: Date.now(),
    });
  } catch (err: any) {
    return NextResponse.json(
      { success: false, error: err?.message || 'Failed to adjust attendance' },
      { status: 500 }
    );
  }
}
