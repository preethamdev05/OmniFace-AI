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
  const [provisionedKiosk, setProvisionedKiosk] = useState<any | null>(null);
  const [isProvisioning, setIsProvisioning] = useState(false);
  const [newDeviceName, setNewDeviceName] = useState('');
  const [newDeviceLocation, setNewDeviceLocation] = useState('Academic Block C');
  const { showToast } = useToast();

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowProvisionModal(false);
        setProvisionedKiosk(null);
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
              student_roll: 'DIAG-TEST-001',
              student_name: 'Diagnostic Subject Alpha',
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

  const handleProvisionDevice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newDeviceName) return;
    setIsProvisioning(true);

    try {
      const res = await fetch('/api/v1/kiosks/provision', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: newDeviceName,
          location: newDeviceLocation,
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        const k = data.kiosk;
        const newDevice: KioskDevice = {
          id: k.id,
          model: k.model,
          location: k.location,
          ping: k.ping || '12ms',
          lastSync: k.lastSync || 'Just now',
          status: 'ONLINE',
        };
        setDevices((prev) => [newDevice, ...prev]);
        setProvisionedKiosk(k);
        setShowProvisionModal(false);
        showToast(`Provisioned ${k.id} successfully!`, 'success');
      } else {
        showToast(data.error || 'Provisioning failed', 'error');
      }
    } catch (err: any) {
      showToast('Error provisioning kiosk: ' + err.message, 'error');
    } finally {
      setIsProvisioning(false);
    }
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
          className="modal-overlay"
          onClick={() => setShowProvisionModal(false)}
        >
          <div
            className="modal-dialog"
            style={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border)',
              borderRadius: '12px',
              maxWidth: '460px',
              padding: '24px',
              boxShadow: '0 25px 50px rgba(0,0,0,0.7)',
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
                <button type="submit" disabled={isProvisioning} className="btn btn-primary">
                  {isProvisioning ? 'Provisioning...' : 'Authorize & Provision'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Scannable Provisioning QR & Secret Modal */}
      {provisionedKiosk && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="provisioned-kiosk-title"
          className="modal-overlay"
          onClick={() => setProvisionedKiosk(null)}
        >
          <div
            className="modal-dialog"
            style={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border)',
              borderRadius: '12px',
              maxWidth: '520px',
              padding: '24px',
              boxShadow: '0 25px 50px rgba(0,0,0,0.7)',
              textAlign: 'center',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ textAlign: 'left' }}>
                <h2 id="provisioned-kiosk-title" style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)' }}>
                  Kiosk Terminal Activated
                </h2>
                <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                  Scan this QR code from the OmniFace Android Kiosk to pair instantly
                </p>
              </div>
              <button
                onClick={() => setProvisionedKiosk(null)}
                style={{ background: 'none', border: 'none', color: 'var(--text-dim)', cursor: 'pointer', fontSize: '18px' }}
              >
                ✕
              </button>
            </div>

            <div style={{ padding: '16px', background: '#0a0c10', borderRadius: '12px', display: 'inline-block', margin: '8px auto 16px auto', border: '1px solid var(--border)' }}>
              {/* Dynamic Secure QR Generator */}
              <img
                src={`https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(provisionedKiosk.qrString)}&bgcolor=0a0c10&color=22c55e`}
                alt="OmniFace Kiosk Provisioning QR Code"
                width="220"
                height="220"
                style={{ display: 'block', borderRadius: '8px' }}
              />
            </div>

            <div style={{ textAlign: 'left', background: 'var(--surface)', padding: '14px', borderRadius: '8px', border: '1px solid var(--border)', fontSize: '12px', marginBottom: '20px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Terminal ID:</span>
                <span className="tnum" style={{ fontWeight: 600, color: 'var(--text-main)' }}>{provisionedKiosk.id}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Location:</span>
                <span style={{ color: 'var(--text-main)' }}>{provisionedKiosk.location}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Sync Endpoint:</span>
                <span className="tnum" style={{ color: 'var(--primary)', wordBreak: 'break-all' }}>{provisionedKiosk.syncEndpoint}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-muted)' }}>HMAC Secret:</span>
                <span className="tnum" style={{ fontFamily: 'var(--font-mono)', color: 'var(--success)' }}>
                  {provisionedKiosk.hmacSecret.slice(0, 12)}••••••••
                </span>
              </div>
            </div>

            <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
              <button
                type="button"
                onClick={() => {
                  navigator.clipboard.writeText(JSON.stringify(provisionedKiosk.qrConfig, null, 2));
                  showToast('Kiosk provisioning payload copied to clipboard!', 'success');
                }}
                className="btn btn-secondary"
              >
                Copy JSON Config
              </button>
              <button
                type="button"
                onClick={() => setProvisionedKiosk(null)}
                className="btn btn-primary"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
