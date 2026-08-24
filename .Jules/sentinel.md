## 2026-08-24 - Arbitrary File Access via FileProvider
**Vulnerability:** FileProvider exposes the entire app data, cache, and external files directories (using path="."), enabling an arbitrary file read/write vulnerability to a malicious application accessing shared URIs.
**Learning:** Broad path "." mappings in file_paths.xml create unintentional path traversal risks allowing an attacker to escape intended boundaries and read sensitive data, like biometric databases or cached files.
**Prevention:** Strictly define exact directories used for specific data sharing tasks (e.g. `<cache-path name="exports" path="exports/" />`) and strictly adhere to the principle of least privilege.
