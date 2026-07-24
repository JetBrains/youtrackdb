<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A4, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: V-A4, verdict: VERIFIED, anchor: "#### V-A4 "}
flags: [CONTRACT_OK]
-->

## Findings

(none — converged. A4 VERIFIED, no new finding.)

## Evidence base

#### V-A4 — Verdict: A4 (I-P2 positive membership-coverage test + membership-only changed-index category) — VERIFIED
- **Prior finding**: A4 [should-fix] — I-A7 and I-P2 pinned the *negative* collection-membership properties (facet (a) no self-commit leak, facet (b) rollback leaves the shared `Index`'s `collectionsToIndex` untouched), but neither pinned the *positive* post-commit coverage (facet (c)): after a committed `addSuperClass` / alter-add-collection, the parent index must actually cover the new subclass collection. An implementer could de-guard the self-commit (pass I-A7's isolation+rollback test) and route the change through the overlay without mutating a shared object mid-tx (pass I-P2's negative clause), yet omit the membership-only changed-index category, so commit persists no `collectionsToIndex` delta and a polymorphic query silently under-returns the subclass rows after commit.
- **Resolution checked** (commit `649608d158`, research-log.md lines 6285-6306): I-P2's **Invariant** now adds "A collection-membership change on a committed index (the polymorphic ripple from `addSuperClass`, or an alter-add-collection on a class with an indexed superclass) is a tracked changed-index category in its own right, so the commit persists the `collectionsToIndex` delta and the parent index covers the new subclass collection afterward." I-P2's **Test** now adds the positive case: "commit an `addSuperClass` (or alter-add-collection) that adds a subclass collection to a superclass's index, then run a polymorphic query through that index and assert it returns the subclass rows. An implementation that routes the membership change through the overlay but omits the membership-only changed-index category passes the isolation and rollback tests (I-A7) yet fails this positive coverage." Provenance cites `F46`; Mechanism already enumerates "in-place collection-membership" as the fourth overlay category.
- **Does I-P2 now pin the positive post-commit membership coverage?** YES. The invariant states the positive property as a checkable fact about on-disk `collectionsToIndex` membership, and the test discriminates it: it asserts the polymorphic query resolves *through the index* and returns the subclass row, which fails under an implementation that omits the membership-only changed-index category (the committed `collectionsToIndex` excludes the new collection → index lookup under-returns). The test is the symmetric positive of I-A7's negative test and reuses the same constructible ripple trigger.
- **Would the test catch an implementation that omits the membership-only changed-index category?** YES. If the commit-time reconciliation treats the membership change as create/drop/rename only (the F46 facet-(c) omission), no `collectionsToIndex` delta is persisted; the post-commit polymorphic-through-index query under-returns and the assertion fails. The test text names this exact failure mode as "the silent under-return the category exists to prevent."
- **Code evidence**: PSI (eid `adv-iter3-verify`) over current code — `collectionsToIndex` is a real `Set<String>` field on `IndexAbstract` (and `IndexMetadata`), so the membership store the positive test asserts against is a concrete symbol an implementer builds the coverage check around. `IndexManagerEmbedded#addCollectionToIndex(4)` and `#removeCollectionFromIndex(3)` self-commit via `executeInTx*` with no `isActive()` throw-guard (facet-(a) sites, caught by I-A7's negative test); the throw-guard sites (`saveInternal`, `dropClass`/`dropClassInternal`, `createIndex(8-arg)`, `dropIndex`) carry the active-tx throw shape. The ripple trigger (`addSuperClass`) and the persistence target (`collectionsToIndex`) both resolve to real symbols, so the positive test is constructible.
- **No contradiction / no untestable clause / no new I-A7 seam**: the new positive-coverage clause is a *post-commit* property; I-P2's "commit-only, never mutate a shared `Index` mid-tx" clause and I-A7's isolation+rollback test are *mid-tx / rollback* properties. They compose without conflict. Ownership is cleanly partitioned — I-A7 owns the de-guarding (facets a/b), I-P2 owns the persistence/coverage (facet c) — and the test text states the split explicitly. The F46 trail is closed on I-P2's Provenance. The fix introduces no contradiction, no untestable clause, and no double-ownership or orphaned facet with I-A7.
- **Verdict**: VERIFIED — I-P2 now pins the positive post-commit membership coverage with a discriminating test that catches the membership-only-category omission. The three F46 facets are now each captured by a catching test (a/b by I-A7, c by I-P2). The invariant list is complete and testable; the gate has converged.
