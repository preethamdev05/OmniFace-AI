## 2024-08-27 - Screenshot leakage of sensitive screens prevention

**Vulnerability:** Screenshot leakage of sensitive screens such as Face Embeddings and Biometric Settings due to lacking `FLAG_SECURE` configuration on application wide activities.

**Learning:** The application is a Single-Activity Jetpack Compose Hub (`MainActivity`). Any sensitive screens rendered via composables are vulnerable to screenshot or screen recording if the underlying activity does not have `FLAG_SECURE` applied.

**Prevention:** `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)` must be added in the `onCreate` method of `MainActivity` to disable screenshots and screen recordings on all screens.
