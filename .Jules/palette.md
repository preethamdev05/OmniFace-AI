## 2024-05-24 - Provide Meaningful Content Descriptions for Icon-Only Buttons

**Learning:** When using `IconButton` (or other icon-only actions), applying `contentDescription` directly to the inner `Icon` is less ideal because it might not expose the button's action properly or may lead to nested/redundant focus elements.

**Action:** Add accessibility support by applying `contentDescription` inside `Modifier.semantics { contentDescription = "..." }` on the parent `IconButton`, and setting the inner `Icon`'s `contentDescription` to `null`.
