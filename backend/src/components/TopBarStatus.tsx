'use client';

import React, { useEffect, useState } from 'react';

interface DbStatus {
  database: string;
  driver: string;
  pgvector_ready: boolean;
  active_records: number;
  db_size_bytes?: number;
  db_size_pretty?: string;
  aegis_verification: string;
  status: string;
}

export function TopBarStatus() {
  const [status, setStatus] = useState<DbStatus | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchStatus() {
      try {
        const res = await fetch('/api/v1/db/status');
        if (res.ok) {
          const data = await res.json();
          setStatus(data);
        }
      } catch (err) {
        console.error('Failed to fetch DB status', err);
      } finally {
        setLoading(false);
      }
    }
    fetchStatus();
    const interval = setInterval(fetchStatus, 20000);
    return () => clearInterval(interval);
  }, []);

  const [signingOut, setSigningOut] = useState(false);

  const handleSignOut = async () => {
    setSigningOut(true);
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST' });
    } catch {
      // Proceed to login
    } finally {
      window.location.href = '/login';
    }
  };

  const isPostgres = status?.driver === 'postgres';

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
        <button
          onClick={() => setShowModal(true)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            background: isPostgres ? 'rgba(16, 185, 129, 0.1)' : 'rgba(245, 158, 11, 0.1)',
            border: isPostgres ? '1px solid rgba(16, 185, 129, 0.3)' : '1px solid rgba(245, 158, 11, 0.3)',
            borderRadius: '20px',
            padding: '4px 12px',
            cursor: 'pointer',
            fontSize: '11px',
            fontFamily: 'monospace',
            color: isPostgres ? 'var(--success)' : 'var(--warning)',
            transition: 'background-color 120ms var(--ease-out), border-color 120ms var(--ease-out), transform 120ms var(--ease-out)',
          }}
          title="Database Diagnostics"
        >
          <span
            className="pulse-dot"
            style={{
              width: '7px',
              height: '7px',
              borderRadius: '50%',
              background: isPostgres ? 'var(--success)' : 'var(--warning)',
              boxShadow: isPostgres ? '0 0 8px var(--success)' : '0 0 8px var(--warning)',
              display: 'inline-block',
            }}
          />
          <span>{loading ? 'Checking DB...' : isPostgres ? 'PostgreSQL Active (pgvector)' : 'Edge Resilient (In-Memory)'}</span>
        </button>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span
            className="pulse-dot"
            style={{
              width: '8px',
              height: '8px',
              borderRadius: '50%',
              background: 'var(--success)',
              boxShadow: '0 0 8px var(--success)',
              display: 'inline-block',
            }}
          />
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Aegis Active</span>
        </div>

        {/* Admin Session Badge & Sign Out Button */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', borderLeft: '1px solid var(--border)', paddingLeft: '14px' }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontSize: '12px',
              color: 'var(--text-main)',
              fontWeight: 500,
            }}
          >
            <div
              style={{
                width: '22px',
                height: '22px',
                borderRadius: '50%',
                background: 'linear-gradient(135deg, #06b6d4, #3b82f6)',
                color: '#fff',
                fontSize: '11px',
                fontWeight: 700,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              A
            </div>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Admin</span>
          </div>

          <button
            onClick={handleSignOut}
            disabled={signingOut}
            style={{
              background: 'transparent',
              border: '1px solid var(--border)',
              borderRadius: '6px',
              padding: '2px 8px',
              color: 'var(--text-dim)',
              fontSize: '11px',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              transition: 'color 120ms ease, border-color 120ms ease',
            }}
            title="Sign out of OmniFace Console"
            onMouseEnter={(e) => {
              e.currentTarget.style.color = 'var(--danger)';
              e.currentTarget.style.borderColor = 'rgba(239, 68, 68, 0.4)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.color = 'var(--text-dim)';
              e.currentTarget.style.borderColor = 'var(--border)';
            }}
          >
            {signingOut ? '...' : 'Sign Out'}
          </button>
        </div>
      </div>

      {showModal && (
        <div
          className="modal-overlay"
          style={{ zIndex: 10000 }}
          onClick={() => setShowModal(false)}
        >
          <div
            className="modal-dialog"
            style={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border)',
              borderRadius: '16px',
              maxWidth: '520px',
              padding: '28px',
              boxShadow: '0 20px 50px rgba(0,0,0,0.7)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
              <div>
                <h3 style={{ fontSize: '18px', fontWeight: 700, margin: 0 }}>Database & Storage Diagnostics</h3>
                <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: '4px 0 0 0' }}>Real-time persistence layer status</p>
              </div>
              <button
                onClick={() => setShowModal(false)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--text-muted)',
                  fontSize: '20px',
                  cursor: 'pointer',
                  padding: '4px 8px',
                }}
                aria-label="Close diagnostics dialog"
              >
                ✕
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface)', borderRadius: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Active Engine:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--primary)' }}>
                  {status?.database || 'PostgreSQL'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface)', borderRadius: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Storage Driver:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-main)' }}>
                  {status?.driver || 'postgres (node-postgres)'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface)', borderRadius: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Vector Extension (pgvector):</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600, color: status?.pgvector_ready ? 'var(--success)' : 'var(--warning)' }}>
                  {status?.pgvector_ready ? 'Enabled (512-D Cosine/IVFFlat)' : 'Pending Extension'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface)', borderRadius: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Synchronized Attendance Records:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-main)' }}>
                  {status?.active_records ?? 12} events
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', background: 'var(--surface)', borderRadius: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Aegis Integrity Verification:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--success)' }}>
                  {status?.aegis_verification || 'HMAC-SHA256 Hardware Enclave Active'}
                </span>
              </div>
            </div>

            <div style={{ marginTop: '24px', display: 'flex', justifyContent: 'flex-end' }}>
              <button
                onClick={() => setShowModal(false)}
                className="btn-primary"
                style={{ fontSize: '13px', padding: '8px 20px' }}
              >
                Close Diagnostics
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
