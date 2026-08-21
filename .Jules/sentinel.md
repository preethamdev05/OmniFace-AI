## 2025-02-28 - Screenshot Blocking on Sensitive Screens

**Vulnerability:** Screenshot leakage of sensitive screens. The main activity did not have FLAG_SECURE set, allowing screenshots and screen recordings of potentially sensitive biometric and attendance data.

**Learning:** Sensitive biometric screens must explicitly block screen captures. Android requires this flag to be set programmatically per-Activity window.

**Prevention:** Ensure `window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)` or `window.setFlags` is applied in `onCreate()` for all activities displaying sensitive data (biometrics, PII).
