'use client';

import React, { useState } from 'react';

interface ReportRow {
  roll: string;
  name: string;
  department: string;
  totalDays: number;
  presentDays: number;
  absentDays: number;
  lateMarks: number;
  ratePct: number;
  status: string;
}

const initialRows: ReportRow[] = [
  { roll: 'CS-2024-001', name: 'Aarav Sharma', department: 'Computer Science', totalDays: 22, presentDays: 21, absentDays: 1, lateMarks: 0, ratePct: 95.5, status: 'GOOD' },
  { roll: 'CS-2024-008', name: 'Ananya Reddy', department: 'Computer Science', totalDays: 22, presentDays: 22, absentDays: 0, lateMarks: 0, ratePct: 100.0, status: 'EXCELLENT' },
  { roll: 'CS-2024-015', name: 'Kabir Verma', department: 'Computer Science', totalDays: 22, presentDays: 19, absentDays: 3, lateMarks: 1, ratePct: 86.4, status: 'WARNING' },
  { roll: 'EC-2024-019', name: 'Priya Patel', department: 'Electronics & Comm.', totalDays: 22, presentDays: 21, absentDays: 1, lateMarks: 0, ratePct: 95.5, status: 'GOOD' },
  { roll: 'EC-2024-025', name: 'Devanshi Shah', department: 'Electronics & Comm.', totalDays: 22, presentDays: 20, absentDays: 2, lateMarks: 2, ratePct: 90.9, status: 'GOOD' },
  { roll: 'ME-2024-011', name: 'Rohan Deshmukh', department: 'Mechanical Eng.', totalDays: 22, presentDays: 20, absentDays: 2, lateMarks: 1, ratePct: 90.9, status: 'GOOD' },
  { roll: 'ME-2024-018', name: 'Aditya Kulkarni', department: 'Mechanical Eng.', totalDays: 22, presentDays: 18, absentDays: 4, lateMarks: 0, ratePct: 81.8, status: 'CRITICAL' },
  { roll: 'SF-2023-003', name: 'Dr. Vikram Joshi', department: 'Staff & Faculty', totalDays: 22, presentDays: 22, absentDays: 0, lateMarks: 0, ratePct: 100.0, status: 'EXCELLENT' },
  { roll: 'SF-2023-009', name: 'Prof. Sunita Rao', department: 'Staff & Faculty', totalDays: 22, presentDays: 22, absentDays: 0, lateMarks: 0, ratePct: 100.0, status: 'EXCELLENT' },
  { roll: 'CS-2024-042', name: 'Tanvi Iyer', department: 'Computer Science', totalDays: 22, presentDays: 21, absentDays: 1, lateMarks: 0, ratePct: 95.5, status: 'GOOD' },
];

export default function ReportsPage() {
  const [dept, setDept] = useState('ALL');
  const [selectedMonth, setSelectedMonth] = useState('2026-09');
  const [search, setSearch] = useState('');

  const filtered = initialRows.filter((r) => {
    const matchDept = dept === 'ALL' || r.department === dept;
    const matchQuery =
      r.name.toLowerCase().includes(search.toLowerCase()) ||
      r.roll.toLowerCase().includes(search.toLowerCase());
    return matchDept && matchQuery;
  });

  const avgAttendance = (filtered.reduce((acc, curr) => acc + curr.ratePct, 0) / (filtered.length || 1)).toFixed(1);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Attendance Ledger & Reports
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Monthly aggregation, punctuality records, and regulatory audit exports
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button
            onClick={() => window.print()}
            className="btn btn-secondary"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/></svg>
            <span>Print Report</span>
          </button>

          <a
            href={`/api/v1/reports/export?month=${selectedMonth}&department=${dept}`}
            className="btn btn-primary"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            <span>Download CSV</span>
          </a>
        </div>
      </div>

      {/* Summary KPI Strip */}
      <div className="grid-cols-4">
        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Working Days in Month</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>22 Days</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Cohort Average Attendance</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--primary)' }}>{avgAttendance}%</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Perfect Attendance (100%)</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--success)' }}>3 Members</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Defaulters (&lt;85%)</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--danger)' }}>1 Member</div>
        </div>
      </div>

      {/* Filter Controls */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', flexWrap: 'wrap' }}>
        <input
          type="month"
          aria-label="Filter report by billing month"
          value={selectedMonth}
          onChange={(e) => setSelectedMonth(e.target.value)}
          style={{
            background: 'var(--surface-raised)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            padding: '10px 14px',
            color: 'var(--text-main)',
            fontSize: '13px',
            outline: 'none',
          }}
        />

        <select
          aria-label="Filter report by department"
          value={dept}
          onChange={(e) => setDept(e.target.value)}
          style={{
            background: 'var(--surface-raised)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            padding: '10px 14px',
            color: 'var(--text-main)',
            fontSize: '13px',
            outline: 'none',
          }}
        >
          <option value="ALL">All Departments</option>
          <option value="Computer Science">Computer Science</option>
          <option value="Electronics & Comm.">Electronics & Comm.</option>
          <option value="Mechanical Eng.">Mechanical Eng.</option>
          <option value="Staff & Faculty">Staff & Faculty</option>
        </select>

        <input
          type="text"
          aria-label="Filter attendance by name or roll number"
          placeholder="Filter by name or roll number..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{
            flex: 1,
            minWidth: '220px',
            background: 'var(--surface-raised)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            padding: '10px 14px',
            color: 'var(--text-main)',
            fontSize: '13px',
            outline: 'none',
          }}
        />
      </div>

      {/* Aggregate Report Table */}
      <div className="table-surface">
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col">Roll Number</th>
              <th scope="col">Member Name</th>
              <th scope="col">Department</th>
              <th scope="col">Working Days</th>
              <th scope="col">Present</th>
              <th scope="col">Absent</th>
              <th scope="col">Late</th>
              <th scope="col">Attendance %</th>
              <th scope="col">Standing</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((r) => (
              <tr key={r.roll}>
                <td className="tnum" style={{ fontWeight: 600 }}>{r.roll}</td>
                <td style={{ fontWeight: 500 }}>{r.name}</td>
                <td style={{ color: 'var(--text-muted)' }}>{r.department}</td>
                <td className="tnum">{r.totalDays}</td>
                <td className="tnum" style={{ color: 'var(--success)', fontWeight: 600 }}>{r.presentDays}</td>
                <td className="tnum" style={{ color: r.absentDays > 2 ? 'var(--danger)' : 'var(--text-dim)' }}>{r.absentDays}</td>
                <td className="tnum" style={{ color: r.lateMarks > 0 ? 'var(--warning)' : 'var(--text-dim)' }}>{r.lateMarks}</td>
                <td className="tnum" style={{ fontWeight: 700, color: r.ratePct >= 90 ? 'var(--primary)' : r.ratePct >= 85 ? 'var(--warning)' : 'var(--danger)' }}>
                  {r.ratePct}%
                </td>
                <td>
                  <span className={`badge ${r.status === 'EXCELLENT' ? 'badge-success' : r.status === 'GOOD' ? 'badge-primary' : r.status === 'WARNING' ? 'badge-warning' : 'badge-warning'}`}>
                    {r.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
