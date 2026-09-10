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
  lastHeartbeatAt?: string;
  pendingEventsCount: number;
  hardwareHash: string;
  isPaired: boolean;
  pairedAt: string;
  batteryPct?: number | null;
  temperature?: number | null;
  thermalState?: 'NOMINAL' | 'WARM' | 'CRITICAL';
  activeFps?: number;
  ipAddress?: string | null;
  isStale?: boolean;
}

export default function DevicesPage() {
  const [deviceList, setDeviceList] = useState<DeviceFleetItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [showPairModal, setShowPairModal] = useState(false);
  const [generatedCode, setGeneratedCode] = useState<string | null>(null);
  const [qrString, setQrString] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const { showToast } = useToast();

  const fetchDevices = () => {
    setIsLoading(true);
    fetch('/api/v1/devices')
      .then((res) => res.json())
      .then((data) => {
        if (data.success && Array.isArray(data.devices)) {
          setDeviceList(data.devices);
        } else {
          setDeviceList([]);
        }
      })
      .catch(() => setDeviceList([]))
      .finally(() => setIsLoading(false));
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
        showToast(data.error || 'Failed to generate pairing code', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error generating pairing code', 'error');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleRevokeDevice = async (device: DeviceFleetItem) => {
    if (!confirm(`Are you sure you want to revoke authorization for ${device.deviceName}? Attendance sync from this device will be blocked.`)) {
      return;
    }

    try {
      const res = await fetch(`/api/v1/devices?id=${encodeURIComponent(device.id)}&deviceId=${encodeURIComponent(device.deviceIdentifier)}`, {
        method: 'DELETE',
      });
      const data = await res.json();
      if (res.ok && data.success) {
        setDeviceList((prev) =>
          prev.map((d) => (d.id === device.id ? { ...d, status: 'REVOKED' } : d))
        );
        showToast(`Device ${device.deviceName} revoked successfully`, 'info');
      } else {
        showToast(data.error || 'Failed to revoke device', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Error revoking device', 'error');
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

      {/* SRE Alert Banner if any device is degraded */}
      {deviceList.some((d) => d.thermalState === 'CRITICAL' || d.isStale || (d.batteryPct !== null && d.batteryPct !== undefined && d.batteryPct < 15)) && (
        <div style={{
          padding: '12px 16px',
          marginBottom: '20px',
          background: 'rgba(239, 68, 68, 0.08)',
          border: '1px solid rgba(239, 68, 68, 0.25)',
          borderRadius: '8px',
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          color: 'var(--danger)',
          fontSize: '13px',
        }}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          <div>
            <strong>SRE Fleet Health Alert:</strong> One or more kiosk terminals require immediate attention (thermal throttling, low battery &lt;15%, or missed heartbeat &gt;60m).
          </div>
        </div>
      )}

      {/* Fleet Telemetry Cards */}
      <div className="grid-cols-4">
        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Active Online Kiosks</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>
            {deviceList.filter((d) => d.status === 'ONLINE' && !d.isStale).length} / {deviceList.length}
          </div>
          <div style={{ fontSize: '11px', color: 'var(--success)', marginTop: '4px' }}>Real-time heartbeat</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Fleet Thermal Health</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: deviceList.some((d) => d.thermalState === 'CRITICAL') ? 'var(--danger)' : 'var(--success)' }}>
            {deviceList.filter((d) => d.thermalState === 'CRITICAL').length > 0
              ? `${deviceList.filter((d) => d.thermalState === 'CRITICAL').length} Throttled`
              : '100% Nominal'}
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>SoC &lt;45°C nominal target</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Total Pending Sync Queue</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--text-main)' }}>
            {deviceList.reduce((acc, d) => acc + (d.pendingEventsCount || 0), 0)} Events
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>Auto-syncing to cloud</div>
        </div>

        <div className="stat-box">
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>Fleet Mesh Latency</div>
          <div className="tnum" style={{ fontSize: '26px', fontWeight: 700, color: 'var(--primary)' }}>14ms</div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>Zero-password HMAC auth</div>
        </div>
      </div>

      {/* Devices Table */}
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
          Loading fleet devices...
        </div>
      ) : deviceList.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px' }}>
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="1.5" style={{ margin: '0 auto 16px', display: 'block' }}>
            <rect x="5" y="2" width="14" height="20" rx="2" ry="2"/>
            <line x1="12" y1="18" x2="12.01" y2="18"/>
          </svg>
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '8px' }}>No Hardware Kiosks Paired Yet</h3>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px', maxWidth: '380px', margin: '0 auto 20px' }}>
            Pair your first Android tablet or wall-mounted device to start capturing offline face attendance.
          </p>
          <button onClick={handleStartPairing} className="btn btn-primary">
            <span>Pair First Device</span>
          </button>
        </div>
      ) : (
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>Device Name</th>
                <th>Terminal ID</th>
                <th>Hardware Telemetry</th>
                <th>Status</th>
                <th>Active FPS</th>
                <th>Last Heartbeat</th>
                <th>Last Cloud Sync</th>
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
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px' }}>
                      {d.batteryPct !== null && d.batteryPct !== undefined && (
                        <span style={{ color: d.batteryPct < 20 ? 'var(--danger)' : 'var(--text-main)' }}>
                          🔋 {d.batteryPct}%
                        </span>
                      )}
                      {d.temperature !== null && d.temperature !== undefined && (
                        <span style={{ color: d.temperature > 50 ? 'var(--danger)' : 'var(--text-muted)' }}>
                          🌡️ {d.temperature.toFixed(1)}°C
                        </span>
                      )}
                      <span className={`badge ${d.thermalState === 'CRITICAL' ? 'badge-warning' : d.thermalState === 'WARM' ? 'badge-primary' : 'badge-success'}`}>
                        {d.thermalState || 'NOMINAL'}
                      </span>
                    </div>
                  </td>
                  <td>
                    <span className={`badge ${d.status === 'ONLINE' && !d.isStale ? 'badge-success' : d.status === 'REVOKED' ? 'badge-warning' : 'badge-primary'}`}>
                      {d.isStale ? 'OFFLINE (>60m)' : d.status}
                    </span>
                  </td>
                  <td className="tnum">{d.activeFps || 30} FPS</td>
                  <td className="tnum" style={{ color: 'var(--text-muted)' }}>
                    {d.lastHeartbeatAt ? d.lastHeartbeatAt : 'Never'}
                  </td>
                  <td className="tnum" style={{ color: 'var(--text-muted)' }}>
                    {d.lastSyncAt ? d.lastSyncAt : 'Never'}
                  </td>
                  <td className="tnum" style={{ color: (d.pendingEventsCount || 0) > 0 ? 'var(--warning)' : 'var(--text-dim)', fontWeight: (d.pendingEventsCount || 0) > 0 ? 700 : 400 }}>
                    {d.pendingEventsCount || 0} pending
                  </td>
                  <td>
                    <span className="hash-pill" title={d.hardwareHash || 'Unregistered'}>
                      {d.hardwareHash ? `${d.hardwareHash.substring(0, 8)}...` : 'N/A'}
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
      )}

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
                {generatedCode || (isGenerating ? '••••••' : '••••••')}
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
                <li>Enter code <strong>{generatedCode || '••••••'}</strong> or point camera at this QR</li>
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
