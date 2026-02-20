package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

  // --- Helper methods ---

  private static List<SnapshotKey> collectKeys(
      Iterable<Map.Entry<SnapshotKey, PositionEntry>> iterable) {
    var keys = new ArrayList<SnapshotKey>();
    for (var entry : iterable) {
      keys.add(entry.getKey());
    }
    return keys;
  }

  private static List<SnapshotKey> collectIteratorKeys(
      java.util.Iterator<Map.Entry<SnapshotKey, PositionEntry>> iter) {
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
