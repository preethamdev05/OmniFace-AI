'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';

export default function OnboardingPage() {
  const router = useRouter();
  const [step, setStep] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [formData, setFormData] = useState({
    orgName: '',
    orgType: 'SCHOOL',
    contactEmail: '',
    contactPhone: '',
    defaultStartTime: '09:00',
    graceMinutes: 15,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.orgName || !formData.contactEmail) {
      setError('Organization name and contact email are required.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await fetch('/api/v1/onboarding', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData),
      });

      const data = await res.json();
      if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to complete onboarding');
      }

      // Successful onboarding! Redirect to subscription/upgrade page or dashboard
      router.push('/subscription?onboarding=complete');
    } catch (err: any) {
      setError(err?.message || 'Something went wrong during setup.');
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col justify-center items-center px-4 py-12">
      <div className="w-full max-w-xl bg-slate-900 border border-slate-800 rounded-2xl p-8 shadow-2xl backdrop-blur-sm">
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-xl text-white shadow-lg shadow-indigo-500/30">
            Ω
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-white">OmniFace AI</h1>
            <p className="text-xs text-slate-400 font-medium">Institution Workspace Onboarding</p>
          </div>
        </div>

        {/* Stepper Progress */}
        <div className="flex items-center justify-between mb-8 border-b border-slate-800 pb-4">
          <div className="flex items-center space-x-2">
            <span className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold ${step >= 1 ? 'bg-indigo-600 text-white' : 'bg-slate-800 text-slate-400'}`}>
              1
            </span>
            <span className={`text-xs font-medium ${step >= 1 ? 'text-indigo-400' : 'text-slate-500'}`}>Organization</span>
          </div>
          <div className="h-0.5 w-12 bg-slate-800" />
          <div className="flex items-center space-x-2">
            <span className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold ${step >= 2 ? 'bg-indigo-600 text-white' : 'bg-slate-800 text-slate-400'}`}>
              2
            </span>
            <span className={`text-xs font-medium ${step >= 2 ? 'text-indigo-400' : 'text-slate-500'}`}>Policy & Schedule</span>
          </div>
          <div className="h-0.5 w-12 bg-slate-800" />
          <div className="flex items-center space-x-2">
            <span className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold ${step >= 3 ? 'bg-indigo-600 text-white' : 'bg-slate-800 text-slate-400'}`}>
              3
            </span>
            <span className={`text-xs font-medium ${step >= 3 ? 'text-indigo-400' : 'text-slate-500'}`}>Confirm</span>
          </div>
        </div>

        {error && (
          <div className="mb-6 p-4 rounded-xl bg-rose-500/10 border border-rose-500/30 text-rose-300 text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-6">
          {step === 1 && (
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Organization Name *
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Stanford Academy of Technology"
                  value={formData.orgName}
                  onChange={(e) => setFormData({ ...formData, orgName: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-sm text-white focus:outline-none focus:border-indigo-500 transition-colors"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Institution Type
                </label>
                <select
                  value={formData.orgType}
                  onChange={(e) => setFormData({ ...formData, orgType: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-sm text-white focus:outline-none focus:border-indigo-500 transition-colors"
                >
                  <option value="SCHOOL">School / K-12</option>
                  <option value="COLLEGE">College / University</option>
                  <option value="COACHING">Coaching Institute / Academy</option>
                  <option value="CORPORATE">Corporate Office / Enterprise</option>
                  <option value="GYM_EVENT">Gym / Event Venue</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Official Contact Email *
                </label>
                <input
                  type="email"
                  required
                  placeholder="admin@institution.edu"
                  value={formData.contactEmail}
                  onChange={(e) => setFormData({ ...formData, contactEmail: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-sm text-white focus:outline-none focus:border-indigo-500 transition-colors"
                />
              </div>

              <div className="pt-4 flex justify-end">
                <button
                  type="button"
                  onClick={() => {
                    if (!formData.orgName || !formData.contactEmail) {
                      setError('Please enter organization name and contact email.');
                      return;
                    }
                    setError(null);
                    setStep(2);
                  }}
                  className="px-6 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-sm transition shadow-lg shadow-indigo-600/20"
                >
                  Continue →
                </button>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Default Shift Start Time
                </label>
                <input
                  type="time"
                  value={formData.defaultStartTime}
                  onChange={(e) => setFormData({ ...formData, defaultStartTime: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-sm text-white focus:outline-none focus:border-indigo-500 transition-colors"
                />
                <p className="text-xs text-slate-500 mt-1">Arrivals after this time plus grace minutes will be tagged as LATE.</p>
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
                  Grace Period (Minutes)
                </label>
                <input
                  type="number"
                  min="0"
                  max="120"
                  value={formData.graceMinutes}
                  onChange={(e) => setFormData({ ...formData, graceMinutes: parseInt(e.target.value) || 0 })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-sm text-white focus:outline-none focus:border-indigo-500 transition-colors"
                />
              </div>

              <div className="pt-4 flex justify-between">
                <button
                  type="button"
                  onClick={() => setStep(1)}
                  className="px-5 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold text-sm transition"
                >
                  ← Back
                </button>
                <button
                  type="button"
                  onClick={() => setStep(3)}
                  className="px-6 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-sm transition shadow-lg shadow-indigo-600/20"
                >
                  Review Details →
                </button>
              </div>
            </div>
          )}

          {step === 3 && (
            <div className="space-y-4">
              <div className="bg-slate-950/60 rounded-xl p-4 border border-slate-800/80 space-y-3">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-400">Organization:</span>
                  <span className="font-semibold text-white">{formData.orgName}</span>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-slate-400">Type:</span>
                  <span className="font-semibold text-white">{formData.orgType}</span>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-slate-400">Contact:</span>
                  <span className="font-semibold text-white">{formData.contactEmail}</span>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-slate-400">Attendance Baseline:</span>
                  <span className="font-semibold text-white">{formData.defaultStartTime} (+{formData.graceMinutes}m grace)</span>
                </div>
                <div className="flex justify-between text-xs pt-2 border-t border-slate-800">
                  <span className="text-slate-400">Initial Entitlement:</span>
                  <span className="font-semibold text-emerald-400">Free Tier (25 users, 1 kiosk)</span>
                </div>
              </div>

              <p className="text-xs text-slate-400 text-center">
                By creating this organization, you will be designated as the <strong className="text-white">OWNER</strong> with full administrative authority.
              </p>

              <div className="pt-4 flex justify-between">
                <button
                  type="button"
                  onClick={() => setStep(2)}
                  className="px-5 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold text-sm transition"
                >
                  ← Back
                </button>
                <button
                  type="submit"
                  disabled={loading}
                  className="px-6 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-semibold text-sm transition shadow-lg shadow-indigo-600/20"
                >
                  {loading ? 'Creating Workspace...' : 'Launch Workspace 🚀'}
                </button>
              </div>
            </div>
          )}
        </form>
      </div>
    </div>
  );
}
