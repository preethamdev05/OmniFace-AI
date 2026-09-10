'use client';

import React, { useState } from 'react';
import { useToast } from '@/components/Toast';

export default function SettingsPage() {
  const [orgName, setOrgName] = useState('National Institute of Technology');
  const [orgType, setOrgType] = useState('SCHOOL');
  const [contactEmail, setContactEmail] = useState('preethamdev05@gmail.com');
  const [contactPhone, setContactPhone] = useState('+91 98765 43210');
  const [defaultStartTime, setDefaultStartTime] = useState('09:00');
  const [graceMinutes, setGraceMinutes] = useState(15);
  const [autoEvaluateStatus, setAutoEvaluateStatus] = useState(true);
  const [dpdpCompliance, setDpdpCompliance] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const { showToast } = useToast();

  const handleSaveSettings = (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);
    setTimeout(() => {
      setIsSaving(false);
      showToast('Organization settings & attendance policy updated successfully', 'success');
    }, 500);
  };

  return (
    <div>
      <div style={{ marginBottom: '28px' }}>
        <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
          Organization & Policy Settings
        </h1>
        <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
          Manage campus identity, biometric attendance thresholds, and DPDP Act 2023 compliance policies
        </p>
      </div>

      <form onSubmit={handleSaveSettings} style={{ display: 'flex', flexDirection: 'column', gap: '24px', maxWidth: '800px' }}>
        {/* Section 1: Institution Profile */}
        <div className="stat-box">
          <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>
            Institution Profile
          </h2>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                Organization / Campus Name
              </label>
              <input
                type="text"
                value={orgName}
                onChange={(e) => setOrgName(e.target.value)}
                style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Institution Type
                </label>
                <select
                  value={orgType}
                  onChange={(e) => setOrgType(e.target.value)}
                  style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
                >
                  <option value="SCHOOL">School / K-12</option>
                  <option value="COLLEGE">University / College</option>
                  <option value="COACHING">Coaching Center</option>
                  <option value="CORPORATE">Corporate Office</option>
                  <option value="GYM_EVENT">Gym & Event Facility</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Contact Phone
                </label>
                <input
                  type="text"
                  value={contactPhone}
                  onChange={(e) => setContactPhone(e.target.value)}
                  style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
                />
              </div>
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                Administrative Contact Email
              </label>
              <input
                type="email"
                value={contactEmail}
                onChange={(e) => setContactEmail(e.target.value)}
                style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
              />
            </div>
          </div>
        </div>

        {/* Section 2: Attendance Server Policy & Grace Period */}
        <div className="stat-box">
          <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>
            Attendance & Schedule Threshold Policy
          </h2>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Default Schedule Start Time
                </label>
                <input
                  type="time"
                  value={defaultStartTime}
                  onChange={(e) => setDefaultStartTime(e.target.value)}
                  style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Late Grace Window (Minutes)
                </label>
                <input
                  type="number"
                  min="0"
                  max="60"
                  value={graceMinutes}
                  onChange={(e) => setGraceMinutes(Number(e.target.value))}
                  style={{ width: '100%', padding: '10px 14px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-main)', fontSize: '13px' }}
                />
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 14px', background: 'var(--surface)', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
              <div>
                <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text-main)' }}>Server-Evaluated Punctuality</div>
                <div style={{ fontSize: '11px', color: 'var(--text-dim)' }}>
                  Device records immutable timestamp; server evaluates PRESENT vs LATE based on schedule threshold.
                </div>
              </div>
              <input
                type="checkbox"
                checked={autoEvaluateStatus}
                onChange={(e) => setAutoEvaluateStatus(e.target.checked)}
                style={{ width: '18px', height: '18px', accentColor: 'var(--primary)', cursor: 'pointer' }}
              />
            </div>
          </div>
        </div>

        {/* Section 3: Biometric Data Governance & DPDP Act 2023 */}
        <div className="stat-box">
          <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>
            Biometric Data Governance & DPDP Act 2023
          </h2>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '12px', color: 'var(--text-muted)' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px', padding: '10px', background: 'var(--surface)', borderRadius: '8px' }}>
              <span style={{ color: 'var(--success)', fontWeight: 700 }}>✓</span>
              <div>
                <strong style={{ color: 'var(--text-main)' }}>Zero Raw Photos Uploaded:</strong> All biometric processing is executed on-device. The server and database only receive 512-dimensional float mathematical hashes.
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px', padding: '10px', background: 'var(--surface)', borderRadius: '8px' }}>
              <span style={{ color: 'var(--success)', fontWeight: 700 }}>✓</span>
              <div>
                <strong style={{ color: 'var(--text-main)' }}>14-Day Grace Period & Archive Mode:</strong> Historical attendance is preserved permanently. If subscription lapses, system transitions to read-only archive mode rather than deleting student data.
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px', padding: '10px', background: 'var(--surface)', borderRadius: '8px' }}>
              <span style={{ color: 'var(--success)', fontWeight: 700 }}>✓</span>
              <div>
                <strong style={{ color: 'var(--text-main)' }}>Audited Modifications:</strong> Any manual changes to attendance records require a mandatory justification and are logged with user ID, timestamp, and previous status.
              </div>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
          <button type="submit" disabled={isSaving} className="btn btn-primary">
            {isSaving ? 'Saving Changes...' : 'Save Organization Settings'}
          </button>
        </div>
      </form>
    </div>
  );
}
