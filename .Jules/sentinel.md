## 2023-10-24 - Prevent Screenshot Leakage

**Vulnerability:** Screenshot leakage of sensitive screens

**Learning:** MainActivity did not have FLAG_SECURE set, allowing background screen recording malware and manual screenshots to capture sensitive PII, biometric liveness feeds, and attendance records on screen.

**Prevention:** Apply WindowManager.LayoutParams.FLAG_SECURE to the window in MainActivity's onCreate() to prevent screenshots and screen recordings.
