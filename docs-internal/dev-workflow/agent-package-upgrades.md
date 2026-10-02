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

Last reconciled against **ytdb-slate 0.13.0**.

This reconciliation read these package documents:

- `track-workflow.md`
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
need `trackNumber` while a change is open. Project charters still use `reviewPerspectivesPath`.
The `slate_change start` and `slate_change close` commands manage records under
`slate-changes/<change>/`. The `writing.showStatus` key controls writing status output.
Draft publishing always uses one umbrella pull request.
