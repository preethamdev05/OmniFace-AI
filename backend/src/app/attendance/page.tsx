'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '@/components/Toast';

interface AttendanceRow {
  id: string;
  recordId: string;
  studentRoll: string;
  studentName: string;
  department: string;
  sessionDate: string;
  clockInTime: string;
  confidencePct: number;
  securityTier: string;
  status: 'PRESENT' | 'LATE' | 'ABSENT' | 'EXCUSED';
  sha256Hash: string;
  serverEvaluated: boolean;
}

export default function AttendancePage() {
  const [records, setRecords] = useState<AttendanceRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [selectedDate, setSelectedDate] = useState(() => new Date().toISOString().split('T')[0]);
  const [filterStatus, setFilterStatus] = useState<string>('ALL');
  const [selectedRecordForAdjustment, setSelectedRecordForAdjustment] = useState<AttendanceRow | null>(null);
  const [newStatus, setNewStatus] = useState<'PRESENT' | 'LATE' | 'ABSENT' | 'EXCUSED'>('PRESENT');
  const [adjustmentReason, setAdjustmentReason] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { showToast } = useToast();

  const fetchAttendance = useCallback(async () => {
    setIsLoading(true);
    try {
      const url = new URL('/api/v1/attendance', window.location.origin);
      if (selectedDate) url.searchParams.set('date', selectedDate);
      if (filterStatus && filterStatus !== 'ALL') url.searchParams.set('status', filterStatus);

      const res = await fetch(url.toString());
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.records)) {
          setRecords(data.records);
        } else {
          setRecords([]);
        }
      } else {
        setRecords([]);
      }
    } catch (err: any) {
      console.error('Failed to load attendance:', err);
      showToast('Could not load attendance records from server', 'error');
      setRecords([]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedDate, filterStatus, showToast]);

  useEffect(() => {
    fetchAttendance();
  }, [fetchAttendance]);

  const handleAdjustSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedRecordForAdjustment) return;

    if (!adjustmentReason || adjustmentReason.trim().length < 3) {
      showToast('Mandatory reason is required for audit compliance (min 3 chars).', 'error');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await fetch('/api/v1/attendance/adjust', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          attendanceEventId: selectedRecordForAdjustment.id,
          recordId: selectedRecordForAdjustment.recordId,
          newStatus,
          reason: adjustmentReason.trim(),
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        setRecords((prev) =>
          prev.map((r) =>
            r.id === selectedRecordForAdjustment.id ? { ...r, status: newStatus } : r
          )
        );
        showToast(`Attendance adjusted to ${newStatus} with audit log recorded`, 'success');
        setSelectedRecordForAdjustment(null);
        setAdjustmentReason('');
      } else {
        showToast(data.error || 'Failed to adjust attendance', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Network error while adjusting attendance', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Attendance Control Center
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Live attendance registers, server schedule evaluation, and audited manual status adjustments
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={fetchAttendance} disabled={isLoading} className="btn btn-secondary">
            <span>{isLoading ? '↻ Refreshing...' : '↻ Refresh'}</span>
          </button>
          <a
            href={`/api/v1/reports/export?month=${selectedDate.substring(0, 7)}&department=All`}
            className="btn btn-primary"
          >
            <span>Export CSV</span>
          </a>
        </div>
      </div>

      {/* Filter Bar */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', flexWrap: 'wrap', alignItems: 'center' }}>
        <input
          type="date"
          value={selectedDate}
          onChange={(e) => setSelectedDate(e.target.value)}
          style={{
            background: 'var(--surface-raised)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            padding: '8px 12px',
            color: 'var(--text-main)',
            fontSize: '13px',
            outline: 'none',
          }}
        />

        <div style={{ display: 'flex', gap: '6px' }}>
          {['ALL', 'PRESENT', 'LATE', 'ABSENT', 'EXCUSED'].map((st) => (
            <button
              key={st}
              onClick={() => setFilterStatus(st)}
              style={{
                background: filterStatus === st ? 'var(--primary-glow)' : 'transparent',
                border: `1px solid ${filterStatus === st ? 'var(--primary)' : 'var(--border)'}`,
                color: filterStatus === st ? 'var(--primary)' : 'var(--text-muted)',
                borderRadius: '6px',
                padding: '6px 12px',
                fontSize: '12px',
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              {st}
            </button>
          ))}
        </div>
      </div>

      {/* Attendance Ledger Table */}
      <div className="table-surface">
        {isLoading ? (
          <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-muted)' }}>
            <div className="spin-icon" style={{ fontSize: '24px', marginBottom: '8px' }}>↻</div>
            <div>Loading live attendance records...</div>
          </div>
        ) : records.length === 0 ? (
          <div style={{ padding: '48px 24px', textAlign: 'center' }}>
            <div style={{ fontSize: '32px', marginBottom: '12px' }}>📋</div>
            <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '6px' }}>
              No attendance events recorded for this selection
            </h3>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)', maxWidth: '420px', margin: '0 auto' }}>
              Attendance marked offline or online by paired Android kiosks will synchronize and appear here in real-time.
            </p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Roll Number</th>
                <th>Member Name</th>
                <th>Department</th>
                <th>Clock-In</th>
                <th>Evaluation Mode</th>
                <th>Biometric Match</th>
                <th>Status</th>
                <th>Cryptographic Proof</th>
                <th>Audit Action</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r) => (
                <tr key={r.id}>
                  <td className="tnum" style={{ fontWeight: 600 }}>{r.studentRoll}</td>
                  <td style={{ fontWeight: 500 }}>{r.studentName}</td>
                  <td style={{ color: 'var(--text-muted)' }}>{r.department}</td>
                  <td className="tnum" style={{ color: 'var(--text-muted)' }}>{r.clockInTime}</td>
                  <td>
                    <span className="badge badge-primary">SERVER EVALUATED</span>
                  </td>
                  <td className="tnum">
                    <span style={{ color: r.confidencePct > 0 ? 'var(--success)' : 'var(--text-dim)', fontWeight: 600 }}>
                      {r.confidencePct > 0 ? `${r.confidencePct}%` : 'N/A'}
                    </span>
                  </td>
                  <td>
                    <span
                      className={`badge ${
                        r.status === 'PRESENT'
                          ? 'badge-success'
                          : r.status === 'LATE'
                          ? 'badge-warning'
                          : r.status === 'ABSENT'
                          ? 'badge-warning'
                          : 'badge-primary'
                      }`}
                    >
                      {r.status}
                    </span>
                  </td>
                  <td>
                    {r.sha256Hash ? (
                      <span className="hash-pill" title={r.sha256Hash}>
                        {r.sha256Hash.substring(0, 10)}...{r.sha256Hash.substring(r.sha256Hash.length - 4)}
                      </span>
                    ) : (
                      <span style={{ color: 'var(--text-dim)', fontSize: '12px' }}>—</span>
                    )}
                  </td>
                  <td>
                    <button
                      onClick={() => {
                        setSelectedRecordForAdjustment(r);
                        setNewStatus(r.status === 'PRESENT' ? 'ABSENT' : 'PRESENT');
                        setAdjustmentReason('');
                      }}
                      className="btn btn-secondary"
                      style={{ padding: '4px 10px', fontSize: '11px' }}
                    >
                      Adjust Status
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Manual Status Adjustment Modal with Mandatory Audit Reason */}
      {selectedRecordForAdjustment && (
        <div className="modal-overlay" onClick={() => setSelectedRecordForAdjustment(null)}>
          <div className="modal-dialog" style={{ maxWidth: '480px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
              Adjust Attendance Status
            </h2>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '16px' }}>
              Adjusting record for <strong style={{ color: 'var(--text-main)' }}>{selectedRecordForAdjustment.studentName}</strong> ({selectedRecordForAdjustment.studentRoll})
            </p>

            <form onSubmit={handleAdjustSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface-raised)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Current Status:</span>
                <span className="badge badge-warning">{selectedRecordForAdjustment.status}</span>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                  New Evaluated Status
                </label>
                <select
                  value={newStatus}
                  onChange={(e) => setNewStatus(e.target.value as any)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                >
                  <option value="PRESENT">PRESENT</option>
                  <option value="LATE">LATE</option>
                  <option value="ABSENT">ABSENT</option>
                  <option value="EXCUSED">EXCUSED (Approved Leave)</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                  Mandatory Audit Reason <span style={{ color: 'var(--danger)' }}>*</span>
                </label>
                <textarea
                  required
                  rows={3}
                  placeholder="e.g. Student submitted written medical excuse slip approved by HOD; turnstile camera was under maintenance."
                  value={adjustmentReason}
                  onChange={(e) => setAdjustmentReason(e.target.value)}
                  style={{ width: '100%', padding: '10px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)', fontSize: '13px', resize: 'vertical' }}
                />
                <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>
                  All status adjustments are permanently recorded in the immutable audit log with administrator identity and timestamp.
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setSelectedRecordForAdjustment(null)} className="btn btn-secondary">
                  Cancel
                </button>
                <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                  {isSubmitting ? 'Recording Audit...' : 'Confirm Adjustment'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
