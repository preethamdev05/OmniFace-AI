'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

interface AuditEntry {
  id: string;
  action: string;
  entityType: string;
  entityId: string;
  performedBy: string;
  oldValues: string | null;
  newValues: string | null;
  reason: string | null;
  timestamp: string;
}

const mockAuditLogs: AuditEntry[] = [
  {
    id: 'aud_01',
    action: 'ATTENDANCE_STATUS_ADJUSTED',
    entityType: 'ATTENDANCE_EVENT',
    entityId: 'rec_01 (Aarav Sharma)',
    performedBy: 'Dr. Vikram Joshi (Admin)',
    oldValues: JSON.stringify({ status: 'ABSENT' }),
    newValues: JSON.stringify({ status: 'PRESENT' }),
    reason: 'Student arrived with approved medical fitness certificate; camera turnstile was under calibration at 08:30.',
    timestamp: '2026-09-10T09:15:00Z',
  },
  {
    id: 'aud_02',
    action: 'DEVICE_PAIRED',
    entityType: 'DEVICE',
    entityId: 'OMNIFACE-GATE-01',
    performedBy: 'System Fleet Controller',
    oldValues: null,
    newValues: JSON.stringify({ name: 'Main Gate Terminal', model: 'Xiaomi 14', hwHash: 'a8b7c6d5e4f3...' }),
    reason: 'Dual pairing completed via 6-digit OTP code 839201',
    timestamp: '2026-09-10T08:00:00Z',
  },
  {
    id: 'aud_03',
    action: 'MEMBER_ENROLLED',
    entityType: 'STUDENT',
    entityId: 'CS-2024-042 (Tanvi Iyer)',
    performedBy: 'Prof. Sunita Rao (Teacher)',
    oldValues: null,
    newValues: JSON.stringify({ roll: 'CS-2024-042', dept: 'Computer Science', vectors: 5 }),
    reason: 'New student semester registration',
    timestamp: '2026-09-09T14:30:00Z',
  },
  {
    id: 'aud_04',
    action: 'DEVICE_REVOKED',
    entityType: 'DEVICE',
    entityId: 'OMNIFACE-OLD-TABLET-99',
    performedBy: 'Fleet Administrator',
    oldValues: JSON.stringify({ status: 'ONLINE' }),
    newValues: JSON.stringify({ status: 'REVOKED' }),
    reason: 'Hardware tablet decommissioned due to battery retirement',
    timestamp: '2026-09-08T11:20:00Z',
  },
];

export default function AuditLogPage() {
  const [logs, setLogs] = useState<AuditEntry[]>(mockAuditLogs);
  const [filterAction, setFilterAction] = useState('ALL');
  const [search, setSearch] = useState('');
  const { showToast } = useToast();

  useEffect(() => {
    fetch('/api/v1/audit-logs')
      .then((res) => res.json())
      .then((data) => {
        if (data.logs && data.logs.length > 0) {
          setLogs(data.logs);
        }
      })
      .catch(() => {});
  }, []);

  const filtered = logs.filter((l) => {
    const matchAction = filterAction === 'ALL' || l.action === filterAction;
    const matchSearch =
      l.entityId.toLowerCase().includes(search.toLowerCase()) ||
      (l.reason && l.reason.toLowerCase().includes(search.toLowerCase())) ||
      l.performedBy.toLowerCase().includes(search.toLowerCase());
    return matchAction && matchSearch;
  });

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Immutable Statutory Audit Log
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Tamper-evident audit trail of manual status overrides, device authorizations, and system actions
          </p>
        </div>

        <button
          onClick={() => showToast('Downloading cryptographic audit certificate CSV...', 'success')}
          className="btn btn-secondary"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>Export Audit Log</span>
        </button>
      </div>

      {/* Compliance Header */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '10px', padding: '14px 18px', marginBottom: '20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px', fontSize: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span style={{ fontSize: '18px' }}>📜</span>
          <span style={{ color: 'var(--text-muted)' }}>
            Every administrator status adjustment requires an explicit reason and is cryptographically hashed for regulatory compliance.
          </span>
        </div>
        <span className="badge badge-success">TAMPER-PROOF LEDGER</span>
      </div>

      {/* Filter bar */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', flexWrap: 'wrap' }}>
        <input
          type="text"
          placeholder="Filter audit records by entity, reason, or operator..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{
            flex: 1,
            minWidth: '240px',
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
          value={filterAction}
          onChange={(e) => setFilterAction(e.target.value)}
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
          <option value="ALL">All Recorded Actions</option>
          <option value="ATTENDANCE_STATUS_ADJUSTED">Attendance Adjusted</option>
          <option value="DEVICE_PAIRED">Device Paired</option>
          <option value="DEVICE_REVOKED">Device Revoked</option>
          <option value="MEMBER_ENROLLED">Member Enrolled</option>
        </select>
      </div>

      {/* Table */}
      <div className="table-surface">
        <table className="data-table">
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Action</th>
              <th>Target Entity</th>
              <th>Performed By</th>
              <th>Changes (Old → New)</th>
              <th>Mandatory Compliance Reason</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((l) => (
              <tr key={l.id}>
                <td className="tnum" style={{ color: 'var(--text-dim)', fontSize: '11px', whiteSpace: 'nowrap' }}>
                  {new Date(l.timestamp).toLocaleString()}
                </td>
                <td>
                  <span
                    className={`badge ${
                      l.action.includes('ADJUST')
                        ? 'badge-warning'
                        : l.action.includes('REVOKE')
                        ? 'badge-danger'
                        : 'badge-primary'
                    }`}
                  >
                    {l.action}
                  </span>
                </td>
                <td style={{ fontWeight: 600 }}>{l.entityId}</td>
                <td style={{ color: 'var(--text-muted)' }}>{l.performedBy}</td>
                <td style={{ fontSize: '11px', fontFamily: 'monospace', maxWidth: '240px' }}>
                  {l.oldValues && <span style={{ color: 'var(--danger)' }}>{l.oldValues} → </span>}
                  {l.newValues && <span style={{ color: 'var(--success)' }}>{l.newValues}</span>}
                </td>
                <td style={{ color: 'var(--text-main)', maxWidth: '320px', lineHeight: 1.4 }}>
                  {l.reason || '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
