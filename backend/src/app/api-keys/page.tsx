'use client';

import React, { useState } from 'react';
import { useToast } from '@/components/Toast';

interface KioskDevice {
  id: string;
  model: string;
  location: string;
  ping: string;
  lastSync: string;
  status: string;
}

const initialDevices: KioskDevice[] = [
  {
    id: 'OMNIFACE-TERMINAL-01',
    model: 'Xiaomi 14 (HyperOS / Android 14)',
    location: 'Main Campus Gate A',
    ping: '14ms',
    lastSync: 'Just now',
    status: 'ONLINE',
  },
  {
    id: 'OMNIFACE-TERMINAL-02',
    model: 'Samsung Galaxy Tab S9 (OneUI 6)',
    location: 'Computer Science Dept Hallway',
    ping: '22ms',
    lastSync: '5 minutes ago',
    status: 'ONLINE',
  },
  {
    id: 'OMNIFACE-TERMINAL-03',
    model: 'Lenovo Tab M10 Plus (Kiosk Mount)',
    location: 'Central Library Entry',
    ping: '19ms',
    lastSync: '8 minutes ago',
    status: 'ONLINE',
  },
];

export default function ApiKeysPage() {
  const [copiedKey, setCopiedKey] = useState(false);
  const [testResponse, setTestResponse] = useState<string | null>(null);
  const [testingEndpoint, setTestingEndpoint] = useState(false);
  const [devices, setDevices] = useState<KioskDevice[]>(initialDevices);
  const [showProvisionModal, setShowProvisionModal] = useState(false);
  const [newDeviceName, setNewDeviceName] = useState('');
  const [newDeviceLocation, setNewDeviceLocation] = useState('Academic Block C');
  const { showToast } = useToast();

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowProvisionModal(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const apiKey = 'omni_kiosk_live_a89f30b91e7724dc5c90a1';

  const copyToClipboard = () => {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(apiKey);
      } else {
        const input = document.createElement('input');
        input.value = apiKey;
        document.body.appendChild(input);
        input.select();
        document.execCommand('copy');
        document.body.removeChild(input);
      }
      setCopiedKey(true);
      showToast('Master Kiosk API Key copied to clipboard!', 'success');
      setTimeout(() => setCopiedKey(false), 2000);
    } catch {
      showToast('Master Kiosk API Key ready to copy', 'info');
    }
  };

  const testSyncEndpoint = async () => {
    setTestingEndpoint(true);
    const testRecordId = 'rec_pw_test_' + Date.now();
    try {
      const res = await fetch('/api/v1/attendance/sync', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Device-ID': 'XIAOMI-14-PROD',
          'X-Device-Fingerprint': 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        },
        body: JSON.stringify({
          device_id: 'XIAOMI-14-PROD',
          records: [
            {
              record_id: testRecordId,
              student_roll: 'CS-2024-001',
              student_name: 'Aarav Sharma',
              session_date: new Date().toISOString().split('T')[0],
              timestamp: Date.now(),
              confidence_pct: 99,
              security_tier: 'HIGH',
              sha256_hash: 'a7b3c82d4e5f61203498adfe1902834b9281a0ec94726481029384756182a93c',
            },
          ],
        }),
      });

      const data = await res.json();
      setTestResponse(JSON.stringify(data, null, 2));
      if (res.ok && data.success) {
        showToast(`Synchronized test record (${testRecordId}) with HTTP 200!`, 'success');
      } else {
        showToast(data.message || 'Endpoint tested', 'info');
      }
    } catch (err: any) {
      setTestResponse('Error: ' + err.message);
      showToast('Sync endpoint error: ' + err.message, 'error');
    } finally {
      setTestingEndpoint(false);
    }
  };

  const handleProvisionDevice = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newDeviceName) return;

    const newId = 'OMNIFACE-TERMINAL-0' + (devices.length + 1);
    const createdDevice: KioskDevice = {
      id: newId,
      model: newDeviceName,
      location: newDeviceLocation,
      ping: '16ms',
      lastSync: 'Pending enrollment',
      status: 'ONLINE',
    };

    setDevices([...devices, createdDevice]);
    setNewDeviceName('');
    setShowProvisionModal(false);
    showToast(`Provisioned ${newId} for ${newDeviceLocation}!`, 'success');
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            API Access & Fleet Kiosks
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Manage kiosk device authentication keys, HMAC secrets, and sync REST endpoints
          </p>
        </div>
      </div>

      {/* Production Kiosk API Key Box */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px', marginBottom: '28px' }}>
        <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>Kiosk Fleet Master API Key</h2>
        <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '16px' }}>
          Use this key in your Android Kiosk settings (`SYNC_REST_ENDPOINT` & API Key) to authorize high-throughput attendance push batches.
        </p>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <input
            type="text"
            readOnly
            aria-label="Kiosk Fleet Master API Key"
            value={apiKey}
            style={{
              flex: 1,
              fontFamily: 'var(--font-mono)',
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: '8px',
              padding: '10px 14px',
              color: 'var(--primary)',
              fontSize: '13px',
              outline: 'none',
            }}
          />
          <button onClick={copyToClipboard} className="btn btn-secondary" aria-label="Copy API Key to clipboard">
            {copiedKey ? '✓ Copied!' : 'Copy Key'}
          </button>
        </div>
      </div>

      {/* Registered Kiosks Table */}
      <div style={{ marginBottom: '28px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Authorized Kiosk Devices ({devices.length})</h2>
          <button onClick={() => setShowProvisionModal(true)} className="btn btn-secondary" style={{ fontSize: '12px' }}>
            + Provision New Terminal
          </button>
        </div>
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Device ID</th>
                <th scope="col">Device Model / Hardware</th>
                <th scope="col">Deployment Location</th>
                <th scope="col">Heartbeat Ping</th>
                <th scope="col">Last Synced Batch</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((d) => (
                <tr key={d.id}>
                  <td className="tnum" style={{ fontWeight: 600 }}>{d.id}</td>
                  <td>{d.model}</td>
                  <td>{d.location}</td>
                  <td className="tnum" style={{ color: 'var(--success)' }}>{d.ping}</td>
                  <td style={{ color: 'var(--text-muted)' }}>{d.lastSync}</td>
                  <td><span className="badge badge-success">{d.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Interactive Sync API Tester */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
          <div>
            <h2 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Interactive Endpoint Verification</h2>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
              Test `/api/v1/attendance/sync` live endpoint with HMAC-SHA256 handshake.
            </p>
          </div>
          <button onClick={testSyncEndpoint} disabled={testingEndpoint} className="btn btn-primary">
            {testingEndpoint ? 'Transmitting...' : 'Dispatch Test Batch'}
          </button>
        </div>

        {testResponse && (
          <pre style={{ marginTop: '16px', padding: '16px', background: 'var(--surface)', borderRadius: '8px', border: '1px solid var(--border)', fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--primary)', overflowX: 'auto' }}>
            {testResponse}
          </pre>
        )}
      </div>

      {/* Provision New Terminal Modal */}
      {showProvisionModal && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="provision-modal-title"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.75)',
            backdropFilter: 'blur(4px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '16px',
          }}
          onClick={() => setShowProvisionModal(false)}
        >
          <div
            style={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border)',
              borderRadius: '12px',
              width: '100%',
              maxWidth: '460px',
              padding: '24px',
              boxShadow: '0 25px 50px rgba(0,0,0,0.7)',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="provision-modal-title" style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>
              Provision New Kiosk Terminal
            </h2>
            <form onSubmit={handleProvisionDevice}>
              <div style={{ marginBottom: '14px' }}>
                <label htmlFor="device-model-input" style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                  Hardware / Device Model
                </label>
                <input
                  id="device-model-input"
                  type="text"
                  required
                  placeholder="e.g. Xiaomi Pad 6 / Galaxy Tab A9"
                  value={newDeviceName}
                  onChange={(e) => setNewDeviceName(e.target.value)}
                  style={{ width: '100%', padding: '10px', borderRadius: '6px', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-main)', fontSize: '13px' }}
                />
              </div>

              <div style={{ marginBottom: '20px' }}>
                <label htmlFor="device-location-input" style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                  Deployment Location
                </label>
                <input
                  id="device-location-input"
                  type="text"
                  required
                  placeholder="e.g. Science Block Ground Floor"
                  value={newDeviceLocation}
                  onChange={(e) => setNewDeviceLocation(e.target.value)}
                  style={{ width: '100%', padding: '10px', borderRadius: '6px', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-main)', fontSize: '13px' }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
                <button type="button" onClick={() => setShowProvisionModal(false)} className="btn btn-secondary">
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary">
                  Authorize & Provision
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
