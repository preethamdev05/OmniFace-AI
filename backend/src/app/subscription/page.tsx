'use client';

import React, { useState } from 'react';
import { useToast } from '@/components/Toast';

export default function SubscriptionPage() {
  const [activeTier, setActiveTier] = useState<'BUSINESS' | 'PROFESSIONAL' | 'PREMIUM' | 'FREE'>('BUSINESS');
  const [showCheckoutModal, setShowCheckoutModal] = useState(false);
  const [selectedPlanForCheckout, setSelectedPlanForCheckout] = useState<'BUSINESS' | 'PROFESSIONAL' | 'PREMIUM'>('PROFESSIONAL');
  const [processingPayment, setProcessingPayment] = useState(false);
  const [viewingInvoice, setViewingInvoice] = useState<string | null>(null);
  const { showToast } = useToast();

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowCheckoutModal(false);
        setViewingInvoice(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const getPlanPrice = (tier: string) => {
    if (tier === 'BUSINESS') return 999;
    if (tier === 'PROFESSIONAL') return 499;
    if (tier === 'PREMIUM') return 199;
    return 0;
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
          provider: 'RAZORPAY',
          razorpayPaymentId: 'pay_' + Math.random().toString(36).substring(2, 12),
        }),
      });

      const data = await res.json();
      if (res.ok && data.success) {
        setActiveTier(selectedPlanForCheckout);
        setShowCheckoutModal(false);
        showToast(`Payment successful! ₹${amount} ${selectedPlanForCheckout} Plan activated with instant GST Tax Invoice.`, 'success');
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
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 700, letterSpacing: '-0.03em', color: 'var(--text-main)', marginBottom: '4px' }}>
            Subscription & Institutional Licensing
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            Manage commercial subscription tiers, Razorpay payment methods, and GST tax invoices
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={() => setShowCheckoutModal(true)} className="btn btn-primary">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="1" y="4" width="22" height="16" rx="2" ry="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
            <span>Renew / Upgrade Plan</span>
          </button>
        </div>
      </div>

      {/* Current Active Plan Status Banner */}
      <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px', marginBottom: '32px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px' }}>
            <span style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)' }}>OmniFace Business Tier</span>
            <span className="badge badge-success">ACTIVE & LICENSED</span>
          </div>
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', maxWidth: '600px' }}>
            Enterprise license supporting unlimited students, multi-department classes, multi-admin access, full Web Dashboard, and high-frequency cryptographic fleet sync.
          </div>
          <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--text-dim)' }}>
            Next Auto-Renewal: <strong style={{ color: 'var(--text-main)' }}>October 10, 2026</strong> • Billing Provider: <strong style={{ color: 'var(--text-main)' }}>Razorpay (UPI / NetBanking)</strong>
          </div>
        </div>

        <div style={{ textAlign: 'right' }}>
          <div className="tnum" style={{ fontSize: '32px', fontWeight: 800, color: 'var(--primary)', letterSpacing: '-0.03em' }}>
            ₹999<span style={{ fontSize: '14px', fontWeight: 500, color: 'var(--text-muted)' }}> / month</span>
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px' }}>GST 18% inclusive</div>
        </div>
      </div>

      {/* Strategic Billing & Channel Architecture Banner */}
      <div
        style={{
          background: 'rgba(6, 182, 212, 0.05)',
          border: '1px solid rgba(6, 182, 212, 0.25)',
          borderRadius: '12px',
          padding: '16px 20px',
          marginBottom: '28px',
          display: 'flex',
          alignItems: 'center',
          gap: '16px',
          fontSize: '13px',
        }}
      >
        <div
          style={{
            width: '36px',
            height: '36px',
            borderRadius: '8px',
            background: 'var(--primary-glow)',
            color: 'var(--primary)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: '18px',
            flexShrink: 0,
          }}
        >
          💳
        </div>
        <div style={{ flex: 1, lineHeight: 1.5 }}>
          <strong style={{ color: 'var(--text-main)' }}>Channel & Invoicing Architecture:</strong>{' '}
          <span style={{ color: 'var(--text-muted)' }}>
            <strong>₹199 Premium</strong> is billed via <strong>Google Play 1-Tap Subscription</strong> inside the Android app (ideal for individual tutors).{' '}
            <strong>₹499 Professional</strong> and <strong>₹999 Business</strong> are billed directly through this <strong>Web Console via Razorpay</strong> to provide formal 18% GST Tax Invoices, institutional receipts, and support for corporate bank transfers/NEFT/UPI.
          </span>
        </div>
      </div>

      {/* Plan Comparison Grid */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>Commercial Tiers Overview</h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '20px' }}>
          {/* Free Tier */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '24px', opacity: activeTier === 'FREE' ? 1 : 0.85, display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Free Plan</h3>
              <span className="badge badge-warning">STARTER</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '4px' }}>
              ₹0<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / forever</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Free Android App Download</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>25 people</strong> capacity</li>
              <li>✓ On-device face registration</li>
              <li>✓ High-accuracy biometric scanning</li>
              <li>✓ Local SQLite history</li>
              <li>✗ Cloud backup & sync locked</li>
              <li>✗ Excel/PDF reports locked</li>
              <li>✗ In-app promotional banners</li>
            </ul>
            {activeTier !== 'FREE' ? (
              <button
                onClick={() => {
                  setActiveTier('FREE');
                  showToast('Downgraded to Free Starter Tier', 'info');
                }}
                className="btn btn-secondary"
                style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
              >
                Downgrade to Free
              </button>
            ) : (
              <div style={{ textAlign: 'center', fontSize: '12px', color: 'var(--text-dim)', padding: '8px', marginTop: 'auto' }}>Active Plan</div>
            )}
          </div>

          {/* Premium Tier */}
          <div style={{ background: 'var(--surface)', border: activeTier === 'PREMIUM' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Premium Plan</h3>
              <span className="badge badge-primary" style={{ fontSize: '10px' }}>PLAY STORE</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹199<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>1-Tap Google Play Subscription</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>250 people</strong> capacity</li>
              <li>✓ Google Drive cloud backup & restore</li>
              <li>✓ Multi-device sync queue</li>
              <li>✓ Excel & PDF report exports</li>
              <li>✓ 100% ad-free experience</li>
              <li>✓ Best for individual teachers & tutors</li>
              <li>✗ Web dashboard & API not included</li>
            </ul>
            <button
              onClick={() => {
                showToast('Premium (₹199/mo) is billed via Google Play Store inside the Android app.', 'info');
              }}
              className={activeTier === 'PREMIUM' ? 'btn btn-secondary' : 'btn btn-secondary'}
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'PREMIUM' ? 'Active on Google Play' : 'Subscribe via Google Play'}
            </button>
          </div>

          {/* Professional Tier (NEW) */}
          <div style={{ background: 'var(--surface-raised)', border: activeTier === 'PROFESSIONAL' ? '2px solid var(--primary)' : '1px solid rgba(6, 182, 212, 0.3)', borderRadius: '12px', padding: '24px', position: 'relative', display: 'flex', flexDirection: 'column' }}>
            <div style={{ position: 'absolute', top: '-11px', right: '16px', background: 'var(--primary)', color: '#03141e', padding: '2px 10px', borderRadius: '12px', fontSize: '10px', fontWeight: 700 }}>
              INSTITUTION SWEET SPOT
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Professional</h3>
              <span className="badge badge-warning" style={{ fontSize: '10px' }}>WEB BILLING</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹499<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Razorpay GST Invoice & Centralized Billing</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ Up to <strong>1,000 people</strong> capacity</li>
              <li>✓ Multiple classes & up to 2 branches</li>
              <li>✓ <strong>Basic Web Dashboard</strong> access</li>
              <li>✓ <strong>2 Administrator</strong> accounts & roles</li>
              <li>✓ Multi-device fleet sync</li>
              <li>✓ Automated Excel/PDF audit reports</li>
              <li>✓ Official GST Tax Invoices for accounting</li>
            </ul>
            <button
              onClick={() => {
                setSelectedPlanForCheckout('PROFESSIONAL');
                setShowCheckoutModal(true);
              }}
              className="btn btn-primary"
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'PROFESSIONAL' ? 'Renew Professional (₹499)' : 'Upgrade to Professional (₹499)'}
            </button>
          </div>

          {/* Business Tier */}
          <div style={{ background: 'var(--surface-raised)', border: activeTier === 'BUSINESS' ? '2px solid var(--primary)' : '1px solid var(--border)', borderRadius: '12px', padding: '24px', position: 'relative', display: 'flex', flexDirection: 'column' }}>
            {activeTier === 'BUSINESS' && (
              <div style={{ position: 'absolute', top: '-11px', right: '16px', background: 'var(--success)', color: '#03141e', padding: '2px 10px', borderRadius: '12px', fontSize: '10px', fontWeight: 700 }}>
                CURRENT PLAN
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>Business Plan</h3>
              <span className="badge badge-success" style={{ fontSize: '10px' }}>UNLIMITED</span>
            </div>
            <div className="tnum" style={{ fontSize: '24px', fontWeight: 700, color: 'var(--primary)', marginBottom: '4px' }}>
              ₹999<span style={{ fontSize: '12px', color: 'var(--text-muted)' }}> / month</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginBottom: '14px' }}>Institutional Invoicing & Enterprise SLA</div>
            <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 20px 0', display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
              <li>✓ <strong>Unlimited</strong> people & classes</li>
              <li>✓ Unlimited campuses & departments</li>
              <li>✓ <strong>Unlimited Admins</strong> & granular RBAC</li>
              <li>✓ Full Next.js Web Dashboard & Live Monitor</li>
              <li>✓ <strong>REST API & Kiosk QR Key Provisioning</strong></li>
              <li>✓ pgvector 512-D similarity search</li>
              <li>✓ Dedicated SLA & Priority 24/7 Support</li>
            </ul>
            <button
              onClick={() => {
                setSelectedPlanForCheckout('BUSINESS');
                setShowCheckoutModal(true);
              }}
              className={activeTier === 'BUSINESS' ? 'btn btn-primary' : 'btn btn-primary'}
              style={{ width: '100%', fontSize: '12px', marginTop: 'auto' }}
            >
              {activeTier === 'BUSINESS' ? 'Renew Business (₹999)' : 'Upgrade to Business (₹999)'}
            </button>
          </div>
        </div>
      </div>

      {/* Invoice & Transaction History */}
      <div>
        <h2 style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '16px' }}>Billing Invoices & Receipts</h2>
        <div className="table-surface">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Invoice Number</th>
                <th scope="col">Billing Period</th>
                <th scope="col">Amount (INR)</th>
                <th scope="col">Payment Method</th>
                <th scope="col">Status</th>
                <th scope="col">Receipt</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="tnum" style={{ fontWeight: 600 }}>INV-2026-09-8812</td>
                <td>Sep 10, 2026 – Oct 10, 2026</td>
                <td className="tnum" style={{ fontWeight: 600 }}>₹999.00</td>
                <td>Razorpay UPI (Verified)</td>
                <td><span className="badge badge-success">PAID</span></td>
                <td>
                  <button
                    onClick={() => setViewingInvoice('INV-2026-09-8812')}
                    aria-label="View or download receipt for invoice INV-2026-09-8812"
                    style={{ background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                  >
                    View Receipt
                  </button>
                </td>
              </tr>
              <tr>
                <td className="tnum" style={{ fontWeight: 600 }}>INV-2026-08-7401</td>
                <td>Aug 10, 2026 – Sep 10, 2026</td>
                <td className="tnum" style={{ fontWeight: 600 }}>₹999.00</td>
                <td>Razorpay UPI (Verified)</td>
                <td><span className="badge badge-success">PAID</span></td>
                <td>
                  <button
                    onClick={() => setViewingInvoice('INV-2026-08-7401')}
                    aria-label="View or download receipt for invoice INV-2026-08-7401"
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
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="checkout-modal-title"
          className="modal-overlay"
          onClick={() => setShowCheckoutModal(false)}
        >
          <div className="modal-dialog" style={{ maxWidth: '480px', padding: '28px', boxShadow: '0 25px 50px rgba(0,0,0,0.6)' }} onClick={(e) => e.stopPropagation()}>
            <h2 id="checkout-modal-title" style={{ fontSize: '20px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
              Razorpay Secure Checkout
            </h2>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '20px' }}>
              OmniFace AI {selectedPlanForCheckout} Plan (1 Month Institutional License)
            </p>

            <div style={{ background: 'var(--surface)', padding: '16px', borderRadius: '8px', marginBottom: '16px', border: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '13px' }}>
                <span style={{ color: 'var(--text-muted)' }}>{selectedPlanForCheckout} Tier Subscription</span>
                <span className="tnum" style={{ color: 'var(--text-main)', fontWeight: 600 }}>
                  ₹{getPlanPrice(selectedPlanForCheckout)}.00
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '13px' }}>
                <span style={{ color: 'var(--text-muted)' }}>Applicable GST (18% inclusive)</span>
                <span className="tnum" style={{ color: 'var(--text-main)', fontWeight: 600 }}>
                  ₹{Math.round(getPlanPrice(selectedPlanForCheckout) * 0.18 * 100 / 118)}.00
                </span>
              </div>
              <div style={{ borderTop: '1px solid var(--border)', paddingTop: '10px', display: 'flex', justifyContent: 'space-between', fontSize: '15px' }}>
                <strong style={{ color: 'var(--text-main)' }}>Total Payable</strong>
                <strong className="tnum" style={{ color: 'var(--primary)', fontSize: '18px' }}>
                  ₹{getPlanPrice(selectedPlanForCheckout)}.00
                </strong>
              </div>
            </div>

            {/* Pre-payment Legal Disclosures */}
            <div style={{ padding: '12px', background: 'rgba(2,132,199,0.06)', border: '1px solid rgba(2,132,199,0.2)', borderRadius: '8px', marginBottom: '20px', fontSize: '11px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
              <div style={{ fontWeight: 600, color: 'var(--text-main)', marginBottom: '4px' }}>Notice & Consent Before Payment:</div>
              By proceeding, you authorize institutional payment and acknowledge that you agree to our{' '}
              <a href="/terms" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)', textDecoration: 'underline' }}>Terms of Service & EULA</a>,{' '}
              <a href="/privacy" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)', textDecoration: 'underline' }}>Biometric Privacy Policy</a>, and{' '}
              <a href="/refund-policy" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)', textDecoration: 'underline' }}>3-Day Refund Policy</a>. Tax invoice with GSTIN will be automatically generated upon payment confirmation.
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button onClick={() => setShowCheckoutModal(false)} className="btn btn-secondary" disabled={processingPayment}>
                Cancel
              </button>
              <button onClick={handleSimulatePayment} className="btn btn-primary" disabled={processingPayment}>
                {processingPayment ? 'Authorizing Razorpay Gateway...' : `Pay ₹${getPlanPrice(selectedPlanForCheckout)} with Razorpay`}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Invoice PDF Preview Modal */}
      {viewingInvoice && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="invoice-modal-title"
          className="modal-overlay"
          onClick={() => setViewingInvoice(null)}
        >
          <div
            className="modal-dialog"
            style={{ background: '#ffffff', color: '#0f172a', maxWidth: '560px', padding: '32px', boxShadow: '0 25px 50px rgba(0,0,0,0.8)' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '2px solid #e2e8f0', paddingBottom: '16px', marginBottom: '20px' }}>
              <div>
                <h3 id="invoice-modal-title" style={{ fontSize: '20px', fontWeight: 800, color: '#0f172a', margin: 0 }}>SOFTWARE LICENSE RECEIPT</h3>
                <div style={{ fontSize: '12px', color: '#475569', fontWeight: 600, marginTop: '2px' }}>OmniFace Technologies</div>
                <div style={{ fontSize: '11px', color: '#64748b' }}>Operated by Preetham, Independent Developer</div>
                <div style={{ fontSize: '11px', color: '#64748b' }}>Karnataka, India • Contact: preethamdev05@gmail.com</div>
                <div style={{ fontSize: '10px', color: '#94a3b8', marginTop: '2px' }}>Status: Small Enterprise / GST-exempt under Sec 22 CGST Act</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontWeight: 700, fontSize: '14px', color: '#0284c7' }}>{viewingInvoice}</div>
                <div style={{ fontSize: '12px', color: '#64748b' }}>Date: Sep 10, 2026</div>
              </div>
            </div>

            <div style={{ marginBottom: '20px', fontSize: '13px' }}>
              <div style={{ color: '#64748b', fontSize: '11px', textTransform: 'uppercase', fontWeight: 600 }}>Billed To:</div>
              <div style={{ fontWeight: 700 }}>National Institute of Technology</div>
              <div style={{ color: '#475569' }}>Academic Campus, Dept of Computer Science</div>
              <div style={{ color: '#475569' }}>Fleet ID: NIT-FLEET-XIAOMI14</div>
            </div>

            <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '20px', fontSize: '13px' }}>
              <thead>
                <tr style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0', textAlign: 'left' }}>
                  <th scope="col" style={{ padding: '8px' }}>Description</th>
                  <th scope="col" style={{ padding: '8px', textAlign: 'right' }}>SAC Code</th>
                  <th scope="col" style={{ padding: '8px', textAlign: 'right' }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                <tr style={{ borderBottom: '1px solid #e2e8f0' }}>
                  <td style={{ padding: '8px' }}>OmniFace AI Business Tier License (1 Month)</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>998313</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>₹999.00</td>
                </tr>
                <tr style={{ borderBottom: '1px solid #e2e8f0' }}>
                  <td style={{ padding: '8px' }}>Taxes (GST Exempt / Small Enterprise)</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>—</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>₹0.00</td>
                </tr>
                <tr style={{ fontWeight: 800 }}>
                  <td style={{ padding: '8px' }}>Total Paid</td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>—</td>
                  <td style={{ padding: '8px', textAlign: 'right', color: '#0284c7' }}>₹999.00</td>
                </tr>
              </tbody>
            </table>

            <div style={{ fontSize: '11px', color: '#64748b', marginBottom: '16px', lineHeight: '1.4' }}>
              Notice: This receipt serves as proof of commercial software license grant under Indian Contract Act, 1872. Governed by Karnataka Jurisdiction. Covered by 3-Day Money-Back Guarantee.
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '16px', flexWrap: 'wrap', gap: '12px' }}>
              <span style={{ fontSize: '11px', color: '#10b981', fontWeight: 600 }}>✓ Verified Razorpay UPI Transaction</span>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  onClick={() => window.print()}
                  style={{
                    background: '#f1f5f9',
                    color: '#0f172a',
                    border: '1px solid #cbd5e1',
                    borderRadius: '8px',
                    padding: '8px 16px',
                    fontSize: '13px',
                    fontWeight: 600,
                    cursor: 'pointer',
                  }}
                >
                  Print Receipt
                </button>
                <button onClick={() => setViewingInvoice(null)} className="btn btn-primary">
                  Close
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
