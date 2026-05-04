package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class EmbeddedSetTest extends DbTestBase {

  @Test
  public void testAddOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.enableTracking(doc);
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.ADD, "value1", "value1", null);
    trackedSet.add("value1");
    Assert.assertEquals(event, trackedSet.getTimeLine().getMultiValueChangeEvents().getFirst());
    Assert.assertTrue(trackedSet.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedSet);
    trackedSet.add("value1");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.enableTracking(doc);
    trackedSet.addInternal("value1");

    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testAddFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);

    trackedSet.add("value1");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.disableTracking(doc);
    trackedSet.enableTracking(doc);

    trackedSet.add("value1");
    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.enableTracking(doc);
    trackedSet.remove("value2");
    var event =
        new MultiValueChangeEvent<Object, Object>(
            ChangeType.REMOVE, "value2", null, "value2");
    Assert.assertEquals(trackedSet.getTimeLine().getMultiValueChangeEvents().getFirst(), event);
    Assert.assertTrue(trackedSet.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationTwo() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    doc.setPropertyInternal("tracked", trackedSet);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.remove("value2");
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testRemoveNotificationFour() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());
    trackedSet.disableTracking(doc);
    trackedSet.enableTracking(doc);
    trackedSet.remove("value5");
    Assert.assertFalse(trackedSet.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearOne() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    final List<MultiValueChangeEvent<String, String>> firedEvents = new ArrayList<>();
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value1", null, "value1"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value2", null, "value2"));
    firedEvents.add(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, "value3", null, "value3"));

    trackedSet.enableTracking(doc);
    trackedSet.clear();

    Assert.assertEquals(new HashSet<>(firedEvents),
        new HashSet<>(trackedSet.getTimeLine().getMultiValueChangeEvents()));
    Assert.assertTrue(trackedSet.isModified());
    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testClearThree() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec1 = (RecordAbstract) doc;
    rec1.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    final var trackedSet = new EntityEmbeddedSetImpl<String>(
        doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");

    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    Assert.assertFalse(doc.isDirty());

    trackedSet.clear();

    Assert.assertTrue(doc.isDirty());
    session.rollback();
  }

  @Test
  public void testReturnOriginalState() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();

    final var trackedSet = new EntityEmbeddedSetImpl<String>(doc);
    trackedSet.add("value1");
    trackedSet.add("value2");
    trackedSet.add("value3");
    trackedSet.add("value4");
    trackedSet.add("value5");

    final Set<String> original = new HashSet<>(trackedSet);
    trackedSet.enableTracking(doc);
    trackedSet.add("value6");
    trackedSet.remove("value2");
    trackedSet.remove("value5");
    trackedSet.add("value7");
    trackedSet.add("value8");
    trackedSet.remove("value7");
    trackedSet.add("value9");
    trackedSet.add("value10");

    Assert.assertEquals(
        original,
        trackedSet.returnOriginalState(session.getActiveTransaction(),
            trackedSet.getTimeLine().getMultiValueChangeEvents()));
    session.rollback();
  }

  @Test
  public void testRollBackChangesAfterCallback() {
    session.begin();
    final var entity = (EntityImpl) session.newEntity();

    final var originalSet = new HashSet<String>();
    originalSet.add("value1");
    originalSet.add("value2");
    originalSet.add("value3");
    originalSet.add("value4");
    originalSet.add("value5");

    var entitySet = entity.newEmbeddedSet("embeddedSet");
    entitySet.addAll(originalSet);

    var tx = (FrontendTransactionImpl) session.getTransactionInternal();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    entitySet.add("value6");
    entitySet.remove("value2");
    entitySet.remove("value5");
    entitySet.add("value7");
    entitySet.add("value8");
    entitySet.remove("value7");
    entitySet.add("value9");
    entitySet.add("value10");

    ((EntityEmbeddedSetImpl<?>) entitySet).rollbackChanges(tx);

    Assert.assertEquals(originalSet, entitySet);
    session.rollback();
  }

  @Test
  public void testRollBackAllChanges() {
    session.begin();
    final var entity = (EntityImpl) session.newEntity();

    final var originalSet = new HashSet<String>();
    originalSet.add("value1");
    originalSet.add("value2");
    originalSet.add("value3");
    originalSet.add("value4");
    originalSet.add("value5");

    var entitySet = entity.newEmbeddedSet("embeddedSet");
    entitySet.addAll(originalSet);

    entitySet.add("value6");
    entitySet.remove("value2");
    entitySet.remove("value5");
    entitySet.add("value7");
    entitySet.add("value8");
    entitySet.remove("value7");
    entitySet.add("value9");
    entitySet.add("value10");

    ((EntityEmbeddedSetImpl<?>) entitySet).rollbackChanges(session.getActiveTransaction());

    Assert.assertTrue(entitySet.isEmpty());
    session.rollback();
  }

  @Test
  public void testStackOverflowOnRecursion() {
    try {
      session.executeInTx(transaction -> {
        final var entity = (EntityImpl) session.newEmbeddedEntity();
        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        final var trackedSet = new EntityEmbeddedSetImpl<EntityImpl>(
            entity);
        trackedSet.add(entity);
        Assert.fail();
      });
    } catch (IllegalStateException e) {
      // Expected exception
    }

  }

  // ---------- residual coverage: ctor / setOwner / iterator / delegations ----------

  /**
   * Default no-record ctor enables tracking immediately; mutations are tracked even
   * without a parent record (used by deserialization).
   */
  @Test
  public void defaultCtorEnablesTracking() {
    final var set = new EntityEmbeddedSetImpl<String>();
    set.add("v");
    Assert.assertTrue(set.isModified());
  }

  /** Size-presizing default ctor — same observable behaviour as the no-arg overload. */
  @Test
  public void defaultSizeCtor() {
    final var set = new EntityEmbeddedSetImpl<String>(8);
    set.add("v");
    Assert.assertTrue(set.isModified());
  }

  /** Size-presizing record ctor — same behaviour as the unsized record ctor. */
  @Test
  public void recordSizeCtor() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc, 8);
    Assert.assertSame(doc, set.getOwner());
    Assert.assertTrue(set.isEmpty());
    session.rollback();
  }

  /** {@code setOwner(null)} clears the owner; subsequent mutations don't propagate dirty. */
  @Test
  public void setOwnerNullClears() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.setOwner(null);
    Assert.assertNull(set.getOwner());
    session.rollback();
  }

  /** {@code setOwner} to a different non-null entity is rejected. */
  @Test
  public void setOwnerToDifferentEntityRejected() {
    session.begin();
    final var doc1 = (EntityImpl) session.newEntity();
    final var doc2 = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc1);
    final var ex = Assert.assertThrows(
        IllegalStateException.class, () -> set.setOwner(doc2));
    Assert.assertTrue(ex.getMessage().startsWith(
        "This set is already owned by data container"));
    session.rollback();
  }

  /**
   * {@code addInternal} skips event emission and dirty propagation. Adding an existing
   * value is a no-op (set semantics).
   */
  @Test
  public void addInternalDoesNotEmitEventsAndDuplicateIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc;
    rec.unsetDirty();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.enableTracking(doc);
    set.addInternal("v");
    set.addInternal("v"); // duplicate — set.add returns false → no owner shuffle

    Assert.assertEquals(1, set.size());
    Assert.assertFalse(set.isModified());
    Assert.assertFalse(doc.isDirty());
    session.rollback();
  }

  /** {@code remove(missing)} is a silent no-op. */
  @Test
  public void removeMissingValueIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.add("a");
    // pre-enable add() flipped the local dirty flag via setDirty; disable/enable cycle clears it.
    set.disableTracking(doc);
    set.enableTracking(doc);

    Assert.assertFalse(set.remove("missing"));
    Assert.assertFalse(set.isModified());
    session.rollback();
  }

  /** Iterator's {@code remove} routes through {@code removeEvent} on the current element. */
  @Test
  public void iteratorRemoveEmitsRemoveEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.add("a");
    set.enableTracking(doc);

    final var it = set.iterator();
    Assert.assertTrue(it.hasNext());
    it.next();
    it.remove();

    Assert.assertTrue(set.isEmpty());
    Assert.assertEquals(ChangeType.REMOVE,
        set.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  /** {@code rollbackChanges} on a non-tracking set throws. */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    final var tx = session.getActiveTransaction();
    final var ex = Assert.assertThrows(
        DatabaseException.class, () -> set.rollbackChanges(tx));
    Assert.assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /** {@code rollbackChanges} on an empty timeline is a no-op. */
  @Test
  public void rollbackChangesEmptyTimelineNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.enableTracking(doc);

    set.rollbackChanges(session.getActiveTransaction());
    Assert.assertTrue(set.isEmpty());
    session.rollback();
  }

  /** {@code transactionClear} resets per-transaction tracking. */
  @Test
  public void transactionClearResetsTransactionDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.enableTracking(doc);
    set.add("a");
    Assert.assertTrue(set.isTransactionModified());

    set.transactionClear();
    Assert.assertFalse(set.isTransactionModified());
    Assert.assertNull(set.getTransactionTimeLine());
    session.rollback();
  }

  /** {@code disableTracking} clears the local dirty flag. */
  @Test
  public void disableTrackingClearsLocalDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.enableTracking(doc);
    set.add("a");
    Assert.assertTrue(set.isModified());

    set.disableTracking(doc);
    Assert.assertFalse(set.isModified());
    session.rollback();
  }

  /** Stream / parallelStream / spliterator delegations. */
  @Test
  public void streamSpliteratorAndForEach() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.add("a");
    set.add("b");

    Assert.assertEquals(2L, set.stream().count());
    Assert.assertEquals(2L, set.parallelStream().count());
    Assert.assertEquals(2L, set.spliterator().getExactSizeIfKnown());

    final Set<String> seen = new HashSet<>();
    set.forEach(seen::add);
    Assert.assertEquals(2, seen.size());
    session.rollback();
  }

  /** {@code toArray} overloads (no-arg, typed-array, IntFunction generator). */
  @Test
  public void toArrayOverloads() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.add("a");

    Assert.assertEquals(1, set.toArray().length);
    Assert.assertEquals("a", set.toArray(new String[0])[0]);
    Assert.assertEquals("a", set.toArray(String[]::new)[0]);
    session.rollback();
  }

  /** {@code contains} / {@code containsAll} / {@code isEmpty}. */
  @Test
  public void containsAndContainsAllAndIsEmpty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    Assert.assertTrue(set.isEmpty());
    set.add("a");
    set.add("b");

    Assert.assertTrue(set.contains("a"));
    Assert.assertFalse(set.contains("c"));
    Assert.assertTrue(set.containsAll(List.of("a", "b")));
    Assert.assertFalse(set.containsAll(List.of("a", "c")));
    session.rollback();
  }

  /** {@code equals} / {@code hashCode} / {@code toString} delegate to backing set. */
  @Test
  public void equalsHashCodeToStringDelegate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    set.add("a");

    Assert.assertEquals(set, set);
    Assert.assertEquals(Set.of("a"), set);
    Assert.assertNotEquals("not a set", set);
    Assert.assertEquals(Set.of("a").hashCode(), set.hashCode());
    // Pin the exact toString format: "[a]" matches the AbstractCollection format. A
    // weaker contains("a") check would pass for unrelated layouts (e.g. an identity
    // hashCode embedded in the default Object.toString).
    Assert.assertEquals(Set.of("a").toString(), set.toString());
    session.rollback();
  }

  /** {@code isEmbeddedContainer} is always true for embedded sets. */
  @Test
  public void isEmbeddedContainerIsTrue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityEmbeddedSetImpl<String>(doc);
    Assert.assertTrue(set.isEmbeddedContainer());
    session.rollback();
  }
}
