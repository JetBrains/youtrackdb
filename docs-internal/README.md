# YouTrackDB Internal Documentation

Project-internal documentation for contributors and agents: the development
workflow, role guidelines, and the architecture-decision archive. User-facing
product documentation lives in the [user documentation index](../docs/README.md).

## Table of Contents

| Document | Description |
|---|---|
| [Track-Based Development](dev-workflow/track-development.md) | Mandatory baseline flow for all changes — research, user design review, adversarial review, umbrella draft PR, per-track code review and mandatory user review gate, marker commits |
| [Satellite Review PRs](dev-workflow/satellite-pr.md) | Draft-only per-track peer-review PRs for multi-track changes (single-track and trivial peer review runs on the umbrella PR) — pinned base/head branches, observation loop, rebase re-pinning, cleanup at merge |
| [Orchestrator Guidelines](agents/orchestrator-guidelines.md) | Planning and delivery rules for the orchestrator role — track workflow, test policy, pre-commit verification scope, git/PR conventions, documentation sync |
| [Worker Thread Guidelines](agents/thread-guidelines.md) | Hands-on engineering rules for worker threads — build commands, code style, Spotless, testing, committing, codebase tips |
| [Architecture Decision Records](adr/) | Archive of Architecture Decision Records, plus historical/sunset workflow research logs |
