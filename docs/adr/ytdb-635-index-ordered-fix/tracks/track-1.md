# Track 1: Revert sort-push-down in fallback path

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
`808fc2861b`

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Revert PRE_SORTED and remove sort in loadFromRidSet
  > In `IndexOrderedEdgeStep.java`:
  > 1. Line 167: change `VAR_INDEX_ORDERED_PRE_SORTED, Boolean.TRUE` to
  >    `Boolean.FALSE` in the `processUpstreamRow` fallback path (cost model
  >    rejects index scan → loadFromRidSet).
  > 2. In `loadFromRidSet` method (~line 222): remove the
  >    `sortByOrderProperty(records)` call. The method now streams unsorted
  >    records to OrderByStep's bounded heap.
  > 3. Remove the `sortByOrderProperty` private helper method (lines ~641-659).
  >    After Track 2 removes multi-source code, no callers remain.
  >    If multi-source callers still reference it at this point, keep the
  >    method but remove the call from loadFromRidSet only.
  > 4. Run `./mvnw -pl core clean test` to verify.

- [ ] Step 2: Update unit tests for unsorted fallback behavior
  > Search `IndexOrderedEdgeStepCostTest.java` and
  > `MatchStatementExecutionNewTest.java` for tests that assert on:
  > - sorted fallback output from loadFromRidSet
  > - PRE_SORTED=true in the fallback path
  > Update them to expect unsorted output and PRE_SORTED=false.
  > Run `./mvnw -pl core clean test` to verify all pass.
