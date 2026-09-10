'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { useToast } from '@/components/Toast';

interface AttendanceRecord {
  id: string;
  roll: string;
  name: string;
  department: string;
  time: string;
  confidence: number;
  tier: string;
  hash: string;
}

interface DepartmentStat {
  department: string;
  total: number;
  present: number;
  ratePct: number;
}

interface KioskStat {
  id: string;
  name: string;
  status: string;
  lastSync: string;
  appVersion: string;
}

export default function DashboardOverview() {
  const [recentRecords, setRecentRecords] = useState<AttendanceRecord[]>([]);
  const [departments, setDepartments] = useState<DepartmentStat[]>([]);
  const [kiosks, setKiosks] = useState<KioskStat[]>([]);
  const [filterTier, setFilterTier] = useState<'ALL' | 'HIGH' | 'STRICT'>('ALL');
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [liveMetrics, setLiveMetrics] = useState({
    totalEnrolled: 0,
    presentToday: 0,
    attendanceRate: 0,
    activeKiosks: 0,
  });
  const { showToast } = useToast();

  const fetchLiveSummary = async () => {
    setIsRefreshing(true);
    try {
      const res = await fetch('/api/v1/analytics/summary');
      if (res.ok) {
        const data = await res.json();
        if (data.summary) {
          setLiveMetrics({
            totalEnrolled: data.summary.totalEnrolled ?? 0,
            presentToday: data.summary.presentToday ?? 0,
            attendanceRate: data.summary.attendanceRatePct ?? 0,
            activeKiosks: data.summary.activeKiosks ?? 0,
          });
        }
        if (Array.isArray(data.departmentDistribution)) {
          setDepartments(data.departmentDistribution);
        } else {
          setDepartments([]);
        }
        if (Array.isArray(data.kioskStatus)) {
          setKiosks(data.kioskStatus);
        } else {
          setKiosks([]);
        }
        if (Array.isArray(data.recentActivity)) {
          const mapped: AttendanceRecord[] = data.recentActivity.map((r: any) => ({
            id: r.id,
            roll: r.studentRoll || r.roll,
            name: r.studentName || r.name,
            department: r.department || 'General',
            time: r.time,
            confidence: r.confidencePct || 99,
            tier: r.securityTier || 'HIGH',
            hash: r.sha256Hash || r.hash || '',
          }));
          setRecentRecords(mapped);
        } else {
          setRecentRecords([]);
        }
      }
    } catch (err: any) {
      console.error('Failed to fetch live summary:', err);
    } finally {
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    fetchLiveSummary();
    const interval = setInterval(fetchLiveSummary, 15000);
    return () => clearInterval(interval);
  }, []);

  const copyHashProof = (hash: string) => {
    if (!hash) return;
    try {
      navigator.clipboard.writeText(hash);
      showToast('Aegis SHA-256 tamper-evident proof copied!', 'success');
    } catch {
      showToast('Aegis Hash: ' + hash.substring(0, 16) + '...', 'info');
    }
  };

  const filteredRecords = recentRecords.filter((r) => {
    if (filterTier === 'ALL') return true;
    return r.tier === filterTier;
  });

  return (
    <div>
      {/* Top Banner / Headline */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Live Attendance Dashboard
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Multi-kiosk real-time biometric feed • Auto-refreshing every 15s
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button
            onClick={fetchLiveSummary}
            disabled={isRefreshing}
            className="btn btn-secondary"
            title="Refresh live metrics from edge fleet"
          >
            <span className={isRefreshing ? 'spin-icon' : ''}>
              ↻
            </span>
            <span>{isRefreshing ? 'Syncing...' : 'Refresh Feed'}</span>
          </button>

          <a
            href="/api/v1/reports/export?month=2026-09&department=All"
            onClick={() => showToast('Downloading verified attendance report CSV...', 'info')}
            className="btn btn-secondary"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            <span>Export CSV</span>
          </a>

          <Link href="/students" className="btn btn-primary">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>Add Member</span>
          </Link>
        </div>
      </div>

      {/* KPI Metrics Strip */}
      <div className="grid-cols-4">
        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '8px' }}>Total Enrolled Members</div>
          <div className="tnum" style={{ fontSize: '30px', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '-0.03em' }}>
            {liveMetrics.totalEnrolled}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '4px' }}>
            Across {departments.length} department{departments.length === 1 ? '' : 's'}
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '8px' }}>Present Today</div>
          <div className="tnum" style={{ fontSize: '30px', fontWeight: 700, color: 'var(--success)', letterSpacing: '-0.03em' }}>
            {liveMetrics.presentToday}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '4px' }}>
            {Math.max(0, liveMetrics.totalEnrolled - liveMetrics.presentToday)} pending / absent
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '8px' }}>Attendance Rate</div>
          <div className="tnum" style={{ fontSize: '30px', fontWeight: 700, color: 'var(--primary)', letterSpacing: '-0.03em' }}>
            {liveMetrics.attendanceRate}%
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '4px' }}>
            {liveMetrics.totalEnrolled > 0 ? 'Evaluated against roster' : 'Awaiting roster setup'}
          </div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 500, marginBottom: '8px' }}>Active Fleet Kiosks</div>
          <div className="tnum" style={{ fontSize: '30px', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '-0.03em' }}>
            {liveMetrics.activeKiosks} / {kiosks.length}
          </div>
          <div style={{ fontSize: '12px', color: liveMetrics.activeKiosks > 0 ? 'var(--success)' : 'var(--text-dim)', marginTop: '4px' }}>
            {liveMetrics.activeKiosks > 0 ? `${liveMetrics.activeKiosks} connected online` : 'No active kiosks'}
          </div>
        </div>
      </div>

      {/* 2-Column Split: Department Breakdown & Live Feed */}
      <div className="grid-cols-2">
        {/* Department Attendance Performance */}
        <div className="stat-box">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
            <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Department Attendance</h2>
            <Link href="/departments" style={{ fontSize: '12px', color: 'var(--primary)', textDecoration: 'none' }}>Manage →</Link>
          </div>

          {departments.length === 0 ? (
            <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
              No departments configured yet. <Link href="/departments" style={{ color: 'var(--primary)' }}>Add Department</Link>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {departments.map((dept) => (
                <div key={dept.department}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px', fontSize: '13px' }}>
                    <span style={{ fontWeight: 500, color: 'var(--text-main)' }}>{dept.department}</span>
                    <span className="tnum" style={{ color: 'var(--text-muted)' }}>
                      {dept.present} / {dept.total} ({dept.ratePct}%)
                    </span>
                  </div>
                  <div style={{ width: '100%', height: '8px', background: 'var(--surface)', borderRadius: '4px', overflow: 'hidden' }}>
                    <div
                      style={{
                        width: `${dept.ratePct}%`,
                        height: '100%',
                        background: dept.ratePct > 90 ? 'var(--primary)' : dept.ratePct > 75 ? 'var(--warning)' : 'var(--danger)',
                        borderRadius: '4px',
                        transition: 'width 400ms var(--ease-out)',
                      }}
                    ></div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Kiosk Fleet Terminals Status */}
        <div className="stat-box">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
            <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Kiosk Fleet Nodes</h2>
            <Link href="/devices" style={{ fontSize: '12px', color: 'var(--primary)', textDecoration: 'none' }}>Manage Terminals →</Link>
          </div>

          {kiosks.length === 0 ? (
            <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
              No kiosks paired with this organization. <Link href="/devices" style={{ color: 'var(--primary)' }}>Pair Kiosk Device</Link>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {kiosks.map((k) => (
                <div key={k.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px', background: 'var(--surface)', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-main)', fontSize: '13px' }}>{k.name} ({k.id})</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-dim)' }}>App {k.appVersion} • Last sync: {k.lastSync}</div>
                  </div>
                  <span className={`badge ${k.status === 'ONLINE' ? 'badge-success' : 'badge-warning'}`}>
                    {k.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Real-time Attendance Ledger Stream */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px', flexWrap: 'wrap', gap: '10px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Live Attendance Log</h2>
            <div style={{ display: 'flex', gap: '4px' }}>
              {(['ALL', 'HIGH', 'STRICT'] as const).map((t) => (
                <button
                  key={t}
                  onClick={() => setFilterTier(t)}
                  style={{
                    background: filterTier === t ? 'var(--primary-glow)' : 'transparent',
                    border: `1px solid ${filterTier === t ? 'var(--primary)' : 'var(--border)'}`,
                    color: filterTier === t ? 'var(--primary)' : 'var(--text-muted)',
                    borderRadius: '6px',
                    padding: '3px 8px',
                    fontSize: '11px',
                    fontWeight: 600,
                    cursor: 'pointer',
                  }}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          <Link href="/attendance" style={{ fontSize: '13px', color: 'var(--primary)', textDecoration: 'none' }}>
            View Full Ledger →
          </Link>
        </div>

        <div className="table-surface">
          {filteredRecords.length === 0 ? (
            <div style={{ padding: '36px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
              No recent attendance records logged yet today.
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Roll Number</th>
                  <th>Member Name</th>
                  <th>Department</th>
                  <th>Clock-In Time</th>
                  <th>Biometric Confidence</th>
                  <th>Security Tier</th>
                  <th>Status</th>
                  <th>Cryptographic Proof (Click to Copy)</th>
                </tr>
              </thead>
              <tbody>
                {filteredRecords.map((r) => (
                  <tr key={r.id}>
                    <td className="tnum" style={{ fontWeight: 600 }}>{r.roll}</td>
                    <td>{r.name}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{r.department}</td>
                    <td className="tnum" style={{ color: 'var(--text-muted)' }}>{r.time}</td>
                    <td className="tnum">
                      <span style={{ color: 'var(--success)', fontWeight: 600 }}>{r.confidence}%</span>
                    </td>
                    <td>
                      <span className="badge badge-primary">{r.tier}</span>
                    </td>
                    <td>
                      <span className="badge badge-success">PRESENT</span>
                    </td>
                    <td>
                      {r.hash ? (
                        <button
                          onClick={() => copyHashProof(r.hash)}
                          style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}
                          title="Click to copy 64-char Aegis SHA-256 Proof"
                        >
                          <span className="hash-pill" style={{ cursor: 'pointer' }}>
                            {r.hash.substring(0, 16)}...{r.hash.substring(r.hash.length - 4)}
                          </span>
                        </button>
                      ) : (
                        <span style={{ color: 'var(--text-dim)', fontSize: '12px' }}>—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
