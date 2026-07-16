package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BTreeSingleValueIndexEngine
    implements SingleValueIndexEngine, BTreeIndexEngine {

  private static final String DATA_FILE_EXTENSION = ".cbt";
  private static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";

  private final CellBTreeSingleValue<CompositeKey> sbTree;
  private final IndexesSnapshot indexesSnapshot;
  private final String name;
  private final int id;

  /**
   * The engine's stable, never-reused file base id. All storage-component names derive from it
   * ({@code ie_<fileBaseId>}), so the logical index name ({@link #name}) never touches a file:
   * a drop-and-recreate of a same-named index gets fresh files, and a rename never moves any.
   */
  private final int fileBaseId;
  private final AbstractStorage storage;
  @Nullable private volatile IndexHistogramManager histogramManager;

  // Approximate count of visible index entries, used by the query optimizer for
  // cost estimation. Read from persisted APPROXIMATE_ENTRIES_COUNT on load().
  // Adjusted at commit time via delta holder (not directly in put/remove).
  // AtomicLong because concurrent TXs can commit index changes simultaneously.
  // Recalibrated by buildInitialHistogram() as self-healing.
  private final AtomicLong approximateIndexEntriesCount = new AtomicLong();

  // Approximate count of null-key entries. Recalibrated on load() from a
  // direct null-key lookup (single-value engine stores at most one entry per
  // key, so this is O(1)). Adjusted at commit time via applyIndexCountDeltas
  // and recalibrated by buildInitialHistogram() during create/rebuild.
  private final AtomicLong approximateNullCount = new AtomicLong();

  // One-shot latch for the in-memory counter underflow stack-trace dump.
  // Shared by addToApproximateEntriesCount and addToApproximateNullCount: the
  // first underflow on either mutator (per engine instance) wins the CAS and
  // emits an error log with a stack trace so the next regression is
  // pin-pointable; subsequent underflows on the same engine emit a compact
  // error line without the stack. Resets on storage close + reopen because the
  // engine is re-instantiated.
  private final AtomicBoolean firstUnderflowDumped = new AtomicBoolean(false);

  public BTreeSingleValueIndexEngine(
      int id, int fileBaseId, String name, AbstractStorage storage, int version) {
    this.name = name;
    this.id = id;
    this.fileBaseId = fileBaseId;
    this.storage = storage;

    if (version == 3 || version == 4) {
      // The component (and therefore its files) is keyed by the stable file base id, not the
      // index name — the single name domain for engine storage components.
      this.sbTree =
          new BTree<>(
              AbstractStorage.indexEngineFileStem(fileBaseId),
              DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      this.sbTree.setEngineId(id);
      // User-facing diagnostics report the index's logical name, never the ie_<n> file stem.
      this.sbTree.setDisplayName(name);
      indexesSnapshot = storage.subIndexSnapshot(id);
    } else {
      throw new IllegalStateException("Invalid tree version " + version);
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public int getFileBaseId() {
    return fileBaseId;
  }

  @Override
  public void init(DatabaseSessionEmbedded session, IndexMetadata metadata) {
  }

  @Override
  public void flush() {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void create(@Nonnull AtomicOperation atomicOperation, IndexEngineData data) {
    try {
      final var sbTypes = calculateTypes(data.getKeyTypes());
      sbTree.create(
          atomicOperation,
          new IndexMultiValuKeySerializer(),
          sbTypes,
          data.getKeySize() + 1);
      approximateIndexEntriesCount.set(0);
      approximateNullCount.set(0);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error of creation of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void delete(final @Nonnull AtomicOperation atomicOperation) {
    try {
      doClearTree(atomicOperation);
      indexesSnapshot.clear();
      sbTree.delete(atomicOperation);
      var mgr = histogramManager;
      if (mgr != null) {
        mgr.deleteStatsFile(atomicOperation);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during deletion of index " + name), e,
          storage.getName());
    }
  }

  private void doClearTree(@Nonnull AtomicOperation atomicOperation) {
    // A single iterate-and-remove pass may miss entries when the B-tree
    // spliterator's LSN-based cursor terminates prematurely after page
    // modifications from remove(). Retry until the tree is empty.
    int pass = 0;
    long sizeBeforePass;
    while ((sizeBeforePass = sbTree.size(atomicOperation)) > 0) {
      pass++;
      final int currentPass = pass;
      try (var stream = sbTree.keyStream(atomicOperation)) {
        stream.forEach(
            key -> {
              try {
                var removed = sbTree.remove(atomicOperation, key);
                // Diagnostic: if remove() returns null, the key read from the
                // stream was not found by findBucketForRemove — a serialization
                // round-trip mismatch between getEntry() and find(byte[]).
                assert removed != null
                    : "doClearTree pass " + currentPass + ": remove() returned null"
                        + " for key from stream in engine=" + name + " id=" + id
                        + " key=" + key;
              } catch (IOException e) {
                throw BaseException.wrapException(
                    new IndexException(storage.getName(), "Can not clear index"), e,
                    storage.getName());
              }
            });
      }
      long sizeAfterPass = sbTree.size(atomicOperation);
      long removedInPass = sizeBeforePass - sizeAfterPass;
      // Hard check: if a pass removed zero entries, we would loop forever.
      // This distinguishes spliterator terminating with zero entries (broken
      // cursor) from remove() silently failing (caught by the assert above).
      if (removedInPass <= 0) {
        throw new IndexException(storage.getName(),
            "doClearTree pass " + pass + " removed 0 entries"
                + " (sizeBefore=" + sizeBeforePass + ", sizeAfter=" + sizeAfterPass + ")"
                + " in engine=" + name + " id=" + id);
      }
    }
  }

  @Override
  public void load(IndexEngineData data, @Nonnull AtomicOperation atomicOperation) {
    assert data.getFileBaseId() == fileBaseId
        : "engine constructed for fileBaseId " + fileBaseId + " but loaded with "
            + data.getFileBaseId();
    var keySize = data.getKeySize();
    var keyTypes = data.getKeyTypes();
    final var sbTypes = calculateTypes(keyTypes);

    // Load under the file-base-id stem the component was constructed with — never the index
    // name, which keys no file.
    sbTree.load(AbstractStorage.indexEngineFileStem(fileBaseId), keySize + 1, sbTypes,
        new IndexMultiValuKeySerializer(), atomicOperation);

    // Read persisted visible count from the BTree entry point page — O(1)
    // instead of the previous O(n) visibility-filtered scan.
    long count = sbTree.getApproximateEntriesCount(atomicOperation);
    if (count == 0) {
      // Upgrade path: APPROXIMATE_ENTRIES_COUNT was not present in prior format.
      // Use TREE_SIZE as initial estimate — overcounts (includes tombstones/markers)
      // but prevents the optimizer from seeing empty indexes until recalibration.
      count = sbTree.size(atomicOperation);
    }
    assert count >= 0
        : "Persisted approximate entries count must be non-negative: " + count;
    approximateIndexEntriesCount.set(count);
    // Recalibrate the in-memory null counter from on-disk state. The single-
    // value engine has no separate persisted null counter (persistCountDelta
    // intentionally ignores nullDelta because nulls live in the same tree as
    // non-null keys), but AbstractStorage.applyIndexCountDeltas still feeds
    // nullDelta into addToApproximateNullCount() polymorphically. Setting the
    // counter to 0 unconditionally meant the first REMOVE of a persisted null
    // entry after restart accumulated -1 and tripped the underflow assert at
    // addToApproximateNullCount(). countNulls() is an O(1) direct null-key
    // lookup (single-value semantics → at most one visible null entry).
    approximateNullCount.set(countNulls(atomicOperation));
  }

  @Override
  public boolean remove(@Nonnull AtomicOperation atomicOperation, Object key) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);

      boolean removed = VersionedIndexOps.doVersionedRemove(
          sbTree, indexesSnapshot, atomicOperation, compositeKey, id, key == null);

      if (removed) {
        var mgr = histogramManager;
        if (mgr != null) {
          mgr.onRemove(atomicOperation, key, true);
        }
      }
      return removed;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of key " + key + " from index " + name),
          e,
          storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, @Nonnull AtomicOperation atomicOperation) {
    try {
      // Order is load-bearing for race-freedom on the clearIndex API path:
      // when doClearTree() enters its while-loop body (the common case for any
      // populated index), the per-tree exclusive lock is acquired transitively
      // via tree.remove → executeInsideComponentOperation →
      // acquireExclusiveLockTillOperationComplete and held for the remainder
      // of this call. For genuinely empty indexes, no lock is taken; the
      // snapshot reads then race against any concurrent commit's apply hook on
      // the same engine, but currentTotal/currentNull are 0 in normal
      // operation and the resulting (0, 0) delta is a no-op. The commit path
      // independently holds the per-engine lock at AbstractStorage.lockIndexes
      // before clear() runs, so this race is API-path-only.
      doClearTree(atomicOperation);
      // Postcondition: doClearTree must remove every entry from the B-tree.
      // If entries remain, the iterate-and-remove loop missed them (e.g., due
      // to cursor invalidation during page rebalancing). This assert fires
      // BEFORE the snapshot read, so the CI stack trace distinguishes
      // "doClearTree left entries" from "entries appeared later."
      assert sbTree.firstKey(atomicOperation) == null
          : "doClearTree() left entries in engine=" + name + " id=" + id
              + " treeSize=" + sbTree.size(atomicOperation);
      // Snapshot under the per-tree lock acquired by doClearTree() above.
      final long currentTotal = approximateIndexEntriesCount.get();
      final long currentNull = approximateNullCount.get();
      assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
          : "clear() snapshot invariant violated on engine=" + name + " id=" + id
              + ": currentTotal=" + currentTotal + " currentNull=" + currentNull;
      indexesSnapshot.clear();
      // Mixed-mode encoding (replaces the prior pure-delta encoding that wrote
      // totalDelta=-currentTotal through persistCountDelta and silently dropped
      // nullDelta because the single-value engine stores nulls and non-nulls
      // in the same tree).
      // Persisted side: one inline absolute write on the single tree,
      // WAL-tracked through the AOM lifecycle, reverts on rollback, and lands
      // at zero regardless of any pre-existing in-mem-vs-persisted drift.
      // Heals the drift window the pure-delta encoding accepted as an open
      // regression: addToApproximateEntriesCount(-currentInMem) on the
      // persisted side would land the EP below zero whenever in-mem had
      // drifted above persisted (e.g. from a reportAndClampUnderflow event on
      // a prior commit).
      //
      // No new checked-exception exposure on this rewrite:
      // setApproximateEntriesCount declares no checked exception. The body of
      // CellBTreeSingleValueV3.setApproximateEntriesCount routes the EP page
      // write through executeInsideComponentOperation, which catches the
      // underlying IOException and rewraps it as a
      // CommonStorageComponentException (unchecked). The setApproximate-
      // EntriesCount call therefore sits inside the existing method-level try
      // purely for code locality; a runtime failure escapes as an unchecked
      // exception, triggers atomic-op rollback at the surrounding
      // executeInsideAtomicOperation boundary, and is handled at the
      // clearIndex API surface. The method-level catch below remains
      // load-bearing for mgr.resetOnClear, which is now the only IOException
      // source on this body.
      sbTree.setApproximateEntriesCount(atomicOperation, 0L);
      // In-mem side: route the collapse through the mixed-mode accumulator
      // shared with buildInitialHistogram(). The delta (-currentTotal,
      // -currentNull) advances inMemAdjustTotal / inMemAdjustNull on the
      // holder; Hook B's applyIndexCountDeltas sums
      // getTotalDelta() + getInMemAdjustTotal() (and the null mirror)
      // post-commit before calling the engine mutators. On rollback the
      // holder is dropped and the in-mem AtomicLongs stay at their
      // pre-clear() values; on success they advance to zero post-commit,
      // matching the persisted side. The new accumulator is the sole carrier
      // of the null-counter delta on this engine — Hook A's persistCountDelta
      // still ignores nullDelta for SV, but Hook A is a no-op for this seam
      // because clear() no longer writes to getTotalDelta()/getNullDelta().
      //
      // Race posture vs the prior pure-delta encoding. Mixed-mode narrows the
      // snapshot-read race the lock-window comment block above documents: the
      // persisted side is now an absolute zero write (immune to a wrong
      // (currentTotal, currentNull) snapshot); only the in-mem side keeps the
      // race window. The consequence is bounded the same way the prior
      // encoding documented: the in-mem-side delta is additively composable,
      // so concurrent recalibrations on the same engine compose via
      // AtomicLong.addAndGet, and any residual drift self-heals on the next
      // buildInitialHistogram() recalibration.
      IndexCountDelta.accumulateInMemRecalibration(
          atomicOperation, id, -currentTotal, -currentNull);
      // mgr.resetOnClear() must remain LAST in this sequence. An IOException
      // from it routes through atomic-op rollback via the surrounding wrap,
      // and the WAL-tracked persisted write plus the holder-only in-mem
      // delta both revert cleanly. Moving it earlier would not break
      // correctness, but it would change the failure-ordering semantics and
      // make the rollback story harder to audit.
      var mgr = histogramManager;
      if (mgr != null) {
        mgr.resetOnClear(atomicOperation);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during clear of index " + name),
          e, storage.getName());
    }
  }

  @Override
  public void close() {
    sbTree.close();
    var mgr = histogramManager;
    if (mgr != null) {
      mgr.closeStatsFile();
    }
  }

  /**
   * Recovery-time orphan-truncation hook dispatched by
   * {@code AbstractStorage.truncateOrphansAfterRecovery()} during iteration over the
   * storage's index engines. Delegates straight through to {@code sbTree}; the engine
   * wrapper exists so the orchestrator can iterate the index engines polymorphically
   * without violating the {@code private final CellBTreeSingleValue<CompositeKey> sbTree}
   * encapsulation.
   *
   * <p>The histogram-manager stats file is deliberately out of scope: as a non-EP
   * derived structure it follows the FSM / CDPB pattern (allocator-only, no
   * logical/physical split) and the partial-flush orphan hazard cannot arise. The
   * recovery pass is scoped to the four entry-point-equipped components (BTree,
   * SharedLinkBagBTree, CollectionPositionMapV2, PaginatedCollectionV2).
   *
   * @param atomicOperation enclosing recovery-pass atomic operation
   * @param readCache       read cache the truncate dispatches through
   * @param writeCache      write cache backing the read cache
   * @throws IOException if the underlying BTree truncate fails
   */
  public void verifyAndTruncateOrphans(
      @Nonnull final AtomicOperation atomicOperation,
      @Nonnull final ReadCache readCache,
      @Nonnull final WriteCache writeCache)
      throws IOException {
    sbTree.verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
  }

  @Override
  public Stream<RID> get(Object key, @Nonnull AtomicOperation atomicOperation) {
    var compositeKey = CompositeKey.asCompositeKey(key);
    // Direct leaf-page lookup — avoids cursor/spliterator/stream overhead.
    // Null keys are handled uniformly: for single-field indexes,
    // CompositeKey(null) is padded with Long.MIN_VALUE for the version slot
    // and matched normally. For composite indexes, getVisible() returns null
    // immediately because the key has fewer user elements than expected
    // (composite indexes have no "null key" — only composite keys with
    // individually-null fields).
    var rid = sbTree.getVisible(compositeKey, indexesSnapshot, atomicOperation);
    return rid != null ? Stream.of(rid) : Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = sbTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey)
        .filter(p -> p.first() != null);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = sbTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesMinor(lastKey, true, false, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey)
        .filter(p -> p.first() != null);
  }

  @Override
  public Stream<Object> keyStream(@Nonnull AtomicOperation atomicOperation) {
    return stream(null, atomicOperation).map(RawPair::first).filter(Objects::nonNull);
  }

  @Override
  public boolean put(@Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);
      boolean wasInserted =
          doPutSingleValue(atomicOperation, compositeKey, value, key);

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, true, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name),
          e, storage.getName());
    }
  }

  @Override
  public boolean validatedPut(
      @Nonnull AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    try {
      var compositeKey = convertToCompositeKeyDefensive(key);

      // Validate at engine level before mutation, keeping the scan result
      // to avoid a second B-tree descent inside doPutSingleValue.
      boolean wasInserted;
      if (validator != null) {
        Optional<RawPair<CompositeKey, RID>> prefetched;
        try (var stream = sbTree.iterateEntriesBetween(
            compositeKey, true, compositeKey, true, true, atomicOperation)) {
          prefetched = stream.findAny();
        }
        if (prefetched.isPresent()) {
          var removedRID = prefetched.get().second();
          // Tombstone means logically deleted — treat as no occupant
          if (!(removedRID instanceof TombstoneRID)) {
            var result = validator.validate(key, removedRID.getIdentity(), value);
            if (result == IndexEngineValidator.IGNORE) {
              return false;
            }
          }
        }
        wasInserted =
            doPutSingleValue(atomicOperation, compositeKey, value, key, prefetched);
      } else {
        wasInserted =
            doPutSingleValue(atomicOperation, compositeKey, value, key);
      }

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, true, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " into index " + name),
          e, storage.getName());
    }
  }

  /**
   * Called by put() — always performs its own B-tree scan.
   */
  private boolean doPutSingleValue(
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value,
      Object key) throws IOException {
    Optional<RawPair<CompositeKey, RID>> existing;
    try (var stream = sbTree.iterateEntriesBetween(
        compositeKey, true, compositeKey, true, true, atomicOperation)) {
      existing = stream.findAny();
    }
    return VersionedIndexOps.doVersionedPut(
        sbTree, indexesSnapshot, atomicOperation, compositeKey, value,
        id, key == null, existing);
  }

  /**
   * Called by validatedPut() — reuses the prefetched scan result.
   */
  private boolean doPutSingleValue(
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey compositeKey,
      RID value,
      Object key,
      @Nonnull Optional<RawPair<CompositeKey, RID>> prefetched) throws IOException {
    return VersionedIndexOps.doVersionedPut(
        sbTree, indexesSnapshot, atomicOperation, compositeKey, value,
        id, key == null, prefetched);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {

    // "from", "to" are null, then scan whole tree as for infinite range
    if (rangeFrom == null && rangeTo == null) {
      return ascSortOrder
          ? stream(transformer, atomicOperation)
          : descStream(transformer, atomicOperation);
    }

    // "from" could be null, then "to" is not (minor)
    if (rangeFrom == null) {
      final var toKey = CompositeKey.asCompositeKey(rangeTo);
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          sbTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

    final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
    // "to" could be null, then "from" is not (major)
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          sbTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeSingleValueIndexEngine::extractKey);
    }

    final var toKey = CompositeKey.asCompositeKey(rangeTo);
    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        sbTree.iterateEntriesBetween(
            fromKey, fromInclusive, toKey, toInclusive, ascSortOrder, atomicOperation),
        BTreeSingleValueIndexEngine::extractKey);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {
    return iterateEntriesBetween(
        fromKey, isInclusive, null, false, ascSortOrder, transformer, atomicOperation);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation) {
    return iterateEntriesBetween(
        null, false, toKey, isInclusive, ascSortOrder, transformer, atomicOperation);
  }

  @Override
  public long size(Storage storage, final IndexEngineValuesTransformer transformer,
      @Nonnull AtomicOperation atomicOperation) {
    return approximateIndexEntriesCount.get();
  }

  @Override
  public boolean acquireAtomicExclusiveLock(@Nonnull AtomicOperation atomicOperation) {
    sbTree.acquireAtomicExclusiveLock(atomicOperation);
    return true;
  }

  private static PropertyTypeInternal[] calculateTypes(final PropertyTypeInternal[] keyTypes) {
    final PropertyTypeInternal[] sbTypes;
    if (keyTypes != null) {
      sbTypes = new PropertyTypeInternal[keyTypes.length + 1];
      System.arraycopy(keyTypes, 0, sbTypes, 0, keyTypes.length);
      // property type for version
      sbTypes[sbTypes.length - 1] = PropertyTypeInternal.LONG;
    } else {
      throw new IndexException("Types of fields should be provided upon creation of index");
    }
    return sbTypes;
  }

  /**
   * Creates a defensive copy of the key as a CompositeKey. Required for mutation
   * paths (put/remove) where addKey(version) appends to the key, which would
   * otherwise mutate the caller's object.
   */
  private static CompositeKey convertToCompositeKeyDefensive(Object key) {
    if (key instanceof CompositeKey compositeKey) {
      return new CompositeKey(compositeKey);
    }
    return new CompositeKey(key);
  }

  // Strips the version timestamp (1 trailing element) to recover the user-visible key.
  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    return VersionedIndexOps.extractUserKey(compositeKey, 1);
  }

  /** Single null lookup — at most 1 entry for a unique index. */
  private long countNulls(@Nonnull AtomicOperation atomicOperation) {
    try (var nullStream = get(null, atomicOperation)) {
      return nullStream.count();
    }
  }

  /**
   * Returns a raw key stream that skips {@link TombstoneRID} entries but does
   * <b>not</b> apply SI visibility filtering. Suitable only for histogram
   * building/rebalance where approximate counts are acceptable.
   *
   * <p>Under snapshot isolation the B-tree stores exactly one physical entry per
   * logical key — old versions live in the snapshot index, not in the B-tree.
   * The only non-visible entries during raw iteration come from uncommitted or
   * phantom transactions (bounded by concurrent writer count, typically
   * &lt;&lt; 0.01% of index size). Skipping the per-entry visibility check
   * eliminates the drift between the scanned count and the scalar counters
   * that caused flaky stress-test failures (nonNullCount deviation).
   */
  public Stream<Object> rawKeyStreamForHistogram(
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = sbTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }
    return sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)
        .filter(pair -> !(pair.second() instanceof TombstoneRID))
        .map(pair -> extractKey(pair.first()))
        .filter(Objects::nonNull);
  }

  @Override
  public void buildInitialHistogram(@Nonnull AtomicOperation atomicOperation)
      throws IOException {
    var mgr = histogramManager;
    if (mgr == null) {
      return;
    }

    // Use approximate count for bucket sizing, scan for exact count + keys.
    long approxTotal = approximateIndexEntriesCount.get();
    long exactNullCount = countNulls(atomicOperation);

    long scannedNonNull;
    // Use the raw (non-SI-filtered) key stream for histogram building.
    // SI filtering is unnecessary here because the histogram tolerates the
    // tiny error from uncommitted/phantom entries (< 0.01% of index size),
    // and skipping it avoids drift between scanned counts and scalar counters.
    try (var keyStream = rawKeyStreamForHistogram(atomicOperation)) {
      scannedNonNull = mgr.buildHistogram(
          atomicOperation, keyStream,
          approxTotal, exactNullCount,
          mgr.getKeyFieldCount());
    }

    // Recalibrate from exact counts using mixed-mode encoding.
    //
    // Persisted side: the sbTree.setApproximateEntriesCount call below is
    // WAL-tracked through the AOM lifecycle. It writes the absolute target
    // value (not a delta), so on every successful recalibration the persisted
    // EP page lands at the exact count regardless of the pre-rebuild
    // persisted value — pre-existing in-mem-vs-persisted drift heals here.
    // On rollback the WAL reverts this write.
    //
    // In-mem side: the AtomicLong counters do NOT move inline. The snapshot
    // delta (target - currentInMem) is recorded on the AtomicOperation via
    // IndexCountDelta.accumulateInMemRecalibration and applied by Hook B
    // (AbstractStorage.applyIndexCountDeltas) after commitChanges succeeds.
    // On the only production path that reaches this method
    // (IndexAbstract.buildHistogramAfterFill via executeInsideAtomicOperation),
    // no per-index lock is held during Hook B's apply; concurrent commits on
    // the same engine compose via AtomicLong.addAndGet's additive semantics.
    // On rollback the holder is dropped and the in-mem counters stay at
    // their pre-recalibration value, so the persisted-side WAL revert and
    // the in-mem-side no-op stay in step. The structural divergence that
    // produced the underflow cascade is unreachable on this path.
    //
    // Scope: the structural-impossibility claim covers only the count
    // counters (approximateIndexEntriesCount, approximateNullCount). The
    // IndexHistogramManager.cache snapshots installed by the preceding
    // mgr.buildHistogram call are NOT reverted on rollback (a heap-only
    // cache rebuilt on next storage open; out-of-scope follow-up).
    //
    // SV-specific note: persistCountDelta ignores nullDelta on this engine
    // (the single tree stores nulls and non-nulls together; the persisted
    // side moves by totalDelta alone). The in-mem-only accumulator below
    // is therefore the sole carrier of the null-counter recalibration on
    // this path.
    long exactTotal = scannedNonNull + exactNullCount;
    sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
    long currentTotal = approximateIndexEntriesCount.get();
    long currentNull = approximateNullCount.get();
    assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
        : "buildInitialHistogram() snapshot invariant violated on engine="
            + name + " id=" + id
            + ": currentTotal=" + currentTotal + " currentNull=" + currentNull;
    IndexCountDelta.accumulateInMemRecalibration(
        atomicOperation, id, exactTotal - currentTotal, exactNullCount - currentNull);
  }

  @Override
  public long getNullCount(@Nonnull AtomicOperation atomicOperation) {
    return approximateNullCount.get();
  }

  @Override
  public long getTotalCount(@Nonnull AtomicOperation atomicOperation) {
    return approximateIndexEntriesCount.get();
  }

  @Override
  public void addToApproximateEntriesCount(long delta) {
    long updated = approximateIndexEntriesCount.addAndGet(delta);
    if (updated < 0) {
      reportAndClampUnderflow(
          "approximateIndexEntriesCount", approximateIndexEntriesCount, updated, delta);
    }
  }

  @Override
  public void addToApproximateNullCount(long delta) {
    long updated = approximateNullCount.addAndGet(delta);
    if (updated < 0) {
      reportAndClampUnderflow(
          "approximateNullCount", approximateNullCount, updated, delta);
    }
  }

  /**
   * Handles an in-memory approximate-count underflow on either mutator: logs at
   * error level with engine identity, then clamps the counter back to 0 via
   * CAS. The first underflow per engine instance carries a stack trace so the
   * next regression is pin-pointable; subsequent underflows on the same engine
   * emit a compact error without the stack.
   *
   * <p>If the CAS to 0 fails (a concurrent applier already moved the counter
   * away from {@code observedNegative}), the method leaves the counter alone.
   * A clamp-loop would mask a legitimate concurrent decrement and force the
   * counter to 0 even when the new value is correct; under heavy contention
   * the counter may stay negative until the next sufficiently-positive delta.
   * This trade-off is intentional.
   */
  // Package-private (rather than private) so the engine-level underflow
  // regression tests in this package can invoke it directly with a pre-set
  // counter value to pin the failed-CAS branch (see
  // BTreeSingleValueIndexEngineUnderflowTest
  //   #failedClampCasLeavesCounterAtConcurrentWriterValueThroughEnginePath).
  // Production callers are exclusively the two mutators above.
  void reportAndClampUnderflow(
      String counterName, AtomicLong counter, long observedNegative, long delta) {
    if (firstUnderflowDumped.compareAndSet(false, true)) {
      LogManager.instance().error(
          this,
          "In-memory %s underflow on engine '%s' (id=%d): updated=%d delta=%d."
              + " Clamping to 0; the cause should be a divergence between persisted"
              + " and in-memory counter state — see stack trace.",
          new IllegalStateException("approximate-count underflow stack"),
          counterName, name, id, observedNegative, delta);
    } else {
      LogManager.instance().error(
          this,
          "In-memory %s underflow on engine '%s' (id=%d): updated=%d delta=%d."
              + " Clamping to 0 (stack trace suppressed; already emitted once for this"
              + " engine instance).",
          null,
          counterName, name, id, observedNegative, delta);
    }
    counter.compareAndSet(observedNegative, 0);
  }

  @Override
  public void persistCountDelta(
      AtomicOperation atomicOperation, long totalDelta, long nullDelta) {
    // Single-value engine stores all entries (including nulls) in one BTree.
    // The full totalDelta applies to the single tree's persisted count.
    // nullDelta intentionally ignored — single tree stores all entries.
    if (totalDelta != 0) {
      sbTree.addToApproximateEntriesCount(atomicOperation, totalDelta);
    }
  }

  /**
   * Sets the histogram manager for this engine. Called during engine
   * lifecycle (create/load) once the manager is initialized.
   */
  @Override
  public void setHistogramManager(@Nullable IndexHistogramManager histogramManager) {
    this.histogramManager = histogramManager;
  }

  @Override
  @Nullable public IndexHistogramManager getHistogramManager() {
    return histogramManager;
  }
}
