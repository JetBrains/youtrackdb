package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the indexes snapshot cleanup logic in {@link AbstractStorage}, specifically the static
 * {@link AbstractStorage#evictStaleIndexesSnapshotEntries} method.
 *
 * <p>Mirrors the production topology: AbstractStorage owns the raw data and version-index maps,
 * creates {@link IndexesSnapshot} wrappers from them, and hands sub-snapshots to index engines.
 * Engines add entries via sub-snapshots; eviction runs on the raw maps owned by the storage.
 * All tests reproduce this: entries are added via sub-snapshots, eviction is invoked on the raw
 * maps via {@link AbstractStorage#evictStaleIndexesSnapshotEntries}.
 */
public class IndexesSnapshotCleanupTest {

  private static final long INDEX_ID = 42L;

  // Raw maps owned by AbstractStorage
  private ConcurrentSkipListMap<CompositeKey, RID> snapshotData;
  private ConcurrentSkipListMap<CompositeKey, CompositeKey> versionIndex;
  private ConcurrentSkipListMap<CompositeKey, RID> nullSnapshotData;
  private ConcurrentSkipListMap<CompositeKey, CompositeKey> nullVersionIndex;

  // Shared size counter — same AtomicLong instance as in AbstractStorage
  private AtomicLong sizeCounter;

  // Sub-snapshots — used for adding entries (as in index engines)
  private IndexesSnapshot indexesSnapshot;
  private IndexesSnapshot nullIndexesSnapshot;

  @Before
  public void setUp() {
    snapshotData = new ConcurrentSkipListMap<>();
    versionIndex = new ConcurrentSkipListMap<>(AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    nullSnapshotData = new ConcurrentSkipListMap<>();
    nullVersionIndex =
        new ConcurrentSkipListMap<>(AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    sizeCounter = new AtomicLong();

    indexesSnapshot = new IndexesSnapshot(snapshotData, versionIndex, sizeCounter, INDEX_ID);
    nullIndexesSnapshot =
        new IndexesSnapshot(nullSnapshotData, nullVersionIndex, sizeCounter, INDEX_ID);
  }

  @After
  public void tearDown() {
    snapshotData = null;
    versionIndex = null;
    nullSnapshotData = null;
    nullVersionIndex = null;
    sizeCounter = null;
    indexesSnapshot = null;
    nullIndexesSnapshot = null;
  }

  private void evict(long lwm) {
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        lwm, snapshotData, versionIndex, sizeCounter);
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        lwm, nullSnapshotData, nullVersionIndex, sizeCounter);
  }

  // --- sizeCounter tracking ---

  /**
   * Verifies the sizeCounter increments by 2 per addSnapshotPair call and decrements
   * by 2 per evicted pair, matching the pattern of snapshotIndexSize / edgeSnapshotIndexSize.
   */
  @Test
  public void testSizeCounterTracksAddAndEvict() {
    assertThat(sizeCounter.get()).isEqualTo(0);

    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);

    // Each addSnapshotPair adds 2 entries (TombstoneRID + RecordId guard)
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    assertThat(sizeCounter.get()).isEqualTo(2);

    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyB", 8L),
        new CompositeKey("keyB", 20L),
        rid2);
    assertThat(sizeCounter.get()).isEqualTo(4);

    // Evict one pair (newVersion 15 < lwm 17), preserving newVersion 20
    evict(17L);
    assertThat(sizeCounter.get()).isEqualTo(2);

    // Evict remaining pair (newVersion 20 < lwm 25)
    evict(25L);
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  /**
   * Verifies that a single sizeCounter is shared across both sv and null sub-snapshots,
   * and that eviction from either sub-snapshot decrements the same counter.
   */
  @Test
  public void testSizeCounterSharedAcrossSvAndNullSnapshots() {
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);

    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid2, 8L),
        new CompositeKey(rid2, 20L),
        rid2);
    assertThat(sizeCounter.get()).isEqualTo(4);

    // Evict all (newVersion 15 and 20 are both < 25)
    evict(25L);
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  /**
   * Verifies that clear() on a sub-snapshot decrements the shared sizeCounter by the
   * number of entries removed, and does not affect entries in other sub-snapshots.
   */
  @Test
  public void testSizeCounterDecrementedByClear() {
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);

    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid2, 8L),
        new CompositeKey(rid2, 20L),
        rid2);
    assertThat(sizeCounter.get()).isEqualTo(4);

    // Clear only the sv sub-snapshot — should decrement by 2, leaving null entries
    indexesSnapshot.clear();
    assertThat(sizeCounter.get()).isEqualTo(2);
    assertThat(indexesSnapshot.allEntries()).isEmpty();
    assertThat(nullIndexesSnapshot.allEntries()).hasSize(2);

    // Clear the null sub-snapshot — counter should reach 0
    nullIndexesSnapshot.clear();
    assertThat(sizeCounter.get()).isEqualTo(0);
    assertThat(nullIndexesSnapshot.allEntries()).isEmpty();
  }

  // --- evictStaleIndexesSnapshotEntries: basic eviction ---

  @Test
  public void testEvictRemovesEntriesBelowLwm() {
    // Two snapshot pairs at version 5 and version 15
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);
    // addSnapshotPair: addedKey (old version), removedKey (new version), value
    // Version is the last element of each CompositeKey
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyB", 8L),
        new CompositeKey("keyB", 20L),
        rid2);

    // lwm = 21: entries with removedKey version < 21 (i.e., newVersion 15 and 20) are evicted
    evict(21L);

    // Both pairs should be fully evicted (addedKey and removedKey entries removed)
    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictPreservesEntriesAtLwm() {
    // Entry with removedKey version exactly at lwm should be preserved (headMap is exclusive)
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 10L),
        new CompositeKey("keyA", 20L),
        rid);

    // lwm = 20: entry with newVersion=20 should be PRESERVED (headMap is exclusive)
    evict(20L);

    // 1 pair = 2 entries (addedKey + removedKey)
    assertThat(indexesSnapshot.allEntries()).hasSize(2);
  }

  @Test
  public void testEvictAllEntriesWhenLwmHighEnough() {
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(2, 200L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyB", 8L),
        new CompositeKey("keyB", 20L),
        rid2);

    // lwm = 100: all entries should be evicted
    evict(100L);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictWithEmptySnapshot() {
    // Eviction on an empty snapshot should not fail
    evict(100L);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictWithLwmZero() {
    // lwm = 0: no entries should be evicted (no version is < 0 in normal usage)
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 1L),
        new CompositeKey("keyA", 10L),
        rid);

    evict(0L);

    // 1 pair = 2 entries preserved
    assertThat(indexesSnapshot.allEntries()).hasSize(2);
  }

  @Test
  public void testEvictWithLwmMaxValue() {
    // lwm = Long.MAX_VALUE: special case — method returns early without evicting
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid);

    evict(Long.MAX_VALUE);

    // Entries should be preserved because MAX_VALUE triggers early return
    assertThat(indexesSnapshot.allEntries()).hasSize(2);
  }

  // --- Partial eviction ---

  @Test
  public void testEvictPartiallyWhenMixedVersions() {
    // Three pairs: versions 5, 10, 20. lwm=15 should evict versions 5 and 10
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);
    var rid3 = new RecordId(1, 300L);

    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyB", 10L),
        new CompositeKey("keyB", 25L),
        rid2);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyC", 20L),
        new CompositeKey("keyC", 30L),
        rid3);

    // lwm = 26: evicts pairs with removedKey version 15 and 25, preserves removedKey 30
    evict(26L);

    // Only the third pair should remain (1 pair = 2 entries: addedKey + removedKey)
    assertThat(indexesSnapshot.allEntries()).hasSize(2);

    // Verify the remaining entries are from the preserved pair (version 20 and 30)
    var remainingVersions = indexesSnapshot.allEntries().stream()
        .map(e -> (Long) e.getKey().getKeys().getLast())
        .sorted()
        .toList();
    assertThat(remainingVersions).containsExactly(20L, 30L);
  }

  // --- Tombstone values in snapshot ---

  @Test
  public void testEvictEntriesWithTombstoneValues() {
    // addSnapshotPair stores TombstoneRID(value) for addedKey and value for removedKey;
    // both should be evicted when version is below lwm
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid);

    // Verify tombstone entry exists before eviction
    var tombstoneCount = indexesSnapshot.allEntries().stream()
        .filter(e -> e.getValue() instanceof TombstoneRID)
        .count();
    assertThat(tombstoneCount).isEqualTo(1);

    // lwm = 16: entry with removedKey version 15 should be evicted (15 < 16)
    evict(16L);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  // --- Multiple entries for the same key ---

  @Test
  public void testEvictMultipleVersionsOfSameKey() {
    // Same logical key "keyA" updated multiple times, creating multiple snapshot pairs
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);

    // First update: version 3 → version 10
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 3L),
        new CompositeKey("keyA", 10L),
        rid1);
    // Second update: version 7 → version 15
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 7L),
        new CompositeKey("keyA", 15L),
        rid2);

    // lwm = 11: evicts pair with removedKey version 10, preserves pair with removedKey 15
    evict(11L);

    // Remaining: 1 pair = 2 entries (addedKey version=7 + removedKey version=15)
    assertThat(indexesSnapshot.allEntries()).hasSize(2);
  }

  // --- Idempotence ---

  @Test
  public void testIdempotentEviction() {
    // Running eviction twice with the same LWM should be safe
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid);

    evict(20L);
    // Second call — snapshot is already empty, should not fail
    evict(20L);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  // --- Null-key entries ---

  @Test
  public void testEvictNullKeyEntries() {
    // Null-key entry (as stored by BTreeSingleValueIndexEngine for null user keys)
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey((Object) null, 5L),
        new CompositeKey((Object) null, 15L),
        rid);

    // lwm = 16: entry with removedKey version 15 should be evicted (15 < 16)
    evict(16L);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictPreservesNullKeyEntriesAboveLwm() {
    // Null-key entry with version above lwm should be preserved
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey((Object) null, 15L),
        new CompositeKey((Object) null, 25L),
        rid);

    // lwm = 10: entry with removedKey version 25 should be preserved (25 >= 10)
    evict(10L);

    assertThat(indexesSnapshot.allEntries()).hasSize(2);
  }

  @Test
  public void testEvictMixedNullAndNonNullKeys() {
    // Mix of null-key and non-null-key entries at various versions
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);
    var rid3 = new RecordId(1, 300L);

    // Null-key at version 5 (below lwm)
    indexesSnapshot.addSnapshotPair(
        new CompositeKey((Object) null, 5L),
        new CompositeKey((Object) null, 12L),
        rid1);
    // Non-null key at version 8 (below lwm)
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 8L),
        new CompositeKey("keyA", 18L),
        rid2);
    // Null-key at version 20 (above lwm)
    indexesSnapshot.addSnapshotPair(
        new CompositeKey((Object) null, 20L),
        new CompositeKey((Object) null, 30L),
        rid3);

    // lwm = 19: evicts removedKey versions 12 and 18, preserves removedKey 30
    evict(19L);

    assertThat(indexesSnapshot.allEntries()).hasSize(2);
    var remainingVersions = indexesSnapshot.allEntries().stream()
        .map(e -> (Long) e.getKey().getKeys().getLast())
        .sorted()
        .toList();
    assertThat(remainingVersions).containsExactly(20L, 30L);
  }

  @Test
  public void testEvictNullKeyMultiValueEntries() {
    // Null-key entries from BTreeMultiValueIndexEngine use a separate null snapshot.
    // Keys are CompositeKey(RID, version). Eviction via parent must clean the null
    // sub-snapshot entries.
    var rid = new RecordId(1, 100L);
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid, 5L),
        new CompositeKey(rid, 15L),
        rid);

    // lwm = 16: should be evicted via parent (removedKey version 15 < 16)
    evict(16L);

    assertThat(nullIndexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictEvictsBothSvAndNullEntries() {
    // Eviction via AbstractStorage must clean up entries from both parent snapshots
    // in a single call.
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);

    // svTree entry at version 5
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", rid1, 5L),
        new CompositeKey("keyA", rid1, 15L),
        rid1);

    // nullTree entry at version 7
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid2, 7L),
        new CompositeKey(rid2, 17L),
        rid2);

    assertThat(indexesSnapshot.allEntries()).hasSize(2);
    assertThat(nullIndexesSnapshot.allEntries()).hasSize(2);

    // Evict via parents — lwm=18 evicts removedKey versions 15 and 17
    evict(18L);

    assertThat(indexesSnapshot.allEntries())
        .as("svTree sub-snapshot must be empty after parent eviction")
        .isEmpty();
    assertThat(nullIndexesSnapshot.allEntries())
        .as("nullTree sub-snapshot must be empty after parent eviction")
        .isEmpty();
  }

  @Test
  public void testEvictPreservesEntriesAboveLwmInBothSnapshots() {
    // Mixed versions across sv and null sub-snapshots: some below lwm, some above
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);
    var rid3 = new RecordId(1, 300L);

    // svTree entry at version 5 (below lwm)
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", rid1, 5L),
        new CompositeKey("keyA", rid1, 15L),
        rid1);

    // nullTree entry at version 20 (above lwm)
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid2, 20L),
        new CompositeKey(rid2, 30L),
        rid2);

    // nullTree entry at version 3 (below lwm)
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid3, 3L),
        new CompositeKey(rid3, 12L),
        rid3);

    // Evict via raw maps — lwm=16 evicts removedKey versions 15 and 12, preserves 30
    evict(16L);

    assertThat(indexesSnapshot.allEntries())
        .as("svTree entry with removedKey version 15 must be evicted")
        .isEmpty();
    assertThat(nullIndexesSnapshot.allEntries())
        .as("nullTree entry with removedKey version 30 must be preserved, version 12 evicted")
        .hasSize(2);
  }

  @Test
  public void testProgressiveEvictionWithIncreasingLwm() {
    // Simulate progressive LWM advancement: evict in stages
    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(1, 200L);
    var rid3 = new RecordId(1, 300L);

    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 5L),
        new CompositeKey("keyA", 15L),
        rid1);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyB", 10L),
        new CompositeKey("keyB", 25L),
        rid2);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyC", 20L),
        new CompositeKey("keyC", 30L),
        rid3);

    // First eviction: lwm=16, evicts removedKey version 15 → 2 pairs remain (4 entries)
    evict(16L);
    assertThat(indexesSnapshot.allEntries()).hasSize(4);

    // Second eviction: lwm=26, evicts removedKey version 25 → 1 pair remains (2 entries)
    evict(26L);
    assertThat(indexesSnapshot.allEntries()).hasSize(2);

    // Third eviction: lwm=31, evicts removedKey version 30 → all gone
    evict(31L);
    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  // --- Multiple index engines (multiple sub-snapshots from the same parent) ---

  /**
   * Multiple index engines (different indexIds) add entries to sub-snapshots derived from
   * the same parent. Eviction on the raw maps must clean entries from all sub-snapshots
   * whose version is below the LWM, and preserve those above.
   */
  @Test
  public void testEvictWithMultipleSubSnapshots() {
    var data = new ConcurrentSkipListMap<CompositeKey, RID>();
    var verIdx =
        new ConcurrentSkipListMap<CompositeKey, CompositeKey>(
            AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    var nullData = new ConcurrentSkipListMap<CompositeKey, RID>();
    var nullVerIdx =
        new ConcurrentSkipListMap<CompositeKey, CompositeKey>(
            AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    var counter = new AtomicLong();

    long indexIdA = 10L;
    long indexIdB = 20L;
    var subA = new IndexesSnapshot(data, verIdx, counter, indexIdA);
    var subB = new IndexesSnapshot(data, verIdx, counter, indexIdB);
    var subNullA = new IndexesSnapshot(nullData, nullVerIdx, counter, indexIdA);

    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(2, 200L);
    var rid3 = new RecordId(3, 300L);

    // Engine A: entry at version 5 (below future LWM)
    subA.addSnapshotPair(
        new CompositeKey("keyA", rid1, 5L),
        new CompositeKey("keyA", rid1, 15L),
        rid1);

    // Engine B: entry at version 8 (below future LWM)
    subB.addSnapshotPair(
        new CompositeKey("keyB", rid2, 8L),
        new CompositeKey("keyB", rid2, 18L),
        rid2);

    // Engine B: another entry at version 25 (above future LWM)
    subB.addSnapshotPair(
        new CompositeKey("keyC", rid3, 25L),
        new CompositeKey("keyC", rid3, 35L),
        rid3);

    // Null sub-snapshot for engine A: entry at version 3 (below LWM)
    subNullA.addSnapshotPair(
        new CompositeKey(rid1, 3L),
        new CompositeKey(rid1, 13L),
        rid1);

    assertThat(data).hasSize(6);
    assertThat(nullData).hasSize(2);
    assertThat(subA.allEntries()).hasSize(2);
    assertThat(subB.allEntries()).hasSize(4);

    // Evict with LWM=19: removedKey versions 15, 18, 13 evicted; removedKey 35 preserved
    AbstractStorage.evictStaleIndexesSnapshotEntries(19L, data, verIdx, counter);
    AbstractStorage.evictStaleIndexesSnapshotEntries(19L, nullData, nullVerIdx, counter);

    assertThat(data)
        .as("only engine B's version-25 pair should survive")
        .hasSize(2);
    assertThat(nullData)
        .as("null snapshot must be fully evicted")
        .isEmpty();
    assertThat(subA.allEntries()).isEmpty();
    assertThat(subB.allEntries()).hasSize(2);
  }

  /**
   * Progressive eviction with increasing LWM across multiple sub-snapshots. Verifies that
   * each eviction pass removes only the entries below the current LWM, and that the
   * versionIndex does not accumulate stale mappings.
   */
  @Test
  public void testProgressiveEvictionWithMultipleSubSnapshots() {
    var data = new ConcurrentSkipListMap<CompositeKey, RID>();
    var verIdx =
        new ConcurrentSkipListMap<CompositeKey, CompositeKey>(
            AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    var counter = new AtomicLong();

    long indexIdA = 10L;
    long indexIdB = 20L;
    var subA = new IndexesSnapshot(data, verIdx, counter, indexIdA);
    var subB = new IndexesSnapshot(data, verIdx, counter, indexIdB);

    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(2, 200L);
    var rid3 = new RecordId(3, 300L);

    subA.addSnapshotPair(
        new CompositeKey("keyA", 5L), new CompositeKey("keyA", 15L), rid1);
    subB.addSnapshotPair(
        new CompositeKey("keyB", 12L), new CompositeKey("keyB", 22L), rid2);
    subA.addSnapshotPair(
        new CompositeKey("keyC", 30L), new CompositeKey("keyC", 40L), rid3);

    assertThat(data).hasSize(6);
    assertThat(verIdx).hasSize(3);

    // First pass: LWM=16 — evicts removedKey version 15 only
    AbstractStorage.evictStaleIndexesSnapshotEntries(16L, data, verIdx, counter);
    assertThat(data).hasSize(4);
    assertThat(verIdx).as("versionIndex must shrink after eviction").hasSize(2);

    // Second pass: LWM=23 — evicts removedKey version 22
    AbstractStorage.evictStaleIndexesSnapshotEntries(23L, data, verIdx, counter);
    assertThat(data).hasSize(2);
    assertThat(verIdx).hasSize(1);
    assertThat(subA.allEntries()).hasSize(2);
    assertThat(subB.allEntries()).isEmpty();

    // Third pass: LWM=41 — evicts everything (removedKey version 40)
    AbstractStorage.evictStaleIndexesSnapshotEntries(41L, data, verIdx, counter);
    assertThat(data).isEmpty();
    assertThat(verIdx).as("versionIndex must be empty after full eviction").isEmpty();
  }

  /**
   * Entries added via sub-snapshots after a partial eviction pass must still be evictable
   * in subsequent passes. Verifies that the versionIndex continues to work correctly
   * after interleaved add/evict cycles.
   */
  @Test
  public void testInterleavedAddAndEvict() {
    var data = new ConcurrentSkipListMap<CompositeKey, RID>();
    var verIdx =
        new ConcurrentSkipListMap<CompositeKey, CompositeKey>(
            AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    var counter = new AtomicLong();
    var sub = new IndexesSnapshot(data, verIdx, counter, INDEX_ID);

    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(2, 200L);

    // Add first pair, evict it
    sub.addSnapshotPair(
        new CompositeKey("keyA", 5L), new CompositeKey("keyA", 15L), rid1);
    assertThat(data).hasSize(2);

    AbstractStorage.evictStaleIndexesSnapshotEntries(16L, data, verIdx, counter);
    assertThat(data).isEmpty();

    // Add second pair after eviction — must still be tracked in versionIndex
    sub.addSnapshotPair(
        new CompositeKey("keyB", 20L), new CompositeKey("keyB", 30L), rid2);
    assertThat(data).hasSize(2);
    assertThat(verIdx).hasSize(1);

    // Evict second pair (removedKey version 30 < 31)
    AbstractStorage.evictStaleIndexesSnapshotEntries(31L, data, verIdx, counter);
    assertThat(data)
        .as("second pair must be evicted after interleaved add/evict")
        .isEmpty();
    assertThat(verIdx).isEmpty();
  }

  // --- clear() isolation between sub-snapshots ---

  /**
   * Clearing one sub-snapshot (e.g., when an index engine is deleted) must not affect
   * entries or versionIndex mappings belonging to other sub-snapshots. The shared
   * versionIndex must only lose the entries owned by the cleared sub-snapshot.
   */
  @Test
  public void testClearOneSubSnapshotPreservesOther() {
    var data = new ConcurrentSkipListMap<CompositeKey, RID>();
    var verIdx =
        new ConcurrentSkipListMap<CompositeKey, CompositeKey>(
            AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR);
    var counter = new AtomicLong();

    long indexIdA = 10L;
    long indexIdB = 20L;
    var subA = new IndexesSnapshot(data, verIdx, counter, indexIdA);
    var subB = new IndexesSnapshot(data, verIdx, counter, indexIdB);

    var rid1 = new RecordId(1, 100L);
    var rid2 = new RecordId(2, 200L);

    subA.addSnapshotPair(
        new CompositeKey("keyA", 5L), new CompositeKey("keyA", 15L), rid1);
    subB.addSnapshotPair(
        new CompositeKey("keyB", 8L), new CompositeKey("keyB", 18L), rid2);

    assertThat(data).hasSize(4);
    assertThat(verIdx).hasSize(2);

    // Clear engine A's sub-snapshot (simulates DROP INDEX for index A)
    subA.clear();

    assertThat(subA.allEntries()).isEmpty();
    assertThat(subB.allEntries())
        .as("clearing subA must not affect subB's data entries")
        .hasSize(2);
    assertThat(data).hasSize(2);
    assertThat(verIdx)
        .as("clearing subA must not remove subB's versionIndex entries")
        .hasSize(1);

    // Eviction must still work for the surviving entries (removedKey version 18 < 19)
    AbstractStorage.evictStaleIndexesSnapshotEntries(19L, data, verIdx, counter);
    assertThat(data).isEmpty();
    assertThat(verIdx).isEmpty();
  }
}
