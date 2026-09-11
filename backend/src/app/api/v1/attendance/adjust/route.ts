import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents, attendanceAdjustments, auditLogs } from '@/db/schema';
import { eq, or, and } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';

export async function POST(req: NextRequest) {
  try {
    // Enforce session authentication (TEACHER, ADMIN, OWNER)
    const auth = await requireSession(req, 'TEACHER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }

    const { user } = auth;
    const sessionUserId = user.userId;
    const sessionOrgId = user.orgId;
    if (!sessionOrgId) {
      return NextResponse.json(
        { success: false, error: 'User does not belong to an organization. Please complete onboarding.' },
        { status: 400 }
      );
    }

    const body = await req.json();
    const {
      attendanceEventId,
      recordId,
      newStatus,
      reason,
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

    if (!isDbConfigured()) {
      return NextResponse.json(
        { success: false, error: 'Database service is unavailable' },
        { status: 503 }
      );
    }

    const database = getDb();
    if (!database) {
      return NextResponse.json({ success: false, error: 'Database connection failed' }, { status: 500 });
    }

    try {

          // Locate attendance event scoped to session organizationId
          const events = await database
            .select()
            .from(attendanceEvents)
            .where(
              and(
                eq(attendanceEvents.organizationId, sessionOrgId),
                or(
                  attendanceEventId ? eq(attendanceEvents.id, attendanceEventId) : undefined,
                  recordId ? eq(attendanceEvents.eventId, recordId) : undefined,
                  recordId ? eq(attendanceEvents.recordId, recordId) : undefined
                )
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
              organizationId: sessionOrgId,
              attendanceEventId: event.id,
              adjustedByUserId: sessionUserId,
              previousStatus,
              newStatus: targetStatus,
              reason: reason.trim(),
            });

            // 3. Record in audit_logs
            await database.insert(auditLogs).values({
              organizationId: sessionOrgId,
              userId: sessionUserId,
              action: 'ATTENDANCE_STATUS_ADJUSTED',
              entityType: 'ATTENDANCE_EVENT',
              entityId: event.eventId || event.id,
              oldValues: JSON.stringify({ status: previousStatus, roll: event.studentRoll, name: event.studentName }),
              newValues: JSON.stringify({ status: targetStatus, adjustedAt: new Date().toISOString() }),
              reason: reason.trim(),
            });
          } else {
            return NextResponse.json(
              {
                success: false,
                error: 'Attendance record not found or does not belong to your organization.',
              },
              { status: 404 }
            );
          }
      } catch (dbErr: any) {
        return NextResponse.json(
          { success: false, error: dbErr?.message || 'Failed to update attendance record in database' },
          { status: 500 }
        );
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
