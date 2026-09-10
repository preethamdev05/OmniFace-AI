import React from 'react';
import Link from 'next/link';

export const metadata = {
  title: 'Privacy Policy & Biometric Data Governance | OmniFace AI',
  description: 'Biometric privacy standards, on-device vector processing, and DPDP Act 2023 / GDPR compliance disclosures.',
};

export default function PrivacyPolicyPage() {
  return (
    <div style={{ maxWidth: '860px', margin: '0 auto', paddingBottom: '60px' }}>
      <div style={{ marginBottom: '32px' }}>
        <Link href="/" style={{ color: 'var(--primary)', textDecoration: 'none', fontSize: '13px', display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '16px' }}>
          &larr; Back to Dashboard
        </Link>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-main)', letterSpacing: '-0.03em', marginBottom: '8px' }}>
          Privacy Policy & Biometric Data Governance
        </h1>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
          <strong>Last Updated & Effective:</strong> September 10, 2026 &bull; <strong>Software Provider:</strong> OmniFace Technologies (Operated by Preetham, Independent Developer, Karnataka, India)
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '28px', fontSize: '14px', lineHeight: 1.7, color: 'var(--text-muted)' }}>
        
        {/* Core Guarantee Card */}
        <div style={{ background: 'rgba(6, 182, 212, 0.08)', border: '1px solid rgba(6, 182, 212, 0.25)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--primary)', marginBottom: '8px' }}>
            🔒 Fundamental Privacy Guarantee: Zero Raw Photo Cloud Storage
          </h2>
          <p style={{ color: 'var(--text-main)', margin: 0 }}>
            OmniFace AI processes facial biometric recognition <strong>100% locally on the edge device</strong>. Raw camera images are converted into non-reversible 512-dimensional mathematical float32 vectors in volatile RAM buffers and <strong>discarded immediately</strong>. Neither raw facial photos nor biometric templates are ever transmitted to our servers or sold to third parties.
          </p>
        </div>

        {/* Section 1 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            1. Scope & Legal Roles (Data Fiduciary vs. Software Provider)
          </h2>
          <p>
            OmniFace AI provides offline-first biometric attendance verification software consisting of an Android Kiosk application and an administrative fleet management console.
          </p>
          <p style={{ marginTop: '8px' }}>
            Under applicable data protection laws, including the <strong>Digital Personal Data Protection (DPDP) Act, 2023 (India)</strong> and the <strong>General Data Protection Regulation (GDPR)</strong>:
          </p>
          <ul style={{ paddingLeft: '20px', marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
            <li><strong>Institutional Customer as Data Fiduciary / Controller:</strong> The educational institution, business enterprise, or facility operating OmniFace acts as the Data Fiduciary / Data Controller. The Customer determines the purpose, lawful basis, and personnel enrolled in the system.</li>
            <li><strong>OmniFace as Software Provider / Processor:</strong> OmniFace Technologies acts solely as an independent software vendor providing on-premise application software. OmniFace does not own, access, or sell customer biometric records.</li>
          </ul>
        </div>

        {/* Section 2 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            2. Categories of Data Processed
          </h2>
          <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <li><strong>Mathematical Biometric Vectors (On-Device):</strong> 512-dimensional floating-point coordinate arrays generated via deep neural inference. These are abstract mathematical vectors that cannot be reverse-engineered into visible photographs.</li>
            <li><strong>Student / Employee Directory Data:</strong> Full Name, Roll Number or Employee ID, Department, Section/Semester, and optional enrolled biometric quality score.</li>
            <li><strong>Attendance Verification Logs:</strong> Date, timestamp, match confidence score, security tier (High/Strict), and cryptographic SHA-256 Merkle chain block hashes.</li>
            <li><strong>Device Telemetry:</strong> Silicon inference latency (ms), battery temperature, and network ping. Telemetry is purely operational and is never linked to individual biometric profiles.</li>
          </ul>
        </div>

        {/* Section 3: Minors & Under 18 */}
        <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            3. Processing of Children & Minors Data (Under 18 Years of Age)
          </h2>
          <p style={{ marginBottom: '10px' }}>
            In compliance with <strong>Section 9 of the DPDP Act 2023</strong>, the <strong>Children&apos;s Online Privacy Protection Act (COPPA)</strong>, and <strong>FERPA</strong>:
          </p>
          <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
            <li>OmniFace strictly prohibits the enrollment of minors (individuals under 18 years of age) without <strong>prior verifiable written consent from a parent or lawful guardian</strong>.</li>
            <li>Institutions enrolling minors must affirmatively certify in the administrative portal that verifiable parental consent has been collected and archived.</li>
            <li>OmniFace software contains zero behavioral profiling, tracking, or targeted advertising directed at children.</li>
          </ul>
        </div>

        {/* Section 4 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            4. Security Architecture & Encryption
          </h2>
          <ul style={{ paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
            <li><strong>At Rest:</strong> On-device SQLite databases are encrypted using AES-256-GCM with hardware-backed keys sealed in the AndroidKeyStore.</li>
            <li><strong>In Transit:</strong> Optional multi-device fleet sync utilizes TLS 1.3 encryption with strict HTTPS validation and HMAC-SHA256 device mutual authentication.</li>
            <li><strong>Backups:</strong> Optional cloud backups are directed solely to the administrator&apos;s own Google Drive sandbox (`appDataFolder`), encrypted end-to-end with PBKDF2-HMAC-SHA256 before upload.</li>
          </ul>
        </div>

        {/* Section 5 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            5. Data Subject Rights & Deletion
          </h2>
          <p>
            Enrolled individuals have the right to access, rectify, or erase their biometric profile under DPDP Act 2023 Sections 11&ndash;14 and GDPR Articles 15&ndash;20.
          </p>
          <p style={{ marginTop: '8px' }}>
            Institutions can execute irreversible template deletion via the <strong>Data Governance</strong> dashboard or individual profile deletion in the <strong>Roster</strong> page, triggering an immediate cryptographic wipe of the associated 512-D embedding.
          </p>
        </div>

        {/* Section 6 */}
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            6. Developer Contact & Grievance Redressal
          </h2>
          <p style={{ marginBottom: '12px' }}>
            For privacy inquiries, technical support, or data protection concerns:
          </p>
          <div style={{ fontSize: '13px', color: 'var(--text-main)', display: 'flex', flexDirection: 'column', gap: '4px' }}>
            <div><strong>Software Developer:</strong> Preetham (OmniFace Technologies)</div>
            <div><strong>Location:</strong> Karnataka (Chamarajanagar / Bengaluru), India</div>
            <div><strong>Official Email:</strong> <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)' }}>preethamdev05@gmail.com</a></div>
            <div><strong>Grievance Acknowledgment SLA:</strong> 48 hours for data protection inquiries.</div>
          </div>
        </div>

      </div>
    </div>
  );
}
