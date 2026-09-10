'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { useToast } from '@/components/Toast';
import { getOrgTerminology, getRoleBadgeMeta, EntityRole } from '@/lib/terminology';

interface StudentItem {
  id: string;
  studentRoll: string;
  fullName: string;
  role: string;
  department: string;
  semester: string;
  vectorCount: number;
  qualityScore: number;
  status: 'ENROLLED' | 'PENDING' | 'INACTIVE';
  enrolledAt: string;
}

export default function StudentsPage() {
  const [students, setStudents] = useState<StudentItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [orgType, setOrgType] = useState<string>('SCHOOL');
  const [selectedRoleCategory, setSelectedRoleCategory] = useState<string>('ALL');
  const [department, setDepartment] = useState('ALL');
  const [search, setSearch] = useState('');
  const [showAddModal, setShowAddModal] = useState(false);
  
  // New enrollment state
  const [newRoll, setNewRoll] = useState('');
  const [newName, setNewName] = useState('');
  const [newRole, setNewRole] = useState<EntityRole>('STUDENT');
  const [newDept, setNewDept] = useState('Computer Science');
  const [newSemester, setNewSemester] = useState('IV');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { showToast } = useToast();

  const terminology = getOrgTerminology(orgType);

  const fetchOrgSettings = async () => {
    try {
      const res = await fetch('/api/v1/settings');
      if (res.ok) {
        const data = await res.json();
        if (data.organization?.type) {
          setOrgType(data.organization.type);
          const t = getOrgTerminology(data.organization.type);
          setNewRole(t.defaultRole);
        }
      }
    } catch {
      // Graceful fallback to default SCHOOL terminology
    }
  };

  const fetchStudents = async () => {
    try {
      setIsLoading(true);
      const res = await fetch('/api/v1/users');
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.members)) {
          setStudents(data.members);
        } else {
          setStudents([]);
        }
      }
    } catch (err: any) {
      console.warn('Could not fetch members:', err?.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchOrgSettings();
    fetchStudents();
  }, []);

  // Filter based on search, department, and role category
  const filtered = students.filter((s) => {
    const matchDept = department === 'ALL' || s.department === department;
    const matchSearch =
      (s.fullName || '').toLowerCase().includes(search.toLowerCase()) ||
      (s.studentRoll || '').toLowerCase().includes(search.toLowerCase());

    // Role category match
    let matchRole = true;
    if (selectedRoleCategory !== 'ALL') {
      const activeCat = terminology.filterCategories.find((c) => c.key === selectedRoleCategory);
      if (activeCat && activeCat.roles) {
        const memberRole = (s.role || 'STUDENT').toUpperCase();
        matchRole = activeCat.roles.includes(memberRole as EntityRole);
      }
    }

    return matchDept && matchSearch && matchRole;
  });

  const handleAddStudent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newRoll.trim() || !newName.trim()) {
      showToast(`${terminology.idLabel} and Full Name are required`, 'error');
      return;
    }

    try {
      setIsSubmitting(true);
      const res = await fetch('/api/v1/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          studentRoll: newRoll.trim(),
          fullName: newName.trim(),
          role: newRole,
          department: newDept,
          semester: newSemester,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`${newName} (${newRole}) enrolled successfully with 512-D vectors`, 'success');
        setShowAddModal(false);
        setNewRoll('');
        setNewName('');
        fetchStudents();
      } else {
        showToast(data.error || `Failed to save ${terminology.entitySingular.toLowerCase()}`, 'error');
      }
    } catch (err: any) {
      showToast(err?.message || `Error enrolling ${terminology.entitySingular.toLowerCase()}`, 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDeleteStudent = async (s: StudentItem) => {
    if (!confirm(`Purge biometric vectors and enrollment for ${s.fullName} (${s.studentRoll}) under DPDP Act 2023?`)) {
      return;
    }

    try {
      const res = await fetch(`/api/v1/users?id=${encodeURIComponent(s.id)}&roll=${encodeURIComponent(s.studentRoll)}`, {
        method: 'DELETE',
      });
      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Purged ${s.fullName} records`, 'success');
        setStudents((prev) => prev.filter((item) => item.id !== s.id));
      } else {
        showToast(data.error || 'Failed to purge record', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error purging record', 'error');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
            <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', margin: 0 }}>
              {terminology.entityPlural} & Directory
            </h1>
            <span
              style={{
                fontSize: '11px',
                fontWeight: 600,
                padding: '2px 8px',
                borderRadius: '6px',
                background: 'rgba(99, 102, 241, 0.1)',
                color: 'var(--primary, #6366f1)',
                border: '1px solid rgba(99, 102, 241, 0.25)',
                textTransform: 'uppercase',
                letterSpacing: '0.5px',
              }}
            >
              {terminology.orgType}
            </span>
          </div>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', margin: 0 }}>
            Institutional member enrollment, multi-role directory, and 512-D ArcFace biometric templates
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>Enroll {terminology.entitySingular} / Staff</span>
          </button>
        </div>
      </div>

      {/* DPDP Compliance Notice Banner */}
      <div
        style={{
          background: 'rgba(59, 130, 246, 0.08)',
          border: '1px solid rgba(59, 130, 246, 0.25)',
          borderRadius: '10px',
          padding: '14px 18px',
          marginBottom: '20px',
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          fontSize: '13px',
          color: 'var(--text-muted)',
        }}
      >
        <span style={{ fontSize: '20px' }}>🛡️</span>
        <div>
          <strong style={{ color: 'var(--text-main)' }}>DPDP Act 2023 Compliant:</strong> Facial biometric templates are stored as 512-dimensional floating-point mathematical hashes. Raw camera photos are never saved or uploaded.
        </div>
      </div>

      {/* Role Category Tabs */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '16px', overflowX: 'auto', paddingBottom: '4px' }}>
        {terminology.filterCategories.map((cat) => {
          const isActive = selectedRoleCategory === cat.key;
          return (
            <button
              key={cat.key}
              onClick={() => setSelectedRoleCategory(cat.key)}
              style={{
                padding: '7px 14px',
                borderRadius: '8px',
                fontSize: '12px',
                fontWeight: isActive ? 600 : 500,
                background: isActive ? 'var(--primary, #6366f1)' : 'var(--surface-raised)',
                color: isActive ? '#fff' : 'var(--text-muted)',
                border: isActive ? '1px solid var(--primary, #6366f1)' : '1px solid var(--border)',
                cursor: 'pointer',
                transition: 'all 0.15s ease',
                whiteSpace: 'nowrap',
              }}
            >
              {cat.label}
            </button>
          );
        })}
      </div>

      {/* Controls: Search + Department */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', flexWrap: 'wrap' }}>
        <input
          type="text"
          placeholder={`Search by name, ${terminology.idLabel.toLowerCase()}, or department...`}
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

        <select
          value={department}
          onChange={(e) => setDepartment(e.target.value)}
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
          <option value="Management & HR">Management & HR</option>
          <option value="Staff & Faculty">Staff & Faculty</option>
        </select>
      </div>

      {/* Directory Table */}
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
          Loading enrolled people & directory...
        </div>
      ) : filtered.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px' }}>
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="1.5" style={{ margin: '0 auto 16px', display: 'block' }}>
            <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/>
            <circle cx="9" cy="7" r="4"/>
            <path d="M22 21v-2a4 4 0 0 0-3-3.87"/>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
          </svg>
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>
            {search || department !== 'ALL' || selectedRoleCategory !== 'ALL'
              ? 'No Matching People Found'
              : `No ${terminology.entityPlural} Enrolled Yet`}
          </h3>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', maxWidth: '380px', margin: '0 auto 20px' }}>
            {search || department !== 'ALL' || selectedRoleCategory !== 'ALL'
              ? 'Try changing your search query, role tab, or department filter.'
              : `Enroll ${terminology.entityPlural.toLowerCase()} or staff with their ${terminology.idLabel.toLowerCase()} and 512-D ArcFace biometric templates.`}
          </p>
          <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
            <span>Enroll First {terminology.entitySingular}</span>
          </button>
        </div>
      ) : (
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>{terminology.idLabel}</th>
                <th>Full Name</th>
                <th>Role</th>
                <th>Department</th>
                <th>{terminology.groupLabel}</th>
                <th>Face Vectors</th>
                <th>Quality Score</th>
                <th>Status</th>
                <th>Enrolled Date</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((s) => {
                const badge = getRoleBadgeMeta(s.role || 'STUDENT');
                return (
                  <tr key={s.id}>
                    <td className="tnum" style={{ fontWeight: 600 }}>{s.studentRoll}</td>
                    <td style={{ fontWeight: 500 }}>{s.fullName}</td>
                    <td>
                      <span
                        style={{
                          fontSize: '11px',
                          fontWeight: 600,
                          padding: '2px 8px',
                          borderRadius: '6px',
                          background: badge.bg,
                          color: badge.color,
                          border: `1px solid ${badge.border}`,
                          textTransform: 'uppercase',
                          letterSpacing: '0.4px',
                        }}
                      >
                        {badge.label}
                      </span>
                    </td>
                    <td style={{ color: 'var(--text-muted)' }}>{s.department}</td>
                    <td>{s.semester}</td>
                    <td className="tnum">
                      <span className="badge badge-primary">{s.vectorCount || 5} Angles (512-D)</span>
                    </td>
                    <td className="tnum" style={{ color: 'var(--success)', fontWeight: 600 }}>
                      {s.qualityScore}%
                    </td>
                    <td>
                      <span className="badge badge-success">{s.status}</span>
                    </td>
                    <td className="tnum" style={{ color: 'var(--text-dim)' }}>{s.enrolledAt}</td>
                    <td>
                      <button
                        onClick={() => handleDeleteStudent(s)}
                        style={{ background: 'none', border: 'none', color: 'var(--danger, #ef4444)', cursor: 'pointer', fontSize: '12px' }}
                      >
                        Purge
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Enroll Modal */}
      {showAddModal && (
        <div className="modal-overlay" onClick={() => setShowAddModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '480px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '16px' }}>
              Enroll New {terminology.entitySingular} / Person
            </h2>
            <form onSubmit={handleAddStudent} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              {/* Role Selector */}
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Person Role & Category
                </label>
                <select
                  value={newRole}
                  onChange={(e) => setNewRole(e.target.value as EntityRole)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                >
                  {terminology.allowedRoles.map((r) => (
                    <option key={r} value={r}>
                      {r} ({getRoleBadgeMeta(r).label})
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  {terminology.idLabel}
                </label>
                <input
                  type="text"
                  required
                  placeholder={orgType === 'CORPORATE' ? 'e.g. EMP-1042' : 'e.g. CS-2024-099'}
                  value={newRoll}
                  onChange={(e) => setNewRoll(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                  Full Name
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Arjun Nair"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                    Department
                  </label>
                  <select
                    value={newDept}
                    onChange={(e) => setNewDept(e.target.value)}
                    style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  >
                    <option value="Computer Science">Computer Science</option>
                    <option value="Electronics & Comm.">Electronics & Comm.</option>
                    <option value="Mechanical Eng.">Mechanical Eng.</option>
                    <option value="Management & HR">Management & HR</option>
                    <option value="Staff & Faculty">Staff & Faculty</option>
                  </select>
                </div>

                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                    {terminology.groupLabel}
                  </label>
                  <input
                    type="text"
                    placeholder={orgType === 'CORPORATE' ? 'General Shift / Engineering' : 'Semester IV'}
                    value={newSemester}
                    onChange={(e) => setNewSemester(e.target.value)}
                    style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} className="btn btn-secondary">
                  Cancel
                </button>
                <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                  {isSubmitting ? 'Enrolling...' : 'Enroll Person'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
