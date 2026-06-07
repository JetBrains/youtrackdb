<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Track 3: Dimensional reviewers emit file+manifest with IDs and an evidence trail

## Purpose / Big Picture
After this track, each of the 16 dimensional `review-*` agents writes a
file-plus-manifest when handed an output path (and returns inline otherwise),
self-assigns its `<PREFIX><n>` finding IDs, and writes its Phase-4 refutation
reasoning to `## Evidence base`; the 4 pure-standalone review agents carry an
explicit `exempt because…` annotation.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track applies the contract to the tactical producers. The edit pattern is
uniform across the 16 dimensional agents (D6 path-conditional output, D5
reviewer-side ID assignment, D8 evidence trail); the 4 standalone agents get a
one-line exemption (D9).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation

The 16 dimensional agents under `.claude/agents/` are the tactical reviewers the
workflow fan-out spawns:

- Code dimensions (5): `review-bugs-concurrency`, `review-code-quality`,
  `review-crash-safety`, `review-performance`, `review-security`.
- Test dimensions (5): `review-test-behavior`, `review-test-completeness`,
  `review-test-concurrency`, `review-test-crash-safety`, `review-test-structure`.
- Workflow dimensions (6): `review-workflow-consistency`,
  `review-workflow-context-budget`, `review-workflow-hook-safety`,
  `review-workflow-instruction-completeness`, `review-workflow-prompt-design`,
  `review-workflow-writing-style`.

The 4 pure-standalone agents (`code-reviewer`, `pr-reviewer`,
`test-quality-reviewer`, `dr-audit`) are never in the workflow fan-out (D9).

Today these agents emit findings inline as their final message, in un-numbered
`#### Critical`-style buckets, and the orchestrator mints merged `M<n>` IDs at
synthesis. This track moves ID assignment to the reviewer and gates the
file-plus-manifest output on a supplied path. The agents run a structured
protocol (premises, code-path traces, formal claims, Phase-4 refutation check)
but emit only findings today; the evidence trail makes the refutation check
verifiable.

**Why path-conditional (D6):** the same `review-*` agents serve the standalone
`/code-review` and `/fix-ci-failure` skills, which read findings inline.
Unconditional file output would break them. The output path is a per-spawn
variable injected by the workflow at the dispatch sites (Track 4); the manifest
schema instruction lives in the agent definition, gated by the path's presence.
The no-path branch stays byte-for-byte today's inline format — native severity
scales, no ID prefix — so the reviewer-side ID assignment never leaks into the
standalone callers.

This track edits `.claude/agents/**`, so it stages only after Track 1's
three-prefix rule is in the staged mirror and the implementer reads it via
§1.7(d) reads-precedence.

## Plan of Work

1. **Path-conditional output (D6), per dimensional agent.** Add the gated
   instruction: when handed an output path, write the file-plus-manifest (the
   schema from Track 2) and return only the thin manifest; with no path, return
   inline exactly as today. Cite the Track 2 schema subsection rather than
   restating it.
2. **Reviewer-side ID assignment (D5).** Each dimensional agent self-assigns its
   `<PREFIX><n>` IDs and writes one `### <PREFIX><n> ` anchored body per finding,
   continuing from the per-dimension high-water-mark the orchestrator hands back
   at spawn (the same `{findings_under_recheck}` hand-back the gate-check already
   uses, applied at the initial review too). Reserve the `### <ID> ` namespace
   under `## Findings` for finding anchors; reasoning prose lives in
   `## Evidence base` or the finding body.
3. **Evidence trail (D8).** Each dimensional agent writes its Phase-4 refutation
   reasoning to `## Evidence base` using YTDB-1069's roster rendering (survived
   claims one line, refuted/non-passing claims in full). Add an `exempt because…`
   hatch for any dimension where the ~1.4K-token cost does not pay.
4. **Standalone exemptions (D9).** Add the `exempt because: invoked standalone,
   output consumed by the user in the same turn, not accumulated in an
   orchestrator session` annotation to `code-reviewer`, `pr-reviewer`,
   `test-quality-reviewer`, `dr-audit`.

Invariants to preserve: S2 — the per-reviewer `id` prefix is load-bearing twice
(bucketing dimension proxy + Review-mode override match) and must never be
renumbered. The no-path branch must stay byte-for-byte today's inline format so
the standalone callers are untouched.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. -->

## Validation and Acceptance

- A dimensional agent handed an output path writes a file whose manifest the
  reviewer returns verbatim, with `## Findings` carrying `### <PREFIX><n> `
  anchored bodies and `## Evidence base` carrying the refutation trail.
- The same agent with no output path returns inline in today's format —
  byte-for-byte, no manifest, no ID prefix — so `/code-review` and
  `/fix-ci-failure` are untouched.
- Finding IDs are reviewer-assigned `<PREFIX><n>` continuing from the handed-back
  high-water-mark; no agent renumbers a prior ID (S2).
- Each of the 4 standalone agents carries the `exempt because…` annotation (D9,
  contributes to S5).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope (16 dimensional + 4 standalone agent files):**
- `.claude/agents/review-bugs-concurrency.md`, `review-code-quality.md`,
  `review-crash-safety.md`, `review-performance.md`, `review-security.md`
- `.claude/agents/review-test-behavior.md`, `review-test-completeness.md`,
  `review-test-concurrency.md`, `review-test-crash-safety.md`,
  `review-test-structure.md`
- `.claude/agents/review-workflow-consistency.md`, `review-workflow-context-budget.md`,
  `review-workflow-hook-safety.md`, `review-workflow-instruction-completeness.md`,
  `review-workflow-prompt-design.md`, `review-workflow-writing-style.md`
- `.claude/agents/code-reviewer.md`, `pr-reviewer.md`, `test-quality-reviewer.md`,
  `dr-audit.md` — `exempt because…` annotation only

**Out of scope:** the orchestrator-side routing, the `M<n>` synthesis removal,
and the path-injection dispatch sites (Track 4); the schema definition (Track 2);
the staging plumbing (Track 1). This track changes only what each agent emits.

**Inter-track dependencies:** depends on **Track 1** (agents must be stageable —
hard dependency, since these are `.claude/agents/**` edits) and **Track 2** (the
manifest schema the agents write). Downstream — **Track 4** consumes the
manifests and IDs these reviewers emit and removes the orchestrator-side `M<n>`
minting; the path-injection that switches on file output lives in Track 4's
dispatch-site edits.
