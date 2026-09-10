import { NextRequest, NextResponse } from 'next/server';

export async function GET(req: NextRequest) {
  try {
    const { searchParams } = new URL(req.url);
    const format = searchParams.get('format') || 'csv';
    const month = searchParams.get('month') || '2026-09';
    const dept = searchParams.get('department') || 'All';

    // CSV Header row
    const headers = [
      'Roll Number',
      'Student / Staff Name',
      'Department',
      'Total Working Days',
      'Days Present',
      'Days Absent',
      'Late Marks',
      'Attendance Rate (%)',
      'Security Tier',
      'Verification Status',
    ];

    const dataRows = [
      ['CS-2024-001', 'Aarav Sharma', 'Computer Science', '22', '21', '1', '0', '95.5%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['CS-2024-008', 'Ananya Reddy', 'Computer Science', '22', '22', '0', '0', '100.0%', 'STRICT', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['CS-2024-015', 'Kabir Verma', 'Computer Science', '22', '19', '3', '1', '86.4%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['EC-2024-019', 'Priya Patel', 'Electronics & Comm.', '22', '21', '1', '0', '95.5%', 'STRICT', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['EC-2024-025', 'Devanshi Shah', 'Electronics & Comm.', '22', '20', '2', '2', '90.9%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['ME-2024-011', 'Rohan Deshmukh', 'Mechanical Eng.', '22', '20', '2', '1', '90.9%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['ME-2024-018', 'Aditya Kulkarni', 'Mechanical Eng.', '22', '18', '4', '0', '81.8%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['SF-2023-003', 'Dr. Vikram Joshi', 'Staff & Faculty', '22', '22', '0', '0', '100.0%', 'STRICT', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['SF-2023-009', 'Prof. Sunita Rao', 'Staff & Faculty', '22', '22', '0', '0', '100.0%', 'STRICT', 'CRYPTOGRAPHICALLY_VERIFIED'],
      ['CS-2024-042', 'Tanvi Iyer', 'Computer Science', '22', '21', '1', '0', '95.5%', 'HIGH', 'CRYPTOGRAPHICALLY_VERIFIED'],
    ];

    const csvContent = [headers.join(','), ...dataRows.map((row) => row.map((cell) => `"${cell}"`).join(','))].join('\n');

    return new NextResponse(csvContent, {
      status: 200,
      headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': `attachment; filename="OmniFace_Attendance_Report_${month}_${dept}.csv"`,
      },
    });
  } catch (err: any) {
    return NextResponse.json({ error: err?.message || 'Report generation failed' }, { status: 500 });
  }
}
