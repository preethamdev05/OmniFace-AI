## 2026-08-29 - Hardcoded CDN Secret

**Vulnerability:** Hardcoded shared secret (`omniface-secure-2025`) for Cloudflare Model CDN found in `QualcommSuiteDownloadManager.kt`.

**Learning:** Secrets used for authenticating with external services should never be hardcoded in the application source code, as they can be easily extracted from the compiled APK.

**Prevention:** Use an empty string or null as the default and rely on secure, dynamically retrieved tokens (like those managed by `HfSecureGateway`) or build configuration variables (`BuildConfig`) that are injected securely during the CI/CD process.