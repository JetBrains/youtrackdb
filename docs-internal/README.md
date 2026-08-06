# YouTrackDB Internal Documentation

Project-internal documentation for contributors and agents: the development
workflow, role guidelines, and the architecture-decision archive. User-facing
product documentation lives in the [user documentation index](../docs/README.md).

## Table of Contents

| Document | Description |
|---|---|
| [Track-Based Development](dev-workflow/track-development.md) | YTDB deltas on the generic track-based workflow (the baseline ships with the ytdb-slate package as track-workflow.md and pr-publishing.md) — `develop` base branch, issue-prefix/PR-template conventions, umbrella-PR peer-review policy, track verification gates, action-level model-routing and failover configuration, package pin-bump rule |
| [Coverage Verification](dev-workflow/coverage-verification.md) | On-demand coverage procedure — command sequence, report-set assertion, result diagnosis, and local-versus-CI differences |
| [Orchestrator Guidelines](agents/orchestrator-guidelines.md) | Planning and delivery rules for the orchestrator role — track workflow, test policy, verification scope, git/PR conventions, documentation sync |
| [Worker Thread Guidelines](agents/thread-guidelines.md) | Hands-on engineering rules for worker threads — build commands, code style, Spotless, testing, committing, web tools, codebase tips |
| [Architecture Decision Records](adr/) | Archive of Architecture Decision Records, plus historical/sunset workflow research logs |
