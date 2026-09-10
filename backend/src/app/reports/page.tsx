'use client';

import React, { useState, useEffect, useCallback } from 'react';

interface ReportRow {
  roll: string;
  name: string;
  department: string;
  departmentId?: string;
  totalDays: number;
  presentDays: number;
  absentDays: number;
  lateMarks: number;
  ratePct: number;
  status: string;
}

interface DepartmentItem {
  id: string;
  name: string;
  code: string;
}

export default function ReportsPage() {
  const [rows, setRows] = useState<ReportRow[]>([]);
  const [departments, setDepartments] = useState<DepartmentItem[]>([]);
  const [dept, setDept] = useState('ALL');
  const [selectedMonth, setSelectedMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [search, setSearch] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [reportSummary, setReportSummary] = useState({
    workingDays: 0,
    cohortAverage: 0,
    perfectCount: 0,
    defaulterCount: 0,
    totalStudents: 0,
  });

  // Fetch departments list for the filter
  useEffect(() => {
    fetch('/api/v1/departments')
      .then((res) => res.json())
      .then((data) => {
        if (data.success && Array.isArray(data.departments)) {
          setDepartments(data.departments);
        }
      })
      .catch(() => {});
  }, []);

  const fetchReports = useCallback(async () => {
    setIsLoading(true);
    try {
      const url = new URL('/api/v1/reports', window.location.origin);
      if (selectedMonth) url.searchParams.set('month', selectedMonth);
      if (dept && dept !== 'ALL') url.searchParams.set('department', dept);

      const res = await fetch(url.toString());
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.rows)) {
          setRows(data.rows);
          setReportSummary({
            workingDays: data.workingDays ?? 0,
            cohortAverage: data.summary?.cohortAverage ?? 0,
            perfectCount: data.summary?.perfectCount ?? 0,
            defaulterCount: data.summary?.defaulterCount ?? 0,
            totalStudents: data.summary?.totalStudents ?? data.rows.length,
          });
        } else {
          setRows([]);
        }
      }
    } catch (err: any) {
      console.error('Failed to load reports:', err);
      setRows([]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedMonth, dept]);

  useEffect(() => {
    fetchReports();
  }, [fetchReports]);

  const filtered = rows.filter((r) => {
    const matchSearch =
      r.name.toLowerCase().includes(search.toLowerCase()) ||
      r.roll.toLowerCase().includes(search.toLowerCase());
    return matchSearch;
  });

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
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
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>
            {reportSummary.workingDays} Day{reportSummary.workingDays === 1 ? '' : 's'}
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Cohort Average Attendance</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--primary)' }}>
            {reportSummary.cohortAverage}%
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Perfect Attendance (100%)</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--success)' }}>
            {reportSummary.perfectCount} Member{reportSummary.perfectCount === 1 ? '' : 's'}
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Defaulters (&lt;85%)</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: reportSummary.defaulterCount > 0 ? 'var(--danger)' : 'var(--text-dim)' }}>
            {reportSummary.defaulterCount} Member{reportSummary.defaulterCount === 1 ? '' : 's'}
          </div>
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
          {departments.map((d) => (
            <option key={d.id} value={d.name}>
              {d.name} ({d.code})
            </option>
          ))}
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
        {isLoading ? (
          <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-muted)' }}>
            <div className="spin-icon" style={{ fontSize: '24px', marginBottom: '8px' }}>↻</div>
            <div>Aggregating monthly attendance ledger...</div>
          </div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: '48px 24px', textAlign: 'center' }}>
            <div style={{ fontSize: '32px', marginBottom: '12px' }}>📊</div>
            <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '6px' }}>
              No attendance records found for {selectedMonth}
            </h3>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)', maxWidth: '420px', margin: '0 auto' }}>
              When attendance events are recorded by Android kiosk devices, monthly aggregations and regulatory audit summaries will compute here automatically.
            </p>
          </div>
        ) : (
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
                    <span className={`badge ${r.status === 'EXCELLENT' ? 'badge-success' : r.status === 'GOOD' ? 'badge-primary' : r.status === 'WARNING' ? 'badge-warning' : 'badge-danger'}`}>
                      {r.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
