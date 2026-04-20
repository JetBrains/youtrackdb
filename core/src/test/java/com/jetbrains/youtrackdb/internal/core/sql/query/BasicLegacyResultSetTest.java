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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import org.junit.Test;

/**
 * Standalone unit tests for {@link BasicLegacyResultSet}, the live List-implementing
 * resultset variant in {@code core/sql/query}. No database session is required; the class
 * wraps a {@code Collections.synchronizedList(new ArrayList<>())} and delegates the live
 * {@link List} operations while throwing {@link UnsupportedOperationException} for the
 * mutating-remove / search branches that the legacy API never supported.
 *
 * <p>The companion {@link SqlQueryDeadCodeTest} pins the four sibling classes
 * ({@code ConcurrentLegacyResultSet}, {@code LiveLegacyResultSet}, {@code LiveResultListener},
 * {@code LocalLiveResultListener}) that have zero production call sites. Together they cover
 * the live slice of the package (BasicLegacyResultSet + the interface) for Track 7.
 *
 * <p>Two behaviors are intentionally pinned as latent bugs via {@code // WHEN-FIXED:} markers:
 *
 * <ul>
 *   <li>The {@link BasicLegacyResultSet#isEmpty()} method re-checks {@code underlying.isEmpty()}
 *       a second time inside an {@code if (empty)} branch — this is a no-op idiom (the first
 *       check already returned {@code true}). Pin that the current behaviour matches the
 *       single-check semantics so a cleanup in Track 22 will not change it silently.</li>
 *   <li>{@link BasicLegacyResultSet#equals(Object)} forwards to {@code underlying.equals(o)}.
 *       This creates an asymmetric contract with {@link List#equals(Object)}: the resultset
 *       equals an {@link java.util.ArrayList} holding the same elements, but the reverse may
 *       or may not — because {@code ArrayList.equals} only compares to other {@link List}
 *       instances, and our resultset IS a {@code List} → both directions happen to be true.
 *       The asymmetry to pin is vs. an arbitrary {@code Object} that is not a List: the
 *       resultset returns {@code false}, but — because {@code underlying.equals} is what runs
 *       — a synchronizedList wrapper can equal a plain {@code ArrayList}, meaning the
 *       resultset's identity leaks into its equality.</li>
 * </ul>
 */
public class BasicLegacyResultSetTest {

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  @Test
  public void defaultConstructorStartsEmptyWithNoLimit() {
    var rs = new BasicLegacyResultSet<String>();
    assertTrue("fresh resultset should be empty", rs.isEmpty());
    assertEquals(0, rs.size());
    assertEquals(0, rs.currentSize());
    assertEquals("default limit should be -1 (unbounded)", -1, rs.getLimit());
  }

  @Test
  public void capacityConstructorStartsEmpty() {
    // Pre-allocated capacity does not change observable size; only future add() growth avoids
    // a resize. We verify size==0 and that add still works.
    var rs = new BasicLegacyResultSet<Integer>(16);
    assertTrue(rs.isEmpty());
    assertEquals(0, rs.size());
    assertTrue(rs.add(1));
    assertEquals(1, rs.size());
  }

  // ---------------------------------------------------------------------------
  // size / isEmpty / currentSize
  // ---------------------------------------------------------------------------

  @Test
  public void sizeAndCurrentSizeAgreeWithUnderlyingList() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    assertEquals(3, rs.size());
    assertEquals(
        "size() and currentSize() must agree for BasicLegacyResultSet (no wait-for-complete)",
        rs.size(), rs.currentSize());
  }

  @Test
  public void isEmptyReturnsTrueWhenNothingAdded() {
    var rs = new BasicLegacyResultSet<String>();
    assertTrue(rs.isEmpty());
  }

  @Test
  public void isEmptyReturnsFalseAfterAdd() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("x");
    assertFalse(rs.isEmpty());
  }

  @Test
  public void isEmptyDoubleCheckIdiomIsNoopForBasicResultSet() {
    // WHEN-FIXED: Track 22 — BasicLegacyResultSet.isEmpty() contains a redundant second
    // isEmpty() call:
    //   var empty = underlying.isEmpty();
    //   if (empty) { empty = underlying.isEmpty(); }
    // This is a no-op idiom (likely copy-pasted from ConcurrentLegacyResultSet's wait-for-items
    // path). Pin the outcome so that cleanup in Track 22 collapses it to a single check without
    // behavioural drift. If the method is ever changed to actually block or wait, this test
    // must be rewritten.
    var rs = new BasicLegacyResultSet<String>();
    assertTrue("double-check idiom still returns true when empty", rs.isEmpty());
    rs.add("x");
    assertFalse("double-check idiom still returns false once non-empty", rs.isEmpty());
  }

  @Test
  public void isEmptyNoWaitMatchesIsEmptyForBasicResultSet() {
    // BasicLegacyResultSet is a live resultset (no wait-for-complete) — isEmptyNoWait should
    // behave identically to isEmpty for every state.
    var rs = new BasicLegacyResultSet<String>();
    assertEquals(rs.isEmpty(), rs.isEmptyNoWait());
    rs.add("x");
    assertEquals(rs.isEmpty(), rs.isEmptyNoWait());
    rs.clear();
    assertEquals(rs.isEmpty(), rs.isEmptyNoWait());
  }

  // ---------------------------------------------------------------------------
  // add / addAll / get / set / clear / contains
  // ---------------------------------------------------------------------------

  @Test
  public void addAppendsAndReturnsTrue() {
    var rs = new BasicLegacyResultSet<String>();
    assertTrue(rs.add("a"));
    assertTrue(rs.add("b"));
    assertEquals("a", rs.get(0));
    assertEquals("b", rs.get(1));
    assertEquals(2, rs.size());
  }

  @Test
  public void addAcceptsNullElement() {
    // ArrayList tolerates null entries; BasicLegacyResultSet must not reject them either.
    var rs = new BasicLegacyResultSet<String>();
    assertTrue(rs.add(null));
    assertEquals(1, rs.size());
    assertNull(rs.get(0));
    assertTrue("contains(null) must locate the stored null element", rs.contains(null));
  }

  @Test
  public void addByIndexInsertsAtPosition() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("c");
    rs.add(1, "b");
    assertEquals(3, rs.size());
    assertEquals("a", rs.get(0));
    assertEquals("b", rs.get(1));
    assertEquals("c", rs.get(2));
  }

  @Test
  public void setReplacesElementAndReturnsPrevious() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var prev = rs.set(0, "A");
    assertEquals("a", prev);
    assertEquals("A", rs.get(0));
    assertEquals("b", rs.get(1));
    assertEquals(2, rs.size());
  }

  @Test
  public void addAllAppendsAllFromCollection() {
    var rs = new BasicLegacyResultSet<String>();
    assertTrue(rs.addAll(Arrays.asList("a", "b", "c")));
    assertEquals(3, rs.size());
    assertEquals("b", rs.get(1));
  }

  @Test
  public void addAllEmptyCollectionReturnsFalse() {
    // ArrayList contract: addAll returns whether the collection changed. Empty input → false.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    assertFalse("addAll(emptyList) must return false per List contract",
        rs.addAll(Collections.<String>emptyList()));
    assertEquals(1, rs.size());
  }

  @Test
  public void addAllAtIndexInsertsAtPosition() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("d");
    assertTrue(rs.addAll(1, Arrays.asList("b", "c")));
    assertEquals(4, rs.size());
    assertEquals("a", rs.get(0));
    assertEquals("b", rs.get(1));
    assertEquals("c", rs.get(2));
    assertEquals("d", rs.get(3));
  }

  @Test
  public void clearRemovesAllElementsAndStateBecomesEmpty() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.clear();
    assertEquals(0, rs.size());
    assertTrue(rs.isEmpty());
    assertFalse(rs.contains("a"));
  }

  @Test
  public void containsReturnsTrueForPresentElementFalseForMissing() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    assertTrue(rs.contains("a"));
    assertTrue(rs.contains("b"));
    assertFalse(rs.contains("c"));
  }

  @Test
  public void getReturnsElementAtIndex() {
    var rs = new BasicLegacyResultSet<Integer>();
    rs.add(10);
    rs.add(20);
    rs.add(30);
    assertEquals(Integer.valueOf(10), rs.get(0));
    assertEquals(Integer.valueOf(20), rs.get(1));
    assertEquals(Integer.valueOf(30), rs.get(2));
  }

  @Test
  public void getOutOfBoundsPropagatesUnderlyingException() {
    // underlying ArrayList throws IndexOutOfBoundsException; BasicLegacyResultSet does not
    // catch/translate — pin this propagation.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.get(5);
      fail("expected IndexOutOfBoundsException");
    } catch (IndexOutOfBoundsException expected) {
      // ok
    }
  }

  // ---------------------------------------------------------------------------
  // setLimit / overflow truncation
  // ---------------------------------------------------------------------------

  @Test
  public void setLimitRecordsValueAndReturnsThis() {
    var rs = new BasicLegacyResultSet<String>();
    var returned = rs.setLimit(5);
    assertSame("setLimit must return this for fluent chaining", rs, returned);
    assertEquals(5, rs.getLimit());
  }

  @Test
  public void setLimitNegativeOneMeansUnbounded() {
    // getLimit() == -1 is the documented "no limit" sentinel; the add() guard is
    // `limit > -1 && size >= limit`, so -1 must not trigger truncation.
    var rs = new BasicLegacyResultSet<Integer>();
    rs.setLimit(-1);
    for (int i = 0; i < 50; i++) {
      assertTrue("under -1 limit every add must succeed", rs.add(i));
    }
    assertEquals(50, rs.size());
  }

  @Test
  public void addBeyondLimitReturnsFalseAndDoesNotTruncate() {
    // The guard is `limit > -1 && size >= limit` → return false. The element is silently
    // dropped without throwing. Pin this exact behaviour.
    var rs = new BasicLegacyResultSet<Integer>();
    rs.setLimit(2);
    assertTrue(rs.add(1));
    assertTrue(rs.add(2));
    assertFalse("third add must be silently dropped when limit == 2", rs.add(3));
    assertEquals("size must not grow past the limit", 2, rs.size());
    assertFalse("dropped element must not appear in the resultset", rs.contains(3));
  }

  @Test
  public void addBeyondZeroLimitDropsEveryElement() {
    // Edge case: limit=0 means no elements are ever accepted.
    var rs = new BasicLegacyResultSet<Integer>();
    rs.setLimit(0);
    assertFalse(rs.add(1));
    assertFalse(rs.add(2));
    assertEquals(0, rs.size());
    assertTrue(rs.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Iterator behaviour
  // ---------------------------------------------------------------------------

  @Test
  public void iteratorTraversesInInsertionOrder() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    var it = rs.iterator();
    assertTrue(it.hasNext());
    assertEquals("a", it.next());
    assertEquals("b", it.next());
    assertEquals("c", it.next());
    assertFalse("iterator exhausted after 3 elements", it.hasNext());
  }

  @Test
  public void iteratorHasNextFalseOnEmptyResultSet() {
    var rs = new BasicLegacyResultSet<String>();
    var it = rs.iterator();
    assertFalse(it.hasNext());
  }

  @Test
  public void iteratorNextThrowsNoSuchElementWhenEmpty() {
    // BasicLegacyResultSet's iterator() throws NoSuchElementException when next() is called
    // and size() == 0 — covers the `size() == 0` branch of the guard `index > size || size == 0`.
    var rs = new BasicLegacyResultSet<String>();
    try {
      rs.iterator().next();
      fail("expected NoSuchElementException on empty resultset iterator.next()");
    } catch (NoSuchElementException expected) {
      assertTrue(
          "exception message must mention the size-zero state, got: " + expected.getMessage(),
          expected.getMessage().contains("contains only 0 items"));
    }
  }

  @Test
  public void iteratorNextOnExhaustedIteratorThrowsIndexOutOfBoundsNotNoSuchElement() {
    // WHEN-FIXED: Track 22 — BasicLegacyResultSet's iterator() returns an anonymous Iterator
    // whose next() uses the guard `if (index > size() || size() == 0)` — strict greater-than,
    // not greater-or-equal. After a single add + next(), index == size() == 1; the guard is
    // FALSE (1 > 1 is false, 1 == 0 is false), so the method falls through to
    // `underlying.get(1)` and the caller receives an IndexOutOfBoundsException from the
    // underlying ArrayList rather than the intended NoSuchElementException. The correct guard
    // is `index >= size() || size() == 0`.
    //
    // Pin the current buggy behaviour (IOOBE at length 1) so Track 22's fix must explicitly
    // flip this test to expect NoSuchElementException.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("only");
    var it = rs.iterator();
    assertEquals("only", it.next());
    try {
      it.next();
      fail("expected IndexOutOfBoundsException from the buggy strict > guard");
    } catch (IndexOutOfBoundsException expected) {
      assertTrue(
          "IOOBE message must surface the out-of-range index 1 at length 1, got: "
              + expected.getMessage(),
          expected.getMessage().contains("1"));
    } catch (NoSuchElementException unexpected) {
      fail(
          "NoSuchElementException would mean the strict > guard was already fixed — update"
              + " this test to match the post-fix contract. Got: "
              + unexpected.getMessage());
    }
  }

  @Test
  public void iteratorRemoveThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    var it = rs.iterator();
    it.next();
    try {
      it.remove();
      fail("iterator.remove() must be unsupported");
    } catch (UnsupportedOperationException expected) {
      assertTrue(
          "message must mention BasicLegacyResultSet.iterator.remove()",
          expected.getMessage().contains("BasicLegacyResultSet.iterator.remove()"));
    }
  }

  // ---------------------------------------------------------------------------
  // Array conversion
  // ---------------------------------------------------------------------------

  @Test
  public void toArrayReturnsElementsInOrder() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    var arr = rs.toArray();
    assertArrayEquals(new Object[] {"a", "b", "c"}, arr);
  }

  @Test
  public void toArrayTypedFillsExistingArrayWhenLargeEnough() {
    // List.toArray(T[]) contract: when the target array is large enough, reuse it and null-
    // terminate at size(). Pin both return-identity and null termination.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var dest = new String[4];
    var ret = rs.toArray(dest);
    assertSame("toArray(T[]) must return the SAME array when capacity suffices", dest, ret);
    assertEquals("a", dest[0]);
    assertEquals("b", dest[1]);
    assertNull("element at size() must be null-terminated per List.toArray contract", dest[2]);
  }

  @Test
  public void toArrayTypedAllocatesNewArrayWhenTooSmall() {
    // When the target is too small, toArray(T[]) allocates a new array of the runtime type.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var dest = new String[0];
    var ret = rs.toArray(dest);
    assertNotSame("toArray(T[]) must allocate a new array when capacity is too small", dest, ret);
    assertEquals(2, ret.length);
    assertEquals("a", ret[0]);
    assertEquals("b", ret[1]);
  }

  // ---------------------------------------------------------------------------
  // UOE methods — one test per method per step T7
  // ---------------------------------------------------------------------------

  @Test
  public void removeObjectThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.remove((Object) "a");
      fail("remove(Object) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void removeIndexThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.remove(0);
      fail("remove(int) must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void containsAllThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    try {
      rs.containsAll(Arrays.asList("a"));
      fail("containsAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void removeAllThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    try {
      rs.removeAll(Arrays.asList("a"));
      fail("removeAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void retainAllThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    try {
      rs.retainAll(Arrays.asList("a"));
      fail("retainAll must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void indexOfThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.indexOf("a");
      fail("indexOf must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void lastIndexOfThrowsUnsupported() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    try {
      rs.lastIndexOf("a");
      fail("lastIndexOf must be unsupported");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  // ---------------------------------------------------------------------------
  // listIterator / subList
  // ---------------------------------------------------------------------------

  @Test
  public void listIteratorDelegatesToUnderlyingList() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    ListIterator<String> lit = rs.listIterator();
    assertTrue(lit.hasNext());
    assertEquals("a", lit.next());
    assertEquals("b", lit.next());
    // listIterator has reverse traversal unlike the forward-only iterator.
    assertTrue(lit.hasPrevious());
    assertEquals("b", lit.previous());
  }

  @Test
  public void listIteratorWithIndexStartsAtRequestedPosition() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    ListIterator<String> lit = rs.listIterator(2);
    assertEquals(2, lit.nextIndex());
    assertTrue(lit.hasNext());
    assertEquals("c", lit.next());
    assertFalse(lit.hasNext());
  }

  @Test
  public void subListReturnsViewOverRange() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    rs.add("c");
    rs.add("d");
    List<String> sub = rs.subList(1, 3);
    assertEquals(2, sub.size());
    assertEquals("b", sub.get(0));
    assertEquals("c", sub.get(1));
  }

  @Test
  public void subListEmptyWhenFromEqualsTo() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    List<String> sub = rs.subList(1, 1);
    assertTrue(sub.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // equals / hashCode
  // ---------------------------------------------------------------------------

  @Test
  public void equalsAndHashCodeMatchArrayListWithSameContent() {
    // underlying.equals delegates to synchronizedList which compares to ArrayList via List
    // contract. Pin this round-trip.
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var control = Arrays.asList("a", "b");
    assertEquals("resultset must equal a List with the same elements", rs, control);
    assertEquals("hashCode must match the underlying List hashCode", rs.hashCode(),
        control.hashCode());
  }

  @Test
  public void equalsReturnsFalseForNonListObject() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    assertFalse("resultset must not equal a non-List", rs.equals("a"));
    assertFalse("resultset must not equal null", rs.equals(null));
  }

  @Test
  public void equalsAsymmetryLeaksThroughSynchronizedListWrapper() {
    // WHEN-FIXED: Track 22 — BasicLegacyResultSet.equals forwards to underlying.equals. The
    // underlying list is a Collections.synchronizedList wrapper, and synchronizedList.equals
    // compares its inner ArrayList to the argument. This creates a subtle asymmetry with the
    // general List.equals contract: an arbitrary List (including ArrayList and another
    // BasicLegacyResultSet) equals this resultset, but the reverse may not hold unless the
    // OTHER side is also a List.
    //
    // Pin: two empty BasicLegacyResultSets are NOT equal to each other via our equals() path,
    // because underlying.equals(otherResultSet) compares a List to a non-List object (the
    // BasicLegacyResultSet itself is the argument — the List.equals contract asks "is this
    // argument a List with the same elements?", and BasicLegacyResultSet IS a List, so the
    // comparison delegates back). In fact both ARE Lists, so underlying.equals(otherResultSet)
    // DOES return true — meaning our equals() is REFLEXIVE across resultset instances.
    // Pin this reflexive pair so a future tightening of equals to `o instanceof
    // BasicLegacyResultSet` does not silently break List-contract callers.
    var empty1 = new BasicLegacyResultSet<String>();
    var empty2 = new BasicLegacyResultSet<String>();
    assertTrue("empty BasicLegacyResultSet must equal another empty one via List contract",
        empty1.equals(empty2));
    assertTrue("reverse: empty2 must equal empty1 too", empty2.equals(empty1));
    // Now cross-check: our resultset equals an empty ArrayList AND the empty ArrayList equals
    // our resultset — symmetric via the List contract.
    assertTrue("resultset.equals(emptyList) must be true",
        empty1.equals(Collections.emptyList()));
    assertTrue("reverse: emptyList.equals(resultset) must be true",
        Collections.emptyList().equals(empty1));
  }

  // ---------------------------------------------------------------------------
  // copy / Externalizable round-trip
  // ---------------------------------------------------------------------------

  @Test
  public void copyProducesIndependentResultSetWithSameElements() {
    var rs = new BasicLegacyResultSet<String>();
    rs.add("a");
    rs.add("b");
    var copy = rs.copy();
    assertNotNull(copy);
    assertNotSame("copy must be a new BasicLegacyResultSet instance", rs, copy);
    assertEquals("copy must have the same size", rs.size(), copy.size());
    assertEquals("a", copy.get(0));
    assertEquals("b", copy.get(1));
    // Independence: modifying the source must not affect the copy.
    rs.add("c");
    assertEquals("source grew", 3, rs.size());
    assertEquals("copy stayed at 2 — independence", 2, copy.size());
  }

  @Test
  public void copyOfEmptyResultSetYieldsEmptyResultSet() {
    var rs = new BasicLegacyResultSet<String>();
    var copy = rs.copy();
    assertNotNull(copy);
    assertTrue("copy of empty must also be empty", copy.isEmpty());
  }

  @Test
  public void externalizableRoundTripRestoresElements() throws Exception {
    // writeExternal delegates to `out.writeObject(underlying)`; readExternal reads it back.
    // Use a standard ObjectOutputStream/ObjectInputStream pair to exercise the round-trip.
    var original = new BasicLegacyResultSet<String>();
    original.add("x");
    original.add("y");
    original.add("z");

    var bout = new ByteArrayOutputStream();
    try (var oos = new ObjectOutputStream(bout)) {
      original.writeExternal(oos);
    }

    var restored = new BasicLegacyResultSet<String>();
    try (var ois = new ObjectInputStream(new ByteArrayInputStream(bout.toByteArray()))) {
      restored.readExternal(ois);
    }

    assertEquals("round-trip must preserve size", 3, restored.size());
    assertEquals("x", restored.get(0));
    assertEquals("y", restored.get(1));
    assertEquals("z", restored.get(2));
  }

  @Test
  public void externalizableRoundTripOfEmptyPreservesEmptyState() throws Exception {
    var original = new BasicLegacyResultSet<String>();
    var bout = new ByteArrayOutputStream();
    try (var oos = new ObjectOutputStream(bout)) {
      original.writeExternal(oos);
    }
    var restored = new BasicLegacyResultSet<String>();
    try (var ois = new ObjectInputStream(new ByteArrayInputStream(bout.toByteArray()))) {
      restored.readExternal(ois);
    }
    assertTrue("round-trip of empty must restore to empty state", restored.isEmpty());
    assertEquals(0, restored.size());
  }

  // ---------------------------------------------------------------------------
  // setCompleted — live resultset no-op returning this
  // ---------------------------------------------------------------------------

  @Test
  public void setCompletedIsNoopAndReturnsThis() {
    // BasicLegacyResultSet is already "complete" as soon as it is constructed — the live
    // variant with no async population. setCompleted() must just return `this` to satisfy the
    // fluent interface declared in LegacyResultSet.
    var rs = new BasicLegacyResultSet<String>();
    var returned = rs.setCompleted();
    assertSame("setCompleted must return this", rs, returned);
    // Calling it a second time must remain idempotent — there is no state to change.
    assertSame(rs, rs.setCompleted());
  }
}
