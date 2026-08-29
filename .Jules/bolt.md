## 2024-11-23 - Fast Unrolled Dot Product for Cosine Similarity

**Learning:** When vector embeddings are already L2-normalized during extraction or database ingestion, full cosine similarity calculations (computing the sum of squares for both vectors) are redundant and computationally wasteful. The sum of squares for normalized vectors is intrinsically 1.0, so the square root of their product is also 1.0.

**Action:** Replace full cosine similarity loops with an 8-way unrolled dot product. This eliminates floating-point divisions, square roots, and leverages ARM NEON SIMD registers, drastically speeding up N:N template matching for pre-normalized Face Recognition embedding vectors.