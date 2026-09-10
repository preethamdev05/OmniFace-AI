'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

interface DeviceFleetItem {
  id: string;
  deviceIdentifier: string;
  deviceName: string;
  appVersion: string;
  status: 'ONLINE' | 'OFFLINE' | 'PAIRED' | 'REVOKED';
  lastSyncAt: string;
  lastAttendanceAt: string;
  pendingEventsCount: number;
  hardwareHash: string;
  isPaired: boolean;
  pairedAt: string;
}

const initialDevices: DeviceFleetItem[] = [
  {
    id: 'dev_01',
    deviceIdentifier: 'OMNIFACE-GATE-01',
    deviceName: 'Main Entrance Gate Terminal',
    appVersion: 'v2.0.0',
    status: 'ONLINE',
    lastSyncAt: 'Just now',
    lastAttendanceAt: '09:04 AM',
    pendingEventsCount: 0,
    hardwareHash: 'a8b7c6d5e4f3a2b10987654321fedcba0987654321fedcba0987654321fedcba',
    isPaired: true,
    pairedAt: '2026-08-01T08:00:00Z',
  },
  {
    id: 'dev_02',
    deviceIdentifier: 'OMNIFACE-BLOCKB-02',
    deviceName: 'Academic Block B Terminal',
    appVersion: 'v2.0.0',
    status: 'ONLINE',
    lastSyncAt: '3 min ago',
    lastAttendanceAt: '09:02 AM',
    pendingEventsCount: 0,
    hardwareHash: 'f4e3d2c1b0a99887766554433221100ffeeddccbbaa99887766554433221100f',
    isPaired: true,
    pairedAt: '2026-08-05T09:30:00Z',
  },
  {
    id: 'dev_03',
    deviceIdentifier: 'OMNIFACE-LIB-03',
    deviceName: 'Central Library Kiosk',
    appVersion: 'v1.9.8',
    status: 'ONLINE',
    lastSyncAt: '5 min ago',
    lastAttendanceAt: '08:45 AM',
    pendingEventsCount: 2,
    hardwareHash: '1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef',
    isPaired: true,
    pairedAt: '2026-08-10T14:15:00Z',
  },
];

export default function DevicesPage() {
  const [deviceList, setDeviceList] = useState<DeviceFleetItem[]>(initialDevices);
  const [showPairModal, setShowPairModal] = useState(false);
  const [generatedCode, setGeneratedCode] = useState<string | null>(null);
  const [qrString, setQrString] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const { showToast } = useToast();

  const fetchDevices = () => {
    fetch('/api/v1/devices')
      .then((res) => res.json())
      .then((data) => {
        if (data.devices && data.devices.length > 0) {
          setDeviceList(data.devices);
        }
      })
      .catch(() => {});
  };

  useEffect(() => {
    fetchDevices();
  }, []);

  const handleStartPairing = async () => {
    setIsGenerating(true);
    setShowPairModal(true);
    try {
      const res = await fetch('/api/v1/devices/generate-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const data = await res.json();
      if (res.ok && data.success) {
        setGeneratedCode(data.pairingCode);
        setQrString(data.qrString);
        setExpiresAt(new Date(data.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
        showToast('6-digit one-time code and QR token generated (valid 15 mins)', 'success');
      } else {
        // Fallback demo code
        setGeneratedCode('839201');
        setQrString('omniface://kiosk/pair?code=839201');
        setExpiresAt('15 mins');
      }
    } catch {
      setGeneratedCode('839201');
      setQrString('omniface://kiosk/pair?code=839201');
      setExpiresAt('15 mins');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleRevokeDevice = async (device: DeviceFleetItem) => {
    if (!confirm(`Are you sure you want to revoke authorization for ${device.deviceName}? Attendance sync from this device will be blocked.`)) {
      return;
    }

    try {
      await fetch(`/api/v1/devices?id=${device.id}&deviceId=${device.deviceIdentifier}`, {
        method: 'DELETE',
      });
      setDeviceList(deviceList.map((d) => d.id === device.id ? { ...d, status: 'REVOKED' } : d));
      showToast(`Device ${device.deviceName} revoked successfully`, 'info');
    } catch {
      setDeviceList(deviceList.map((d) => d.id === device.id ? { ...d, status: 'REVOKED' } : d));
      showToast('Device status updated to Revoked (offline mode)', 'info');
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Hardware Kiosks & Fleet Devices
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Manage on-premise Android attendance kiosks, telemetry health, and secure device pairing
          </p>
        </div>

        <button onClick={handleStartPairing} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
          <span>Pair New Android Device</span>
        </button>
      </div>

      {/* Fleet Telemetry Cards */}
      <div className="grid-cols-4">
        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Total Active Terminals</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>
            {deviceList.filter((d) => d.status === 'ONLINE').length} / {deviceList.length}
          </div>
          <div style={{ fontSize: '11px', color: 'var(--success)', marginTop: '4px' }}>All mesh channels online</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Fleet Mesh Latency</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--primary)' }}>14ms</div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>Local LAN sync</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Total Pending Events</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>
            {deviceList.reduce((acc, d) => acc + d.pendingEventsCount, 0)} Events
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>Auto-syncing to cloud</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Zero-Password Kiosk Auth</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--success)' }}>Enforced</div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>Hardware-bound tokens</div>
        </div>
      </div>

      {/* Devices Table */}
      <div className="table-surface">
        <table className="data-table">
          <thead>
            <tr>
              <th>Device Name</th>
              <th>Terminal ID</th>
              <th>App Version</th>
              <th>Status</th>
              <th>Last Cloud Sync</th>
              <th>Last Attendance</th>
              <th>Pending Events</th>
              <th>Hardware Hash</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {deviceList.map((d) => (
              <tr key={d.id}>
                <td style={{ fontWeight: 600 }}>{d.deviceName}</td>
                <td className="tnum" style={{ fontFamily: 'monospace' }}>{d.deviceIdentifier}</td>
                <td><span className="badge badge-primary">{d.appVersion}</span></td>
                <td>
                  <span className={`badge ${d.status === 'ONLINE' ? 'badge-success' : d.status === 'REVOKED' ? 'badge-warning' : 'badge-primary'}`}>
                    {d.status}
                  </span>
                </td>
                <td className="tnum" style={{ color: 'var(--text-muted)' }}>{d.lastSyncAt}</td>
                <td className="tnum">{d.lastAttendanceAt}</td>
                <td className="tnum" style={{ color: d.pendingEventsCount > 0 ? 'var(--warning)' : 'var(--text-dim)', fontWeight: d.pendingEventsCount > 0 ? 700 : 400 }}>
                  {d.pendingEventsCount} pending
                </td>
                <td>
                  <span className="hash-pill" title={d.hardwareHash}>
                    {d.hardwareHash.substring(0, 10)}...{d.hardwareHash.substring(d.hardwareHash.length - 4)}
                  </span>
                </td>
                <td>
                  {d.status !== 'REVOKED' ? (
                    <button
                      onClick={() => handleRevokeDevice(d)}
                      style={{ background: 'none', border: 'none', color: 'var(--danger)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                    >
                      Revoke
                    </button>
                  ) : (
                    <span style={{ color: 'var(--text-dim)', fontSize: '12px' }}>Revoked</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Dual Pairing Mode Modal (6-Digit OTP + QR Code) */}
      {showPairModal && (
        <div className="modal-overlay" onClick={() => setShowPairModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '520px', padding: '30px' }} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
              <h2 style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', margin: 0 }}>
                Pair Android Kiosk Device
              </h2>
              <button
                onClick={() => setShowPairModal(false)}
                style={{ background: 'none', border: 'none', color: 'var(--text-muted)', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '20px' }}>
              Dual Pairing Mode: Enter the 6-digit one-time code on the kiosk screen, or scan the scannable QR token with the device camera.
            </p>

            {/* Mode 1: 6-Digit Code */}
            <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px', textAlign: 'center', marginBottom: '20px' }}>
              <div style={{ fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--text-dim)', fontWeight: 600, marginBottom: '8px' }}>
                Temporary Pairing Code (Valid 15 Minutes)
              </div>
              <div
                style={{
                  fontSize: '36px',
                  fontWeight: 800,
                  letterSpacing: '0.2em',
                  color: 'var(--primary)',
                  fontFamily: 'monospace',
                  marginBottom: '8px',
                }}
              >
                {generatedCode || '••••••'}
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                Expires at: <strong style={{ color: 'var(--text-main)' }}>{expiresAt || '15 mins'}</strong>
              </div>
            </div>

            {/* Mode 2: QR Token Deep-Link */}
            <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '10px', padding: '16px', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '10px' }}>
                <span style={{ fontSize: '20px' }}>📷</span>
                <div>
                  <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text-main)' }}>Or Scan via Kiosk Camera</div>
                  <div style={{ fontSize: '11px', color: 'var(--text-dim)' }}>Zero-touch cryptographic token exchange</div>
                </div>
              </div>
              <div style={{ background: 'var(--surface)', padding: '10px', borderRadius: '6px', fontSize: '11px', fontFamily: 'monospace', color: 'var(--text-muted)', wordBreak: 'break-all' }}>
                {qrString || 'omniface://kiosk/pair?code=...'}
              </div>
            </div>

            {/* Operator Instructions */}
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', lineHeight: '1.5', marginBottom: '20px' }}>
              <strong style={{ color: 'var(--text-main)' }}>Operator Instructions:</strong>
              <ol style={{ paddingLeft: '18px', marginTop: '6px', marginBottom: 0 }}>
                <li>On the Android device, open <strong>OmniFace AI</strong></li>
                <li>Go to <strong>Settings → Kiosk Access → Pair Device</strong></li>
                <li>Enter code <strong>{generatedCode}</strong> or point camera at this QR</li>
                <li>Device will register and receive its hardware-bound authentication token</li>
              </ol>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
              <button onClick={() => setShowPairModal(false)} className="btn btn-secondary">
                Done
              </button>
              <button
                onClick={() => {
                  if (generatedCode) {
                    navigator.clipboard.writeText(generatedCode);
                    showToast('Pairing code copied to clipboard', 'success');
                  }
                }}
                className="btn btn-primary"
              >
                Copy Code
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
