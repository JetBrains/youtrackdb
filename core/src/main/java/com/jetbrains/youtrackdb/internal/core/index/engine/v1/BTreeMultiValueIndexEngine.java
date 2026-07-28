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
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
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

public final class BTreeMultiValueIndexEngine
    implements MultiValueIndexEngine, BTreeIndexEngine {

  public static final String DATA_FILE_EXTENSION = ".cbt";
  // Public so DiskStorage.ALL_FILE_EXTENSIONS can register it: a drop must sweep the null-bucket
  // files of both trees, or they leak on disk after database deletion.
  public static final String NULL_BUCKET_FILE_EXTENSION = ".nbt";
  public static final String M_CONTAINER_EXTENSION = ".mbt";

  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> svTree;
  @Nonnull
  private final CellBTreeSingleValue<CompositeKey> nullTree;
  @Nonnull
  private final IndexesSnapshot indexesSnapshot;
  @Nonnull
  private final IndexesSnapshot nullIndexesSnapshot;

  private final String name;
  private final int id;
  private final String nullTreeName;
  private final AbstractStorage storage;

  /**
   * The engine's stable, never-reused file base id. All storage-component names derive from it
   * ({@code ie_<fileBaseId>} and {@code ie_<fileBaseId>$null}), so the logical index name
   * ({@link #name}) never touches a file: a drop-and-recreate of a same-named index gets fresh
   * files, and a rename never moves any.
   */
  private final int fileBaseId;
  @Nullable private volatile IndexHistogramManager histogramManager;

  // Approximate count of visible index entries (svTree + nullTree), used by the
  // query optimizer for cost estimation. Read from persisted
  // APPROXIMATE_ENTRIES_COUNT on both trees on load(). Adjusted at commit time
  // via delta holder (not directly in doPut/doRemove). AtomicLong because
  // concurrent TXs can commit index changes simultaneously. Recalibrated by
  // buildInitialHistogram() as self-healing.
  private final AtomicLong approximateIndexEntriesCount = new AtomicLong();

  // Approximate count of null-key entries. Read from nullTree's persisted
  // APPROXIMATE_ENTRIES_COUNT on load(). Adjusted at commit time, recalibrated
  // by buildInitialHistogram().
  private final AtomicLong approximateNullCount = new AtomicLong();

  // One-shot latch for the in-memory counter underflow stack-trace dump.
  // Shared by addToApproximateEntriesCount and addToApproximateNullCount: the
  // first underflow on either mutator (per engine instance) wins the CAS and
  // emits an error log with a stack trace so the next regression is
  // pin-pointable; subsequent underflows on the same engine emit a compact
  // error line without the stack. Resets on storage close + reopen because the
  // engine is re-instantiated.
  private final AtomicBoolean firstUnderflowDumped = new AtomicBoolean(false);

  public BTreeMultiValueIndexEngine(
      int id, int fileBaseId, @Nonnull String name, AbstractStorage storage, final int version) {
    this.id = id;
    this.fileBaseId = fileBaseId;
    this.name = name;
    this.storage = storage;
    // Both components (and therefore their files) are keyed by the stable file base id, not the
    // index name — the single name domain for engine storage components.
    final var stem = AbstractStorage.indexEngineFileStem(fileBaseId);
    nullTreeName = stem + AbstractStorage.NULL_TREE_SUFFIX;

    if (version == 1 || version == 2 || version == 3) {
      throw new IllegalArgumentException("Unsupported version of index : " + version);
    } else if (version == 4) {
      svTree =
          new BTree<>(
              stem, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      svTree.setEngineId(id);
      // User-facing diagnostics report the index's logical name, never the ie_<n> file stems.
      svTree.setDisplayName(name);
      nullTree =
          new BTree<>(
              nullTreeName, DATA_FILE_EXTENSION, NULL_BUCKET_FILE_EXTENSION, storage);
      nullTree.setEngineId(id);
      nullTree.setDisplayName(name + AbstractStorage.NULL_TREE_SUFFIX);
      // Explicit null-tree identity for the tombstone-GC snapshot lookup — the component name
      // is a file key, not an identity carrier.
      nullTree.setNullTree(true);
      indexesSnapshot = storage.subIndexSnapshot(id);
      nullIndexesSnapshot = storage.subNullIndexSnapshot(id);
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
      svTree.create(
          atomicOperation,
          new IndexMultiValuKeySerializer(),
          sbTypes,
          data.getKeySize() + 1);
      nullTree.create(
          atomicOperation, new IndexMultiValuKeySerializer(),
          new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG},
          2);
      approximateIndexEntriesCount.set(0);
      approximateNullCount.set(0);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(), "Error during creation of index " + name), e,
          storage.getName());
    }
  }

  @Override
  public void delete(@Nonnull AtomicOperation atomicOperation) {
    try {
      clearSVTree(atomicOperation);
      indexesSnapshot.clear();
      nullIndexesSnapshot.clear();
      svTree.delete(atomicOperation);
      nullTree.delete(atomicOperation);
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

  private void clearSVTree(final @Nonnull AtomicOperation atomicOperation) {
    doClearTree(svTree, atomicOperation);
    doClearTree(nullTree, atomicOperation);
  }

  private void doClearTree(CellBTreeSingleValue<CompositeKey> tree,
      @Nonnull AtomicOperation atomicOperation) {
    // A single iterate-and-remove pass may miss entries when the B-tree
    // spliterator's LSN-based cursor terminates prematurely after page
    // modifications from remove(). Retry until the tree is empty.
    int pass = 0;
    long sizeBeforePass;
    while ((sizeBeforePass = tree.size(atomicOperation)) > 0) {
      pass++;
      final int currentPass = pass;
      var firstKey = tree.firstKey(atomicOperation);
      var lastKey = tree.lastKey(atomicOperation);
      assert firstKey != null && lastKey != null
          : "tree.size() > 0 but firstKey/lastKey is null in engine=" + name + " id=" + id;
      if (firstKey == null || lastKey == null) {
        return;
      }
      try (var stream =
          tree.iterateEntriesBetween(firstKey, true, lastKey, true,
              true, atomicOperation)) {
        stream.forEach(
            pair -> {
              try {
                var removed = tree.remove(atomicOperation, pair.first());
                assert removed != null
                    : "doClearTree pass " + currentPass + ": remove() returned null"
                        + " for key from stream in engine=" + name + " id=" + id
                        + " key=" + pair.first();
              } catch (IOException e) {
                throw BaseException.wrapException(
                    new IndexException(storage.getName(), "Error during index cleaning"), e,
                    storage.getName());
              }
            });
      }
      long sizeAfterPass = tree.size(atomicOperation);
      long removedInPass = sizeBeforePass - sizeAfterPass;
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

    // Load under the file-base-id stems the components were constructed with — never the index
    // name, which keys no file.
    svTree.load(AbstractStorage.indexEngineFileStem(fileBaseId), keySize + 1, sbTypes,
        new IndexMultiValuKeySerializer(), atomicOperation);
    nullTree.load(
        nullTreeName, 2,
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG},
        new IndexMultiValuKeySerializer(), atomicOperation);

    // Read persisted visible counts from both trees' entry point pages — O(1)
    // instead of the previous O(n) visibility-filtered scans.
    long svCount = svTree.getApproximateEntriesCount(atomicOperation);
    if (svCount == 0) {
      // Upgrade path: APPROXIMATE_ENTRIES_COUNT was not present in prior format.
      // Use TREE_SIZE as initial estimate — overcounts (includes tombstones/markers)
      // but prevents the optimizer from seeing empty indexes until recalibration.
      svCount = svTree.size(atomicOperation);
    }
    long nullCount = nullTree.getApproximateEntriesCount(atomicOperation);
    if (nullCount == 0) {
      // Upgrade path: same fallback for the null tree.
      nullCount = nullTree.size(atomicOperation);
    }
    assert svCount >= 0
        : "Persisted svTree approximate entries count must be non-negative: " + svCount;
    assert nullCount >= 0
        : "Persisted nullTree approximate entries count must be non-negative: " + nullCount;
    approximateIndexEntriesCount.set(svCount + nullCount);
    approximateNullCount.set(nullCount);
  }

  @Override
  public boolean remove(final @Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean removed;
      if (key != null) {
        removed = VersionedIndexOps.doVersionedRemove(svTree, indexesSnapshot,
            atomicOperation, createCompositeKey(key, value), id, false);
      } else {
        removed = VersionedIndexOps.doVersionedRemove(nullTree, nullIndexesSnapshot,
            atomicOperation, new CompositeKey(value), id, true);
      }

      if (removed) {
        var mgr = histogramManager;
        if (mgr != null) {
          mgr.onRemove(atomicOperation, key, false);
        }
      }
      return removed;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during removal of entry with key "
                  + key
                  + " and RID "
                  + value
                  + " from index "
                  + name),
          e, storage.getName());
    }
  }

  @Override
  public void clear(Storage storage, @Nonnull AtomicOperation atomicOperation) {
    // Order is load-bearing for race-freedom on the clearIndex API path:
    // when clearSVTree() enters doClearTree's while-loop body (the common case
    // for any populated index), the per-tree exclusive lock is acquired
    // transitively via tree.remove → executeInsideComponentOperation →
    // acquireExclusiveLockTillOperationComplete and held for the remainder of
    // this call. For the API path on a genuinely empty index (both svTree
    // and nullTree empty), neither per-tree lock is taken because size()==0
    // skips the while-loop in doClearTree; the snapshot reads then race
    // against any concurrent commit's apply hook on the same engine, but
    // currentTotal/currentNull are 0 in normal operation and the resulting
    // (0, 0) delta is a no-op. If only one of the two trees is empty, only
    // the populated tree's lock is acquired transitively; the snapshot of
    // the engine-level AtomicLongs can capture a concurrent put against the
    // empty side before the snapshot read. The resulting in-memory counter
    // divergence is bounded and self-healed by buildInitialHistogram
    // recalibration on the next touch. The commit path independently holds
    // the per-engine lock at AbstractStorage.lockIndexes before clear()
    // runs, so this race is clearIndex API-path-only.
    clearSVTree(atomicOperation);
    // Postcondition: doClearTree must remove every entry from both trees.
    assert svTree.firstKey(atomicOperation) == null
        : "doClearTree() left entries in svTree of engine=" + name + " id=" + id
            + " treeSize=" + svTree.size(atomicOperation);
    assert nullTree.firstKey(atomicOperation) == null
        : "doClearTree() left entries in nullTree of engine=" + name + " id=" + id
            + " treeSize=" + nullTree.size(atomicOperation);
    // Snapshot under the per-tree lock acquired by clearSVTree() above.
    final long currentTotal = approximateIndexEntriesCount.get();
    final long currentNull = approximateNullCount.get();
    assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
        : "clear() snapshot invariant violated on engine=" + name + " id=" + id
            + ": currentTotal=" + currentTotal + " currentNull=" + currentNull;
    indexesSnapshot.clear();
    nullIndexesSnapshot.clear();
    // Mixed-mode encoding (replaces the prior pure-delta encoding that
    // wrote -currentTotal / -currentNull through the persist hook).
    // Persisted side: two inline per-tree absolute writes, WAL-tracked
    // through the AOM lifecycle, revert on rollback, and land at zero
    // regardless of any pre-existing in-mem-vs-persisted drift. Heals the
    // drift window the pure-delta encoding accepted as an open regression:
    // addToApproximateEntriesCount(-currentInMem) on the persisted side
    // would land the EP below zero whenever in-mem had drifted above
    // persisted (e.g. from a reportAndClampUnderflow event on a prior
    // commit).
    //
    // No new try/catch on this rewrite: setApproximateEntriesCount declares
    // no checked exception. BTree.setApproximateEntriesCount routes the EP
    // page write through executeInsideComponentOperation, which catches the
    // underlying IOException and rewraps it as a
    // CommonStorageComponentException (unchecked). The MV clear() signature
    // therefore does not gain "throws IOException"; a runtime failure
    // escapes as an unchecked exception, triggers atomic-op rollback at the
    // surrounding executeInsideAtomicOperation boundary, and is handled at
    // the clearIndex API surface. The histogram-reset try/catch below still
    // covers mgr.resetOnClear, which is the only IOException source
    // remaining on this body.
    svTree.setApproximateEntriesCount(atomicOperation, 0L);
    nullTree.setApproximateEntriesCount(atomicOperation, 0L);
    // In-mem side: route the collapse through the mixed-mode accumulator
    // shared with buildInitialHistogram(). The delta (-currentTotal,
    // -currentNull) advances inMemAdjustTotal / inMemAdjustNull on the
    // holder; Hook B's applyIndexCountDeltas sums
    // getTotalDelta() + getInMemAdjustTotal() (and the null mirror)
    // post-commit before calling the engine mutators. On rollback the
    // holder is dropped and the in-mem AtomicLongs stay at their
    // pre-clear() values; on success they advance to zero post-commit,
    // matching the persisted side.
    //
    // Race posture vs the prior pure-delta encoding. Mixed-mode narrows
    // the snapshot-read race the bifurcated-lock-posture comment block
    // above documents: the persisted side is now an absolute zero write
    // (immune to a wrong (currentTotal, currentNull) snapshot); only the
    // in-mem side keeps the race window. The consequence is bounded the
    // same way: the in-mem-side delta is additively composable, so
    // concurrent recalibrations on the same engine compose via
    // AtomicLong.addAndGet, and any residual drift self-heals on the next
    // buildInitialHistogram() recalibration.
    IndexCountDelta.accumulateInMemRecalibration(
        atomicOperation, id, -currentTotal, -currentNull);
    // mgr.resetOnClear() must remain LAST in this sequence. An IOException
    // from it routes through atomic-op rollback via the surrounding wrap,
    // and the WAL-tracked persisted writes plus the holder-only in-mem
    // delta both revert cleanly. Moving it earlier would not break
    // correctness, but it would change the failure-ordering semantics and
    // make the rollback story harder to audit.
    var mgr = histogramManager;
    if (mgr != null) {
      // Local try-catch needed: unlike BTreeSingleValueIndexEngine.clear(),
      // this method's outer scope does not catch IOException.
      try {
        mgr.resetOnClear(atomicOperation);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new IndexException(storage.getName(),
                "Error during histogram reset on clear of index " + name),
            e, storage.getName());
      }
    }
  }

  @Override
  public void close() {
    svTree.close();
    nullTree.close();
    var mgr = histogramManager;
    if (mgr != null) {
      mgr.closeStatsFile();
    }
  }

  /**
   * Recovery-time orphan-truncation hook. Dispatches to both inner trees — the
   * primary {@code svTree} and the auxiliary {@code nullTree}. Both are full
   * multi-page-growing BTrees: the null-key tree is not a single-page degenerate but
   * an actual BTree whose physical file can grow past the EP page under multi-null-key
   * load (see the multi-page null-key write paths in this engine's {@code doPut} →
   * {@code nullTree} write call sites).
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
   * @throws IOException if either BTree's underlying truncate fails
   */
  public void verifyAndTruncateOrphans(
      @Nonnull final AtomicOperation atomicOperation,
      @Nonnull final ReadCache readCache,
      @Nonnull final WriteCache writeCache)
      throws IOException {
    svTree.verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    // The null-bucket tree is constructed unconditionally in the ctor (see :81-83) and
    // never nulled out; the defensive check guards against a future refactor that makes
    // the null-tree optional rather than against a real possibility today.
    if (nullTree != null) {
      nullTree.verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    }
  }

  @Override
  public Stream<RID> get(Object key, @Nonnull AtomicOperation atomicOperation) {
    if (key != null) {
      final var compositeKey = CompositeKey.asCompositeKey(key);
      return svTree.getVisibleStream(compositeKey, indexesSnapshot, atomicOperation);
    } else {
      // Null tree stores entries as [RID, version] with varying RIDs — a prefix
      // match on firstKey() would restrict to a single RID. Use the full iteration
      // path instead to collect all visible null-key entries.
      var prefixKey = nullTree.firstKey(atomicOperation);
      if (prefixKey == null) {
        return Stream.empty();
      }
      var stream = nullTree.iterateEntriesMajor(
          prefixKey, true, true, atomicOperation);
      return nullIndexesSnapshot.visibilityFilterValues(atomicOperation, stream);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
        BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer, @Nonnull AtomicOperation atomicOperation) {
    final var lastKey = svTree.lastKey(atomicOperation);
    if (lastKey == null) {
      return Stream.empty();
    }

    return indexesSnapshot.visibilityFilterMapped(atomicOperation,
        svTree.iterateEntriesMinor(lastKey, true, false, atomicOperation),
        BTreeMultiValueIndexEngine::extractKey);
  }

  @Override
  public Stream<Object> keyStream(@Nonnull AtomicOperation atomicOperation) {
    return stream(null, atomicOperation).map(RawPair::first);
  }

  @Override
  public boolean put(@Nonnull AtomicOperation atomicOperation, Object key, RID value) {
    try {
      boolean wasInserted;
      if (key != null) {
        wasInserted = doPut(svTree, indexesSnapshot, atomicOperation,
            createCompositeKey(key, value), value, false);
      } else {
        wasInserted = doPut(nullTree, nullIndexesSnapshot, atomicOperation,
            new CompositeKey(value), value, true);
      }

      var mgr = histogramManager;
      if (mgr != null) {
        mgr.onPut(atomicOperation, key, false, wasInserted);
      }
      return wasInserted;
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(storage.getName(),
              "Error during insertion of key " + key + " and RID " + value + " to index " + name),
          e, storage.getName());
    }
  }

  private boolean doPut(
      CellBTreeSingleValue<CompositeKey> tree,
      IndexesSnapshot snapshot,
      @Nonnull AtomicOperation atomicOperation,
      CompositeKey newKey,
      RID value,
      boolean isNullKey) throws IOException {
    // Find existing entry by (userKey, RID) prefix
    Optional<RawPair<CompositeKey, RID>> existing;
    try (var stream = tree.iterateEntriesBetween(
        newKey, true, newKey, true, true, atomicOperation)) {
      existing = stream.findAny();
    }
    return VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOperation, newKey, value, id, isNullKey, existing);
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
          svTree.iterateEntriesMinor(toKey, toInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    // "to" could be null, then "from" is not (major)
    final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
    if (rangeTo == null) {
      return indexesSnapshot.visibilityFilterMapped(atomicOperation,
          svTree.iterateEntriesMajor(fromKey, fromInclusive, ascSortOrder, atomicOperation),
          BTreeMultiValueIndexEngine::extractKey);
    }

    final var toKey = CompositeKey.asCompositeKey(rangeTo);
    var stream =
        svTree.iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder,
            atomicOperation);

    return indexesSnapshot.visibilityFilterMapped(atomicOperation, stream,
        BTreeMultiValueIndexEngine::extractKey);
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
    svTree.acquireAtomicExclusiveLock(atomicOperation);
    nullTree.acquireAtomicExclusiveLock(atomicOperation);

    return true;
  }

  private static PropertyTypeInternal[] calculateTypes(final PropertyTypeInternal[] keyTypes) {
    final PropertyTypeInternal[] sbTypes;
    if (keyTypes != null) {
      sbTypes = new PropertyTypeInternal[keyTypes.length + 2];
      System.arraycopy(keyTypes, 0, sbTypes, 0, keyTypes.length);
      // property type for RID
      sbTypes[sbTypes.length - 2] = PropertyTypeInternal.LINK;
      // property type for version
      sbTypes[sbTypes.length - 1] = PropertyTypeInternal.LONG;
    } else {
      throw new IndexException("Types of fields should be provided upon creation of index");
    }
    return sbTypes;
  }

  private static CompositeKey createCompositeKey(final Object key, final RID value) {
    final var compositeKey = new CompositeKey(key);
    compositeKey.addKey(value);
    return compositeKey;
  }

  // Strips RID and version (2 trailing elements) to recover the user-visible key.
  @Nullable private static Object extractKey(final CompositeKey compositeKey) {
    return VersionedIndexOps.extractUserKey(compositeKey, 2);
  }

  /**
   * Returns a raw key stream that skips {@link TombstoneRID} entries but does
   * <b>not</b> apply SI visibility filtering. Suitable only for histogram
   * building/rebalance where approximate counts are acceptable.
   *
   * <p>See {@link BTreeSingleValueIndexEngine#rawKeyStreamForHistogram} for the
   * full rationale.
   */
  public Stream<Object> rawKeyStreamForHistogram(
      @Nonnull AtomicOperation atomicOperation) {
    final var firstKey = svTree.firstKey(atomicOperation);
    if (firstKey == null) {
      return Stream.empty();
    }
    return svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)
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
    long approxNull = approximateNullCount.get();

    long scannedNonNull;
    // Use the raw (non-SI-filtered) key stream for histogram building.
    // SI filtering is unnecessary here because the histogram tolerates the
    // tiny error from uncommitted/phantom entries (< 0.01% of index size),
    // and skipping it avoids drift between scanned counts and scalar counters.
    try (var keyStream = rawKeyStreamForHistogram(atomicOperation)) {
      scannedNonNull = mgr.buildHistogram(
          atomicOperation, keyStream,
          approxTotal, approxNull,
          mgr.getKeyFieldCount());
    }

    // Count visible null entries for recalibration. The null tree is typically
    // small (one entry per null-keyed document), so the scan cost is negligible
    // compared to the svTree scan.
    long exactNullCount;
    var nullFirstKey = nullTree.firstKey(atomicOperation);
    if (nullFirstKey == null) {
      exactNullCount = 0;
    } else {
      try (var nullStream = nullIndexesSnapshot.visibilityFilterValues(
          atomicOperation,
          nullTree.iterateEntriesMajor(nullFirstKey, true, true, atomicOperation))) {
        exactNullCount = nullStream.count();
      }
    }

    // Recalibrate from exact counts using mixed-mode encoding.
    //
    // Persisted side: the two setApproximateEntriesCount calls below are
    // WAL-tracked through the AOM lifecycle. They write the absolute target
    // value (not a delta), so on every successful recalibration the persisted
    // EP page lands at the exact count regardless of the pre-rebuild
    // persisted value — pre-existing in-mem-vs-persisted drift heals here.
    // On rollback the WAL reverts these writes.
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
    svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
    nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
    long currentTotal = approximateIndexEntriesCount.get();
    long currentNull = approximateNullCount.get();
    long targetTotal = scannedNonNull + exactNullCount;
    assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
        : "buildInitialHistogram() snapshot invariant violated on engine="
            + name + " id=" + id
            + ": currentTotal=" + currentTotal + " currentNull=" + currentNull;
    IndexCountDelta.accumulateInMemRecalibration(
        atomicOperation, id, targetTotal - currentTotal, exactNullCount - currentNull);
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
  // BTreeMultiValueIndexEngineUnderflowTest
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
    // Multi-value engine splits entries across two trees:
    // svTree holds non-null entries, nullTree holds null entries.
    // totalDelta = nonNullDelta + nullDelta, so nonNullDelta = totalDelta - nullDelta.
    long nonNullDelta = totalDelta - nullDelta;
    if (nonNullDelta != 0) {
      svTree.addToApproximateEntriesCount(atomicOperation, nonNullDelta);
    }
    if (nullDelta != 0) {
      nullTree.addToApproximateEntriesCount(atomicOperation, nullDelta);
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
