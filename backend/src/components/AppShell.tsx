'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { SidebarNav } from '@/components/SidebarNav';
import { TopBarStatus } from '@/components/TopBarStatus';
import { ToastProvider } from '@/components/Toast';

export function AppShell({ children }: { children: React.ReactNode }) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const pathname = usePathname();

  const [tierInfo, setTierInfo] = useState<{ tier: string; badge: string; description: string }>({
    tier: 'PRO',
    badge: 'PRO ₹349',
    description: 'Up to 500 Rosters & 3 Devices',
  });
  const [orgName, setOrgName] = useState<string>('OmniFace Campus');

  useEffect(() => {
    async function loadOrgAndPlan() {
      try {
        const subRes = await fetch('/api/v1/subscriptions');
        if (subRes.ok) {
          const data = await subRes.json();
          const t = data.tier || 'FREE';
          if (t === 'FREE') {
            setTierInfo({ tier: 'FREE', badge: 'FREE ₹0', description: 'Up to 25 Rosters (Single Kiosk)' });
          } else if (t === 'PREMIUM') {
            setTierInfo({ tier: 'PREMIUM', badge: 'PREMIUM ₹199', description: 'Up to 250 Rosters (Cloud Sync)' });
          } else if (t === 'PRO') {
            setTierInfo({ tier: 'PRO', badge: 'PRO ₹349', description: 'Up to 500 Rosters & 3 Devices' });
          } else if (t === 'INSTITUTION') {
            setTierInfo({ tier: 'INSTITUTION', badge: 'INSTITUTION', description: '500+ Fleet & Multi-Admin' });
          }
          if (data.organization?.name) {
            setOrgName(data.organization.name);
          }
        }
      } catch {
        // Retain resilient state
      }
    }
    loadOrgAndPlan();
  }, []);

  // Close mobile drawer when route changes
  useEffect(() => {
    setSidebarOpen(false);
  }, [pathname]);

  // Keyboard accessibility: Close drawer on Escape
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSidebarOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // Allow full-viewport layout for login page without dashboard sidebar/header
  if (pathname === '/login') {
    return <ToastProvider>{children}</ToastProvider>;
  }

  return (
    <div className="app-container">
      {/* Mobile Backdrop Overlay */}
      {sidebarOpen && (
        <div
          className="sidebar-backdrop"
          onClick={() => setSidebarOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Sidebar Navigation */}
      <aside className={`sidebar ${sidebarOpen ? 'sidebar-open' : ''}`}>
        <div style={{ padding: '20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{ width: '36px', height: '36px', borderRadius: '10px', background: 'linear-gradient(135deg, #06b6d4, #3b82f6)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 800, fontSize: '18px' }}>
              Ω
            </div>
            <div>
              <div style={{ fontWeight: 700, fontSize: '15px', letterSpacing: '-0.02em', color: 'var(--text-main)' }}>OmniFace AI</div>
              <div style={{ fontSize: '11px', color: 'var(--text-dim)' }}>Fleet Console v1.0</div>
            </div>
          </div>

          <button
            className="mobile-sidebar-close"
            onClick={() => setSidebarOpen(false)}
            aria-label="Close navigation menu"
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--text-muted)',
              fontSize: '18px',
              cursor: 'pointer',
              padding: '6px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            ✕
          </button>
        </div>

        <SidebarNav onNavigate={() => setSidebarOpen(false)} />

        <div style={{ padding: '16px 20px', borderTop: '1px solid var(--border)', background: 'var(--surface-raised)' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
            <span style={{ fontSize: '11px', color: 'var(--text-dim)', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 600 }}>Plan Tier</span>
            <Link href="/subscription" style={{ textDecoration: 'none' }}>
              <span className="badge badge-primary">{tierInfo.badge}</span>
            </Link>
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{tierInfo.description}</div>
        </div>
      </aside>

      {/* Main Workspace Surface */}
      <main className="main-content">
        {/* Top Navigation Bar */}
        <header className="top-nav">
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
            {/* Hamburger Button for Mobile screens */}
            <button
              className="mobile-menu-toggle"
              onClick={() => setSidebarOpen(!sidebarOpen)}
              aria-label="Open navigation menu"
              aria-expanded={sidebarOpen}
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="3" y1="12" x2="21" y2="12" />
                <line x1="3" y1="6" x2="21" y2="6" />
                <line x1="3" y1="18" x2="21" y2="18" />
              </svg>
            </button>

            <span className="campus-title" style={{ fontWeight: 600, fontSize: '14px', color: 'var(--text-main)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {orgName}
            </span>
            <span className="campus-divider" style={{ color: 'var(--text-dim)' }}>/</span>
            <span className="campus-subtitle" style={{ color: 'var(--text-muted)', fontSize: '13px', whiteSpace: 'nowrap' }}>
              Academic Fleet
            </span>
          </div>

          <TopBarStatus />
        </header>

        {/* Injected Page Content */}
        <ToastProvider>
          <div className="page-body">
            {children}
          </div>
        </ToastProvider>

        {/* Global Accessible Legal & Trust Footer */}
        <footer
          style={{
            marginTop: 'auto',
            borderTop: '1px solid var(--border)',
            background: 'var(--surface)',
            padding: '24px 32px',
            fontSize: '12px',
            color: 'var(--text-dim)',
          }}
          className="global-footer"
        >
          <div
            style={{
              maxWidth: '1400px',
              margin: '0 auto',
              display: 'flex',
              flexDirection: 'column',
              gap: '12px',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: '12px',
              }}
            >
              <div>
                <strong style={{ color: 'var(--text-muted)' }}>OmniFace AI</strong> • Developed by Preetham (OmniFace Technologies, Karnataka, India) • Support:{' '}
                <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)', textDecoration: 'none' }}>
                  preethamdev05@gmail.com
                </a>
              </div>
              <nav aria-label="Legal navigation" style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
                <Link href="/privacy" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
                  Privacy Policy
                </Link>
                <Link href="/terms" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
                  Terms of Service
                </Link>
                <Link href="/cookies" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
                  Cookie Policy
                </Link>
                <Link href="/refund-policy" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
                  Refund Policy
                </Link>
              </nav>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', lineHeight: 1.5 }}>
              Notice: OmniFace operates as an on-premise software tool; institutions act as the sole Data Fiduciary under DPDP Act 2023. All third-party product names (Xiaomi, Samsung, Google Drive, Razorpay) are trademarks™ of their respective holders and imply no affiliation or endorsement.
            </div>
          </div>
        </footer>
      </main>
    </div>
  );
}
