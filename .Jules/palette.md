## 2024-05-18 - Improve Semantic Support for Icon Buttons

**Learning:** When using an `IconButton` that only contains an `Icon`, the `Icon`'s `contentDescription` should be set to `null` while the `IconButton` parent itself is given the `contentDescription` through a `Modifier.semantics { contentDescription = "..." }`. This ensures screen readers like TalkBack properly announce the button and its action.

**Action:** Look for `IconButton`s that contain `Icon`s with hardcoded strings for `contentDescription`. Refactor them to move the content description up to the parent using the `Modifier.semantics` for better accessibility support.
