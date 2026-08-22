## 2026-08-22 - Pre-Allocate Bitmaps for ML Inference

**Learning:** Android camera pipelines create massive garbage and frame drops when using `Bitmap.createScaledBitmap` inside the frame loop.
**Action:** Always pre-allocate `Bitmap`, `Canvas`, and `Rect` objects at the class level and use `canvas.drawBitmap` to resize camera frames for ML without allocations.
