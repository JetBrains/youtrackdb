# Agent package upgrades

## Upgrade trigger and workflow

Use this procedure when bumping a pinned agent package version in `.pi/settings.json`.
Treat a `ytdb-slate` pin bump as a tracked change. Run the full workflow.

The new pin takes effect at the next session start. A running session keeps its loaded package
rules.

## Package-document reconciliation

Compare `docs-internal/dev-workflow/track-development.md` with the new package documents.
Also compare `docs-internal/agents/slate-doctrine-extra.md` with them. Fix every resulting
mismatch.

## Reconciliation record

Last reconciled against **ytdb-slate 0.14.0**.

This reconciliation read these package documents:

- `track-workflow.md`
- `recursive-workflow.md`
- `blast-radius.md`
- `review-rules.md`
- `delivery-packages.md`
- `user-notes.md`
- `pr-publishing.md`
- `model-routing.md`
- `writing-guidance.md`
- `context-budget.md`
- `design-principles.md`
- `configuration.md`
- `review-common-policy.md`
- `review-implementation-input.md`
- `review-perspectives/ri.md`
- `review-perspectives/cn.md`
- `review-perspectives/du.md`
- `review-perspectives/se.md`
- `review-perspectives/pf.md`
- `review-perspectives/tq.md`
- `review-perspectives/pl.md`
- `review-perspectives/lx.md`
- `review-perspectives/nl.md`
- `review-perspectives/cb.md`
- `review-perspectives/gr.md`
- `review-perspectives/uf.md`
- `roadmap.md`
- `safety-and-trust.md`

The check covered configured `router` (the logical-model `models` object and `compressor`),
`workflow`, `workerExtensions`, `orchestratorPromptDocs`, `workerPromptDocs`,
`doctrineExtraPath`, and `reviewPerspectivesPath`. It also covered the `thread` tool contract.
Every call starts a new thread and needs `type`, a logical `model`, and `reason`.
The `thread` tool also accepts `reviewPerspectives` for built-in reviews. Implementer threads
need `trackNumber` while a change is open. It accepts positive integers or canonical dotted
track identifiers. Project charters still use `reviewPerspectivesPath`.
The `slate_change start` and `slate_change close` commands manage records under
`slate-changes/<change>/`. The `writing.showStatus` key controls writing status output.
Draft publishing uses one draft pull request per level with code tracks.
Implementer reports are append-only. Follow `recursive-workflow.md` § Manual records and
safe writes for temporary files, `>>` appends, and whole-report checks.
Terminal, native and bell notifications default to on. Push defaults to off.
Home settings control channel permission and text detail. A trusted project can only
turn channels off or lower detail. See `configuration.md` § Notifications.
