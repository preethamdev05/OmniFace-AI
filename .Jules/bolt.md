## 2024-05-24 - Avoid Redundant L2 Norms on Pre-Normalized Embeddings

**Learning:** Cosine similarity requires computing the dot product divided by the product of the L2 norms of both vectors. However, when embeddings are already L2-normalized during extraction or database preloading, their norms are always 1. Computing the norms and their square root per comparison in a high-frequency matching loop adds unnecessary float operations and CPU cycles.

**Action:** For matching L2-normalized vectors (like ArcFace embeddings), replace full cosine similarity with a pure dot product: `sum += a[i] * b[i]`. This saves ~1024 math operations (multiplications, additions, and an expensive `sqrt`) per template comparison, significantly improving candidate scoring throughput, especially for large enrolled template databases.
