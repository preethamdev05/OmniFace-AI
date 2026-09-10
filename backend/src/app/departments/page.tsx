'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

interface DepartmentItem {
  id: string;
  name: string;
  code: string;
  description?: string | null;
  studentCount?: number;
  classesCount?: number;
  status: 'ACTIVE' | 'INACTIVE';
}

export default function DepartmentsPage() {
  const [deptList, setDeptList] = useState<DepartmentItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { showToast } = useToast();

  const fetchDepartments = async () => {
    try {
      setIsLoading(true);
      const res = await fetch('/api/v1/departments');
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.departments)) {
          setDeptList(
            data.departments.map((d: any) => ({
              id: d.id,
              name: d.name,
              code: d.code,
              description: d.description,
              studentCount: d.studentCount || 0,
              classesCount: d.classesCount || 0,
              status: 'ACTIVE',
            }))
          );
        }
      }
    } catch (err: any) {
      console.warn('Could not fetch departments:', err?.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchDepartments();
  }, []);

  const handleAddDept = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !code.trim()) {
      showToast('Department name and code are required', 'error');
      return;
    }

    try {
      setIsSubmitting(true);
      const res = await fetch('/api/v1/departments', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          code: code.trim().toUpperCase(),
          description: description.trim() || undefined,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Department "${name}" created successfully`, 'success');
        setShowAddModal(false);
        setName('');
        setCode('');
        setDescription('');
        fetchDepartments();
      } else {
        showToast(data.error || 'Failed to create department', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error creating department', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDeleteDept = async (id: string, deptName: string) => {
    if (!confirm(`Are you sure you want to delete department "${deptName}"?`)) return;

    try {
      const res = await fetch(`/api/v1/departments?id=${encodeURIComponent(id)}`, {
        method: 'DELETE',
      });
      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Department "${deptName}" deleted`, 'success');
        setDeptList((prev) => prev.filter((d) => d.id !== id));
      } else {
        showToast(data.error || 'Failed to delete department', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error deleting department', 'error');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Academic Departments
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Manage departmental organizational units, student allocations, and institutional structure
          </p>
        </div>

        <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>Add Department</span>
        </button>
      </div>

      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
          Loading departments...
        </div>
      ) : deptList.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px' }}>
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="1.5" style={{ margin: '0 auto 16px', display: 'block' }}>
            <rect width="18" height="18" x="3" y="3" rx="2"/>
            <path d="M9 3v18M15 9h6M15 15h6"/>
          </svg>
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>No Departments Configured Yet</h3>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', maxWidth: '380px', margin: '0 auto 20px' }}>
            Organize courses, people, and rosters into departments (e.g. Computer Science, Mechanical Eng., General Staff).
          </p>
          <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
            <span>Add First Department</span>
          </button>
        </div>
      ) : (
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>Department Name</th>
                <th>Code</th>
                <th>Description</th>
                <th>Enrolled People</th>
                <th>Classes</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {deptList.map((d) => (
                <tr key={d.id}>
                  <td style={{ fontWeight: 600 }}>{d.name}</td>
                  <td><span className="badge badge-primary">{d.code}</span></td>
                  <td style={{ color: 'var(--text-muted)' }}>{d.description || '—'}</td>
                  <td className="tnum" style={{ fontWeight: 600 }}>{d.studentCount}</td>
                  <td className="tnum">{d.classesCount} Classes</td>
                  <td><span className="badge badge-success">{d.status}</span></td>
                  <td>
                    <div style={{ display: 'flex', gap: '10px' }}>
                      <button
                        onClick={() => showToast(`Department details: ${d.name}`, 'info')}
                        style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                      >
                        Configure
                      </button>
                      <button
                        onClick={() => handleDeleteDept(d.id, d.name)}
                        style={{ background: 'none', border: 'none', color: 'var(--danger, #ef4444)', cursor: 'pointer', fontSize: '12px' }}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showAddModal && (
        <div className="modal-overlay" onClick={() => setShowAddModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '440px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '16px' }}>Add Department</h2>
            <form onSubmit={handleAddDept} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Department Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Information Technology"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Department Code</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. IT"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Description (Optional)</label>
                <input
                  type="text"
                  placeholder="e.g. Faculty, Labs and Students"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} className="btn btn-secondary">Cancel</button>
                <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                  {isSubmitting ? 'Saving...' : 'Save Department'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
