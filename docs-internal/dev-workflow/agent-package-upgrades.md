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

## Model-routing reconciliation

Recheck these four deliberately restated routing facts. Each restatement supports a project
decision that depends on its underlying fact.

- Check the profile markers behind every candidate inclusion and exclusion.
- Check the base model and level pairs under Unsized dispatches.
- Check the accepted residual under What failover ignores.
- Check the dated price step recorded below.

Recheck that every listed model has a measured effort level. A new candidate can falsify this
claim. A refreshed profile table can also falsify it.

Recheck the current candidate rationale against the shipped profiles. The project excludes
`openai/gpt-5.6-terra` because its profile marks it never-auto-select. It has no defensible
routing niche.

The project includes `anthropic/claude-sonnet-5` despite the same marker. Its sole niche is
replacing unavailable Sol at `high`.

The project excludes `anthropic/claude-fable-5` for two reasons. It is never-auto-select and
has no zero-data-retention option.

Prices use dated schedules. `claude-sonnet-5` increases 50 percent on 2026-09-01. This change
alters the table numbers but not their order. The `nonPreferred` setting sorts that model last
before and after the change.

Recheck prices, measured levels, route-for guidance, avoid-for guidance, and profile membership.
These facts can change whenever the package is republished.

## Worker-extension reconciliation

When an upgraded package fixes worker initialization, add `^npm:pi-mcp-adapter(@|$)` to
`workerExtensions`. Recheck one security property first.

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

The 0.10.0 check confirmed the four routing facts above. It also confirmed a measured effort
level for every listed model.
