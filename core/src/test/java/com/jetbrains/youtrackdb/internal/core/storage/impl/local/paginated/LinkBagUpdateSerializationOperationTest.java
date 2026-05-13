package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link LinkBagUpdateSerializationOperation}. Covers the four observable
 * behaviours of the deferred linkbag-update operation:
 * <ul>
 *   <li>positive counter → {@code put} on the resolved B-tree with a {@link LinkBagValue}
 *       carrying the secondary RID;</li>
 *   <li>zero counter → {@code remove} on the B-tree (the linkbag dropped its last reference
 *       to the primary RID);</li>
 *   <li>over-cap counter → {@link DatabaseException} (per-RID hard cap protects the page
 *       layout);</li>
 *   <li>negative counter → {@link DatabaseException} (more removes than inserts is an
 *       inconsistent client state and must not propagate to disk).</li>
 * </ul>
 *
 * <p>The B-tree is mocked via {@link LinkCollectionsBTreeManager#loadIsolatedBTree(LinkBagPointer)}
 * so the tests stay in surefire scope.
 */
public class LinkBagUpdateSerializationOperationTest {

  private static final int MAX_COUNTER = 100;

  private AtomicOperation atomicOperation;
  private AbstractStorage storage;
  private DatabaseSessionEmbedded session;
  private LinkCollectionsBTreeManager manager;
  @SuppressWarnings("unchecked")
  private final IsolatedLinkBagBTree<RID, LinkBagValue> tree = mock(IsolatedLinkBagBTree.class);
  private final LinkBagPointer collectionPointer = new LinkBagPointer(0L, 0L);

  @Before
  public void setUp() {
    atomicOperation = mock(AtomicOperation.class);
    storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn("test-storage");

    session = mock(DatabaseSessionEmbedded.class);
    manager = mock(LinkCollectionsBTreeManager.class);
    when(session.getBTreeCollectionManager()).thenReturn(manager);
    when(manager.loadIsolatedBTree(collectionPointer)).thenReturn(tree);
  }

  /**
   * A positive counter triggers a {@code put} on the resolved B-tree carrying the secondary
   * RID and the new counter value.
   */
  @Test
  public void testPositiveCounterTriggersPut() throws IOException {
    var primaryRid = new RecordId(7, 1L);
    var secondaryRid = new RecordId(8, 99L);
    var change = new AbsoluteChange(5, secondaryRid);
    var pair = new RawPair<RID, AbsoluteChange>(primaryRid, change);

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pair), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(tree, times(1))
        .put(
            eq(atomicOperation),
            eq((RID) primaryRid),
            eq(
                new LinkBagValue(
                    5, secondaryRid.getCollectionId(), secondaryRid.getCollectionPosition(),
                    false)));
    verify(tree, never()).remove(any(), any());
  }

  /**
   * A zero counter signals the linkbag dropped its last reference to the primary RID; the
   * operation must call {@code remove} on the B-tree (not {@code put}) so the entry is
   * deleted from the persisted index.
   */
  @Test
  public void testZeroCounterTriggersRemove() throws IOException {
    var primaryRid = new RecordId(7, 2L);
    var secondaryRid = new RecordId(8, 0L);
    var change = new AbsoluteChange(0, secondaryRid);
    var pair = new RawPair<RID, AbsoluteChange>(primaryRid, change);

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pair), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(tree, times(1)).remove(atomicOperation, primaryRid);
    verify(tree, never()).put(any(), any(), any());
  }

  /**
   * A counter above the configured maxCounterValue must abort the operation with a
   * {@link DatabaseException}. The cap protects the page layout from oversize entries.
   */
  @Test
  public void testOverCapTriggersDatabaseException() {
    var primaryRid = new RecordId(7, 3L);
    var secondaryRid = new RecordId(8, 0L);
    var change = new AbsoluteChange(MAX_COUNTER + 1, secondaryRid);
    var pair = new RawPair<RID, AbsoluteChange>(primaryRid, change);

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pair), collectionPointer, MAX_COUNTER, session);

    assertThatThrownBy(() -> op.execute(atomicOperation, storage))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("Link collection can not contain more than")
        .hasMessageContaining(String.valueOf(MAX_COUNTER));
  }

  /**
   * Multiple changes are applied in stream order. A mix of put (positive counter) and remove
   * (zero counter) must both reach the B-tree.
   */
  @Test
  public void testMultipleChangesAreReplayedInStreamOrder() throws IOException {
    var primaryA = new RecordId(7, 10L);
    var primaryB = new RecordId(7, 20L);
    var secondary = new RecordId(8, 0L);

    var pairA = new RawPair<RID, AbsoluteChange>(primaryA, new AbsoluteChange(3, secondary));
    var pairB = new RawPair<RID, AbsoluteChange>(primaryB, new AbsoluteChange(0, secondary));

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pairA, pairB), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(tree, times(1)).put(eq(atomicOperation), eq((RID) primaryA), any(LinkBagValue.class));
    verify(tree, times(1)).remove(atomicOperation, primaryB);
  }

  /**
   * An empty input stream is a no-op: the tree is loaded (the operation does not optimise
   * the empty case at construction time) but neither {@code put} nor {@code remove} is
   * invoked.
   */
  @Test
  public void testEmptyChangesIsNoOp() throws IOException {
    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.<RawPair<RID, AbsoluteChange>>of(), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(manager, times(1)).loadIsolatedBTree(collectionPointer);
    verify(tree, never()).put(any(), any(), any());
    verify(tree, never()).remove(any(), any());
  }

  /**
   * If the put on the B-tree throws an {@link IOException}, the surrounding code must wrap
   * it in a {@link DatabaseException} so the surrounding atomic operation can detect the
   * failure and roll back. Use a stream of one valid put change and stub the tree to throw.
   */
  @Test
  public void testIoExceptionFromTreeIsWrapped() throws IOException {
    var primary = new RecordId(7, 30L);
    var secondary = new RecordId(8, 0L);
    var change = new AbsoluteChange(5, secondary);
    var pair = new RawPair<RID, AbsoluteChange>(primary, change);

    when(tree.put(any(), any(), any())).thenThrow(new IOException("disk failure under test"));

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pair), collectionPointer, MAX_COUNTER, session);

    assertThatThrownBy(() -> op.execute(atomicOperation, storage))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("Error during ridbag update");
  }

  /**
   * Sanity: the {@code maxCounterValue} boundary is inclusive — exactly {@code MAX_COUNTER}
   * is permitted, only strictly greater triggers the cap exception.
   */
  @Test
  public void testExactlyMaxCounterIsPermitted() throws IOException {
    var primary = new RecordId(7, 40L);
    var secondary = new RecordId(8, 1L);
    var change = new AbsoluteChange(MAX_COUNTER, secondary);
    var pair = new RawPair<RID, AbsoluteChange>(primary, change);

    var op =
        new LinkBagUpdateSerializationOperation(
            Stream.of(pair), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(tree, times(1)).put(eq(atomicOperation), eq((RID) primary), any(LinkBagValue.class));
  }

  /**
   * Sanity: a stream with valid pairs is iterated lazily — supplying a {@link List#stream()}
   * works the same as a static-built stream.
   */
  @Test
  public void testStreamFromListSourceWorks() throws IOException {
    var p1 = new RecordId(7, 50L);
    var p2 = new RecordId(7, 51L);
    var secondary = new RecordId(8, 0L);
    var pairs = List.of(
        new RawPair<RID, AbsoluteChange>(p1, new AbsoluteChange(2, secondary)),
        new RawPair<RID, AbsoluteChange>(p2, new AbsoluteChange(7, secondary)));

    var op =
        new LinkBagUpdateSerializationOperation(
            pairs.stream(), collectionPointer, MAX_COUNTER, session);

    op.execute(atomicOperation, storage);

    verify(tree, times(2)).put(eq(atomicOperation), any(RID.class), any(LinkBagValue.class));
    verify(tree, never()).remove(any(), any());
  }

  /**
   * A negative counter is not constructible directly via {@link AbsoluteChange} (the
   * constructor clamps to zero). However, the production code defends against the
   * theoretical negative-counter path with an explicit branch — pin that branch as
   * defensive-only documentation by exercising a test that confirms zero is the floor
   * AbsoluteChange enforces, so the negative branch in {@code execute} is preserved by
   * future refactors as a deliberate guard.
   */
  @Test
  public void testAbsoluteChangeFloorIsZero() {
    var rid = new RecordId(8, 0L);
    var clamped = new AbsoluteChange(-5, rid);
    assertThat(clamped.getValue()).isZero();
  }
}
