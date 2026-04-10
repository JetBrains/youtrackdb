package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface CellBTreeSingleValue<K> {

  void create(
      @Nonnull AtomicOperation atomicOperation,
      BinarySerializer<K> keySerializer,
      PropertyTypeInternal[] keyTypes,
      int keySize)
      throws IOException;

  @Nullable RID get(K key, @Nonnull AtomicOperation atomicOperation);

  /**
   * Returns the first visible RID for the given key according to the snapshot isolation
   * visibility rules. Uses optimistic lock-free reads on the happy path with pinned
   * fallback. This is an SI-aware operation — visibility is applied inline during the
   * leaf scan via {@link IndexesSnapshot#checkVisibility}.
   *
   * @param key the user key (without version component)
   * @param snapshot the indexes snapshot for visibility checks
   * @param atomicOperation the current atomic operation (provides snapshot version)
   * @return the visible RID, or null if no visible entry exists for this key
   */
  @Nullable RID getVisible(K key, IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation);

  /**
   * Returns all visible RIDs for the given key according to snapshot isolation visibility
   * rules. This is the multi-value variant of {@link #getVisible} — instead of returning
   * only the first visible entry, it collects ALL visible entries with the same user-key
   * prefix into a stream. Uses the same optimistic lock-free leaf descent with pinned
   * fallback.
   *
   * @param key the user key (without version component)
   * @param snapshot the indexes snapshot for visibility checks
   * @param atomicOperation the current atomic operation (provides snapshot version)
   * @return a stream of all visible RIDs for this key (may be empty)
   */
  Stream<RID> getVisibleStream(K key, IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation);

  boolean put(@Nonnull AtomicOperation atomicOperation, K key, RID value) throws IOException;

  /**
   * Puts a key-value pair after consulting the validator.
   *
   * @return {@code 1} if a new key was inserted, {@code 0} if an existing key was updated,
   *     {@code -1} if the validator rejected the operation (IGNORE).
   */
  int validatedPut(
      @Nonnull AtomicOperation atomicOperation, K key, RID value,
      IndexEngineValidator<K, RID> validator)
      throws IOException;

  void close();

  void delete(@Nonnull AtomicOperation atomicOperation) throws IOException;

  void load(
      String name,
      int keySize,
      PropertyTypeInternal[] keyTypes,
      BinarySerializer<K> keySerializer, @Nonnull AtomicOperation atomicOperation);

  long size(@Nonnull AtomicOperation atomicOperation);

  @Nullable RID remove(@Nonnull AtomicOperation atomicOperation, K key) throws IOException;

  Stream<RawPair<K, RID>> iterateEntriesMinor(K key, boolean inclusive, boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> iterateEntriesMajor(K key, boolean inclusive, boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation);

  @Nullable K firstKey(@Nonnull AtomicOperation atomicOperation);

  @Nullable K lastKey(@Nonnull AtomicOperation atomicOperation);

  Stream<K> keyStream(@Nonnull AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> allEntries(@Nonnull AtomicOperation atomicOperation);

  Stream<RawPair<K, RID>> iterateEntriesBetween(
      K keyFrom, boolean fromInclusive, K keyTo, boolean toInclusive, boolean ascSortOrder,
      @Nonnull AtomicOperation atomicOperation);

  void acquireAtomicExclusiveLock(@Nonnull AtomicOperation atomicOperation);

  /**
   * Returns the persisted approximate count of live (non-tombstone) entries.
   * Uses optimistic read with fallback to pinned read.
   */
  long getApproximateEntriesCount(@Nonnull AtomicOperation atomicOperation);

  /**
   * Sets the persisted approximate entry count to an absolute value.
   * Used by {@code clear()} and {@code buildInitialHistogram()} to reset
   * or recalibrate the count.
   */
  void setApproximateEntriesCount(
      @Nonnull AtomicOperation atomicOperation, long count);

  /**
   * Adds a delta to the persisted approximate entry count. Used at commit
   * time to apply accumulated {@code IndexCountDelta} values.
   */
  void addToApproximateEntriesCount(
      @Nonnull AtomicOperation atomicOperation, long delta);
}
