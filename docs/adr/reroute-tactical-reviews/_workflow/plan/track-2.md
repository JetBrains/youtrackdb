<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Track 2: Manifest-plus-sections schema, persistence/lifecycle, and the coverage invariant

## Purpose / Big Picture
After this track, the workflow defines one file schema every bulk-producing
sub-agent writes (a manifest header over anchored body sections), the lifecycle
that persists and resumes those files, the coverage invariant that binds the
producers, and the strategic + research producers that write files the
orchestrator partial-fetches.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This is the contract track. It establishes the enabling primitive (D2/D3) that
Tracks 3 and 4 cite, the lifecycle (D10) that makes a mid-review `/clear`
resume from files, and the coverage rule (S5). It also lands the schema's
non-tactical applications — the strategic panel/plan-review reviewers and the
research/audit sub-agents write files and the orchestrator partial-fetches them
— because packing the strategic side with the contract clears the merge floor;
a schema-only track (~9 files) would fold into a neighbor.

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

The schema's canonical home is a new subsection in `conventions-execution.md`;
the design (Coverage and exemptions) is explicit that other docs cite it rather
than restate it. `conventions-execution.md` today carries §2.1 (plan/track file
content + section lifecycle), §2.2 (episode formats), §2.3 (ephemeral
identifier rule), §2.4 (commit/review/complexity/decomposition pointers). The
new schema + coverage rule lands as a new §2.x subsection, and §2.1's lifecycle
table gains the `plan/track-N/reviews/` review-file artifact.

The schema (from design Part 1):

- A leading HTML-comment `MANIFEST` block: `findings` count, `severity` counts,
  a per-finding `index` (`id`, `sev`, `loc`, `anchor`, `cert`, `basis`),
  `evidence_base` summary, `cert_index`, `flags`.
- Segregated body sections: `## Findings` (one `### <ID> ` anchored body per
  finding) and `## Evidence base` (`#### <cert> ` entries).
- Addressing on stable heading anchors, not line offsets (line offsets are an
  optional fast-path hint). Validation: `grep -cE '^### [A-Z]{2,}[0-9]+ '` must
  equal the manifest `findings` count (S4), reads heading lines only (S6), and a
  mismatch raises `CONTRACT_VIOLATION` with a whole-section fallback owned by
  the routing class.

Lifecycle (design Part 4 §Lifecycle): review files are plan-directory artifacts
(never staged), live in `_workflow/plan/track-N/reviews/`, are written **and
committed** at reviewer-return (committing is the resume precondition, D10), and
are swept by the Phase 4 cleanup commit alongside `handoff-*.md`. The thin
episode block points at the files and records manifest counts only.

Strategic producers (design Part 2 §Routing): the Phase A technical/risk/
adversarial panel, the Phase 2 consistency/structural plan review, and the
plan/decomposition gate-verifications write files in the same schema; their
consumer is the orchestrator/planner revising the plan, so the orchestrator
keeps its own partial-fetch read of `## Findings`. Research/audit (Phase 0/1
Explore, Phase 4 cold-read and design audit) write a file and return a summary.

**A10 (Phase A revisit):** the chosen review-file shape is
`plan/track-N/reviews/` (a directory beside the `plan/track-N.md` file). The
`plan/track-N.md` file and the `plan/track-N/` directory coexist, so the §2.1
lifecycle must not glob `plan/*` expecting files only — confirm consumers glob
`plan/track-*.md`. Revisit `plan/track-N/reviews/` vs `plan/track-N-reviews/`
during decomposition; the directory form works and is the design's choice.

## Plan of Work

1. **Schema subsection.** Add the manifest-plus-sections schema as a new
   `conventions-execution.md §2.x` subsection: the manifest block shape, the
   anchored body sections, the reserved `### <ID> ` namespace under `## Findings`,
   the ID-anchored count-validation grep (S4/S6), and the `CONTRACT_VIOLATION`
   fallback. State that this is the single source of truth and other docs cite it.
2. **Coverage invariant.** State S5 once, canonically, in the same subsection:
   every bulk-producing sub-agent class follows the file-plus-manifest rule or
   carries an explicit `exempt because…` annotation, and a new bulk-producing
   class must declare one or the other.
3. **Lifecycle.** Add the review-file artifact to `conventions-execution.md §2.1`
   (written+committed at reviewer-return, `plan/track-N/reviews/`, Phase 4 sweep),
   the thin episode-block shape pointing at the files, and the `plan/*` glob
   caution (A10). Extend the Phase 4 cleanup in `workflow.md §Final Artifacts`
   and `create-final-design.md` to sweep `plan/track-N/reviews/`.
4. **Strategic producers.** Teach the Phase A panel prompts (technical/risk/
   adversarial review) and the Phase 2 plan-review prompts (consistency/
   structural) to write a file in the schema and return a thin manifest; the
   orchestrator keeps its partial-fetch of `## Findings`. The strategic
   reviewers already emit certificate bases, so `## Evidence base` mostly maps
   existing output. Apply the same to the strategic gate-verification prompts.
5. **Research/audit.** Teach the `research.md` Explore delegation and the Phase 4
   cold-read/design-audit to write a file and return a summary; the orchestrator
   partial-fetches on demand.
6. **Exemptions on the strategic/orchestrator side.** Add the `review-mode.md`
   `FIX_FINDING` exemption (user-sourced triples, tiny, already in the
   orchestrator's conversation context).

Invariants to preserve: the no-bodies invariant (S1) is established here for the
strategic side only in the sense that the schema keeps the evidence base on disk
(the largest on-disk win); the orchestrator still reads `## Findings` for
strategic reviews by design. S4/S6 (count validation, heading-only) are
mechanical and get tests.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. -->

## Validation and Acceptance

- A bulk producer writes a file whose manifest `findings` count equals the
  ID-anchored grep count; a deliberate mismatch raises `CONTRACT_VIOLATION` and
  the reader falls back to a whole-section read (S4/S6, mechanical-testable).
- A strategic review (Phase A panel, Phase 2 plan review, gate-verification)
  writes its file in the schema and returns a thin manifest; the orchestrator
  partial-fetches `## Findings` from disk rather than receiving it inline.
- A research/audit sub-agent writes a file and returns a summary the orchestrator
  pulls on demand.
- After a completed review pass whose file is committed, a `/clear` resume reads
  the committed file instead of re-spawning the reviewer; this does not override
  the Phase A re-run-from-iteration-1 rule for an interrupted iteration (D10).
- Every bulk-producing sub-agent class named in the coverage rule either follows
  the file-plus-manifest rule or carries an `exempt because…` annotation (S5).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope:**
- `.claude/workflow/conventions-execution.md` — new schema + coverage §2.x; §2.1
  review-file lifecycle + `plan/*` glob caution
- `.claude/workflow/workflow.md` — §Final Artifacts cleanup sweep of `plan/track-N/reviews/`
- `.claude/workflow/prompts/create-final-design.md` — Phase 4 cleanup sweep
- `.claude/workflow/prompts/technical-review.md`, `risk-review.md`,
  `adversarial-review.md` — Phase A panel: write file + thin manifest
- `.claude/workflow/prompts/consistency-review.md`, `structural-review.md` —
  Phase 2 plan review: write file + thin manifest
- `.claude/workflow/prompts/consistency-gate-verification.md`,
  `structural-gate-verification.md`, `review-gate-verification.md` — strategic
  gate-verifications
- `.claude/workflow/research.md` — Explore delegation writes a file + returns a summary
- `.claude/workflow/review-mode.md` — `FIX_FINDING` exemption annotation
- `.claude/scripts/tests/` — count-validation (S4/S6) mechanical test

**Out of scope:** the dimensional `review-*` agents and their tactical routing
(Tracks 3-4); the staging plumbing (Track 1). This track defines the contract
and applies it to the strategic/research side only.

**Inter-track dependencies:** depends on **Track 1** (ordered after the
precursor; this track edits only `.claude/workflow/**`, which stages under the
existing two-prefix rule, so the dependency is ordering, not a hard path
requirement). Downstream — **Track 3** cites this schema for the dimensional
agents; **Track 4** cites it for tactical routing and the `basis`/`cert`
manifest fields.
