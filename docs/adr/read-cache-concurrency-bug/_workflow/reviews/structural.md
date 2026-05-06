# Structural Review

## Iteration 1

### Finding S1 [should-fix]
**Location**: Track 1 plan-file intro paragraph (lines 232-242) vs. Track 1 backlog `**What**` section (lines 26-29) and Constraints (lines 96-100); cf. Track 4 backlog (lines 365-369).
**Issue**: Cross-file contradiction about *when* `allocateNewPage` is deleted. The plan-file Track 1 intro says "Delete `WOWCache.allocateNewPage` and `LockFreeReadCache.allocateNewPage`. Mirror in `DirectMemoryOnlyDiskCache`." — i.e., the deletion is part of Track 1's scope. The backlog Track 1 explicitly says these methods are *marked deprecated* in Track 1 and "deletion lands in Track 4 once replay-loop callers migrate"; the backlog Constraints reaffirm that "the deletion of `WriteCache.allocateNewPage` is deferred to Track 4". Backlog Track 4 then carries the actual deletion. The execution agent reading only the plan-file intro will pull deletion forward into Track 1 and break the replay-loop callers that still reference `allocateNewPage` until Track 4 collapses them.
**Proposed fix**: Reword Track 1's plan-file intro to match the backlog — replace "Delete `WOWCache.allocateNewPage` and `LockFreeReadCache.allocateNewPage`" with "Mark `WOWCache.allocateNewPage` and `LockFreeReadCache.allocateNewPage` (and the in-memory engine's parallel) deprecated; final deletion deferred to Track 4." Optionally, add a one-line reminder in Track 4's plan-file intro that the final `allocateNewPage` deletions land there alongside `addPage`.

### Finding S2 [should-fix]
**Location**: Track 1 plan-file intro (lines 232-242) — 4 sentences.
**Issue**: Intro paragraph runs 4 sentences ("Rewrite ... Delete ... Mirror ... Keep ..."). The TRACK DESCRIPTIONS rule caps the plan-file intro at 1-3 sentences because the plan checklist is loaded at every `/execute-tracks` startup; everything beyond a 1–3-sentence high-level pointer belongs in the backlog's W/H/C/I subsections.
**Proposed fix**: Collapse to 1-3 sentences, e.g.: "Rewrite the write-cache around a single total `loadOrAdd(fileId, pageIndex, verifyChecksums)` primitive covering load / one-page extend / multi-page gap-fill, with the in-memory engine mirroring it. Both `LockFreeReadCache` wrappers (`loadForRead` / `loadOrAddForWrite`) collapse to a `data.compute` lambda that delegates to `loadOrAdd`." The deletion / deprecation detail belongs in the backlog (where it already is).

### Finding S3 [should-fix]
**Location**: Track 2 plan-file intro (lines 244-256) — 4 sentences.
**Issue**: Intro paragraph runs 4 sentences ("Audit ... Add unit tests ... Add MT stress harnesses ... Run the coverage gate ..."). Same rationale as S2 — exceeds the 1–3-sentence cap.
**Proposed fix**: Compress to 2 sentences, e.g.: "Add functional unit tests covering every branch of `WOWCache.loadOrAdd` and the `LockFreeReadCache` wrappers, plus MT stress harnesses for contention, eviction, and `EnsurePageIsValidInFileTask` idempotency. Run the cache-classes coverage gate before closing the track." The site-by-site test inventory and audit detail already live in the backlog.

### Finding S4 [should-fix]
**Location**: Track 3 plan-file intro (lines 258-275) — 4 sentences spread across multiple bullet-style clauses, longest of the six tracks.
**Issue**: The intro spans four sentences plus a parenthetical and runs 17 lines in the checklist — well beyond the 1–3-sentence budget. The detailed call-site enumeration belongs in the backlog (and indeed already does).
**Proposed fix**: Reduce to 2 sentences naming the migration target in strategic terms, e.g.: "Migrate the pure-sizing production callers of `StorageComponent.getFilledUpTo` (≈9 sites across BTree, CollectionPositionMapV2, PaginatedCollectionV2, FreeSpaceMap, IndexHistogramManager, CollectionDirtyPageBitSet) to read each component's logical `entryPoint.pagesSize` / `fileSize`. The reuse-or-extend probe sites and the lone storage-quiesced backup caller stay until Tracks 4 / 5." The exact site list and the `SharedLinkBagBTree` clarification already live in the backlog.

### Finding S5 [should-fix]
**Location**: Track 4 plan-file intro (lines 276-296) — 7+ sentences over 16 lines.
**Issue**: Easily the worst offender against the intro budget. The intro narrates the full Track 4 design — `AtomicOperation` interface change, `loadOrAddPageForWrite` rewire, replay-loop collapse, `internalFilledUpTo` removal, probe deletion. All of this is already in the backlog Track 4 `**What**` / `**How**` sections.
**Proposed fix**: Replace with a 2–3 sentence pointer, e.g.: "Delete the `addPage` API surface (`StorageComponent.addPage` + `AtomicOperation.addPage` + their 19 external production call sites) and migrate to `loadOrAddPageForWrite(fileId, knownIndex)` on top of Track 1's primitive. Collapse the `commitChanges` / `restoreAtomicUnit` / `restoreFromIncrementalBackup` reconciliation loops, drop the `internalFilledUpTo` prediction wrapper, and delete the per-component reuse-or-extend probes." Detail is preserved in the backlog.

### Finding S6 [should-fix]
**Location**: `StorageComponent` component-intent bullet in the Component Map (plan lines 81-88) — 8 lines.
**Issue**: The component intent runs 8 lines (≤5 budget). The expansion is a parenthetical explaining how PSI's reference count of 20 reconciles with the "19 external call sites" claim — that's design-level explanation that already lives in design.md §"Allocation discovery surface" ("The 20th PSI reference to `addPage` is the recursive call inside `StorageComponent.loadOrAddPageForWrite` ..."). The Component Map bullet should state intent only.
**Proposed fix**: Trim to ≤5 lines, e.g.: "`StorageComponent` — `addPage` is deleted; 19 external production call sites migrate to `loadOrAddPageForWrite(fileId, knownIndex)` where `knownIndex` comes from `entryPoint.pagesSize + 1` or known fresh-file state. Reuse-or-extend probes (`if pageSize < filledUpTo - 1`) are removed. See design.md §\"Allocation discovery surface\" for the PSI-reference reconciliation." The 4-line `loadOrAddPageForWrite` self-reference parenthetical moves out of the plan.

### Finding S7 [suggestion]
**Location**: Decision Record D5 (lines 164-185).
**Issue**: D5 records the *rejected* marker-bit alternative as a standalone DR. The structural rules don't forbid this shape — D5 is not marked `(SUPERSEDED)` and the four-bullet form fits within 30 lines (D5 is 22 lines). However, the planning convention for superseded approaches is to fold the rejection into the *replacing* DR's rationale rather than carry a dedicated "rejected alternative" DR. The current shape is defensible (the rejection is meaningfully cross-cutting across D1+D2+D3, no single DR fully owns it), but reviewers may flag it as a style miss.
**Proposed fix**: Optional. Either keep D5 as-is (it carries useful information about the prior design iteration that future readers may need) or fold its content into D1's Rationale ("This replaces an earlier marker-bit + adopt-on-existing approach where ...") and delete D5. No action required for the structural review to pass — this is a style suggestion only.

---

## Summary

- **Blockers**: 0
- **Should-fix**: 6 (S1–S6) — one cross-file inconsistency about `allocateNewPage` deletion timing (S1); five plan-file intro / Component-Map-intent budget violations (S2–S6).
- **Suggestions**: 1 (S7) — optional reshaping of the rejected-alternative DR.

---

## Iteration 2 — Gate Verification

**Verdict: PASS.** All 6 should-fix findings verified as correctly
fixed; S7 (suggestion) confirmed REJECTED with sound reasoning.
No new structural blockers introduced by the trims.

### Outcomes

- **S1 — VERIFIED.** Plan-file Track 1 intro now reads "Legacy
  `allocateNewPage` methods are deprecated here; final deletion
  lands in Track 4 once replay-loop callers migrate." Plan-file
  Track 4 intro now explicitly says "Final `allocateNewPage`
  deletions on `WriteCache` / `LockFreeReadCache` /
  `DirectMemoryOnlyDiskCache` land here, alongside the `addPage`
  deletion." Cross-file consistency restored: plan ↔ backlog
  Track 1 §What ↔ backlog Track 1 §Constraints ↔ backlog Track 4
  §What all agree on the deletion timing.
- **S2 — VERIFIED.** Track 1 intro trimmed to 3 sentences (was 4).
- **S3 — VERIFIED.** Track 2 intro trimmed to 2 sentences (was 4).
- **S4 — VERIFIED.** Track 3 intro trimmed to 2 sentences (was 4+).
  `SharedLinkBagBTree` removed from Track 3 component list (it
  belongs to Track 4).
- **S5 — VERIFIED.** Track 4 intro trimmed to 3 sentences (was 7+).
- **S6 — VERIFIED.** `StorageComponent` Component-Map bullet
  trimmed to 5 lines (was 8). Now links to design.md §"Allocation
  discovery surface" for the PSI reference-count reconciliation.
  Other Component Map bullets unaffected — all stay ≤5 lines.
- **S7 — REJECTED, downstream clean.** D5 stays as-is. Re-read of
  D1/D2/D3/D4/D5 (each 12–22 lines, all ≤30) confirms cross-cutting
  rejection record fits no single DR's rationale. No duplication
  with design.md detected.

### Re-scan summary

Re-scanned: plan §Component Map, all 4 track intros, all 5 DRs,
plan Checklist line-count. No new structural issues found. All
track intros now ≤3 sentences; StorageComponent bullet ≤5 lines;
allocateNewPage timing consistent across plan + backlog.

## Phase 2 — Structural Review: PASS

The plan is now structurally sound on all dimensions checked.
Ready for Phase 3 (`/execute-tracks`).
