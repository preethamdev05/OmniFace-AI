'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

type PlanTier = 'FREE' | 'PREMIUM' | 'PRO' | 'INSTITUTION';

export default function SubscriptionPage() {
  const [activeTier, setActiveTier] = useState<PlanTier>('PRO');
  const [subStatus, setSubStatus] = useState<'ACTIVE' | 'GRACE_PERIOD' | 'ARCHIVE_READ_ONLY'>('ACTIVE');
  const [enrolledCount, setEnrolledCount] = useState(247);
  const [showCheckoutModal, setShowCheckoutModal] = useState(false);
  const [selectedPlanForCheckout, setSelectedPlanForCheckout] = useState<PlanTier>('PRO');
  const [processingPayment, setProcessingPayment] = useState(false);
  const [viewingInvoice, setViewingInvoice] = useState<string | null>(null);
  const { showToast } = useToast();

  useEffect(() => {
    fetch('/api/v1/subscriptions')
      .then((res) => res.json())
      .then((data) => {
        if (data.subscription) {
          const tier = data.subscription.tier as PlanTier;
          if (tier) setActiveTier(tier);
          if (data.subscription.status) setSubStatus(data.subscription.status);
          if (data.subscription.enrolledCount) setEnrolledCount(data.subscription.enrolledCount);
        }
      })
      .catch(() => {});
  }, []);

  const getPlanPrice = (tier: PlanTier) => {
    switch (tier) {
      case 'FREE': return 0;
      case 'PREMIUM': return 199;
      case 'PRO': return 399;
      case 'INSTITUTION': return 1499;
    }
  };

  const handleSimulatePayment = async () => {
    setProcessingPayment(true);
    const amount = getPlanPrice(selectedPlanForCheckout);
    try {
      const res = await fetch('/api/v1/subscriptions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          orgId: '00000000-0000-0000-0000-000000000001',
          tier: selectedPlanForCheckout,
          provider: selectedPlanForCheckout === 'INSTITUTION' ? 'WEBSITE_CUSTOM' : 'GOOGLE_PLAY',
          purchaseToken: 'sub_tok_' + Math.random().toString(36).substring(2, 12),
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        setActiveTier(selectedPlanForCheckout);
        setSubStatus('ACTIVE');
        setShowCheckoutModal(false);
        showToast(`Payment successful! ${selectedPlanForCheckout} Plan activated.`, 'success');
      } else {
        setActiveTier(selectedPlanForCheckout);
        setShowCheckoutModal(false);
        showToast(`${selectedPlanForCheckout} tier activated!`, 'info');
      }
    } catch {
      setActiveTier(selectedPlanForCheckout);
      setShowCheckoutModal(false);
      showToast(`${selectedPlanForCheckout} tier activated (offline mode)`, 'info');
    } finally {
      setProcessingPayment(false);
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Subscription & Institutional Licensing
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Institutional plans, Google Play consumer billing, and custom institutional invoices
          </p>
        </div>

        <button onClick={() => setShowCheckoutModal(true)} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="1" y="4" width="22" height="16" rx="2" ry="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
          <span>Renew / Change Plan</span>
        </button>
      </div>

      {/* Upgrade Funnel Warning Alert (247 / 250 capacity warning) */}
      {enrolledCount >= 240 && enrolledCount <= 250 && activeTier === 'PREMIUM' && (
        <div style={{ background: 'rgba(234, 179, 8, 0.12)', border: '1px solid rgba(234, 179, 8, 0.4)', borderRadius: '12px', padding: '16px 20px', marginBottom: '24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <span style={{ fontSize: '24px' }}>⚠️</span>
            <div>
              <strong style={{ color: 'var(--text-main)', fontSize: '14px' }}>Capacity Alert: {enrolledCount} / 250 Seats Enrolled</strong>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                You are approaching your plan limit. Upgrade to Pro (500 people, ₹399/mo) to prevent registration interruption.
              </div>
            </div>
          </div>
          <button onClick={() => { setSelectedPlanForCheckout('PRO'); setShowCheckoutModal(true); }} className="btn btn-primary" style={{ padding: '6px 14px', fontSize: '12px' }}>
            Upgrade to Pro →
          </button>
        </div>
      )}

      {/* 500 / 500 Capacity Alert -> Show Institution Option */}
      {enrolledCount >= 490 && (activeTier === 'PRO' || activeTier === 'PREMIUM') && (
        <div style={{ background: 'rgba(6, 182, 212, 0.12)', border: '1px solid rgba(6, 182, 212, 0.4)', borderRadius: '12px', padding: '16px 20px', marginBottom: '24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <span style={{ fontSize: '24px' }}>🏛️</span>
            <div>
              <strong style={{ color: 'var(--text-main)', fontSize: '14px' }}>Enterprise Deployment: 500+ People Threshold</strong>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                For large campuses requiring multi-branch kiosks, custom SLAs, and high volume seats, deploy the custom Institution Plan.
              </div>
            </div>
          </div>
          <button onClick={() => { setSelectedPlanForCheckout('INSTITUTION'); setShowCheckoutModal(true); }} className="btn btn-primary" style={{ padding: '6px 14px', fontSize: '12px' }}>
            Inquire Institution Plan →
          </button>
        </div>
      )}

      {/* 14-Day Grace Period & Archive Notice */}
      {subStatus === 'GRACE_PERIOD' && (
        <div style={{ background: 'rgba(239, 68, 68, 0.12)', border: '1px solid rgba(239, 68, 68, 0.4)', borderRadius: '12px', padding: '16px 20px', marginBottom: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <span style={{ fontSize: '24px' }}>⏳</span>
            <div>
              <strong style={{ color: 'var(--danger)', fontSize: '14px' }}>Subscription Expired: 14-Day Full Grace Period Active</strong>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                Attendance continues without interruption during the 14-day grace window. After expiration, the organization transitions to Read-Only Archive Mode (historical registers remain exportable, but new kiosk sync is paused).
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Current Active Plan Status Banner */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px', marginBottom: '32px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px' }}>
            <span style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)' }}>
              OmniFace {activeTier === 'PRO' ? 'Pro' : activeTier === 'PREMIUM' ? 'Premium' : activeTier === 'INSTITUTION' ? 'Institution' : 'Free Starter'} Plan
            </span>
            <span className="badge badge-success">
              {subStatus === 'ACTIVE' ? 'ACTIVE & LICENSED' : subStatus === 'GRACE_PERIOD' ? 'GRACE PERIOD' : 'READ-ONLY ARCHIVE'}
            </span>
          </div>
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', maxWidth: '600px' }}>
            {activeTier === 'INSTITUTION'
              ? 'Institutional deployment supporting 500+ people, multi-branch kiosks, custom administrators, departments, API, and organization-wide cloud sync.'
              : activeTier === 'PRO'
              ? 'Pro plan supporting up to 500 people, multiple classes/sections, advanced reports, and priority support.'
              : activeTier === 'PREMIUM'
              ? 'Premium plan supporting up to 250 people, cloud backup, multi-device synchronization, Excel/PDF exports, and ad-free operation.'
              : 'Free Starter plan supporting up to 25 people for local offline kiosk attendance.'}
          </div>
          <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--text-dim)' }}>
            Next Renewal Date: <strong style={{ color: 'var(--text-main)' }}>October 10, 2026</strong> • Grace Period Policy: <strong style={{ color: 'var(--text-main)' }}>14 Days Guaranteed</strong>
          </div>
        </div>

        <div style={{ textAlign: 'right' }}>
          <div className="tnum" style={{ fontSize: '32px', fontWeight: 800, color: 'var(--primary)', letterSpacing: '-0.03em' }}>
            {activeTier === 'INSTITUTION' ? 'Custom' : activeTier === 'PRO' ? '₹399' : activeTier === 'PREMIUM' ? '₹199' : '₹0'}
            {activeTier !== 'INSTITUTION' && <span style={{ fontSize: '14px', fontWeight: 500, color: 'var(--text-muted)' }}> / month</span>}
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>
            Billing: {activeTier === 'INSTITUTION' ? 'Website / Custom Sales' : activeTier === 'FREE' ? 'Free Forever' : 'Google Play Billing'}
          </div>
        </div>
      </div>

      {/* 4 Official Plans Grid */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>Official Commercial Plans</h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '20px' }}>
          {/* Plan 1: Free */}
          <div style={{ background: 'var(--surface)', border: activeTier === 'FREE' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Free</h3>
              <span className="badge badge-warning">25 PEOPLE</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '4px' }}>
              ₹0<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / forever</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Single Kiosk Attendance</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>25 people</strong> capacity</li>
              <li>✓ Offline face recognition</li>
              <li>✓ Attendance marking</li>
              <li>✓ Local SQLite history</li>
              <li>✗ No cloud backup & sync</li>
              <li>✗ No web dashboard</li>
              <li>✗ In-app ads displayed</li>
            </ul>
            <button
              onClick={() => { setActiveTier('FREE'); showToast('Downgraded to Free Starter', 'info'); }}
              className="btn btn-secondary"
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'FREE' ? 'Active Plan' : 'Downgrade to Free'}
            </button>
          </div>

          {/* Plan 2: Premium (₹199/month, Google Play) */}
          <div style={{ background: 'var(--surface)', border: activeTier === 'PREMIUM' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Premium</h3>
              <span className="badge badge-primary">250 PEOPLE</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹199<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Google Play 1-Tap Subscription</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>250 people</strong> capacity</li>
              <li>✓ Everything in Free</li>
              <li>✓ Cloud backup & sync queue</li>
              <li>✓ Multi-device synchronization</li>
              <li>✓ Excel export & PDF reports</li>
              <li>✓ <strong>100% Ad-Free</strong></li>
              <li>✓ Automatic backup / sync</li>
            </ul>
            <button
              onClick={() => { setSelectedPlanForCheckout('PREMIUM'); setShowCheckoutModal(true); }}
              className={activeTier === 'PREMIUM' ? 'btn btn-secondary' : 'btn btn-primary'}
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'PREMIUM' ? 'Active on Google Play' : 'Subscribe (₹199 / mo)'}
            </button>
          </div>

          {/* Plan 3: Pro (₹399/month, Google Play) */}
          <div style={{ background: 'var(--surface-raised)', border: activeTier === 'PRO' ? '2px solid var(--primary)' : '1px solid rgba(6, 182, 212, 0.3)', borderRadius: '12px', padding: '24px', position: 'relative', display: 'flex', flexDirection: 'column' }}>
            <div style={{ position: 'absolute', top: '-11px', right: '16px', background: 'var(--primary)', color: '#03141e', padding: '2px 10px', borderRadius: '12px', fontSize: '10px', fontWeight: 700 }}>
              POPULAR
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Pro</h3>
              <span className="badge badge-success">500 PEOPLE</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹399<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Google Play Subscription</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>500 people</strong> capacity</li>
              <li>✓ Everything in Premium</li>
              <li>✓ More hardware devices</li>
              <li>✓ Multiple classes & sections</li>
              <li>✓ Advanced aggregated reports</li>
              <li>✓ Higher storage & sync limits</li>
              <li>✓ Priority support</li>
            </ul>
            <button
              onClick={() => { setSelectedPlanForCheckout('PRO'); setShowCheckoutModal(true); }}
              className="btn btn-primary"
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'PRO' ? 'Current Pro Plan (₹399)' : 'Upgrade to Pro (₹399 / mo)'}
            </button>
          </div>

          {/* Plan 4: Institution (500+ people, Custom Website Billing) */}
          <div style={{ background: 'var(--surface-raised)', border: activeTier === 'INSTITUTION' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', position: 'relative', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Institution</h3>
              <span className="badge badge-primary">500+ SEATS</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              Custom<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / quote</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Website Sales & GST Invoicing</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ <strong>500+ people</strong> tailored deployment</li>
              <li>✓ Full Web Dashboard administrative center</li>
              <li>✓ Multiple administrators & staff roles</li>
              <li>✓ Departments, classes & sections</li>
              <li>✓ Multiple attendance devices & management</li>
              <li>✓ REST API access & device pairing</li>
              <li>✓ Statutory audit logs & dedicated SLA</li>
            </ul>
            <button
              onClick={() => { setSelectedPlanForCheckout('INSTITUTION'); setShowCheckoutModal(true); }}
              className="btn btn-primary"
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'INSTITUTION' ? 'Manage Institution Plan' : 'Contact Sales / Custom Quote'}
            </button>
          </div>
        </div>
      </div>

      {/* Invoice History */}
      <div>
        <h2 style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>Invoices & Payment Records</h2>
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th>Invoice Number</th>
                <th>Period</th>
                <th>Plan Tier</th>
                <th>Amount (INR)</th>
                <th>Channel</th>
                <th>Status</th>
                <th>Receipt</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="tnum" style={{ fontWeight: 600 }}>INV-2026-09-8812</td>
                <td>Sep 10, 2026 – Oct 10, 2026</td>
                <td>Pro (500 Seats)</td>
                <td className="tnum" style={{ fontWeight: 600 }}>₹399.00</td>
                <td>Google Play Billing</td>
                <td><span className="badge badge-success">PAID</span></td>
                <td>
                  <button
                    onClick={() => setViewingInvoice('INV-2026-09-8812')}
                    style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                  >
                    View Receipt
                  </button>
                </td>
              </tr>
              <tr>
                <td className="tnum" style={{ fontWeight: 600 }}>INV-2026-08-7401</td>
                <td>Aug 10, 2026 – Sep 10, 2026</td>
                <td>Premium (250 Seats)</td>
                <td className="tnum" style={{ fontWeight: 600 }}>₹199.00</td>
                <td>Google Play Billing</td>
                <td><span className="badge badge-success">PAID</span></td>
                <td>
                  <button
                    onClick={() => setViewingInvoice('INV-2026-08-7401')}
                    style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                  >
                    View Receipt
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* Checkout Modal */}
      {showCheckoutModal && (
        <div className="modal-overlay" onClick={() => setShowCheckoutModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '480px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
              {selectedPlanForCheckout === 'INSTITUTION' ? 'Institution Plan Inquiry' : 'Plan Activation'}
            </h2>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '20px' }}>
              OmniFace AI {selectedPlanForCheckout} Plan
            </p>

            <div style={{ background: 'var(--surface)', padding: '16px', borderRadius: '8px', marginBottom: '16px', border: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '13px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Selected Plan:</span>
                <strong style={{ color: 'var(--text-main)' }}>{selectedPlanForCheckout}</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '13px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Price:</span>
                <strong style={{ color: 'var(--primary)' }}>
                  {selectedPlanForCheckout === 'INSTITUTION' ? 'Custom Quote' : `₹${getPlanPrice(selectedPlanForCheckout)} / mo`}
                </strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Billing Channel:</span>
                <span style={{ color: 'var(--text-main)' }}>
                  {selectedPlanForCheckout === 'INSTITUTION' ? 'Website / Custom Sales' : 'Google Play Store'}
                </span>
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button onClick={() => setShowCheckoutModal(false)} className="btn btn-secondary">
                Cancel
              </button>
              <button onClick={handleSimulatePayment} disabled={processingPayment} className="btn btn-primary">
                {processingPayment ? 'Processing...' : selectedPlanForCheckout === 'INSTITUTION' ? 'Request Institutional Quote' : `Activate ${selectedPlanForCheckout}`}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Invoice PDF Preview Modal */}
      {viewingInvoice && (
        <div className="modal-overlay" onClick={() => setViewingInvoice(null)}>
          <div className="modal-dialog" style={{ background: '#ffffff', color: '#0f172a', maxWidth: '540px', padding: '30px' }} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '2px solid #e2e8f0', paddingBottom: '16px', marginBottom: '16px' }}>
              <div>
                <h3 style={{ fontSize: '18px', fontWeight: 800, margin: 0, color: '#0f172a' }}>SUBSCRIPTION RECEIPT</h3>
                <div style={{ fontSize: '11px', color: '#64748b' }}>OmniFace AI Technologies • India</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontWeight: 700, color: '#0284c7' }}>{viewingInvoice}</div>
                <div style={{ fontSize: '11px', color: '#64748b' }}>Date: Sep 10, 2026</div>
              </div>
            </div>

            <div style={{ fontSize: '13px', marginBottom: '16px' }}>
              <div style={{ color: '#64748b', fontSize: '11px' }}>Organization:</div>
              <div style={{ fontWeight: 700 }}>National Institute of Technology</div>
            </div>

            <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '16px', fontSize: '12px' }}>
              <thead>
                <tr style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                  <th style={{ padding: '8px', textAlign: 'left' }}>Item</th>
                  <th style={{ padding: '8px', textAlign: 'right' }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td style={{ padding: '8px' }}>OmniFace Pro Monthly License (500 Seats)</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>₹399.00</td>
                </tr>
              </tbody>
            </table>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button onClick={() => window.print()} style={{ padding: '6px 14px', borderRadius: '6px', border: '1px solid #cbd5e1', background: '#f8fafc', cursor: 'pointer' }}>
                Print
              </button>
              <button onClick={() => setViewingInvoice(null)} className="btn btn-primary" style={{ padding: '6px 14px' }}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
