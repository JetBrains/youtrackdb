# YouTrackDB Internal Documentation

Project-internal documentation for contributors and agents: the development
workflow, role guidelines, and the architecture-decision archive. User-facing
product documentation lives in the [user documentation index](../docs/README.md).

## Table of Contents

| Document | Description |
|---|---|
| [Track-Based Development](dev-workflow/track-development.md) | YTDB deltas on the generic track-based workflow (the baseline ships with the ytdb-slate package as track-workflow.md and pr-publishing.md) — `develop` base branch, issue-prefix/PR-template conventions, satellite peer-review layer, package pin-bump rule |
| [Design Decisions in Scoped JavaDoc](dev-workflow/design-decisions.md) | Where code design rationale lives (method/class/package-info JavaDoc, owning package for cross-cutting decisions), entry structure, staleness rule; replaces ADR authoring |
| [Satellite Review PRs](dev-workflow/satellite-pr.md) | Draft-only per-track peer-review PRs for multi-track changes (single-track and trivial peer review runs on the umbrella PR) — pinned base/head branches, observation loop, rebase re-pinning, cleanup at merge |
| [Orchestrator Guidelines](agents/orchestrator-guidelines.md) | Planning and delivery rules for the orchestrator role — track workflow, test policy, pre-commit verification scope, git/PR conventions, documentation sync |
| [Worker Thread Guidelines](agents/thread-guidelines.md) | Hands-on engineering rules for worker threads — build commands, code style, Spotless, testing, committing, codebase tips |
| [Architecture Decision Records](adr/) | Frozen archive of Architecture Decision Records from the previous workflow generation, plus historical/sunset workflow research logs — no new entries; new design rationale lives in scoped JavaDoc per [design-decisions.md](dev-workflow/design-decisions.md) |
| [Enable Scoped doclint](backlog/enable-scoped-doclint.md) | Proposed follow-up to enable syntax/reference doclint validation of JavaDoc cross-references; probe data and rollout plan |
