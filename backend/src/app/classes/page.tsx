'use client';

import React, { useState } from 'react';
import { useToast } from '@/components/Toast';

interface ClassItem {
  id: string;
  name: string;
  section: string;
  department: string;
  scheduleTime: string;
  graceMinutes: number;
  enrolledCount: number;
  status: 'ACTIVE' | 'ARCHIVED';
}

const mockClasses: ClassItem[] = [
  { id: 'cls_01', name: 'B.Tech CS 4th Sem', section: 'A', department: 'Computer Science', scheduleTime: '09:00 – 17:00', graceMinutes: 15, enrolledCount: 64, status: 'ACTIVE' },
  { id: 'cls_02', name: 'B.Tech CS 4th Sem', section: 'B', department: 'Computer Science', scheduleTime: '09:00 – 17:00', graceMinutes: 15, enrolledCount: 58, status: 'ACTIVE' },
  { id: 'cls_03', name: 'B.Tech EC 4th Sem', section: 'A', department: 'Electronics & Comm.', scheduleTime: '08:30 – 16:30', graceMinutes: 10, enrolledCount: 48, status: 'ACTIVE' },
  { id: 'cls_04', name: 'B.Tech ME 6th Sem', section: 'A', department: 'Mechanical Eng.', scheduleTime: '09:00 – 17:00', graceMinutes: 15, enrolledCount: 42, status: 'ACTIVE' },
  { id: 'cls_05', name: 'Faculty & Non-Teaching', section: 'Staff', department: 'Staff & Faculty', scheduleTime: '08:00 – 18:00', graceMinutes: 30, enrolledCount: 30, status: 'ACTIVE' },
];

export default function ClassesPage() {
  const [classesList, setClassesList] = useState<ClassItem[]>(mockClasses);
  const [showAddModal, setShowAddModal] = useState(false);
  const [className, setClassName] = useState('');
  const [section, setSection] = useState('A');
  const [department, setDepartment] = useState('Computer Science');
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('17:00');
  const [graceMinutes, setGraceMinutes] = useState(15);
  const { showToast } = useToast();

  const handleAddClass = (e: React.FormEvent) => {
    e.preventDefault();
    if (!className) {
      showToast('Class name is required', 'error');
      return;
    }

    const newClass: ClassItem = {
      id: `cls_${Date.now()}`,
      name: className,
      section,
      department,
      scheduleTime: `${startTime} – ${endTime}`,
      graceMinutes: Number(graceMinutes),
      enrolledCount: 0,
      status: 'ACTIVE',
    };

    setClassesList([newClass, ...classesList]);
    setShowAddModal(false);
    setClassName('');
    showToast(`Class "${className} (${section})" created successfully`, 'success');
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

      <div className="table-surface">
        <table className="data-table">
          <thead>
            <tr>
              <th>Class Name</th>
              <th>Section</th>
              <th>Department</th>
              <th>Timetable Schedule</th>
              <th>Grace Threshold</th>
              <th>Enrolled Students</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {classesList.map((cls) => (
              <tr key={cls.id}>
                <td style={{ fontWeight: 600 }}>{cls.name}</td>
                <td><span className="badge badge-primary">Section {cls.section}</span></td>
                <td style={{ color: 'var(--text-muted)' }}>{cls.department}</td>
                <td className="tnum">{cls.scheduleTime}</td>
                <td className="tnum">{cls.graceMinutes} mins</td>
                <td className="tnum" style={{ fontWeight: 600 }}>{cls.enrolledCount} Students</td>
                <td><span className="badge badge-success">{cls.status}</span></td>
                <td>
                  <button
                    onClick={() => showToast(`Schedule configured for ${cls.name}`, 'info')}
                    style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                  >
                    Manage Roster
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
