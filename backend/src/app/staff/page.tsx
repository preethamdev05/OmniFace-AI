'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

type StaffRole = 'OWNER' | 'ADMIN' | 'TEACHER' | 'VIEWER';

interface StaffMember {
  membershipId: string;
  userId: string;
  email: string;
  fullName: string;
  avatarUrl?: string | null;
  role: StaffRole;
  status: 'ACTIVE' | 'INVITED' | 'SUSPENDED';
  joinedAt: string;
}

export default function StaffPage() {
  const [staffList, setStaffList] = useState<StaffMember[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [role, setRole] = useState<StaffRole>('TEACHER');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { showToast } = useToast();

  const fetchStaff = async () => {
    try {
      setIsLoading(true);
      const res = await fetch('/api/v1/staff');
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.staff)) {
          setStaffList(
            data.staff.map((m: any) => ({
              membershipId: m.membershipId || m.id,
              userId: m.userId || m.id,
              email: m.email,
              fullName: m.fullName || m.email.split('@')[0],
              avatarUrl: m.avatarUrl,
              role: (m.role as StaffRole) || 'TEACHER',
              status: (m.status as any) || 'ACTIVE',
              joinedAt: m.joinedAt ? new Date(m.joinedAt).toLocaleDateString() : 'Recent',
            }))
          );
        }
      }
    } catch (err: any) {
      console.warn('Could not fetch staff:', err?.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchStaff();
  }, []);

  const handleInviteStaff = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !fullName.trim()) {
      showToast('Email and Full Name are required', 'error');
      return;
    }

    try {
      setIsSubmitting(true);
      const res = await fetch('/api/v1/staff', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: email.trim(),
          fullName: fullName.trim(),
          role,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Staff member ${email} assigned role ${role}`, 'success');
        setShowInviteModal(false);
        setEmail('');
        setFullName('');
        fetchStaff();
      } else {
        showToast(data.error || 'Failed to invite staff member', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error inviting staff member', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRoleChange = async (member: StaffMember, newRole: StaffRole) => {
    try {
      const res = await fetch('/api/v1/staff', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: member.email,
          fullName: member.fullName,
          role: newRole,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Updated ${member.fullName} to ${newRole}`, 'success');
        setStaffList((prev) =>
          prev.map((m) => (m.userId === member.userId ? { ...m, role: newRole } : m))
        );
      } else {
        showToast(data.error || 'Failed to update role', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error updating role', 'error');
    }
  };

  const handleRevokeStaff = async (member: StaffMember) => {
    if (!confirm(`Revoke access for ${member.fullName} (${member.email})?`)) return;

    try {
      const res = await fetch(
        `/api/v1/staff?membershipId=${encodeURIComponent(member.membershipId)}&userId=${encodeURIComponent(member.userId)}`,
        { method: 'DELETE' }
      );
      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Access revoked for ${member.fullName}`, 'success');
        setStaffList((prev) => prev.filter((m) => m.membershipId !== member.membershipId));
      } else {
        showToast(data.error || 'Failed to revoke staff access', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error revoking access', 'error');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Staff & Role Permissions
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Firebase identity federation and Role-Based Access Control (OWNER, ADMIN, TEACHER, VIEWER)
          </p>
        </div>

        <button onClick={() => setShowInviteModal(true)} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>Invite Staff / Admin</span>
        </button>
      </div>

      {/* RBAC Overview Matrix Card */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px', marginBottom: '24px' }}>
        <h2 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '12px' }}>
          Role-Based Access Control Matrix
        </h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '14px', fontSize: '12px' }}>
          <div style={{ background: 'var(--surface)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
            <div style={{ fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>OWNER</div>
            <div style={{ color: 'var(--text-muted)' }}>Full system control, billing, subscriptions, org deletion, data purge.</div>
          </div>
          <div style={{ background: 'var(--surface)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
            <div style={{ fontWeight: 700, color: 'var(--success)', marginBottom: '4px' }}>ADMIN</div>
            <div style={{ color: 'var(--text-muted)' }}>Staff management, devices, student enrollments, attendance status adjustments.</div>
          </div>
          <div style={{ background: 'var(--surface)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
            <div style={{ fontWeight: 700, color: 'var(--warning)', marginBottom: '4px' }}>TEACHER</div>
            <div style={{ color: 'var(--text-muted)' }}>Class attendance view, student directory, class timetable management.</div>
          </div>
          <div style={{ background: 'var(--surface)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border-subtle)' }}>
            <div style={{ fontWeight: 700, color: 'var(--text-dim)', marginBottom: '4px' }}>VIEWER</div>
            <div style={{ color: 'var(--text-muted)' }}>Read-only ledger inspections, report PDF/CSV downloads, audit review.</div>
          </div>
        </div>
      </div>

      {/* Staff Table */}
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
          Loading staff members...
        </div>
      ) : staffList.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px' }}>
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>No Staff Members Found</h3>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', maxWidth: '380px', margin: '0 auto 20px' }}>
            Invite teachers, operators, and administrators to grant them role-based access.
          </p>
          <button onClick={() => setShowInviteModal(true)} className="btn btn-primary">
            <span>Invite First Staff Member</span>
          </button>
        </div>
      ) : (
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>Full Name</th>
                <th>Email Address</th>
                <th>Assigned Role</th>
                <th>Status</th>
                <th>Joined Date</th>
                <th>Change Role</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {staffList.map((m) => (
                <tr key={m.membershipId || m.userId}>
                  <td style={{ fontWeight: 600 }}>{m.fullName}</td>
                  <td style={{ color: 'var(--text-muted)' }}>{m.email}</td>
                  <td>
                    <span
                      className={`badge ${
                        m.role === 'OWNER'
                          ? 'badge-primary'
                          : m.role === 'ADMIN'
                          ? 'badge-success'
                          : m.role === 'TEACHER'
                          ? 'badge-warning'
                          : 'badge-primary'
                      }`}
                    >
                      {m.role}
                    </span>
                  </td>
                  <td>
                    <span className={`badge ${m.status === 'ACTIVE' ? 'badge-success' : 'badge-warning'}`}>
                      {m.status}
                    </span>
                  </td>
                  <td className="tnum" style={{ color: 'var(--text-dim)' }}>{m.joinedAt}</td>
                  <td>
                    {m.role !== 'OWNER' ? (
                      <select
                        value={m.role}
                        onChange={(e) => handleRoleChange(m, e.target.value as StaffRole)}
                        style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', padding: '4px 8px', fontSize: '11px', color: 'var(--text-main)' }}
                      >
                        <option value="ADMIN">ADMIN</option>
                        <option value="TEACHER">TEACHER</option>
                        <option value="VIEWER">VIEWER</option>
                      </select>
                    ) : (
                      <span style={{ fontSize: '11px', color: 'var(--text-dim)' }}>Primary Owner</span>
                    )}
                  </td>
                  <td>
                    {m.role !== 'OWNER' && (
                      <button
                        onClick={() => handleRevokeStaff(m)}
                        style={{ background: 'none', border: 'none', color: 'var(--danger, #ef4444)', cursor: 'pointer', fontSize: '12px' }}
                      >
                        Revoke
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Invite Staff Modal */}
      {showInviteModal && (
        <div className="modal-overlay" onClick={() => setShowInviteModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '440px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '16px' }}>Invite Staff Member</h2>
            <form onSubmit={handleInviteStaff} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Full Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Dr. Priya Sundaram"
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Email Address</label>
                <input
                  type="email"
                  required
                  placeholder="e.g. priya@nit.edu"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Role</label>
                <select
                  value={role}
                  onChange={(e) => setRole(e.target.value as StaffRole)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                >
                  <option value="ADMIN">ADMIN (Full management)</option>
                  <option value="TEACHER">TEACHER (Class view & register)</option>
                  <option value="VIEWER">VIEWER (Read-only reports)</option>
                </select>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setShowInviteModal(false)} className="btn btn-secondary">Cancel</button>
                <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                  {isSubmitting ? 'Sending...' : 'Send Invitation'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
