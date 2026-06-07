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
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-07T15:31Z [ctx=info] Review + decomposition complete (3 steps: 1 high, 1 medium, 1 low; 0 failed)
- [x] 2026-06-07T16:05Z [ctx=safe] Step 1 complete (commit 0b27c8d8ce85fec9f2c1b88515df9ca8fda50c67)
- [x] 2026-06-07T16:17Z [ctx=safe] Step 2 complete (commit 396935bb0ce16c8dc5d808ef32a86f53aa500277)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-06-07T16:05Z Step 1: the review-file schema's canonical home is
  `conventions-execution.md §2.5`; the count-validation regex `^### [A-Z]+[0-9]+ `
  (one-or-more uppercase, DL1) and the mandatory `id`/`sev`/`anchor` versus
  downstream `loc`/`cert`/`basis` field split are fixed there and embedded in the
  live test, so Track 3 (dimensional agents) and Track 4 (`basis`/`anchor` reads)
  must key off `§2.5`. See Episodes §Step 1.
- 2026-06-07T16:17Z Step 2: the strategic finding-heading shape is now bare
  `### <PREFIX><N>` (the literal `Finding ` word dropped) across the five strategic
  finding-producers, so Track 3's 16 dimensional `review-*` agents must make the
  identical change to keep the S4 grep honest. The file-when-path /
  inline-otherwise output-mode block installed on the strategic side is a reusable
  template for the dimensional set. See Episodes §Step 2.

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**DL1 (Phase A, all three reviews, 2026-06-07) — S4 count-validation regex
corrected `[A-Z]{2,}` → `[A-Z]+`; a D3 entailment, no decision change.** The
finding-ID prefix table (`review-iteration.md`) assigns single-letter prefixes to
the strategic reviewers — `T` (technical), `R` (risk), `A` (adversarial), `S`
(structural) — while the dimensional reviewers use two-letter prefixes (`BC`,
`CQ`, …). The plan/design S4 grep `^### [A-Z]{2,}[0-9]+ ` requires two uppercase
letters, so it returns zero for `### T1 `/`### R1 `/`### A1 `/`### S1 ` —
empirically verified (`### T1 ` → 0 matches, `### BC1 ` → 1). Left uncorrected,
every strategic review file Track 2 step 4 enables would raise a spurious
`CONTRACT_VIOLATION`. Widened to `[A-Z]+` (matches single- and two-letter
prefixes; `#### <cert>` four-hash evidence entries still excluded). This corrects
a regex literal inside D3's rationale + the S4 invariant + the canonical §2.x
implementation; D3's decision (anchored count-validation over line-offsets) is
unchanged, the same "entailment, not a new decision" pattern Track 1 used for its
§1.6(b)/D7 extension (Track 1 DL1). The adversarial reviewer (A1) mis-stated
`### T1 ` as matching; orchestrator re-verified before acting. The frozen
`design.md` carries the old regex + an "under `## Findings`" reservation narrower
than the whole-file grep — reconciled at Phase 4 in `design-final.md` (the
canonical §2.x implementation is the source of truth; design.md is the
point-in-time narrative). The fix pairs with the step-4 heading-shape change
(`### Finding <PREFIX><N>` → `### <PREFIX><N>`, A1/T1) and the file-wide
`### <PREFIX><N> ` reservation (A2).

**DL2 (Phase A, technical/risk/adversarial, 2026-06-07) — A10 resolved in favour
of the directory form; Phase 4 cleanup needs no new sweep.** All three reviews
independently audited the consumers of `plan/`: the precheck stamp/drift walk,
the §1.6(h) Phase 1 walk, `design-mechanical-checks.py`, and the §1.6(f) stamp
set all glob `plan/track-*.md` (the `.md` suffix excludes a `track-N/` directory)
or `.md`-filter a non-recursive `listdir`; the Phase 4 cleanup is a blanket
`git rm -r _workflow/` (recursive, sweeps the directory automatically). No
consumer globs bare `plan/*`. So `plan/track-N/reviews/` coexists safely with
`plan/track-N.md`; the rejected `plan/track-N-reviews/` is no safer. A10's
"revisit at decomposition" is closed. The `plan/*` glob caution stays in §2.1 as
a forward guard. Step 3's "extend the Phase 4 cleanup to sweep" is downgraded to
"confirm the blanket `rm -r` already covers it" — adding an explicit
`plan/track-*` `git rm` would itself introduce the A10 hazard (T2/R4/A5).

**DL3 (Phase A, technical/risk, 2026-06-07) — two load-bearing edit sites added
to scope; footprint ~17 → ~19.** (a) The Phase 4 cold-read (`design-review.md`)
is spawned from `edit-design/SKILL.md §Step 4`, not `create-final-design.md`
Step 4 (whose Step 4 is staged-workflow promotion); `create-final-design.md`
routes it via `edit-design` with `mutation_kind: phase4-creation`. The
output-path injection must therefore land in `edit-design/SKILL.md`, added to
scope; `design-review.md` becomes path-conditional so the Phase 1
`phase1-creation` invocation stays exempt (T4/R1). (b) `track-review.md` §Phase A
Resume states findings are "not persisted to a separate file" and gates resume on
the `## Outcomes & Retrospective` checkboxes, which now contradicts the committed
strategic review files; added to scope for a light reconciliation pass stating
the committed files are durable records (the live-iteration win is the evidence
base off-context) while resume still gates on the checkboxes (R2/A3). Neither
file is touched by Track 3 or Track 4 — no cross-track conflict. The two
single-track core edits plus these reconciliation touches keep ~19 a coherent
track under the ~20-25 split band.

**DL4 (Phase A, adversarial, 2026-06-07) — S5 is a documented contract, not a
mechanical gate this track.** S5 (coverage) has no mechanical enforcement: nothing
enumerates bulk-producing classes and checks each carries file-plus-manifest or
`exempt because…`. A mechanical check over the full producer set cannot land in
Track 2 — the 16 dimensional `review-*` agents do not carry their annotations
until Track 3, so the assertion would fail until then. Step 2 states S5's
enforcement status plainly (decomposer/reviewer-checked, deferred mechanical
coverage) so it is not mistaken for a checked invariant like S4/S6 (A4).

**DL5 (Phase A, risk/adversarial, 2026-06-07) — verdict-producing strategic
reviewers get a manifest variant.** The gate-verification prompts
(`review-gate-verification.md`, `consistency-/structural-gate-verification.md`)
emit per-finding verdicts (`VERIFIED`/`STILL OPEN`/`REJECTED`) + `PASS`/`FAIL`,
not a fresh severity-graded finding set, so the finding-shaped manifest fields do
not map cleanly. The schema (step 1) specifies the variant: such a file carries
only its new findings under `findings`/`severity` and conveys per-prior-finding
verdicts via a distinct `verdicts` block, so the count grep still validates the
new-finding anchors. Resolving this in the schema-definition step keeps the
contract Tracks 3/4 cite unambiguous (R3).

**DL6 (Phase A, risk, 2026-06-07) — Explore delegation states its write-vs-exempt
rationale.** Phase 0/1 `research.md` Explore feeds the planner's in-session
conversation — the same in-session-consumption property that exempts the Phase 1
cold-read. Step 5 states the rule rather than leaving the asymmetry implicit:
write a file when the output would otherwise accumulate in a long-lived session;
else carry the `exempt because…` annotation under the Phase-1-cold-read
rationale (R5).

**DL7 (Phase B, inline replan after step 2, 2026-06-07) — strategic dispatch
path-injection added as step 4 so the producer-prompt conditional gains its
orchestrator-side caller.** Step 2 taught the strategic producer prompts to write a
review file when handed an output path. The audit before step 3 found that no track
scoped the orchestrator-side dispatch that injects that path: `track-review.md §Inputs`
passes the Phase A panel (technical/risk/adversarial) and the review-gate-verification
a shared input set with no output path, and `implementation-review.md` (the Phase 2
consistency/structural dispatch) was referenced nowhere in the plan. Left unfixed, the
write-when-handed-a-path branch would have no caller post-promotion and the strategic
on-disk win (panel evidence bases off-context) would never activate, even though
`## Validation and Acceptance` already asserts the orchestrator partial-fetches
`## Findings` from disk (an acceptance with no implementing step). The user chose
Escalate now over deferring to the Phase C instruction-completeness reviewer. Added
Concrete Steps step 4 (risk: medium, appended without renumber, so step 3's
order-independent reconciliation prose stays untouched) injecting the per-spawn output
path at both strategic dispatch sites and documenting the orchestrator's partial-fetch
read; `implementation-review.md` joins the in-scope list (footprint ~19 → ~20). No
numbered Decision Record is invalidated: D1/D6 and the design's strategic-routing
intent stand, and the plan's step decomposition simply missed the dispatch-injection
site. The replan resets `## Plan Review` to route the next session through State 0
before Phase B resumes at step 3.

## Outcomes & Retrospective
<!-- Continuous-log. -->

- [x] Technical: PASS at iteration 2 (6 findings — 1 blocker, 2 should-fix, 3
  suggestion; all 6 applied). Blocker T4 corrected the Phase 4 cold-read wiring
  (spawned from `edit-design/SKILL.md §Step 4`, not `create-final-design.md` Step
  4) and added `edit-design/SKILL.md` to scope (DL3). T1/T3 fixed the
  heading-shape/regex contract (DL1) and the §2.1 new-prose-vs-table-row framing.
  Gate-check independently confirmed the blocker against the actual spawn site.
- [x] Risk: PASS at iteration 2 (6 findings — 3 should-fix, 3 suggestion; all 6
  applied, 0 blockers). R1/R2 added the two load-bearing edit sites
  (`edit-design/SKILL.md`, `track-review.md`) to scope (DL3); R3 specified the
  verdict-producer manifest variant for the gate-verification prompts (DL5).
  Footprint ~17 → ~19, coherent under the ~20-25 band.
- [x] Adversarial: PASS at iteration 2 (8 findings — 1 blocker, 4 should-fix, 3
  suggestion; 7 applied, A7 noted/rejected). Blocker A1 = the S4 regex+heading
  defect (DL1); the reviewer's original "`### T1` matches" claim was wrong and the
  orchestrator re-verified before acting. A8 closed A10 in favour of the directory
  form (DL2). A7 rejected: `design.md` is frozen at Phase 1, reconciliation booked
  for Phase 4.

## Context and Orientation

The schema's canonical home is a new subsection in `conventions-execution.md`;
the design (Coverage and exemptions) is explicit that other docs cite it rather
than restate it. `conventions-execution.md` today carries §2.1 (plan/track file
content + section lifecycle), §2.2 (episode formats), §2.3 (ephemeral
identifier rule), §2.4 (commit/review/complexity/decomposition pointers). The
new schema + coverage rule lands as a new §2.x subsection. The review-file
lifecycle lands as new prose under §2.1, adjacent to the existing track-file
directory-lifecycle note — not as a row in the §2.1 `#### Section lifecycle`
matrix, whose rows are the 14 track-file sections, not plan-directory artifacts
(T3).

The schema (from design Part 1):

- A leading HTML-comment `MANIFEST` block: `findings` count, `severity` counts,
  a per-finding `index` (`id`, `sev`, `loc`, `anchor`, `cert`, `basis`),
  `evidence_base` summary, `cert_index`, `flags`.
- Segregated body sections: `## Findings` (one `### <ID> ` anchored body per
  finding) and `## Evidence base` (`#### <cert> ` entries).
- Addressing on stable heading anchors, not line offsets (line offsets are an
  optional fast-path hint). Validation: `grep -cE '^### [A-Z]+[0-9]+ '` must
  equal the manifest `findings` count (S4), reads heading lines only (S6), and a
  mismatch raises `CONTRACT_VIOLATION` with a whole-section fallback owned by
  the routing class. The grep uses `[A-Z]+` (one-or-more), not `[A-Z]{2,}`, so
  single-letter strategic prefixes (`T`/`R`/`A`/`S`) match alongside two-letter
  dimensional ones (`BC`/`CQ`/…) — DL1. The `### <PREFIX><N> ` three-hash heading
  shape is reserved file-wide for finding anchors; `## Evidence base` uses
  `#### <cert>` (four-hash, no collision) and finding bodies use `####`/bold for
  sub-structure, never a `### <CAPS><digit>` heading.

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

**A10 (resolved at Phase A):** the review-file home is `plan/track-N/reviews/`
(a directory beside the `plan/track-N.md` file). The directory form is audited
safe against every current consumer — all glob `plan/track-*.md` (the `.md`
suffix excludes a `track-N/` directory) or `.md`-filter their `listdir`, and the
Phase 4 cleanup is a blanket recursive `git rm -r _workflow/`; none glob bare
`plan/*`. The rejected `plan/track-N-reviews/` is no safer. The `plan/*` glob
caution stays in §2.1 as a forward guard for any future consumer (DL2).

## Plan of Work

1. **Schema subsection.** Add the manifest-plus-sections schema as a new
   `conventions-execution.md §2.x` subsection: the manifest block shape, the
   anchored body sections, the reserved `### <PREFIX><N> ` namespace (reserved
   file-wide, not only under `## Findings`), the ID-anchored count-validation
   grep `grep -cE '^### [A-Z]+[0-9]+ '` (S4/S6 — `[A-Z]+`, not `[A-Z]{2,}`; DL1),
   and the `CONTRACT_VIOLATION` fallback. Mark which manifest index fields are
   mandatory (`id`, `sev`, `anchor`) versus consumed downstream (`loc`, `cert`,
   `basis` — read by Tracks 3/4) so the contract is unambiguous (A6). Specify the
   manifest variant for a verdict-producing strategic reviewer: a
   gate-verification emits `VERIFIED`/`STILL OPEN`/`REJECTED` + `PASS`/`FAIL`, not
   a severity-graded finding set, so its file carries only its new findings under
   `findings`/`severity` and conveys per-prior-finding verdicts via a distinct
   `verdicts` block — the count grep then validates the new-finding anchors
   (R3/DL5). State that this is the single source of truth and other docs cite it.
2. **Coverage invariant.** State S5 once, canonically, in the same subsection:
   every bulk-producing sub-agent class follows the file-plus-manifest rule or
   carries an explicit `exempt because…` annotation, and a new bulk-producing
   class must declare one or the other. State S5's enforcement status plainly: it
   is a documented contract checked by the decomposer/reviewer, not a mechanical
   gate in this track (unlike S4/S6). A mechanical coverage check over the full
   producer set is deferred — the 16 dimensional `review-*` agents do not carry
   their annotations until Track 3, so a Track-2 test asserting universal
   compliance would fail until then (A4/DL4).
3. **Lifecycle.** Add the review-file lifecycle as new prose under
   `conventions-execution.md §2.1` (written+committed at reviewer-return,
   `plan/track-N/reviews/`, swept by Phase 4) — adjacent to the track-file
   directory-lifecycle note, not as a row in the `#### Section lifecycle` matrix
   (T3). Add the thin episode-block shape pointing at the files and the `plan/*`
   glob caution (A10/DL2). The Phase 4 cleanup needs no new sweep: the existing
   blanket `git rm -r _workflow/` in `workflow.md §Final Artifacts` and
   `create-final-design.md` already removes `plan/track-N/reviews/` recursively;
   confirm this and at most mention review files in the cleanup example prose — do
   NOT add a `plan/track-*`-globbing `git rm` (the A10 hazard) (T2/R4/A5/DL2).
4. **Strategic producers.** Teach the Phase A panel prompts (technical/risk/
   adversarial review) and the Phase 2 plan-review prompts (consistency/
   structural) to write a file in the schema and return a thin manifest; the
   orchestrator keeps its partial-fetch of `## Findings`. **Load-bearing heading
   change:** convert each prompt's finding heading from `### Finding <PREFIX><N>
   [sev]` to bare `### <PREFIX><N> [sev]` (drop the literal `Finding ` word), or
   the S4 grep counts zero and every strategic file raises `CONTRACT_VIOLATION`
   (A1/T1). The strategic reviewers already emit certificate bases, so
   `## Evidence base` mostly maps existing output — except `structural-review.md`,
   which has no certificate Part; its file carries `## Findings` plus an
   empty/minimal `## Evidence base` (`evidence_base` certs 0) (T5). Apply the same
   to the strategic gate-verification prompts, using the verdict-producer manifest
   variant from step 1 (R3/DL5).
5. **Research/audit.** Teach the `research.md` Explore delegation and the Phase 4
   cold-read/design-audit to write a file and return a summary; the orchestrator
   partial-fetches on demand. **Corrected wiring (T4/R1):** the Phase 4 cold-read
   is `prompts/design-review.md` spawned from `edit-design/SKILL.md §Step 4` (the
   `mutation_kind: phase4-creation` invocation routed from `create-final-design.md`
   Artifact 1 / Sub-step B), NOT `create-final-design.md` Step 4 (which is the
   staged-workflow promotion step). Inject the output path into that §Step 4
   `## Inputs` block gated on `mutation_kind == phase4-creation`, and make
   `design-review.md` path-conditional (write file + return summary when handed a
   path; today's inline verdict otherwise) — so the Phase 1 `phase1-creation`
   invocation, which passes no path, stays byte-for-byte exempt (step 6). For the
   `research.md` Explore delegation, state its rationale: write a file when its
   output would otherwise accumulate in a long-lived session, else carry the
   `exempt because…` annotation under the same in-session-consumption rationale as
   the Phase 1 cold-read (R5/DL6).
6. **Exemptions on the strategic/orchestrator side.** Add the `review-mode.md`
   `FIX_FINDING` exemption (user-sourced triples, tiny, already in the
   orchestrator's conversation context). Annotate the Phase 1 `design-review.md`
   cold-read `exempt because…` its output is consumed in-session by the design
   author within the same `edit-design` run, never accumulated in an orchestrator
   session — the same rationale as the four pure-standalone agents.
7. **Consistency touches.** Give `step-implementation-recovery.md` a light
   consistency pass so its references to findings, synthesis, and the recovery
   flow stay coherent with the new schema and lifecycle — scoped to
   schema/lifecycle phrasing only; leave the tactical-routing wording (`M<n>`,
   implementer body-reads) to Track 4, which owns that file's routing edits (T6).
   Also reconcile `track-review.md` §Phase A Resume: it states findings are "not
   persisted to a separate file" and gates resume on `## Outcomes & Retrospective`
   checkboxes, which now contradicts the committed strategic review files. State
   the accurate behavior — the committed strategic files are durable records and
   the live-iteration win is the evidence base off-context; Phase A resume still
   gates on the Outcomes checkboxes (a completed panel review's findings are
   already consumed into track-file edits) (R2/A3/DL3). `design.md` flags both as
   light-pass sites, not bulk producers.

Invariants to preserve: the no-bodies invariant (S1) is established here for the
strategic side only in the sense that the schema keeps the evidence base on disk
(the largest on-disk win); the orchestrator still reads `## Findings` for
strategic reviews by design. S4/S6 (count validation, heading-only) are
mechanical and get tests.

## Concrete Steps

1. **`conventions-execution.md` schema + coverage + lifecycle, with the S4/S6
   test.** Add the `§2.x` manifest-plus-sections schema: the manifest block, the
   `## Findings` / `## Evidence base` anchored bodies, the `### <PREFIX><N> `
   namespace reserved file-wide, the count-validation grep
   `grep -cE '^### [A-Z]+[0-9]+ '` (S4/S6), the `CONTRACT_VIOLATION` whole-section
   fallback, the mandatory `id`/`sev`/`anchor` vs downstream `loc`/`cert`/`basis`
   field split, and the verdict-producer manifest variant. State S5 once with its
   enforcement status (documented contract, not mechanical this track). Add the
   review-file lifecycle as new prose under `§2.1` (committed-at-return,
   `plan/track-N/reviews/`, Phase 4 sweep, thin episode-block shape, `plan/*` glob
   caution) — not a `#### Section lifecycle` matrix row. Confirm the blanket
   `git rm -r _workflow/` in `workflow.md §Final Artifacts` + `create-final-design.md`
   already sweeps review files (cleanup-prose mention only; no `plan/*` glob). Add
   the `.claude/scripts/tests/` mechanical test for S4/S6 (count==grep,
   heading-only, a stray-`### CASE1 ` CONTRACT_VIOLATION fixture; fixtures embed
   and cite the canonical regex). — risk: high (workflow machinery — defines the
   shared review-file schema/lifecycle every bulk producer keys off)  [x] commit: 0b27c8d8ce85fec9f2c1b88515df9ca8fda50c67

2. **Teach the bulk producers to write files in the schema.** Phase A panel
   (`technical-`/`risk-`/`adversarial-review.md`) + Phase 2 plan-review
   (`consistency-`/`structural-review.md`): write file + thin manifest, keep the
   orchestrator's `## Findings` partial-fetch, and **drop the `Finding ` word**
   from finding headings (`### Finding <PREFIX><N>` → `### <PREFIX><N>`) so the S4
   grep counts them; `structural-review.md` carries an empty/minimal
   `## Evidence base`. The three gate-verification prompts (`consistency-`/
   `structural-`/`review-gate-verification.md`) adopt the verdict-producer
   manifest variant from step 1. Research/audit: `research.md` Explore delegation
   states its write-or-`exempt because…` rationale; `design-review.md` made
   path-conditional (write file + summary when handed an output path, inline
   otherwise); inject the output path into `edit-design/SKILL.md §Step 4`
   `## Inputs` gated on `mutation_kind == phase4-creation`, so the Phase 4
   cold-read writes a file and the Phase 1 `phase1-creation` invocation stays
   byte-for-byte exempt. — risk: medium (bounded behavioral workflow edits across
   producer prompts and one skill; no auto-running script or load-bearing gate
   changed)  [x] commit: 396935bb0ce16c8dc5d808ef32a86f53aa500277

3. **Prose-only exemptions + consistency reconciliations.** Annotate
   `review-mode.md`'s `FIX_FINDING` with `exempt because…` (user-sourced triples,
   in-conversation, never accumulated). Light consistency pass on
   `step-implementation-recovery.md` (schema/lifecycle phrasing only; routing
   wording stays Track 4). Reconcile `track-review.md §Phase A Resume`: the
   committed strategic review files are durable records and the live-iteration win
   is the evidence base off-context, while Phase A resume still gates on the
   `## Outcomes & Retrospective` checkboxes. — risk: low (prose-only — no
   hook/script/settings, no gate/dispatch/schema change) — size: ~3 files. Of the
   other low/medium work, step 2 (~11 files) would trip the ~14 overblown line if
   merged in (reason a), and the inline-replan step 4 (~2 files, medium) stays
   separate because it touches a disjoint `track-review.md` section and carries a
   different risk tag; folding this low prose-only step into the medium dispatch
   step would erase the risk-tag granularity that keeps step 3 on the no-step-review
   fast path.  [ ]

4. **Strategic dispatch path-injection (orchestrator side).** Inject the
   review-file output path at the strategic spawn sites so step 2's producer
   prompts (write-when-handed-a-path) gain a caller and the orchestrator
   partial-fetches `## Findings` from the committed file — the orchestrator-side
   complement of step 2, without which that write branch has no caller
   post-promotion. `track-review.md §Inputs`: add the per-spawn output path to the
   shared set passed to the Phase A panel (`technical`/`risk`/`adversarial`) and the
   review-gate-verification spawn, naming the
   `_workflow/plan/track-N/reviews/<type>-iter<N>.md` target (the §2.1 lifecycle
   home, §2.5 schema) and the orchestrator's partial-fetch read of `## Findings`.
   `implementation-review.md`: inject the same for the Phase 2
   `consistency`/`structural` reviewers and their gate-verifications. Both files
   stage under §1.7. Does not change S1 (strategic reviews keep the orchestrator's
   partial-fetch by design). — risk: medium (bounded behavioral dispatch/`## Inputs`
   edits at the strategic spawn sites; no auto-running script or load-bearing gate
   changed) — size: ~2 files (`track-review.md` already in scope for §Phase A
   Resume; `implementation-review.md` new); added by inline replan after step 2
   (DL7)  [ ]

Sequential: 2 depends on step 1's schema; 3 depends on the step-1 coverage rule
and the step-2 strategic-file behavior; 4 (added by inline replan, DL7) depends on
step 1's §2.1 lifecycle and step 2's producer-prompt behavior, and is independent
of step 3 (both follow step 2; execute 3 then 4 — they touch disjoint sections of
`track-review.md`). No parallel steps (each builds on the prior).

## Episodes
<!-- Continuous-log. -->

### Step 1 — commit 0b27c8d8ce85fec9f2c1b88515df9ca8fda50c67, 2026-06-07T16:05Z [ctx=safe]
**What was done:** Added the manifest-plus-sections review-file schema as a new
`conventions-execution.md §2.5` (staged mirror): the MANIFEST comment block, the
`## Findings` / `## Evidence base` anchored bodies, the file-wide `### <PREFIX><N> `
finding-anchor reservation, the count-validation grep `grep -cE '^### [A-Z]+[0-9]+ '`
(S4/S6) with the `CONTRACT_VIOLATION` whole-section fallback, the mandatory
`id`/`sev`/`anchor` versus downstream `loc`/`cert`/`basis` field split, the
verdict-producer manifest variant (DL5), and the S5 coverage rule stated once with
its enforcement status (a documented contract this track, with mechanical coverage
deferred — DL4). Added the review-file lifecycle as new prose under `§2.1`
(committed-at-return, `plan/track-N/reviews/`, thin episode block, Phase 4 sweep,
`plan/*` glob caution) and a cleanup-prose confirmation in the staged
`workflow.md §Final Artifacts` and `create-final-design.md` that the blanket
`git rm -r _workflow/` already sweeps the review files, with no `plan/*`-globbing
removal added (DL2). Added the live S4/S6 mechanical test under
`.claude/scripts/tests/` with four fixtures. This step is risk:high, so the
step-level dimensional review (hook-safety + prompt-design) ran and passed clean at
iteration 1 with zero findings.

**What was discovered:** The schema landed at `§2.5`, the next free `§2.x` slot
after `§2.4`; that anchor is what Track 3 (dimensional agents) and Track 4 (tactical
routing) cite. The mandatory `id`/`sev`/`anchor` versus downstream
`loc`/`cert`/`basis` field split is now fixed, so Track 4's severity backstop reads
`basis` and the implementer anchor-read reads `anchor`. The canonical
count-validation regex `^### [A-Z]+[0-9]+ ` is embedded verbatim in both the live
test and `§2.5`; any later track that emits or validates review files must use
`[A-Z]+` (one-or-more uppercase), since `[A-Z]{2,}` silently drops the single-letter
strategic prefixes `T`/`R`/`A`/`S` (DL1). The validator's anchor count was
cross-checked against the real `grep -cE '^### [A-Z]+[0-9]+ '` and matched on every
fixture (2/3/2/3). The staged `workflow.md` and `create-final-design.md` already
existed from Track 1 and differ from live, so they were edited in place per
`§1.7(d)` reads-precedence rather than re-copied. Two non-defect nuances in the test
were noted by review and not filed: `manifest_findings_count` matches the first
whole-file `findings:` rather than scoping to the MANIFEST block (correct on every
current fixture, since the manifest is first), and `assert_regex_source_contract`
reads the schema doc twice. Both are harmless and recorded for a future fixture
author.

**Key files:**
- `…/staged-workflow/.claude/workflow/conventions-execution.md` (new — staged)
- `…/staged-workflow/.claude/workflow/workflow.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/create-final-design.md` (modified — staged)
- `.claude/scripts/tests/test_review_file_schema.py` (new — live)
- `.claude/scripts/tests/fixtures/review-file-valid-dimensional.md` (new — live)
- `.claude/scripts/tests/fixtures/review-file-valid-strategic.md` (new — live)
- `.claude/scripts/tests/fixtures/review-file-count-mismatch.md` (new — live)
- `.claude/scripts/tests/fixtures/review-file-stray-heading.md` (new — live)

### Step 2 — commit 396935bb0ce16c8dc5d808ef32a86f53aa500277, 2026-06-07T16:17Z [ctx=safe]
**What was done:** Taught eleven staged producer prompts to write a schema file
plus a thin manifest keyed off `conventions-execution.md §2.5`, keeping each
prompt's no-path branch byte-for-byte today's inline output. Dropped the literal
`Finding ` word from the finding-heading template in the five finding-producers
(`technical`/`risk`/`adversarial`/`consistency`/`structural`:
`### Finding <PREFIX><N>` → `### <PREFIX><N>`) so the S4 count grep matches the
single-letter `T`/`R`/`A`/`S` and two-letter `CR` prefixes (DL1);
`structural-review.md` carries an empty/minimal `## Evidence base` (no certificate
Part). The three gate-verification prompts adopt the §2.5 verdict-producer manifest
variant (DL5). `research.md` states the write-or-`exempt because…` rationale for its
Explore delegation (DL6). `design-review.md` is now path-conditional, gated on an
output path that `edit-design/SKILL.md §Step 4` injects only when
`mutation_kind == phase4-creation`, so the Phase 1 `phase1-creation` cold-read
passes no path and stays byte-for-byte exempt (DL3). Every write routed to the
staged mirror; no live `.claude` path was touched, and both pre-commit gates passed
empty. This step is risk:medium, so no step-level dimensional review ran; the change
rests on the re-run step-1 S4/S6 test (still green) plus the Phase C track pass.

**What was discovered:** Four of the eleven files (`consistency-gate-verification`,
`structural-gate-verification`, `research`, `design-review`) had no staged copy yet,
so they were first-touch-copied verbatim from live and then edited; git records them
as new files, which inflates the step diff to ~819 insertions even though each
behavioral delta is one inserted block. The other seven already had Track-1 staged
copies and were edited in place per `§1.7(d)`. The `adversarial-review.md` Phase 1
(design-scoped) section carried an internal reference to `Finding A<N> certificates`
that the heading rename made stale; it was reconciled to `each ### A<N> finding`
with a note that the `phase1-creation` loop passes no output path, so the inline
two-part format applies there. Cross-track: Track 3's 16 dimensional agents must
apply the same `### <PREFIX><N>` heading shape (drop `Finding `) to keep the S4 grep
honest, and the file-when-path/inline-otherwise output-mode block installed here is
a reusable template for them. The cumulative track diff now reads ~2,000 changed
lines, but that is dominated by the freshly-staged whole-file copies; the real
review surface is far smaller, so this is an order-of-magnitude signal, not a
track-oversize flag.

**Critical context:** The producer prompts edited here define only the consumer
behavior (write when handed an output path). The orchestrator-side path injection at
the strategic dispatch sites (the Phase A panel spawn in `track-review.md`, the Phase
2 plan-review spawn) is a separate wiring; whether Track 2 covers it is resolved at
step-3 planning, where the dispatch sites are confirmed to hand a path or the gap is
recorded for the Phase C instruction-completeness pass. Step 3 also owns
`review-mode.md`, `step-implementation-recovery.md`, and the `track-review.md §Phase
A Resume` reconciliation.

**Key files:**
- `…/staged-workflow/.claude/workflow/prompts/technical-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/risk-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/consistency-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/structural-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/consistency-gate-verification.md` (new — staged, first-touch copy)
- `…/staged-workflow/.claude/workflow/prompts/structural-gate-verification.md` (new — staged, first-touch copy)
- `…/staged-workflow/.claude/workflow/prompts/review-gate-verification.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/research.md` (new — staged, first-touch copy)
- `…/staged-workflow/.claude/workflow/prompts/design-review.md` (new — staged, first-touch copy)
- `…/staged-workflow/.claude/skills/edit-design/SKILL.md` (modified — staged)

## Validation and Acceptance

- A bulk producer writes a file whose manifest `findings` count equals the
  ID-anchored grep count `grep -cE '^### [A-Z]+[0-9]+ '`; a strategic reviewer's
  emitted file (single-letter prefix `### T1 `/`### R1 `/`### A1 `/`### S1 `)
  passes this check, and a deliberate mismatch — including a stray `### CASE1 `
  sub-heading inside a finding body — raises `CONTRACT_VIOLATION` and the reader
  falls back to a whole-section read (S4/S6, mechanical-testable; the test
  fixtures embed the canonical anchor regex and cite its source — R6).
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

Per-step acceptance:
- **Step 1:** the `.claude/scripts/tests/` test passes — a fixture file whose
  manifest `findings` count equals its `### <PREFIX><N> ` anchor count validates,
  a single-letter-prefix file (`### T1 `) validates, and a count mismatch or a
  stray `### CASE1 ` body heading raises `CONTRACT_VIOLATION`. `conventions-execution.md`
  carries the `§2.x` schema + S5 + the `§2.1` lifecycle prose.
- **Step 2:** each edited producer prompt, given an output path, emits a schema
  file whose anchors pass the step-1 grep; with no path it is byte-for-byte
  today's inline output. The Phase 4 `phase4-creation` cold-read writes a file;
  the Phase 1 `phase1-creation` cold-read does not.
- **Step 3:** `review-mode.md` carries the `FIX_FINDING` `exempt because…`
  annotation; `track-review.md §Phase A Resume` no longer claims findings are
  "not persisted" in a way that contradicts the committed strategic files.
- **Step 4:** `track-review.md §Inputs` and `implementation-review.md` name a
  per-spawn output path in the strategic reviewers' input set (Phase A panel +
  review-gate-verification; Phase 2 consistency/structural + their
  gate-verifications), targeting `_workflow/plan/track-N/reviews/<type>-iter<N>.md`,
  and document the orchestrator's partial-fetch read of `## Findings`. The
  whole-track acceptance bullet "a strategic review … writes its file in the schema
  … the orchestrator partial-fetches `## Findings` from disk" is now satisfied by an
  implementing edit, not just the producer-prompt half from step 2.

<!-- Reserved for Move 3. -->

## Idempotence and Recovery

Each step is one commit; revert with `git reset --hard HEAD`. No runtime
dogfooding — every edit accumulates in the staged mirror (or, for
`.claude/scripts/tests/`, lands live outside `WORKFLOW_PATHSPECS`) and flips on at
the single Phase 4 promotion, so there is no half-applied schema to corrupt a
Phase C on this branch. The step-1 S4/S6 mechanical test is the recovery
net for the schema's count-validation contract; a regression there fails the test
rather than shipping a silent `CONTRACT_VIOLATION` post-promotion.

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope:**
- `.claude/workflow/conventions-execution.md` — new schema + coverage §2.x; §2.1
  review-file lifecycle + `plan/*` glob caution
- `.claude/workflow/workflow.md` — §Final Artifacts cleanup sweep of `plan/track-N/reviews/`
- `.claude/workflow/prompts/create-final-design.md` — confirm the blanket Phase 4
  `git rm -r _workflow/` already sweeps `plan/track-N/reviews/` (cleanup-prose
  mention only); it routes the Phase 4 cold-read through `edit-design`, so the
  output-path injection lands in `edit-design/SKILL.md`, not here (T4/R1)
- `.claude/skills/edit-design/SKILL.md` — Phase 4 cold-read spawn site (§Step 4
  `## Inputs` block): inject the `design-review.md` output path gated on
  `mutation_kind == phase4-creation`; the Phase 1 `phase1-creation` invocation
  passes none and stays exempt (T4/R1)
- `.claude/workflow/prompts/technical-review.md`, `risk-review.md`,
  `adversarial-review.md` — Phase A panel: write file + thin manifest
- `.claude/workflow/prompts/consistency-review.md`, `structural-review.md` —
  Phase 2 plan review: write file + thin manifest
- `.claude/workflow/prompts/consistency-gate-verification.md`,
  `structural-gate-verification.md`, `review-gate-verification.md` — strategic
  gate-verifications
- `.claude/workflow/research.md` — Explore delegation writes a file + returns a summary
- `.claude/workflow/prompts/design-review.md` — make output path-conditional:
  write file + return summary when handed an output path (Phase 4
  `phase4-creation`); inline verdict otherwise, so the Phase 1 `phase1-creation`
  cold-read stays byte-for-byte exempt
- `.claude/workflow/step-implementation-recovery.md` — light consistency pass
  (schema/lifecycle phrasing only; routing wording stays Track 4 — T6)
- `.claude/workflow/track-review.md` — (1) §Phase A Resume light consistency pass:
  reconcile the "findings not persisted to a separate file" prose with the
  committed strategic review files (R2/A3); (2) §Inputs strategic dispatch
  path-injection: add the per-spawn output path to the shared set for the Phase A
  panel + review-gate-verification spawn, plus the orchestrator's partial-fetch read
  (step 4, DL7)
- `.claude/workflow/implementation-review.md` — Phase 2 plan-review strategic
  dispatch: inject the per-spawn output path for the consistency/structural
  reviewers and their gate-verifications, plus the orchestrator's partial-fetch read
  (step 4, DL7)
- `.claude/workflow/review-mode.md` — `FIX_FINDING` exemption annotation
- `.claude/scripts/tests/` — count-validation (S4/S6) mechanical test (edited
  live, not staged — `.claude/scripts/` is outside `WORKFLOW_PATHSPECS`)

**Out of scope:** the dimensional `review-*` agents and their tactical routing
(Tracks 3-4); the staging plumbing (Track 1). This track defines the contract
and applies it to the strategic/research side only.

**Inter-track dependencies:** depends on **Track 1** (ordered after the
precursor; this track edits only `.claude/workflow/**`, which stages under the
existing two-prefix rule, so the dependency is ordering, not a hard path
requirement). Downstream — **Track 3** cites this schema for the dimensional
agents; **Track 4** cites it for tactical routing and the `basis`/`cert`
manifest fields.

## Base commit

fd6a7322e79671ac6bef45b93cef200571817eb7
