# Track 1 Technical Review

## T1 [should-fix] — Snapshot-derived key vs B-tree key semantic change
After refactoring, the stream path emits the original B-tree entry's key
(with original version) instead of the snapshot-derived key. No caller
depends on the version component. Safe but should be documented.
**Decision**: Accept — document in step description.

## T2 [should-fix] — Test strategy clarification
`emitSnapshotVisibility()` retains its signature and delegates to the new
helper. Existing ~20 direct tests remain valid. New tests needed for
`checkVisibility()` covering all branches.
**Decision**: Accept — include in step decomposition.

## T3 [suggestion] — hasInProgress short-circuit
Consider pre-computing `hasInProgress` boolean to match current optimization.
**Decision**: Accept — implement in checkVisibility.

## T4 [suggestion] — null atomicOperation handling
checkVisibility receives pre-computed values, null handling is caller's
responsibility. Track 2 must replicate this.
**Decision**: Noted for Track 2.
