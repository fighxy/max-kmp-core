Vendored copies of the shared Orbitle fixtures, unchanged.

Source: repository `fighxy/Orbitle`, branch `ios/evening`, commit `8f859e1`, directory
`test-fixtures/`. Copied files (the core-level ones), all replayed by `SharedFixturesTest`:

- `names/phone-normalize.json`, `names/address-book.json`, `names/display-name.json`
- `drafts/merge.json`, `drafts/outgoing.json` (address and request body only)
- `formatting/parse-defaults.json`, `formatting/parse-overlap.json`, `formatting/parse-types.json`,
  `formatting/parse-unknown.json`, `formatting/parse-utf16.json`
- `members/search.json`, `members/paging.json`, `members/roles.json`
- `selection/delete.json`, `selection/forward.json`

Not copied: composer and screen rules of the apps (`formatting/serialize-*`, `toggle`,
`replace`, `edit`, `selection/copy.json`) and the sets not reviewed yet (`calls/`, `readers/`,
`typing/`).
