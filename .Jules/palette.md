## 2026-08-28 - Improve TalkBack accessibility for IconButtons
**Learning:** When making an `IconButton` with a nested `Icon`, TalkBack will not effectively announce the intended action if the `contentDescription` is only placed on the inner `Icon`.
**Action:** Add `.semantics { contentDescription = "Action Description" }` to the modifier of the parent `IconButton` and set the nested `Icon`'s `contentDescription` to `null`.
