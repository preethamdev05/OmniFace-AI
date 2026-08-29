## 2026-08-29 - TalkBack Accessibility for Icon Buttons

**Learning:** TalkBack screen readers announce `IconButton` incorrectly if the `contentDescription` is only placed on the inner `Icon`. It's essential to put the description on the parent.

**Action:** For icon-only actions (like `IconButton`), provide accessibility support by applying `contentDescription` inside `Modifier.semantics { contentDescription = "..." }` on the parent button, and set the inner `Icon`'s `contentDescription` to `null`.
