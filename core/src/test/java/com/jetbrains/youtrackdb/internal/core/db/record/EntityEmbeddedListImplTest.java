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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.record;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/**
 * Behavioural pins for {@link EntityEmbeddedListImpl} — the embedded-list wrapper that drives
 * dirty/track propagation back to the owning record. Existing {@code TrackedListTest} covers
 * the happy-path event-emission flow; this class targets residual paths: alternative
 * constructors, owner-conflict, structural mutators (set/remove/clear/sort/{add,remove}{First,
 * Last}), rollback semantics, and AbstractList/Collection delegations (equals/hashCode/toArray/
 * iterator/spliterator/stream/forEach/reversed). Each test runs inside a transaction that is
 * rolled back via {@link DbTestBase#afterTest()} so per-test isolation does not depend on a
 * passing assertion.
 */
public class EntityEmbeddedListImplTest extends DbTestBase {

  // ---------- constructors ----------

  /**
   * Constructor that copies an origin collection records the items and marks the parent
   * dirty exactly once via the unconditional {@code addEvent} → {@code setDirty} chain
   * (tracking is off because the wrapper has no parent yet — the ctor in question takes a
   * non-null record but does not call {@code enableTracking}). Pins both the size after
   * copy and that {@code isModified()} reports {@code true} because {@code dirty} is set.
   */
  @Test
  public void ctorWithOriginCopiesAllElementsAndMarksDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<>(doc, Arrays.asList("a", "b", "c"));

    assertEquals(3, list.size());
    assertEquals(List.of("a", "b", "c"), list);
    assertTrue("addAll → addEvent → setDirty propagates", doc.isDirty());
    session.rollback();
  }

  /**
   * The ctor with an empty origin must not fall through into {@code addAll}, so the parent
   * stays clean. This pins the {@code iOrigin != null && !iOrigin.isEmpty()} guard.
   */
  @Test
  public void ctorWithEmptyOriginIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();

    final var list = new EntityEmbeddedListImpl<String>(doc, List.of());
    assertTrue(list.isEmpty());
    assertFalse("empty origin must not flip the parent dirty", doc.isDirty());
    session.rollback();
  }

  /**
   * The size-presizing ctor allocates the backing list at the requested capacity and
   * pins the no-record overload that auto-enables tracking from construction (used by
   * deserialization paths).
   */
  @Test
  public void ctorWithoutRecordEnablesTrackingImmediately() {
    final var list = new EntityEmbeddedListImpl<String>(7);

    list.add("x");
    assertTrue("standalone ctor enables tracking → tracker.isChanged()", list.isModified());
    assertEquals(1, list.getTimeLine().getMultiValueChangeEvents().size());
  }

  // ---------- setOwner ----------

  /**
   * {@code setOwner(null)} clears the source record. The wrapper falls back to the
   * dirty-only branch on the next mutation (no parent to propagate to).
   */
  @Test
  public void setOwnerNullClearsSourceRecord() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);

    list.setOwner(null);
    assertNull(list.getOwner());

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    list.add("v");
    assertFalse("no owner means setDirty has nothing to propagate", doc.isDirty());
    session.rollback();
  }

  /**
   * Reassigning to a different non-null owner is rejected. The exception text mirrors the
   * production message verbatim so a future rename is forced through the test.
   */
  @Test
  public void setOwnerToDifferentRecordIsRejected() {
    session.begin();
    final var doc1 = (EntityImpl) session.newEntity();
    final var doc2 = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc1);

    final var ex = assertThrows(IllegalStateException.class, () -> list.setOwner(doc2));
    assertTrue(ex.getMessage().startsWith("This list is already owned by data container"));
    session.rollback();
  }

  /**
   * Reassigning to the same owner is a no-op (equals returns true). Pins the
   * {@code !owner.equals(newOwner)} short-circuit.
   */
  @Test
  public void setOwnerToSameRecordIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.setOwner(doc);
    assertSame(doc, list.getOwner());
    session.rollback();
  }

  // ---------- structural mutators ----------

  /**
   * {@code addInternal} bypasses event emission — pins that the tracker stays clean and the
   * parent does NOT flip dirty even though the underlying list grows.
   */
  @Test
  public void addInternalDoesNotEmitEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();

    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.enableTracking(doc);

    list.addInternal("via-internal");

    assertEquals(1, list.size());
    assertFalse("addInternal does not emit ADD event", list.isModified());
    assertFalse(doc.isDirty());
    session.rollback();
  }

  /**
   * {@code add(int, T)} emits an ADD event positioned at the given index. Pins the
   * tracking branch.
   */
  @Test
  public void addAtIndexEmitsEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("c");
    list.enableTracking(doc);

    list.add(1, "b");

    assertEquals(List.of("a", "b", "c"), list);
    final var event = list.getTimeLine().getMultiValueChangeEvents().getFirst();
    assertEquals(MultiValueChangeEvent.ChangeType.ADD, event.getChangeType());
    assertEquals(Integer.valueOf(1), event.getKey());
    session.rollback();
  }

  /**
   * {@code set(int, T)} emits an UPDATE only when the new value differs from the old one
   * by {@link Object#equals}. The equal-replacement path stays silent (pins the
   * {@code oldValue != null && !oldValue.equals(element)} guard).
   */
  @Test
  public void setReplacingEqualValueDoesNotEmitEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    // pre-enable add()s flipped the local dirty flag; cycle disable/enable to clear it before pinning the no-op.
    list.disableTracking(doc);
    list.enableTracking(doc);

    final var old = list.set(0, "a");
    assertEquals("a", old);

    assertFalse("equal-value replacement is not an update", list.isModified());
    session.rollback();
  }

  /**
   * The differing replacement path emits UPDATE with both old and new values pinned.
   */
  @Test
  public void setReplacingDifferingValueEmitsUpdateEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.enableTracking(doc);

    final var old = list.set(0, "Z");
    assertEquals("a", old);

    final var event = list.getTimeLine().getMultiValueChangeEvents().getFirst();
    assertEquals(MultiValueChangeEvent.ChangeType.UPDATE, event.getChangeType());
    assertEquals("Z", event.getValue());
    assertEquals("a", event.getOldValue());
    session.rollback();
  }

  /** {@code remove(Object)} returns false when the value is missing — no event emitted. */
  @Test
  public void removeObjectMissingReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.disableTracking(doc);
    list.enableTracking(doc);

    assertFalse(list.remove("nope"));
    assertFalse(list.isModified());
    session.rollback();
  }

  /** {@code remove(Object)} dispatches to the indexed remove and emits a REMOVE event. */
  @Test
  public void removeObjectFound() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    list.enableTracking(doc);

    assertTrue(list.remove("a"));
    assertEquals(1, list.size());
    assertEquals(MultiValueChangeEvent.ChangeType.REMOVE,
        list.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /** {@code removeAll} aggregates per-element removes. */
  @Test
  public void removeAllDispatchesPerElement() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    list.add("c");
    list.enableTracking(doc);

    final var changed = list.removeAll(List.of("a", "c", "missing"));
    assertTrue(changed);
    assertEquals(List.of("b"), list);
    session.rollback();
  }

  /**
   * {@code clear} iterates in reverse and emits REMOVE events for every element. Pins
   * both the descending iteration order (oldest index visited last) and that the list
   * empties.
   */
  @Test
  public void clearEmitsReverseOrderRemoveEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    list.add("c");
    list.enableTracking(doc);

    list.clear();

    assertTrue(list.isEmpty());
    final var events = list.getTimeLine().getMultiValueChangeEvents();
    assertEquals(3, events.size());
    // first emitted index is the highest (reverse iteration)
    assertEquals(Integer.valueOf(2), events.get(0).getKey());
    assertEquals(Integer.valueOf(0), events.get(2).getKey());
    session.rollback();
  }

  // ---------- deque-style methods ----------

  /**
   * Deque-style mutators each emit events at the documented index (0 for first, size-1 for
   * last). Bundles the four {@code addFirst/addLast/removeFirst/removeLast} arms into a
   * single tightly-asserted scenario.
   */
  @Test
  public void dequeOpsEmitEventsAtCorrectIndex() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("middle");
    list.enableTracking(doc);

    list.addFirst("first");
    list.addLast("last");
    assertEquals(List.of("first", "middle", "last"), list);
    assertEquals("first", list.getFirst());
    assertEquals("last", list.getLast());

    final var addedFirstEvent = list.getTimeLine().getMultiValueChangeEvents().getFirst();
    assertEquals(Integer.valueOf(0), addedFirstEvent.getKey());

    final var removedFirst = list.removeFirst();
    final var removedLast = list.removeLast();
    assertEquals("first", removedFirst);
    assertEquals("last", removedLast);
    assertEquals(List.of("middle"), list);
    session.rollback();
  }

  // ---------- rollback ----------

  /**
   * {@code returnOriginalState} replays events backwards: ADD undoes via {@code remove},
   * REMOVE re-inserts the old value, UPDATE restores the old value at the same index. Pins
   * all three arms in one scenario.
   */
  @Test
  public void returnOriginalStateRevertsAddRemoveUpdate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    list.add("c");

    final List<String> snapshot = new ArrayList<>(list);
    list.enableTracking(doc);

    list.add("d"); // ADD
    list.remove(0); // REMOVE
    list.set(0, "Z"); // UPDATE on what is now "c" (was at index 1, snapshot[2])

    final var reverted = list.returnOriginalState(
        session.getActiveTransaction(), list.getTimeLine().getMultiValueChangeEvents());
    assertEquals(snapshot, reverted);
    session.rollback();
  }

  /**
   * {@code rollbackChanges} on a tracker that was never enabled throws — the production
   * message is preserved for grep-friendliness.
   */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");

    final var tx = session.getActiveTransaction();
    final var ex = assertThrows(DatabaseException.class, () -> list.rollbackChanges(tx));
    assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /**
   * With tracking enabled but no events, {@code rollbackChanges} is a no-op (timeline
   * empty branch).
   */
  @Test
  public void rollbackChangesWithEmptyTimelineIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.enableTracking(doc);

    list.rollbackChanges(session.getActiveTransaction());
    assertTrue(list.isEmpty());
    session.rollback();
  }

  // ---------- tracking lifecycle ----------

  /**
   * {@code disableTracking} clears the dirty flag and disables event emission going
   * forward; subsequent mutations propagate dirty to the parent (the {@code else}
   * branch in {@code addEvent}).
   */
  @Test
  public void disableTrackingClearsDirtyAndDelegatesToSetDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.enableTracking(doc);
    list.add("a");
    assertTrue(list.isModified());

    list.disableTracking(doc);
    assertFalse("disableTracking resets the local dirty flag", list.isModified());

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    list.add("b");
    assertTrue("post-disable mutations route through setDirty", doc.isDirty());
    session.rollback();
  }

  /**
   * {@code transactionClear} clears the per-transaction dirty flag without affecting the
   * persistent {@code dirty} signal, and resets the transactional timeline.
   */
  @Test
  public void transactionClearResetsTransactionDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.enableTracking(doc);
    list.add("a");
    assertTrue(list.isTransactionModified());

    list.transactionClear();
    assertFalse(list.isTransactionModified());
    assertNull(list.getTransactionTimeLine());
    session.rollback();
  }

  // ---------- AbstractList delegations ----------

  /** {@code equals} delegates to the backing list (any List comparison). */
  @Test
  public void equalsDelegatesToBackingList() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");

    assertEquals(list, list);
    assertEquals(List.of("a", "b"), list);
    assertEquals(list, List.of("a", "b"));
    assertNotEquals("non-list comparand returns false", "x", list);
    session.rollback();
  }

  /** {@code hashCode} delegates to the backing list — two equal lists hash equally. */
  @Test
  public void hashCodeMatchesBackingList() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");

    assertEquals(List.of("a", "b").hashCode(), list.hashCode());
    session.rollback();
  }

  /** {@code toString} delegates to {@link ArrayList#toString}. */
  @Test
  public void toStringDelegatesToBackingList() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    assertEquals(List.of("a", "b").toString(), list.toString());
    session.rollback();
  }

  /** {@code indexOf} / {@code lastIndexOf} delegate to the backing list. */
  @Test
  public void indexOfAndLastIndexOf() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");
    list.add("a");

    assertEquals(0, list.indexOf("a"));
    assertEquals(2, list.lastIndexOf("a"));
    assertEquals(-1, list.indexOf("missing"));
    session.rollback();
  }

  /** {@code contains} / {@code containsAll} / {@code isEmpty}. */
  @Test
  public void containsContainsAllAndIsEmpty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    assertTrue(list.isEmpty());

    list.add("a");
    list.add("b");

    assertTrue(list.contains("a"));
    assertFalse(list.contains("c"));
    assertTrue(list.containsAll(List.of("a", "b")));
    assertFalse(list.containsAll(List.of("a", "c")));
    assertFalse(list.isEmpty());
    session.rollback();
  }

  /** All three {@code toArray} overloads round-trip. */
  @Test
  public void toArrayOverloads() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");

    assertArrayEquals(new Object[] {"a", "b"}, list.toArray());
    assertArrayEquals(new String[] {"a", "b"}, list.toArray(new String[0]));
    assertArrayEquals(new String[] {"a", "b"}, list.toArray(String[]::new));
    session.rollback();
  }

  /** {@code sort} delegates to the backing list. The sort is in-place and emits no events. */
  @Test
  public void sortDelegatesToBackingList() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("c");
    list.add("a");
    list.add("b");
    list.enableTracking(doc);

    list.sort(Comparator.naturalOrder());
    assertEquals(List.of("a", "b", "c"), list);
    // sort is structural but goes around the wrapper — no events
    final var timeline = list.getTimeLine();
    assertTrue(timeline == null
        || timeline.getMultiValueChangeEvents() == null
        || timeline.getMultiValueChangeEvents().isEmpty());
    session.rollback();
  }

  /** {@code reversed}, {@code spliterator}, {@code stream}, {@code parallelStream}. */
  @Test
  public void streamSpliteratorReversed() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    list.add("a");
    list.add("b");

    assertEquals(List.of("b", "a"), list.reversed());
    assertEquals(2, list.spliterator().getExactSizeIfKnown());
    assertEquals(List.of("a", "b"), list.stream().toList());
    assertEquals(2L, list.parallelStream().count());
    final var collected = new ArrayList<String>();
    list.forEach(collected::add);
    assertEquals(List.of("a", "b"), collected);
    session.rollback();
  }

  /** {@code isEmbeddedContainer} is always true for the embedded list. */
  @Test
  public void isEmbeddedContainerIsTrue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityEmbeddedListImpl<String>(doc);
    assertTrue(list.isEmbeddedContainer());
    session.rollback();
  }
}
