# Design mutations log

## Mutation 1 — 2026-05-05 — phase1-creation (design.md)

**Diff summary**: Initial seed of `design.md` for the read-cache
concurrency bug fix. Single-file design (no `design-mechanics.md`
companion) covering Overview, Class Design, Workflow, and four content
sections — Cache primitive (`loadOrAdd`), Allocation discovery surface,
Concurrency model, and Crash safety. Concept-first Overview (≤40 lines)
names the race vector and the structural fix. Class Design diagrams the
post-fix shape; Workflow shows three runtime paths (write-side
allocation, recovery gap-fill, cross-TX read). Each content section
follows the mandatory shape (TL;DR + mechanism overview + Edge cases +
References). All five Decision Records (D1-D5) and five Invariants
(I1-I5) referenced from the implementation plan resolve to sections in
this file.

**Mechanical checks** (target=design): PASS — 0 findings on second
iteration. First iteration flagged Overview length at 45 lines (cap 40);
trimmed to ~36 lines by tightening prose around the race vector
description, removing one sentence on `getAndAdd` / async stamp
mechanics that already lives in §"Cache primitive: loadOrAdd".

**Cold-read** (scope: whole-doc): PASS — 3 suggestions, no blockers, no
should-fix.

**Findings**:
- suggestion: `## Class Design` and `## Workflow` lack a formal
  `**TL;DR.**` marker. These sections are shape-exempt per
  `design-document-rules.md` § Per-section mandatory shape, but adding
  a one-line TL;DR above the diagram in each would aid cold-reader scan.
- suggestion: §"Allocation discovery surface" mentions three components
  (`CollectionDirtyPageBitSet`, `FreeSpaceMap`, `IndexHistogramManager`)
  whose logical-size accessor is not yet confirmed and defers to Phase
  A. Could state explicitly whether an unconfirmed component blocks the
  whole migration or only its own row.
- suggestion: §"Crash safety" Scenario D references a follow-up
  ("`fallocate` for batched extension" or "block WAL atomic-unit close
  until all per-page ensure-valid tasks have completed") without a
  tracked ticket id. Once an issue file is created, replace with a
  concrete pointer.

**Iterations**: 2 of 3 (PASS)

## Mutation 2 — 2026-05-05 — content-edit (design.md)

**Diff summary**: Corrected the closing paragraph of §"Workflow"
§"Cross-TX read path". The previous text claimed the cross-TX page is
"durably on disk" after `TX_A` commits, which overstated the disk-side
guarantee. The corrected text replaces it with the actual write-ahead
invariant: WAL records may still be in the in-memory log buffer at
commit time, but any subsequent flush of the page to disk happens only
after `TX_A`'s WAL records are durable. So `TX_B` finds the page
either still cache-resident with `TX_A`'s content or already flushed
under WAL protection — either way, the load branch is reachable, the
extend branches are not, and the race vector is unreachable from this
path. Mermaid diagram unchanged.

**Mechanical checks** (target=design, scope=bounded,
changed-section="Workflow"): PASS — 0 findings.

**Cold-read** (scope: bounded — Workflow + Class Design + Cache
primitive: loadOrAdd + Overview): PASS — 0 findings, no blockers, no
should-fix, no suggestions. Comprehension assessment confirmed the
corrected text is self-contained, internally consistent with §"Cache
primitive: loadOrAdd" (which states the read path's load branch is the
only reachable one) and §"Concurrency model" (which states `pagesSize`
is bumped only after the WAL atomic unit closes), and that Overview
contains no residual "durably on disk" claim.

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 3 — 2026-05-05 — structural-rewrite (design.md)

**Diff summary**: Coordinated edits across two sections (and ripple
into plan + backlog) to remove a phantom vulnerability and align the
doc with `wowCacheFlushExecutor`'s actual concurrency model.

§"Cache primitive: loadOrAdd": branch table reduced from four to three
branches (Load existing, One-page extend, Gap-fill); the "Orphan
re-stamp" branch was dropped because it modeled a state
(`AsyncFile.size > disk file size`) that is not reachable after reopen
and was conflated with sparse-zero gaps that don't actually occur.
TL;DR updated to drop "re-stamps an in-flight orphan". Added a
sentence above the branch table noting that magic-stamped disk-resident
orphans are absorbed by the Load existing branch with no special-casing.
Edge case bullet "Orphan re-stamp double-submit" removed. New paragraph
under the table explains that magic-check failure on the load branch
propagates as `StorageException` (today's behavior, unchanged) and
points at the new related-issue file.

§"Crash safety": Scenario D ("TX committed, multi-page allocation,
task partially ran") removed entirely. The TL;DR is rewritten to name
the actual executor model (`wowCacheFlushExecutor` is single-threaded
per `YouTrackDBEnginesManager.java:231`; submissions for a given
`fileId` are monotonic in pageIndex by construction) and to explicitly
note that FIFO + monotonic submission forecloses sparse-zero interior
pages. Scenario A's sub-cases tightened to drop the "unstamped orphan
sparse / zeros" branch (impossible under FIFO + monotonic). References
footer updated to point at `ISSUE-ensurevalidpagetask-torn-write.md`.
Sub-section "Role of `EnsurePageIsValidInFileTask` in the new design"
unchanged.

Plan + backlog ripple edits: dropped every reference to "orphan
re-stamp" / "four branches" / "task-partially-ran" and replaced with
the three-branch / three-scenario framing; updated Track 4's crash
safety constraint to point at design.md and the new issue file.

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0
findings on second iteration. First iteration passed mechanical
already; the rewrite was triggered by user-reported semantic
inconsistency, not by a mechanical-check finding.

**Cold-read** (scope: whole-doc): NEEDS REVISION on first pass — the
sub-agent flagged 9 residual references across design.md (1),
implementation-plan.md (3), and implementation-backlog.md (5) that
still mentioned "orphan re-stamp" / "four branches" / "Scenario D".
Second-pass verification (after fixes) confirmed all 11 numbered
points resolved.

**Related artifact**: created
`/home/andrii0lomakin/Projects/ytdb/read-cache-concurrency-bug/ISSUE-ensurevalidpagetask-torn-write.md`
(34 lines) tracking the orthogonal torn-write / OS-writeback durability
gap. Marked out-of-scope for the read-cache concurrency fix; defers
detail to a separate investigation agent.

**Findings**: 9 should-fix residual inconsistencies on first cold-read
(all addressed in iteration 2). No blockers. No remaining findings on
the verification pass.

**Iterations**: 2 of 3 (PASS)
