import React from 'react';
import Link from 'next/link';

export const metadata = {
  title: 'Subscription & Refund Policy | OmniFace AI',
  description: 'Details on our 3-day money-back guarantee, monthly subscription renewals, Google Play cancellation procedures, and billing terms.',
};

export default function RefundPolicyPage() {
  return (
    <div style={{ maxWidth: '860px', margin: '0 auto', paddingBottom: '60px' }}>
      <div style={{ marginBottom: '32px' }}>
        <Link href="/" style={{ color: 'var(--primary)', textDecoration: 'none', fontSize: '13px', display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '16px' }}>
          &larr; Back to Dashboard
        </Link>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-main)', letterSpacing: '-0.03em', marginBottom: '8px' }}>
          Subscription, Renewal & Refund Policy
        </h1>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
          <strong>Last Updated & Effective:</strong> September 10, 2026 &bull; <strong>Software Provider:</strong> OmniFace Technologies
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '28px', fontSize: '14px', lineHeight: 1.7, color: 'var(--text-muted)' }}>
        
        {/* Core Guarantee Card */}
        <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--primary)', marginBottom: '8px' }}>
            3-Day Money-Back Guarantee
          </h2>
          <p style={{ margin: 0 }}>
            We want you to be completely confident in OmniFace AI. If you purchase an initial commercial subscription (Premium &#8377;199 or Business &#8377;999) and determine it does not satisfy your operational requirements, you may request a <strong>100% full refund within three (3) calendar days (72 hours)</strong> of initial purchase.
          </p>
        </div>

        {/* Section 1 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            1. Refund Eligibility & Request Timeline
          </h2>
          <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <li>
              <strong>Hours 0 to 48 (Google Play Instant Self-Service):</strong><br />
              If you purchased your subscription via Google Play in the Android application, you can request an instant automated refund directly through your Google account by visiting <a href="https://play.google.com/store/account/orderhistory" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)' }}>Google Play Order History</a>.
            </li>
            <li>
              <strong>Hours 48 to 72 (Developer-Assisted Refund):</strong><br />
              Between 48 hours and 72 hours following initial purchase, send an email to <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)' }}>preethamdev05@gmail.com</a> with your Google Play Order Number (e.g., <code>GPA.XXXX-XXXX-XXXX-XXXXX</code>) or invoice reference. We will process and approve your refund in the Google Play Developer Console.
            </li>
            <li>
              <strong>After 72 Hours (3 Days):</strong><br />
              Payments become non-refundable for that monthly billing cycle. You may cancel your subscription at any time to prevent any future renewals, and your plan features will remain active until the end of your prepaid billing period.
            </li>
          </ul>
        </div>

        {/* Section 2 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            2. How to Cancel Your Subscription
          </h2>
          <p>
            Subscriptions are billed on a recurring monthly basis. You can cancel your subscription at any time with <strong>zero hidden fees or cancellation penalties</strong>:
          </p>
          <div style={{ marginTop: '12px', padding: '16px', background: 'var(--surface)', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <h3 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-main)', marginBottom: '6px' }}>Self-Service Cancellation via Google Play</h3>
            <ol style={{ paddingLeft: '20px', fontSize: '13px', display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <li>Open the <strong>Google Play Store</strong> app on your Android device.</li>
              <li>Tap your profile icon in the top right &rarr; select <strong>Payments & subscriptions</strong>.</li>
              <li>Select <strong>Subscriptions</strong> &rarr; tap <strong>OmniFace AI</strong>.</li>
              <li>Tap <strong>Cancel subscription</strong> and follow the on-screen instructions.</li>
            </ol>
            <p style={{ marginTop: '10px', fontSize: '12px', color: 'var(--text-dim)' }}>
              Direct web management link: <a href="https://play.google.com/store/account/subscriptions" target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)' }}>play.google.com/store/account/subscriptions</a>
            </p>
          </div>
        </div>

        {/* Section 3 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            3. Processing Time & Credit Method
          </h2>
          <p>
            Once a refund is approved, funds are credited directly back to your original payment method (Google Play balance, UPI, credit/debit card, or net banking). Standard processing times depend on your banking provider:
          </p>
          <ul style={{ paddingLeft: '20px', marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '4px' }}>
            <li><strong>Google Play Balance:</strong> Instant (typically within 2 hours).</li>
            <li><strong>UPI / Debit / Credit Cards:</strong> 3 to 5 business days depending on your bank.</li>
          </ul>
        </div>

        {/* Section 4 */}
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            Billing Inquiries & Support
          </h2>
          <p style={{ margin: 0 }}>
            If you experience any billing discrepancies or need assistance with a refund request, please email developer support at <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)' }}>preethamdev05@gmail.com</a>. Please include your order ID for expedited resolution.
          </p>
        </div>

      </div>
    </div>
  );
}
