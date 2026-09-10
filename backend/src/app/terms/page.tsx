import React from 'react';
import Link from 'next/link';

export const metadata = {
  title: 'Terms of Service & License Agreement | OmniFace AI',
  description: 'Enterprise software licensing terms, institutional compliance obligations, minor parental consent warranties, and liability limitations.',
};

export default function TermsOfServicePage() {
  return (
    <div style={{ maxWidth: '860px', margin: '0 auto', paddingBottom: '60px' }}>
      <div style={{ marginBottom: '32px' }}>
        <Link href="/" style={{ color: 'var(--primary)', textDecoration: 'none', fontSize: '13px', display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '16px' }}>
          &larr; Back to Dashboard
        </Link>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-main)', letterSpacing: '-0.03em', marginBottom: '8px' }}>
          Terms of Service & Software License Agreement
        </h1>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
          <strong>Last Updated & Effective:</strong> September 10, 2026 &bull; <strong>Licensor:</strong> OmniFace Technologies (Preetham, Independent Developer, Karnataka, India)
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '28px', fontSize: '14px', lineHeight: 1.7, color: 'var(--text-muted)' }}>
        
        {/* Important Notice */}
        <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            Agreement Overview
          </h2>
          <p style={{ margin: 0 }}>
            By downloading, installing, accessing, or using the OmniFace AI Android application or Web Management Dashboard, you ("Customer", "Institution", or "User") agree to be bound by these Terms of Service. If you are entering into this agreement on behalf of an institution, school, or corporate entity, you represent that you possess the legal authority to bind that entity.
          </p>
        </div>

        {/* Section 1 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            1. License Grant & Permitted Use
          </h2>
          <p>
            OmniFace grants you a revocable, non-exclusive, non-transferable license to install and operate the software strictly for internal institutional attendance monitoring and access verification.
          </p>
          <p style={{ marginTop: '8px' }}>
            <strong>Prohibited Activities:</strong> You shall not: (a) reverse-engineer, decompile, or extract proprietary neural network weights; (b) use the software for covert, non-consensual surveillance; (c) bypass security access controls or device limits; or (d) resell or sub-license the software without prior written authorization.
          </p>
        </div>

        {/* Section 2: Critical Minors & Consent Clause */}
        <div style={{ background: 'rgba(239, 68, 68, 0.06)', border: '1px solid rgba(239, 68, 68, 0.25)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--danger)', marginBottom: '8px' }}>
            2. Customer Warranties & Mandatory Parental Consent for Minors (&lt;18)
          </h2>
          <p style={{ color: 'var(--text-main)', marginBottom: '10px' }}>
            OmniFace operates strictly as an on-premise software tool. The Customer acts as the sole <strong>Data Fiduciary (under India DPDP Act 2023)</strong> and <strong>Data Controller (under GDPR)</strong>.
          </p>
          <ul style={{ paddingLeft: '20px', color: 'var(--text-main)', display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <li><strong>Mandatory Parental Consent:</strong> If Customer enrolls any student or individual under the age of 18 years, Customer represents and warrants that it has collected, validated, and archived <strong>prior verifiable written consent from the parent or lawful guardian</strong> in accordance with Section 9 of the DPDP Act 2023 and applicable privacy statutes.</li>
            <li><strong>Notice & Signage:</strong> Customer warrants that it has provided conspicuous written notice and signage at all kiosk entry points informing individuals of biometric scanning and verification.</li>
          </ul>
        </div>

        {/* Section 3: Indemnification Shield */}
        <div style={{ background: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            3. Absolute Indemnification Shield
          </h2>
          <p style={{ margin: 0 }}>
            Customer agrees to <strong>defend, indemnify, and hold harmless</strong> OmniFace Technologies, its developer (Preetham), and affiliates from and against any and all third-party claims, lawsuits, investigations, regulatory fines, statutory penalties (including any penalties levied under DPDP Act Section 33 or Illinois BIPA), legal fees, and liabilities arising out of or related to: (a) Customer&apos;s failure to obtain requisite parental or individual consent; (b) Customer&apos;s breach of data protection laws; or (c) any unlawful, discriminatory, or unauthorized use of the software by Customer.
          </p>
        </div>

        {/* Section 4 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            4. Subscription Tiers & Payment Terms
          </h2>
          <p>
            Commercial plans (Free: 25 members; Premium: 250 members; Business: Unlimited) are billed on a recurring monthly cycle. Subscriptions activated through Google Play Billing are managed and billed through Google Play accounts. Direct institutional licenses are payable in Indian Rupees (INR) inclusive of digital service charges.
          </p>
        </div>

        {/* Section 5 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            5. Disclaimer of Warranties (&quot;AS IS&quot;) & Limitation of Liability
          </h2>
          <p>
            THE SOFTWARE IS PROVIDED &quot;AS IS&quot; AND &quot;AS AVAILABLE&quot; WITHOUT WARRANTIES OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR UNINTERRUPTED AVAILABILITY.
          </p>
          <p style={{ marginTop: '8px' }}>
            IN NO EVENT SHALL OMNIFACE TECHNOLOGIES OR ITS DEVELOPER BE LIABLE FOR ANY INDIRECT, INCIDENTAL, CONSEQUENTIAL, SPECIAL, OR PUNITIVE DAMAGES. TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, TOTAL AGGREGATE LIABILITY ARISING UNDER THIS AGREEMENT SHALL BE STRICTLY LIMITED TO THE ACTUAL AMOUNTS PAID BY CUSTOMER IN THE THREE (3) MONTHS PRECEDING THE CLAIM, OR &#8377;1,000 (INR), WHICHEVER IS LESS.
          </p>
        </div>

        {/* Section 6: Jurisdiction */}
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '12px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '8px' }}>
            6. Governing Law & Exclusive Jurisdiction
          </h2>
          <p style={{ margin: 0 }}>
            This Agreement shall be governed by and construed in accordance with the laws of the Republic of India. Any dispute, claim, or controversy arising under or in connection with this Agreement shall be subject to the <strong>exclusive jurisdiction of the competent courts in Karnataka (Chamarajanagar / Bengaluru), India</strong>. The parties further agree that any dispute may be referred to binding arbitration under the Indian Arbitration and Conciliation Act, 1996, conducted in English in Karnataka, India.
          </p>
        </div>

        {/* Section 7 */}
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-main)', marginBottom: '10px' }}>
            7. Contact Information
          </h2>
          <p>
            For legal notices, licensing inquiries, or contract questions, contact:
          </p>
          <div style={{ fontSize: '13px', color: 'var(--text-main)', marginTop: '6px' }}>
            <strong>Email:</strong> <a href="mailto:preethamdev05@gmail.com" style={{ color: 'var(--primary)' }}>preethamdev05@gmail.com</a><br />
            <strong>Location:</strong> Karnataka, India
          </div>
        </div>

      </div>
    </div>
  );
}
