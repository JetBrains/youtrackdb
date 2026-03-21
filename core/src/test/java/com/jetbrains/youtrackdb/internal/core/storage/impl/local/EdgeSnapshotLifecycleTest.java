package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test verifying the full edge snapshot lifecycle: populate shared maps (simulating
 * flushed entries from committed transactions) → verify entries are queryable → evict via LWM →
 * verify entries are removed. Also tests mixed scenarios with both collection and edge snapshot
 * entries, and multi-transaction accumulation.
 *
 * <p>The commit flush pathway (AtomicOperation → local buffers → shared maps) is tested in
 * {@code AtomicOperationSnapshotProxyTest}. This test focuses on the shared-map level: the
 * eviction behavior, size counter management, and independence of collection vs edge cleanup.
 */
public class EdgeSnapshotLifecycleTest {

  private ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex;
  private ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex;
  private AtomicLong snapshotIndexSize;
  private ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex;
  private ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> sharedEdgeVisibilityIndex;
  private AtomicLong edgeSnapshotIndexSize;

  @Before
  public void setUp() {
    sharedSnapshotIndex = new ConcurrentSkipListMap<>();
    sharedVisibilityIndex = new ConcurrentSkipListMap<>();
    snapshotIndexSize = new AtomicLong();
    sharedEdgeSnapshotIndex = new ConcurrentSkipListMap<>();
    sharedEdgeVisibilityIndex = new ConcurrentSkipListMap<>();
    edgeSnapshotIndexSize = new AtomicLong();
  }

  // --- Full lifecycle: populate → query → evict → verify ---

  @Test
  public void testEdgeSnapshotPopulateAndEvict() {
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    var value = new LinkBagValue(1, 10, 200L, false);
    var evk = new EdgeVisibilityKey(100L, 1, 50L, 10, 200L);

    sharedEdgeSnapshotIndex.put(esk, value);
    sharedEdgeVisibilityIndex.put(evk, esk);
    edgeSnapshotIndexSize.set(1);

    assertThat(sharedEdgeSnapshotIndex.get(esk)).isEqualTo(value);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        200L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).isEmpty();
    assertThat(sharedEdgeVisibilityIndex).isEmpty();
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(0L);
  }

  @Test
  public void testEdgeEntriesSurviveCleanupWhenLwmBelowRecordTs() {
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), esk);
    edgeSnapshotIndexSize.set(1);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        50L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).hasSize(1);
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(1L);
  }

  @Test
  public void testMultipleComponentsEvictedIndependently() {
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), esk1);

    var esk2 = new EdgeSnapshotKey(2, 60L, 20, 300L, 8L);
    sharedEdgeSnapshotIndex.put(esk2, new LinkBagValue(1, 20, 300L, false));
    sharedEdgeVisibilityIndex.put(new EdgeVisibilityKey(200L, 2, 60L, 20, 300L), esk2);
    edgeSnapshotIndexSize.set(2);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        150L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).hasSize(1);
    assertThat(sharedEdgeSnapshotIndex).containsKey(esk2);
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(1L);
  }

  // --- Mixed scenario: collection + edge entries ---

  @Test
  public void testMixedCollectionAndEdgeSnapshot() {
    var collKey = new SnapshotKey(1, 50L, 10L);
    sharedSnapshotIndex.put(collKey, new PositionEntry(1L, 0, 10L));
    sharedVisibilityIndex.put(new VisibilityKey(100L, 1, 50L), collKey);
    snapshotIndexSize.set(1);

    var edgeKey = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(edgeKey, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(
        new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), edgeKey);
    edgeSnapshotIndexSize.set(1);

    AbstractStorage.evictStaleSnapshotEntries(
        200L, sharedSnapshotIndex, sharedVisibilityIndex, snapshotIndexSize);
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        200L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedEdgeSnapshotIndex).isEmpty();
    assertThat(snapshotIndexSize.get()).isEqualTo(0L);
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(0L);
  }

  @Test
  public void testEdgeEvictionDoesNotAffectCollectionEntries() {
    var collKey = new SnapshotKey(1, 50L, 10L);
    sharedSnapshotIndex.put(collKey, new PositionEntry(1L, 0, 10L));
    sharedVisibilityIndex.put(new VisibilityKey(100L, 1, 50L), collKey);
    snapshotIndexSize.set(1);

    var edgeKey = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(edgeKey, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(
        new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), edgeKey);
    edgeSnapshotIndexSize.set(1);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        200L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).isEmpty();
    assertThat(sharedSnapshotIndex).hasSize(1);
    assertThat(snapshotIndexSize.get()).isEqualTo(1L);
  }

  @Test
  public void testCollectionEvictionDoesNotAffectEdgeEntries() {
    var collKey = new SnapshotKey(1, 50L, 10L);
    sharedSnapshotIndex.put(collKey, new PositionEntry(1L, 0, 10L));
    sharedVisibilityIndex.put(new VisibilityKey(100L, 1, 50L), collKey);
    snapshotIndexSize.set(1);

    var edgeKey = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(edgeKey, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(
        new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), edgeKey);
    edgeSnapshotIndexSize.set(1);

    AbstractStorage.evictStaleSnapshotEntries(
        200L, sharedSnapshotIndex, sharedVisibilityIndex, snapshotIndexSize);

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedEdgeSnapshotIndex).hasSize(1);
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(1L);
  }

  // --- Multi-transaction accumulation ---

  @Test
  public void testMultipleTransactionsAccumulateEdgeEntries() {
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 3L);
    sharedEdgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(new EdgeVisibilityKey(50L, 1, 50L, 10, 200L), esk1);

    var esk2 = new EdgeSnapshotKey(1, 50L, 10, 200L, 7L);
    sharedEdgeSnapshotIndex.put(esk2, new LinkBagValue(2, 10, 200L, false));
    sharedEdgeVisibilityIndex.put(new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), esk2);
    edgeSnapshotIndexSize.set(2);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        75L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).hasSize(1);
    assertThat(sharedEdgeSnapshotIndex).containsKey(esk2);
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(1L);
  }

  // --- Tombstone entries ---

  @Test
  public void testTombstoneEntryLifecycle() {
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    sharedEdgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, true));
    sharedEdgeVisibilityIndex.put(
        new EdgeVisibilityKey(100L, 1, 50L, 10, 200L), esk);
    edgeSnapshotIndexSize.set(1);

    assertThat(sharedEdgeSnapshotIndex.get(esk).tombstone()).isTrue();

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        200L, sharedEdgeSnapshotIndex, sharedEdgeVisibilityIndex, edgeSnapshotIndexSize);

    assertThat(sharedEdgeSnapshotIndex).isEmpty();
    assertThat(edgeSnapshotIndexSize.get()).isEqualTo(0L);
  }

  // --- Descending subMap query on shared index ---

  @Test
  public void testDescendingSubMapQueryForNewestVisibleVersion() {
    // Multiple versions of the same logical edge
    sharedEdgeSnapshotIndex.put(
        new EdgeSnapshotKey(1, 50L, 10, 200L, 3L), new LinkBagValue(1, 10, 200L, false));
    sharedEdgeSnapshotIndex.put(
        new EdgeSnapshotKey(1, 50L, 10, 200L, 7L), new LinkBagValue(2, 10, 200L, false));
    sharedEdgeSnapshotIndex.put(
        new EdgeSnapshotKey(1, 50L, 10, 200L, 12L), new LinkBagValue(3, 10, 200L, true));

    var from = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MIN_VALUE);
    var to = new EdgeSnapshotKey(1, 50L, 10, 200L, Long.MAX_VALUE);
    var descending = sharedEdgeSnapshotIndex.subMap(from, true, to, true).descendingMap();

    var values = descending.values().stream().toList();
    assertThat(values).hasSize(3);
    assertThat(values.get(0).tombstone()).isTrue(); // version 12 is tombstone
    assertThat(values.get(1).counter()).isEqualTo(2); // version 7
    assertThat(values.get(2).counter()).isEqualTo(1); // version 3
  }
}
