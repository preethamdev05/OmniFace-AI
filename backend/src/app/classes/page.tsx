'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

interface ClassItem {
  id: string;
  name: string;
  section: string;
  departmentId?: string | null;
  departmentName?: string;
  scheduleStartTime: string;
  scheduleEndTime: string;
  graceMinutes: number;
  enrolledCount: number;
  status: 'ACTIVE' | 'ARCHIVED';
}

export default function ClassesPage() {
  const [classesList, setClassesList] = useState<ClassItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [className, setClassName] = useState('');
  const [section, setSection] = useState('A');
  const [department, setDepartment] = useState('Computer Science');
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('17:00');
  const [graceMinutes, setGraceMinutes] = useState(15);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { showToast } = useToast();

  const fetchClasses = async () => {
    try {
      setIsLoading(true);
      const res = await fetch('/api/v1/classes');
      if (res.ok) {
        const data = await res.json();
        if (data.success && Array.isArray(data.classes)) {
          setClassesList(
            data.classes.map((c: any) => ({
              id: c.id,
              name: c.name,
              section: c.section || 'A',
              departmentId: c.departmentId,
              departmentName: c.departmentName || 'General',
              scheduleStartTime: c.scheduleStartTime || '09:00',
              scheduleEndTime: c.scheduleEndTime || '17:00',
              graceMinutes: c.graceMinutes || 15,
              enrolledCount: c.enrolledCount || 0,
              status: 'ACTIVE',
            }))
          );
        }
      }
    } catch (err: any) {
      console.warn('Could not fetch classes:', err?.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchClasses();
  }, []);

  const handleAddClass = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!className.trim()) {
      showToast('Class name is required', 'error');
      return;
    }

    try {
      setIsSubmitting(true);
      const res = await fetch('/api/v1/classes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: className.trim(),
          section: section.trim() || 'A',
          scheduleStartTime: startTime,
          scheduleEndTime: endTime,
          graceMinutes: Number(graceMinutes),
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Class "${className} (${section})" created successfully`, 'success');
        setShowAddModal(false);
        setClassName('');
        setSection('A');
        fetchClasses();
      } else {
        showToast(data.error || 'Failed to create class', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error creating class', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDeleteClass = async (id: string, name: string) => {
    if (!confirm(`Are you sure you want to delete class "${name}"?`)) return;

    try {
      const res = await fetch(`/api/v1/classes?id=${encodeURIComponent(id)}`, {
        method: 'DELETE',
      });
      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Class "${name}" deleted`, 'success');
        setClassesList((prev) => prev.filter((c) => c.id !== id));
      } else {
        showToast(data.error || 'Failed to delete class', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error deleting class', 'error');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Classes & Sections
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Configure academic timetable schedules, sections, and automated attendance grace periods
          </p>
        </div>

        <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>Add Class / Section</span>
        </button>
      </div>

      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
          Loading classes...
        </div>
      ) : classesList.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px' }}>
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="1.5" style={{ margin: '0 auto 16px', display: 'block' }}>
            <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/>
            <path d="M6 6h10M6 10h10"/>
          </svg>
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>No Classes Configured Yet</h3>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', maxWidth: '380px', margin: '0 auto 20px' }}>
            Create academic cohorts, timetable schedules, and sections to group attendance sessions and student enrollments.
          </p>
          <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
            <span>Add First Class</span>
          </button>
        </div>
      ) : (
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>Class Name</th>
                <th>Section</th>
                <th>Department</th>
                <th>Timetable Schedule</th>
                <th>Grace Threshold</th>
                <th>Enrolled Members</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {classesList.map((cls) => (
                <tr key={cls.id}>
                  <td style={{ fontWeight: 600 }}>{cls.name}</td>
                  <td><span className="badge badge-primary">Section {cls.section}</span></td>
                  <td style={{ color: 'var(--text-muted)' }}>{cls.departmentName || 'General'}</td>
                  <td className="tnum">{cls.scheduleStartTime} – {cls.scheduleEndTime}</td>
                  <td className="tnum">{cls.graceMinutes} mins</td>
                  <td className="tnum" style={{ fontWeight: 600 }}>{cls.enrolledCount} Enrolled</td>
                  <td><span className="badge badge-success">{cls.status}</span></td>
                  <td>
                    <div style={{ display: 'flex', gap: '10px' }}>
                      <button
                        onClick={() => showToast(`Schedule configured for ${cls.name}`, 'info')}
                        style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                      >
                        Manage Roster
                      </button>
                      <button
                        onClick={() => handleDeleteClass(cls.id, cls.name)}
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
          <div className="modal-dialog" style={{ maxWidth: '480px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '16px' }}>Create New Class</h2>
            <form onSubmit={handleAddClass} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Class / Program Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. B.Tech AI & Data Science"
                  value={className}
                  onChange={(e) => setClassName(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Section</label>
                  <input
                    type="text"
                    required
                    placeholder="A"
                    value={section}
                    onChange={(e) => setSection(e.target.value)}
                    style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  />
                </div>

                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Department</label>
                  <select
                    value={department}
                    onChange={(e) => setDepartment(e.target.value)}
                    style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  >
                    <option value="Computer Science">Computer Science</option>
                    <option value="Electronics & Comm.">Electronics & Comm.</option>
                    <option value="Mechanical Eng.">Mechanical Eng.</option>
                    <option value="Staff & Faculty">Staff & Faculty</option>
                  </select>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Start Time</label>
                  <input
                    type="time"
                    value={startTime}
                    onChange={(e) => setStartTime(e.target.value)}
                    style={{ width: '100%', padding: '8px 10px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  />
                </div>

                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>End Time</label>
                  <input
                    type="time"
                    value={endTime}
                    onChange={(e) => setEndTime(e.target.value)}
                    style={{ width: '100%', padding: '8px 10px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  />
                </div>

                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Grace (Mins)</label>
                  <input
                    type="number"
                    min="0"
                    max="60"
                    value={graceMinutes}
                    onChange={(e) => setGraceMinutes(Number(e.target.value))}
                    style={{ width: '100%', padding: '8px 10px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} className="btn btn-secondary">Cancel</button>
                <button type="submit" className="btn btn-primary">Create Class</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
