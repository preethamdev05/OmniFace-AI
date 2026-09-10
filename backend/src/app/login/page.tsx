'use client';

import React, { useState, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { useToast } from '@/components/Toast';

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirectTarget = searchParams.get('redirect') || '/';

  const [email, setEmail] = useState('admin@omniface.ai');
  const [password, setPassword] = useState('OmniFace@2026!');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(true);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const { showToast } = useToast();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setErrorMsg('Please enter both administrative email and access password.');
      return;
    }

    setLoading(true);
    setErrorMsg(null);

    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, rememberMe }),
      });

      const data = await res.json();

      if (res.ok && data.success) {
        showToast('Administrative credentials verified. Welcome back!', 'success');
        // Force full page navigation to ensure middleware cookies and session state are recognized immediately
        window.location.href = redirectTarget;
      } else {
        const msg = data.error || 'Authentication rejected. Please verify credentials.';
        setErrorMsg(msg);
        showToast(msg, 'error');
      }
    } catch (err: any) {
      const msg = err?.message || 'Connection to authentication gateway failed.';
      setErrorMsg(msg);
      showToast(msg, 'error');
    } finally {
      setLoading(false);
    }
  };

  const autofillDemoCredentials = () => {
    setEmail('admin@omniface.ai');
    setPassword('OmniFace@2026!');
    setErrorMsg(null);
    showToast('Auto-filled enterprise admin credentials', 'info');
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'radial-gradient(ellipse at 50% 15%, rgba(6, 182, 212, 0.09), transparent 65%), var(--bg)',
        padding: '24px 16px',
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Subtle background ambient orb */}
      <div
        style={{
          position: 'absolute',
          top: '-150px',
          left: '50%',
          transform: 'translateX(-50%)',
          width: '600px',
          height: '600px',
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(6, 182, 212, 0.05) 0%, transparent 70%)',
          pointerEvents: 'none',
        }}
        aria-hidden="true"
      />

      {/* Main Glass Card */}
      <div
        style={{
          width: '100%',
          maxWidth: '440px',
          background: 'rgba(15, 21, 36, 0.85)',
          backdropFilter: 'blur(20px)',
          WebkitBackdropFilter: 'blur(20px)',
          border: '1px solid var(--border)',
          borderRadius: '18px',
          padding: '36px 32px',
          boxShadow: '0 24px 64px -12px rgba(0, 0, 0, 0.7), 0 0 0 1px rgba(255, 255, 255, 0.03)',
          position: 'relative',
          zIndex: 1,
        }}
      >
        {/* Header Branding */}
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <div
            style={{
              width: '48px',
              height: '48px',
              borderRadius: '12px',
              background: 'linear-gradient(135deg, #06b6d4, #3b82f6)',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
              fontWeight: 800,
              fontSize: '24px',
              boxShadow: '0 8px 24px rgba(6, 182, 212, 0.35)',
              marginBottom: '16px',
            }}
          >
            Ω
          </div>
          <h1
            style={{
              fontSize: '22px',
              fontWeight: 700,
              letterSpacing: '-0.03em',
              color: 'var(--text-main)',
              margin: '0 0 6px 0',
            }}
          >
            OmniFace AI Console
          </h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)', margin: 0 }}>
            Fleet Administration & Biometric Management
          </p>
        </div>

        {/* Security / Compliance Badges */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            flexWrap: 'wrap',
            marginBottom: '24px',
          }}
        >
          <span className="badge badge-primary" style={{ fontSize: '11px', padding: '3px 8px' }}>
            Aegis Enclave
          </span>
          <span className="badge badge-success" style={{ fontSize: '11px', padding: '3px 8px' }}>
            DPDP Compliant
          </span>
          <span
            style={{
              fontSize: '11px',
              color: 'var(--text-dim)',
              background: 'rgba(30, 42, 68, 0.4)',
              padding: '3px 8px',
              borderRadius: '9999px',
              border: '1px solid var(--border)',
            }}
          >
            pgvector 512-D
          </span>
        </div>

        {/* Error notification */}
        {errorMsg && (
          <div
            style={{
              background: 'rgba(239, 68, 68, 0.12)',
              border: '1px solid rgba(239, 68, 68, 0.3)',
              borderRadius: '8px',
              padding: '12px 14px',
              marginBottom: '20px',
              fontSize: '13px',
              color: '#fca5a5',
              display: 'flex',
              alignItems: 'flex-start',
              gap: '10px',
            }}
            role="alert"
          >
            <span style={{ fontSize: '15px', lineHeight: 1 }}>⚠</span>
            <div>{errorMsg}</div>
          </div>
        )}

        {/* Login Form */}
        <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
          <div>
            <label
              htmlFor="admin-email"
              style={{
                display: 'block',
                fontSize: '12px',
                fontWeight: 600,
                color: 'var(--text-muted)',
                marginBottom: '6px',
                textTransform: 'uppercase',
                letterSpacing: '0.04em',
              }}
            >
              Institutional Email
            </label>
            <input
              id="admin-email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="admin@omniface.ai"
              style={{
                width: '100%',
                padding: '11px 14px',
                background: 'var(--surface)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                color: 'var(--text-main)',
                fontSize: '14px',
                fontFamily: 'inherit',
                outline: 'none',
                transition: 'border-color 150ms ease, box-shadow 150ms ease',
              }}
              onFocus={(e) => {
                e.currentTarget.style.borderColor = 'var(--primary)';
                e.currentTarget.style.boxShadow = '0 0 0 2px var(--primary-glow)';
              }}
              onBlur={(e) => {
                e.currentTarget.style.borderColor = 'var(--border)';
                e.currentTarget.style.boxShadow = 'none';
              }}
            />
          </div>

          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
              <label
                htmlFor="admin-password"
                style={{
                  fontSize: '12px',
                  fontWeight: 600,
                  color: 'var(--text-muted)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                }}
              >
                Access Password
              </label>
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--text-dim)',
                  fontSize: '12px',
                  cursor: 'pointer',
                  padding: 0,
                }}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
            <input
              id="admin-password"
              type={showPassword ? 'text' : 'password'}
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••••••"
              style={{
                width: '100%',
                padding: '11px 14px',
                background: 'var(--surface)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                color: 'var(--text-main)',
                fontSize: '14px',
                fontFamily: 'inherit',
                outline: 'none',
                transition: 'border-color 150ms ease, box-shadow 150ms ease',
              }}
              onFocus={(e) => {
                e.currentTarget.style.borderColor = 'var(--primary)';
                e.currentTarget.style.boxShadow = '0 0 0 2px var(--primary-glow)';
              }}
              onBlur={(e) => {
                e.currentTarget.style.borderColor = 'var(--border)';
                e.currentTarget.style.boxShadow = 'none';
              }}
            />
          </div>

          {/* Remember Me Checkbox */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '13px', color: 'var(--text-muted)' }}>
              <input
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                style={{
                  accentColor: 'var(--primary)',
                  width: '16px',
                  height: '16px',
                  borderRadius: '4px',
                }}
              />
              Keep terminal session active (7 days)
            </label>
          </div>

          {/* Submit Button */}
          <button
            type="submit"
            disabled={loading}
            className="btn btn-primary"
            style={{
              width: '100%',
              padding: '12px 18px',
              justifyContent: 'center',
              fontSize: '14px',
              fontWeight: 600,
              marginTop: '6px',
              opacity: loading ? 0.75 : 1,
              cursor: loading ? 'not-allowed' : 'pointer',
            }}
          >
            {loading ? (
              <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span
                  style={{
                    width: '14px',
                    height: '14px',
                    border: '2px solid rgba(0,0,0,0.3)',
                    borderTopColor: '#03141e',
                    borderRadius: '50%',
                    animation: 'spin 0.7s linear infinite',
                    display: 'inline-block',
                  }}
                />
                Authenticating...
              </span>
            ) : (
              'Sign In to Console'
            )}
          </button>
        </form>

        {/* 1-Click Quick Demo Access */}
        <div
          style={{
            marginTop: '24px',
            paddingTop: '20px',
            borderTop: '1px solid var(--border-subtle)',
            textAlign: 'center',
          }}
        >
          <button
            type="button"
            onClick={autofillDemoCredentials}
            style={{
              background: 'rgba(6, 182, 212, 0.08)',
              border: '1px dashed rgba(6, 182, 212, 0.4)',
              borderRadius: '8px',
              padding: '8px 14px',
              color: 'var(--primary)',
              fontSize: '12px',
              cursor: 'pointer',
              width: '100%',
              fontWeight: 500,
              transition: 'background-color 150ms ease',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(6, 182, 212, 0.16)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'rgba(6, 182, 212, 0.08)')}
          >
            ⚡ Click to load Demo Admin credentials
          </button>
          <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '8px' }}>
            Instant evaluation credentials: <code style={{ color: 'var(--text-muted)' }}>admin@omniface.ai</code>
          </div>
        </div>
      </div>

      {/* Trust & Legal Footer */}
      <div
        style={{
          marginTop: '28px',
          textAlign: 'center',
          fontSize: '12px',
          color: 'var(--text-dim)',
          zIndex: 1,
        }}
      >
        <div style={{ marginBottom: '8px' }}>
          OmniFace AI • National Institute of Technology Fleet Node
        </div>
        <div style={{ display: 'flex', gap: '16px', justifyContent: 'center' }}>
          <Link href="/privacy" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
            Privacy
          </Link>
          <span>•</span>
          <Link href="/terms" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
            Terms
          </Link>
          <span>•</span>
          <Link href="/cookies" style={{ color: 'var(--text-muted)', textDecoration: 'none' }}>
            Cookies
          </Link>
        </div>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <div
          style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--bg)',
            color: 'var(--text-muted)',
            fontSize: '14px',
          }}
        >
          Loading OmniFace Console...
        </div>
      }
    >
      <LoginForm />
    </Suspense>
  );
}
