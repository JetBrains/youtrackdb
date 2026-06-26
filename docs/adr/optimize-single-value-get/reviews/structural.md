# Structural Review — optimize-single-value-get

## Iteration 1

### S1 [suggestion] — ACCEPTED
DurableComponent in diagram but not in annotated bullet list. Added bullet.

### S2 [should-fix] — ACCEPTED
Invariants don't reference which track's tests verify them. Added annotations.

### S3 [suggestion] — ACCEPTED
Track 1 could mention test classes for regression verification. Added references
to IndexesSnapshotVisibilityFilterTest, SnapshotIsolationIndexesGetTest,
SnapshotIsolationIndexesUniqueTest.

### S4 [suggestion] — No action needed
Before/after comparison in design doc is effective. Positive observation.

### S5 [should-fix] — ACCEPTED
Missing note about negative insertion point case in workflow prose. Added.

### S6 [should-fix] — SKIPPED
checkVisibility() and null key sections slightly too code-level. Kept as-is —
the detail is needed for correctness of complex visibility logic.

### S7 [suggestion] — SKIPPED
Cross-page test complexity. Execution agent handles tactical decisions.

### S8 [suggestion] — SKIPPED
"(if any)" parenthetical for non-SI callers. Minor.

## Result: PASS
No blockers. 4 findings applied, 4 skipped.

---

## Track 5 Review

### S9 [should-fix] — ACCEPTED
Component Map missing `IndexMultiValuKeySerializer`. Added node + relationship + bullet.

### S10 [should-fix] — ACCEPTED
`preprocess()` parenthetical was ambiguous. Clarified wording.

### S11 [should-fix] — REJECTED
Design.md coincidentally matches Track 5 target state. Frozen — no meta-notes added.

### S12 [should-fix] — ACCEPTED
D4 deferral said "after Track 4" but Non-Goals said "after Track 5". Updated D4 to match.

### S13 [suggestion] — No action needed
Scope ~3 steps is tight but plausible. Execution agent can split if needed.

### S14 [suggestion] — REJECTED
D5 risks could mention per-type correctness. Test strategy already covers this.

### S15 [should-fix] — No action needed
WAL variant and compareInByteBuffer coupling noted. Scope indicator is flexible.

## Result (Track 5): PASS
No blockers. 3 findings applied (S9, S10, S12), 4 skipped/rejected.
