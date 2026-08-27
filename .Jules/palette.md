## 2026-08-27 - TalkBack Support for Icon-Only Actions

**Learning:** When using icon-only actions (like `IconButton` with an `Icon` inside), adding `contentDescription` directly to the `Icon` can lead to inconsistent or missing TalkBack announcements. The parent `IconButton` needs the semantic meaning.

**Action:** For icon-only Compose actions, apply the accessibility `contentDescription` on the parent button using `Modifier.semantics { contentDescription = "..." }`, and explicitly set the inner `Icon`'s `contentDescription` to `null` to avoid redundant or conflicting accessibility node generation.