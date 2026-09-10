'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

export default function RosterRedirectPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/students');
  }, [router]);

  return (
    <div style={{ padding: '60px', textAlign: 'center', color: 'var(--text-muted)' }}>
      <div className="spin-icon" style={{ fontSize: '24px', marginBottom: '12px' }}>↻</div>
      <div>Redirecting to Students Directory...</div>
    </div>
  );
}
