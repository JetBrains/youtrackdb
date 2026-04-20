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
package com.jetbrains.youtrackdb.internal.core.sql.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Dead-code pin tests for the {@code core/sql/query} package.
 *
 * <p>Track 7's technical review (T2) and adversarial review (A1) identified four classes with
 * <strong>zero</strong> callers in the core module (verified with grep at plan time; cross-
 * checked by this suite's existence). They are kept here only to:
 *
 * <ul>
 *   <li>Exercise the class once so JaCoCo reports coverage for the dead surface area and the
 *       package aggregate is not dominated by never-loaded classes.</li>
 *   <li>Flag the exact branches Track 22 should delete via {@code // WHEN-FIXED:} markers.</li>
 * </ul>
 *
 * <p>The companion {@link BasicLegacyResultSetTest} covers the live slice of the package —
 * {@link BasicLegacyResultSet} + the {@link LegacyResultSet} interface.
 *
 * <p>Dead classes covered here:
 *
 * <ul>
 *   <li>{@link ConcurrentLegacyResultSet} — concurrent-populate resultset with
 *       {@code waitForCompletion} semantics. No production code instantiates it; the
 *       deprecated blocking-resultset pattern it supported no longer exists in the core SQL
 *       executor.</li>
 *   <li>{@link LiveLegacyResultSet} — extends {@link ConcurrentLegacyResultSet} on top of a
 *       {@link java.util.concurrent.LinkedBlockingQueue}. Intended for the old
 *       {@code LiveQuery} flow, which has been removed.</li>
 *   <li>{@link LiveResultListener} — interface for the old live-query callback. Only
 *       implementor is {@link LocalLiveResultListener} (also dead).</li>
 *   <li>{@link LocalLiveResultListener} — adapter bridging {@link LiveResultListener} and
 *       {@code CommandResultListener}. Its constructor is {@code protected} and has zero
 *       callers outside this test.</li>
 * </ul>
 *
 * <p>Package aggregate coverage will stay well below the 85% target until Track 22 deletes
 * these classes — accepted per Track 7 plan (scope line 1126–1128).
 *
 * <p><strong>Single-thread by design.</strong> These tests do NOT exercise the producer-
 * consumer {@code notifyNewItem}/{@code waitForNewItemOrCompleted}/volatile {@code completed}
 * contract across threads. Every blocking call ({@code size}, {@code contains},
 * {@code toArray}, {@code iterator.hasNext/next}, {@code queue.take}) is preceded by a
 * {@code setCompleted()} call or enough pre-queued items to make it non-blocking. The
 * {@link Timeout} {@code @Rule} below is a defensive net: if a future edit drops a
 * {@code setCompleted()} call, surefire will report a bounded test failure instead of
 * hanging the CI fork indefinitely (there is no {@code forkedProcessTimeoutInSeconds} on
 * the core module's surefire plugin, so accidental hangs would otherwise block the entire
 * build until the outer CI job times out).
 */
public class SqlQueryDeadCodeTest {

  @Rule
  public Timeout globalTimeout = Timeout.seconds(10);

  // ---------------------------------------------------------------------------
  // UOE helper — used by the UnsupportedOperationException surface-coverage tests below.
  // Replaces a previous monolithic try/catch ladder that hid per-branch failures.
  // ---------------------------------------------------------------------------

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void assertUoe(String label, ThrowingRunnable op) {
    try {
      op.run();
      fail(label + " must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // ok
    } catch (Exception unexpected) {
      fail(label + " threw " + unexpected.getClass().getName() + ": " + unexpected.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // ConcurrentLegacyResultSet — zero-caller evidence: grep for
  // `new ConcurrentLegacyResultSet` in core/src/main returns only the class file itself
  // ---------------------------------------------------------------------------

  @Test
  public void concurrentLegacyResultSetIsUnusedOutsideTestsAndCanBeConstructedEmpty() {
    // WHEN-FIXED: Track 22 — delete ConcurrentLegacyResultSet entirely. No remaining call sites
    // in core/src/main. Pin construction + setCompleted unblocks the waitForCompletion gate so
    // a future reader can confirm the contract before removal.
    var rs = new ConcurrentLegacyResultSet<String>();
    assertNotNull(rs);
    assertEquals("fresh concurrent resultset currentSize == 0", 0, rs.currentSize());
    rs.setCompleted();
    // After setCompleted the wait-for-completion barriers release immediately; size() and
    // contains() no longer block.
    assertEquals("size() unblocks after setCompleted", 0, rs.size());
    assertFalse("contains(x) is false on empty completed resultset", rs.contains("x"));
  }

  @Test
  public void concurrentLegacyResultSetAddAndIterateAfterCompletion() {
    // Pin the producer/consumer contract: add() notifies; setCompleted releases the waiters;
    // iterator walks the wrapped elements in insertion order.
    var rs = new ConcurrentLegacyResultSet<String>();
    assertTrue(rs.add("a"));
    assertTrue(rs.add("b"));
    rs.setCompleted();
    var it = rs.iterator();
    assertTrue(it.hasNext());
    assertEquals("a", it.next());
    assertEquals("b", it.next());
    assertFalse(it.hasNext());
  }

  @Test
  public void concurrentLegacyResultSetIteratorNextAfterExhaustionThrowsIndexOutOfBounds() {
    // WHEN-FIXED: Track 22 — ConcurrentLegacyResultSet's anonymous iterator has the same
    // strict-`>` guard bug as BasicLegacyResultSet.iterator (see
    // BasicLegacyResultSetTest#iteratorNextOnExhaustedIteratorThrowsIndexOutOfBoundsNotNoSuchElement).
    // The guard at production line 225 is `if (index > size || size == 0)` but after a single
    // next() returning the only element, `index == size == 1`, so the guard is false and the
    // method falls through to `wrapped.get(index++)` — yielding IOOBE from the underlying
    // ArrayList rather than the intended NoSuchElementException. Pin this here explicitly
    // so Track 22 knows the fix has to land in BOTH classes' iterator guards (strict > → >=).
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("only");
    rs.setCompleted();
    var it = rs.iterator();
    assertEquals("only", it.next());
    try {
      it.next();
      fail("expected IndexOutOfBoundsException from the buggy strict > guard");
    } catch (IndexOutOfBoundsException expected) {
      // ok — pinned
    } catch (java.util.NoSuchElementException unexpected) {
      fail(
          "NoSuchElementException means the CRS strict > guard was already fixed — update"
              + " this test (and BasicLegacyResultSetTest's sibling) to expect NSE instead");
    }
  }

  @Test
  public void concurrentLegacyResultSetCopyIsCompletedAndIndependent() {
    // WHEN-FIXED: Track 22 — the copy() path wraps the inner BasicLegacyResultSet.copy() in a
    // fresh ConcurrentLegacyResultSet and forces completed=true. Pin independence: mutating
    // the source after copy must not affect the copy's size().
    var rs = new ConcurrentLegacyResultSet<Integer>();
    rs.add(1);
    rs.add(2);
    var copy = rs.copy();
    rs.setCompleted();
    assertNotNull(copy);
    assertNotSame("copy must be a fresh ConcurrentLegacyResultSet instance", rs, copy);
    // Copy is already completed internally — size() must not block.
    assertEquals(2, copy.size());
    // Independence: add more to source; copy stays at 2.
    rs.add(3);
    assertEquals("copy stays independent of source after copy()", 2, copy.size());
  }

  @Test
  public void concurrentLegacyResultSetAddAllAndAddAtIndex() {
    // Cover the addAll(Collection), addAll(int, Collection), and add(int, T) branches — none
    // block; all notifyNewItem.
    var rs = new ConcurrentLegacyResultSet<String>();
    assertTrue(rs.addAll(Arrays.asList("a", "b")));
    assertTrue(rs.addAll(1, Arrays.asList("x", "y")));
    rs.add(0, "start");
    rs.setCompleted();
    assertEquals(5, rs.size());
    assertEquals("start", rs.get(0));
    assertEquals("a", rs.get(1));
    assertEquals("x", rs.get(2));
    assertEquals("y", rs.get(3));
    assertEquals("b", rs.get(4));
  }

  @Test
  public void concurrentLegacyResultSetSetReplacesElement() {
    // Pin the synchronized set(int, T) path returning the previous element.
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    var prev = rs.set(0, "A");
    rs.setCompleted();
    assertEquals("a", prev);
    assertEquals("A", rs.get(0));
  }

  // ---------------------------------------------------------------------------
  // ConcurrentLegacyResultSet — one-test-per-method UOE coverage.
  // Production throws UnsupportedOperationException with NO message for these branches, so
  // the tests pin only the exception type; the sibling liveLegacyResultSet* tests below are
  // structurally identical.
  // ---------------------------------------------------------------------------

  @Test
  public void concurrentLegacyResultSetRemoveByIndexThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    assertUoe("remove(int)", () -> rs.remove(0));
  }

  @Test
  public void concurrentLegacyResultSetRemoveByObjectThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    assertUoe("remove(Object)", () -> rs.remove((Object) "a"));
  }

  @Test
  public void concurrentLegacyResultSetRemoveAllThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    assertUoe("removeAll", () -> rs.removeAll(Arrays.asList("a")));
  }

  @Test
  public void concurrentLegacyResultSetRetainAllThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    assertUoe("retainAll", () -> rs.retainAll(Arrays.asList("a")));
  }

  @Test
  public void concurrentLegacyResultSetContainsAllThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    assertUoe("containsAll", () -> rs.containsAll(Arrays.asList("a")));
  }

  @Test
  public void concurrentLegacyResultSetIndexOfThrowsUnsupported() {
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    assertUoe("indexOf", () -> rs.indexOf("a"));
  }

  @Test
  public void concurrentLegacyResultSetLastIndexOfAlwaysReturnsZero() {
    // WHEN-FIXED: Track 22 — ConcurrentLegacyResultSet.lastIndexOf always returns 0,
    // regardless of contents or argument. This is almost certainly a stub (the sibling
    // indexOf throws UOE). Pin the stub so deletion is explicit rather than a silent
    // contract change.
    var rs = new ConcurrentLegacyResultSet<String>();
    assertEquals("lastIndexOf is a stub returning 0 for empty", 0, rs.lastIndexOf("anything"));
    rs.add("a");
    rs.add("b");
    assertEquals("lastIndexOf remains 0 even with matching elements", 0, rs.lastIndexOf("a"));
    assertEquals("lastIndexOf remains 0 for non-matching argument", 0, rs.lastIndexOf("missing"));
  }

  @Test
  public void concurrentLegacyResultSetLimitsAndListIteratorDelegate() {
    // setLimit / getLimit / listIterator / subList all delegate to the wrapped
    // BasicLegacyResultSet. Pin delegation semantics.
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.setLimit(10);
    assertEquals(10, rs.getLimit());
    rs.add("a");
    rs.add("b");
    rs.add("c");
    var lit0 = rs.listIterator();
    assertTrue("listIterator delegates to wrapped — must start with hasNext", lit0.hasNext());
    assertEquals("first element from listIterator", "a", lit0.next());
    var lit1 = rs.listIterator(1);
    assertEquals("listIterator(1) starts at index 1", "b", lit1.next());
    assertEquals("subList(1,3) spans b and c", 2, rs.subList(1, 3).size());
    assertEquals("b", rs.subList(1, 3).get(0));
    assertEquals("c", rs.subList(1, 3).get(1));
    rs.setCompleted();
  }

  @Test
  public void concurrentLegacyResultSetToStringReportsSize() {
    // Pin the toString format — "size=" + wrapped.size(). Simple sanity pin.
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    assertEquals("size=2", rs.toString());
  }

  @Test
  public void concurrentLegacyResultSetExternalizableRoundTrip() throws Exception {
    // writeExternal / readExternal delegate to the wrapped BasicLegacyResultSet under the
    // synchronization guard. Round-trip via byte array.
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("x");
    rs.add("y");
    rs.setCompleted();
    var bout = new ByteArrayOutputStream();
    try (var oos = new ObjectOutputStream(bout)) {
      rs.writeExternal(oos);
    }
    var restored = new ConcurrentLegacyResultSet<String>();
    try (var ois = new ObjectInputStream(new ByteArrayInputStream(bout.toByteArray()))) {
      restored.readExternal(ois);
    }
    // readExternal sets completed=true — size() must not block.
    assertEquals(2, restored.size());
    assertEquals("x", restored.get(0));
    assertEquals("y", restored.get(1));
  }

  @Test
  public void concurrentLegacyResultSetHashCodeAndEqualsDelegateToWrapped() {
    // Pin: equals/hashCode forward to the wrapped BasicLegacyResultSet, which in turn
    // forwards to the underlying synchronizedList. This gives List-contract semantics.
    //
    // CRITICAL: equals against another ConcurrentLegacyResultSet would deadlock. The JDK's
    // ArrayList.equals calls `other.iterator().hasNext()` after iterating; the concurrent
    // iterator's hasNext blocks on `waitForNewItemOrCompleted` when completed is false. So we
    // must setCompleted BEFORE calling equals, not after. Pin this subtle requirement here —
    // a future reader who removes setCompleted() will re-hit the hang (bounded now by the
    // class-level @Rule Timeout, which will surface a 10s failure instead of a silent hang).
    var rs1 = new ConcurrentLegacyResultSet<String>();
    rs1.add("a");
    rs1.add("b");
    rs1.setCompleted();
    var rs2 = new ConcurrentLegacyResultSet<String>();
    rs2.add("a");
    rs2.add("b");
    rs2.setCompleted();
    assertEquals("two concurrent resultsets with same elements must be equal", rs1, rs2);
    assertEquals(rs1.hashCode(), rs2.hashCode());
    // A different-content resultset must NOT be equal — sanity check.
    var rs3 = new ConcurrentLegacyResultSet<String>();
    rs3.add("a");
    rs3.add("different");
    rs3.setCompleted();
    assertFalse("different-content resultset must not be equal", rs1.equals(rs3));
  }

  @Test
  public void concurrentLegacyResultSetIsEmptyNoWaitDoesNotBlock() {
    // isEmptyNoWait must return immediately even when completed is false (that's its whole
    // purpose vs isEmpty). Pin by reading it on a non-completed resultset — a block here would
    // hang the test (the class-level Timeout rule would then convert it to a failure).
    var rs = new ConcurrentLegacyResultSet<String>();
    // NOT calling setCompleted.
    assertTrue("isEmptyNoWait must return immediately without blocking", rs.isEmptyNoWait());
    rs.add("a");
    assertFalse("isEmptyNoWait reflects contents without waiting", rs.isEmptyNoWait());
    rs.setCompleted();
  }

  // ---------------------------------------------------------------------------
  // LiveLegacyResultSet — zero-caller evidence: grep for `new LiveLegacyResultSet`
  // in core/src/main returns only the class file itself
  // ---------------------------------------------------------------------------

  @Test
  public void liveLegacyResultSetSizeThrowsUnsupported() {
    // WHEN-FIXED: Track 22 — delete LiveLegacyResultSet. Its size() throws UOE, making it an
    // intentionally-crippled queue wrapper. Pin the UOE.
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("size()", rs::size);
  }

  @Test
  public void liveLegacyResultSetIsEmptyAlwaysFalse() {
    // Pin that isEmpty() is hardcoded to return false (a stream-like resultset is never
    // considered empty until terminated). This is the override that differentiates it from
    // the parent ConcurrentLegacyResultSet.
    var rs = new LiveLegacyResultSet<String>();
    assertFalse("LiveLegacyResultSet.isEmpty is hardcoded to false", rs.isEmpty());
    rs.add("a");
    assertFalse(rs.isEmpty());
  }

  @Test
  public void liveLegacyResultSetAddEnqueuesAndNextDequeues() {
    // Live resultset uses a BlockingQueue: add() enqueues, iterator.next() dequeues (FIFO).
    // Pin the single-thread enqueue/dequeue path.
    var rs = new LiveLegacyResultSet<String>();
    assertTrue(rs.add("first"));
    assertTrue(rs.add("second"));
    var it = rs.iterator();
    assertFalse("hasNext is hardcoded to false", it.hasNext());
    // But next() still pulls from the queue regardless of hasNext.
    assertEquals("first", it.next());
    assertEquals("second", it.next());
  }

  @Test
  public void liveLegacyResultSetIteratorNextPropagatesInterrupt() throws Exception {
    // WHEN-FIXED: Track 22 — LiveLegacyResultSet.iterator().next() catches
    // InterruptedException, calls setCompleted(), re-interrupts the thread, and returns null.
    // Without this test the `catch (InterruptedException)` branch at production lines 83–87 is
    // entirely unexercised. Pin the branch on a helper thread so the main test thread keeps
    // its own interrupt state clean.
    var rs = new LiveLegacyResultSet<String>();
    var it = rs.iterator();
    var interruptReRaised = new AtomicInteger();
    var returnedNull = new AtomicInteger();
    var helper =
        new Thread(
            () -> {
              // Pre-interrupt so queue.take() throws InterruptedException immediately on the
              // first next() call.
              Thread.currentThread().interrupt();
              var result = it.next();
              if (result == null) {
                returnedNull.incrementAndGet();
              }
              if (Thread.currentThread().isInterrupted()) {
                interruptReRaised.incrementAndGet();
              }
            });
    helper.start();
    helper.join(5000);
    assertFalse("helper thread must complete without hanging", helper.isAlive());
    assertEquals("interrupt branch returned null exactly once", 1, returnedNull.get());
    assertEquals("interrupt branch re-raised the interrupt flag", 1, interruptReRaised.get());
  }

  @Test
  public void liveLegacyResultSetAddAllEnqueuesEach() {
    // addAll variants both iterate and enqueue each element. Pin that N adds yield N items.
    var rs = new LiveLegacyResultSet<String>();
    assertTrue(rs.addAll(Arrays.asList("a", "b", "c")));
    assertTrue(rs.addAll(0, Arrays.asList("x")));
    var it = rs.iterator();
    // Order is FIFO per queue semantics — first addAll enters first.
    assertEquals("a", it.next());
    assertEquals("b", it.next());
    assertEquals("c", it.next());
    assertEquals("x", it.next());
  }

  @Test
  public void liveLegacyResultSetAddByIndexIgnoresIndexAndAppends() {
    // add(int, T) ignores the index and just calls add(T). Pin this behaviour so a future
    // fix (use the index) is an explicit contract change.
    var rs = new LiveLegacyResultSet<String>();
    rs.add(0, "a");
    rs.add(999, "b");
    var it = rs.iterator();
    assertEquals("index ignored — FIFO preserved", "a", it.next());
    assertEquals("b", it.next());
  }

  @Test
  public void liveLegacyResultSetSetCompletedDoesNotFlipCompletedFlagButCompleteDoes()
      throws Exception {
    // WHEN-FIXED: Track 22 — the override setCompleted() on LiveLegacyResultSet has its
    // `completed = true;` line commented out. The public complete() method is the one that
    // actually sets the flag. The `completed` field is declared protected volatile on the
    // parent, and LiveLegacyResultSet is in the same package as this test — BUT `completed`
    // is a field of ConcurrentLegacyResultSet, so direct access works only from that
    // package. We use reflection to read the parent's field for a falsifiable pin.
    var field = ConcurrentLegacyResultSet.class.getDeclaredField("completed");
    field.setAccessible(true);
    var rs = new LiveLegacyResultSet<String>();
    assertFalse("fresh resultset — completed is false", field.getBoolean(rs));
    rs.setCompleted();
    assertFalse(
        "setCompleted override has its assignment commented out — completed stays false."
            + " Track 22 fix (uncomment the assignment) must flip this assertion to true.",
        field.getBoolean(rs));
    rs.complete();
    assertTrue("complete() actually sets completed=true", field.getBoolean(rs));
    // Idempotent: a second complete() must not throw and must leave the flag true.
    rs.complete();
    assertTrue("complete() is idempotent", field.getBoolean(rs));
  }

  @Test
  public void liveLegacyResultSetCopyProducesFreshEmptyInstance() throws Exception {
    // LiveLegacyResultSet.copy returns a fresh instance with an empty queue AND an empty
    // wrapped BasicLegacyResultSet inherited from the parent. Pin independence by reading
    // the inherited wrapped.currentSize() — the overriding public size() throws UOE, so we
    // use the non-blocking BasicLegacyResultSet accessor directly.
    var rs = new LiveLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var copy = rs.copy();
    assertNotNull(copy);
    assertNotSame("copy must be a fresh LiveLegacyResultSet instance", rs, copy);
    var wrappedField = ConcurrentLegacyResultSet.class.getDeclaredField("wrapped");
    wrappedField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var copyWrapped = (BasicLegacyResultSet<String>) wrappedField.get(copy);
    assertEquals(
        "copy's inherited wrapped resultset must start empty — no carry-over from source",
        0,
        copyWrapped.currentSize());
    // size() on the copy still throws UOE (the override is class-wide, not per-instance).
    assertUoe("size() on copy", copy::size);
  }

  // ---------------------------------------------------------------------------
  // LiveLegacyResultSet — one-test-per-method UOE coverage for the many
  // UnsupportedOperationException branches. Splitting them (vs. the previous single
  // "unsupportedMethods" ladder) lets surefire pinpoint the exact regression if Track 22
  // accidentally lifts a UOE while refactoring.
  // ---------------------------------------------------------------------------

  @Test
  public void liveLegacyResultSetSetThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("set(int, T)", () -> rs.set(0, "a"));
  }

  @Test
  public void liveLegacyResultSetContainsThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("contains", () -> rs.contains("a"));
  }

  @Test
  public void liveLegacyResultSetToArrayThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("toArray()", rs::toArray);
  }

  @Test
  public void liveLegacyResultSetToArrayTypedThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("toArray(T[])", () -> rs.toArray(new String[0]));
  }

  @Test
  public void liveLegacyResultSetClearThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("clear", rs::clear);
  }

  @Test
  public void liveLegacyResultSetGetThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("get(int)", () -> rs.get(0));
  }

  @Test
  public void liveLegacyResultSetIndexOfThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("indexOf", () -> rs.indexOf("a"));
  }

  @Test
  public void liveLegacyResultSetLastIndexOfThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("lastIndexOf", () -> rs.lastIndexOf("a"));
  }

  @Test
  public void liveLegacyResultSetListIteratorThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("listIterator()", rs::listIterator);
  }

  @Test
  public void liveLegacyResultSetListIteratorWithIndexThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("listIterator(int)", () -> rs.listIterator(0));
  }

  @Test
  public void liveLegacyResultSetSubListThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("subList", () -> rs.subList(0, 0));
  }

  @Test
  public void liveLegacyResultSetRemoveByIndexThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("remove(int)", () -> rs.remove(0));
  }

  @Test
  public void liveLegacyResultSetRemoveByObjectThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("remove(Object)", () -> rs.remove((Object) "a"));
  }

  @Test
  public void liveLegacyResultSetRemoveAllThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("removeAll", () -> rs.removeAll(Arrays.asList("a")));
  }

  @Test
  public void liveLegacyResultSetRetainAllThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("retainAll", () -> rs.retainAll(Arrays.asList("a")));
  }

  @Test
  public void liveLegacyResultSetContainsAllThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("containsAll", () -> rs.containsAll(Arrays.asList("a")));
  }

  @Test
  public void liveLegacyResultSetWriteExternalThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("writeExternal", () -> rs.writeExternal(null));
  }

  @Test
  public void liveLegacyResultSetReadExternalThrowsUnsupported() {
    var rs = new LiveLegacyResultSet<String>();
    assertUoe("readExternal", () -> rs.readExternal(null));
  }

  @Test
  public void liveLegacyResultSetSetLimitReturnsNull() {
    // WHEN-FIXED: Track 22 — LiveLegacyResultSet.setLimit returns null (annotated @Nullable).
    // This breaks the LegacyResultSet fluent contract (every other impl returns `this`). Pin
    // the divergence so removal is explicit.
    var rs = new LiveLegacyResultSet<String>();
    var returned = rs.setLimit(5);
    assertNull(
        "LiveLegacyResultSet.setLimit deviates from fluent contract (returns null)", returned);
    assertEquals(
        "the limit still propagates to the wrapped BasicLegacyResultSet", 5, rs.getLimit());
  }

  @Test
  public void liveLegacyResultSetIteratorRemoveThrows() {
    // The iterator's remove() throws UOE per the legacy resultset convention.
    var rs = new LiveLegacyResultSet<String>();
    var it = rs.iterator();
    try {
      it.remove();
      fail("iterator.remove must be unsupported");
    } catch (UnsupportedOperationException expected) {
      assertTrue(
          "iterator.remove message pins the full LegacyResultSet.iterator.remove() identifier,"
              + " got: "
              + expected.getMessage(),
          expected.getMessage().contains("LegacyResultSet.iterator.remove()"));
    }
  }

  // ---------------------------------------------------------------------------
  // LiveResultListener — zero-caller evidence: grep for LiveResultListener in
  // core/src/main returns only the interface file + LocalLiveResultListener (also dead)
  // ---------------------------------------------------------------------------

  @Test
  public void liveResultListenerInterfaceCanBeImplementedWithAllThreeCallbacks() {
    // WHEN-FIXED: Track 22 — delete LiveResultListener interface + LocalLiveResultListener.
    // No production implementor remains; the old LiveQuery flow that consumed this listener
    // was removed earlier in the codebase's evolution. Pin that the interface still has the
    // expected three methods by implementing it and exercising all callbacks.
    var liveCount = new AtomicInteger();
    var errCount = new AtomicInteger();
    var unsubCount = new AtomicInteger();
    LiveResultListener l =
        new LiveResultListener() {
          @Override
          public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken, RecordOperation iOp)
              throws BaseException {
            liveCount.incrementAndGet();
          }

          @Override
          public void onError(int iLiveToken) {
            errCount.incrementAndGet();
          }

          @Override
          public void onUnsubscribe(int iLiveToken) {
            unsubCount.incrementAndGet();
          }
        };
    l.onLiveResult(null, 42, null);
    l.onError(42);
    l.onUnsubscribe(42);
    assertEquals("onLiveResult called once", 1, liveCount.get());
    assertEquals("onError called once", 1, errCount.get());
    assertEquals("onUnsubscribe called once", 1, unsubCount.get());
  }

  // ---------------------------------------------------------------------------
  // LocalLiveResultListener — zero-caller evidence: grep for `new LocalLiveResultListener`
  // in core/src/main returns only the class file itself. Constructor is protected, so this
  // test lives in the same package to invoke it.
  // ---------------------------------------------------------------------------

  @Test
  public void localLiveResultListenerForwardsListenerCallbacksToUnderlying() {
    // WHEN-FIXED: Track 22 — delete LocalLiveResultListener. Pin that its three listener
    // callbacks forward to the underlying LiveResultListener.
    var delegateLive = new AtomicInteger();
    var delegateErr = new AtomicInteger();
    var delegateUnsub = new AtomicInteger();
    LiveResultListener delegate =
        new LiveResultListener() {
          @Override
          public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken, RecordOperation iOp)
              throws BaseException {
            delegateLive.incrementAndGet();
          }

          @Override
          public void onError(int iLiveToken) {
            delegateErr.incrementAndGet();
          }

          @Override
          public void onUnsubscribe(int iLiveToken) {
            delegateUnsub.incrementAndGet();
          }
        };
    var adapter = new LocalLiveResultListener(delegate);
    adapter.onLiveResult(null, 7, null);
    adapter.onError(7);
    adapter.onUnsubscribe(7);
    assertEquals("onLiveResult forwards to the underlying listener", 1, delegateLive.get());
    assertEquals("onError forwards to the underlying listener", 1, delegateErr.get());
    assertEquals("onUnsubscribe forwards to the underlying listener", 1, delegateUnsub.get());
  }

  @Test
  public void localLiveResultListenerCommandResultListenerStubsReturnEmpty() {
    // WHEN-FIXED: Track 22 — the CommandResultListener half of LocalLiveResultListener is
    // stubbed: result() returns false, end() is a no-op, getResult() returns null. Pin all
    // three so deletion is an explicit contract change. The delegate counts every
    // LiveResultListener callback — result() and end() must NOT forward to any of them.
    var delegateLive = new AtomicInteger();
    var delegateErr = new AtomicInteger();
    var delegateUnsub = new AtomicInteger();
    LiveResultListener delegate =
        new LiveResultListener() {
          @Override
          public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken,
              RecordOperation iOp) {
            delegateLive.incrementAndGet();
          }

          @Override
          public void onError(int iLiveToken) {
            delegateErr.incrementAndGet();
          }

          @Override
          public void onUnsubscribe(int iLiveToken) {
            delegateUnsub.incrementAndGet();
          }
        };
    var adapter = new LocalLiveResultListener(delegate);
    // CommandResultListener.result(session, record) is hardcoded to return false. Passing
    // null for the @Nonnull session is intentional — the stub ignores the argument.
    assertFalse("result() is a hardcoded stub returning false", adapter.result(null, new Object()));
    // end(session) is a no-op — simply proving it does not throw pins the contract.
    adapter.end(null);
    // getResult() is hardcoded to return null.
    assertNull("getResult() is a hardcoded stub returning null", adapter.getResult());
    // Crucially — none of the three CommandResultListener methods are allowed to forward to
    // the LiveResultListener delegate. Verify the delegate's callback counters stayed at 0.
    assertEquals(
        "result() must NOT forward to LiveResultListener.onLiveResult", 0, delegateLive.get());
    assertEquals("end() must NOT forward to LiveResultListener.onError", 0, delegateErr.get());
    assertEquals(
        "result()/end()/getResult() must NOT forward to onUnsubscribe", 0, delegateUnsub.get());
  }

  @Test
  public void localLiveResultListenerDelegateIdentityIsPreservedThroughAllForwardingPaths() {
    // Pin that ALL forwarding calls use the same delegate identity — a defensive check against
    // a regression where one of the three overrides silently swaps the target.
    var seen = new AtomicInteger();
    final LiveResultListener sentinel =
        new LiveResultListener() {
          @Override
          public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken,
              RecordOperation iOp) {
            // Token acts as a breadcrumb — if a future refactor drops the token during
            // forwarding, this assertion catches it.
            assertEquals(
                "delegate must receive the exact token passed to the adapter", 123, iLiveToken);
            seen.incrementAndGet();
          }

          @Override
          public void onError(int iLiveToken) {
            assertEquals("delegate must receive the exact error token", 456, iLiveToken);
            seen.incrementAndGet();
          }

          @Override
          public void onUnsubscribe(int iLiveToken) {
            assertEquals("delegate must receive the exact unsub token", 789, iLiveToken);
            seen.incrementAndGet();
          }
        };
    var adapter = new LocalLiveResultListener(sentinel);
    // Pin the declared interface list via assertTrue(instanceof) rather than a tautological
    // self-cast assertSame.
    assertTrue(
        "adapter must implement LiveResultListener", adapter instanceof LiveResultListener);
    adapter.onLiveResult(null, 123, null);
    adapter.onError(456);
    adapter.onUnsubscribe(789);
    assertEquals("all three callbacks landed on the same delegate", 3, seen.get());
    // Identity pin: since LocalLiveResultListener stores its delegate in a final field, the
    // same adapter must keep forwarding to the same delegate across many invocations.
    adapter.onLiveResult(null, 123, null);
    assertEquals("repeat forwarding observes same delegate", 4, seen.get());
  }
}
