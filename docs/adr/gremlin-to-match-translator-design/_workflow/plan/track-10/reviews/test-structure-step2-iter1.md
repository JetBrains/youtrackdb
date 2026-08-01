<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 1, suggestion: 4}
index:
  - {id: TS1, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java:1423, anchor: "### TS1 ", cert: n/a, basis: "drain helper catches NoSuchElementException, so a mid-pass iteration failure reads as clean exhaustion in three tests that discard the drained list"}
  - {id: TS2, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java:1420, anchor: "### TS2 ", cert: n/a, basis: "unbounded while(true) drive loop hangs the surefire fork instead of failing when a step stops signalling exhaustion"}
  - {id: TS3, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java:1020, anchor: "### TS3 ", cert: n/a, basis: "drainPayloads duplicated in both boundary test classes in the same commit that introduced a shared package-private fixture for exactly that overlap"}
  - {id: TS4, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java:1035, anchor: "### TS4 ", cert: n/a, basis: "rowYieldingCopy installs a Mockito stub as a side effect while its two same-section siblings do not, so the name family no longer predicts the contract"}
  - {id: TS5, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java:828, anchor: "### TS5 ", cert: n/a, basis: "one of the five new close-then-reset tests ends mid-pass without close(), breaking the block's own explicit-close convention"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] `drainPayloads` reports a mid-pass iteration failure as clean exhaustion

Both new copies of `drainPayloads` end the drive loop on `catch (NoSuchElementException exhausted)`
(`YTDBMatchPlanStepTest.java:1423`, `MultiPlanMatchStepTest.java:1025`). The step signals exhaustion
with `FastNoSuchElementException`, a subtype, but the catch also swallows every plain
`NoSuchElementException` that escapes `processNextStart()` — including one raised on the step's
terminal iteration-failure branch, which closes the stream and the plan and rethrows
(`AbstractMatchPlanStep.processNextStart`, the `catch (RuntimeException | Error e)` arm at
`AbstractMatchPlanStep.java:297`).

That exception type is not hypothetical here. `stubPlanCopyDelivering` backs the copy's stream with
`pending.removeFirst()` on an `ArrayDeque`, which throws `NoSuchElementException` when the deque is
empty, and the real-plan fixture reaches `ExecutionStream.resultIterator(rows.iterator())`, whose
iterator does the same.

Three of the five new tests discard the drained list entirely and assert only on Mockito
interactions, so the swallow is not caught downstream either.

**Failure scenario.** A regression makes the re-armed pass pull `next()` without a preceding
`hasNext()` probe. `pending.removeFirst()` throws `NoSuchElementException`; the step's terminal arm
closes the copy and rethrows; `drainPayloads` returns the payloads collected so far. In
`closeThenReset_startsAFreshCopyAndNeverRestartsTheClosedPlan` the return value is discarded and
`verify(plan, times(1)).copy(ctx)` / `verify(copiedPlan, times(1)).start()` still hold, so the test
is green. In `closeAfterAReArmedPass_closesThePlanCopyThatRan` the failure path itself closes the
copy and moves the step to `CLOSED`, so the trailing `step.close()` is a no-op and
`verify(copiedPlan, times(1)).close()` also holds — the test passes on a pass that blew up.

**Suggestion.** Narrow the catch to the exhaustion signal the step actually throws:

```java
} catch (FastNoSuchElementException exhausted) {
  return payloads;
}
```

`FastNoSuchElementException` (`org.apache.tinkerpop.gremlin.process.traversal.util`) extends
`NoSuchElementException`, and the step raises no other exhaustion type, so the narrowing is safe and
turns a swallowed failure into a propagated one.

### TS2 [suggestion] The drive loop is unbounded, so a step that stops signalling exhaustion hangs instead of failing

`drainPayloads` is a bare `while (true)` whose only exit is the exhaustion catch
(`YTDBMatchPlanStepTest.java:1420`, `MultiPlanMatchStepTest.java:1022`). It sits in the "Test
helpers" section of both classes and reads as a general-purpose drive helper, but it is only safe
against a stream that eventually reports `hasNext == false`.

**Failure scenario.** A future test — or a rewrite of an existing one — pairs the helper with the
never-exhausting stub both classes already use (`when(stream.hasNext(ctx)).thenReturn(true)` in
`close_earlyTermination_closesStreamThenPlan`, `close_isIdempotent`,
`clone_whileOriginalStreamOpen_doesNotCloseOriginalStream`). The loop never terminates, the surefire
fork hangs until the CI job's wall-clock timeout, and the run reports a timeout rather than a named
failing test.

**Suggestion.** Cap the pulls and fail loudly past the cap, e.g. drive at most a few hundred
iterations and `fail("step never signalled exhaustion after N pulls")`. The cap costs nothing on a
passing run and converts a hung job into a legible failure.

### TS3 [suggestion] `drainPayloads` is duplicated across both boundary test classes

This commit introduces `ReplayablePlanFixture` as a package-private support class precisely because
`YTDBMatchPlanStepTest` and `MultiPlanMatchStepTest` need the same scaffolding, then adds
`drainPayloads` twice instead — `YTDBMatchPlanStepTest.java:1418` and
`MultiPlanMatchStepTest.java:1020`. The two bodies are identical apart from the step type, and both
only need `processNextStart().get()`, which is package-visible on the shared base, so a single
`static List<Object> drainPayloads(AbstractMatchPlanStep<Object, ? extends Element> step)` would
serve both.

**Failure scenario.** TS1 and TS2 both require the same edit in two files. The class pair already
shows where that leads: the pre-existing raw-entity reader exists as `assertRawEntityOf`
(`YTDBMatchPlanStepTest.java:1442`) and `rawEntityOf` (`MultiPlanMatchStepTest.java:1178`) — one
helper, two names, two bodies, two Javadocs to keep in step. A fix applied to one copy of
`drainPayloads` and not the other leaves half the new close-then-reset block on the weaker helper,
with nothing to flag the divergence.

**Suggestion.** Move `drainPayloads` next to `ReplayablePlanFixture` (or into a small shared
`BoundaryStepTestSupport`) and have both classes call it.

### TS4 [suggestion] `rowYieldingCopy` installs a stub as a side effect while its same-section siblings do not

`rowYieldingCopy(Child, Result...)` (`MultiPlanMatchStepTest.java:1035`) ends with
`when(child.plan.copy(any())).thenReturn(copy)`. Its two neighbours in the same helper block —
`emptyCopy(CommandContext)` at `:1045` and `identityYieldingCopy(...)` at `:1057` — build a copy mock
and leave the `copy(...)` stubbing to the caller. Three helpers with parallel names now carry two
different contracts, and the only signal is the phrase "on this child" in the new Javadoc. The
sibling class names the equivalent helper `stubPlanCopyDelivering`
(`YTDBMatchPlanStepTest.java:1402`), where the `stub` prefix does announce the side effect.

**Failure scenario.** A test author adds a union case, copies the `emptyCopy` call shape it read
first, and reaches for `rowYieldingCopy` expecting a plain factory. Either the child's `copy(...)`
never gets stubbed for the case being written (Mockito returns `null`, and the re-arm assertion in
`MultiPlanMatchStep.replaceClosedPlanWithCopy` fires under `-ea` with a message about the production
contract rather than about the test wiring), or a second `when(child.plan.copy(any()))` silently
overwrites the one the helper installed, so the test verifies against a copy that never ran.

**Suggestion.** Rename to `stubCopyYielding(child, rows)`, matching the `stub…` convention the
sibling class already uses, or drop the stub install and let the caller wire it like the other two
helpers.

### TS5 [suggestion] One new test ends mid-pass without `close()`, against the block's own convention

`closeThenReset_reIterationYieldsTheSameRows` (`YTDBMatchPlanStepTest.java:814`) drains the re-armed
pass at `:828` and returns. Every other test in the new close-then-reset block closes the step at the
end, and the class Javadoc states the rule: these tests drive `processNextStart()` directly, so they
call `close()` explicitly. The test therefore finishes with the step holding a started plan copy
nobody released.

**Failure scenario.** A later author extends the test with a third pass (`reset()` + drain), starting
from a step in `DRAINED` rather than `CLOSED`. That exercises the `REARMED` rewind path while the
method name and Javadoc still promise the close-then-reset path, so the test silently stops covering
the case it is named for.

**Suggestion.** Add `step.close();` after the second drain, matching the three sibling tests.

## Evidence base
