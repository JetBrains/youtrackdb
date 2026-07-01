package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit coverage for {@link IndexOverlay}, the transaction-local index-definition delta. These tests
 * exercise the overlay in isolation with mocked {@link Index} handles, so they cover the four
 * categories (created, dropped, renamed, collection-membership), the net-cancellation rules
 * (create-then-drop, add-then-remove), and the effective-set resolution without a full database.
 * The database-integrated behaviour (the routing seam, the snapshot force-rebuild, the planner skip)
 * is covered separately in {@code SchemaDeguardTest}.
 */
public class IndexOverlayTest {

  // Builds a mock index handle carrying a name and, for a class-owned index, a definition whose
  // className is the given class. A null className yields an index with no definition, standing in
  // for a manual/nameless index.
  private static Index indexNamed(String name, String className) {
    var index = Mockito.mock(Index.class);
    Mockito.when(index.getName()).thenReturn(name);
    if (className != null) {
      var def = Mockito.mock(IndexDefinition.class);
      Mockito.when(def.getClassName()).thenReturn(className);
      Mockito.when(index.getDefinition()).thenReturn(def);
    } else {
      Mockito.when(index.getDefinition()).thenReturn(null);
    }
    return index;
  }

  /**
   * A fresh overlay carries no delta, so it is empty and behaviourally identical to no overlay. This
   * is the state the routing seam treats as "resolve against the committed maps unchanged".
   */
  @Test
  public void freshOverlayIsEmpty() {
    var overlay = new IndexOverlay();
    assertTrue("a fresh overlay must be empty", overlay.isEmpty());
    assertTrue("a fresh overlay has no tx-created names", overlay.getTxCreatedNames().isEmpty());
    assertTrue("a fresh overlay has no tx-dropped names", overlay.getTxDroppedNames().isEmpty());
    assertTrue("a fresh overlay has no renames", overlay.getRenamed().isEmpty());
    assertTrue("a fresh overlay has no membership adds", overlay.getMembershipAdded().isEmpty());
    assertTrue("a fresh overlay has no membership removes",
        overlay.getMembershipRemoved().isEmpty());
  }

  /**
   * Recording a created index makes the overlay non-empty, exposes the index by name, and returns the
   * same handle back through the accessors so the commit can build its engine from it.
   */
  @Test
  public void recordCreatedExposesTheHandle() {
    var overlay = new IndexOverlay();
    var idx = indexNamed("C.name", "C");
    overlay.recordCreated(idx);

    assertFalse("an overlay with a created index is not empty", overlay.isEmpty());
    assertTrue("the created name must be reported as tx-created", overlay.isTxCreated("C.name"));
    assertSame("the tx-created handle must be returned unchanged", idx,
        overlay.getTxCreated("C.name"));
    assertTrue("the created name must appear in the tx-created name set",
        overlay.getTxCreatedNames().contains("C.name"));
    assertTrue("the created handle must appear in the tx-created handle set",
        overlay.getTxCreatedIndexes().contains(idx));
    assertNull("an unrelated name must not resolve to a tx-created handle",
        overlay.getTxCreated("Other.name"));
  }

  /**
   * Recording a dropped index that exists only in the committed registry adds it to the tx-dropped
   * set so the effective-set resolution hides it.
   */
  @Test
  public void recordDroppedHidesCommittedIndex() {
    var overlay = new IndexOverlay();
    overlay.recordDropped("Committed.name");

    assertTrue("the dropped name must be reported as tx-dropped",
        overlay.isTxDropped("Committed.name"));
    assertTrue("the dropped name must appear in the tx-dropped name set",
        overlay.getTxDroppedNames().contains("Committed.name"));
    assertFalse("an unrelated name is not dropped", overlay.isTxDropped("Other.name"));
  }

  /**
   * A create-then-drop of the same name inside one transaction cancels: the never-committed create is
   * simply removed, and the name is NOT recorded as a drop (there is nothing in the committed
   * registry to hide). The overlay returns to carrying no index for that name.
   */
  @Test
  public void createThenDropSameNameCancels() {
    var overlay = new IndexOverlay();
    overlay.recordCreated(indexNamed("X.name", "X"));
    overlay.recordDropped("X.name");

    assertFalse("the cancelled create must not remain tx-created", overlay.isTxCreated("X.name"));
    assertFalse("a create-then-drop must not record a spurious tx-drop",
        overlay.isTxDropped("X.name"));
    assertTrue("a create-then-drop of the only change leaves the overlay empty", overlay.isEmpty());
  }

  /**
   * A drop-then-recreate of the same committed name is a replace: the name stays in the tx-dropped set
   * (so the old committed engine is deleted at commit) AND reads as tx-created (so the new engine is
   * built), so the two deltas net to a replace rather than cancelling the drop. A committed name
   * reaches the tx-dropped set only through recordDropped (recordDropped cancels a same-tx create
   * instead of recording a drop), so a name in tx-dropped always names a committed index whose old
   * engine must be deleted before the recreate.
   */
  @Test
  public void dropThenCreateSameCommittedNameIsAReplace() {
    var overlay = new IndexOverlay();
    // recordDropped on a name with no pending create records a drop of the (implied) committed index.
    overlay.recordDropped("Y.name");
    var recreated = indexNamed("Y.name", "Y");
    overlay.recordCreated(recreated);

    assertTrue("the recreated name must read as tx-created", overlay.isTxCreated("Y.name"));
    assertTrue(
        "the committed drop must survive the recreate so the old engine is deleted (replace)",
        overlay.isTxDropped("Y.name"));
    assertSame("the recreated handle must be the one returned", recreated,
        overlay.getTxCreated("Y.name"));
  }

  /**
   * The effective raw-index set of a class that drops then recreates a same-named committed index
   * surfaces only the new handle: resolveClassRawIndexes hides the committed index whose name is in
   * the tx-dropped set and adds the tx-created replacement, so a name in both sets nets to the
   * replacement being visible and the old one hidden.
   */
  @Test
  public void resolveClassRawIndexesSurfacesTheReplacementForADropThenRecreate() {
    var overlay = new IndexOverlay();
    overlay.recordDropped("Z.name");
    var recreated = indexNamed("Z.name", "Z");
    overlay.recordCreated(recreated);

    // The committed set still carries the old (about-to-be-dropped) index of the same name.
    var oldCommitted = indexNamed("Z.name", "Z");
    var effective = overlay.resolveClassRawIndexes("Z", java.util.List.of(oldCommitted));

    assertEquals("the effective set must carry exactly one index for the replaced name", 1,
        effective.size());
    assertSame(
        "the effective set must surface the tx-created replacement, not the dropped committed"
            + " index",
        recreated, effective.iterator().next());
  }

  /**
   * The rename category records the old-to-new name mapping. The rename is applied commit-only, so
   * the overlay only carries the intent; this pins that intent is stored and readable.
   */
  @Test
  public void recordRenamedStoresMapping() {
    var overlay = new IndexOverlay();
    overlay.recordRenamed("old.name", "new.name");

    assertFalse("an overlay with a rename is not empty", overlay.isEmpty());
    assertEquals("the rename must map the old name to the new name", "new.name",
        overlay.getRenamed().get("old.name"));
  }

  /**
   * The membership-added category records a per-index collection add. This is the polymorphic ripple
   * the commit persists so the parent index covers the new subclass collection.
   */
  @Test
  public void recordMembershipAddedStoresCollectionPerIndex() {
    var overlay = new IndexOverlay();
    overlay.recordMembershipAdded("Super.name", "sub_1");
    overlay.recordMembershipAdded("Super.name", "sub_2");

    assertFalse("an overlay with a membership add is not empty", overlay.isEmpty());
    var added = overlay.getMembershipAdded().get("Super.name");
    assertEquals("both added collections must be recorded under the index", 2, added.size());
    assertTrue("the first added collection must be present", added.contains("sub_1"));
    assertTrue("the second added collection must be present", added.contains("sub_2"));
  }

  /**
   * The membership-removed category records a per-index collection removal, the mirror of the add
   * side, so the commit stops the parent index from covering the removed collection.
   */
  @Test
  public void recordMembershipRemovedStoresCollectionPerIndex() {
    var overlay = new IndexOverlay();
    overlay.recordMembershipRemoved("Super.name", "sub_1");

    assertFalse("an overlay with a membership remove is not empty", overlay.isEmpty());
    var removed = overlay.getMembershipRemoved().get("Super.name");
    assertEquals("the removed collection must be recorded under the index", 1, removed.size());
    assertTrue("the removed collection must be present", removed.contains("sub_1"));
  }

  /**
   * An add-then-remove of the same (index, collection) pair nets to no membership change: the pending
   * add is cancelled rather than leaving contradictory add and remove entries. This keeps the commit
   * from persisting a membership change the transaction ultimately reverted.
   */
  @Test
  public void membershipAddThenRemoveSamePairCancels() {
    var overlay = new IndexOverlay();
    overlay.recordMembershipAdded("Super.name", "sub_1");
    overlay.recordMembershipRemoved("Super.name", "sub_1");

    assertTrue("an add-then-remove of the same pair must leave no membership add",
        overlay.getMembershipAdded().isEmpty());
    assertTrue("an add-then-remove of the same pair must leave no membership remove",
        overlay.getMembershipRemoved().isEmpty());
    assertTrue("an add-then-remove of the only change leaves the overlay empty", overlay.isEmpty());
  }

  /**
   * A remove-then-add of the same (index, collection) pair nets to no membership change, the mirror
   * of the add-then-remove cancellation.
   */
  @Test
  public void membershipRemoveThenAddSamePairCancels() {
    var overlay = new IndexOverlay();
    overlay.recordMembershipRemoved("Super.name", "sub_1");
    overlay.recordMembershipAdded("Super.name", "sub_1");

    assertTrue("a remove-then-add of the same pair must leave no membership remove",
        overlay.getMembershipRemoved().isEmpty());
    assertTrue("a remove-then-add of the same pair must leave no membership add",
        overlay.getMembershipAdded().isEmpty());
    assertTrue("a remove-then-add of the only change leaves the overlay empty", overlay.isEmpty());
  }

  /**
   * The effective-set resolution drops indexes the transaction dropped and adds indexes the
   * transaction created on the resolved class. A committed index kept intact stays; a committed index
   * the transaction dropped is hidden; a tx-created index on the class is added; a tx-created index on
   * a different class is not added to this class.
   */
  @Test
  public void resolveClassRawIndexesAppliesDeltas() {
    var overlay = new IndexOverlay();
    var keptCommitted = indexNamed("C.kept", "C");
    var droppedCommitted = indexNamed("C.dropped", "C");
    var createdOnC = indexNamed("C.created", "C");
    var createdOnOther = indexNamed("D.created", "D");

    overlay.recordDropped("C.dropped");
    overlay.recordCreated(createdOnC);
    overlay.recordCreated(createdOnOther);

    List<Index> committed = new ArrayList<>(List.of(keptCommitted, droppedCommitted));
    Collection<Index> effective = overlay.resolveClassRawIndexes("C", committed);

    assertTrue("a kept committed index must remain in the effective set",
        effective.contains(keptCommitted));
    assertFalse("a tx-dropped committed index must be hidden from the effective set",
        effective.contains(droppedCommitted));
    assertTrue("a tx-created index on the class must be added to the effective set",
        effective.contains(createdOnC));
    assertFalse("a tx-created index on a different class must not appear on this class",
        effective.contains(createdOnOther));
    assertEquals("the effective set for C must be exactly the kept and created indexes", 2,
        effective.size());
  }

  /**
   * A tx-created index whose definition names no class (a nameless/manual index) is not attributed to
   * any class's effective set. resolveClassRawIndexes filters on the definition's class name, so a
   * null-definition or null-class tx-created index is skipped rather than throwing.
   */
  @Test
  public void resolveClassRawIndexesSkipsCreatedIndexWithNoClass() {
    var overlay = new IndexOverlay();
    overlay.recordCreated(indexNamed("nameless", null));

    Collection<Index> effective = overlay.resolveClassRawIndexes("C", new ArrayList<>());
    assertTrue("a tx-created index with no class must not appear in any class's effective set",
        effective.isEmpty());
  }
}
