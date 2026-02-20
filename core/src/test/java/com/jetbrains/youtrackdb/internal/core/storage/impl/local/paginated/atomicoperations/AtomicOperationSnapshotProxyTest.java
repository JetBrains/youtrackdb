package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationBinaryTracking.MergingDescendingIterator;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Before;
import org.junit.Test;

public class AtomicOperationSnapshotProxyTest {

  private ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex;
  private ConcurrentSkipListMap<VisibilityKey, SnapshotKey> sharedVisibilityIndex;
  private AtomicOperationBinaryTracking operation;

  @Before
  public void setUp() {
    sharedSnapshotIndex = new ConcurrentSkipListMap<>();
    sharedVisibilityIndex = new ConcurrentSkipListMap<>();
    operation = createOperation();
  }

  private AtomicOperationBinaryTracking createOperation() {
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);
    when(writeCache.getStorageName()).thenReturn("test-storage");
    var snapshot = new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet());
    return new AtomicOperationBinaryTracking(
        readCache, writeCache, 1, snapshot,
        sharedSnapshotIndex, sharedVisibilityIndex);
  }

  // --- putSnapshotEntry / getSnapshotEntry ---

  @Test
  public void testPutAndGetSnapshotEntry() {
    var key = new SnapshotKey(1, 50L, 10L);
    var value = new PositionEntry(1L, 0, 10L);

    operation.putSnapshotEntry(key, value);

    assertThat(operation.getSnapshotEntry(key)).isEqualTo(value);
  }

  @Test
  public void testGetSnapshotEntryFallsBackToSharedMap() {
    var key = new SnapshotKey(1, 50L, 10L);
    var value = new PositionEntry(1L, 0, 10L);
    sharedSnapshotIndex.put(key, value);

    assertThat(operation.getSnapshotEntry(key)).isEqualTo(value);
  }

  @Test
  public void testGetSnapshotEntryReturnsNullWhenNotFound() {
    var key = new SnapshotKey(1, 50L, 10L);

    assertThat(operation.getSnapshotEntry(key)).isNull();
  }

  @Test
  public void testLocalSnapshotBufferShadowsSharedMap() {
    var key = new SnapshotKey(1, 50L, 10L);
    var sharedValue = new PositionEntry(1L, 0, 10L);
    var localValue = new PositionEntry(2L, 1, 10L);
    sharedSnapshotIndex.put(key, sharedValue);

    operation.putSnapshotEntry(key, localValue);

    assertThat(operation.getSnapshotEntry(key)).isEqualTo(localValue);
  }

  // --- putVisibilityEntry / containsVisibilityEntry ---

  @Test
  public void testPutAndContainsVisibilityEntry() {
    var key = new VisibilityKey(100L, 1, 50L);
    var value = new SnapshotKey(1, 50L, 10L);

    operation.putVisibilityEntry(key, value);

    assertThat(operation.containsVisibilityEntry(key)).isTrue();
  }

  @Test
  public void testContainsVisibilityEntryFallsBackToSharedMap() {
    var key = new VisibilityKey(100L, 1, 50L);
    var value = new SnapshotKey(1, 50L, 10L);
    sharedVisibilityIndex.put(key, value);

    assertThat(operation.containsVisibilityEntry(key)).isTrue();
  }

  @Test
  public void testContainsVisibilityEntryReturnsFalseWhenNotFound() {
    var key = new VisibilityKey(100L, 1, 50L);

    assertThat(operation.containsVisibilityEntry(key)).isFalse();
  }

  @Test
  public void testLocalVisibilityBufferShadowsSharedMap() {
    // The containsVisibilityEntry API only returns boolean, so we can only verify
    // that the key is found when present in both layers. Value-level shadowing
    // (local wins over shared) is verified by testFlushOverwritesSharedEntryOnConflict
    // which asserts the local value overwrites the shared one during flush.
    var key = new VisibilityKey(100L, 1, 50L);
    sharedVisibilityIndex.put(key, new SnapshotKey(1, 50L, 5L));

    operation.putVisibilityEntry(key, new SnapshotKey(1, 50L, 10L));

    assertThat(operation.containsVisibilityEntry(key)).isTrue();
  }

  // --- snapshotSubMapDescending ---

  @Test
  public void testSubMapDescendingNoLocalEntries() {
    sharedSnapshotIndex.put(new SnapshotKey(1, 50L, 1L), new PositionEntry(1L, 0, 1L));
    sharedSnapshotIndex.put(new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));
    sharedSnapshotIndex.put(new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, 10L);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 10L),
        new SnapshotKey(1, 50L, 5L),
        new SnapshotKey(1, 50L, 1L));
  }

  @Test
  public void testSubMapDescendingLocalEntriesOnly() {
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 3L), new PositionEntry(1L, 0, 3L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 7L), new PositionEntry(1L, 0, 7L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 7L),
        new SnapshotKey(1, 50L, 3L));
  }

  @Test
  public void testSubMapDescendingMergedView() {
    // Shared: versions 1, 5
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 1L), new PositionEntry(1L, 0, 1L));
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));

    // Local: versions 3, 7
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 3L), new PositionEntry(2L, 1, 3L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 7L), new PositionEntry(2L, 1, 7L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 7L),
        new SnapshotKey(1, 50L, 5L),
        new SnapshotKey(1, 50L, 3L),
        new SnapshotKey(1, 50L, 1L));
  }

  @Test
  public void testSubMapDescendingLocalShadowsSharedOnEqualKey() {
    var key = new SnapshotKey(1, 50L, 5L);
    var sharedValue = new PositionEntry(1L, 0, 5L);
    var localValue = new PositionEntry(2L, 1, 5L);

    sharedSnapshotIndex.put(key, sharedValue);
    operation.putSnapshotEntry(key, localValue);

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = new ArrayList<Map.Entry<SnapshotKey, PositionEntry>>();
    for (var entry : operation.snapshotSubMapDescending(from, to)) {
      result.add(entry);
    }

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getKey()).isEqualTo(key);
    assertThat(result.get(0).getValue()).isEqualTo(localValue);
  }

  @Test
  public void testSubMapDescendingEmptyRange() {
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(2L, 1, 10L));

    // Query a range that has no entries
    var from = new SnapshotKey(2, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(2, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).isEmpty();
  }

  @Test
  public void testSubMapDescendingDoesNotIncludeOutOfRangeLocal() {
    // Local entry for a different component
    operation.putSnapshotEntry(
        new SnapshotKey(2, 50L, 3L), new PositionEntry(2L, 1, 3L));
    // Shared entry in range
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(new SnapshotKey(1, 50L, 5L));
  }

  @Test
  public void testSubMapDescendingEarlyTermination() {
    // Simulate the findHistoricalPositionEntry pattern: iterate in descending order,
    // stop at first match.
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 1L), new PositionEntry(1L, 0, 1L));
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, 10L);

    PositionEntry found = null;
    for (var entry : operation.snapshotSubMapDescending(from, to)) {
      // Simulate "first visible" check — pick version 5
      if (entry.getKey().recordVersion() == 5L) {
        found = entry.getValue();
        break;
      }
    }

    assertThat(found).isNotNull();
    assertThat(found.getRecordVersion()).isEqualTo(5L);
  }

  // --- Branch coverage: local buffer exists but key not found ---

  @Test
  public void testGetSnapshotEntryLocalBufferExistsButKeyMissing() {
    // Put one key to allocate the local buffer
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    // Lookup a different key — exercises localSnapshotBuffer != null, local == null branch
    var sharedValue = new PositionEntry(2L, 0, 5L);
    sharedSnapshotIndex.put(new SnapshotKey(1, 50L, 5L), sharedValue);

    assertThat(operation.getSnapshotEntry(new SnapshotKey(1, 50L, 5L)))
        .isEqualTo(sharedValue);
  }

  @Test
  public void testContainsVisibilityEntryLocalBufferExistsButKeyMissing() {
    // Put one key to allocate the local buffer
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));
    // Check a different key — exercises localVisibilityBuffer != null but not containing
    sharedVisibilityIndex.put(
        new VisibilityKey(200L, 1, 50L), new SnapshotKey(1, 50L, 5L));

    assertThat(operation.containsVisibilityEntry(
        new VisibilityKey(200L, 1, 50L))).isTrue();
    assertThat(operation.containsVisibilityEntry(
        new VisibilityKey(300L, 1, 50L))).isFalse();
  }

  @Test
  public void testPutVisibilityEntryTwiceReusesBuffer() {
    // First put allocates, second reuses (exercises both branches of null check)
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));
    operation.putVisibilityEntry(
        new VisibilityKey(200L, 1, 50L), new SnapshotKey(1, 50L, 20L));

    assertThat(operation.containsVisibilityEntry(
        new VisibilityKey(100L, 1, 50L))).isTrue();
    assertThat(operation.containsVisibilityEntry(
        new VisibilityKey(200L, 1, 50L))).isTrue();
  }

  // --- flushSnapshotBuffers ---

  @Test
  public void testFlushWithBothBuffersNull() {
    // No puts, so both buffers are null
    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  @Test
  public void testFlushWithSnapshotBufferOnly() {
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));

    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex).hasSize(1);
    assertThat(sharedSnapshotIndex.get(new SnapshotKey(1, 50L, 10L)))
        .isEqualTo(new PositionEntry(1L, 0, 10L));
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  @Test
  public void testFlushWithVisibilityBufferOnly() {
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).hasSize(1);
    assertThat(sharedVisibilityIndex.get(new VisibilityKey(100L, 1, 50L)))
        .isEqualTo(new SnapshotKey(1, 50L, 10L));
  }

  @Test
  public void testFlushWithBothBuffers() {
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 20L), new PositionEntry(2L, 0, 20L));
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex).hasSize(2);
    assertThat(sharedVisibilityIndex).hasSize(1);
  }

  @Test
  public void testFlushOverwritesSharedEntryOnConflict() {
    // When local and shared maps have the same key, flush (putAll) overwrites
    // the shared entry with the local value.
    var snapKey = new SnapshotKey(1, 50L, 10L);
    var sharedSnapValue = new PositionEntry(1L, 0, 10L);
    var localSnapValue = new PositionEntry(2L, 1, 10L);
    sharedSnapshotIndex.put(snapKey, sharedSnapValue);
    operation.putSnapshotEntry(snapKey, localSnapValue);

    var visKey = new VisibilityKey(100L, 1, 50L);
    var sharedVisValue = new SnapshotKey(1, 50L, 5L);
    var localVisValue = new SnapshotKey(1, 50L, 10L);
    sharedVisibilityIndex.put(visKey, sharedVisValue);
    operation.putVisibilityEntry(visKey, localVisValue);

    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex.get(snapKey)).isEqualTo(localSnapValue);
    assertThat(sharedVisibilityIndex.get(visKey)).isEqualTo(localVisValue);
  }

  @Test
  public void testFlushDoesNotAffectPreviousSharedEntries() {
    // Pre-existing shared entries should be preserved
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 1L), new PositionEntry(0L, 0, 1L));
    sharedVisibilityIndex.put(
        new VisibilityKey(50L, 1, 50L), new SnapshotKey(1, 50L, 1L));

    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));

    operation.flushSnapshotBuffers();

    assertThat(sharedSnapshotIndex).hasSize(2);
    assertThat(sharedSnapshotIndex.get(new SnapshotKey(1, 50L, 1L)))
        .isEqualTo(new PositionEntry(0L, 0, 1L));
    assertThat(sharedVisibilityIndex).hasSize(1);
  }

  // --- Lazy allocation ---

  @Test
  public void testLazyAllocationNoBuffersUntilFirstPut() {
    // Accessing getSnapshotEntry on a fresh operation should not allocate
    assertThat(operation.getSnapshotEntry(new SnapshotKey(1, 50L, 10L))).isNull();
    assertThat(operation.containsVisibilityEntry(
        new VisibilityKey(100L, 1, 50L))).isFalse();

    // Internal buffers should still be null — verified indirectly by the fact that
    // snapshotSubMapDescending returns the shared view directly (fast path)
    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));
    assertThat(result).isEmpty();
  }

  // --- Deactivated operation ---

  @Test
  public void testDeactivatedOperationRejectsSnapshotPut() {
    operation.deactivate();
    assertThatThrownBy(() ->
        operation.putSnapshotEntry(
            new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L)))
        .isInstanceOf(DatabaseException.class);
  }

  @Test
  public void testDeactivatedOperationRejectsSnapshotGet() {
    operation.deactivate();
    assertThatThrownBy(() ->
        operation.getSnapshotEntry(new SnapshotKey(1, 50L, 10L)))
        .isInstanceOf(DatabaseException.class);
  }

  @Test
  public void testDeactivatedOperationRejectsSubMapDescending() {
    operation.deactivate();
    assertThatThrownBy(() ->
        operation.snapshotSubMapDescending(
            new SnapshotKey(1, 50L, Long.MIN_VALUE),
            new SnapshotKey(1, 50L, Long.MAX_VALUE)))
        .isInstanceOf(DatabaseException.class);
  }

  @Test
  public void testDeactivatedOperationRejectsVisibilityPut() {
    operation.deactivate();
    assertThatThrownBy(() ->
        operation.putVisibilityEntry(
            new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L)))
        .isInstanceOf(DatabaseException.class);
  }

  @Test
  public void testDeactivatedOperationRejectsVisibilityContains() {
    operation.deactivate();
    assertThatThrownBy(() ->
        operation.containsVisibilityEntry(new VisibilityKey(100L, 1, 50L)))
        .isInstanceOf(DatabaseException.class);
  }

  // --- Rollback: buffers discarded ---

  @Test
  public void testRollbackDiscardsLocalBuffers() {
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    // Simulate rollback: deactivate discards the operation
    operation.deactivate();

    // Shared maps should be unchanged
    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  // --- MergingDescendingIterator ---

  @Test
  public void testMergeIteratorBothEmpty() {
    var iter = new MergingDescendingIterator(
        Collections.emptyIterator(), Collections.emptyIterator());

    assertThat(iter.hasNext()).isFalse();
  }

  @Test
  public void testMergeIteratorSharedOnly() {
    var shared = List.of(
        entry(new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L)),
        entry(new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L)));
    var iter = new MergingDescendingIterator(
        shared.iterator(), Collections.emptyIterator());

    var result = collectIteratorKeys(iter);
    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 10L),
        new SnapshotKey(1, 50L, 5L));
  }

  @Test
  public void testMergeIteratorLocalOnly() {
    var local = List.of(
        entry(new SnapshotKey(1, 50L, 7L), new PositionEntry(2L, 1, 7L)),
        entry(new SnapshotKey(1, 50L, 3L), new PositionEntry(2L, 1, 3L)));
    var iter = new MergingDescendingIterator(
        Collections.emptyIterator(), local.iterator());

    var result = collectIteratorKeys(iter);
    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 7L),
        new SnapshotKey(1, 50L, 3L));
  }

  @Test
  public void testMergeIteratorInterleavedKeys() {
    // Shared: 10, 5, 1 (descending)
    var shared = List.of(
        entry(new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L)),
        entry(new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L)),
        entry(new SnapshotKey(1, 50L, 1L), new PositionEntry(1L, 0, 1L)));
    // Local: 7, 3 (descending)
    var local = List.of(
        entry(new SnapshotKey(1, 50L, 7L), new PositionEntry(2L, 1, 7L)),
        entry(new SnapshotKey(1, 50L, 3L), new PositionEntry(2L, 1, 3L)));

    var iter = new MergingDescendingIterator(shared.iterator(), local.iterator());
    var result = collectIteratorKeys(iter);

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 10L),
        new SnapshotKey(1, 50L, 7L),
        new SnapshotKey(1, 50L, 5L),
        new SnapshotKey(1, 50L, 3L),
        new SnapshotKey(1, 50L, 1L));
  }

  @Test
  public void testMergeIteratorEqualKeysLocalWins() {
    var key = new SnapshotKey(1, 50L, 5L);
    var sharedValue = new PositionEntry(1L, 0, 5L);
    var localValue = new PositionEntry(2L, 1, 5L);

    var shared = List.of(entry(key, sharedValue));
    var local = List.of(entry(key, localValue));

    var iter = new MergingDescendingIterator(shared.iterator(), local.iterator());

    assertThat(iter.hasNext()).isTrue();
    var result = iter.next();
    assertThat(result.getKey()).isEqualTo(key);
    assertThat(result.getValue()).isEqualTo(localValue);
    assertThat(iter.hasNext()).isFalse();
  }

  @Test
  public void testMergeIteratorMultipleEqualKeys() {
    // Shared: 10, 5, 1 — Local: 10, 5 (both shadow shared)
    var shared = List.of(
        entry(new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L)),
        entry(new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L)),
        entry(new SnapshotKey(1, 50L, 1L), new PositionEntry(1L, 0, 1L)));
    var local = List.of(
        entry(new SnapshotKey(1, 50L, 10L), new PositionEntry(2L, 1, 10L)),
        entry(new SnapshotKey(1, 50L, 5L), new PositionEntry(2L, 1, 5L)));

    var iter = new MergingDescendingIterator(shared.iterator(), local.iterator());
    var result = new ArrayList<Map.Entry<SnapshotKey, PositionEntry>>();
    while (iter.hasNext()) {
      result.add(iter.next());
    }

    assertThat(result).hasSize(3);
    // version 10: local value
    assertThat(result.get(0).getValue()).isEqualTo(new PositionEntry(2L, 1, 10L));
    // version 5: local value
    assertThat(result.get(1).getValue()).isEqualTo(new PositionEntry(2L, 1, 5L));
    // version 1: shared value (no local for this key)
    assertThat(result.get(2).getValue()).isEqualTo(new PositionEntry(1L, 0, 1L));
  }

  @Test
  public void testMergeIteratorExhaustedThrowsNoSuchElementException() {
    // Iterate a single-element iterator to exhaustion, then call next() again.
    var shared = List.of(
        entry(new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L)));
    var iter = new MergingDescendingIterator(
        shared.iterator(), Collections.emptyIterator());

    assertThat(iter.hasNext()).isTrue();
    iter.next();
    assertThat(iter.hasNext()).isFalse();

    assertThatThrownBy(iter::next).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  public void testMergeIteratorSharedLargerIsLastEntry() {
    // Shared has a single entry with a larger key than local's single entry.
    // After emitting the shared entry (cmp < 0 path), sharedIter is exhausted
    // → exercises the branch where sharedIter has no more entries after yielding
    // a shared entry whose key is larger than the current local entry.
    var shared = List.of(
        entry(new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L)));
    var local = List.of(
        entry(new SnapshotKey(1, 50L, 3L), new PositionEntry(2L, 1, 3L)));

    var iter = new MergingDescendingIterator(shared.iterator(), local.iterator());
    var result = collectIteratorKeys(iter);

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 10L),
        new SnapshotKey(1, 50L, 3L));
  }

  // --- Additional proxy method tests ---

  @Test
  public void testSubMapDescendingLocalEntryOutsideQueryRange() {
    // Local buffer is allocated (non-null) but its entries are outside the
    // queried range → localDescending.isEmpty() returns true, fast path returns
    // shared view directly.
    operation.putSnapshotEntry(
        new SnapshotKey(2, 100L, 50L), new PositionEntry(2L, 1, 50L));
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(new SnapshotKey(1, 50L, 5L));
  }

  @Test
  public void testMultiplePutsOverwriteInLocalSnapshotBuffer() {
    // Two puts to the same key — second value should win.
    var key = new SnapshotKey(1, 50L, 10L);
    var first = new PositionEntry(1L, 0, 10L);
    var second = new PositionEntry(2L, 1, 10L);

    operation.putSnapshotEntry(key, first);
    operation.putSnapshotEntry(key, second);

    assertThat(operation.getSnapshotEntry(key)).isEqualTo(second);
  }

  @Test
  public void testSubMapDescendingMultipleComponentsFiltering() {
    // Entries for components 1 and 2 in both shared and local.
    // Range scan for component 1 must not include component 2 entries.
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 5L), new PositionEntry(1L, 0, 5L));
    sharedSnapshotIndex.put(
        new SnapshotKey(2, 50L, 5L), new PositionEntry(1L, 0, 5L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(2L, 1, 10L));
    operation.putSnapshotEntry(
        new SnapshotKey(2, 50L, 10L), new PositionEntry(2L, 1, 10L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 10L),
        new SnapshotKey(1, 50L, 5L));
  }

  @Test
  public void testFreshOperationSubMapReturnsSharedEntries() {
    // A fresh operation (no local writes) should return shared entries directly
    // via the fast path (localSnapshotBuffer == null).
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 3L), new PositionEntry(1L, 0, 3L));
    sharedSnapshotIndex.put(
        new SnapshotKey(1, 50L, 7L), new PositionEntry(1L, 0, 7L));

    var from = new SnapshotKey(1, 50L, Long.MIN_VALUE);
    var to = new SnapshotKey(1, 50L, Long.MAX_VALUE);
    var result = collectKeys(operation.snapshotSubMapDescending(from, to));

    assertThat(result).containsExactly(
        new SnapshotKey(1, 50L, 7L),
        new SnapshotKey(1, 50L, 3L));
  }

  @Test
  public void testMultiplePutsToDistinctKeysAccumulate() {
    // First put allocates the TreeMap, second reuses it — both keys are stored.
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 20L), new PositionEntry(2L, 0, 20L));

    assertThat(operation.getSnapshotEntry(new SnapshotKey(1, 50L, 10L)))
        .isEqualTo(new PositionEntry(1L, 0, 10L));
    assertThat(operation.getSnapshotEntry(new SnapshotKey(1, 50L, 20L)))
        .isEqualTo(new PositionEntry(2L, 0, 20L));
  }

  @Test
  public void testMultipleOperationsIsolation() {
    // Two AtomicOperations share the same shared maps. Local buffers are isolated;
    // flushed entries from one become visible to the other via shared maps.
    var op2 = createOperation();

    // op1 puts locally
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    // op2 should NOT see op1's local entries
    assertThat(op2.getSnapshotEntry(new SnapshotKey(1, 50L, 10L))).isNull();
    assertThat(op2.containsVisibilityEntry(new VisibilityKey(100L, 1, 50L)))
        .isFalse();

    // Flush op1's buffers to shared maps
    operation.flushSnapshotBuffers();

    // Now op2 sees them via shared map fallback
    assertThat(op2.getSnapshotEntry(new SnapshotKey(1, 50L, 10L)))
        .isEqualTo(new PositionEntry(1L, 0, 10L));
    assertThat(op2.containsVisibilityEntry(new VisibilityKey(100L, 1, 50L)))
        .isTrue();
  }

  // --- Buffered flush via commitChanges() ---

  @Test
  public void testCommitChangesFlushesBuffersViaWal() throws IOException {
    // Happy path: commitChanges() should flush local buffers to shared maps.
    var snapKey = new SnapshotKey(1, 50L, 10L);
    var snapValue = new PositionEntry(1L, 0, 10L);
    var visKey = new VisibilityKey(100L, 1, 50L);
    var visValue = new SnapshotKey(1, 50L, 10L);

    operation.putSnapshotEntry(snapKey, snapValue);
    operation.putVisibilityEntry(visKey, visValue);

    // Shared maps are empty before commit
    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();

    operation.commitChanges(42L, createMockWal());

    // After commit, buffers are flushed to shared maps
    assertThat(sharedSnapshotIndex).hasSize(1);
    assertThat(sharedSnapshotIndex.get(snapKey)).isEqualTo(snapValue);
    assertThat(sharedVisibilityIndex).hasSize(1);
    assertThat(sharedVisibilityIndex.get(visKey)).isEqualTo(visValue);
  }

  @Test
  public void testCommitChangesWithEmptyBuffersIsNoOp() throws IOException {
    // No local puts — commitChanges() should not populate shared maps.
    operation.commitChanges(42L, createMockWal());

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  @Test
  public void testRollbackFlagPreventsFlushInCommitChanges() throws IOException {
    // Tests the defensive `if (!rollback)` guard inside commitChanges().
    // In production, AtomicOperationsManager skips commitChanges() entirely when
    // rollback is in progress. This test exercises the belt-and-suspenders guard:
    // even if commitChanges() were called after rollbackInProgress(), buffers must
    // NOT be flushed to shared maps.
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    operation.rollbackInProgress();
    operation.commitChanges(42L, createMockWal());

    assertThat(operation.isActive()).isFalse();
    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  // --- Rollback: realistic error path ---

  @Test
  public void testRollbackViaEndAtomicOperationPattern() {
    // Simulates the realistic error path from AtomicOperationsManager.endAtomicOperation():
    // 1. put entries  2. rollbackInProgress()  3. skip commitChanges()  4. deactivate()
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    operation.putVisibilityEntry(
        new VisibilityKey(100L, 1, 50L), new SnapshotKey(1, 50L, 10L));

    // Error path: set rollback flag, then deactivate (commitChanges never called)
    operation.rollbackInProgress();
    assertThat(operation.isRollbackInProgress()).isTrue();
    operation.deactivate();

    // Shared maps must remain untouched
    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  @Test
  public void testRollbackPreservesOtherOperationCommittedEntries() {
    // Op1 commits its entries to shared maps. Op2 populates buffers then rolls
    // back. Op1's entries must survive; op2's must never appear.
    var op1SnapKey = new SnapshotKey(1, 50L, 5L);
    var op1SnapValue = new PositionEntry(1L, 0, 5L);
    var op1VisKey = new VisibilityKey(50L, 1, 50L);
    var op1VisValue = new SnapshotKey(1, 50L, 5L);

    // Op1 commits
    operation.putSnapshotEntry(op1SnapKey, op1SnapValue);
    operation.putVisibilityEntry(op1VisKey, op1VisValue);
    operation.flushSnapshotBuffers();
    operation.deactivate();

    assertThat(sharedSnapshotIndex).hasSize(1);
    assertThat(sharedVisibilityIndex).hasSize(1);

    // Op2 populates buffers then rolls back
    var op2 = createOperation();
    op2.putSnapshotEntry(
        new SnapshotKey(2, 60L, 20L), new PositionEntry(3L, 0, 20L));
    op2.putVisibilityEntry(
        new VisibilityKey(200L, 2, 60L), new SnapshotKey(2, 60L, 20L));
    op2.rollbackInProgress();
    op2.deactivate();

    // Op1's entries are intact; op2's entries never appeared
    assertThat(sharedSnapshotIndex).hasSize(1);
    assertThat(sharedSnapshotIndex.get(op1SnapKey)).isEqualTo(op1SnapValue);
    assertThat(sharedVisibilityIndex).hasSize(1);
    assertThat(sharedVisibilityIndex.get(op1VisKey)).isEqualTo(op1VisValue);
  }

  @Test
  public void testSequentialCommitRollbackCommitCycle() throws IOException {
    // Op1 commits, op2 rolls back, op3 commits. Shared maps must contain entries
    // from op1 and op3 only. Op2's rollback must not corrupt op1's entries.
    var snap1 = new SnapshotKey(1, 10L, 1L);
    var val1 = new PositionEntry(1L, 0, 1L);
    var vis1Key = new VisibilityKey(10L, 1, 10L);
    var vis1Value = new SnapshotKey(1, 10L, 1L);
    operation.putSnapshotEntry(snap1, val1);
    operation.putVisibilityEntry(vis1Key, vis1Value);
    operation.commitChanges(10L, createMockWal());
    assertThat(operation.isActive()).isFalse();

    // Op2 rolls back
    var op2 = createOperation();
    op2.putSnapshotEntry(new SnapshotKey(1, 20L, 2L), new PositionEntry(2L, 0, 2L));
    op2.putVisibilityEntry(
        new VisibilityKey(20L, 1, 20L), new SnapshotKey(1, 20L, 2L));
    op2.rollbackInProgress();
    op2.deactivate();

    // Op3 commits
    var op3 = createOperation();
    var snap3 = new SnapshotKey(1, 30L, 3L);
    var val3 = new PositionEntry(3L, 0, 3L);
    var vis3Key = new VisibilityKey(30L, 1, 30L);
    var vis3Value = new SnapshotKey(1, 30L, 3L);
    op3.putSnapshotEntry(snap3, val3);
    op3.putVisibilityEntry(vis3Key, vis3Value);
    op3.commitChanges(30L, createMockWal());
    assertThat(op3.isActive()).isFalse();

    // Shared maps: op1 + op3 entries only
    assertThat(sharedSnapshotIndex).hasSize(2);
    assertThat(sharedSnapshotIndex.get(snap1)).isEqualTo(val1);
    assertThat(sharedSnapshotIndex.get(snap3)).isEqualTo(val3);
    assertThat(sharedVisibilityIndex).hasSize(2);
    assertThat(sharedVisibilityIndex.get(vis1Key)).isEqualTo(vis1Value);
    assertThat(sharedVisibilityIndex.get(vis3Key)).isEqualTo(vis3Value);
  }

  @Test
  public void testRollbackWithOnlySnapshotBufferPopulated() {
    // Populate only the snapshot buffer (not visibility). Rollback must not leak
    // any partial buffer state to shared maps.
    operation.putSnapshotEntry(
        new SnapshotKey(1, 50L, 10L), new PositionEntry(1L, 0, 10L));
    // Deliberately do NOT put any visibility entry

    operation.rollbackInProgress();
    operation.deactivate();

    assertThat(sharedSnapshotIndex).isEmpty();
    assertThat(sharedVisibilityIndex).isEmpty();
  }

  // --- Helper methods ---

  private static WriteAheadLog createMockWal() throws IOException {
    var wal = mock(WriteAheadLog.class);
    var lsn = new LogSequenceNumber(1, 0);
    when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong())).thenReturn(lsn);
    when(wal.end()).thenReturn(lsn);
    when(wal.log(any(WriteableWALRecord.class))).thenReturn(lsn);
    return wal;
  }

  private static List<SnapshotKey> collectKeys(
      Iterable<Map.Entry<SnapshotKey, PositionEntry>> iterable) {
    var keys = new ArrayList<SnapshotKey>();
    for (var entry : iterable) {
      keys.add(entry.getKey());
    }
    return keys;
  }

  private static List<SnapshotKey> collectIteratorKeys(
      Iterator<Map.Entry<SnapshotKey, PositionEntry>> iter) {
    var keys = new ArrayList<SnapshotKey>();
    while (iter.hasNext()) {
      keys.add(iter.next().getKey());
    }
    return keys;
  }

  private static Map.Entry<SnapshotKey, PositionEntry> entry(
      SnapshotKey key, PositionEntry value) {
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }
}
