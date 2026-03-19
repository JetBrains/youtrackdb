package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the edge snapshot index cleanup logic in {@link AbstractStorage}, specifically the
 * static {@link AbstractStorage#evictStaleEdgeSnapshotEntries} method and its integration with
 * the combined cleanup threshold in {@code cleanupSnapshotIndex()}.
 */
public class EdgeSnapshotIndexCleanupTest {

  private ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> edgeSnapshotIndex;
  private ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> edgeVisibilityIndex;
  private AtomicLong sizeCounter;

  @Before
  public void setUp() {
    edgeSnapshotIndex = new ConcurrentSkipListMap<>();
    edgeVisibilityIndex = new ConcurrentSkipListMap<>();
    sizeCounter = new AtomicLong();
  }

  @After
  public void tearDown() {
    edgeSnapshotIndex = null;
    edgeVisibilityIndex = null;
    sizeCounter = null;
  }

  // --- evictStaleEdgeSnapshotEntries: basic eviction ---

  @Test
  public void testEvictRemovesEdgeEntriesBelowLwm() {
    // Two edge snapshot entries at different timestamps
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    var esk2 = new EdgeSnapshotKey(1, 50L, 10, 300L, 8L);
    edgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    edgeSnapshotIndex.put(esk2, new LinkBagValue(1, 10, 300L, false));
    sizeCounter.set(2);

    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(20L, 1, 50L, 10, 300L), esk2);

    // lwm = 15: entries with recordTs < 15 (i.e., recordTs=10) should be evicted
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        15L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).doesNotContainKey(esk1);
    assertThat(edgeSnapshotIndex).containsKey(esk2);
    assertThat(edgeVisibilityIndex).hasSize(1);
    assertThat(edgeVisibilityIndex.firstKey().recordTs()).isEqualTo(20L);
    assertThat(sizeCounter.get()).isEqualTo(1L);
  }

  @Test
  public void testEvictPreservesEdgeEntriesAtLwm() {
    // Entry at exactly lwm should be preserved (headMap is exclusive)
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    edgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, false));
    sizeCounter.set(1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk);

    // lwm = 10: entry with recordTs=10 should be PRESERVED
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        10L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).containsKey(esk);
    assertThat(edgeVisibilityIndex).hasSize(1);
    assertThat(sizeCounter.get()).isEqualTo(1L);
  }

  @Test
  public void testEvictAllEdgeEntriesWhenLwmHighEnough() {
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    var esk2 = new EdgeSnapshotKey(2, 60L, 20, 300L, 8L);
    edgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    edgeSnapshotIndex.put(esk2, new LinkBagValue(1, 20, 300L, false));
    sizeCounter.set(2);

    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(20L, 2, 60L, 20, 300L), esk2);

    // lwm = 100: all entries should be evicted
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        100L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).isEmpty();
    assertThat(edgeVisibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0L);
  }

  @Test
  public void testEvictWithEmptyMaps() {
    // Eviction on empty maps should not fail
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        100L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).isEmpty();
    assertThat(edgeVisibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0L);
  }

  @Test
  public void testEvictWithLwmZero() {
    // lwm = 0: no entries should be evicted (nothing has recordTs < 0 in normal usage)
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 1L);
    edgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, false));
    sizeCounter.set(1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(0L, 1, 50L, 10, 200L), esk);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        0L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).containsKey(esk);
    assertThat(edgeVisibilityIndex).hasSize(1);
    assertThat(sizeCounter.get()).isEqualTo(1L);
  }

  // --- Multi-component and ridBag isolation ---

  @Test
  public void testEvictAcrossMultipleComponents() {
    // Entries from different components at different timestamps
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    var esk2 = new EdgeSnapshotKey(2, 60L, 20, 300L, 8L);
    var esk3 = new EdgeSnapshotKey(3, 70L, 30, 400L, 12L);
    edgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    edgeSnapshotIndex.put(esk2, new LinkBagValue(1, 20, 300L, false));
    edgeSnapshotIndex.put(esk3, new LinkBagValue(1, 30, 400L, false));
    sizeCounter.set(3);

    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(15L, 2, 60L, 20, 300L), esk2);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(20L, 3, 70L, 30, 400L), esk3);

    // lwm = 16: evicts components 1 and 2 (recordTs=10, 15), preserves component 3 (recordTs=20)
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        16L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).hasSize(1);
    assertThat(edgeSnapshotIndex).containsKey(esk3);
    assertThat(edgeVisibilityIndex).hasSize(1);
    assertThat(sizeCounter.get()).isEqualTo(1L);
  }

  @Test
  public void testEvictMultipleEntriesFromSameRidBag() {
    // Multiple edge versions from the same ridBag at different timestamps
    var esk1 = new EdgeSnapshotKey(1, 50L, 10, 200L, 3L);
    var esk2 = new EdgeSnapshotKey(1, 50L, 10, 200L, 7L);
    edgeSnapshotIndex.put(esk1, new LinkBagValue(1, 10, 200L, false));
    edgeSnapshotIndex.put(esk2, new LinkBagValue(1, 10, 200L, false));
    sizeCounter.set(2);

    edgeVisibilityIndex.put(new EdgeVisibilityKey(5L, 1, 50L, 10, 200L), esk1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk2);

    // lwm = 8: evicts version 3 (recordTs=5), preserves version 7 (recordTs=10)
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        8L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).hasSize(1);
    assertThat(edgeSnapshotIndex).containsKey(esk2);
    assertThat(sizeCounter.get()).isEqualTo(1L);
  }

  // --- Tombstone entries ---

  @Test
  public void testEvictTombstoneEntries() {
    // Tombstone entries should be evicted the same way as live entries
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    edgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, true));
    sizeCounter.set(1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        20L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).isEmpty();
    assertThat(edgeVisibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0L);
  }

  // --- Size counter ---

  @Test
  public void testSizeCounterDecrementsCorrectly() {
    // Start with 5 entries, evict 3, counter should be 2
    for (int i = 0; i < 5; i++) {
      var esk = new EdgeSnapshotKey(1, 50L, 10, (long) (i * 100), (long) i);
      edgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, (long) (i * 100), false));
      // recordTs: 10, 20, 30, 40, 50
      edgeVisibilityIndex.put(
          new EdgeVisibilityKey((i + 1) * 10L, 1, 50L, 10, (long) (i * 100)), esk);
    }
    sizeCounter.set(5);

    // lwm = 35: evicts entries with recordTs=10, 20, 30
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        35L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(sizeCounter.get()).isEqualTo(2L);
    assertThat(edgeSnapshotIndex).hasSize(2);
    assertThat(edgeVisibilityIndex).hasSize(2);
  }

  @Test
  public void testOrphanedVisibilityEntryDoesNotDecrementCounter() {
    // Visibility entry exists but corresponding snapshot entry was already removed
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    // Don't add to snapshotIndex — only to visibility
    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk);
    sizeCounter.set(0);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        20L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    // Visibility entry removed, but counter unchanged (was already 0)
    assertThat(edgeVisibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0L);
  }

  // --- Idempotence ---

  @Test
  public void testIdempotentEviction() {
    // Running eviction twice with the same LWM should be safe
    var esk = new EdgeSnapshotKey(1, 50L, 10, 200L, 5L);
    edgeSnapshotIndex.put(esk, new LinkBagValue(1, 10, 200L, false));
    sizeCounter.set(1);
    edgeVisibilityIndex.put(new EdgeVisibilityKey(10L, 1, 50L, 10, 200L), esk);

    AbstractStorage.evictStaleEdgeSnapshotEntries(
        20L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);
    // Second call — maps are already empty, should not fail
    AbstractStorage.evictStaleEdgeSnapshotEntries(
        20L, edgeSnapshotIndex, edgeVisibilityIndex, sizeCounter);

    assertThat(edgeSnapshotIndex).isEmpty();
    assertThat(edgeVisibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0L);
  }
}
