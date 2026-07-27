# YouTrackDB Internal Documentation

Project-internal documentation for contributors and agents: the development
workflow, role guidelines, and the architecture-decision archive. User-facing
product documentation lives in the [user documentation index](../docs/README.md).

## Table of Contents

| Document | Description |
|---|---|
| [Track-Based Development](dev-workflow/track-development.md) | YTDB deltas on the generic track-based workflow (the baseline ships with the ytdb-slate package as track-workflow.md and pr-publishing.md) — `develop` base branch, issue-prefix/PR-template conventions, umbrella-PR peer-review policy, package pin-bump rule |
| [TinkerPop Fork Dependency](dev-workflow/tinkerpop-fork-dependency.md) | `io.youtrackdb:gremlin-*` SNAPSHOT pin and its 90-day Sonatype expiry — the twice-monthly refresh workflow, recovering a purged snapshot, bumping the pin |
| [Orchestrator Guidelines](agents/orchestrator-guidelines.md) | Planning and delivery rules for the orchestrator role — track workflow, test policy, pre-commit verification scope, git/PR conventions, documentation sync |
| [Worker Thread Guidelines](agents/thread-guidelines.md) | Hands-on engineering rules for worker threads — build commands, code style, Spotless, testing, committing, codebase tips |
| [Architecture Decision Records](adr/) | Archive of Architecture Decision Records, plus historical/sunset workflow research logs |
