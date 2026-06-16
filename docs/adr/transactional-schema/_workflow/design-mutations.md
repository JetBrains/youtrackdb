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
