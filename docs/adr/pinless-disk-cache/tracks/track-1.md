# Track 1: Remove legacy null checks in DurableComponent

## Progress
- [x] Review + decomposition
- [x] Step implementation (1/1 complete)
- [x] Track-level code review

## Base commit
`b1ef7073df`

## Reviews completed
- [x] Technical — no blockers, two should-fix findings accepted (see reviews/track-1-technical.md)

## Steps
- [x] Step 1: Remove null branches and add @Nonnull annotations
  > **What was done:** Removed 5 `if (atomicOperation == null)` dead-code
  > branches in `DurableComponent` read methods (`getFilledUpTo`,
  > `loadPageForRead`, `releasePageFromRead`, `openFile`, `isFileExists`),
  > replacing them with `assert atomicOperation != null` — consistent with
  > the existing write-method pattern. Added `@Nonnull` annotations to all
  > `AtomicOperation` parameters across 8 public interfaces:
  > `CellBTreeSingleValue`, `BaseIndexEngine`, `IndexEngine`,
  > `V1IndexEngine`, `SingleValueIndexEngine`, `MultiValueIndexEngine`,
  > `CellBTreeMultiValue`, `StorageCollection`.
  >
  > **Key files:** `DurableComponent.java` (modified),
  > `CellBTreeSingleValue.java` (modified), `BaseIndexEngine.java` (modified),
  > `IndexEngine.java` (modified), `V1IndexEngine.java` (modified),
  > `SingleValueIndexEngine.java` (modified),
  > `MultiValueIndexEngine.java` (modified),
  > `CellBTreeMultiValue.java` (modified),
  > `StorageCollection.java` (modified)
