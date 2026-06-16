# Design mutation log — transactional-schema

Append-only record of every `design.md` mutation: diff summary, mechanical-check
result, cold-read verdict, iteration count. Not stamped (`conventions.md`
§1.6(f) exclusion).

## Mutation 1 — 2026-06-15 — phase1-creation (design.md)

**Diff summary**: Created the Phase-1 seed `design.md` for the YTDB-382
transactional schema feature (full tier), single file, at design altitude.
Authored from the research log's `## Decision Log` (D1-D20) and
`## Invariants and Test Requirements` (the I-* invariants); mechanism detail
stays in the research log's `## Delegated to implementation` and the future
track files. Structure: Overview → Core Concepts (nine concepts) → Class Design
(two classDiagrams) → Workflow (one sequence diagram) → Part 1 (the
transactional schema model) → Part 2 (index transactionality) → Part 3
(concurrency and locking) → Part 4 (schema-format migration). 15 top-level
sections, no `design-mechanics.md` companion (well under the length trigger).

**Mechanical checks** (target=design, scope=whole-doc): PASS after one revision.
First run flagged 28 should-fix (0 blocker): 27 `decision-cited-without-rationale`
(every footer D-code lacked an inline rationale) and 1 `top-level-cap` (17
sections over the ~15 cap). Resolved by adding a one-line rationale to every
footer D-code citation and by two consolidations — the de-guarding section
merged into "The tx-local schema view and transactional enablement", and the
two migration sections merged into "Schema-format migration" — bringing the
count to 15. Re-run: 0 findings.

**Cold-read** (scope: whole-doc): PASS. Absorption-completeness cross-check
complete in both directions — all 20 research-log decisions (D1-D20) appear as
seed D-records, all 25 invariants are seeded, and no design D-record invents a
decision the log never recorded. The S3 freeze-order gate was clear before the
cold-read ran (the research log's `## Adversarial gate record` shows the
Phase-0→1 gate at iter-3 PASS). Two findings, both resolved:
- should-fix: the Workflow sequence diagram acquired `stateLock.writeLock`
  before the schema and index-manager locks, contradicting the four-lock order
  (mutex → `SchemaShared.lock` → index-manager lock → `stateLock.writeLock`)
  stated three times in the prose and load-bearing for the deadlock-freedom
  proof (I-C1). The diagram conflated D19's "write-lock path from the start"
  (path selection at entry, not read-then-upgrade) with "acquired first".
  Resolved by reordering the diagram to acquire the metadata locks before
  `stateLock.writeLock` and rewording the annotation.
- suggestion: the freezer section used a colon-suffixed heading breaking the
  uniform noun-phrase style, and the Core Concepts cross-reference used a prefix
  of the full heading. Resolved by shortening the heading to "The freezer gate"
  so the reference resolves exactly and the heading style stays uniform.

**Findings**:
- should-fix (mechanical, 27): footer D-codes cited without inline rationale — resolved.
- should-fix (mechanical, 1): 17 sections over the ~15 top-level cap — resolved (→ 15).
- should-fix (cold-read, 1): Workflow diagram lock order reversed vs the authoritative four-lock order — resolved.
- suggestion (cold-read, 1): freezer colon-heading and partial cross-reference — resolved.

**Iterations**: 1 of 3 (PASS). The cold-read returned PASS; the two findings
were applied as the reviewer prescribed. A full cold-read re-spawn was not run
for the post-PASS fixes — both were localized (a two-line diagram reorder and a
heading rename) and the mechanical re-check confirmed zero findings, with the
diagram now matching the thrice-stated authoritative lock order.

## Mutation 2 — 2026-06-16 — content-edit (design.md)

**Diff summary**: Flushed the Phase-1 review-hold batch (D15) of four readability
clarifications raised during the user's design review, applied as one mutation.
(1) "Tx-local index overlay": tightened the loose "create/drop" to "index
create/drop (`createIndex` / `dropIndex`)" and added a clause that a tx-created
index's committed entries come from the commit-time re-derivation (cross-ref
"Index build and query-usability"), not the per-record `ClassIndexManager`
tracking the force-rebuild surfaces — that tracking framing applies to
pre-existing indexes only, so an implementer cannot re-introduce the F66
silent-untracking corruption. (2) "Mutex lifecycle and the permit handshake":
comprehension expansion — a "What the pieces are" gloss (Semaphore(1)-not-
ReentrantLock, the `(session, ordinal, thread)` ownership record explained by
role), a two-paths-to-release paragraph, the engage/teardown store-then-load
handshake prose, and a new Mermaid flowchart of the three-outcome race.
(3) "The freezer gate": glossed the two freeze kinds with examples and named the
exception type `ModificationOperationProhibitedException`; cleared a pre-existing
em-dash triple-clause in the same paragraph. Decisions and invariants unchanged
(D7, D12/D13/D15, I-P1..I-P4, I-handshake-1, I-C3, I-freezer-1) — clarifications
only, no gate run.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS (0 blockers, 0 should-fix, 2 suggestions)

**Findings**:
- suggestion (cold-read): off-convention in-doc cross-ref form in the new overlay
  clause — resolved (changed to `(see "Index build and query-usability" below)`).
- suggestion (cold-read): a pre-existing "(see the research log's delegated list)"
  pointer sits in an edited sentence and is unresolvable for a cold reader — NOT
  applied; dropping only this one would make it inconsistent with the doc's other
  research-log references. Deferred to the doc-wide readability-feedback pass.

**Iterations**: 1 of 3 (PASS). Six em-dash overuns introduced in the mutex
expansion were corrected before the mechanical check (down to zero per paragraph).
The section-length-cap exemption flagged in the queue was not needed — the
expanded sections stayed under the cap (mechanical PASS). Suggestion 1 was a
one-line cosmetic cross-ref fix; no full cold-read re-spawn (mechanical re-check
confirmed PASS).

## Mutation 3 — 2026-06-16 — content-edit (design.md)

**Diff summary**: First half of the scheduled doc-wide readability pass — a cold
prose rewrite by a dedicated author sub-agent, run as the YTDB-1130 two-role
author/cold-auditor loop in place of the standard cold-read. A five-range cold
audit (auditor contract: read only `house-style.md` and the doc, enumerate every
passage a context-free reader cannot reconstruct) flagged 55 obscure passages, 0
GAPs — the ruleset already catches every one, so the pass produced no rule
hardening. An author sub-agent reading only the frozen `design.md` and
`house-style.md`, prompted to write for a reader who has only the finished doc,
rewrote the flagged prose holistically (prose, TL;DR, and Edge-cases bullets only;
340 insertions, 298 deletions). Decisions, invariants, mechanisms, the four-lock
order, the Dekker handshake, the drop-detection source, the I-P2/F66 two-source
split, and the freezer windows are unchanged. Mermaid bodies, the `### Decisions &
invariants` lists, and section headings were not touched. The orchestrator verified
semantic preservation passage by passage from the diff; the author's added glosses
are faithful and doc-grounded, with no decision drift.

**Mechanical checks** (target=design): PASS (0 findings). Overview 39 lines (cap 40).

**Cold-read** (scope: whole-doc): replaced by the YTDB-1130 cold-auditor verify-half
(the auditor contract, not the standard verifying reviewer — which, per YTDB-1130,
had passed this very doc at zero prose-density findings and is the wrong instrument
for verifying a readability fix). Three-pass finding count: 55 (frozen original) →
57 (a discarded warm main-agent self-edit pass, which introduced two new tells) →
54 (this cold-author pass, kept). The loop converged to a domain-density floor, not
to clean.

**Findings**:
- Floor at ~54. The residual findings are irreducible concurrency-mechanism
  density, the cap-bound Overview inventory sentences, and the parallel
  Core-Concepts noun-phrase form. The cold author removed the specific tells the
  original carried (garden-paths, idioms, split predicates, missing copulas); the
  flat aggregate is the floor, not a failed fix.
- Two findings deliberately left: the Overview "four primitives" / "several
  subsystems" inventories (a multi-line list breaches the 40-line cap) and the
  Schema-write-mutex Core-Concept entry (a copula breaks the nine-entry parallel
  glossary form). Both forward-point to fully-glossed downstream sections.
- Root-cause diagnosis carried to Mutation 4 (next session): a large share of the
  floor is "the passage needs current-system grounding the doc never states", which
  a prose-only author cannot add. Mutation 4 gives the author the audit findings
  plus codebase access (grep / `Read`, mcp-steroid PSI for reference-accuracy) and a
  TRANSLATE mandate — establish current state then the change, grounded in code,
  written at reader level.

**Iterations**: 1 author round (PASS-by-floor; a second prose-only round judged
low-value). Env: `fable` unavailable, so author and auditor spawns ran on the
session default. The YTDB-1130 comment is deferred to Mutation 4, since the
code-grounded result is the better data point.

## Mutation 4 — 2026-06-16 — content-edit (design.md) — code-grounded readability pass-2

**Kind**: content-edit (readability enrichment). No decision, invariant, mechanism,
or four-lock-order change. design.md 889 → ~1044 lines. Line-1 stamp, Mermaid
bodies, `### Decisions & invariants` lists (all 20 D-records, all 25 invariants),
and the four-lock order verified preserved against the committed baseline.

**Method**: re-audited the committed pass-1 doc with the 5-range cold-auditor
contract, then a code-grounded author (the audit worklist + codebase access via
grep/`Read` + mcp-steroid PSI for reference-accuracy + a TRANSLATE mandate)
established current-state-then-change for each flagged passage, grounded in real
symbols.

**Data (YTDB-1130 — does code-grounding beat the prose-only floor?): yes.**
- Pass-1 prose-only: 55 (frozen) → 57 (warm self-edit, discarded) → 54
  (cold-author, kept). Floor ~54; prose reshaping could not beat it.
- Pass-2 code-grounded, same 5 auditors (controls for auditor variance): fresh
  independent baseline 76 (the gap from the pass-1 internal 54 is auditor
  variance) → ~38 after one author round (−50%). Part 1 converged 19 → 6 → 3
  across two rounds. 0 GAPs throughout — no rule hardening.
- The loop converges to a domain-density floor (the Dekker handshake, the mutex
  ownership-record prose, the cap-bound Overview inventories), not to zero.

**Code claims verified accurate**: `ScalableRWLock` non-reentrant and `stateLock`
is one; `OperationsFreezer` `cutWaitingList`/`LockSupport.unpark`/`freezeRequests`;
`EXPORTER_VERSION` 14; collection name `<lowercase-classname>_<counter>`;
`fromStream`/`toStream` on `SchemaClassImpl`; `SchemaClassProxy` captured delegate;
`addCollectionToIndex`/`removeCollectionFromIndex` self-commit via
`executeInTxInternal`; `checkOpenness` CLOSED-status guard; `reload` takes
`SchemaShared.lock`; `commitChanges` success-path-only. Two design-level claims
left as the prose's own assertion (index-build WAL atomicity; the forward/reverse
collection maps glossed by direction only, asserting no false `SchemaShared` field).

**Process incident (recorded honestly)**: a named-teammate `Agent` spawn returned a
roster error but its underlying agent launched and kept running; with a shared
`TaskList` present it auto-pulled the orchestration tasks and co-edited design.md as
a rogue, alongside two intended authors — three concurrent writers. The
read-before-edit guard serialized the edits (no lost updates; structure, D-records,
invariants, lock order, and code claims all verified intact afterward), so the doc
passed every integrity check, but the run was contaminated — the exact final
integer is indicative, not a clean single-author measurement. Recovery: cleared the
task board to stop the auto-pull, confirmed the file static. The clean exact-final
re-audit was skipped (no further agents per the user's instruction). A trivial
line-wrap orphan in the provisional-id paragraph was fixed by hand.

**Env**: `fable` unavailable; spawns ran on the session default (opus).
