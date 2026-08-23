## 2025-05-20 - Screenshot leakage of sensitive screens

**Vulnerability:** The application handles sensitive biometric data, student attendance records, and administrator actions, but did not have screenshot protection enabled. This could lead to screenshot leakage of sensitive screens, exposing facial data, enrollment details, or system configuration.

**Learning:** When developing apps that handle biometric and high-security data, it is critical to prevent the OS or other background applications from recording the screen or taking screenshots. A single-activity architecture allows applying this protection globally at the root activity level.

**Prevention:** Apply `WindowManager.LayoutParams.FLAG_SECURE` to the `Window` in `onCreate()` for any Activities displaying sensitive information, ensuring the flag is set before rendering content.
