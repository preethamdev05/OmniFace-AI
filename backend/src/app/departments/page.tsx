'use client';

import React, { useState } from 'react';
import { useToast } from '@/components/Toast';

interface DepartmentItem {
  id: string;
  name: string;
  code: string;
  headOfDept: string;
  studentCount: number;
  classesCount: number;
  status: 'ACTIVE' | 'INACTIVE';
}

const mockDepartments: DepartmentItem[] = [
  { id: 'dept_01', name: 'Computer Science & Engineering', code: 'CSE', headOfDept: 'Dr. Anand Raman', studentCount: 64, classesCount: 4, status: 'ACTIVE' },
  { id: 'dept_02', name: 'Electronics & Communication', code: 'ECE', headOfDept: 'Dr. Meera Nambiar', studentCount: 48, classesCount: 3, status: 'ACTIVE' },
  { id: 'dept_03', name: 'Mechanical Engineering', code: 'MECH', headOfDept: 'Prof. Ramesh Rao', studentCount: 42, classesCount: 3, status: 'ACTIVE' },
  { id: 'dept_04', name: 'Staff, Faculty & Administration', code: 'STAFF', headOfDept: 'Registrar Office', studentCount: 30, classesCount: 1, status: 'ACTIVE' },
];

export default function DepartmentsPage() {
  const [deptList, setDeptList] = useState<DepartmentItem[]>(mockDepartments);
  const [showAddModal, setShowAddModal] = useState(false);
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [hod, setHod] = useState('');
  const { showToast } = useToast();

  const handleAddDept = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name || !code) {
      showToast('Department name and code are required', 'error');
      return;
    }

    const newDept: DepartmentItem = {
      id: `dept_${Date.now()}`,
      name,
      code: code.toUpperCase(),
      headOfDept: hod || 'Department Faculty Head',
      studentCount: 0,
      classesCount: 0,
      status: 'ACTIVE',
    };

    setDeptList([...deptList, newDept]);
    setShowAddModal(false);
    setName('');
    setCode('');
    setHod('');
    showToast(`Department "${name}" created successfully`, 'success');
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Academic Departments
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Manage departmental organizational units, student allocations, and faculty leadership
          </p>
        </div>

        <button onClick={() => setShowAddModal(true)} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>Add Department</span>
        </button>
      </div>

      <div className="table-surface">
        <table className="data-table">
          <thead>
            <tr>
              <th>Department Name</th>
              <th>Code</th>
              <th>Head of Department</th>
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
                <td style={{ color: 'var(--text-muted)' }}>{d.headOfDept}</td>
                <td className="tnum" style={{ fontWeight: 600 }}>{d.studentCount}</td>
                <td className="tnum">{d.classesCount} Classes</td>
                <td><span className="badge badge-success">{d.status}</span></td>
                <td>
                  <button
                    onClick={() => showToast(`Department details: ${d.name}`, 'info')}
                    style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                  >
                    Configure
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>Head of Department / Lead</label>
                <input
                  type="text"
                  placeholder="e.g. Dr. Priya Sundaram"
                  value={hod}
                  onChange={(e) => setHod(e.target.value)}
                  style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} className="btn btn-secondary">Cancel</button>
                <button type="submit" className="btn btn-primary">Save Department</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
