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

## Worker-extension reconciliation

Before enabling workers, confirm the upgraded package fixes `JetBrains/ytdb-slate` issue 50,
tracked in `docs-internal/dev-workflow/mcp-server-configuration.md`.
Then add `^npm:pi-mcp-adapter(@|$)` to `workerExtensions` and recheck the security property below.

The scripting tool does not fully contain its sandbox. A script can reach the file system and
network. A worker with the shell tool gains no new reach. A narrowed tool list retains that
reach. Therefore, narrowed tools do not form a security boundary after worker enablement.

## Reconciliation record

Last reconciled against **ytdb-slate 0.10.0**.

This reconciliation read these package documents:

- `track-workflow.md`
- `pr-publishing.md`
- `model-routing.md`
- `model-failover.md`
- `review-rules.md`
- `writing-guidance.md`
- `thread-cache-cost.md`
- `context-budget.md`
- `design-principles.md`

The check covered configured `router`, `writing`, `threadChoice`, `modelFailover`, and `workflow`
behavior.
