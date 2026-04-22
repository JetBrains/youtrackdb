/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages persistent equi-depth histogram statistics for a single index engine.
 *
 * <p>Extends {@link StorageComponent} with its own file ({@code .ixs} extension).
 * Provides lifecycle, incremental maintenance, histogram construction, background
 * rebalancing, and planner read access.
 *
 * <p>Each index engine (single-value or multi-value) owns one histogram manager.
 * The manager does NOT modify the B-tree — it is a purely additive, separate
 * component with independent file storage.
 *
 * <p><b>Threading model:</b> Write-side methods ({@code onPut}, {@code onRemove})
 * accumulate deltas in a transaction-local {@link HistogramDelta} attached to the
 * {@link AtomicOperation}. Read-side methods ({@code getStatistics},
 * {@code getHistogram}) read from the storage-level
 * {@link ConcurrentHashMap ConcurrentHashMap&lt;Integer, HistogramSnapshot&gt;}.
 * Delta application on commit uses {@code cache.compute()} for atomicity.
 */
public class IndexHistogramManager extends StorageComponent {

  private static final Logger logger =
      LoggerFactory.getLogger(IndexHistogramManager.class);

  /** File extension for the statistics page. */
  public static final String IXS_EXTENSION = ".ixs";

  /**
   * Absolute minimum bucket count — below this, a histogram is not useful.
   * Not configurable (structural invariant, not a tuning knob).
   */
  static final int MINIMUM_BUCKET_COUNT = 4;

  private static final int MURMUR_SEED = 0x9747b28c;

  /** VarHandle for atomic operations on {@link #dirtyMutations}. */
  private static final VarHandle DIRTY_MUTATIONS;

  static {
    try {
      DIRTY_MUTATIONS = MethodHandles.lookup().findVarHandle(
          IndexHistogramManager.class, "dirtyMutations", long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Fixed header payload size in bytes (from NEXT_FREE_POSITION to start of
   * variable data). Used for page budget calculations.
   */
  // formatVersion(4) + serializerId(1) + totalCount(8) + distinctCount(8) +
  // nullCount(8) + mutationsSinceRebalance(8) + totalCountAtLastBuild(8) +
  // histogramDataLength(4) + hllRegisterCount(4) = 53 bytes
  static final int FIXED_HEADER_SIZE = 53;

  // ---- Instance state ----
  private final int engineId;
  private final boolean isSingleValue;
  private final ConcurrentHashMap<Integer, HistogramSnapshot> cache;
  @SuppressWarnings("unchecked")
  private final BinarySerializer<Object> keySerializer;
  private final BinarySerializerFactory serializerFactory;
  private final byte serializerId;

  private FileHandler fileHandler = null;

  /**
   * Cached persist batch size — read from GlobalConfiguration at construction
   * time to avoid per-commit config lookup overhead on the hot path.
   */
  private final int persistBatchSize;

  // Cached rebalance-related configuration values — read from
  // GlobalConfiguration at construction time to avoid repeated config
  // lookups on every getHistogram() call (planner hot path).
  private final int histogramMinSize;
  private final double rebalanceMutationFraction;
  private final long maxRebalanceMutations;
  private final long minRebalanceMutations;
  private final long rebalanceFailureCooldownNs;

  /**
   * Maximum serialized size in bytes for a single histogram boundary key.
   * Boundaries exceeding this limit are truncated at character boundaries
   * (for strings) or prefix-truncated (for byte arrays) before the page
   * budget check. This is Strategy 1 from the implementation plan (Section
   * 5.3); bucket count reduction (Strategy 2) is the fallback.
   */
  private final int maxBoundaryBytes;

  /**
   * Committed mutations not yet persisted to the .ixs page. Accessed via
   * {@link #DIRTY_MUTATIONS} VarHandle for atomic increments — prevents
   * lost updates when concurrent commits call {@link #applyDelta}.
   */
  @SuppressWarnings({"FieldMayBeFinal", "UnusedVariable"}) // accessed via DIRTY_MUTATIONS VarHandle
  private long dirtyMutations;

  /** Prevents concurrent rebalances for the same index. */
  private final AtomicBoolean rebalanceInProgress = new AtomicBoolean(false);

  /**
   * Monotonic timestamp ({@link System#nanoTime()}) of last rebalance failure.
   * Prevents retry storms under persistent I/O errors (cooldown period).
   * Uses nanoTime (not currentTimeMillis) to be immune to NTP clock adjustments.
   */
  private volatile long lastRebalanceFailureNanos;

  /**
   * Function that produces the engine's sorted key stream given an atomic
   * operation. Set by the engine after construction. Used by rebalance to scan
   * keys. May be null before wiring. The atomic operation is created on the
   * calling thread so that snapshot visibility and tsMin tracking are correct.
   * Volatile: written during setup, read by background rebalance thread.
   */
  @Nullable private volatile Function<AtomicOperation, Stream<Object>> keyStreamSupplier;

  /**
   * Storage-level semaphore limiting concurrent rebalance tasks. Set by
   * the engine from DiskStorage. May be null before wiring.
   * Volatile: written during setup, read by background rebalance thread.
   */
  @Nullable private volatile Semaphore rebalanceSemaphore;

  /**
   * Number of key fields in the index definition (1 for simple, >1 for composite).
   * Volatile: written during setup, read by onPut/onRemove and background rebalance.
   */
  private volatile int keyFieldCount = 1;

  /** When true, onPut/onRemove are no-ops (used during bulk load / fillIndex). */
  private volatile boolean bulkLoading;

  /**
   * Background executor for scheduling histogram rebalance tasks. Set
   * post-construction by the storage layer once the database is fully open.
   * Before this is set, {@link #maybeScheduleHistogramWork} is a no-op (null
   * executor returns immediately).
   * <p>
   * <b>Must NOT be the ioExecutor used by {@code AsynchronousFileChannel}
   * for I/O completions.</b> Running blocking page reads on the ioExecutor
   * thread pool deadlocks because I/O completion callbacks need the same
   * threads.
   */
  @Nullable private volatile ExecutorService backgroundExecutor;

  /**
   * Creates a new histogram manager for the given index engine.
   *
   * @param storage           parent storage
   * @param name              base file name (same as the index file, without extension)
   * @param engineId          stable engine ID from DiskStorage
   * @param isSingleValue     true for single-value indexes, false for multi-value
   * @param cache             storage-level shared snapshot cache
   * @param keySerializer     serializer for boundary keys (leading field for composite)
   * @param serializerFactory factory for key serialization
   * @param serializerId      numeric ID of the key serializer (for page persistence)
   */
  public IndexHistogramManager(
      @Nonnull AbstractStorage storage,
      @Nonnull String name,
      int engineId,
      boolean isSingleValue,
      @Nonnull ConcurrentHashMap<Integer, HistogramSnapshot> cache,
      @Nonnull BinarySerializer<?> keySerializer,
      @Nonnull BinarySerializerFactory serializerFactory,
      byte serializerId) {
    super(storage, name, IXS_EXTENSION, name + IXS_EXTENSION, true);
    this.engineId = engineId;
    this.isSingleValue = isSingleValue;
    this.cache = cache;
    this.keySerializer = (BinarySerializer<Object>) keySerializer;
    this.serializerFactory = serializerFactory;
    this.serializerId = serializerId;
    this.persistBatchSize =
        GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE.getValueAsInteger();
    this.histogramMinSize =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE.getValueAsInteger();
    this.rebalanceMutationFraction =
        GlobalConfiguration.QUERY_STATS_REBALANCE_MUTATION_FRACTION
            .getValueAsDouble();
    this.maxRebalanceMutations =
        GlobalConfiguration.QUERY_STATS_MAX_REBALANCE_MUTATIONS.getValueAsLong();
    this.minRebalanceMutations =
        GlobalConfiguration.QUERY_STATS_MIN_REBALANCE_MUTATIONS.getValueAsLong();
    this.rebalanceFailureCooldownNs =
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            GlobalConfiguration.QUERY_STATS_REBALANCE_FAILURE_COOLDOWN
                .getValueAsLong());
    this.maxBoundaryBytes =
        GlobalConfiguration.QUERY_STATS_MAX_BOUNDARY_BYTES
            .getValueAsInteger();
  }

  // ---- Configuration setters (for deferred wiring in Steps 5-6) ----

  public void setKeyStreamSupplier(Function<AtomicOperation, Stream<Object>> supplier) {
    this.keyStreamSupplier = supplier;
  }

  public void setRebalanceSemaphore(Semaphore semaphore) {
    this.rebalanceSemaphore = semaphore;
  }

  public void setKeyFieldCount(int keyFieldCount) {
    this.keyFieldCount = keyFieldCount;
  }

  public void setBulkLoading(boolean bulkLoading) {
    this.bulkLoading = bulkLoading;
  }

  /**
   * Sets the IO executor for background rebalance scheduling. Called by
   * the storage layer after the database is fully open and the executor
   * is available. Also triggers a proactive rebalance check — if mutations
   * accumulated before a crash exceeded the threshold, an immediate
   * background rebalance is scheduled now that the executor is available.
   *
   * <p>Idempotent: calling this method multiple times with the same
   * executor is safe. The proactive rebalance check re-runs, but the
   * at-most-one CAS guard in {@code scheduleRebalance} prevents
   * duplicate rebalance tasks.
   *
   * @param executor the background executor, or null to disable background
   *                 rebalance. Must NOT be the ioExecutor used by
   *                 AsynchronousFileChannel — blocking reads on that executor
   *                 deadlock because completion callbacks need the same pool.
   */
  public void setBackgroundExecutor(@Nullable ExecutorService executor) {
    this.backgroundExecutor = executor;
    if (executor != null) {
      // Proactive rebalance check after database open (Section 5.7):
      // if mutations accumulated before a crash exceeded the threshold,
      // schedule an immediate rebalance now that the executor is available.
      maybeScheduleHistogramWork(executor);
    }
  }

  // ---- Lifecycle ----

  /**
   * Checks if the .ixs file exists. Used by the storage layer to decide
   * between the normal open path and the migration path.
   */
  public boolean statsFileExists(AtomicOperation op) {
    return isFileExists(op, getFullName());
  }

  /**
   * Creates the .ixs file and writes an initial empty statistics page.
   * Called by the engine during index creation.
   */
  public void createStatsFile(AtomicOperation op) throws IOException {
    createEmptyStatsPage(op);

    // Install empty snapshot in cache
    var emptySnapshot = createEmptySnapshot();
    cache.put(engineId, emptySnapshot);
  }

  /**
   * Creates the .ixs file and writes initial counters without building a
   * histogram. Used for the migration path when an existing index has no
   * .ixs file yet.
   *
   * <p>After calling this method, the snapshot in the CHM cache contains
   * the provided counters with no histogram. The first planner access
   * will trigger {@link #maybeScheduleHistogramWork} which will schedule
   * a background histogram build if the non-null count exceeds
   * {@code HISTOGRAM_MIN_SIZE}.
   *
   * @param op            current atomic operation
   * @param totalCount    total number of entries (including nulls)
   * @param distinctCount estimated distinct count (may overestimate for
   *                      multi-value indexes — corrected on first rebalance)
   * @param nullCount     number of null entries
   */
  public void createStatsFileWithCounters(AtomicOperation op,
      long totalCount, long distinctCount, long nullCount) throws IOException {
    createEmptyStatsPage(op);

    // Install snapshot with initial counters (no histogram)
    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    cache.put(engineId, snapshot);

    // Persist the counters to the page
    writeSnapshotToPage(op, snapshot);
  }

  /**
   * Opens the .ixs file and loads the statistics page into the CHM cache.
   * Called by the engine during index load.
   */
  public void openStatsFile(AtomicOperation op) throws IOException {
    fileHandler = openFile(op, getFullName());
    var snapshot = readSnapshotFromPage(op);
    cache.put(engineId, snapshot);

    // Proactively check if rebalance is needed after loading
    // (handles the case where mutations accumulated before a crash)
    DIRTY_MUTATIONS.setRelease(this, 0L);
  }

  /**
   * Flushes dirty state and releases resources. Called by the engine
   * during index-level close (e.g., drop index, rebuild index).
   *
   * <p><b>Not called during normal storage-level close.</b> The storage
   * close path in {@code AbstractStorage} skips B-tree engine close calls
   * (the write cache closes all files), but calls
   * {@link #flushIfDirty()} via {@code flushDirtyHistograms()} to persist
   * dirty histogram data before shutdown.
   *
   * <p>Waits for any in-progress background rebalance to finish before
   * cleanup, then permanently blocks future rebalances by leaving
   * {@code rebalanceInProgress = true}. This prevents a background task
   * from installing a phantom CHM entry or writing to a closed file.
   */
  public void closeStatsFile() {
    // Acquire exclusive rebalance guard — wait for any in-progress
    // background rebalance to finish, then block future ones.
    waitForAndBlockRebalance();
    try {
      if (fileHandler != null && (long) DIRTY_MUTATIONS.getAcquire(this) > 0) {
        flushSnapshotToPage();
      }
    } catch (IOException e) {
      logger.warn("Failed to flush histogram stats on close for {}",
          getName(), e);
    }
    cache.remove(engineId);
    fileHandler = null;
  }

  /**
   * Deletes the .ixs file. Called by the engine during index deletion.
   *
   * <p>Waits for any in-progress background rebalance to finish before
   * deletion, then permanently blocks future rebalances.
   */
  public void deleteStatsFile(AtomicOperation op) throws IOException {
    waitForAndBlockRebalance();
    if (fileHandler != null) {
      deleteFile(op, fileHandler.fileId());
    }
    cache.remove(engineId);
    fileHandler = null;
  }

  /**
   * Waits until the rebalance CAS guard can be acquired, then leaves it
   * set to permanently prevent future background rebalances. Used by
   * {@link #closeStatsFile()} and {@link #deleteStatsFile} to ensure no
   * background task touches the file or cache after cleanup.
   *
   * <p>Parks the thread in 1 ms intervals to avoid burning CPU while
   * waiting for a potentially long-running rebalance scan. Logs a warning
   * if the wait exceeds 30 seconds.
   */
  private void waitForAndBlockRebalance() {
    long waitStartNanos = System.nanoTime();
    boolean warned = false;
    while (!rebalanceInProgress.compareAndSet(false, true)) {
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        logger.warn("Interrupted while waiting for rebalance to"
            + " complete during close/delete of {}", getName());
        // Force-acquire the flag to prevent future schedules even
        // though we were interrupted — the close/delete must proceed.
        rebalanceInProgress.set(true);
        return;
      }
      LockSupport.parkNanos(1_000_000L); // 1 ms
      if (!warned
          && System.nanoTime() - waitStartNanos > 30_000_000_000L) {
        logger.warn("Waiting >30s for background rebalance to finish"
            + " during close/delete of {}", getName());
        warned = true;
      }
    }
    // Leave rebalanceInProgress == true to block future schedules.
  }

  // ---- Incremental updates ----

  /**
   * Accumulates a put delta in the transaction-local {@link HistogramDelta}.
   *
   * @param op           current atomic operation
   * @param key          the original key (before CompositeKey wrapping); may be null
   * @param isSingleVal  true for single-value indexes
   * @param wasInsert    true if a new key was inserted; false if an existing
   *                     key's value was updated in-place (single-value only;
   *                     always true for multi-value)
   */
  public void onPut(AtomicOperation op, @Nullable Object key,
      boolean isSingleVal, boolean wasInsert) {
    if (bulkLoading) {
      return;
    }

    var delta = op.getOrCreateHistogramDeltas().getOrCreate(engineId);
    delta.mutationCount++;

    // For single-value updates (wasInsert == false), the key already exists.
    // The B-tree's treeSize is unchanged, so no counter or frequency changes.
    if (!wasInsert) {
      return;
    }

    delta.totalCountDelta++;
    if (key == null) {
      delta.nullCountDelta++;
      return;
    }

    // For composite indexes, extract the leading field. If the leading
    // field is null (e.g., CompositeKey(null, "Smith")), treat as null
    // for histogram purposes — findBucket requires a non-null key.
    Object effectiveKey = extractLeadingField(key);
    if (effectiveKey == null) {
      delta.nullCountDelta++;
      return;
    }

    var snapshot = cache.get(engineId);

    if (snapshot != null && snapshot.histogram() != null) {
      delta.initFrequencyDeltas(
          snapshot.histogram().bucketCount(), snapshot.version());
      // If a rebalance changed the bucket layout after the delta was first
      // initialized, the snapshot version will differ from the delta's recorded
      // version.  The frequencyDeltas array was sized for the OLD layout, so
      // indexing it with a bucket from the NEW histogram could cause an
      // ArrayIndexOutOfBoundsException.  Skip the frequency update — the stale
      // deltas will be discarded at commit time by the version check in
      // computeNewSnapshot (Section 5.7).
      if (delta.snapshotVersion == snapshot.version()) {
        int bucket = snapshot.histogram().findBucket(effectiveKey);
        delta.frequencyDeltas[bucket]++;
      }
    }

    // Multi-value: update per-transaction HLL sketch for NDV tracking
    if (!isSingleVal && snapshot != null && snapshot.hllSketch() != null) {
      long hash = hashKey(effectiveKey);
      delta.getOrCreateHll().add(hash);
    }
  }

  /**
   * Accumulates a remove delta in the transaction-local {@link HistogramDelta}.
   *
   * @param op          current atomic operation
   * @param key         the original key; may be null
   * @param isSingleVal true for single-value indexes
   */
  public void onRemove(AtomicOperation op, @Nullable Object key,
      boolean isSingleVal) {
    if (bulkLoading) {
      return;
    }

    var delta = op.getOrCreateHistogramDeltas().getOrCreate(engineId);
    delta.mutationCount++;
    delta.totalCountDelta--;

    if (key == null) {
      delta.nullCountDelta--;
      return;
    }

    // For composite indexes, extract the leading field. If the leading
    // field is null, treat as null — same as onPut.
    Object effectiveKey = extractLeadingField(key);
    if (effectiveKey == null) {
      delta.nullCountDelta--;
      return;
    }

    var snapshot = cache.get(engineId);

    if (snapshot != null && snapshot.histogram() != null) {
      delta.initFrequencyDeltas(
          snapshot.histogram().bucketCount(), snapshot.version());
      // Skip frequency update on version mismatch — same reasoning as onPut.
      if (delta.snapshotVersion == snapshot.version()) {
        int bucket = snapshot.histogram().findBucket(effectiveKey);
        delta.frequencyDeltas[bucket]--;
      }
    }
    // HLL is insert-only — not updated on remove (Section 6.2)
  }

  // ---- Commit: apply deltas to CHM cache ----

  /**
   * Applies accumulated deltas from a committed transaction to the CHM cache.
   * Called by the storage after commitIndexes() succeeds.
   *
   * @param delta the accumulated delta for this engine
   */
  public void applyDelta(HistogramDelta delta) {
    cache.compute(engineId, (id, old) -> {
      if (old == null) {
        return null; // engine deleted — discard delta silently
      }
      return computeNewSnapshot(old, delta);
    });
    long newDirty =
        (long) DIRTY_MUTATIONS.getAndAdd(this, delta.mutationCount)
            + delta.mutationCount;

    // Use getAndSet(0) so exactly one thread claims the dirty count and
    // flushes. Any mutations added by concurrent threads between the
    // getAndAdd above and the getAndSet here become part of the next batch
    // (their count is already reflected in the VarHandle and will be
    // re-accumulated by subsequent applyDelta calls).
    if (newDirty >= persistBatchSize) {
      long claimed = (long) DIRTY_MUTATIONS.getAndSet(this, 0L);
      if (claimed > 0) {
        try {
          flushSnapshotToPage();
        } catch (IOException e) {
          // Restore the count so the next applyDelta re-triggers the flush.
          DIRTY_MUTATIONS.getAndAdd(this, claimed);
          logger.warn("Failed to flush histogram stats for {}",
              getName(), e);
        }
      }
    }
  }

  /**
   * Computes a new immutable snapshot by applying the given delta to the
   * current snapshot. Pure function (no side effects).
   */
  static HistogramSnapshot computeNewSnapshot(
      HistogramSnapshot current, HistogramDelta delta) {
    // Apply scalar counters, clamping to >= 0
    long newTotal = Math.max(0,
        current.stats().totalCount() + delta.totalCountDelta);
    long newNull = Math.max(0,
        current.stats().nullCount() + delta.nullCountDelta);

    // Compute distinct count
    long newDistinct;
    HyperLogLogSketch newHll = current.hllSketch();
    if (newHll != null && delta.hllSketch != null) {
      // Multi-value: clone and merge HLL, update distinctCount from estimate
      newHll = newHll.copy();
      newHll.merge(delta.hllSketch);
      newDistinct = newHll.estimate();
    } else if (newHll != null) {
      // Multi-value but no HLL updates in this delta
      newDistinct = current.stats().distinctCount();
    } else {
      // Single-value: distinctCount == non-null count (each key is unique,
      // and null is tracked separately via nullCount — not a "distinct key")
      newDistinct = Math.max(0, newTotal - newNull);
    }

    var newStats = new IndexStatistics(newTotal, newDistinct, newNull);

    // Apply frequency deltas if version matches
    EquiDepthHistogram newHistogram = current.histogram();
    boolean hasDrifted = current.hasDriftedBuckets();

    if (newHistogram != null && delta.frequencyDeltas != null
        && current.version() == delta.snapshotVersion) {
      // Version matches — apply per-bucket deltas
      long[] newFreqs = new long[newHistogram.bucketCount()];
      for (int i = 0; i < newHistogram.bucketCount(); i++) {
        long freq = newHistogram.frequencies()[i] + delta.frequencyDeltas[i];
        if (freq < 0) {
          hasDrifted = true;
          freq = 0;
        }
        newFreqs[i] = freq;
      }

      // Use the accurate scalar counter (newTotal - newNull) for nonNullCount
      // instead of the sum of clamped frequencies. Clamping negative frequencies
      // to zero (when deletes target entries inserted before the histogram was
      // built) causes nonNullSum to undercount. The scalar counters track every
      // commit exactly, so they are the authoritative source.
      long accurateNonNull = Math.max(0, newTotal - newNull);
      assert accurateNonNull <= newTotal
          : "nonNullCount (" + accurateNonNull
              + ") exceeds totalCount (" + newTotal + ")";
      newHistogram = new EquiDepthHistogram(
          newHistogram.bucketCount(),
          newHistogram.boundaries(),
          newFreqs,
          newHistogram.distinctCounts(),
          accurateNonNull,
          newHistogram.mcvValue(),
          newHistogram.mcvFrequency());
    } else if (newHistogram != null) {
      // Version mismatch or no frequency deltas — bucket frequencies stay as-is
      // (the rebalanced histogram's frequencies are already accurate at rebalance
      // time). However, nonNullCount must still track the authoritative scalar
      // counters. Without this update, nonNullCount freezes at the rebalance-time
      // value while totalCount/nullCount continue to advance through subsequent
      // commits, causing drift between histogram.nonNullCount() and the true
      // (totalCount - nullCount).
      long accurateNonNull = Math.max(0, newTotal - newNull);
      if (newHistogram.nonNullCount() != accurateNonNull) {
        newHistogram = new EquiDepthHistogram(
            newHistogram.bucketCount(),
            newHistogram.boundaries(),
            newHistogram.frequencies(),
            newHistogram.distinctCounts(),
            accurateNonNull,
            newHistogram.mcvValue(),
            newHistogram.mcvFrequency());
      }
    }

    long newMutations =
        current.mutationsSinceRebalance() + delta.mutationCount;

    return new HistogramSnapshot(
        newStats,
        newHistogram,
        newMutations,
        current.totalCountAtLastBuild(),
        current.version(),
        hasDrifted,
        newHll,
        current.hllOnPage1());
  }

  // ---- Planner reads ----

  /**
   * Returns the current index statistics from the CHM cache.
   * Reload from page on cache miss.
   */
  @Nullable public IndexStatistics getStatistics() {
    var snapshot = cache.get(engineId);
    if (snapshot != null) {
      return snapshot.stats();
    }
    return null;
  }

  /**
   * Returns the current histogram from the CHM cache, or null if no
   * histogram has been built yet. Evaluates the rebalance trigger on each
   * call — if mutation thresholds are exceeded, schedules a background
   * rebalance on the background executor (non-blocking).
   */
  @Nullable public EquiDepthHistogram getHistogram() {
    // Read snapshot before scheduling — any rebalance triggered by this call
    // must not affect the returned value (callers see a point-in-time view).
    var snapshot = cache.get(engineId);
    maybeScheduleHistogramWork(backgroundExecutor);
    return snapshot != null ? snapshot.histogram() : null;
  }

  /**
   * Returns the full snapshot from the CHM cache.
   */
  @Nullable public HistogramSnapshot getSnapshot() {
    return cache.get(engineId);
  }

  // ---- Histogram construction ----

  /**
   * Builds a histogram from a sorted stream of non-null keys.
   *
   * <p>Implements the equi-depth construction algorithm with:
   * <ul>
   *   <li>MCV tracking (one extra comparison per key transition)</li>
   *   <li>Adaptive bucket count (sqrt cap + NDV cap)</li>
   *   <li>Boundary truncation for variable-length keys (Strategy 1)</li>
   *   <li>Dynamic bucket count reduction if boundaries still exceed page
   *       space after truncation (Strategy 2)</li>
   *   <li>HLL sketch population for multi-value indexes</li>
   * </ul>
   *
   * @param op           atomic operation for page I/O
   * @param sortedKeys   non-null keys only (sorted by DefaultComparator).
   *                     The caller is responsible for closing this stream.
   * @param totalCount   total entries including nulls (may be approximate —
   *                     used only for bucket sizing and early-exit threshold)
   * @param nullCount    number of null entries (may be approximate)
   * @param keyFieldCnt  number of key fields (1 for simple, >1 for composite)
   * @return for the normal path: exact count of non-null keys consumed from the
   *     stream (callers can use this to recalibrate approximate counters). For the
   *     early-exit path (nonNullCount < histogramMinSize): the approximate
   *     {@code totalCount - nullCount} input — no stream elements are consumed.
   */
  public long buildHistogram(AtomicOperation op, Stream<Object> sortedKeys,
      long totalCount, long nullCount, int keyFieldCnt) throws IOException {
    long nonNullCount = totalCount - nullCount;
    if (nonNullCount < histogramMinSize) {
      // Too few non-null keys for histogram; install counters-only snapshot.
      // distinctCount = nonNullCount for both single-value (each key unique)
      // and multi-value (overestimates — corrected on first rebalance when
      // exact NDV is computed from the key scan).
      var stats = new IndexStatistics(totalCount, nonNullCount, nullCount);
      var snapshot = new HistogramSnapshot(
          stats, null, 0, totalCount, 0, false, null, false);
      cache.put(engineId, snapshot);
      writeSnapshotToPage(op, snapshot);
      // Early exit: return approximate count (no stream consumed).
      // Callers' "recalibration" is effectively a no-op for small indexes.
      return nonNullCount;
    }

    // Compute effective keys (leading field for composite indexes)
    Stream<Object> effectiveKeys;
    if (keyFieldCnt > 1) {
      effectiveKeys =
          sortedKeys.map(k -> ((CompositeKey) k).getKeys().getFirst());
    } else {
      effectiveKeys = sortedKeys;
    }

    // Determine target bucket count (adaptive)
    int targetBuckets =
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS.getValueAsInteger();
    targetBuckets =
        Math.min(targetBuckets, (int) Math.floor(Math.sqrt(nonNullCount)));
    targetBuckets = Math.max(targetBuckets, MINIMUM_BUCKET_COUNT);

    // For multi-value indexes, create the HLL sketch before the scan so
    // scanAndBuild can populate it during the single pass over all keys.
    // This ensures the HLL reflects the full index NDV after construction,
    // so incremental delta merges don't reset distinctCount to near-zero.
    HyperLogLogSketch hll = null;
    KeyHasher hasher = null;
    if (!isSingleValue) {
      hll = new HyperLogLogSketch();
      hasher = this::hashKey;
    }

    // Build histogram via single-pass scan (also populates HLL if non-null)
    var result = scanAndBuild(
        effectiveKeys, nonNullCount, targetBuckets, hll, hasher);
    if (result == null) {
      // Empty key stream (all entries are null) — stay in uniform mode.
      // distinctCount = 0 because there are no non-null keys.
      var stats = new IndexStatistics(totalCount, 0, nullCount);
      var snapshot = new HistogramSnapshot(
          stats, null, 0, totalCount, 0, false, null, false);
      cache.put(engineId, snapshot);
      writeSnapshotToPage(op, snapshot);
      return 0;
    }

    // Compute actual non-null count from scan (consistent with frequencies)
    long scannedNonNull = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      scannedNonNull += result.frequencies[i];
    }

    // Apply boundary truncation and page budget check
    var fitResult = fitToPage(result, scannedNonNull);

    boolean hllOnPage1 = false;
    if (!isSingleValue) {
      hllOnPage1 = fitResult != null && fitResult.hllOnPage1;
    }

    EquiDepthHistogram histogram =
        fitResult != null ? fitResult.histogram : null;
    long exactDistinctCount = result.totalDistinct;
    var stats = new IndexStatistics(totalCount, exactDistinctCount, nullCount);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, totalCount, 0, false, hll, hllOnPage1);
    cache.put(engineId, snapshot);
    writeSnapshotToPage(op, snapshot);
    return scannedNonNull;
  }

  // ---- Rebalance ----

  /**
   * Checks if a histogram build or rebalance is needed and schedules it
   * if so. Called from planner reads (getHistogram). Does NOT block the
   * planner — returns immediately with the current (possibly stale) data.
   *
   * @param executor the background executor for rebalance tasks, or null if
   *                 background rebalance is not available
   */
  public void maybeScheduleHistogramWork(
      @Nullable ExecutorService executor) {
    if (executor == null) {
      return;
    }
    var snapshot = cache.get(engineId);
    if (snapshot == null) {
      return;
    }

    long nonNullCount =
        snapshot.stats().totalCount() - snapshot.stats().nullCount();
    if (nonNullCount < histogramMinSize) {
      return;
    }

    // Initial build: no histogram yet and no previous build attempt
    if (snapshot.histogram() == null
        && snapshot.totalCountAtLastBuild() == 0) {
      scheduleRebalance(executor);
      return;
    }

    // Rebalance threshold check
    long rebalanceThreshold = computeRebalanceThreshold(snapshot);
    if (snapshot.mutationsSinceRebalance() > rebalanceThreshold) {
      scheduleRebalance(executor);
    }
  }

  /**
   * Runs a synchronous histogram rebuild. Called by ANALYZE INDEX command.
   * Bypasses the storage-level semaphore and HISTOGRAM_MIN_SIZE threshold.
   *
   * @return the refreshed snapshot, or null if the index is empty
   */
  @Nullable public HistogramSnapshot analyzeIndex() {
    if (keyStreamSupplier == null) {
      return null;
    }

    // If a background rebalance is already running, wait for it
    if (!rebalanceInProgress.compareAndSet(false, true)) {
      return waitForRebalanceAndReturn();
    }

    try {
      // Run the rebalance logic synchronously, bypassing min-size threshold
      doRebalance(true);
    } finally {
      rebalanceInProgress.set(false);
    }
    return cache.get(engineId);
  }

  // ---- Reset ----

  /**
   * Resets all statistics after index clear/truncate. Zeroes counters,
   * discards histogram, persists empty page to .ixs.
   */
  public void resetOnClear(AtomicOperation op) throws IOException {
    var emptySnapshot = createEmptySnapshot();
    cache.put(engineId, emptySnapshot);
    DIRTY_MUTATIONS.setRelease(this, 0L);

    if (fileHandler != null) {
      var cacheEntry = loadPageForWrite(op, fileHandler, 0, true);
      try {
        var page = new HistogramStatsPage(cacheEntry);
        page.writeEmpty(serializerId);
      } finally {
        releasePageFromWrite(op, cacheEntry);
      }
    }
  }

  // ---- Checkpoint flush ----

  /**
   * Persists the current CHM snapshot to the .ixs page if there are
   * uncommitted mutations, using a caller-provided {@link AtomicOperation}.
   * Available for callers that already have an operation context; the
   * checkpoint/shutdown path uses the no-arg {@link #flushIfDirty()} instead.
   */
  public void flushIfDirty(AtomicOperation op) throws IOException {
    long observed = (long) DIRTY_MUTATIONS.getAcquire(this);
    if (observed > 0 && fileHandler != null
        && DIRTY_MUTATIONS.compareAndSet(this, observed, 0L)) {
      var snapshot = cache.get(engineId);
      if (snapshot != null) {
        try {
          writeSnapshotToPage(op, snapshot);
        } catch (IOException e) {
          // Restore the count so the next flush re-triggers.
          DIRTY_MUTATIONS.getAndAdd(this, observed);
          throw e;
        }
      }
    }
  }

  /**
   * Persists the current CHM snapshot to the .ixs page if there are
   * uncommitted (dirty) mutations. Creates its own AtomicOperation (via
   * {@code executeInsideAtomicOperation}). Called by
   * {@code AbstractStorage.flushDirtyHistograms()} during fuzzy checkpoint,
   * synch, close, and recovery.
   *
   * <p>Failures are logged but never propagated — histogram persistence is
   * best-effort and must not block checkpoint or shutdown.
   */
  public void flushIfDirty() {
    long observed = (long) DIRTY_MUTATIONS.getAcquire(this);
    if (observed > 0
        && DIRTY_MUTATIONS.compareAndSet(this, observed, 0L)) {
      try {
        flushSnapshotToPage(); // creates its own AtomicOperation
      } catch (IOException e) {
        // Restore the count so the next checkpoint or applyDelta re-triggers.
        DIRTY_MUTATIONS.getAndAdd(this, observed);
        logger.warn("Failed to flush histogram stats for {}"
            + " during checkpoint", getName(), e);
      }
    }
  }

  // ---- Internal: histogram construction ----

  /**
   * Result of {@link #fitToPage}: the fitted histogram and whether the
   * HLL registers were spilled to page 1.
   */
  record FitResult(EquiDepthHistogram histogram, boolean hllOnPage1) {
  }

  /**
   * Result of a single-pass histogram construction scan.
   */
  static final class BuildResult {
    final Comparable<?>[] boundaries;
    final long[] frequencies;
    final long[] distinctCounts;
    final int actualBucketCount;
    final long totalDistinct; // global NDV from scan
    final Comparable<?> mcvValue;
    final long mcvFrequency;

    BuildResult(Comparable<?>[] boundaries, long[] frequencies,
        long[] distinctCounts, int actualBucketCount, long totalDistinct,
        Comparable<?> mcvValue, long mcvFrequency) {
      this.boundaries = boundaries;
      this.frequencies = frequencies;
      this.distinctCounts = distinctCounts;
      this.actualBucketCount = actualBucketCount;
      this.totalDistinct = totalDistinct;
      this.mcvValue = mcvValue;
      this.mcvFrequency = mcvFrequency;
    }
  }

  /**
   * Single-pass equi-depth histogram construction from a sorted key stream.
   * Overload without HLL population — for tests and single-value indexes.
   *
   * @param effectiveKeys sorted non-null effective keys (leading field for
   *                      composite)
   * @param nonNullCount  expected count of non-null entries
   * @param targetBuckets target number of buckets
   * @return the build result, or null if the key stream is empty
   */
  @Nullable static BuildResult scanAndBuild(
      Stream<Object> effectiveKeys, long nonNullCount, int targetBuckets) {
    return scanAndBuild(effectiveKeys, nonNullCount, targetBuckets, null, null);
  }

  /**
   * Functional interface for hashing a key into a long value for HLL.
   * Injected to keep {@code scanAndBuild} static and testable without
   * requiring a real serializer.
   */
  @FunctionalInterface
  interface KeyHasher {
    long hash(Object key);
  }

  /**
   * Single-pass equi-depth histogram construction from a sorted key stream.
   * When {@code hll} and {@code hasher} are non-null, each key is hashed
   * and added to the HLL during the scan so that the sketch reflects the
   * full index NDV after construction.
   *
   * @param effectiveKeys sorted non-null effective keys (leading field for
   *                      composite)
   * @param nonNullCount  expected count of non-null entries
   * @param targetBuckets target number of buckets
   * @param hll           optional HLL sketch to populate during the scan
   * @param hasher        hash function for HLL; required when hll is non-null
   * @return the build result, or null if the key stream is empty
   */
  @Nullable @SuppressWarnings("unchecked")
  static BuildResult scanAndBuild(
      Stream<Object> effectiveKeys, long nonNullCount, int targetBuckets,
      @Nullable HyperLogLogSketch hll, @Nullable KeyHasher hasher) {
    var boundaries = new Comparable<?>[targetBuckets + 1];
    var frequencies = new long[targetBuckets];
    var distinctCounts = new long[targetBuckets];

    var comparator = DefaultComparator.INSTANCE;

    long totalSeen = 0;
    int currentBucket = 0;
    long currentCount = 0;
    long currentNDV = 0;
    long totalDistinct = 0;
    Comparable<?> prevKey = null;
    boolean first = true;

    // MCV tracking
    long currentRunLength = 0;
    Comparable<?> mcvValue = null;
    long mcvFrequency = 0;

    var iterator = effectiveKeys.iterator();
    while (iterator.hasNext()) {
      var rawKey = iterator.next();
      var key = (Comparable<?>) rawKey;

      // Populate HLL during the scan so it reflects the full index NDV.
      if (hll != null) {
        hll.add(hasher.hash(rawKey));
      }

      if (first) {
        boundaries[0] = key;
        first = false;
        prevKey = key;
        totalSeen++;
        currentCount++;
        currentRunLength++;
        currentNDV++;
        totalDistinct++;
        continue;
      }

      totalSeen++;

      if (comparator.compare(key, prevKey) != 0) {
        // Key transition: check MCV, maybe start new bucket
        if (currentRunLength > mcvFrequency) {
          mcvValue = prevKey;
          mcvFrequency = currentRunLength;
        }
        currentRunLength = 0;

        // Check if we should start a new bucket (cumulative threshold)
        if (totalSeen * targetBuckets
            >= (long) (currentBucket + 1) * nonNullCount
            && currentBucket < targetBuckets - 1) {
          boundaries[currentBucket + 1] = key; // lower bound of next bucket
          frequencies[currentBucket] = currentCount;
          distinctCounts[currentBucket] = currentNDV;
          currentBucket++;
          currentCount = 0;
          currentNDV = 0;
        }
        currentNDV++;
        totalDistinct++;
        prevKey = key;
      }
      currentCount++;
      currentRunLength++;
    }

    // Guard: empty key stream
    if (first) {
      return null;
    }

    // Final MCV check for the last run
    if (currentRunLength > mcvFrequency) {
      mcvValue = prevKey;
      mcvFrequency = currentRunLength;
    }

    // Close last bucket
    frequencies[currentBucket] = currentCount;
    distinctCounts[currentBucket] = currentNDV;
    boundaries[currentBucket + 1] = prevKey; // upper bound = max key
    int actualBucketCount = currentBucket + 1;

    // Trim arrays to actual size if fewer buckets were used
    if (actualBucketCount < targetBuckets) {
      var trimmedBounds = new Comparable<?>[actualBucketCount + 1];
      System.arraycopy(boundaries, 0, trimmedBounds, 0,
          actualBucketCount + 1);
      var trimmedFreqs = new long[actualBucketCount];
      System.arraycopy(frequencies, 0, trimmedFreqs, 0, actualBucketCount);
      var trimmedNDV = new long[actualBucketCount];
      System.arraycopy(distinctCounts, 0, trimmedNDV, 0, actualBucketCount);
      return new BuildResult(trimmedBounds, trimmedFreqs, trimmedNDV,
          actualBucketCount, totalDistinct, mcvValue, mcvFrequency);
    }

    return new BuildResult(boundaries, frequencies, distinctCounts,
        actualBucketCount, totalDistinct, mcvValue, mcvFrequency);
  }

  /**
   * Instance convenience wrapper that delegates to the static
   * {@link #fitToPage(BuildResult, long, boolean, int,
   * BoundarySizeCalculator)}.
   */
  @Nullable private FitResult fitToPage(BuildResult result, long nonNullCount) {
    // Strategy 1: truncate oversized variable-length boundary keys before
    // checking page budget. This preserves the full bucket count when
    // individual keys are long but truncation makes them fit. Only applies
    // to variable-length serializers (strings, byte arrays); fixed-length
    // keys (int, long, double, date) are never truncated.
    BuildResult truncated = result;
    if (!keySerializer.isFixedLength()) {
      truncated = truncateBoundaries(
          result, maxBoundaryBytes, keySerializer, serializerFactory);
    }

    // Also truncate the MCV key if needed (same budget constraint)
    Comparable<?> mcvForSize = truncated.mcvValue;
    if (mcvForSize != null && !keySerializer.isFixedLength()) {
      var truncatedMcv = truncateBoundary(
          mcvForSize, maxBoundaryBytes, keySerializer, serializerFactory);
      if (truncatedMcv != mcvForSize) {
        mcvForSize = truncatedMcv;
      }
    }
    int mcvKeySize = mcvForSize != null
        ? keySerializer.getObjectSize(serializerFactory, mcvForSize)
        : 0;

    // Strategy 2: reduce bucket count if boundaries still exceed page space
    return fitToPage(truncated, nonNullCount, isSingleValue, mcvKeySize,
        this::computeBoundaryBytes);
  }

  /**
   * Checks page budget and reduces bucket count if boundaries don't fit
   * (Strategy 2). Boundary truncation (Strategy 1) is applied by the
   * instance wrapper before calling this method. When the HLL is spilled
   * to page 1, the returned {@link FitResult#hllOnPage1} is {@code true}.
   *
   * <p>Package-private and static for testability — all serializer-dependent
   * logic is injected via the {@code boundarySizeCalc} function.
   *
   * @param result         the scan result to fit
   * @param nonNullCount   total non-null entries
   * @param singleValue    true for single-value indexes (no HLL budget)
   * @param mcvKeySize     serialized size of the MCV key (0 if no MCV)
   * @param boundarySizeCalc computes total serialized boundary bytes
   * @return the fit result, or null if keys are too large for any useful
   *         histogram
   */
  @Nullable static FitResult fitToPage(BuildResult result, long nonNullCount,
      boolean singleValue, int mcvKeySize,
      BoundarySizeCalculator boundarySizeCalc) {
    int bucketCount = result.actualBucketCount;
    // Track the source bucket count that matches the current arrays.
    // mergeBuckets needs to know how many buckets the source arrays have.
    int sourceBucketCount = bucketCount;
    var boundaries = result.boundaries;
    var frequencies = result.frequencies;
    var distinctCounts = result.distinctCounts;

    int totalBoundaryBytes =
        boundarySizeCalc.computeSize(boundaries, bucketCount);
    int hllSize = singleValue ? 0 : HyperLogLogSketch.serializedSize();
    boolean hllSpilledToPage1 = false;

    // Check page budget and reduce buckets if needed
    while (bucketCount > MINIMUM_BUCKET_COUNT) {
      int available = computeMaxBoundarySpace(
          bucketCount, hllSize, mcvKeySize);
      if (totalBoundaryBytes <= available) {
        break;
      }
      // Reduce bucket count by half
      bucketCount = bucketCount / 2;
      if (bucketCount < MINIMUM_BUCKET_COUNT) {
        // Try spilling HLL to page 1 (at most once — hllSize > 0 guard
        // prevents re-entry after the first spill attempt)
        if (!singleValue && hllSize > 0) {
          hllSize = 0;
          hllSpilledToPage1 = true;
          // Reset to original arrays and bucket count. This is safe because
          // the spill triggers when bucketCount/2 < MINIMUM_BUCKET_COUNT,
          // which happens before mergeBuckets could run for this iteration.
          // Explicit reset guards against future refactors.
          bucketCount = result.actualBucketCount;
          sourceBucketCount = bucketCount;
          boundaries = result.boundaries;
          frequencies = result.frequencies;
          distinctCounts = result.distinctCounts;
          totalBoundaryBytes =
              boundarySizeCalc.computeSize(boundaries, bucketCount);
          continue;
        }
        return null; // keys too large for any useful histogram
      }
      // Merge adjacent bucket pairs
      var merged = mergeBuckets(boundaries, frequencies, distinctCounts,
          sourceBucketCount, bucketCount);
      sourceBucketCount = bucketCount;
      boundaries = merged.boundaries;
      frequencies = merged.frequencies;
      distinctCounts = merged.distinctCounts;
      totalBoundaryBytes =
          boundarySizeCalc.computeSize(boundaries, bucketCount);
    }

    var histogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts, nonNullCount,
        result.mcvValue, result.mcvFrequency);
    return new FitResult(histogram, hllSpilledToPage1);
  }

  /**
   * Functional interface for computing total serialized boundary bytes.
   * Injected into {@link #fitToPage} to decouple from the key serializer.
   */
  @FunctionalInterface
  interface BoundarySizeCalculator {
    int computeSize(Comparable<?>[] boundaries, int bucketCount);
  }

  // ---- Strategy 1: Boundary truncation for variable-length keys ----

  /**
   * Truncates a single boundary key if its serialized size exceeds
   * {@code maxBytes}. For String keys, truncation happens at character
   * boundaries to produce a valid, shorter string that preserves sort
   * order (a prefix is always &le; the full string in lexicographic
   * order). For byte[] keys, a simple prefix is taken. For other
   * variable-length types (e.g., Decimal), no truncation is applied
   * (their serialized sizes are typically well within budget).
   *
   * @return the original key if no truncation needed, or a truncated copy
   */
  @SuppressWarnings("unchecked")
  static <T> Comparable<T> truncateBoundary(
      Comparable<?> key, int maxBytes,
      BinarySerializer<Object> serializer,
      BinarySerializerFactory factory) {
    int serializedSize = serializer.getObjectSize(factory, key);
    if (serializedSize <= maxBytes) {
      return (Comparable<T>) key;
    }

    if (key instanceof String s) {
      // Truncate the String so its serialized form fits within maxBytes.
      // The serializer uses either UTF8Serializer (2-byte header + UTF-8)
      // or StringSerializer (4-byte header + UTF-16). We compute the
      // maximum payload bytes and truncate the string at character
      // boundaries.
      return (Comparable<T>) truncateString(
          s, maxBytes, serializer);
    }

    // Other variable-length types (byte[], Decimal): no truncation.
    // byte[] does not implement Comparable and is not used as B-tree keys;
    // Decimal is typically small enough (10-20 bytes).
    // The bucket count reduction fallback (Strategy 2) handles edge cases.
    return (Comparable<T>) key;
  }

  /**
   * Truncates a String so its serialized form fits within {@code maxBytes}.
   * Operates at character boundaries: for UTF-8 serialization, finds the
   * last complete character whose UTF-8 encoding fits; for UTF-16
   * serialization, truncates at char boundaries avoiding surrogate pair
   * splits. The truncated string preserves sort order (a prefix is always
   * &le; the full string in {@code String.compareTo()} order).
   */
  private static String truncateString(
      String s, int maxBytes,
      BinarySerializer<Object> serializer) {
    // Determine the header overhead for this serializer:
    // UTF8Serializer: 2-byte short header
    // StringSerializer: 4-byte int header (length in chars × 2)
    boolean isUtf8 = serializer.getId() == UTF8Serializer.ID;
    int headerSize = isUtf8
        ? ShortSerializer.SHORT_SIZE
        : IntegerSerializer.INT_SIZE;
    int maxPayload = maxBytes - headerSize;
    if (maxPayload <= 0) {
      return s; // can't truncate below header size
    }

    if (isUtf8) {
      // UTF-8 encoding: each char may be 1–4 bytes. Walk forward
      // counting encoded bytes, stop before exceeding maxPayload.
      return truncateStringUtf8(s, maxPayload);
    } else {
      // UTF-16 encoding: each char is exactly 2 bytes, except surrogate
      // pairs which are 4 bytes (2 chars × 2 bytes each). Truncate at
      // the last complete character/surrogate-pair boundary.
      int maxChars = maxPayload / 2;
      if (maxChars >= s.length()) {
        return s;
      }
      // Don't split a surrogate pair: if the last char would be a high
      // surrogate (leading surrogate), back up one position.
      if (maxChars > 0
          && Character.isHighSurrogate(s.charAt(maxChars - 1))) {
        maxChars--;
      }
      return maxChars > 0 ? s.substring(0, maxChars) : s;
    }
  }

  /**
   * Truncates a String so its UTF-8 encoded form fits within
   * {@code maxUtf8Bytes}. Respects character boundaries: never splits
   * a multi-byte UTF-8 sequence.
   */
  private static String truncateStringUtf8(String s, int maxUtf8Bytes) {
    int byteCount = 0;
    int charIndex = 0;
    while (charIndex < s.length()) {
      int codePoint = s.codePointAt(charIndex);
      int cpBytes;
      if (codePoint <= 0x7F) {
        cpBytes = 1;
      } else if (codePoint <= 0x7FF) {
        cpBytes = 2;
      } else if (codePoint <= 0xFFFF) {
        cpBytes = 3;
      } else {
        cpBytes = 4;
      }
      if (byteCount + cpBytes > maxUtf8Bytes) {
        break;
      }
      byteCount += cpBytes;
      charIndex += Character.charCount(codePoint);
    }
    return charIndex > 0 ? s.substring(0, charIndex) : s;
  }

  /**
   * Applies boundary truncation (Strategy 1) to all boundaries in a
   * {@link BuildResult}. After truncation, adjacent boundaries that have
   * become equal are merged (their buckets' frequencies and NDVs are
   * summed). This handles the rare case where two adjacent boundaries
   * share a prefix longer than {@code maxBoundaryBytes}.
   *
   * @return a new BuildResult with truncated boundaries and merged
   *         buckets, or the original result if no truncation was needed
   */
  static BuildResult truncateBoundaries(
      BuildResult result, int maxBoundaryBytes,
      BinarySerializer<Object> serializer,
      BinarySerializerFactory factory) {
    // Check if any boundary needs truncation (fast path: skip if all fit)
    boolean needsTruncation = false;
    for (int i = 0; i <= result.actualBucketCount; i++) {
      int size = serializer.getObjectSize(factory, result.boundaries[i]);
      if (size > maxBoundaryBytes) {
        needsTruncation = true;
        break;
      }
    }
    if (!needsTruncation) {
      return result;
    }

    // Truncate all boundaries
    var truncated = new Comparable<?>[result.actualBucketCount + 1];
    for (int i = 0; i <= result.actualBucketCount; i++) {
      truncated[i] = truncateBoundary(
          result.boundaries[i], maxBoundaryBytes, serializer, factory);
    }

    // Check for adjacent equal boundaries after truncation and merge
    var comparator = DefaultComparator.INSTANCE;
    // Count how many unique boundaries remain
    int uniqueCount = 1; // boundaries[0] is always kept
    for (int i = 1; i <= result.actualBucketCount; i++) {
      if (comparator.compare(truncated[i], truncated[i - 1]) != 0) {
        uniqueCount++;
      }
    }

    // If no adjacent duplicates, return with truncated boundaries
    if (uniqueCount == result.actualBucketCount + 1) {
      return new BuildResult(truncated, result.frequencies,
          result.distinctCounts, result.actualBucketCount,
          result.totalDistinct, result.mcvValue, result.mcvFrequency);
    }

    // Merge adjacent buckets whose boundaries became equal.
    // newBucketCount = uniqueCount - 1 (each unique pair of boundaries
    // defines one bucket).
    int newBucketCount = uniqueCount - 1;
    if (newBucketCount < 1) {
      // All boundaries collapsed to the same value — degenerate case.
      // Return a single-bucket histogram.
      long totalFreq = 0;
      long totalNdv = 0;
      for (int i = 0; i < result.actualBucketCount; i++) {
        totalFreq += result.frequencies[i];
        totalNdv += result.distinctCounts[i];
      }
      var bounds = new Comparable<?>[] {truncated[0], truncated[0]};
      return new BuildResult(bounds, new long[] {totalFreq},
          new long[] {totalNdv}, 1, result.totalDistinct,
          result.mcvValue, result.mcvFrequency);
    }

    var newBounds = new Comparable<?>[newBucketCount + 1];
    var newFreqs = new long[newBucketCount];
    var newNdvs = new long[newBucketCount];

    newBounds[0] = truncated[0];
    int newIdx = 0;
    long accFreq = 0;
    long accNdv = 0;

    for (int i = 0; i < result.actualBucketCount; i++) {
      accFreq += result.frequencies[i];
      accNdv += result.distinctCounts[i];

      // Check if the next boundary is different from the current one
      // (i.e., we've reached the end of a group of merged buckets).
      // The next boundary is truncated[i + 1].
      boolean isLast = (i == result.actualBucketCount - 1);
      boolean nextIsDifferent = !isLast
          && comparator.compare(
              truncated[i + 1], truncated[i]) != 0;

      if (isLast || nextIsDifferent) {
        newFreqs[newIdx] = accFreq;
        newNdvs[newIdx] = accNdv;
        newBounds[newIdx + 1] = truncated[i + 1];
        newIdx++;
        accFreq = 0;
        accNdv = 0;
      }
    }

    return new BuildResult(newBounds, newFreqs, newNdvs, newBucketCount,
        result.totalDistinct, result.mcvValue, result.mcvFrequency);
  }

  /**
   * Computes the serialized size of all boundaries including 4-byte length
   * prefixes.
   */
  private int computeBoundaryBytes(Comparable<?>[] boundaries,
      int bucketCount) {
    int total = 0;
    for (int i = 0; i <= bucketCount; i++) {
      int keySize =
          keySerializer.getObjectSize(serializerFactory, boundaries[i]);
      total += IntegerSerializer.INT_SIZE + keySize;
    }
    return total;
  }

  /**
   * Per-blob overhead in {@link EquiDepthHistogram#serialize}: bucketCount(4) +
   * nonNullCount(8) + mcvFrequency(8) + mcvKeyLength(4).
   */
  static final int HISTOGRAM_BLOB_HEADER_SIZE =
      IntegerSerializer.INT_SIZE + LongSerializer.LONG_SIZE
          + LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE;

  /**
   * Computes available page space for boundaries given the current
   * bucket count, HLL size, and MCV key size.  The result is the number
   * of bytes that may be occupied by serialized boundary keys (each
   * prefixed with a 4-byte length) within the histogram data blob on
   * page 0.
   */
  static int computeMaxBoundarySpace(
      int bucketCount, int hllSize, int mcvKeySize) {
    int pagePayload =
        DurablePage.MAX_PAGE_SIZE_BYTES - DurablePage.NEXT_FREE_POSITION;
    return pagePayload - FIXED_HEADER_SIZE
        - HISTOGRAM_BLOB_HEADER_SIZE // blob header inside EquiDepthHistogram.serialize()
        - bucketCount * LongSerializer.LONG_SIZE // frequencies
        - bucketCount * LongSerializer.LONG_SIZE // distinctCounts
        - hllSize
        - mcvKeySize;
  }

  /**
   * Merges buckets from originalCount down to targetCount by combining
   * adjacent pairs.
   */
  static BuildResult mergeBuckets(
      Comparable<?>[] boundaries, long[] frequencies,
      long[] distinctCounts, int originalCount, int targetCount) {
    int ratio = originalCount / targetCount;
    var newBounds = new Comparable<?>[targetCount + 1];
    var newFreqs = new long[targetCount];
    var newNDV = new long[targetCount];

    newBounds[0] = boundaries[0];
    for (int i = 0; i < targetCount; i++) {
      int start = i * ratio;
      int end = (i == targetCount - 1) ? originalCount : (i + 1) * ratio;
      long freqSum = 0;
      long ndvSum = 0;
      for (int j = start; j < end; j++) {
        freqSum += frequencies[j];
        ndvSum += distinctCounts[j];
      }
      newFreqs[i] = freqSum;
      newNDV[i] = ndvSum;
      newBounds[i + 1] = boundaries[end];
    }

    return new BuildResult(newBounds, newFreqs, newNDV, targetCount, 0,
        null, 0);
  }

  // ---- Internal: rebalance ----

  // Package-private for testability (RebalanceTriggerTest).
  long computeRebalanceThreshold(HistogramSnapshot snapshot) {
    long threshold =
        (long) (snapshot.totalCountAtLastBuild() * rebalanceMutationFraction);
    threshold = Math.min(threshold, maxRebalanceMutations);
    threshold = Math.max(threshold, minRebalanceMutations);
    if (snapshot.hasDriftedBuckets()) {
      threshold = Math.max(1, threshold / 2);
    }
    return threshold;
  }

  private void scheduleRebalance(
      ExecutorService executor) {
    // Check cooldown — only enforce when a failure has actually occurred
    // (lastRebalanceFailureNanos != 0). Without this guard, on JVM startup
    // System.nanoTime() - 0 could theoretically be negative (nanoTime spec
    // allows negative values), which would bypass the cooldown.
    if (lastRebalanceFailureNanos != 0
        && System.nanoTime() - lastRebalanceFailureNanos
            < rebalanceFailureCooldownNs) {
      return;
    }
    // At-most-one guard
    if (!rebalanceInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.submit(() -> {
        try {
          doRebalance(false);
          lastRebalanceFailureNanos = 0;
        } catch (Exception e) {
          lastRebalanceFailureNanos = System.nanoTime();
          logger.warn("Histogram rebalance failed for {}", getName(), e);
        } finally {
          rebalanceInProgress.set(false);
        }
      });
    } catch (Exception e) {
      // Any failure to submit (RejectedExecutionException on shutdown,
      // OOME on lambda allocation, etc.) — reset the CAS guard so future
      // schedules are not permanently blocked.
      rebalanceInProgress.set(false);
      if (!(e instanceof RejectedExecutionException)) {
        logger.warn("Failed to schedule histogram rebalance for {}",
            getName(), e);
      }
    }
  }

  /**
   * Performs the actual rebalance: scan keys, build histogram, install in
   * CHM cache.
   *
   * @param bypassMinSize true for ANALYZE INDEX (always build)
   */
  private void doRebalance(boolean bypassMinSize) {
    if (keyStreamSupplier == null || fileHandler == null) {
      return;
    }

    // Acquire semaphore if available (bypass for ANALYZE INDEX)
    Semaphore sem = bypassMinSize ? null : rebalanceSemaphore;
    if (sem != null && !sem.tryAcquire()) {
      return; // deferred — will re-trigger on next planner read
    }

    try {
      var snapshot = cache.get(engineId);
      if (snapshot == null) {
        return;
      }

      long totalCount = snapshot.stats().totalCount();
      long nullCount = snapshot.stats().nullCount();
      long nonNullCount = totalCount - nullCount;

      if (!bypassMinSize && nonNullCount < histogramMinSize) {
        return;
      }

      // Adaptive bucket count
      int targetBuckets =
          GlobalConfiguration.QUERY_STATS_HISTOGRAM_BUCKETS
              .getValueAsInteger();
      if (nonNullCount > 0) {
        targetBuckets = Math.min(targetBuckets,
            (int) Math.floor(Math.sqrt(nonNullCount)));
      }
      // NDV cap: avoid over-allocation when distinct values are few.
      // Use the previous snapshot's distinctCount as an upper bound.
      // On the initial build this is 0 or unknown — skip (scan trims
      // naturally). For single-value indexes distinctCount == nonNullCount,
      // so this cap never fires (the sqrt cap already governs).
      long prevDistinct = snapshot.stats().distinctCount();
      if (prevDistinct > 0 && prevDistinct < targetBuckets) {
        targetBuckets = (int) prevDistinct;
      }
      targetBuckets = Math.max(targetBuckets, MINIMUM_BUCKET_COUNT);

      // For multi-value indexes, create the HLL before the scan so it gets
      // populated during the single pass (same approach as buildHistogram).
      HyperLogLogSketch newHll = null;
      KeyHasher hasher = null;
      if (!isSingleValue) {
        newHll = new HyperLogLogSketch();
        hasher = this::hashKey;
      }

      // Scan sorted keys. A lightweight read-only atomic operation is created
      // on this thread for snapshot visibility and tsMin tracking, then
      // deactivated after the stream is consumed.
      IndexHistogramManager.BuildResult result;
      var atomicOp = storage.getAtomicOperationsManager().startAtomicOperation();
      try {
        try (Stream<Object> keyStream = keyStreamSupplier.apply(atomicOp)) {
          Stream<Object> effectiveKeys;
          if (keyFieldCount > 1) {
            effectiveKeys =
                keyStream.map(k -> ((CompositeKey) k).getKeys().getFirst());
          } else {
            effectiveKeys = keyStream;
          }
          result = scanAndBuild(
              effectiveKeys, nonNullCount, targetBuckets, newHll, hasher);
        }
      } finally {
        atomicOp.deactivate();
      }
      if (result == null) {
        return;
      }

      // Use the actual scanned count (sum of frequencies) rather than the
      // snapshot's nonNullCount. Under concurrent writes the snapshot counter
      // and the B-tree contents can diverge, leading to nonNullCount !=
      // sum(frequencies) in the resulting histogram.
      long scannedNonNullCount = 0;
      for (int i = 0; i < result.actualBucketCount; i++) {
        scannedNonNullCount += result.frequencies[i];
      }
      var fitResult = fitToPage(result, scannedNonNullCount);
      if (fitResult == null) {
        return;
      }

      long scannedDistinctCount = result.totalDistinct;

      boolean hllOnPage1 = false;
      if (!isSingleValue) {
        hllOnPage1 = fitResult.hllOnPage1;
      }

      // Install new snapshot via cache.compute() — preserves scalar counters
      // from concurrent commits that occurred after the scan started.
      final var finalHistogram = fitResult.histogram;
      final var finalHll = newHll;
      final long finalNDV = scannedDistinctCount;
      final boolean finalHllOnPage1 = hllOnPage1;
      cache.compute(engineId, (id, old) -> {
        if (old == null) {
          return null; // engine deleted during rebalance
        }
        // Use CHM's totalCount/nullCount (most up-to-date — includes concurrent
        // commits after the scan) rather than the scanned values. For distinctCount,
        // use the scan's exact NDV (replaces the approximate HLL estimate).
        // Note: this means distinctCount may briefly exceed totalCount - nullCount
        // if concurrent inserts added entries during the scan. This is benign —
        // subsequent delta applications will correct it, and the planner tolerates
        // approximate stats.
        var newStats = new IndexStatistics(
            old.stats().totalCount(), finalNDV, old.stats().nullCount());
        return new HistogramSnapshot(
            newStats,
            finalHistogram,
            0, // reset mutationsSinceRebalance
            old.stats().totalCount(), // totalCountAtLastBuild (latest committed, not scanned)
            old.version() + 1, // increment version
            false, // reset hasDriftedBuckets
            finalHll,
            finalHllOnPage1);
      });

      // Persist the new snapshot. On failure, mark dirty so the next
      // checkpoint or commit-path batch flush retries the persistence.
      // The CHM snapshot is already installed (correct in-memory state);
      // only the on-disk page needs to catch up.
      try {
        flushSnapshotToPage();
        DIRTY_MUTATIONS.setRelease(this, 0L);
      } catch (IOException e) {
        DIRTY_MUTATIONS.getAndAdd(this, 1);
        logger.warn("Failed to persist rebalanced histogram for {}",
            getName(), e);
      }
    } finally {
      if (sem != null) {
        sem.release();
      }
    }
  }

  /**
   * Waits for an in-progress rebalance to complete (for ANALYZE INDEX).
   */
  @Nullable private HistogramSnapshot waitForRebalanceAndReturn() {
    // Poll with bounded timeout (100ms intervals, 30s max)
    for (int i = 0; i < 300; i++) {
      if (!rebalanceInProgress.get()) {
        return cache.get(engineId);
      }
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        return cache.get(engineId);
      }
      LockSupport.parkNanos(100_000_000L); // 100 ms
    }
    return cache.get(engineId);
  }

  // ---- Internal: page I/O ----

  /**
   * Creates the .ixs file with an initial empty page. Shared by
   * {@link #createStatsFile} and {@link #createStatsFileWithCounters}.
   */
  private void createEmptyStatsPage(AtomicOperation op) throws IOException {
    fileHandler = addFile(op, getFullName());
    var cacheEntry = addPage(op, fileHandler);
    try {
      var page = new HistogramStatsPage(cacheEntry);
      page.writeEmpty(serializerId);
    } finally {
      releasePageFromWrite(op, cacheEntry);
    }
  }

  private HistogramSnapshot createEmptySnapshot() {
    var stats = new IndexStatistics(0, 0, 0);
    return new HistogramSnapshot(stats, null, 0, 0, 0, false, null, false);
  }

  /**
   * Reads the snapshot from the .ixs file. Reads page 0 first; if the
   * HLL page-1 flag is set, also reads page 1 to load the HLL registers.
   */
  private HistogramSnapshot readSnapshotFromPage(AtomicOperation op)
      throws IOException {
    HistogramSnapshot snapshot;
    var cacheEntry = loadPageForRead(op, fileHandler, 0);
    try {
      var page = new HistogramStatsPage(cacheEntry);
      snapshot = page.readSnapshot(keySerializer, serializerFactory);
    } finally {
      releasePageFromRead(op, cacheEntry);
    }

    // If HLL was spilled to page 1, load it separately.
    // Guard against missing page 1 (e.g., crash between page-0 and
    // page-1 write — both are in the same atomic op, but defensive).
    if (snapshot.hllOnPage1()) {
      long filledUpTo = getFilledUpTo(op, fileHandler);
      if (filledUpTo > 1) {
        var page1Entry = loadPageForRead(op, fileHandler, 1);
        try {
          var hll = HistogramStatsPage.readHllFromPage1(page1Entry);
          snapshot = new HistogramSnapshot(
              snapshot.stats(), snapshot.histogram(),
              snapshot.mutationsSinceRebalance(),
              snapshot.totalCountAtLastBuild(),
              snapshot.version(), snapshot.hasDriftedBuckets(),
              hll, true);
        } finally {
          releasePageFromRead(op, page1Entry);
        }
      } else {
        // Page 1 missing — fall back to empty HLL; next rebalance
        // will rebuild it from the sorted key stream.
        logger.warn("HLL page-1 flag set but page 1 missing for {};"
            + " will rebuild on next rebalance", getName());
        snapshot = new HistogramSnapshot(
            snapshot.stats(), snapshot.histogram(),
            snapshot.mutationsSinceRebalance(),
            snapshot.totalCountAtLastBuild(),
            snapshot.version(), snapshot.hasDriftedBuckets(),
            new HyperLogLogSketch(), true);
      }
    }

    return snapshot;
  }

  /**
   * Writes the given snapshot to the .ixs file. Writes page 0, and if
   * {@code hllOnPage1} is set, also writes HLL registers to page 1.
   * Both pages are written within the same {@link AtomicOperation}, so
   * they are flushed atomically via WAL.
   */
  private void writeSnapshotToPage(AtomicOperation op,
      HistogramSnapshot snapshot) throws IOException {
    var cacheEntry = loadPageForWrite(op, fileHandler, 0, true);
    try {
      var page = new HistogramStatsPage(cacheEntry);
      page.writeSnapshot(snapshot, serializerId,
          keySerializer, serializerFactory);
    } finally {
      releasePageFromWrite(op, cacheEntry);
    }

    // Write HLL to page 1 when spilled. loadOrAddPageForWrite creates
    // page 1 on first use (the .ixs file starts with only page 0).
    if (snapshot.hllOnPage1() && snapshot.hllSketch() != null) {
      var page1Entry = loadOrAddPageForWrite(op, fileHandler, 1);
      try {
        HistogramStatsPage.writeHllToPage1(page1Entry, snapshot.hllSketch());
      } finally {
        releasePageFromWrite(op, page1Entry);
      }
    }
  }

  /**
   * Writes the current cached snapshot to the .ixs file. Creates its own
   * atomic operation (flush runs from commit and rebalance paths where
   * no caller-provided AtomicOperation is available).
   */
  private void flushSnapshotToPage() throws IOException {
    var snapshot = cache.get(engineId);
    if (snapshot == null || fileHandler == null) {
      return;
    }

    // Create a standalone atomic operation for the flush — we cannot
    // use executeInsideComponentOperation(null, ...) because that
    // passes null to AtomicOperationsManager which requires non-null.
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
      var cacheEntry = loadPageForWrite(op, fileHandler, 0, true);
      try {
        var page = new HistogramStatsPage(cacheEntry);
        page.writeSnapshot(snapshot, serializerId,
            keySerializer, serializerFactory);
      } finally {
        releasePageFromWrite(op, cacheEntry);
      }

      // Write HLL to page 1 when spilled
      if (snapshot.hllOnPage1() && snapshot.hllSketch() != null) {
        var page1Entry = loadOrAddPageForWrite(op, fileHandler, 1);
        try {
          HistogramStatsPage.writeHllToPage1(
              page1Entry, snapshot.hllSketch());
        } finally {
          releasePageFromWrite(op, page1Entry);
        }
      }
    });
  }

  // ---- Internal: key utilities ----

  /**
   * Extracts the leading (first) field from a key. Identity for single-field
   * indexes; first component for composite indexes.
   */
  private Object extractLeadingField(Object key) {
    if (keyFieldCount > 1 && key instanceof CompositeKey compositeKey) {
      return compositeKey.getKeys().getFirst();
    }
    return key;
  }

  /**
   * Hashes a key value to a 64-bit long for HLL register update.
   * Uses type-specific hashing for common key types to avoid byte[]
   * allocation via serialization on the hot onPut/rebalance path.
   * String keys use a two-round long hash (hashCode → MurmurHash3) to
   * avoid allocating a serialized byte[] per key.
   */
  long hashKey(Object key) {
    if (key instanceof Long v) {
      return MurmurHash3.murmurHash3_x64_64(v, MURMUR_SEED);
    } else if (key instanceof Integer v) {
      return MurmurHash3.murmurHash3_x64_64(v, MURMUR_SEED);
    } else if (key instanceof Double v) {
      return MurmurHash3.murmurHash3_x64_64(
          Double.doubleToLongBits(v), MURMUR_SEED);
    } else if (key instanceof java.util.Date v) {
      return MurmurHash3.murmurHash3_x64_64(v.getTime(), MURMUR_SEED);
    } else if (key instanceof String v) {
      // Hash chars directly for full 64-bit entropy. Unlike the
      // String.hashCode() → long mixer approach (which caps at 2^32
      // distinct outputs and plateaus the HLL around ~4M), this reads
      // charAt() values into MurmurHash3 blocks — no byte[] allocation,
      // full 64-bit range, O(n) same as hashCode() on first call.
      return MurmurHash3.murmurHash3_x64_64(v, MURMUR_SEED);
    }
    // Remaining variable-length types (byte[], Decimal, CompositeKey):
    // fall back to serialization.
    byte[] bytes = keySerializer.serializeNativeAsWhole(
        serializerFactory, key);
    return MurmurHash3.murmurHash3_x64_64(bytes, MURMUR_SEED);
  }

  // ---- Public accessors ----

  public int getKeyFieldCount() {
    return keyFieldCount;
  }

  // ---- Accessors for testing ----

  int getEngineId() {
    return engineId;
  }

  long getDirtyMutations() {
    return (long) DIRTY_MUTATIONS.getAcquire(this);
  }

  boolean isRebalanceInProgress() {
    return rebalanceInProgress.get();
  }

  long getLastRebalanceFailureNanos() {
    return lastRebalanceFailureNanos;
  }

  void setLastRebalanceFailureNanos(long nanos) {
    this.lastRebalanceFailureNanos = nanos;
  }

  void setFileIdForTest(long fileId) {
    this.fileHandler = new FileHandler(fileId);
  }

  void setDirtyMutationsForTest(long value) {
    DIRTY_MUTATIONS.setRelease(this, value);
  }

  void resetRebalanceInProgressForTest() {
    rebalanceInProgress.set(false);
  }
}
