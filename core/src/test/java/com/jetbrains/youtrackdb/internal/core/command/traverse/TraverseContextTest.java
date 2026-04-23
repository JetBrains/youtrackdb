/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.command.traverse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link TraverseContext}. The context holds the traverse's memory (a stack or
 * queue of pending processes) and the history of already-visited records. These tests exercise
 * {@code push/pop} mechanics, the two branches of {@code pop} (known vs. unknown RID), memory
 * strategy switching, and the {@code getVariable} path that surfaces traversal state to callers.
 *
 * <p>A {@link DbTestBase} session is required because {@link TraverseAbstractProcess} is
 * instantiated with a real {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded}
 * and because {@code getVariable}'s PATH / STACK / HISTORY branches reach
 * {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#getFieldValue} which
 * inspects the session's active transaction.
 *
 * <p>Track 9 Step 3 coverage — direct {@link TraverseContext} methods not reachable via the
 * end-to-end traversal path without brittle ordering assumptions.
 */
public class TraverseContextTest extends DbTestBase {

  /**
   * Safety net matching Track 8's {@code TestUtilsFixture.rollbackIfLeftOpen} — tests that throw
   * mid-transaction otherwise cascade into an awkward {@code afterTest()} close-path error.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  private Traverse newTraverse() {
    return new Traverse(session);
  }

  /**
   * Seed {@code traverse} with a single empty-iterator {@link TraverseRecordSetProcess} (which
   * auto-pushes itself in its constructor). Most context tests need exactly one pop-able frame,
   * and the RSP is the simplest way to produce one. Returns the {@link TraverseContext} for
   * fluent access.
   */
  private TraverseContext seedEmptyRsp(Traverse traverse) {
    new TraverseRecordSetProcess(traverse,
        Collections.<Identifiable>emptyList().iterator(),
        TraversePath.empty(), session);
    return traverse.getContext();
  }

  private int stackSize(TraverseContext ctx) {
    return ((Collection<?>) ctx.getVariables().get("stack")).size();
  }

  /**
   * A fresh context with no pushed process has empty memory, zero depth, an empty path, and
   * {@code isEmpty() == true}. {@code next()} on empty memory returns null (no NoSuchElement).
   */
  @Test
  public void freshContextStartsEmptyWithZeroDepthAndEmptyPath() {
    var ctx = new TraverseContext();

    assertTrue("fresh memory is empty", ctx.isEmpty());
    assertEquals("depth defaults to 0 when no process is current", 0, ctx.getDepth());
    assertEquals("path defaults to empty string when no process is current",
        "", ctx.getPath());
  }

  /**
   * {@code push(process)} places the process on the stack; {@code next()} (peek semantics)
   * returns the same reference and sets it as the current process so {@code getPath} / {@code
   * getDepth} reflect it.
   */
  @Test
  public void pushThenNextExposesProcessAsCurrentWithItsPathAndDepth() {
    var traverse = newTraverse();
    var root = new RecordId(12, 100L);
    // A RecordSetProcess auto-pushes itself in its constructor, then we push a child RecordProcess.
    new TraverseRecordSetProcess(traverse,
        Collections.<Identifiable>singletonList(root).iterator(),
        TraversePath.empty(), session);
    var ctx = traverse.getContext();

    // Peek the top — should be the recordSetProcess (depth is -1 from FirstPathItem via
    // appendRecordSet) → reports getDepth as -1 since the RSP's path is shared with empty().
    var top = ctx.next();
    assertNotNull("next() must return the pushed process", top);
    assertSame("next() returns the peeked reference (not a copy)", top, ctx.next());
    assertFalse("memory is not empty after push", ctx.isEmpty());
    assertEquals("getPath follows the current process's path",
        top.getPath().toString(), ctx.getPath());
    assertEquals("getDepth follows the current process's path depth",
        top.getPath().getDepth(), ctx.getDepth());
  }

  /**
   * {@code pop(null)} on an empty deque wraps the internal {@link java.util.NoSuchElementException}
   * into an {@link IllegalStateException} with message {@code "Traverse stack is empty"}. This pins
   * T9 — the observable shape of the empty-deque guard in
   * {@link TraverseContext#pop(Identifiable)}.
   */
  @Test
  public void popOnEmptyMemoryThrowsIllegalStateWithStableMessage() {
    var ctx = new TraverseContext();

    var ex = assertThrows("pop on empty memory must throw IllegalStateException",
        IllegalStateException.class, () -> ctx.pop(null));

    assertEquals("the exception message pins the observable behavior",
        "Traverse stack is empty", ex.getMessage());
    assertNotNull("cause must be preserved, not swallowed", ex.getCause());
    assertEquals(
        "cause class is exactly NoSuchElementException (not a subclass) for debugging clarity",
        NoSuchElementException.class, ex.getCause().getClass());
  }

  /**
   * {@code pop(record)} where the record's RID is NOT in {@code history} must still drop the top
   * frame (observable: memory shrinks by one). The missing-from-history branch emits a
   * {@code LogManager.warn} whose content this test does NOT verify — capturing the log
   * appender is a Track 22 follow-up (WHEN-FIXED: Track 22 — add LogManager appender capture to
   * pin the exact warn message). Today the assertion is that pop proceeds non-destructively.
   */
  @Test
  public void popRemovesTopFrameEvenWhenRidMissingFromHistory() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);

    assertFalse("precondition: memory has one frame from RecordSetProcess", ctx.isEmpty());

    // The record is not in history — pop must still dequeue the frame silently.
    ctx.pop(new RecordId(99, 999L));

    assertTrue("memory is empty after popping the only frame", ctx.isEmpty());
  }

  /**
   * {@code pop(record)} with a RID that was previously {@code addTraversed} must remove both the
   * history entry (verified via {@link TraverseContext#isAlreadyTraversed}) and one memory frame.
   */
  @Test
  public void popRemovesHistoryEntryAndTopFrameWhenRidMatches() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);
    var rid = new RecordId(0, 42L);
    ctx.addTraversed(rid, 0);
    assertTrue("precondition: RID is in history after addTraversed",
        ctx.isAlreadyTraversed(rid, 0));

    ctx.pop(rid);

    assertFalse("history no longer contains the popped RID", ctx.isAlreadyTraversed(rid, 0));
    assertTrue("memory is empty after popping the only frame", ctx.isEmpty());
  }

  /**
   * {@code isAlreadyTraversed} is a pure lookup into {@code history} — false before
   * {@code addTraversed}, true after, regardless of the {@code iLevel} argument. The commented-out
   * per-level tracking is dead code; this pin locks in the current RID-only semantics.
   */
  @Test
  public void isAlreadyTraversedIsPureRidLookupIgnoringLevelArgument() {
    var ctx = new TraverseContext();
    var rid = new RecordId(2, 17L);

    assertFalse("new RID is not in history", ctx.isAlreadyTraversed(rid, 0));

    ctx.addTraversed(rid, 5);

    assertTrue("added RID is in history regardless of level asked for (same level)",
        ctx.isAlreadyTraversed(rid, 5));
    assertTrue("added RID is in history regardless of level asked for (different level)",
        ctx.isAlreadyTraversed(rid, 99));
  }

  /**
   * {@code reset()} clears memory — used by {@link Traverse#target} to re-seed the traversal. It
   * does NOT clear history; that invariant matters so a re-target on the same Traverse does not
   * re-visit already-seen RIDs.
   */
  @Test
  public void resetClearsMemoryButNotHistory() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);
    var rid = new RecordId(1, 1L);
    ctx.addTraversed(rid, 0);
    assertFalse("precondition: memory has a frame", ctx.isEmpty());

    ctx.reset();

    assertTrue("memory is empty after reset", ctx.isEmpty());
    assertTrue("history survives reset — re-target must not re-visit seen RIDs",
        ctx.isAlreadyTraversed(rid, 0));
  }

  /**
   * {@code setStrategy(BREADTH_FIRST)} swaps the internal memory to a {@code QueueMemory} while
   * copying pending frames forward. Pin exact stack size (not just {@code !isEmpty()}) so a
   * regression that accidentally drops frames during the swap is caught.
   */
  @Test
  public void setStrategyPreservesPendingProcessesAcrossStrategySwitches() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);
    int initial = stackSize(ctx);
    assertEquals("precondition: exactly one RSP frame present", 1, initial);

    ctx.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);
    assertEquals("BFS switch preserves exact frame count", initial, stackSize(ctx));

    ctx.setStrategy(Traverse.STRATEGY.DEPTH_FIRST);
    assertEquals("DFS switch preserves exact frame count", initial, stackSize(ctx));
  }

  /**
   * {@code getVariables()} returns a map containing {@code depth}, {@code path}, and the
   * underlying {@code stack} collection, merged with the parent {@link
   * com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext} variables. The traverse
   * keys must be present even when no process is pushed.
   */
  @Test
  public void getVariablesContainsDepthPathStackKeysOnEmptyContext() {
    var ctx = new TraverseContext();

    Map<String, Object> vars = ctx.getVariables();

    assertTrue("getVariables must surface 'depth' even for an empty context",
        vars.containsKey("depth"));
    assertTrue("getVariables must surface 'path' even for an empty context",
        vars.containsKey("path"));
    assertTrue("getVariables must surface 'stack' even for an empty context",
        vars.containsKey("stack"));
    assertEquals("depth value is 0 with no current process", 0, vars.get("depth"));
    assertEquals("path value is empty with no current process", "", vars.get("path"));
    assertTrue("stack value is a Deque — downstream code relies on head/tail semantics",
        vars.get("stack") instanceof Deque);
  }

  /**
   * {@code getVariable("depth")} short-circuits (prefix match on {@code "DEPTH"}) and returns the
   * current depth as an Integer. Case and trimming are normalized.
   */
  @Test
  public void getVariableDepthIsCaseInsensitiveAndTrimmed() {
    var ctx = new TraverseContext();

    assertEquals("DEPTH returns 0 on empty context", 0, ctx.getVariable("DEPTH"));
    assertEquals("lowercase 'depth' is normalized to DEPTH", 0, ctx.getVariable("depth"));
    assertEquals("whitespace-padded name is trimmed",
        0, ctx.getVariable("  depth  "));
  }

  /**
   * {@code getVariable("stack")} returns a clone of the underlying {@link ArrayDeque} so callers
   * cannot mutate the context's internal memory. This pin protects the invariant that retrieved
   * variable values are snapshots.
   */
  @Test
  public void getVariableStackReturnsCloneNotTheInternalDeque() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);

    var returned = ctx.getVariable("STACK");

    // The contract is "returns a snapshot-like collection" — an ArrayDeque today via
    // ((ArrayDeque) result).clone(). We accept any Collection to stay robust against a refactor
    // that changes the snapshot type but preserves the read-only semantics.
    assertTrue("STACK returns a Collection snapshot",
        returned instanceof Collection);
    var snapshot = (Collection<?>) returned;
    var countBefore = snapshot.size();
    snapshot.clear();

    assertFalse("clearing the returned snapshot must not empty the real context memory",
        ctx.isEmpty());
    assertEquals("snapshot had exactly one frame (the seeded RSP) before clearing",
        1, countBefore);
  }

  /**
   * {@code getVariable} with an unrecognized key delegates to the parent {@link
   * com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext#getVariable}, which returns
   * null for unknown variables and not, say, an empty string or sentinel.
   */
  @Test
  public void getVariableUnknownKeyDelegatesToSuperAndReturnsNull() {
    var ctx = new TraverseContext();

    assertNull("unknown variable delegates to BasicCommandContext and returns null",
        ctx.getVariable("someUnknownKey"));
  }

  /**
   * Integration check: after a full run of {@link Traverse#execute} on a DB-backed entity, the
   * context's history must include that entity's RID and {@code isAlreadyTraversed} must report
   * it as visited. This glues {@code addTraversed} → history set into the observable surface.
   */
  @Test
  public void addTraversedIsObservableViaIsAlreadyTraversedAfterExecute() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    var reloaded = session.getActiveTransaction().load(entity);
    var traverse = newTraverse();
    traverse.target(reloaded).fields("*");
    var results = traverse.execute(session);
    session.rollback();

    assertFalse("traversal must produce at least the root",
        results.isEmpty());
    assertTrue("every result's RID must be observable via isAlreadyTraversed",
        traverse.getContext().isAlreadyTraversed(reloaded.getIdentity(), 0));
  }

  /**
   * {@code getUnderlying} on the memory is used by {@code getVariables}'s {@code stack} entry. This
   * test pins that an RSP auto-pushed in its constructor is observable in the stack collection.
   */
  @Test
  public void getVariablesStackReflectsProcessesPushedByProcessConstructors() {
    var traverse = newTraverse();
    var ctx = seedEmptyRsp(traverse);

    assertEquals("exactly one process is on the stack (the RSP)",
        1, stackSize(ctx));
  }

  /**
   * {@code setStrategy} twice in a row (BFS then BFS again, or DFS then DFS) must still preserve
   * frames. The constructors read {@code memory.getUnderlying()} and copy the deque; this pin
   * guards that the copy path is idempotent.
   */
  @Test
  public void consecutiveSetStrategyCallsArePreservingAndIdempotent() {
    var traverse = newTraverse();
    new TraverseRecordSetProcess(traverse,
        Arrays.<Identifiable>asList(new RecordId(3, 1L), new RecordId(3, 2L)).iterator(),
        TraversePath.empty(), session);
    var ctx = traverse.getContext();
    var initialSize = stackSize(ctx);

    ctx.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);
    ctx.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);
    ctx.setStrategy(Traverse.STRATEGY.DEPTH_FIRST);

    assertEquals("stack size is preserved across repeated strategy switches",
        initialSize, stackSize(ctx));
  }
}
