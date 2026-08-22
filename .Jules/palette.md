## 2026-08-22 - Jetpack Compose IconButton TalkBack Accessibility

**Learning:** Setting `contentDescription` directly on the inner `Icon` of an `IconButton` can lead to inconsistent TalkBack behavior. Applying `contentDescription` to the parent `IconButton` via `Modifier.semantics` is the preferred Compose accessibility standard.

**Action:** Always use `Modifier.semantics { contentDescription = "..." }` on the `IconButton` itself and set the inner `Icon`'s `contentDescription` to `null`.
