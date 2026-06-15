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
