# YouTrackDB Documentation

## Table of Contents

| Document | Description |
|---|---|
| [Getting Started](getting-started.md) | Tutorial covering schema, CRUD, MATCH traversals, and transactions using YQL |
| [Object-Oriented Data Modeling](object-oriented.md) | Inheritance, polymorphic queries, property types, schema evolution |
| [Fine-Grained Security](security.md) | Predicate-based security policies, per-role filtering, ALTER/REVOKE lifecycle |
| [Development Workflow](workflow-book/chapters/01-workflow-at-a-glance.md) | Contributor onboarding book for the YouTrackDB development workflow — phases, tiers, tracks, and reviews — in 16 chapters. Start at Chapter 1. |
| [Track-Based Development](dev-workflow/track-development.md) | Mandatory baseline flow for all changes — research, user design review, adversarial review, umbrella draft PR, per-track code review and mandatory user review gate, marker commits |
| [Satellite Review PRs](dev-workflow/satellite-pr.md) | Draft-only per-track peer-review PRs for multi-track changes (single-track peer review runs on the umbrella PR) — pinned base/head branches, observation loop, rebase re-pinning, cleanup at merge |
| [Orchestrator Guidelines](agents/orchestrator-guidelines.md) | Planning and delivery rules for the orchestrator role — track workflow, test policy, pre-commit verification scope, git/PR conventions, documentation sync |
| [Worker Thread Guidelines](agents/thread-guidelines.md) | Hands-on engineering rules for worker threads — build commands, code style, Spotless, testing, committing, codebase tips |
