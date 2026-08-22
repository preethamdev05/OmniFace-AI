## 2025-05-18 - Zip Slip Vulnerability

**Vulnerability:** Path traversal (Zip Slip) in QualcommSuiteDownloadManager unzip method allows overwriting arbitrary files by supplying malicious zip with `../` entries.

**Learning:** When using `java.util.zip.ZipInputStream`, the file name must be validated against the destination path because `java.util.zip.ZipEntry` is not sanitized against path traversals.

**Prevention:** Always validate `outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)` when decompressing archives in Java/Kotlin.