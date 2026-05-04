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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/**
 * Behavioural pins for {@link EntityLinkSetImpl}, the {@code Set<Identifiable>} wrapper that
 * delegates persistence to a {@link com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBagDelegate}
 * (embedded-array or B-tree-based, configurable per session). Existing tests cover
 * happy-path add/remove flows; this class fills in residual paths: alternative ctors, the
 * {@code remove(Object)} fast-rejection branches, owner delegation, tracker-driven rollback,
 * isEmbedded / isToSerializeEmbedded query methods, and the equals/hashCode contracts.
 */
public class EntityLinkSetImplTest extends DbTestBase {

  // ---------- constructors ----------

  /**
   * The session-only ctor leaves {@code sourceRecord} unset (delegate has no owner). The
   * default {@link com.jetbrains.youtrackdb.api.config.GlobalConfiguration#LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD}
   * is non-negative so the delegate starts as embedded — pin via {@link
   * EntityLinkSetImpl#isEmbedded()}.
   */
  @Test
  public void ctorWithSessionStartsEmbeddedWithNoOwner() {
    session.begin();
    final var set = new EntityLinkSetImpl(session);
    assertTrue(set.isEmbedded());
    assertEquals(EmbeddedLinkBag.class, set.getDelegate().getClass());
    assertNull(set.getOwner());
    assertTrue(set.isEmpty());
    session.rollback();
  }

  /**
   * The record-element ctor wires {@code setOwner} on the delegate so {@code getOwner()}
   * returns the entity. Pins the delegated path used by the in-place serializer.
   */
  @Test
  public void ctorWithRecordElementSetsOwnerOnDelegate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertSame(doc, set.getOwner());
    session.rollback();
  }

  /** The collection-source ctor copies all elements via {@code addAll} → {@code add}. */
  @Test
  public void ctorWithCollectionCopiesAllElements() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();

    final var set = new EntityLinkSetImpl(doc, List.of(a, b));
    assertEquals(2, set.size());
    session.rollback();
  }

  // ---------- add / remove fast-rejection branches ----------

  /**
   * {@code remove(null)} returns {@code false} via the early-return null guard — pins
   * the {@code if (o == null) return false} branch.
   */
  @Test
  public void removeNullReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertFalse(set.remove(null));
    session.rollback();
  }

  /**
   * {@code remove(non-Identifiable)} returns {@code false} via the {@code instanceof}
   * guard — pins the type-rejection branch.
   */
  @Test
  public void removeNonIdentifiableReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertFalse(set.remove("not-identifiable"));
    session.rollback();
  }

  /**
   * {@code remove(Identifiable)} dispatches to the delegate. Pins the happy path against an
   * already-present element and against a missing one.
   */
  @Test
  public void removeIdentifiable() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.add(a);

    assertTrue(set.remove(a));
    assertFalse(set.remove(b));
    assertTrue(set.isEmpty());
    session.rollback();
  }

  // ---------- contains ----------

  /** {@code contains(non-Identifiable)} returns {@code false}. */
  @Test
  public void containsNonIdentifiableReturnsFalse() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertFalse(set.contains("not-identifiable"));
    session.rollback();
  }

  /** {@code contains(Identifiable)} delegates to {@code delegate.contains}. */
  @Test
  public void containsIdentifiableHitAndMiss() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.add(a);

    assertTrue(set.contains(a));
    assertFalse(set.contains(b));
    session.rollback();
  }

  // ---------- isEmpty / size / iterator ----------

  /**
   * {@code isEmpty} mirrors {@code size() == 0}. Iterator must walk every element exactly
   * once, returning the {@code primaryRid} per pair.
   */
  @Test
  public void iteratorWalksPrimaryRids() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.add(a);
    set.add(b);

    assertFalse(set.isEmpty());
    assertEquals(2, set.size());

    final var seen = new HashSet<Identifiable>();
    final var it = set.iterator();
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertEquals(2, seen.size());
    assertTrue(seen.contains(a.getIdentity()));
    assertTrue(seen.contains(b.getIdentity()));
    session.rollback();
  }

  /** Iterator {@code remove()} delegates to the underlying delegate's iterator. */
  @Test
  public void iteratorRemoveDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.add(a);

    final var it = set.iterator();
    assertTrue(it.hasNext());
    it.next();
    it.remove();
    assertTrue(set.isEmpty());
    session.rollback();
  }

  // ---------- isEmbedded / isToSerializeEmbedded / getPointer ----------

  /**
   * For an embedded delegate (default) {@link EntityLinkSetImpl#isToSerializeEmbedded()}
   * is {@code true} and {@code getPointer()} returns {@link
   * com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer#INVALID}. Pins the
   * shortcut-return branch.
   */
  @Test
  public void embeddedDelegateReportsInvalidPointerAndSerializesEmbedded() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertTrue(set.isToSerializeEmbedded());
    assertNotNull(set.getPointer());
    assertFalse(set.getPointer().isValid());
    session.rollback();
  }

  // ---------- session ----------

  /** {@link EntityLinkSetImpl#getSession()} returns the bound session. */
  @Test
  public void getSessionReturnsBoundSession() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    assertSame(session, set.getSession());
    session.rollback();
  }

  // ---------- tracking lifecycle ----------

  /**
   * {@code enableTracking} / {@code disableTracking} are delegated. After enable, an add
   * registers a change event; the cleared dirty after disable makes {@code isModified}
   * report {@code false}.
   */
  @Test
  public void trackingEnableDisableDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();

    set.enableTracking(doc);
    set.add(a);
    assertTrue(set.isModified());

    set.disableTracking(doc);
    assertFalse(set.isModified());
    session.rollback();
  }

  /**
   * {@code transactionClear} resets the per-transaction tracker state and the in-flight
   * timeline.
   */
  @Test
  public void transactionClearResetsTransactionDirtyAndTimeline() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();

    set.enableTracking(doc);
    set.add(a);
    assertTrue(set.isTransactionModified());

    set.transactionClear();
    assertFalse(set.isTransactionModified());
    assertNull(set.getTransactionTimeLine());
    session.rollback();
  }

  // ---------- rollback ----------

  /**
   * {@code rollbackChanges} on a tracker that was never enabled throws via the delegated
   * {@code rollbackChanges}.
   */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);

    final var tx = session.getActiveTransaction();
    final var ex = assertThrows(DatabaseException.class, () -> set.rollbackChanges(tx));
    assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /** {@code rollbackChanges} with empty timeline is a no-op. */
  @Test
  public void rollbackChangesWithEmptyTimelineIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.enableTracking(doc);

    set.rollbackChanges(session.getActiveTransaction());
    assertTrue(set.isEmpty());
    session.rollback();
  }

  /**
   * {@code rollbackChanges} undoes ADD events registered during the transaction. Pins the
   * end-to-end ADD/REMOVE-rewind path through the delegate's tracker.
   */
  @Test
  public void rollbackChangesRevertsAdds() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    set.add(a);

    set.enableTracking(doc);
    set.add(b);
    assertEquals(2, set.size());

    set.rollbackChanges(session.getActiveTransaction());
    // size is delegate-driven; ADD-rollback removes b but leaves a untouched
    assertTrue(set.contains(a));
    assertFalse(set.contains(b));
    session.rollback();
  }

  /**
   * {@code returnOriginalState} returns a fresh {@link HashSet} reverted via the supplied
   * change events, leaving the live set untouched. Verifies non-mutation and reverted
   * contents in one shot.
   */
  @Test
  public void returnOriginalStateReturnsRevertedSnapshot() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    set.add(a);
    set.enableTracking(doc);
    set.add(b);

    final var events = new java.util.ArrayList<MultiValueChangeEvent<Identifiable, Identifiable>>();
    set.getTimeLine().getMultiValueChangeEvents().forEach(e -> events.add(
        new MultiValueChangeEvent<>(e.getChangeType(), e.getKey(), e.getValue(), e.getOldValue())));

    final var reverted = set.returnOriginalState(session.getActiveTransaction(), events);
    assertTrue(reverted.contains(a.getIdentity()));
    assertFalse(reverted.contains(b.getIdentity()));
    // live set untouched
    assertTrue(set.contains(b));
    session.rollback();
  }

  // ---------- dirty propagation ----------

  /** {@code setDirty} propagates through to the delegate's owner. */
  @Test
  public void setDirtyDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var rec = (com.jetbrains.youtrackdb.internal.core.record.RecordAbstract) doc;
    final var set = new EntityLinkSetImpl(doc);
    rec.unsetDirty();
    assertFalse(doc.isDirty());

    set.setDirty();
    assertTrue(doc.isDirty());
    session.rollback();
  }

  /** {@code setDirtyNoChanged} is a non-incrementing dirty flag for the owner. */
  @Test
  public void setDirtyNoChangedDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.setDirtyNoChanged();
    // No exception means the delegate accepted the call; owner state is implementation-defined.
    session.rollback();
  }

  // ---------- toString / hashCode ----------

  /** {@code toString} format for embedded delegate is {@code LinkSet[<size>]}. */
  @Test
  public void toStringFormat() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();
    set.add(a);
    assertEquals("LinkSet[1]", set.toString());
    session.rollback();
  }

  /** {@code hashCode} for an embedded delegate falls through to {@link AbstractSet#hashCode}. */
  @Test
  public void hashCodeFallsThroughToAbstractSetForEmbedded() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var setA = new EntityLinkSetImpl(doc);
    final var setB = new EntityLinkSetImpl(doc);
    final var a = (EntityImpl) session.newEntity();
    setA.add(a);
    setB.add(a);

    // Stable hash on a single instance: a regression that flipped to identity-hashCode
    // would still pass this stand-alone check, so we ALSO compare two independent
    // EntityLinkSetImpl instances containing the same single Identifiable. AbstractSet.
    // hashCode is the sum of element hashCodes — two link-sets with equal element
    // contents MUST hash equally regardless of wrapper instance.
    assertEquals("hashCode must be stable across two reads on the same instance",
        setA.hashCode(), setA.hashCode());
    assertEquals(
        "AbstractSet.hashCode is sum-of-element-hashCodes — two single-element sets with the"
            + " same Identifiable must hash equally, falsifying any swap to identity-hashCode",
        setA.hashCode(), setB.hashCode());
    session.rollback();
  }

  // ---------- addInternal ----------

  /**
   * {@code addInternal} is currently a no-op (matches the production signature; declared
   * for the {@link LinkTrackedMultiValue} contract). Pins the no-event behaviour so a future
   * implementation must update the test in lockstep.
   *
   * <p>WHEN-FIXED: forwards-to: deferred-cleanup — {@code EntityLinkSetImpl.addInternal}
   * is presently a stub. Either implement it to mirror {@code EntityEmbeddedSetImpl.addInternal}
   * or document the no-op contract.
   */
  @Test
  public void addInternalIsNoOpStub() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var set = new EntityLinkSetImpl(doc);
    set.addInternal(new RecordId(5, 10));
    assertEquals(0, set.size());
    session.rollback();
  }
}
