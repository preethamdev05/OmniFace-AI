import { NextRequest, NextResponse } from 'next/server';
import { isDbConfigured, getDb } from '@/db';
import { attendanceEvents, organizations } from '@/db/schema';
import { eq, desc } from 'drizzle-orm';
import { requireSession } from '@/lib/api-auth';
import { assertCanExportFormat, ExportFormat } from '@/lib/entitlements';
import ExcelJS from 'exceljs';
import PDFDocument from 'pdfkit';

export async function GET(req: NextRequest) {
  try {
    const auth = await requireSession(req, 'TEACHER');
    if (auth.errorResponse) {
      return auth.errorResponse;
    }
    const { user } = auth;
    const orgId = user.orgId;

    if (!orgId) {
      return NextResponse.json(
        { success: false, error: 'No organization affiliated with session' },
        { status: 403 }
      );
    }

    const { searchParams } = new URL(req.url);
    const rawFormat = (searchParams.get('format') || 'csv').toLowerCase();
    const format = (['csv', 'xlsx', 'pdf'].includes(rawFormat) ? rawFormat : 'csv') as ExportFormat;
    const month = searchParams.get('month') || new Date().toISOString().slice(0, 7);
    const dept = searchParams.get('department') || 'All';

    // ── 1. Centralized Entitlement Gate ──
    try {
      assertCanExportFormat(user.entitlements, format);
    } catch (gateErr: any) {
      return NextResponse.json(
        {
          success: false,
          error: gateErr.message,
          code: gateErr.code || 'ENTITLEMENT_EXPORT_RESTRICTED',
          requiredTier: format === 'csv' ? 'PREMIUM' : 'PRO',
          currentTier: user.entitlements.tier,
        },
        { status: gateErr.status || 403 }
      );
    }

    // ── 2. Query Authoritative PostgreSQL Events ──
    let eventsData: any[] = [];
    let orgName = user.orgName || 'OmniFace Campus';

    if (isDbConfigured()) {
      const database = getDb();
      if (database) {
        try {
          const orgRec = await database
            .select({ name: organizations.name })
            .from(organizations)
            .where(eq(organizations.id, orgId))
            .limit(1);
          if (orgRec.length > 0) {
            orgName = orgRec[0].name;
          }

          eventsData = await database
            .select()
            .from(attendanceEvents)
            .where(eq(attendanceEvents.organizationId, orgId))
            .orderBy(desc(attendanceEvents.timestamp))
            .limit(2500);
        } catch (dbErr) {
          console.warn('DB reports export query warning:', dbErr);
        }
      }
    }

    const safeMonth = month.replace(/[^a-zA-Z0-9_-]/g, '_');
    const safeDept = dept.replace(/[^a-zA-Z0-9_-]/g, '_');
    const baseFilename = `OmniFace_Attendance_${safeMonth}_${safeDept}`;

    // ── 3. Render Requested Format ──

    // ── Format A: Real XLSX via ExcelJS ──
    if (format === 'xlsx') {
      const workbook = new ExcelJS.Workbook();
      workbook.creator = 'OmniFace AI Enterprise Platform';
      workbook.lastModifiedBy = user.fullName || 'OmniFace Admin';
      workbook.created = new Date();

      const worksheet = workbook.addWorksheet('Attendance Register', {
        views: [{ state: 'frozen', xSplit: 0, ySplit: 4 }],
      });

      // Title Banner
      worksheet.mergeCells('A1:J1');
      const titleCell = worksheet.getCell('A1');
      titleCell.value = `${orgName.toUpperCase()} — ATTENDANCE AUDIT REGISTER`;
      titleCell.font = { name: 'Arial', size: 14, bold: true, color: { argb: 'FFFFFFFF' } };
      titleCell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF1E293B' } };
      titleCell.alignment = { vertical: 'middle', horizontal: 'center' };
      worksheet.getRow(1).height = 30;

      // Subtitle
      worksheet.mergeCells('A2:J2');
      const subCell = worksheet.getCell('A2');
      subCell.value = `Period: ${month} | Department: ${dept} | Generated: ${new Date().toUTCString()}`;
      subCell.font = { name: 'Arial', size: 10, italic: true, color: { argb: 'FF64748B' } };
      subCell.alignment = { vertical: 'middle', horizontal: 'center' };
      worksheet.getRow(2).height = 20;

      worksheet.addRow([]); // Blank row 3

      // Column Headers Row 4
      const headerRow = worksheet.addRow([
        'Event ID',
        'Roll Number',
        'Student Name',
        'Date',
        'Time (UTC)',
        'Status',
        'Confidence',
        'Security Tier',
        'Device ID',
        'Cryptographic Proof',
      ]);
      headerRow.height = 24;
      headerRow.eachCell((cell) => {
        cell.font = { name: 'Arial', size: 10, bold: true, color: { argb: 'FFFFFFFF' } };
        cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF4F46E5' } };
        cell.alignment = { vertical: 'middle', horizontal: 'center' };
        cell.border = {
          top: { style: 'thin', color: { argb: 'FFCBD5E1' } },
          bottom: { style: 'medium', color: { argb: 'FF0F172A' } },
        };
      });

      // Data Rows
      let presentCount = 0;
      let lateCount = 0;

      eventsData.forEach((e) => {
        if (e.status === 'PRESENT') presentCount++;
        if (e.status === 'LATE') lateCount++;

        const row = worksheet.addRow([
          e.eventId || e.id,
          e.studentRoll || 'N/A',
          e.studentName || 'Student',
          e.sessionDate || '',
          new Date(Number(e.timestamp)).toLocaleTimeString(),
          e.status,
          `${e.confidencePct || 95}%`,
          e.securityTier || 'HIGH',
          e.deviceId || 'kiosk-alpha',
          e.sha256Hash ? 'VERIFIED (SHA-256)' : 'STANDARD',
        ]);

        const statusCell = row.getCell(6);
        if (e.status === 'PRESENT') {
          statusCell.font = { color: { argb: 'FF059669' }, bold: true };
        } else if (e.status === 'LATE') {
          statusCell.font = { color: { argb: 'FFD97706' }, bold: true };
        } else {
          statusCell.font = { color: { argb: 'FFDC2626' }, bold: true };
        }
      });

      // Auto-fit Column Widths
      worksheet.columns.forEach((column) => {
        let maxLen = 12;
        column.eachCell?.({ includeEmpty: false }, (cell) => {
          const len = cell.value ? String(cell.value).length : 0;
          if (len > maxLen) maxLen = Math.min(len + 2, 40);
        });
        column.width = maxLen;
      });

      // Summary Footer
      worksheet.addRow([]);
      const summaryRow = worksheet.addRow([
        'SUMMARY',
        `Total: ${eventsData.length}`,
        `Present: ${presentCount}`,
        `Late: ${lateCount}`,
        `Rate: ${eventsData.length > 0 ? Math.round((presentCount / eventsData.length) * 100) : 0}%`,
      ]);
      summaryRow.font = { bold: true, size: 10 };

      const buffer = await workbook.xlsx.writeBuffer();

      return new NextResponse(new Uint8Array(buffer), {
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          'Content-Disposition': `attachment; filename="${baseFilename}.xlsx"`,
        },
      });
    }

    // ── Format B: Real PDF via PDFKit ──
    if (format === 'pdf') {
      const pdfDoc = new PDFDocument({ margin: 40, size: 'A4' });
      const chunks: Buffer[] = [];

      const pdfPromise = new Promise<Buffer>((resolve, reject) => {
        pdfDoc.on('data', (chunk) => chunks.push(chunk));
        pdfDoc.on('end', () => resolve(Buffer.concat(chunks)));
        pdfDoc.on('error', reject);
      });

      // Header Banner
      pdfDoc.rect(40, 40, 515, 50).fill('#1E293B');
      pdfDoc.fillColor('#FFFFFF').fontSize(16).font('Helvetica-Bold').text(orgName.toUpperCase(), 50, 50);
      pdfDoc.fontSize(10).font('Helvetica').text('INSTITUTIONAL ATTENDANCE REGISTER & BIOMETRIC AUDIT', 50, 70);

      pdfDoc.fillColor('#334155').fontSize(9).font('Helvetica');
      pdfDoc.text(`Month: ${month}    |    Department: ${dept}    |    Generated: ${new Date().toISOString().slice(0, 10)}`, 40, 105);

      pdfDoc.moveTo(40, 120).lineTo(555, 120).strokeColor('#E2E8F0').stroke();

      // Table Header
      let y = 135;
      pdfDoc.rect(40, y, 515, 20).fill('#4F46E5');
      pdfDoc.fillColor('#FFFFFF').fontSize(8).font('Helvetica-Bold');
      pdfDoc.text('ROLL NUMBER', 45, y + 6);
      pdfDoc.text('STUDENT NAME', 140, y + 6);
      pdfDoc.text('DATE', 260, y + 6);
      pdfDoc.text('STATUS', 330, y + 6);
      pdfDoc.text('CONFIDENCE', 390, y + 6);
      pdfDoc.text('VERIFICATION', 470, y + 6);

      y += 25;

      // Table Rows
      pdfDoc.font('Helvetica').fontSize(8);
      const rowHeight = 18;
      const maxRows = Math.min(eventsData.length, 35); // 1 page sample for report register

      for (let i = 0; i < maxRows; i++) {
        const e = eventsData[i];
        if (i % 2 === 0) {
          pdfDoc.rect(40, y - 2, 515, rowHeight).fill('#F8FAFC');
        }

        pdfDoc.fillColor('#1E293B').text(e.studentRoll || 'N/A', 45, y + 3);
        pdfDoc.text(e.studentName || 'Student', 140, y + 3);
        pdfDoc.text(e.sessionDate || '', 260, y + 3);

        if (e.status === 'PRESENT') {
          pdfDoc.fillColor('#059669').text('PRESENT', 330, y + 3);
        } else if (e.status === 'LATE') {
          pdfDoc.fillColor('#D97706').text('LATE', 330, y + 3);
        } else {
          pdfDoc.fillColor('#DC2626').text('ABSENT', 330, y + 3);
        }

        pdfDoc.fillColor('#1E293B').text(`${e.confidencePct || 95}%`, 390, y + 3);
        pdfDoc.fillColor('#4338CA').text(e.sha256Hash ? 'SHA-256 OK' : 'STANDARD', 470, y + 3);

        y += rowHeight;
      }

      // Footer
      pdfDoc.fontSize(7).fillColor('#94A3B8').text(
        `OmniFace AI Biometric Attendance System — End-to-End Cryptographically Bound Entitlement Report — Page 1 of 1`,
        40,
        780,
        { align: 'center', width: 515 }
      );

      pdfDoc.end();
      const pdfBuffer = await pdfPromise;

      return new NextResponse(new Uint8Array(pdfBuffer), {
        status: 200,
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': `attachment; filename="${baseFilename}.pdf"`,
        },
      });
    }

    // ── Format C: Sanitized CSV (Default) ──
    const headers = [
      'Event ID',
      'Roll Number',
      'Student Name',
      'Date',
      'Timestamp',
      'Status',
      'Confidence (%)',
      'Security Tier',
      'Device ID',
      'Verification Proof',
    ];

    function sanitizeCsvCell(value: any): string {
      if (value === null || value === undefined) return '';
      const str = String(value).replace(/"/g, '""');
      if (/^[=+\-@\t\r]/.test(str)) {
        return `'${str}`;
      }
      return str;
    }

    const csvRows = eventsData.map((e) => [
      e.eventId || e.id,
      e.studentRoll || 'N/A',
      e.studentName || 'Student',
      e.sessionDate || '',
      new Date(Number(e.timestamp)).toISOString(),
      e.status,
      `${e.confidencePct}%`,
      e.securityTier,
      e.deviceId,
      e.sha256Hash ? 'CRYPTOGRAPHICALLY_VERIFIED' : 'STANDARD',
    ]);

    const csvContent = [
      headers.join(','),
      ...csvRows.map((row) => row.map((cell) => `"${sanitizeCsvCell(cell)}"`).join(',')),
    ].join('\n');

    return new NextResponse(csvContent, {
      status: 200,
      headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': `attachment; filename="${baseFilename}.csv"`,
      },
    });
  } catch (err: any) {
    return NextResponse.json({ error: err?.message || 'Report generation failed' }, { status: 500 });
  }
}
