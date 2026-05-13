package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class LinkListTest extends DbTestBase {

  @Test
  public void testRollBackChangesOne() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<Identifiable>();
    var entity1 = session.newEntity();
    originalList.add(entity1);

    var entity2 = session.newEntity();
    originalList.add(entity2);

    var entity3 = session.newEntity();
    originalList.add(entity3);

    var entity4 = session.newEntity();
    originalList.add(entity4);

    var entity5 = session.newEntity();
    originalList.add(entity5);

    var trackedList = entity.newLinkList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    trackedList.add(entity6);

    var entity7 = session.newEntity();
    trackedList.add(entity7);

    var entity10 = session.newEntity();
    trackedList.set(2, entity10);

    var entity8 = session.newEntity();
    trackedList.add(1, entity8);
    trackedList.add(1, entity8);

    trackedList.remove(3);
    trackedList.remove(entity7);

    var entity9 = session.newEntity();
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);

    trackedList.remove(entity9);
    trackedList.remove(entity9);

    var entity11 = session.newEntity();
    trackedList.add(4, entity11);

    ((EntityLinkListImpl) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }

  @Test
  public void testRollBackChangesTwo() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<Identifiable>();

    var entity1 = session.newEntity();
    originalList.add(entity1);

    var entity2 = session.newEntity();
    originalList.add(entity2);

    var entity3 = session.newEntity();
    originalList.add(entity3);

    var entity4 = session.newEntity();
    originalList.add(entity4);

    var entity5 = session.newEntity();
    originalList.add(entity5);

    var trackedList = entity.newLinkList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    trackedList.add(entity6);

    var entity7 = session.newEntity();
    trackedList.add(entity7);

    var entity10 = session.newEntity();
    trackedList.set(2, entity10);

    var entity8 = session.newEntity();
    trackedList.add(1, entity8);
    trackedList.remove(3);
    trackedList.clear();

    //noinspection RedundantOperationOnEmptyContainer
    trackedList.remove(entity7);

    var entity9 = session.newEntity();
    trackedList.addFirst(entity9);

    var entity11 = session.newEntity();
    trackedList.add(entity11);

    var entity12 = session.newEntity();
    trackedList.addFirst(entity12);
    trackedList.add(entity12);

    ((EntityLinkListImpl) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }

  // ---------- residual coverage for EntityLinkListImpl ----------

  /** Session-only ctor enables tracking immediately and starts empty. */
  @Test
  public void sessionOnlyCtorEnablesTracking() {
    session.begin();
    final var list = new EntityLinkListImpl(session);
    Assert.assertTrue(list.isEmpty());
    final var doc = (EntityImpl) session.newEntity();
    list.add(doc);
    Assert.assertTrue(list.isModified());
    session.rollback();
  }

  /** Session+size ctor — same observable behaviour as the no-size variant. */
  @Test
  public void sessionAndSizeCtor() {
    session.begin();
    final var list = new EntityLinkListImpl(session, 8);
    Assert.assertTrue(list.isEmpty());
    session.rollback();
  }

  /** Record+size ctor — pin the size-presizing branch. */
  @Test
  public void recordAndSizeCtor() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc, 8);
    Assert.assertSame(doc, list.getOwner());
    Assert.assertTrue(list.isEmpty());
    session.rollback();
  }

  /**
   * Origin-collection ctor copies all entries. Pins both the constructor's branch and the
   * ADD-event chain through {@code addAll}.
   */
  @Test
  public void originCollectionCtorCopies() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();

    final var list = new EntityLinkListImpl(doc, java.util.List.of(a, b));
    Assert.assertEquals(2, list.size());
    Assert.assertEquals(a.getIdentity(), list.get(0));
    Assert.assertEquals(b.getIdentity(), list.get(1));
    session.rollback();
  }

  /** Empty origin-collection ctor is a no-op. */
  @Test
  public void originCollectionEmptyIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc, java.util.List.of());
    Assert.assertTrue(list.isEmpty());
    session.rollback();
  }

  /** {@code addInternal} adds without emitting events. */
  @Test
  public void addInternalSkipsEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.enableTracking(doc);
    list.addInternal(a);

    Assert.assertEquals(1, list.size());
    Assert.assertFalse(list.isModified());
    session.rollback();
  }

  /**
   * {@code set(int, T)} on a same-RID replacement does not emit UPDATE — pins the
   * {@code !oldValue.equals(rid)} guard.
   */
  @Test
  public void setSameRidIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    // pre-enable add flipped the local dirty flag; disable/enable cycle clears it.
    list.disableTracking(doc);
    list.enableTracking(doc);

    final var prev = list.set(0, a);
    Assert.assertEquals(a.getIdentity(), prev);
    Assert.assertFalse("equal-RID replacement is not an update", list.isModified());
    session.rollback();
  }

  /** {@code set} with a differing element emits UPDATE. */
  @Test
  public void setDifferingRidEmitsUpdate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    list.enableTracking(doc);

    list.set(0, b);
    Assert.assertEquals(MultiValueChangeEvent.ChangeType.UPDATE,
        list.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /** {@code remove(non-Identifiable)} returns false via the type guard. */
  @Test
  public void removeNonIdentifiableReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    Assert.assertFalse(list.remove("not-identifiable"));
    session.rollback();
  }

  /**
   * {@code remove(embedded entity)} returns false via the
   * {@link com.jetbrains.youtrackdb.internal.core.exception.SchemaException} guard
   * inside {@code checkValue} — pins the swallow-and-return-false branch.
   */
  @Test
  public void removeEmbeddedEntityReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    final var embedded = (EntityImpl) session.newEmbeddedEntity();
    Assert.assertFalse(list.remove(embedded));
    session.rollback();
  }

  /** {@code add(embedded)} throws via the {@link LinkTrackedMultiValue#checkValue} guard. */
  @Test
  public void addEmbeddedEntityThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    final var embedded = (EntityImpl) session.newEmbeddedEntity();
    Assert.assertThrows(SchemaException.class, () -> list.add(embedded));
    session.rollback();
  }

  /** {@code indexOf(non-Identifiable)} returns -1. */
  @Test
  public void indexOfNonIdentifiableReturnsMinusOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    Assert.assertEquals(-1, list.indexOf("not-identifiable"));
    Assert.assertEquals(-1, list.lastIndexOf("not-identifiable"));
    session.rollback();
  }

  /**
   * {@code indexOf(embedded)} catches the {@link SchemaException} via
   * {@code checkValue} and returns -1 — pins the silent-rejection branch (a feature so
   * collections with mixed-type membership probes don't throw).
   */
  @Test
  public void indexOfEmbeddedReturnsMinusOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    final var embedded = (EntityImpl) session.newEmbeddedEntity();
    Assert.assertEquals(-1, list.indexOf(embedded));
    Assert.assertEquals(-1, list.lastIndexOf(embedded));
    Assert.assertFalse(list.contains(embedded));
    session.rollback();
  }

  /** {@code indexOf}/{@code lastIndexOf} happy paths over a real RID. */
  @Test
  public void indexOfAndLastIndexOfHits() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    list.add(b);
    list.add(a);

    Assert.assertEquals(0, list.indexOf(a));
    Assert.assertEquals(2, list.lastIndexOf(a));
    session.rollback();
  }

  /** {@code contains}-by-RID delegates to backing list. */
  @Test
  public void containsHitAndMiss() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    Assert.assertTrue(list.contains(a));
    Assert.assertFalse(list.contains(new RecordId(99, 99)));
    Assert.assertFalse(list.contains("non-identifiable"));
    session.rollback();
  }

  /**
   * Deque-style mutators: addFirst/addLast/getFirst/getLast/removeFirst/removeLast.
   * Bundled into one assertion-rich scenario.
   */
  @Test
  public void dequeOps() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var c = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(b);

    list.addFirst(a);
    list.addLast(c);
    Assert.assertEquals(a.getIdentity(), list.getFirst());
    Assert.assertEquals(c.getIdentity(), list.getLast());

    final var first = list.removeFirst();
    final var last = list.removeLast();
    Assert.assertEquals(a.getIdentity(), first);
    Assert.assertEquals(c.getIdentity(), last);
    Assert.assertEquals(1, list.size());
    session.rollback();
  }

  /** {@code clear} emits one REMOVE event per item in reverse order. */
  @Test
  public void clearEmitsReverseOrderRemoves() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    list.add(b);
    list.enableTracking(doc);

    list.clear();
    Assert.assertTrue(list.isEmpty());
    final var events = list.getTimeLine().getMultiValueChangeEvents();
    Assert.assertEquals(2, events.size());
    Assert.assertEquals(Integer.valueOf(1), events.get(0).getKey());
    Assert.assertEquals(Integer.valueOf(0), events.get(1).getKey());
    session.rollback();
  }

  /** {@code rollbackChanges} on a non-tracking list throws. */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    final var tx = session.getActiveTransaction();
    final var ex = Assert.assertThrows(
        DatabaseException.class, () -> list.rollbackChanges(tx));
    Assert.assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /** {@code rollbackChanges} on an empty timeline is a no-op. */
  @Test
  public void rollbackChangesEmptyTimelineNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.enableTracking(doc);
    list.rollbackChanges(session.getActiveTransaction());
    Assert.assertTrue(list.isEmpty());
    session.rollback();
  }

  /** {@code transactionClear} resets per-transaction tracking. */
  @Test
  public void transactionClearResetsState() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.enableTracking(doc);
    list.add(a);
    Assert.assertTrue(list.isTransactionModified());

    list.transactionClear();
    Assert.assertFalse(list.isTransactionModified());
    Assert.assertNull(list.getTransactionTimeLine());
    session.rollback();
  }

  /** {@code disableTracking} clears local dirty. */
  @Test
  public void disableTrackingClearsLocalDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.enableTracking(doc);
    list.add(a);
    Assert.assertTrue(list.isModified());

    list.disableTracking(doc);
    Assert.assertFalse(list.isModified());
    session.rollback();
  }

  /** {@code setOwner(null)} clears the source record. */
  @Test
  public void setOwnerNullClears() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.setOwner(null);
    Assert.assertNull(list.getOwner());
    session.rollback();
  }

  /** {@code setOwner} to a different non-null entity is rejected. */
  @Test
  public void setOwnerToDifferentEntityRejected() {
    session.begin();
    final var doc1 = (EntityImpl) session.newEntity();
    final var doc2 = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc1);
    final var ex = Assert.assertThrows(
        IllegalStateException.class, () -> list.setOwner(doc2));
    Assert.assertTrue(ex.getMessage().startsWith(
        "This list is already owned by data container"));
    session.rollback();
  }

  /** {@code equals}/{@code hashCode}/{@code toString} delegate to backing list. */
  @Test
  public void equalsHashCodeToStringDelegate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);

    Assert.assertEquals(list, list);
    Assert.assertEquals(java.util.List.of(a.getIdentity()), list);
    Assert.assertNotEquals("non-list comparand", "x", list);
    Assert.assertEquals(java.util.List.of(a.getIdentity()).hashCode(), list.hashCode());
    // Exact toString format pinned — falsifiable against an identity-style or
    // wrapper-class-named override.
    Assert.assertEquals(java.util.List.of(a.getIdentity()).toString(), list.toString());
    session.rollback();
  }

  /** {@code sort} delegates without emitting tracker events. */
  @Test
  public void sortDelegatesWithoutEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(b);
    list.add(a);

    list.sort(Comparator.comparing(Identifiable::getIdentity));
    final var first = list.get(0);
    final var second = list.get(1);
    Assert.assertEquals(2, list.size());
    Assert.assertNotNull(first);
    Assert.assertNotNull(second);
    session.rollback();
  }

  /** {@code toArray} overloads. */
  @Test
  public void toArrayOverloads() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);

    Assert.assertEquals(1, list.toArray().length);
    Assert.assertEquals(1, list.toArray(new Identifiable[0]).length);
    Assert.assertEquals(1, list.toArray(Identifiable[]::new).length);
    session.rollback();
  }

  /** {@code stream}, {@code parallelStream}, {@code spliterator}, {@code forEach}. */
  @Test
  public void streamSpliteratorAndForEach() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);

    Assert.assertEquals(1L, list.stream().count());
    Assert.assertEquals(1L, list.parallelStream().count());
    final var seen = new ArrayList<Identifiable>();
    list.forEach(seen::add);
    Assert.assertEquals(1, seen.size());
    session.rollback();
  }

  /** {@code returnOriginalState} reverts ADD/REMOVE/UPDATE events. */
  @Test
  public void returnOriginalStateRevertsAddRemoveUpdate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var c = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    list.add(a);
    list.add(b);

    final List<Identifiable> snapshot = new ArrayList<>(list);
    list.enableTracking(doc);

    list.add(c);
    list.remove(0);
    list.set(0, c); // UPDATE on what was b

    final var reverted = list.returnOriginalState(
        session.getActiveTransaction(), list.getTimeLine().getMultiValueChangeEvents());
    Assert.assertEquals(snapshot, reverted);
    session.rollback();
  }

  /** {@code getSession} returns the bound session via the weak ref. */
  @Test
  public void getSessionReturnsBoundSession() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    Assert.assertSame(session, list.getSession());
    session.rollback();
  }

  /** {@code isSizeable} is always true. */
  @Test
  public void isSizeableIsTrue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var list = new EntityLinkListImpl(doc);
    Assert.assertTrue(list.isSizeable());
    session.rollback();
  }
}
