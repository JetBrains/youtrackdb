# Track 1: Remove legacy null checks in DurableComponent

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/1 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical — no blockers, two should-fix findings accepted (see reviews/track-1-technical.md)

## Steps
- [ ] Step 1: Remove null branches and add @Nonnull annotations
  > Remove `if (atomicOperation == null)` branches in 5 DurableComponent
  > methods: `loadPageForRead()`, `releasePageFromRead()`, `getFilledUpTo()`,
  > `openFile()`, `isFileExists()`. Replace with `assert atomicOperation != null`
  > (consistent with existing write-method pattern in the same class).
  >
  > Additionally, add `@Nonnull` annotations to all `AtomicOperation` parameters
  > on public interface methods (`CellBTreeSingleValue`, `IndexEngine`, and
  > other DurableComponent subclass public methods) that currently lack them.
  > This makes the non-null invariant machine-checkable (review finding T1).
  >
  > Files to modify:
  > - `DurableComponent.java` — remove 5 null branches, add assertions
  > - `CellBTreeSingleValue.java` — add @Nonnull to atomicOperation params
  > - Other public interfaces/subclasses with missing @Nonnull annotations
  >
  > Tests: Existing test suite covers all these methods (no null is ever passed).
  > Run `./mvnw -pl core clean test` to verify no regressions.
