'use client';

import React, { useState, useEffect } from 'react';
import { useToast } from '@/components/Toast';

type PlanTier = 'FREE' | 'PREMIUM' | 'PRO' | 'INSTITUTION';

interface InvoiceItem {
  id: string;
  invoiceNumber: string;
  amountInr: number;
  status: string;
  dueDate?: string;
  paidAt?: string;
  createdAt: string;
}

export default function SubscriptionPage() {
  const [activeTier, setActiveTier] = useState<PlanTier>('PRO');
  const [subStatus, setSubStatus] = useState<'ACTIVE' | 'GRACE_PERIOD' | 'ARCHIVE_READ_ONLY'>('ACTIVE');
  const [enrolledCount, setEnrolledCount] = useState(0);
  const [invoices, setInvoices] = useState<InvoiceItem[]>([]);
  const [showCheckoutModal, setShowCheckoutModal] = useState(false);
  const [selectedPlanForCheckout, setSelectedPlanForCheckout] = useState<PlanTier>('PRO');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [viewingInvoice, setViewingInvoice] = useState<InvoiceItem | null>(null);
  const { showToast } = useToast();

  // Institution lead form fields
  const [institutionForm, setInstitutionForm] = useState({
    organizationName: '',
    contactName: '',
    email: '',
    phone: '',
    expectedSeats: 500,
    notes: '',
  });

  // Google Play purchase token manual input
  const [purchaseTokenInput, setPurchaseTokenInput] = useState('');

  const fetchSubscription = () => {
    fetch('/api/v1/subscriptions')
      .then((res) => res.json())
      .then((data) => {
        if (data.subscription) {
          const tier = data.subscription.tier as PlanTier;
          if (tier) setActiveTier(tier);
          if (data.subscription.status) setSubStatus(data.subscription.status);
          if (data.subscription.enrolledCount !== undefined) {
            setEnrolledCount(data.subscription.enrolledCount);
          }
        }
        if (Array.isArray(data.invoices)) {
          setInvoices(data.invoices);
        } else {
          setInvoices([]);
        }
      })
      .catch((err) => {
        console.error('Failed to load subscription:', err);
      });
  };

  useEffect(() => {
    fetchSubscription();
  }, []);

  const getPlanPrice = (tier: PlanTier) => {
    switch (tier) {
      case 'FREE': return 0;
      case 'PREMIUM': return 199;
      case 'PRO': return 349;
      case 'INSTITUTION': return 0;
    }
  };

  const handleInstitutionSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!institutionForm.organizationName || !institutionForm.contactName || !institutionForm.email) {
      showToast('Please fill all required fields.', 'warning');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await fetch('/api/v1/institution/contact', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(institutionForm),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast('Institutional deployment inquiry submitted successfully!', 'success');
        setShowCheckoutModal(false);
        setInstitutionForm({
          organizationName: '',
          contactName: '',
          email: '',
          phone: '',
          expectedSeats: 500,
          notes: '',
        });
      } else {
        showToast(data.error || 'Failed to submit inquiry', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Network error submitting inquiry', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleGooglePlayTokenVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!purchaseTokenInput.trim()) {
      showToast('Please enter a valid Google Play purchase token.', 'warning');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await fetch('/api/v1/subscriptions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          tier: selectedPlanForCheckout,
          provider: 'GOOGLE_PLAY',
          purchaseToken: purchaseTokenInput.trim(),
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        showToast(`Verified! ${selectedPlanForCheckout} Plan activated.`, 'success');
        setShowCheckoutModal(false);
        setPurchaseTokenInput('');
        fetchSubscription();
      } else {
        showToast(data.error || 'Failed to verify Google Play purchase token', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Network error verifying purchase token', 'error');
    } finally {
      setIsSubmitting(false);
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
            Institutional plans, Google Play consumer billing, and custom enterprise deployments
          </p>
        </div>

        <button onClick={() => { setSelectedPlanForCheckout('PRO'); setShowCheckoutModal(true); }} className="btn btn-primary">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="1" y="4" width="22" height="16" rx="2" ry="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
          <span>Renew / Change Plan</span>
        </button>
      </div>

      {/* Capacity Warning Alert (Approaching 250 on Premium) */}
      {enrolledCount >= 240 && enrolledCount <= 250 && activeTier === 'PREMIUM' && (
        <div style={{ background: 'rgba(234, 179, 8, 0.12)', border: '1px solid rgba(234, 179, 8, 0.4)', borderRadius: '12px', padding: '16px 20px', marginBottom: '24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <span style={{ fontSize: '24px' }}>⚠️</span>
            <div>
              <strong style={{ color: 'var(--text-main)', fontSize: '14px' }}>Capacity Alert: {enrolledCount} / 250 Seats Enrolled</strong>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                You are approaching your plan limit. Upgrade to Pro (500 people, ₹349/mo) to prevent registration interruption.
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
            Enrolled: <strong style={{ color: 'var(--text-main)' }}>{enrolledCount} members</strong> • Grace Period Policy: <strong style={{ color: 'var(--text-main)' }}>14 Days Guaranteed</strong>
          </div>
        </div>

        <div style={{ textAlign: 'right' }}>
          <div className="tnum" style={{ fontSize: '32px', fontWeight: 800, color: 'var(--primary)', letterSpacing: '-0.03em' }}>
            {activeTier === 'INSTITUTION' ? 'Custom' : activeTier === 'PRO' ? '₹349' : activeTier === 'PREMIUM' ? '₹199' : '₹0'}
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
            <div style={{ fontSize: '12px', color: 'var(--text-dim)', textAlign: 'center', marginTop: 'auto' }}>
              {activeTier === 'FREE' ? 'Current Tier' : 'Default Starter Tier'}
            </div>
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
              {activeTier === 'PREMIUM' ? 'Active Plan' : 'Select Premium (₹199/mo)'}
            </button>
          </div>

          {/* Plan 3: Pro (₹349/month, Google Play) */}
          <div style={{ background: 'var(--surface-raised)', border: activeTier === 'PRO' ? '2px solid var(--primary)' : '1px solid rgba(6, 182, 212, 0.3)', borderRadius: '12px', padding: '24px', position: 'relative', display: 'flex', flexDirection: 'column' }}>
            <div style={{ position: 'absolute', top: '-11px', right: '16px', background: 'var(--primary)', color: '#03141e', padding: '2px 10px', borderRadius: '12px', fontSize: '10px', fontWeight: 700 }}>
              POPULAR
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Pro</h3>
              <span className="badge badge-success">500 USERS</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹349<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Google Play In-App Billing</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>500 users</strong> capacity</li>
              <li>✓ Everything in Premium</li>
              <li>✓ Multiple classes & sections</li>
              <li>✓ Advanced reports & audit log</li>
              <li>✓ Higher storage & sync limits</li>
              <li>✓ Priority support</li>
            </ul>
            <button
              onClick={() => { setSelectedPlanForCheckout('PRO'); setShowCheckoutModal(true); }}
              className={activeTier === 'PRO' ? 'btn btn-secondary' : 'btn btn-primary'}
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'PRO' ? 'Active Plan' : 'Select Pro (₹349/mo)'}
            </button>
          </div>

          {/* Plan 4: Institution (Custom pricing, 500+ users) */}
          <div style={{ background: 'var(--surface)', border: activeTier === 'INSTITUTION' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Institution</h3>
              <span className="badge badge-primary">500+ USERS</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '4px' }}>
              Custom<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> pricing</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Custom Pricing & Onboarding</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ <strong>500+ users</strong></li>
              <li>✓ <strong>Unlimited devices</strong></li>
              <li>✓ <strong>Multi-admin</strong></li>
              <li>✓ <strong>Departments</strong></li>
              <li>✓ <strong>Classes</strong></li>
              <li>✓ <strong>Staff roles</strong></li>
              <li>✓ <strong>Audit logs</strong></li>
              <li>✓ <strong>Advanced reporting</strong></li>
              <li>✓ <strong>Custom onboarding</strong></li>
              <li>✓ <strong>Custom pricing</strong></li>
            </ul>
            <button
              onClick={() => { setSelectedPlanForCheckout('INSTITUTION'); setShowCheckoutModal(true); }}
              className="btn btn-primary"
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              Contact Sales / Demo
            </button>
          </div>
        </div>
      </div>

      {/* Invoice History */}
      <div>
        <h2 style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>Invoices & Payment Records</h2>
        <div className="table-surface">
          {invoices.length === 0 ? (
            <div style={{ padding: '36px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
              No billing invoices on file. Invoices generated for custom institutional contracts or Google Play receipts will appear here.
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Invoice Number</th>
                  <th>Issued Date</th>
                  <th>Amount (INR)</th>
                  <th>Status</th>
                  <th>Receipt</th>
                </tr>
              </thead>
              <tbody>
                {invoices.map((inv) => (
                  <tr key={inv.id}>
                    <td className="tnum" style={{ fontWeight: 600 }}>{inv.invoiceNumber}</td>
                    <td>{new Date(inv.createdAt).toLocaleDateString()}</td>
                    <td className="tnum" style={{ fontWeight: 600 }}>₹{inv.amountInr}.00</td>
                    <td>
                      <span className={`badge ${inv.status === 'PAID' ? 'badge-success' : 'badge-warning'}`}>
                        {inv.status}
                      </span>
                    </td>
                    <td>
                      <button
                        onClick={() => setViewingInvoice(inv)}
                        style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                      >
                        View Receipt
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* Modal: Institution Lead Capture or Google Play Activation */}
      {showCheckoutModal && (
        <div className="modal-overlay" onClick={() => setShowCheckoutModal(false)}>
          <div className="modal-dialog" style={{ maxWidth: '520px', padding: '28px' }} onClick={(e) => e.stopPropagation()}>
            {selectedPlanForCheckout === 'INSTITUTION' ? (
              <div>
                <h2 style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
                  Institutional Deployment Inquiry
                </h2>
                <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '20px' }}>
                  For institutions with 500+ members, multi-branch kiosks, and custom compliance requirements.
                </p>

                <form onSubmit={handleInstitutionSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                  <div>
                    <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                      Organization / Institution Name <span style={{ color: 'var(--danger)' }}>*</span>
                    </label>
                    <input
                      required
                      type="text"
                      placeholder="e.g. National Institute of Technology"
                      value={institutionForm.organizationName}
                      onChange={(e) => setInstitutionForm({ ...institutionForm, organizationName: e.target.value })}
                      style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                    />
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <div>
                      <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                        Contact Person Name <span style={{ color: 'var(--danger)' }}>*</span>
                      </label>
                      <input
                        required
                        type="text"
                        placeholder="e.g. Dr. Rajesh Kumar"
                        value={institutionForm.contactName}
                        onChange={(e) => setInstitutionForm({ ...institutionForm, contactName: e.target.value })}
                        style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                      />
                    </div>
                    <div>
                      <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                        Official Email <span style={{ color: 'var(--danger)' }}>*</span>
                      </label>
                      <input
                        required
                        type="email"
                        placeholder="admin@nit.edu.in"
                        value={institutionForm.email}
                        onChange={(e) => setInstitutionForm({ ...institutionForm, email: e.target.value })}
                        style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                      />
                    </div>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <div>
                      <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                        Phone Number
                      </label>
                      <input
                        type="tel"
                        placeholder="+91 98765 43210"
                        value={institutionForm.phone}
                        onChange={(e) => setInstitutionForm({ ...institutionForm, phone: e.target.value })}
                        style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                      />
                    </div>
                    <div>
                      <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                        Expected Seats
                      </label>
                      <input
                        type="number"
                        min="100"
                        step="50"
                        value={institutionForm.expectedSeats}
                        onChange={(e) => setInstitutionForm({ ...institutionForm, expectedSeats: parseInt(e.target.value) || 500 })}
                        style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)' }}
                      />
                    </div>
                  </div>

                  <div>
                    <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                      Deployment Requirements / Notes
                    </label>
                    <textarea
                      rows={3}
                      placeholder="e.g. 8 turnstiles across 2 campuses, on-premise attendance sync requirements."
                      value={institutionForm.notes}
                      onChange={(e) => setInstitutionForm({ ...institutionForm, notes: e.target.value })}
                      style={{ width: '100%', padding: '8px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)', fontSize: '13px' }}
                    />
                  </div>

                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                    <button type="button" onClick={() => setShowCheckoutModal(false)} className="btn btn-secondary">
                      Cancel
                    </button>
                    <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                      {isSubmitting ? 'Submitting...' : 'Submit Institutional Inquiry'}
                    </button>
                  </div>
                </form>
              </div>
            ) : (
              <div>
                <h2 style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
                  {selectedPlanForCheckout} Plan Activation
                </h2>
                <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '16px' }}>
                  OmniFace AI {selectedPlanForCheckout} Plan (₹{getPlanPrice(selectedPlanForCheckout)} / mo)
                </p>

                <div style={{ background: 'var(--surface-raised)', padding: '16px', borderRadius: '8px', marginBottom: '18px', border: '1px solid var(--border)', fontSize: '13px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', color: 'var(--text-main)', fontWeight: 600 }}>
                    <span>📱</span>
                    <span>Direct In-App Subscription</span>
                  </div>
                  <p style={{ color: 'var(--text-muted)', margin: 0, lineHeight: 1.5 }}>
                    Consumer plans (Premium & Pro) can be subscribed directly with 1-tap on your paired Android Kiosk tablet via Google Play Store billing.
                  </p>
                </div>

                <form onSubmit={handleGooglePlayTokenVerify} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                  <div>
                    <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-main)', fontWeight: 600, marginBottom: '6px' }}>
                      Verify Google Play Purchase Token
                    </label>
                    <input
                      type="text"
                      placeholder="Paste purchase token from Play Store receipt..."
                      value={purchaseTokenInput}
                      onChange={(e) => setPurchaseTokenInput(e.target.value)}
                      style={{ width: '100%', padding: '10px 12px', background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-main)', fontSize: '13px' }}
                    />
                    <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>
                      Tokens are validated against Google Play Developer verification services.
                    </div>
                  </div>

                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                    <button type="button" onClick={() => setShowCheckoutModal(false)} className="btn btn-secondary">
                      Close
                    </button>
                    <button type="submit" disabled={isSubmitting} className="btn btn-primary">
                      {isSubmitting ? 'Verifying...' : `Verify & Activate ${selectedPlanForCheckout}`}
                    </button>
                  </div>
                </form>
              </div>
            )}
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
                <div style={{ fontWeight: 700, color: '#0284c7' }}>{viewingInvoice.invoiceNumber}</div>
                <div style={{ fontSize: '11px', color: '#64748b' }}>Date: {new Date(viewingInvoice.createdAt).toLocaleDateString()}</div>
              </div>
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
                  <td style={{ padding: '8px' }}>OmniFace License</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>₹{viewingInvoice.amountInr}.00</td>
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
