package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the indexes snapshot cleanup logic in {@link AbstractStorage}, specifically the static
 * {@link AbstractStorage#evictStaleIndexesSnapshotEntries} method which delegates to {@link
 * IndexesSnapshot#evictStaleIndexesSnapshotEntries(long)}.
 *
 * <p>Mirrors the structure of {@link EdgeSnapshotIndexCleanupTest} for consistency. Each test
 * populates the snapshot via {@link IndexesSnapshot#addSnapshotPair}, then invokes eviction through
 * the AbstractStorage static method and verifies the expected entries remain.
 */
public class IndexesSnapshotCleanupTest {

  private static final long INDEX_ID = 42L;
  private IndexesSnapshot indexesSnapshot;
  private IndexesSnapshot nullIndexesSnapshot;

  @Before
  public void setUp() {
    indexesSnapshot = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
    nullIndexesSnapshot = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
  }

  @After
  public void tearDown() {
    indexesSnapshot = null;
    nullIndexesSnapshot = null;
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

    // lwm = 10: entries with addedKey version < 10 (i.e., version 5 and 8) should be evicted
    AbstractStorage.evictStaleIndexesSnapshotEntries(10L, indexesSnapshot, nullIndexesSnapshot);

    // Both pairs should be fully evicted (addedKey and removedKey entries removed)
    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictPreservesEntriesAtLwm() {
    // Entry with addedKey version exactly at lwm should be preserved (headMap is exclusive)
    var rid = new RecordId(1, 100L);
    indexesSnapshot.addSnapshotPair(
        new CompositeKey("keyA", 10L),
        new CompositeKey("keyA", 20L),
        rid);

    // lwm = 10: entry with version=10 should be PRESERVED
    AbstractStorage.evictStaleIndexesSnapshotEntries(10L, indexesSnapshot, nullIndexesSnapshot);

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
    AbstractStorage.evictStaleIndexesSnapshotEntries(100L, indexesSnapshot, nullIndexesSnapshot);

    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictWithEmptySnapshot() {
    // Eviction on an empty snapshot should not fail
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        100L, indexesSnapshot, nullIndexesSnapshot);

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

    AbstractStorage.evictStaleIndexesSnapshotEntries(0L, indexesSnapshot, nullIndexesSnapshot);

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

    AbstractStorage.evictStaleIndexesSnapshotEntries(
        Long.MAX_VALUE, indexesSnapshot, nullIndexesSnapshot);

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

    // lwm = 15: evicts pairs with addedKey version 5 and 10, preserves version 20
    AbstractStorage.evictStaleIndexesSnapshotEntries(15L, indexesSnapshot, nullIndexesSnapshot);

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

    AbstractStorage.evictStaleIndexesSnapshotEntries(10L, indexesSnapshot, nullIndexesSnapshot);

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

    // lwm = 5: evicts pair with addedKey version 3, preserves pair with version 7
    AbstractStorage.evictStaleIndexesSnapshotEntries(5L, indexesSnapshot, nullIndexesSnapshot);

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

    AbstractStorage.evictStaleIndexesSnapshotEntries(20L, indexesSnapshot, nullIndexesSnapshot);
    // Second call — snapshot is already empty, should not fail
    AbstractStorage.evictStaleIndexesSnapshotEntries(20L, indexesSnapshot, nullIndexesSnapshot);

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

    // lwm = 10: entry with addedKey version 5 should be evicted
    AbstractStorage.evictStaleIndexesSnapshotEntries(10L, indexesSnapshot, nullIndexesSnapshot);

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

    // lwm = 10: entry with addedKey version 15 should be preserved
    AbstractStorage.evictStaleIndexesSnapshotEntries(10L, indexesSnapshot, nullIndexesSnapshot);

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

    // lwm = 15: evicts null-key v5 and non-null v8, preserves null-key v20
    AbstractStorage.evictStaleIndexesSnapshotEntries(15L, indexesSnapshot, nullIndexesSnapshot);

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
    // Keys are CompositeKey(RID, version). Eviction must be called on the sub-snapshot
    // where entries (and the versionIndex) live.
    var rid = new RecordId(1, 100L);
    nullIndexesSnapshot.addSnapshotPair(
        new CompositeKey(rid, 5L),
        new CompositeKey(rid, 15L),
        rid);

    // lwm = 10: should be evicted via the nullIndexesSnapshot parameter
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        10L, indexesSnapshot, nullIndexesSnapshot);

    assertThat(nullIndexesSnapshot.allEntries()).isEmpty();
  }

  @Test
  public void testEvictViaSubSnapshotsEvictsBothSvAndNullEntries() {
    // Eviction via AbstractStorage must clean up entries from both indexesSnapshot
    // and nullIndexesSnapshot in a single call.
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

    // Evict both sub-snapshots in a single call
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        10L, indexesSnapshot, nullIndexesSnapshot);

    assertThat(indexesSnapshot.allEntries())
        .as("svTree sub-snapshot must be empty after eviction")
        .isEmpty();
    assertThat(nullIndexesSnapshot.allEntries())
        .as("nullTree sub-snapshot must be empty after eviction")
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

    // Evict both sub-snapshots
    AbstractStorage.evictStaleIndexesSnapshotEntries(
        10L, indexesSnapshot, nullIndexesSnapshot);

    assertThat(indexesSnapshot.allEntries())
        .as("svTree entry at version 5 must be evicted")
        .isEmpty();
    assertThat(nullIndexesSnapshot.allEntries())
        .as("nullTree entry at version 20 must be preserved, version 3 evicted")
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

    // First eviction: lwm=8, evicts version 5 only → 2 pairs remain (4 entries)
    AbstractStorage.evictStaleIndexesSnapshotEntries(8L, indexesSnapshot, nullIndexesSnapshot);
    assertThat(indexesSnapshot.allEntries()).hasSize(4);

    // Second eviction: lwm=15, evicts version 10 → 1 pair remains (2 entries)
    AbstractStorage.evictStaleIndexesSnapshotEntries(15L, indexesSnapshot, nullIndexesSnapshot);
    assertThat(indexesSnapshot.allEntries()).hasSize(2);

    // Third eviction: lwm=25, evicts version 20 → all gone
    AbstractStorage.evictStaleIndexesSnapshotEntries(25L, indexesSnapshot, nullIndexesSnapshot);
    assertThat(indexesSnapshot.allEntries()).isEmpty();
  }
}
