# Design Mutation Log

## Mutation 1 — 2026-05-21 — structural-rewrite (design.md)

**Diff summary**: Phase 2 consistency review surfaced three small fact-corrections
across three sections of `design.md`. §"Workflow" sequenceDiagram now omits the
nonexistent `baseRepository` field from `gh pr view --json` and shows the separate
`gh repo view --json nameWithOwner` call that resolves owner/repo. §"HEAD-SHA
verification" Edge cases replaces the inaccurate "detached HEAD by default" claim
with the actual `gh pr checkout` default (named branch; `--detach` opts in).
§"gh-api submission payload" Edge cases clarifies that the `files` field returns
an array of `{path, additions, deletions, changeType}` objects, not bare paths.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS (0 findings)

**Findings**:
- (none — both phases clean on iteration 1)

**Iterations**: 1 of 3 (PASS)
