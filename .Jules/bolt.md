## 2024-08-20 - Jetpack Compose Canvas Allocations

**Learning:** Allocating objects (like `android.graphics.Paint` or `Typeface`) inside a Composable's `Canvas` `onDraw` phase or an `AndroidView` factory lambda leads to severe garbage collection (GC) thrashing and dropped frames, especially when rendering at 60 FPS.

**Action:** Always hoist heavy object instantiations out of render loops into `by lazy` properties (if stateless/shared) or `remember` blocks (if stateful).

## 2024-08-20 - ML Kit FaceDetector Lifecycle in Compose

**Learning:** Instantiating ML Kit clients (like `FaceDetection.getClient`) inside `AndroidView` factory blocks can cause memory leaks and redundant allocations on camera flips or recompositions.

**Action:** Use `remember { ... }` combined with `DisposableEffect` to manage the lifecycle of heavy ML clients, ensuring `.close()` is called `onDispose`.
