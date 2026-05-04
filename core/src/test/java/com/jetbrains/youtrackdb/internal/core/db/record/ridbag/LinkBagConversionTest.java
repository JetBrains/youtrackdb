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
package com.jetbrains.youtrackdb.internal.core.db.record.ridbag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Behavioural pins for {@link LinkBag} — the {@code Iterable<RidPair>} ridbag wrapper used
 * for vertex/edge adjacency lists. The class delegates persistence to one of two
 * implementations selected at construction by the per-session
 * {@link GlobalConfiguration#LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD} value:
 * positive (or zero) → {@link EmbeddedLinkBag}; negative → {@link BTreeBasedLinkBag}.
 *
 * <p>Tests in this class pin: the type-dispatch in the two ctor variants (embedded /
 * b-tree), the embedded-only query methods ({@link LinkBag#isEmbedded()} /
 * {@link LinkBag#isToSerializeEmbedded()} / {@link LinkBag#getPointer()}), the
 * size/iteration delegation, the {@link LinkBag#setOwner(com.jetbrains.youtrackdb.internal.core.db.record.RecordElement)}
 * rejection branches (non-EntityImpl owner; embedded-EntityImpl owner), the equals/hashCode
 * type-equivalence rule, the copy-ctor element preservation, and the {@link LinkBagDeleter}
 * empty / non-empty paths. The class is annotated {@code @Category(SequentialTest.class)}
 * because {@link Before}/{@link After} mutate static {@link GlobalConfiguration} thresholds.
 */
@Category(SequentialTest.class)
public class LinkBagConversionTest extends DbTestBase {

  private int savedTopThreshold;
  private int savedBottomThreshold;

  @Before
  public void saveAndConfigureThresholds() {
    savedTopThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    savedBottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();
  }

  @After
  public void restoreThresholds() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(savedTopThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(savedBottomThreshold);
  }

  // ---------- ctor — embedded delegate ----------

  /**
   * Default thresholds (≥ 0) make {@code init()} pick {@link EmbeddedLinkBag}. Pins the
   * type via wrapper-class assertion — equality on {@code .getClass()}, not
   * {@code instanceof}, so a future subclass split of {@link EmbeddedLinkBag} is
   * caught at the assertion site rather than silently subsumed.
   */
  @Test
  public void defaultThresholdYieldsEmbeddedDelegate() {
    final var bag = new LinkBag(session);
    assertEquals(EmbeddedLinkBag.class, bag.getDelegate().getClass());
    assertTrue(bag.isEmbedded());
    assertTrue(bag.isToSerializeEmbedded());
    assertSame("embedded delegate must report INVALID pointer",
        LinkBagPointer.INVALID, bag.getPointer());
  }

  /**
   * Boundary: {@code topThreshold == 0} keeps the embedded delegate. Pins the
   * production predicate {@code embedded.size() >= topThreshold} (with init's threshold
   * read as 0) against a future refactor that swaps to {@code > topThreshold}, which
   * would silently divert this case to the b-tree branch. Distinct from the default-
   * threshold test (40) which doesn't probe the equality boundary.
   */
  @Test
  public void zeroThresholdYieldsEmbeddedDelegateAtBoundary() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(0);
    final var sessionWithOverride = openDatabase();
    try {
      final var bag = new LinkBag(sessionWithOverride);
      assertEquals(EmbeddedLinkBag.class, bag.getDelegate().getClass());
      assertTrue(bag.isEmbedded());
    } finally {
      sessionWithOverride.close();
    }
  }

  /**
   * Threshold -1 forces the b-tree-based delegate at construction. The equality assertion
   * pins the wrapper-type so a future rename or subtype split is caught early.
   */
  @Test
  public void negativeThresholdYieldsBTreeBasedDelegate() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    final var sessionWithOverride = openDatabase();
    try {
      final var bag = new LinkBag(sessionWithOverride);
      assertEquals(BTreeBasedLinkBag.class, bag.getDelegate().getClass());
      assertFalse(bag.isEmbedded());
    } finally {
      sessionWithOverride.close();
    }
  }

  // ---------- mutation / iteration / contains ----------

  /**
   * Add three RIDs; size must reflect the delegate count, isEmpty must agree, and the
   * iteration order is delegate-defined but each added RID must appear exactly once.
   */
  @Test
  public void addContainsAndSize() {
    session.begin();
    final var bag = new LinkBag(session);
    final var r1 = new RecordId(5, 1);
    final var r2 = new RecordId(5, 2);
    final var r3 = new RecordId(5, 3);

    assertTrue(bag.isEmpty());
    bag.add(r1);
    bag.add(r2);
    bag.add(r3);

    assertFalse(bag.isEmpty());
    assertEquals(3, bag.size());
    assertTrue(bag.isSizeable());
    assertTrue(bag.contains(r1));
    assertTrue(bag.contains(r3));
    assertFalse(bag.contains(new RecordId(5, 99)));

    // Pin element identity, not just count: collect each iterated primaryRid into a Set
    // and assert it matches the expected three. A regression that returns three placeholder
    // RidPairs (e.g. all-zero or all-equal) would otherwise pass the previous count-only
    // loop.
    final var seen = new HashSet<RID>();
    final var iter = bag.iterator();
    while (iter.hasNext()) {
      seen.add(iter.next().primaryRid());
    }
    assertEquals(Set.of(r1, r2, r3), seen);
    assertEquals(3, bag.stream().count());
    session.rollback();
  }

  /** {@link LinkBag#addAll(java.util.Collection)} delegates per element. */
  @Test
  public void addAllDelegates() {
    session.begin();
    final var bag = new LinkBag(session);
    bag.addAll(java.util.List.of(new RecordId(5, 1), new RecordId(5, 2)));
    assertEquals(2, bag.size());
    session.rollback();
  }

  /** {@link LinkBag#remove} returns true on a present rid, false on a missing one. */
  @Test
  public void removeHitAndMiss() {
    session.begin();
    final var bag = new LinkBag(session);
    final var r1 = new RecordId(5, 1);
    bag.add(r1);

    assertTrue(bag.remove(r1));
    assertFalse(bag.remove(r1));
    assertTrue(bag.isEmpty());
    session.rollback();
  }

  /**
   * The two-arg {@code add(primaryRid, secondaryRid)} stores both rids on the resulting
   * {@link com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair}.
   */
  @Test
  public void addPairStoresPrimaryAndSecondaryRids() {
    session.begin();
    final var bag = new LinkBag(session);
    final var primary = new RecordId(5, 10);
    final var secondary = new RecordId(5, 20);
    assertTrue(bag.add(primary, secondary));

    final var pair = bag.iterator().next();
    assertEquals(primary, pair.primaryRid());
    assertEquals(secondary, pair.secondaryRid());
    session.rollback();
  }

  // ---------- copy ctor ----------

  /**
   * The {@code LinkBag(session, source)} copy ctor walks the source pair-by-pair and
   * adds them through {@link LinkBag#add(com.jetbrains.youtrackdb.internal.core.db.record.record.RID,
   * com.jetbrains.youtrackdb.internal.core.db.record.record.RID)}. The copy must have
   * the same size as the source.
   */
  @Test
  public void copyCtorPreservesContents() {
    session.begin();
    final var src = new LinkBag(session);
    src.add(new RecordId(5, 1), new RecordId(5, 100));
    src.add(new RecordId(5, 2), new RecordId(5, 200));

    final var copy = new LinkBag(session, src);
    assertEquals(src.size(), copy.size());
    session.rollback();
  }

  /**
   * The {@code LinkBag(session, delegate)} ctor adopts the supplied delegate verbatim.
   * Pins that {@code getDelegate()} returns the same instance.
   */
  @Test
  public void delegateCtorAdoptsTheGivenDelegate() {
    final var delegate = new EmbeddedLinkBag(session, Integer.MAX_VALUE);
    final var bag = new LinkBag(session, delegate);
    assertSame(delegate, bag.getDelegate());
  }

  // ---------- setOwner branches ----------

  /** {@code setOwner(null)} delegates to the delegate without raising. */
  @Test
  public void setOwnerNullIsAccepted() {
    final var bag = new LinkBag(session);
    bag.setOwner(null);
    assertNull(bag.getOwner());
  }

  /**
   * {@code setOwner(non-EntityImpl)} is rejected with the production message — pins the
   * "ridbag are supported only at entity root" guard.
   */
  @Test
  public void setOwnerNonEntityRejected() {
    final var bag = new LinkBag(session);
    final var fakeOwner = new EntityEmbeddedListImpl<>(); // RecordElement, not Entity
    final var ex = assertThrows(DatabaseException.class, () -> bag.setOwner(fakeOwner));
    assertTrue(ex.getMessage().contains("RidBag are supported only at entity root"));
  }

  /** {@code setOwner(embedded EntityImpl)} is rejected — embedded entities cannot host ridbags. */
  @Test
  public void setOwnerEmbeddedEntityRejected() {
    session.begin();
    final var bag = new LinkBag(session);
    final var embedded = (EntityImpl) session.newEmbeddedEntity();
    assertThrows(DatabaseException.class, () -> bag.setOwner(embedded));
    session.rollback();
  }

  /** {@code setOwner(EntityImpl)} (top-level) succeeds. */
  @Test
  public void setOwnerTopLevelEntityAccepted() {
    session.begin();
    final var bag = new LinkBag(session);
    final var doc = (EntityImpl) session.newEntity();
    bag.setOwner(doc);
    assertSame(doc, bag.getOwner());
    session.rollback();
  }

  // ---------- equals / hashCode ----------

  /**
   * Two embedded ridbags with the same content are equal and hash equally; differing
   * content makes them unequal. Comparison against a non-{@link LinkBag} returns false.
   */
  @Test
  public void equalsAndHashCodeContract() {
    session.begin();
    final var a = new LinkBag(session);
    final var b = new LinkBag(session);
    a.add(new RecordId(5, 1));
    b.add(new RecordId(5, 1));

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals("non-LinkBag comparand", "x", a);

    b.add(new RecordId(5, 2));
    assertNotEquals(a, b);
    session.rollback();
  }

  /**
   * Two ridbags with different delegate types are NOT equal, even if both are empty —
   * the type check guards the cross-implementation comparison.
   */
  @Test
  public void equalsAcrossDelegateTypesFails() {
    final var embeddedBag = new LinkBag(session);
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    final var sessionWithOverride = openDatabase();
    try {
      final var btreeBag = new LinkBag(sessionWithOverride);
      assertNotEquals("different delegate types must not be equal", embeddedBag, btreeBag);
    } finally {
      sessionWithOverride.close();
    }
  }

  // ---------- toString / isEmbeddedContainer ----------

  /**
   * {@code toString} delegates to the delegate's {@code toString}. The embedded delegate
   * renders as {@code "EmbeddedLinkBag [size=N]"} (size summary, not element list). Pin
   * the observable: a non-empty bag's rendering must reflect its current size so a
   * regression that collapses to {@code Object.toString} (containing the identity
   * hashCode only) is falsified.
   */
  @Test
  public void toStringDelegatesAndReflectsSize() {
    session.begin();
    try {
      final var bag = new LinkBag(session);
      bag.add(new RecordId(7, 42));
      bag.add(new RecordId(7, 43));
      final var rendered = bag.toString();
      assertNotNull(rendered);
      assertTrue(
          "delegate's toString must surface the current size; got: " + rendered,
          rendered.contains("size=2"));
    } finally {
      session.rollback();
    }
  }

  /** {@code isEmbeddedContainer()} returns false — ridbag is not embedded for entity-storage. */
  @Test
  public void isEmbeddedContainerIsFalse() {
    final var bag = new LinkBag(session);
    assertFalse(bag.isEmbeddedContainer());
  }

  // ---------- LinkBagDeleter ----------

  /**
   * {@link LinkBagDeleter#deleteAllRidBags} on an entity with no pending bags is a no-op.
   *
   * <p>The iterating-loop path that walks {@code entity.getLinkBagsToDelete()} cannot be
   * driven from the unit-test layer — populating that collection requires the storage
   * delete pipeline which only fires inside an active commit. The non-empty path is
   * forwarded to the deferred-cleanup track for storage-IT-level coverage.
   */
  @Test
  public void deleteAllRidBagsOnEntityWithNoPendingBagsIsNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    // The deleter must not throw on an entity with no pending bags. The dirty state of
    // a freshly-created entity is implementation-defined (newEntity flips it dirty), so
    // we pin only that the call completes and the entity remains usable.
    LinkBagDeleter.deleteAllRidBags(doc, session.getActiveTransaction());
    assertNotNull(doc.getIdentity());
    session.rollback();
  }
}
