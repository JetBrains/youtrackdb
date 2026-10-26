# Orchestrator Guidelines

## Workflow and delivery

When planning, verifying, naming branches, committing, or publishing pull requests, read
`docs-internal/dev-workflow/track-development.md` for YTDB rules.

For test authorship, read `docs-internal/agents/thread-guidelines.md` § Test Authorship.
Before measuring coverage, read `docs-internal/dev-workflow/coverage-verification.md`.
Before running Maven, read `docs-internal/agents/thread-guidelines.md` § Test Execution.
For project review charters, read `docs-internal/agents/review-perspectives.md`.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
