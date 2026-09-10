import React from 'react';
import Link from 'next/link';

export const metadata = {
  title: 'Cookie & Storage Policy | OmniFace AI',
  description: 'Details on authentication cookies, zero third-party trackers, and privacy-first local storage usage.',
};

export default function CookiePolicyPage() {
  return (
    <div style={{ maxWidth: '860px', margin: '0 auto', paddingBottom: '60px' }}>
      <div style={{ marginBottom: '32px' }}>
        <Link href="/" style={{ color: 'var(--primary)', textDecoration: 'none', fontSize: '13px', display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '16px' }}>
          &larr; Back to Dashboard
        </Link>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-main)', letterSpacing: '-0.03em', marginBottom: '8px' }}>
          Cookie & Local Storage Policy
        </h1>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
          <strong>Last Updated & Effective:</strong> September 10, 2026 &bull; <strong>Software Provider:</strong> OmniFace Technologies
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '28px', fontSize: '14px', lineHeight: 1.7, color: 'var(--text-muted)' }}>
        
        {/* Core Notice */}
        <div style={{ background: 'rgba(16, 185, 129, 0.08)', border: '1px solid rgba(16, 185, 129, 0.25)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--success)', marginBottom: '8px' }}>
            🛡️ Privacy-First: Zero Tracking or Advertising Cookies
          </h2>
          <p style={{ color: 'var(--text-main)', margin: 0 }}>
            OmniFace AI does <strong>not</strong> use advertising cookies, marketing pixels, cross-site analytics trackers (such as Google Analytics or Meta Pixel), or behavioral profiling tools. We only use strictly necessary authentication session cookies required to operate the secure web dashboard.
          </p>
        </div>

        {/* Section 1 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            1. What Are Cookies?
          </h2>
          <p>
            Cookies are small cryptographic text tokens placed on your device by your web browser when you access web applications. They allow websites to remember user sessions, protect against cross-site request forgery, and maintain operational security.
          </p>
        </div>

        {/* Section 2: Exact Cookies Table */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            2. Cookies Used by OmniFace AI
          </h2>
          <div className="table-surface" style={{ marginTop: '12px' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col">Cookie Name</th>
                  <th scope="col">Type / Purpose</th>
                  <th scope="col">Duration</th>
                  <th scope="col">Security Flags</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="tnum" style={{ fontWeight: 600, color: 'var(--primary)' }}>omniface_session</td>
                  <td>Strictly Necessary: Administrator authentication session token</td>
                  <td>7 Days</td>
                  <td><span className="badge badge-success">HttpOnly ? Secure ? SameSite=Lax</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        {/* Section 3: Legal Basis */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            3. Legal Basis under GDPR & ePrivacy Directive
          </h2>
          <p>
            Under the EU ePrivacy Directive (Directive 2002/58/EC as amended by 2009/136/EC) and global privacy regulations, cookies that are <strong>strictly necessary</strong> to provide a service explicitly requested by the user (such as maintaining a logged-in administrator session) are exempt from requiring prior consent banners. Because OmniFace uses zero marketing or tracking cookies, intrusive cookie consent popups are unnecessary.
          </p>
        </div>

        {/* Section 4 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            4. Managing & Disabling Cookies
          </h2>
          <p>
            You can manage or disable cookies through your browser settings (Chrome, Firefox, Safari, Edge). Please note that disabling strictly necessary session cookies will prevent login and access to the OmniFace Web Fleet Console.
          </p>
        </div>

        {/* Section 5 */}
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            Questions Regarding Cookies?
          </h2>
          <p style={{ margin: 0 }}>
            Contact the developer at <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)' }}>preethamdev05@gmail.com</a> for technical inquiries regarding our storage practices.
          </p>
        </div>

      </div>
    </div>
  );
}
