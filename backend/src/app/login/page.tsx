'use client';

import React, { useState, useEffect, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { useToast } from '@/components/Toast';
import {
  auth,
  googleProvider,
  signInWithPopup,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendEmailVerification,
  reload,
  User,
} from '@/lib/firebase-client';

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirectTarget = searchParams.get('redirect') || '/';

  // Mode: 'signin' | 'signup'
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  
  // Credentials
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [orgName, setOrgName] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(true);

  // States
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Gmail Verification View
  const [verificationPending, setVerificationPending] = useState(false);
  const [unverifiedEmail, setUnverifiedEmail] = useState('');
  const [resendCooldown, setResendCooldown] = useState(0);
  const [checkingVerification, setCheckingVerification] = useState(false);

  const { showToast } = useToast();

  // Handle countdown for resending verification email
  useEffect(() => {
    if (resendCooldown > 0) {
      const timer = setTimeout(() => setResendCooldown(resendCooldown - 1), 1000);
      return () => clearTimeout(timer);
    }
  }, [resendCooldown]);

  // Exchange Firebase ID Token with OmniFace Session
  const exchangeFirebaseToken = async (user: User) => {
    const idToken = await user.getIdToken(true);

    const res = await fetch('/api/v1/auth/firebase-verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    });

    const data = await res.json();

    if (res.ok && data.success) {
      showToast(`Access granted. Welcome to OmniFace, ${data.user.fullName}!`, 'success');
      if (!data.user.orgId) {
        window.location.href = '/onboarding';
      } else {
        window.location.href = redirectTarget;
      }
      return true;
    } else if (data.code === 'EMAIL_NOT_VERIFIED') {
      setUnverifiedEmail(user.email || '');
      setVerificationPending(true);
      setErrorMsg(data.error);
      return false;
    } else {
      throw new Error(data.error || 'Enterprise authorization rejected.');
    }
  };

  // Google Sign-In Handler
  const handleGoogleSignIn = async () => {
    setGoogleLoading(true);
    setErrorMsg(null);

    try {
      const result = await signInWithPopup(auth, googleProvider);
      const user = result.user;

      // Google accounts are pre-verified
      if (!user.emailVerified) {
        setUnverifiedEmail(user.email || '');
        setVerificationPending(true);
        setErrorMsg('Please verify your Gmail address to continue.');
        return;
      }

      await exchangeFirebaseToken(user);
    } catch (err: any) {
      if (err.code === 'auth/popup-closed-by-user') {
        showToast('Google authentication cancelled', 'info');
      } else {
        const msg = err?.message || 'Google authentication failed';
        setErrorMsg(msg);
        showToast(msg, 'error');
      }
    } finally {
      setGoogleLoading(false);
    }
  };

  // Email / Password Form Submit
  const handleEmailAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setErrorMsg('Please enter both email and password.');
      return;
    }

    setLoading(true);
    setErrorMsg(null);

    try {
      if (authMode === 'signup') {
        const cred = await createUserWithEmailAndPassword(auth, email, password);
        // Send email verification immediately
        await sendEmailVerification(cred.user);
        setUnverifiedEmail(email);
        setVerificationPending(true);
        setResendCooldown(60);
        showToast('Account registered! A verification link was sent to your email.', 'success');
      } else {
        // Sign In
        const cred = await signInWithEmailAndPassword(auth, email, password);
        if (!cred.user.emailVerified) {
          setUnverifiedEmail(cred.user.email || email);
          setVerificationPending(true);
          setErrorMsg('Your email is not verified yet. Please check your inbox.');
          return;
        }
        await exchangeFirebaseToken(cred.user);
      }
    } catch (err: any) {
      let msg = err?.message || 'Authentication failed.';
      if (err.code === 'auth/invalid-credential' || err.code === 'auth/user-not-found' || err.code === 'auth/wrong-password') {
        msg = 'Invalid email or password. Check credentials or sign up.';
      } else if (err.code === 'auth/email-already-in-use') {
        msg = 'An account already exists with this email. Please sign in instead.';
      } else if (err.code === 'auth/weak-password') {
        msg = 'Password should be at least 6 characters.';
      }
      setErrorMsg(msg);
      showToast(msg, 'error');
    } finally {
      setLoading(false);
    }
  };

  // Resend Verification Email
  const handleResendEmail = async () => {
    if (resendCooldown > 0) return;
    try {
      if (auth.currentUser) {
        await sendEmailVerification(auth.currentUser);
        setResendCooldown(60);
        showToast('Verification email resent! Check your inbox and spam folder.', 'success');
      } else {
        showToast('Please sign in first to request a new verification link.', 'error');
      }
    } catch (err: any) {
      showToast(err?.message || 'Failed to resend verification email.', 'error');
    }
  };

  // Check Email Verification Status
  const handleCheckVerification = async () => {
    setCheckingVerification(true);
    try {
      if (auth.currentUser) {
        await reload(auth.currentUser);
        if (auth.currentUser.emailVerified) {
          showToast('Email verified successfully! Initializing fleet session...', 'success');
          await exchangeFirebaseToken(auth.currentUser);
        } else {
          showToast('Email not verified yet. Please click the link in your email.', 'info');
        }
      } else {
        setVerificationPending(false);
      }
    } catch (err: any) {
      showToast(err?.message || 'Could not verify status. Please try again.', 'error');
    } finally {
      setCheckingVerification(false);
    }
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
        background: 'radial-gradient(ellipse at 50% -10%, rgba(16, 185, 129, 0.12), transparent 50%), radial-gradient(ellipse at 50% 110%, rgba(6, 182, 212, 0.08), transparent 55%), #06080D',
        padding: '24px 16px',
        position: 'relative',
        overflow: 'hidden',
        fontFamily: 'var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif)',
      }}
    >
      {/* Background Cybernetic Matrix Grid Lines */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          backgroundImage: `linear-gradient(rgba(255, 255, 255, 0.02) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.02) 1px, transparent 1px)`,
          backgroundSize: '48px 48px',
          maskImage: 'radial-gradient(circle at 50% 50%, black 35%, transparent 75%)',
          WebkitMaskImage: 'radial-gradient(circle at 50% 50%, black 35%, transparent 75%)',
          pointerEvents: 'none',
        }}
      />

      {/* Main Glassmorphic Authentication Terminal */}
      <div
        style={{
          width: '100%',
          maxWidth: '440px',
          background: 'rgba(11, 15, 25, 0.78)',
          backdropFilter: 'blur(36px)',
          WebkitBackdropFilter: 'blur(36px)',
          borderRadius: '24px',
          border: '1px solid rgba(255, 255, 255, 0.09)',
          boxShadow: '0 24px 64px -12px rgba(0, 0, 0, 0.8), 0 0 0 1px rgba(16, 185, 129, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.15)',
          padding: '36px 32px',
          zIndex: 10,
          position: 'relative',
        }}
      >
        {/* Holographic Glowing Header */}
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '56px',
              height: '56px',
              borderRadius: '16px',
              background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(6, 182, 212, 0.2))',
              border: '1px solid rgba(16, 185, 129, 0.4)',
              boxShadow: '0 0 24px rgba(16, 185, 129, 0.25)',
              marginBottom: '16px',
              position: 'relative',
            }}
          >
            {/* Biometric Iris Mesh SVG */}
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" />
              <circle cx="12" cy="12" r="3" />
              <circle cx="12" cy="12" r="6" stroke="#06B6D4" strokeDasharray="3 3" />
            </svg>
            <span
              style={{
                position: 'absolute',
                top: '-4px',
                right: '-4px',
                width: '10px',
                height: '10px',
                borderRadius: '50%',
                background: '#10B981',
                boxShadow: '0 0 8px #10B981',
              }}
            />
          </div>

          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '6px',
              padding: '4px 10px',
              borderRadius: '999px',
              background: 'rgba(16, 185, 129, 0.1)',
              border: '1px solid rgba(16, 185, 129, 0.25)',
              marginBottom: '10px',
            }}
          >
            <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: '#10B981' }} />
            <span style={{ fontSize: '10.5px', fontWeight: 700, letterSpacing: '0.08em', color: '#10B981', textTransform: 'uppercase' }}>
              Cloud Fleet Authority
            </span>
          </div>

          <h1
            style={{
              fontSize: '24px',
              fontWeight: 800,
              letterSpacing: '-0.03em',
              color: '#F8FAFC',
              margin: '0 0 6px 0',
            }}
          >
            {verificationPending ? 'Verify Your Email' : authMode === 'signin' ? 'Sign In to OmniFace' : 'Create Organization Fleet'}
          </h1>
          <p style={{ fontSize: '13px', color: '#94A3B8', margin: 0 }}>
            {verificationPending
              ? 'Click the link sent to your inbox to activate administrative access.'
              : authMode === 'signin'
              ? 'Institutional Biometric Access Control & Attendance'
              : 'Setup new autonomous attendance fleet in 60 seconds'}
          </p>
        </div>

        {/* VIEW 1: EMAIL VERIFICATION PENDING */}
        {verificationPending ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            <div
              style={{
                background: 'rgba(15, 23, 42, 0.65)',
                border: '1px solid rgba(16, 185, 129, 0.25)',
                borderRadius: '16px',
                padding: '20px',
                textAlign: 'center',
              }}
            >
              <div
                style={{
                  width: '48px',
                  height: '48px',
                  borderRadius: '50%',
                  background: 'rgba(16, 185, 129, 0.12)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginBottom: '12px',
                }}
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#10B981" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="20" height="16" x="2" y="4" rx="2" />
                  <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
                </svg>
              </div>

              <div style={{ fontSize: '13px', color: '#CBD5E1', marginBottom: '8px' }}>
                We sent a verification link to:
              </div>
              <div
                style={{
                  fontSize: '14px',
                  fontWeight: 700,
                  color: '#10B981',
                  background: 'rgba(16, 185, 129, 0.08)',
                  padding: '6px 12px',
                  borderRadius: '8px',
                  display: 'inline-block',
                  border: '1px solid rgba(16, 185, 129, 0.2)',
                  wordBreak: 'break-all',
                }}
              >
                {unverifiedEmail}
              </div>
            </div>

            {/* Quick Action: Open Gmail */}
            <a
              href="https://mail.google.com"
              target="_blank"
              rel="noopener noreferrer"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '10px',
                width: '100%',
                padding: '12px 16px',
                borderRadius: '14px',
                background: 'linear-gradient(135deg, rgba(234, 67, 53, 0.15), rgba(251, 188, 4, 0.1))',
                border: '1px solid rgba(234, 67, 53, 0.3)',
                color: '#F8FAFC',
                fontSize: '13.5px',
                fontWeight: 600,
                textDecoration: 'none',
                transition: 'all 0.15s ease',
              }}
            >
              <svg width="18" height="18" viewBox="0 0 24 24">
                <path fill="#EA4335" d="M24 5.457v13.909c0 .904-.732 1.636-1.636 1.636h-3.819V11.73L12 16.64l-6.545-4.91v9.273H1.636A1.636 1.636 0 0 1 0 19.366V5.457c0-2.023 2.309-3.178 3.927-1.964L5.455 4.64 12 9.548l6.545-4.91 1.528-1.145C21.69 2.28 24 3.434 24 5.457z" />
              </svg>
              Open Gmail Inbox
            </a>

            {/* I Have Verified Button */}
            <button
              onClick={handleCheckVerification}
              disabled={checkingVerification}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px',
                width: '100%',
                padding: '13px',
                borderRadius: '14px',
                background: 'linear-gradient(135deg, #10B981 0%, #059669 100%)',
                border: 'none',
                color: '#FFFFFF',
                fontSize: '14px',
                fontWeight: 700,
                cursor: checkingVerification ? 'not-allowed' : 'pointer',
                boxShadow: '0 8px 24px rgba(16, 185, 129, 0.35)',
                transition: 'transform 0.15s ease',
              }}
            >
              {checkingVerification ? 'Checking Verification...' : "I've Verified My Email"}
            </button>

            {/* Resend Link & Return Buttons */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '4px' }}>
              <button
                type="button"
                onClick={handleResendEmail}
                disabled={resendCooldown > 0}
                style={{
                  background: 'none',
                  border: 'none',
                  color: resendCooldown > 0 ? '#64748B' : '#06B6D4',
                  fontSize: '12.5px',
                  cursor: resendCooldown > 0 ? 'default' : 'pointer',
                  fontWeight: 500,
                  padding: 0,
                }}
              >
                {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : 'Resend verification link'}
              </button>

              <button
                type="button"
                onClick={() => setVerificationPending(false)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#94A3B8',
                  fontSize: '12.5px',
                  cursor: 'pointer',
                  padding: 0,
                }}
              >
                ← Back to sign in
              </button>
            </div>
          </div>
        ) : (
          /* VIEW 2: NORMAL SIGN IN / SIGN UP */
          <>
            {/* Mode Switcher Tabs */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: '1fr 1fr',
                background: 'rgba(15, 23, 42, 0.8)',
                borderRadius: '14px',
                padding: '4px',
                marginBottom: '20px',
                border: '1px solid rgba(255, 255, 255, 0.06)',
              }}
            >
              <button
                type="button"
                onClick={() => { setAuthMode('signin'); setErrorMsg(null); }}
                style={{
                  padding: '9px',
                  borderRadius: '10px',
                  border: 'none',
                  fontSize: '13px',
                  fontWeight: 600,
                  color: authMode === 'signin' ? '#FFFFFF' : '#94A3B8',
                  background: authMode === 'signin' ? 'linear-gradient(135deg, rgba(255,255,255,0.12), rgba(255,255,255,0.04))' : 'transparent',
                  boxShadow: authMode === 'signin' ? '0 4px 12px rgba(0,0,0,0.3)' : 'none',
                  cursor: 'pointer',
                  transition: 'all 0.15s ease',
                }}
              >
                Sign In
              </button>
              <button
                type="button"
                onClick={() => { setAuthMode('signup'); setErrorMsg(null); }}
                style={{
                  padding: '9px',
                  borderRadius: '10px',
                  border: 'none',
                  fontSize: '13px',
                  fontWeight: 600,
                  color: authMode === 'signup' ? '#FFFFFF' : '#94A3B8',
                  background: authMode === 'signup' ? 'linear-gradient(135deg, rgba(255,255,255,0.12), rgba(255,255,255,0.04))' : 'transparent',
                  boxShadow: authMode === 'signup' ? '0 4px 12px rgba(0,0,0,0.3)' : 'none',
                  cursor: 'pointer',
                  transition: 'all 0.15s ease',
                }}
              >
                Create Fleet
              </button>
            </div>

            {/* Error Banner */}
            {errorMsg && (
              <div
                style={{
                  background: 'rgba(239, 68, 68, 0.12)',
                  border: '1px solid rgba(239, 68, 68, 0.3)',
                  borderRadius: '12px',
                  padding: '10px 14px',
                  color: '#FCA5A5',
                  fontSize: '12.5px',
                  marginBottom: '16px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                }}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span style={{ flex: 1 }}>{errorMsg}</span>
              </div>
            )}

            {/* Primary Hero Action: Continue with Google */}
            <button
              type="button"
              onClick={handleGoogleSignIn}
              disabled={googleLoading || loading}
              style={{
                width: '100%',
                padding: '13px 18px',
                borderRadius: '14px',
                background: 'rgba(255, 255, 255, 0.06)',
                border: '1px solid rgba(255, 255, 255, 0.16)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '12px',
                color: '#FFFFFF',
                fontSize: '14.5px',
                fontWeight: 600,
                cursor: googleLoading ? 'not-allowed' : 'pointer',
                transition: 'all 0.2s ease',
                boxShadow: '0 4px 20px rgba(0, 0, 0, 0.35)',
                marginBottom: '20px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.background = 'rgba(255, 255, 255, 0.11)';
                e.currentTarget.style.borderColor = 'rgba(16, 185, 129, 0.4)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = 'rgba(255, 255, 255, 0.06)';
                e.currentTarget.style.borderColor = 'rgba(255, 255, 255, 0.16)';
              }}
            >
              {googleLoading ? (
                <span>Connecting to Google...</span>
              ) : (
                <>
                  {/* Google Full-Color G Logo SVG */}
                  <svg width="19" height="19" viewBox="0 0 24 24">
                    <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
                    <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
                    <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" />
                    <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" />
                  </svg>
                  <span>Continue with Google</span>
                  <span
                    style={{
                      fontSize: '10px',
                      padding: '2px 6px',
                      borderRadius: '6px',
                      background: 'rgba(16, 185, 129, 0.15)',
                      color: '#10B981',
                      border: '1px solid rgba(16, 185, 129, 0.3)',
                      fontWeight: 700,
                    }}
                  >
                    Verified
                  </span>
                </>
              )}
            </button>

            {/* Divider */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                marginBottom: '20px',
              }}
            >
              <div style={{ flex: 1, height: '1px', background: 'rgba(255, 255, 255, 0.08)' }} />
              <span style={{ fontSize: '11px', color: '#64748B', fontWeight: 600, letterSpacing: '0.05em' }}>
                OR EMAIL ACCESS
              </span>
              <div style={{ flex: 1, height: '1px', background: 'rgba(255, 255, 255, 0.08)' }} />
            </div>

            {/* Email / Password Form */}
            <form onSubmit={handleEmailAuth} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              {authMode === 'signup' && (
                <div>
                  <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: '#CBD5E1', marginBottom: '6px' }}>
                    Organization / Institution Name
                  </label>
                  <input
                    type="text"
                    value={orgName}
                    onChange={(e) => setOrgName(e.target.value)}
                    placeholder="e.g. Apex Tech Campus"
                    style={{
                      width: '100%',
                      padding: '11px 14px',
                      borderRadius: '12px',
                      background: 'rgba(15, 23, 42, 0.65)',
                      border: '1px solid rgba(255, 255, 255, 0.1)',
                      color: '#FFFFFF',
                      fontSize: '14px',
                      outline: 'none',
                    }}
                  />
                </div>
              )}

              <div>
                <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: '#CBD5E1', marginBottom: '6px' }}>
                  Email Address
                </label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="admin@institution.edu"
                  style={{
                    width: '100%',
                    padding: '11px 14px',
                    borderRadius: '12px',
                    background: 'rgba(15, 23, 42, 0.65)',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    color: '#FFFFFF',
                    fontSize: '14px',
                    outline: 'none',
                  }}
                />
              </div>

              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
                  <label style={{ fontSize: '12px', fontWeight: 600, color: '#CBD5E1' }}>
                    Password
                  </label>
                  {authMode === 'signin' && (
                    <span style={{ fontSize: '11.5px', color: '#06B6D4', cursor: 'pointer' }}>
                      Forgot?
                    </span>
                  )}
                </div>
                <div style={{ position: 'relative' }}>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••••••"
                    style={{
                      width: '100%',
                      padding: '11px 40px 11px 14px',
                      borderRadius: '12px',
                      background: 'rgba(15, 23, 42, 0.65)',
                      border: '1px solid rgba(255, 255, 255, 0.1)',
                      color: '#FFFFFF',
                      fontSize: '14px',
                      outline: 'none',
                    }}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    style={{
                      position: 'absolute',
                      right: '12px',
                      top: '50%',
                      transform: 'translateY(-50%)',
                      background: 'none',
                      border: 'none',
                      color: '#64748B',
                      cursor: 'pointer',
                      padding: 0,
                    }}
                  >
                    {showPassword ? 'Hide' : 'Show'}
                  </button>
                </div>
              </div>

              {/* Submit CTA */}
              <button
                type="submit"
                disabled={loading || googleLoading}
                style={{
                  width: '100%',
                  padding: '13px',
                  borderRadius: '14px',
                  background: 'linear-gradient(135deg, #10B981 0%, #059669 100%)',
                  border: 'none',
                  color: '#FFFFFF',
                  fontSize: '14px',
                  fontWeight: 700,
                  cursor: loading ? 'not-allowed' : 'pointer',
                  boxShadow: '0 8px 24px rgba(16, 185, 129, 0.35)',
                  marginTop: '6px',
                  transition: 'all 0.15s ease',
                }}
              >
                {loading ? 'Authenticating...' : authMode === 'signin' ? 'Sign In to Dashboard' : 'Create Organization Fleet'}
              </button>
            </form>
          </>
        )}
      </div>

      {/* Security Proof Footer */}
      <div
        style={{
          marginTop: '24px',
          display: 'flex',
          alignItems: 'center',
          gap: '16px',
          color: '#64748B',
          fontSize: '12px',
          zIndex: 10,
        }}
      >
        <span>End-to-End Sovereign Encryption</span>
        <span>•</span>
        <Link href="/privacy" style={{ color: '#64748B', textDecoration: 'none' }}>Privacy Policy</Link>
        <span>•</span>
        <Link href="/terms" style={{ color: '#64748B', textDecoration: 'none' }}>Terms of Service</Link>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <div style={{ minHeight: '100vh', background: '#06080D', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#10B981' }}>
          Loading OmniFace Biometric Terminal...
        </div>
      }
    >
      <LoginForm />
    </Suspense>
  );
}
