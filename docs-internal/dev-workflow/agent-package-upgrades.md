# Agent package upgrades

## Upgrade trigger and workflow

Use this procedure when bumping a pinned agent package version in `.pi/settings.json`.
Treat a `ytdb-slate` pin bump as a tracked change.

## Package-document reconciliation

Compare `docs-internal/dev-workflow/track-development.md` with the new package documents.
Also compare `docs-internal/agents/slate-doctrine-extra.md` with them. Fix every resulting
mismatch.

Project workflow documents state only YTDB-specific differences from the installed package.
Delete package-rule repeats. Link to a package section only when a YTDB difference cannot be
understood without it. Give each YTDB-specific rule one owning project document.

## Reconciliation record

Last reconciled against **ytdb-slate 0.16.0**.

This reconciliation read these package documents:

- `track-workflow.md`
- `recursive-workflow.md`
- `change-records.md`
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

The check covered the YTDB configuration in `.pi/slate.json` and its prompt documents.
It also covered worker action folders and `change-records.md` § Manual records and safe writes.
