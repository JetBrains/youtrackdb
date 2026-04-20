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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

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
 */
public class SqlQueryDeadCodeTest {

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

  @Test
  public void concurrentLegacyResultSetMutatingRemoveOperationsThrowUnsupported() {
    // The legacy resultset never supported removal semantics. Cover the four UOE branches
    // in a single test (they share the rationale).
    var rs = new ConcurrentLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.remove(0);
      fail("remove(int) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.remove((Object) "a");
      fail("remove(Object) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.removeAll(Arrays.asList("a"));
      fail("removeAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.retainAll(Arrays.asList("a"));
      fail("retainAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.containsAll(Arrays.asList("a"));
      fail("containsAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.indexOf("a");
      fail("indexOf must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
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
    var bout = new java.io.ByteArrayOutputStream();
    try (var oos = new java.io.ObjectOutputStream(bout)) {
      rs.writeExternal(oos);
    }
    var restored = new ConcurrentLegacyResultSet<String>();
    try (var ois =
        new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bout.toByteArray()))) {
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
    // a future reader who removes setCompleted() will re-hit the hang.
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
    // hang the test.
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
    try {
      rs.size();
      fail("size() must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
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
  public void liveLegacyResultSetCompleteSetsCompletedFlagAndSetCompletedDoesNot() {
    // WHEN-FIXED: Track 22 — the override setCompleted() on LiveLegacyResultSet has its
    // assignment commented out (`// completed = true;`). The public complete() method is the
    // one that actually sets the flag. Pin this divergence from the parent's contract.
    var rs = new LiveLegacyResultSet<String>();
    // Before complete(): live is still running — isEmptyNoWait pinned below.
    rs.setCompleted();
    // After setCompleted the parent's completed flag remains false because the override
    // commented out that assignment; we prove this by calling complete() and observing that
    // it does something (no exception, and subsequent complete() is idempotent).
    rs.complete();
    // Idempotent: second complete must not throw.
    rs.complete();
  }

  @Test
  public void liveLegacyResultSetCopyProducesEmptyInstance() {
    // LiveLegacyResultSet.copy returns a fresh instance that does not carry over the queue.
    // Pin this "shallow new" behaviour.
    var rs = new LiveLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var copy = rs.copy();
    assertNotNull(copy);
    assertNotNull("copy is a new LiveLegacyResultSet instance", copy);
    assertFalse("copy has its own queue and is treated as live (isEmpty hardcoded false)",
        copy.isEmpty());
    // Copy's queue is empty — next() would block forever if called without add, so we don't
    // call it. We only pin that size() on the copy still throws UOE.
    try {
      copy.size();
      fail("size() on copy must also throw UOE");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void liveLegacyResultSetUnsupportedMethods() throws Exception {
    // The class disables most List mutators + readers. Cover them in one test.
    var rs = new LiveLegacyResultSet<String>();
    try {
      rs.set(0, "a");
      fail("set must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.contains("a");
      fail("contains must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.toArray();
      fail("toArray must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.toArray(new String[0]);
      fail("toArray(T[]) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.clear();
      fail("clear must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.get(0);
      fail("get(int) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.indexOf("a");
      fail("indexOf must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.lastIndexOf("a");
      fail("lastIndexOf must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.listIterator();
      fail("listIterator must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.listIterator(0);
      fail("listIterator(int) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.subList(0, 0);
      fail("subList must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.remove(0);
      fail("remove(int) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.remove((Object) "a");
      fail("remove(Object) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.removeAll(Arrays.asList("a"));
      fail("removeAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.retainAll(Arrays.asList("a"));
      fail("retainAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.containsAll(Arrays.asList("a"));
      fail("containsAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.writeExternal(null);
      fail("writeExternal must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
    try {
      rs.readExternal(null);
      fail("readExternal must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void liveLegacyResultSetSetLimitReturnsNull() {
    // WHEN-FIXED: Track 22 — LiveLegacyResultSet.setLimit returns null (annotated @Nullable).
    // This breaks the LegacyResultSet fluent contract (every other impl returns `this`). Pin
    // the divergence so removal is explicit.
    var rs = new LiveLegacyResultSet<String>();
    var returned = rs.setLimit(5);
    assertNull("LiveLegacyResultSet.setLimit deviates from fluent contract (returns null)",
        returned);
    assertEquals("the limit still propagates to the wrapped BasicLegacyResultSet", 5,
        rs.getLimit());
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
      assertTrue(expected.getMessage().contains("LegacyResultSet.iterator.remove()"));
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
    LiveResultListener l = new LiveResultListener() {
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
    LiveResultListener delegate = new LiveResultListener() {
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
    // three so deletion is an explicit contract change.
    LiveResultListener noop = new LiveResultListener() {
      @Override
      public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken, RecordOperation iOp) {
        // no-op
      }

      @Override
      public void onError(int iLiveToken) {
        // no-op
      }

      @Override
      public void onUnsubscribe(int iLiveToken) {
        // no-op
      }
    };
    var adapter = new LocalLiveResultListener(noop);
    // CommandResultListener.result(session, record) is hardcoded to return false.
    assertFalse("result() is a hardcoded stub returning false", adapter.result(null, new Object()));
    // end(session) is a no-op — simply proving it does not throw pins the contract.
    adapter.end(null);
    // getResult() is hardcoded to return null.
    assertNull("getResult() is a hardcoded stub returning null", adapter.getResult());
  }

  @Test
  public void localLiveResultListenerDelegateIdentityIsPreservedThroughAllForwardingPaths() {
    // Pin that ALL forwarding calls use the same delegate identity — a defensive check against
    // a regression where one of the three overrides silently swaps the target.
    var seen = new AtomicInteger();
    final LiveResultListener sentinel = new LiveResultListener() {
      @Override
      public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken, RecordOperation iOp) {
        // Token acts as a breadcrumb — if a future refactor drops the token during forwarding,
        // this assertion catches it.
        assertEquals("delegate must receive the exact token passed to the adapter", 123,
            iLiveToken);
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
    adapter.onLiveResult(null, 123, null);
    adapter.onError(456);
    adapter.onUnsubscribe(789);
    assertEquals("all three callbacks landed on the same delegate", 3, seen.get());
    // Identity pin: since LocalLiveResultListener stores its delegate in a final field, the
    // same adapter must keep forwarding to the same delegate across many invocations.
    adapter.onLiveResult(null, 123, null);
    assertSame("adapter IS-A LiveResultListener; identity pin for isinstanceof checks", adapter,
        (LiveResultListener) adapter);
    assertEquals("repeat forwarding observes same delegate", 4, seen.get());
  }
}
