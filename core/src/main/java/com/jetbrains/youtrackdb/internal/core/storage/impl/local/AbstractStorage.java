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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.io.YTIOException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Stopwatch;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfigurationUpdateListener;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBagDeleter;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CollectionDoesNotExistException;
import com.jetbrains.youtrackdb.internal.core.exception.CommitSerializationException;
import com.jetbrains.youtrackdb.internal.core.exception.ConcurrentCreateException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.InternalErrorException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidDatabaseNameException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidInstanceIdException;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageDoesNotExistException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageExistsException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.Indexes;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrackdb.internal.core.storage.IdentifiableStorage;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection.RECORD_STATUS;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.NonTxOperationPerformedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.OperationUnitRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.StorageCollectionFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageBrokenException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation for paginated storage engines, providing common storage operations.
 *
 * @since 28.03.13
 */
public abstract class AbstractStorage
    implements CheckpointRequestListener,
    IdentifiableStorage,
    BackgroundExceptionListener,
    FreezableStorageComponent,
    PageIsBrokenListener {

  private static final Logger logger = LoggerFactory.getLogger(AbstractStorage.class);
  private static final int WAL_RESTORE_REPORT_INTERVAL = 30 * 1000; // milliseconds

  private static final Comparator<RecordOperation> COMMIT_RECORD_OPERATION_COMPARATOR =
      Comparator.comparing(
          o -> o.record.getIdentity());

  /**
   * Version comparator for index snapshot visibility-index maps: orders by the
   * last key element (the committing TX's version = newVersion), falling back
   * to full element-wise comparison for uniqueness. Enables efficient
   * headMap(lwm) eviction.
   *
   * <p>Hand-written to avoid per-comparison allocations from
   * Comparator.comparingLong (unboxing) and thenComparing(identity)
   * (iterator allocation in CompositeKey.compareTo).
   */
  public static final Comparator<CompositeKey> INDEX_SNAPSHOT_VERSION_COMPARATOR =
      (a, b) -> {
        var aKeys = a.getKeys();
        var bKeys = b.getKeys();
        // Primary: compare last element (version) as long
        int cmp = Long.compare(
            (Long) aKeys.getLast(),
            (Long) bKeys.getLast());
        if (cmp != 0) {
          return cmp;
        }
        // Tiebreaker: element-wise comparison without iterator allocation.
        // Uses DefaultComparator for null-safe comparison (null keys are valid
        // in snapshot visibility maps for null-indexed entries).
        int aSize = aKeys.size();
        int bSize = bKeys.size();
        int minSize = Math.min(aSize, bSize);
        for (int i = 0; i < minSize; i++) {
          cmp = DefaultComparator.INSTANCE.compare(aKeys.get(i), bKeys.get(i));
          if (cmp != 0) {
            return cmp;
          }
        }
        return Integer.compare(aSize, bSize);
      };

  protected volatile LinkCollectionsBTreeManagerShared linkCollectionsBTreeManager;

  private final Map<String, StorageCollection> collectionMap = new HashMap<>();
  private final List<StorageCollection> collections = new CopyOnWriteArrayList<>();

  private final AtomicBoolean walVacuumInProgress = new AtomicBoolean();

  protected volatile WriteAheadLog writeAheadLog;
  @Nullable private StorageRecoverListener recoverListener;

  protected volatile ReadCache readCache;
  protected volatile WriteCache writeCache;

  private volatile RecordConflictStrategy recordConflictStrategy =
      YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getDefaultImplementation();

  protected volatile AtomicOperationsManager atomicOperationsManager;
  private volatile boolean wereNonTxOperationsPerformedInPreviousOpen;

  /**
   * Internal file IDs of non-durable files deleted during crash recovery, used by WAL replay
   * ({@link #restoreAtomicUnit}) to skip records referencing these files. Populated by
   * {@code writeCache.deleteNonDurableFilesOnRecovery()} before WAL replay, cleared after
   * replay completes. Plain (non-volatile) field — recovery is single-threaded.
   */
  private IntOpenHashSet deletedNonDurableFileIds = new IntOpenHashSet();

  private final int id;

  private final Map<String, BaseIndexEngine> indexEngineNameMap = new HashMap<>();
  private final List<BaseIndexEngine> indexEngines = new ArrayList<>();
  private final AtomicOperationIdGen idGen = new AtomicOperationIdGen();

  /**
   * Storage-level shared cache for histogram snapshots, keyed by engine ID.
   * Shared across all {@link IndexHistogramManager} instances in this storage.
   */
  private final ConcurrentHashMap<Integer, HistogramSnapshot> histogramSnapshotCache =
      new ConcurrentHashMap<>();

  /**
   * Executor reference for histogram managers. Must NOT be the ioExecutor
   * (used by AsynchronousFileChannel for I/O completions) — blocking reads
   * on the ioExecutor cause deadlocks. Stored so that newly created indexes
   * (after database open) can receive the executor immediately.
   * Set by {@link #setHistogramExecutor}.
   */
  @Nullable private volatile ExecutorService histogramExecutor;

  /**
   * Storage-level semaphore limiting concurrent histogram rebalance tasks.
   * Permit count is read from
   * {@link GlobalConfiguration#QUERY_STATS_MAX_CONCURRENT_REBALANCES} at
   * construction time ({@code -1} = auto: {@code max(2, processors / 4)}).
   * Propagated to each histogram manager via
   * {@link IndexHistogramManager#setRebalanceSemaphore}.
   */
  private final Semaphore histogramRebalanceSemaphore;

  private boolean wereDataRestoredAfterOpen;
  protected UUID uuid;

  private final AtomicInteger sessionCount = new AtomicInteger(0);
  private volatile long lastCloseTime = System.currentTimeMillis();

  protected static final String DATABASE_INSTANCE_ID = "databaseInstenceId";

  protected AtomicOperationsTable atomicOperationsTable;
  protected final String url;
  protected final ScalableRWLock stateLock;

  protected volatile StorageConfiguration configuration;
  protected volatile CurrentStorageComponentsFactory componentsFactory;
  protected final String name;
  private final AtomicLong version = new AtomicLong();

  protected volatile STATUS status = STATUS.CLOSED;

  protected final AtomicReference<Throwable> error = new AtomicReference<>(null);
  protected YouTrackDBInternalEmbedded context;
  private volatile CountDownLatch migration = new CountDownLatch(1);

  protected final ReentrantLock backupLock = new ReentrantLock();

  // Non-blocking lock for snapshot index cleanup. Only one thread performs cleanup at a time;
  // other committing threads skip cleanup if the lock is already held (tryLock pattern).
  // Package-private for testability (SnapshotIndexCleanupTest).
  final ReentrantLock snapshotCleanupLock = new ReentrantLock();

  private final Stopwatch dropDuration;
  private final Stopwatch synchDuration;
  private final Stopwatch shutdownDuration;

  // Per-thread holder for the minimum active operation timestamp (tsMin).
  // Used by the low-water-mark GC to determine which snapshot index entries are safe to evict.
  protected final ThreadLocal<TsMinHolder> tsMinThreadLocal =
      ThreadLocal.withInitial(TsMinHolder::new);

  // Set of all TsMinHolders across threads. Backed by Guava's ConcurrentHashMap with weak
  // keys so that entries are automatically removed when the owning thread's TsMinHolder
  // becomes unreachable (after thread death releases the ThreadLocal's strong reference).
  // Uses identity equality (MapMaker.weakKeys() default), matching TsMinHolder's inherited
  // Object.equals/hashCode.
  protected final Set<TsMinHolder> tsMins = newTsMinsSet();

  static Set<TsMinHolder> newTsMinsSet() {
    return Sets.newSetFromMap(new MapMaker().weakKeys().makeMap());
  }

  // Shared snapshot index: maps (componentId, collectionPosition, recordVersion) → PositionEntry.
  // Replaces per-collection snapshotIndex fields in PaginatedCollectionV2, enabling centralized
  // low-water-mark GC across all collections.
  protected final ConcurrentSkipListMap<SnapshotKey, PositionEntry> sharedSnapshotIndex =
      new ConcurrentSkipListMap<>();

  // Visibility index: maps (recordTs, componentId, collectionPosition) → SnapshotKey.
  // Ordering by recordTs first enables efficient range-scan eviction via headMap(lowWaterMark).
  protected final ConcurrentSkipListMap<VisibilityKey, SnapshotKey> visibilityIndex =
      new ConcurrentSkipListMap<>();

  // Approximate count of entries in sharedSnapshotIndex, used for O(1) cleanup threshold checks.
  // ConcurrentSkipListMap.size() is O(n) and calling it on every commit causes resource
  // exhaustion under sustained heavy concurrent load (e.g., 30-minute soak tests).
  // Incremented during flushSnapshotBuffers(), decremented during evictStaleSnapshotEntries().
  protected final AtomicLong snapshotIndexSize = new AtomicLong();

  // Indexes snapshot: maps CompositeKey(indexId, userKey..., version) → RID (TombstoneRID or plain).
  private final ConcurrentSkipListMap<CompositeKey, RID> sharedIndexesSnapshot =
      new ConcurrentSkipListMap<>();
  private final ConcurrentSkipListMap<CompositeKey, RID> sharedNullIndexesSnapshot =
      new ConcurrentSkipListMap<>();

  // Indexes snapshot visibility index: maps removedKey (newVersion) → addedKey (oldVersion),
  // ordered by newVersion (last key element). Enables efficient range-scan eviction via
  // headMap(lowWaterMark), matching the collection/edge eviction pattern.
  private final ConcurrentSkipListMap<CompositeKey, CompositeKey> indexesSnapshotVisibilityIndex =
      new ConcurrentSkipListMap<>(INDEX_SNAPSHOT_VERSION_COMPARATOR);
  private final ConcurrentSkipListMap<CompositeKey, CompositeKey> nullIndexSnapshotVisibilityIndex =
      new ConcurrentSkipListMap<>(INDEX_SNAPSHOT_VERSION_COMPARATOR);

  // Approximate count of entries in sharedIndexesSnapshotData + sharedNullIndexesSnapshotData,
  // used for O(1) cleanup threshold checks.
  // Incremented by IndexesSnapshot.addSnapshotPair() (2 per pair), decremented by
  // evictStaleIndexesSnapshotEntries().
  protected final AtomicLong indexesSnapshotEntriesCount = new AtomicLong();

  // Edge snapshot index: maps (componentId, ridBagId, targetCollection, targetPosition, version)
  // → LinkBagValue. Stores old versions of link bag entries for snapshot isolation on edges.
  // Parallel to sharedSnapshotIndex but with edge-specific key types and full value storage
  // (B-tree entries don't have stable page positions across splits/merges).
  protected final ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex =
      new ConcurrentSkipListMap<>();

  // Edge visibility index: maps (recordTs, componentId, ridBagId, targetCollection,
  // targetPosition) → EdgeSnapshotKey. Ordering by recordTs first enables efficient
  // range-scan eviction via headMap(lowWaterMark), same pattern as visibilityIndex.
  protected final ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> edgeVisibilityIndex =
      new ConcurrentSkipListMap<>();

  // Approximate count of entries in sharedEdgeSnapshotIndex, used for O(1) cleanup threshold
  // checks. Same rationale as snapshotIndexSize — ConcurrentSkipListMap.size() is O(n).
  protected final AtomicLong edgeSnapshotIndexSize = new AtomicLong();

  // Stale transaction monitor (YTDB-550): periodically scans tsMins to detect long-running
  // transactions and logs warnings. Initialized at storage open, stopped at shutdown.
  @Nullable private volatile StaleTransactionMonitor staleTransactionMonitor;

  public AbstractStorage(
      final String name, final String filePath, final int id,
      YouTrackDBInternalEmbedded context) {
    this.context = context;
    this.name = checkName(name);

    url = filePath;

    stateLock = new ScalableRWLock();

    this.id = id;

    int permits = GlobalConfiguration.QUERY_STATS_MAX_CONCURRENT_REBALANCES
        .getValueAsInteger();
    if (permits <= 0) {
      permits = Math.max(2,
          Runtime.getRuntime().availableProcessors() / 4);
    }
    this.histogramRebalanceSemaphore = new Semaphore(permits);

    linkCollectionsBTreeManager = new LinkCollectionsBTreeManagerShared(this);
    dropDuration = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DATABASE_DROP_DURATION, this.name);
    synchDuration = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DATABASE_SYNCH_DURATION, this.name);
    shutdownDuration = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DATABASE_SHUTDOWN_DURATION, this.name);
  }

  protected static String normalizeName(String name) {
    final var firstIndexOf = name.lastIndexOf('/');
    final var secondIndexOf = name.lastIndexOf(File.separator);

    if (firstIndexOf >= 0 || secondIndexOf >= 0) {
      return name.substring(Math.max(firstIndexOf, secondIndexOf) + 1);
    } else {
      return name;
    }
  }

  public static String checkName(String name) {
    name = normalizeName(name);

    var pattern = Pattern.compile("^\\p{L}[\\p{L}\\d_$-]*$");
    var matcher = pattern.matcher(name);
    var isValid = matcher.matches();
    if (!isValid) {
      throw new InvalidDatabaseNameException(
          "Invalid name for database. ("
              + name
              + ") Name can contain only letters, numbers, underscores and dashes. "
              + "Name should start with letter.");
    }

    return name;
  }

  @Override
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public Storage getUnderlying() {
    return this;
  }

  @Override
  public String getName() {
    return name;
  }

  public String getURL() {
    return url;
  }

  public ContextConfiguration getContextConfiguration() {
    return configuration.getContextConfiguration();
  }

  public String getSchemaRecordId() {
    return configuration.getSchemaRecordId();
  }

  public String getCharset() {
    return configuration.getCharset();
  }

  public String getIndexMgrRecordId() {
    return configuration.getIndexMgrRecordId();
  }

  @Nullable public TimeZone getTimeZone() {
    return configuration.getTimeZone();
  }

  public SimpleDateFormat getDateFormatInstance() {
    return configuration.getDateFormatInstance();
  }

  public String getDateFormat() {
    return configuration.getDateFormat();
  }

  public String getDateTimeFormat() {
    return configuration.getDateTimeFormat();
  }

  public SimpleDateFormat getDateTimeFormatInstance() {
    return configuration.getDateTimeFormatInstance();
  }

  public String getRecordSerializer() {
    return configuration.getRecordSerializer();
  }

  public int getRecordSerializerVersion() {
    return configuration.getRecordSerializerVersion();
  }

  public String getLocaleCountry() {
    return configuration.getLocaleCountry();
  }

  public String getLocaleLanguage() {
    return configuration.getLocaleLanguage();
  }

  public int getMinimumCollections() {
    return configuration.getMinimumCollections();
  }

  public boolean isValidationEnabled() {
    return configuration.isValidationEnabled();
  }

  @Override
  public void close(DatabaseSessionEmbedded session) {
    var sessions = sessionCount.decrementAndGet();

    if (sessions < 0) {
      throw new StorageException(name,
          "Amount of closed sessions in storage "
              + name
              + " is bigger than amount of open sessions");
    }
    lastCloseTime = System.currentTimeMillis();
  }

  private void closeIfPossible() {
    while (true) {
      var sessions = sessionCount.get();
      if (sessions < 0) {
        return;
      }
      if (sessionCount.compareAndSet(sessions, sessions - 1)) {
        lastCloseTime = System.currentTimeMillis();
        return;
      }
    }
  }

  public long getSessionsCount() {
    return sessionCount.get();
  }

  public long getLastCloseTime() {
    return lastCloseTime;
  }

  @Override
  public boolean dropCollection(DatabaseSessionEmbedded session, final String iCollectionName) {
    return dropCollection(session, getCollectionIdByName(iCollectionName));
  }

  @Override
  public long countRecords(DatabaseSessionEmbedded session) {
    long tot = 0;
    for (var c : getCollectionInstances()) {
      if (c != null) {
        tot += c.getApproximateRecordsCount();
      }
    }
    return tot;
  }

  @Override
  public String toString() {
    return url != null ? url : "?";
  }

  @Override
  public STATUS getStatus() {
    return status;
  }

  @Override
  public boolean isAssigningCollectionIds() {
    return true;
  }

  @Override
  public CurrentStorageComponentsFactory getComponentsFactory() {
    return componentsFactory;
  }

  @Override
  public long getVersion() {
    return version.get();
  }

  @Override
  public void shutdown() {
    stateLock.writeLock().lock();
    try {
      doShutdown();
    } catch (final IOException e) {
      final var message = "Error on closing of storage '" + name;
      LogManager.instance().error(this, message, e);

      throw BaseException.wrapException(new StorageException(name, message), e, name);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private static void checkPageSizeAndRelatedParametersInGlobalConfiguration(String dbName) {
    final var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;
    var maxKeySize = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
    var bTreeMaxKeySize = (int) (pageSize * 0.3);

    if (maxKeySize <= 0) {
      maxKeySize = bTreeMaxKeySize;
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(maxKeySize);
    }

    if (maxKeySize > bTreeMaxKeySize) {
      throw new StorageException(dbName,
          "Value of parameter "
              + GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getKey()
              + " should be at least 4 times bigger than value of parameter "
              + GlobalConfiguration.BTREE_MAX_KEY_SIZE.getKey()
              + " but real values are :"
              + GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getKey()
              + " = "
              + pageSize
              + " , "
              + GlobalConfiguration.BTREE_MAX_KEY_SIZE.getKey()
              + " = "
              + maxKeySize);
    }
  }

  private static SortedMap<String, FrontendTransactionIndexChanges> getSortedIndexOperations(
      final FrontendTransaction clientTx) {
    return new TreeMap<>(clientTx.getIndexOperations());
  }

  @Override
  public final void open(
      DatabaseSessionEmbedded remote, final String iUserName,
      final String iUserPassword,
      final ContextConfiguration contextConfiguration) {
    open(contextConfiguration);
  }

  public final void open(final ContextConfiguration contextConfiguration) {
    try {
      stateLock.readLock().lock();
      try {
        if (status == STATUS.OPEN || isInError()) {
          // ALREADY OPENED: THIS IS THE CASE WHEN A STORAGE INSTANCE IS
          // REUSED

          sessionCount.incrementAndGet();
          return;
        }

      } finally {
        stateLock.readLock().unlock();
      }

      checkPageSizeAndRelatedParametersInGlobalConfiguration(name);
      try {
        stateLock.writeLock().lock();
        try {
          if (status == STATUS.MIGRATION) {
            try {
              // Yes this look inverted but is correct.
              stateLock.writeLock().unlock();
              migration.await();
            } finally {
              stateLock.writeLock().lock();
            }
          }

          if (status == STATUS.OPEN || isInError())
          // ALREADY OPENED: THIS IS THE CASE WHEN A STORAGE INSTANCE IS
          // REUSED
          {
            return;
          }

          if (status != STATUS.CLOSED) {
            throw new StorageException(name,
                "Storage " + name + " is in wrong state " + status + " and can not be opened.");
          }

          if (!exists()) {
            throw new StorageDoesNotExistException(name,
                "Cannot open the storage '" + name + "' because it does not exist in path: " + url);
          }

          readIv();

          initWalAndDiskCache(contextConfiguration);

          final var startupMetadata = checkIfStorageDirty();
          final var lastTxId = startupMetadata.lastTxId;
          if (lastTxId > 0) {
            idGen.setStartId(lastTxId + 1);
          } else {
            idGen.setStartId(0);
          }

          atomicOperationsTable =
              new AtomicOperationsTable(
                  contextConfiguration.getValueAsInteger(
                      GlobalConfiguration.STORAGE_ATOMIC_OPERATIONS_TABLE_COMPACTION_LIMIT),
                  idGen.getLastId() + 1);
          atomicOperationsManager = new AtomicOperationsManager(this, atomicOperationsTable);

          recoverIfNeeded();

          atomicOperationsManager.executeInsideAtomicOperation(
              atomicOperation -> {
                if (CollectionBasedStorageConfiguration.exists(writeCache)) {
                  configuration = new CollectionBasedStorageConfiguration(this);
                  ((CollectionBasedStorageConfiguration) configuration)
                      .load(contextConfiguration, atomicOperation);

                  // otherwise delayed to disk based storage to convert old format to new format.
                }

                initConfiguration(contextConfiguration, atomicOperation);
              });

          atomicOperationsManager.executeInsideAtomicOperation(
              (atomicOperation) -> {
                var uuid = configuration.getUuid();
                if (uuid == null) {
                  uuid = UUID.randomUUID().toString();
                  configuration.setUuid(atomicOperation, uuid);
                }
                this.uuid = UUID.fromString(uuid);
              });

          var binaryFormatVersion =
              atomicOperationsManager.calculateInsideAtomicOperation(
                  configuration::getBinaryFormatVersion);
          componentsFactory = new CurrentStorageComponentsFactory(binaryFormatVersion);

          atomicOperationsManager.executeInsideAtomicOperation(
              this::checkPageSizeAndRelatedParameters);

          atomicOperationsManager.executeInsideAtomicOperation(linkCollectionsBTreeManager::load);

          atomicOperationsManager.executeInsideAtomicOperation(this::openCollections);
          atomicOperationsManager.executeInsideAtomicOperation(this::openIndexes);

          atomicOperationsManager.executeInsideAtomicOperation(
              (atomicOperation) -> {
                final var cs = configuration.getConflictStrategy();
                if (cs != null) {
                  // SET THE CONFLICT STORAGE STRATEGY FROM THE LOADED CONFIGURATION
                  doSetConflictStrategy(
                      YouTrackDBEnginesManager.instance().getRecordConflictStrategy()
                          .getStrategy(cs),
                      atomicOperation);
                }
              });

          status = STATUS.MIGRATION;
        } finally {
          stateLock.writeLock().unlock();
        }

        // we need to use read lock to allow for example correctly truncate WAL during data
        // processing
        // all operations are prohibited on storage because of usage of special status.
        stateLock.readLock().lock();
        try {
          if (status != STATUS.MIGRATION) {
            LogManager.instance()
                .error(
                    this,
                    "Unexpected storage status %s, process of creation of storage is aborted",
                    null,
                    status.name());
            return;
          }

          //migration goes here, for future use
        } finally {
          stateLock.readLock().unlock();
        }

        stateLock.writeLock().lock();
        try {
          if (status != STATUS.MIGRATION) {
            LogManager.instance()
                .error(
                    this,
                    "Unexpected storage status %s, process of creation of storage is aborted",
                    null,
                    status.name());
            return;
          }

          atomicOperationsManager.executeInsideAtomicOperation(this::checkRidBagsPresence);
          status = STATUS.OPEN;
          startStaleTransactionMonitor();
          migration.countDown();
        } finally {
          stateLock.writeLock().unlock();
        }

      } catch (final RuntimeException e) {
        try {
          if (writeCache != null) {
            readCache.closeStorage(writeCache);
          }
        } catch (final Exception ee) {
          // ignore
        }

        try {
          if (writeAheadLog != null) {
            writeAheadLog.close();
          }
        } catch (final Exception ee) {
          // ignore
        }

        try {
          postCloseSteps(false, true, idGen.getLastId());
        } catch (final Exception ee) {
          // ignore
        }

        status = STATUS.CLOSED;
        throw e;
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      if (status == STATUS.OPEN) {
        sessionCount.incrementAndGet();
      }
    }

    final var additionalArgs = new Object[] {getURL(), YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .info(this, "Storage '%s' is opened under YouTrackDB distribution : %s", additionalArgs);
  }

  protected abstract void readIv() throws IOException;

  @SuppressWarnings("unused")
  protected abstract byte[] getIv();

  /** {@inheritDoc} */
  @Override
  public final String getCreatedAtVersion() {
    return configuration.getCreatedAtVersion();
  }

  protected final void openIndexes(AtomicOperation atomicOperation) {
    final var cf = componentsFactory;
    if (cf == null) {
      throw new StorageException(name, "Storage '" + name + "' is not properly initialized");
    }
    final var indexNames = configuration.indexEngines(atomicOperation);
    var counter = 0;

    // avoid duplication of index engine ids
    for (final var indexName : indexNames) {
      final var engineData = configuration.getIndexEngine(indexName, -1, atomicOperation);
      if (counter <= engineData.getIndexId()) {
        counter = engineData.getIndexId() + 1;
      }
    }

    for (final var indexName : indexNames) {
      final var engineData = configuration.getIndexEngine(indexName, counter, atomicOperation);

      final var engine = Indexes.createIndexEngine(this, engineData);

      engine.load(engineData, atomicOperation);

      // Wire histogram manager for B-tree engines
      if (engine instanceof BTreeIndexEngine btreeEngine) {
        wireHistogramManagerOnLoad(btreeEngine, engineData, atomicOperation);
      }

      indexEngineNameMap.put(engineData.getName(), engine);
      while (engineData.getIndexId() >= indexEngines.size()) {
        indexEngines.add(null);
      }
      indexEngines.set(engineData.getIndexId(), engine);
      counter++;
    }
  }

  protected final void openCollections(final AtomicOperation atomicOperation) throws IOException {
    // OPEN BASIC SEGMENTS
    int pos;

    // REGISTER COLLECTION
    final var configurationCollections = configuration.getCollections();
    for (var i = 0; i < configurationCollections.size(); ++i) {
      final var collectionConfig = configurationCollections.get(i);

      if (collectionConfig != null) {
        pos = createCollectionFromConfig(collectionConfig, atomicOperation);
        try {
          if (pos == -1) {
            collections.get(i).open(atomicOperation);
          } else {
            collections.get(pos).open(atomicOperation);
          }
        } catch (final FileNotFoundException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error on loading collection '"
                      + configurationCollections.get(i).name()
                      + "' ("
                      + i
                      + "): file not found. It will be excluded from current database '"
                      + name
                      + "'.",
                  e);

          collectionMap.remove(configurationCollections.get(i).name().toLowerCase(Locale.ROOT));

          setCollection(i, null);
        }
      } else {
        setCollection(i, null);
      }
    }
  }

  private void checkRidBagsPresence(final AtomicOperation operation) {
    for (final var collection : collections) {
      if (collection != null) {
        final var collectionId = collection.getId();

        if (!LinkCollectionsBTreeManagerShared.isComponentPresent(operation, collectionId)) {
          LogManager.instance()
              .info(
                  this,
                  "Collection with id %d does not have associated rid bag, fixing ...",
                  collectionId);
          linkCollectionsBTreeManager.createComponent(operation, collectionId);
        }
      }
    }
  }

  @Override
  public void create(final ContextConfiguration contextConfiguration) {

    try {
      stateLock.writeLock().lock();
      try {
        doCreate(contextConfiguration);
      } catch (final java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Storage creation was interrupted"), e, name);
      } catch (final StorageException e) {
        closeIfPossible();
        throw e;
      } catch (final IOException e) {
        closeIfPossible();
        throw BaseException.wrapException(
            new StorageException(name, "Error on creation of storage '" + name + "'"), e, name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }

    var fsyncAfterCreate =
        contextConfiguration.getValueAsBoolean(
            GlobalConfiguration.STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE);
    if (fsyncAfterCreate) {
      synch();
    }

    final var additionalArgs = new Object[] {getURL(), YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .info(this, "Storage '%s' is created under YouTrackDB distribution : %s", additionalArgs);
  }

  protected void doCreate(ContextConfiguration contextConfiguration)
      throws IOException, java.lang.InterruptedException {
    checkPageSizeAndRelatedParametersInGlobalConfiguration(name);

    if (name == null) {
      throw new InvalidDatabaseNameException("Database name can not be null");
    }

    if (name.isEmpty()) {
      throw new InvalidDatabaseNameException("Database name can not be empty");
    }

    final var namePattern = Pattern.compile("[^\\w$_-]+");
    final var matcher = namePattern.matcher(name);
    if (matcher.find()) {
      throw new InvalidDatabaseNameException(
          "Only letters, numbers, `$`, `_` and `-` are allowed in database name. Provided name :`"
              + name
              + "`");
    }

    if (status != STATUS.CLOSED) {
      throw new StorageExistsException(name,
          "Cannot create new storage '" + getURL() + "' because it is not closed");
    }

    if (exists()) {
      throw new StorageExistsException(name,
          "Cannot create new storage '" + getURL() + "' because it already exists");
    }

    uuid = UUID.randomUUID();
    initIv();

    initWalAndDiskCache(contextConfiguration);

    atomicOperationsTable =
        new AtomicOperationsTable(
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.STORAGE_ATOMIC_OPERATIONS_TABLE_COMPACTION_LIMIT),
            idGen.getLastId() + 1);
    atomicOperationsManager = new AtomicOperationsManager(this, atomicOperationsTable);

    preCreateSteps();
    makeStorageDirty();

    atomicOperationsManager.executeInsideAtomicOperation(
        (atomicOperation) -> {
          configuration = new CollectionBasedStorageConfiguration(this);
          ((CollectionBasedStorageConfiguration) configuration)
              .create(atomicOperation, contextConfiguration);
          configuration.setUuid(atomicOperation, uuid.toString());

          var binaryFormatVersion = atomicOperationsManager.calculateInsideAtomicOperation(
              configuration::getBinaryFormatVersion);
          componentsFactory = new CurrentStorageComponentsFactory(binaryFormatVersion);

          linkCollectionsBTreeManager.load(atomicOperation);

          status = STATUS.OPEN;
          startStaleTransactionMonitor();

          linkCollectionsBTreeManager = new LinkCollectionsBTreeManagerShared(this);

          // ADD THE METADATA COLLECTION TO STORE INTERNAL STUFF
          doAddCollection(atomicOperation, MetadataDefault.COLLECTION_INTERNAL_NAME);

          ((CollectionBasedStorageConfiguration) configuration)
              .setCreationVersion(atomicOperation, YouTrackDBConstants.getVersion());
          ((CollectionBasedStorageConfiguration) configuration)
              .setPageSize(
                  atomicOperation,
                  GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10);
          ((CollectionBasedStorageConfiguration) configuration)
              .setMaxKeySize(
                  atomicOperation, GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger());

          generateDatabaseInstanceId(atomicOperation);
          clearStorageDirty();
          postCreateSteps();
        });
  }

  protected void generateDatabaseInstanceId(AtomicOperation atomicOperation) {
    ((CollectionBasedStorageConfiguration) configuration)
        .setProperty(atomicOperation, DATABASE_INSTANCE_ID, UUID.randomUUID().toString());
  }

  @Nullable protected UUID readDatabaseInstanceId() {
    var id = configuration.getProperty(DATABASE_INSTANCE_ID);
    if (id != null) {
      return UUID.fromString(id);
    } else {
      return null;
    }
  }

  @SuppressWarnings("unused")
  protected void checkDatabaseInstanceId(UUID backupUUID) {
    var dbUUID = readDatabaseInstanceId();
    if (backupUUID == null) {
      throw new InvalidInstanceIdException(name,
          "The Database Instance Id do not mach, backup UUID is null");
    }
    if (dbUUID != null) {
      if (!dbUUID.equals(backupUUID)) {
        throw new InvalidInstanceIdException(name,
            String.format(
                "The Database Instance Id do not mach, database: '%s' backup: '%s'",
                dbUUID, backupUUID));
      }
    }
  }

  protected abstract void initIv() throws IOException;

  private void checkPageSizeAndRelatedParameters(AtomicOperation atomicOperation) {
    final var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;
    final var maxKeySize = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();

    if (configuration.getPageSize(atomicOperation) != -1
        && configuration.getPageSize(atomicOperation) != pageSize) {
      throw new StorageException(name,
          "Storage is created with value of "
              + configuration.getPageSize(atomicOperation)
              + " parameter equal to "
              + GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getKey()
              + " but current value is "
              + pageSize);
    }

    if (configuration.getMaxKeySize(atomicOperation) != -1
        && configuration.getMaxKeySize(atomicOperation) != maxKeySize) {
      throw new StorageException(name,
          "Storage is created with value of "
              + configuration.getMaxKeySize(atomicOperation)
              + " parameter equal to "
              + GlobalConfiguration.BTREE_MAX_KEY_SIZE.getKey()
              + " but current value is "
              + maxKeySize);
    }
  }

  @Override
  public final boolean isClosed(DatabaseSessionEmbedded database) {
    try {
      stateLock.readLock().lock();
      try {
        return isClosedInternal();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  protected final boolean isClosedInternal() {
    return status == STATUS.CLOSED;
  }

  @Override
  public final void close(DatabaseSessionEmbedded database, final boolean force) {
    try {
      if (!force) {
        close(database);
        return;
      }

      doShutdown();
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final void delete() {
    try {
      dropDuration.timed(() -> {
        stateLock.writeLock().lock();
        try {
          doDelete();
        } finally {
          stateLock.writeLock().unlock();
        }
      });
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void doDelete() throws IOException {
    makeStorageDirty();

    // CLOSE THE DATABASE BY REMOVING THE CURRENT USER
    doShutdownOnDelete();
    postDeleteSteps();
  }

  @Override
  public final int addCollection(DatabaseSessionEmbedded database, final String collectionName,
      final Object... parameters) {
    try {
      stateLock.writeLock().lock();
      try {
        if (collectionMap.containsKey(collectionName)) {
          throw new ConfigurationException(
              database.getDatabaseName(),
              String.format("Collection with name:'%s' already exists", collectionName));
        }
        checkOpennessAndMigration();

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            (atomicOperation) -> doAddCollection(atomicOperation, collectionName));
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error in creation of new collection '" + collectionName), e,
            name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final int addCollection(DatabaseSessionEmbedded database, final String collectionName,
      final int requestedId) {
    try {
      stateLock.writeLock().lock();
      try {
        checkOpennessAndMigration();

        if (requestedId < 0) {
          throw new ConfigurationException(database.getDatabaseName(),
              "Collection id must be positive!");
        }
        if (requestedId < collections.size() && collections.get(requestedId) != null) {
          throw new ConfigurationException(
              database.getDatabaseName(), "Requested collection ID ["
                  + requestedId
                  + "] is occupied by collection with name ["
                  + collections.get(requestedId).getName()
                  + "]");
        }
        if (collectionMap.containsKey(collectionName)) {
          throw new ConfigurationException(
              database.getDatabaseName(),
              String.format("Collection with name:'%s' already exists", collectionName));
        }

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            atomicOperation -> doAddCollection(atomicOperation, collectionName, requestedId));

      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Error in creation of new collection '" + collectionName + "'"),
            e,
            name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final boolean dropCollection(DatabaseSessionEmbedded database, final int collectionId) {
    try {
      stateLock.writeLock().lock();
      try {
        checkOpennessAndMigration();
        if (collectionId < 0 || collectionId >= collections.size()) {
          throw new IllegalArgumentException(
              "Collection id '"
                  + collectionId
                  + "' is outside the of range of configured collections (0-"
                  + (collections.size() - 1)
                  + ") in database '"
                  + name
                  + "'");
        }

        makeStorageDirty();

        return atomicOperationsManager.calculateInsideAtomicOperation(
            atomicOperation -> {
              if (dropCollectionInternal(atomicOperation, collectionId)) {
                return false;
              }

              ((CollectionBasedStorageConfiguration) configuration)
                  .dropCollection(atomicOperation, collectionId);
              linkCollectionsBTreeManager.deleteComponentByCollectionId(atomicOperation,
                  collectionId);

              return true;
            });
      } catch (final Exception e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error while removing collection '" + collectionId + "'"), e,
            name);

      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void checkCollectionId(int collectionId) {
    if (collectionId < 0 || collectionId >= collections.size()) {
      throw new CollectionDoesNotExistException(name,
          "Collection id '"
              + collectionId
              + "' is outside the of range of configured collections (0-"
              + (collections.size() - 1)
              + ") in database '"
              + name
              + "'");
    }
  }

  @Override
  public String getCollectionNameById(int collectionId) {
    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        checkCollectionId(collectionId);
        final var collection = collections.get(collectionId);
        if (collection == null) {
          throwCollectionDoesNotExist(collectionId);
        }

        return collection.getName();
      } finally {
        stateLock.readLock().unlock();
      }

    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public String getCollectionRecordConflictStrategy(int collectionId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkCollectionId(collectionId);
        final var collection = collections.get(collectionId);
        if (collection == null) {
          throwCollectionDoesNotExist(collectionId);
        }

        return Optional.ofNullable(collection.getRecordConflictStrategy())
            .map(RecordConflictStrategy::getName)
            .orElse(null);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public boolean isSystemCollection(int collectionId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkCollectionId(collectionId);
        final var collection = collections.get(collectionId);
        if (collection == null) {
          throwCollectionDoesNotExist(collectionId);
        }

        return collection.isSystemCollection();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private void throwCollectionDoesNotExist(int collectionId) {
    throw new CollectionDoesNotExistException(name,
        "Collection with id " + collectionId + " does not exist inside of storage " + name);
  }

  @Override
  public final int getId() {
    return id;
  }

  public UUID getUuid() {
    return uuid;
  }

  @Override
  public final LinkCollectionsBTreeManager getLinkCollectionsBtreeCollectionManager() {
    return linkCollectionsBTreeManager;
  }

  public ReadCache getReadCache() {
    return readCache;
  }

  public WriteCache getWriteCache() {
    return writeCache;
  }

  @Override
  public final long count(DatabaseSessionEmbedded session, final int iCollectionId) {
    return count(session, iCollectionId, false);
  }

  @Override
  public final long count(DatabaseSessionEmbedded session, final int collectionId,
      final boolean countTombstones) {
    try {
      if (collectionId == -1) {
        throw new StorageException(name,
            "Collection Id " + collectionId + " is invalid in database '" + name + "'");
      }

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();
      // COUNT PHYSICAL COLLECTION IF ANY
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = collections.get(collectionId);
        if (collection == null) {
          return 0;
        }

        if (countTombstones) {
          return collection.getEntries(atomicOperation);
        }

        return collection.getEntries(atomicOperation) - collection.getTombstonesCount();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final long getApproximateRecordsCount(final int collectionId) {
    try {
      if (collectionId < 0) {
        throw new StorageException(name,
            "Collection Id " + collectionId + " is invalid in database '" + name + "'");
      }

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        if (collectionId >= collections.size() || collections.get(collectionId) == null) {
          throw new StorageException(name,
              "Collection with id " + collectionId + " does not exist in database '"
                  + name + "'");
        }

        return collections.get(collectionId).getApproximateRecordsCount();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final long count(DatabaseSessionEmbedded session, final int[] iCollectionIds) {
    return count(session, iCollectionIds, false);
  }

  @Override
  public final void onException(final Throwable e) {

    LogManager.instance()
        .error(
            this,
            "Error in data flush background thread, for storage %s ,"
                + "please restart database and send full stack trace inside of bug report",
            e,
            name);

    if (status == STATUS.CLOSED) {
      return;
    }

    if (!(e instanceof InternalErrorException)) {
      setInError(e);
    }

    try {
      makeStorageDirty();
    } catch (IOException ioException) {
      // ignore
    }
  }

  private void setInError(final Throwable e) {
    error.set(e);
  }

  @Override
  public final long count(DatabaseSessionEmbedded session, final int[] iCollectionIds,
      final boolean countTombstones) {
    try {
      long tot = 0;

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        for (final var iCollectionId : iCollectionIds) {
          if (iCollectionId >= collections.size()) {
            throw new ConfigurationException(
                session.getDatabaseName(),
                "Collection id " + iCollectionId + " was not found in database '" + name + "'");
          }

          if (iCollectionId > -1) {
            final var c = collections.get(iCollectionId);
            if (c != null) {
              tot +=
                  c.getEntries(atomicOperation) - (countTombstones ? 0L : c.getTombstonesCount());
            }
          }
        }

        return tot;
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Nullable @Override
  public final RecordMetadata getRecordMetadata(DatabaseSessionEmbedded session,
      final RID rid) {
    try {
      if (rid.isNew()) {
        throw new StorageException(name,
            "Passed record with id " + rid + " is new and cannot be stored.");
      }

      stateLock.readLock().lock();
      try {

        final var collection = doGetAndCheckCollection(rid.getCollectionId());
        checkOpennessAndMigration();

        var atomicOperation = session.getActiveTransaction().getAtomicOperation();
        final var ppos =
            collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()),
                atomicOperation);
        if (ppos == null) {
          return null;
        }

        return new RecordMetadata(rid, ppos.recordVersion);
      } catch (final IOException ioe) {
        LogManager.instance()
            .error(this, "Retrieval of record  '" + rid + "' cause: " + ioe.getMessage(), ioe);
      } finally {
        stateLock.readLock().unlock();
      }

      return null;
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public Iterator<CollectionBrowsePage> browseCollection(
      final int collectionId,
      final boolean forward,
      AtomicOperation atomicOperation) {
    return browseCollection(collectionId, forward, () -> atomicOperation);
  }

  public Iterator<CollectionBrowsePage> browseCollection(
      final int collectionId,
      final boolean forward,
      Supplier<AtomicOperation> atomicOperationSupplier) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        if (collectionId == RID.COLLECTION_ID_INVALID) {
          // GET THE DEFAULT COLLECTION
          throw new StorageException(name, "Collection Id " + collectionId + " is invalid");
        }
        return new Iterator<>() {
          @Nullable private CollectionBrowsePage page;
          private long lastPos = forward ? -1 : Long.MAX_VALUE;

          @Override
          public boolean hasNext() {
            if (page == null) {
              page = nextPage(collectionId, lastPos, forward,
                  atomicOperationSupplier.get());
              if (page != null) {
                lastPos = page.getLastPosition();
              }
            }
            return page != null;
          }

          @Override
          public CollectionBrowsePage next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            final var curPage = page;
            page = null;
            return curPage;
          }
        };
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private CollectionBrowsePage nextPage(
      final int collectionId,
      final long lastPosition,
      final boolean forward,
      AtomicOperation atomicOperation) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(collectionId);
        return collection.nextPage(lastPosition, forward, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private StorageCollection doGetAndCheckCollection(final int collectionId) {
    checkCollectionSegmentIndexRange(collectionId);

    final var collection = collections.get(collectionId);
    if (collection == null) {
      throw new IllegalArgumentException("Collection " + collectionId + " is null");
    }
    return collection;
  }

  @Override
  public @Nonnull StorageReadResult readRecord(final RecordIdInternal rid,
      @Nonnull AtomicOperation atomicOperation) {
    try {
      return readRecordInternal(rid, atomicOperation);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public final AtomicOperationsManager getAtomicOperationsManager() {
    return atomicOperationsManager;
  }

  @Nonnull
  public WriteAheadLog getWALInstance() {
    return writeAheadLog;
  }

  public AtomicOperationIdGen getIdGen() {
    return idGen;
  }

  @Override
  public final Set<String> getCollectionNames() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return Collections.unmodifiableSet(collectionMap.keySet());
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final int getCollectionIdByName(final String collectionName) {
    try {
      if (collectionName == null) {
        throw new IllegalArgumentException("Collection name is null");
      }

      if (collectionName.isEmpty()) {
        throw new IllegalArgumentException("Collection name is empty");
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        // SEARCH IT BETWEEN PHYSICAL COLLECTIONS

        final var segment = collectionMap.get(collectionName.toLowerCase(Locale.ROOT));
        if (segment != null) {
          return segment.getId();
        }

        return -1;
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  /**
   * Scan the given transaction for new record and allocate a record id for them, the relative
   * record id is inserted inside the transaction for future use.
   *
   * @param clientTx the transaction of witch allocate rids
   */
  public void preallocateRids(final FrontendTransaction clientTx) {
    try {
      final Iterable<RecordOperation> entries = clientTx.getRecordOperationsInternal();
      final var collectionsToLock = new TreeMap<Integer, StorageCollection>();

      final Set<RecordOperation> newRecords = new TreeSet<>(COMMIT_RECORD_OPERATION_COMPARATOR);

      for (final var txEntry : entries) {

        if (txEntry.type == RecordOperation.CREATED) {
          newRecords.add(txEntry);
          final var collectionId = txEntry.getRecordId().getCollectionId();
          collectionsToLock.put(collectionId, doGetAndCheckCollection(collectionId));
        }
      }
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> {
              lockCollections(collectionsToLock, atomicOperation);

              var session = clientTx.getDatabaseSession();
              for (final var txEntry : newRecords) {
                final var rec = txEntry.record;
                if (!rec.getIdentity().isPersistent()) {
                  if (rec.isDirty()) {
                    // This allocate a position for a new record
                    final var rid = rec.getIdentity();
                    final var oldRID = rid.copy();
                    final var collection = doGetAndCheckCollection(rid.getCollectionId());
                    final var ppos =
                        collection.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    if (rid instanceof ChangeableRecordId changeableRecordId) {
                      changeableRecordId.setCollectionPosition(ppos.collectionPosition);
                    } else {
                      throw new DatabaseException(name,
                          "Provided record is not new and its position cannot be changed");
                    }

                    assert clientTx.assertIdentityChangedAfterCommit(oldRID, rid);
                  }
                } else {
                  // This allocate position starting from a valid rid, used in distributed for
                  // allocate the same position on other nodes
                  final var rid = rec.getIdentity();
                  final var collection =
                      (PaginatedCollection) doGetAndCheckCollection(rid.getCollectionId());
                  var recordStatus = collection.getRecordStatus(rid.getCollectionPosition(),
                      atomicOperation);
                  if (recordStatus == RECORD_STATUS.NOT_EXISTENT) {
                    var ppos =
                        collection.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    while (ppos.collectionPosition < rid.getCollectionPosition()) {
                      ppos =
                          collection.allocatePosition(
                              rec.getRecordType(), atomicOperation);
                    }
                    if (ppos.collectionPosition != rid.getCollectionPosition()) {
                      throw new ConcurrentCreateException(session.getDatabaseName(),
                          rid,
                          new RecordId(rid.getCollectionId(), ppos.collectionPosition));
                    }
                  } else if (recordStatus == RECORD_STATUS.PRESENT
                      || recordStatus == RECORD_STATUS.REMOVED) {
                    final var ppos =
                        collection.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    throw new ConcurrentCreateException(session.getDatabaseName(),
                        rid, new RecordId(rid.getCollectionId(), ppos.collectionPosition));
                  }
                }
              }
            });
      } catch (final IOException | RuntimeException ioe) {
        throw BaseException.wrapException(new StorageException(name, "Could not preallocate RIDs"),
            ioe, name);
      } finally {
        stateLock.readLock().unlock();
      }

    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  /**
   * Traditional commit that support already temporary rid and already assigned rids
   *
   * @param clientTx the transaction to commit
   */
  @Override
  public void commit(final FrontendTransactionImpl clientTx) {
    commit(clientTx, false);
  }

  /**
   * Commit a transaction where the rid where pre-allocated in a previous phase
   *
   * @param clientTx the pre-allocated transaction to commit
   * @return The list of operations applied by the transaction
   */
  @SuppressWarnings("UnusedReturnValue")
  public List<RecordOperation> commitPreAllocated(final FrontendTransactionImpl clientTx) {
    return commit(clientTx, true);
  }

  /**
   * The commit operation can be run in 3 different conditions, embedded commit, pre-allocated
   * commit, other node commit. <bold>Embedded commit</bold> is the basic commit where the operation
   * is run in embedded or server side, the transaction arrive with invalid rids that get allocated
   * and committed. <bold>pre-allocated commit</bold> is the commit that happen after an
   * preAllocateRids call is done, this is usually run by the coordinator of a tx in distributed.
   * <bold>other node commit</bold> is the commit that happen when a node execute a transaction of
   * another node where all the rids are already allocated in the other node.
   *
   * @param frontendTransaction the transaction to commit
   * @param allocated           true if the operation is pre-allocated commit
   * @return The list of operations applied by the transaction
   */
  @SuppressWarnings("IdentityHashMapUsage")
  protected List<RecordOperation> commit(
      final FrontendTransactionImpl frontendTransaction, final boolean allocated) {
    // XXX: At this moment, there are two implementations of the commit method. One for regular
    // client transactions and one for
    // implicit micro-transactions. The implementations are quite identical, but operate on slightly
    // different data. If you change
    // this method don't forget to change its counterpart:
    //
    //
    try {
      final var session = frontendTransaction.getDatabaseSession();
      final var indexOperations =
          getSortedIndexOperations(frontendTransaction);

      session.getMetadata().makeThreadLocalSchemaSnapshot();

      final var recordOperations = frontendTransaction.getRecordOperationsInternal();
      final var collectionsToLock = new TreeMap<Integer, StorageCollection>();
      final Map<RecordOperation, Integer> collectionOverrides = new IdentityHashMap<>(8);

      final Set<RecordOperation> newRecords = new TreeSet<>(COMMIT_RECORD_OPERATION_COMPARATOR);
      for (final var recordOperation : recordOperations) {
        var record = recordOperation.record;
        if (recordOperation.type == RecordOperation.CREATED
            || recordOperation.type == RecordOperation.UPDATED) {

          if (record.isUnloaded()) {
            throw new IllegalStateException(
                "Unloaded record " + record.getIdentity() + " cannot be committed");
          }

          if (record instanceof EntityImpl entity) {
            entity.validate();
          }
        }

        if (recordOperation.type == RecordOperation.UPDATED
            || recordOperation.type == RecordOperation.DELETED) {
          final var collectionId = recordOperation.record.getIdentity().getCollectionId();
          collectionsToLock.put(collectionId, doGetAndCheckCollection(collectionId));
        } else if (recordOperation.type == RecordOperation.CREATED) {
          newRecords.add(recordOperation);

          final RID rid = record.getIdentity();

          var collectionId = rid.getCollectionId();

          if (record.isDirty()
              && collectionId == RID.COLLECTION_ID_INVALID
              && record instanceof EntityImpl entity) {
            // TRY TO FIX COLLECTION ID TO THE DEFAULT COLLECTION ID DEFINED IN SCHEMA CLASS

            var cls = entity.getImmutableSchemaClass(session);
            if (cls != null) {
              collectionId = cls.getCollectionForNewInstance(entity);
              collectionOverrides.put(recordOperation, collectionId);
            }
          }
          collectionsToLock.put(collectionId, doGetAndCheckCollection(collectionId));
        }
      }

      final List<RecordOperation> result = new ArrayList<>(8);
      final var atomicOperation = frontendTransaction.getAtomicOperation();
      stateLock.readLock().lock();
      try {
        try {
          checkOpennessAndMigration();

          makeStorageDirty();

          Throwable error = null;
          startTxCommit(atomicOperation);
          try {
            lockCollections(collectionsToLock, atomicOperation);
            lockLinkBags(collectionsToLock, atomicOperation);
            lockIndexes(indexOperations, atomicOperation);

            final Map<RecordOperation, PhysicalPosition> positions = new IdentityHashMap<>(8);
            for (final var recordOperation : newRecords) {
              final var rec = recordOperation.record;

              if (allocated) {
                if (rec.getIdentity().isPersistent()) {
                  positions.put(
                      recordOperation,
                      new PhysicalPosition(rec.getIdentity().getCollectionPosition()));
                } else {
                  throw new StorageException(name,
                      "Impossible to commit a transaction with not valid rid in pre-allocated"
                          + " commit");
                }
              } else if (rec.isDirty() && !rec.getIdentity().isPersistent()) {
                final var rid = rec.getIdentity();
                final var oldRID = rid.copy();

                final var collectionOverride = collectionOverrides.get(recordOperation);
                final int collectionId =
                    Optional.ofNullable(collectionOverride).orElseGet(rid::getCollectionId);

                final var collection = doGetAndCheckCollection(collectionId);

                var physicalPosition =
                    collection.allocatePosition(
                        rec.getRecordType(),
                        atomicOperation);

                if (rid.getCollectionPosition() > -1) {
                  // CREATE EMPTY RECORDS UNTIL THE POSITION IS REACHED. THIS IS THE CASE WHEN A
                  // SERVER IS OUT OF SYNC
                  // BECAUSE A TRANSACTION HAS BEEN ROLLED BACK BEFORE TO SEND THE REMOTE CREATES.
                  // SO THE OWNER NODE DELETED
                  // RECORD HAVING A HIGHER COLLECTION POSITION
                  while (rid.getCollectionPosition() > physicalPosition.collectionPosition) {
                    physicalPosition =
                        collection.allocatePosition(
                            rec.getRecordType(),
                            atomicOperation);
                  }

                  if (rid.getCollectionPosition() != physicalPosition.collectionPosition) {
                    throw new ConcurrentCreateException(name,
                        rid, new RecordId(collection.getId(),
                            physicalPosition.collectionPosition));
                  }
                }
                positions.put(recordOperation, physicalPosition);

                if (rid instanceof ChangeableRecordId changeableRecordId) {
                  changeableRecordId.setCollectionAndPosition(collection.getId(),

                      physicalPosition.collectionPosition);
                } else {
                  throw new DatabaseException(name,
                      "Provided record is not new and its identity cannot be changed");
                }
                assert frontendTransaction.assertIdentityChangedAfterCommit(oldRID, rid);
              }
            }

            for (final var recordOperation : recordOperations) {
              commitEntry(
                  frontendTransaction,
                  atomicOperation,
                  recordOperation,
                  positions.get(recordOperation),
                  session.getSerializer());
              result.add(recordOperation);
            }

            //update of b-tree based link bags
            var recordSerializationContext = frontendTransaction.getRecordSerializationContext();
            recordSerializationContext.executeOperations(atomicOperation, this);

            commitIndexes(frontendTransaction.getDatabaseSession(), atomicOperation,
                indexOperations);

            // Persist accumulated index count deltas to BTree entry point pages.
            // Runs inside the same WAL atomic operation as commitIndexes — any
            // failure triggers rollback, ensuring persisted counts always match
            // index data (design decision D2).
            persistIndexCountDeltas(atomicOperation);
          } catch (final IOException | RuntimeException e) {
            error = e;
            if (e instanceof RuntimeException runtimeException) {
              throw runtimeException;
            } else {
              throw BaseException.wrapException(
                  new StorageException(name, "Error during transaction commit"), e, name);
            }
          } finally {
            if (error != null) {
              rollback(error, atomicOperation);
            } else {
              endTxCommit(atomicOperation);
              try {
                applyIndexCountDeltas(atomicOperation);
              } catch (final RuntimeException e) {
                // Counter application is a cache-only operation — its failure
                // must never mask a successful commit. Counters will be
                // recalibrated by load() on restart or buildInitialHistogram().
                LogManager.instance()
                    .warn(this,
                        "Index count delta application failed after successful"
                            + " commit",
                        e);
              }
              try {
                applyHistogramDeltas(atomicOperation);
              } catch (final RuntimeException e) {
                // Delta application is a cache-only operation — its failure
                // must never mask a successful commit. The cache will be
                // reconstructed from the .ixs page on next restart.
                LogManager.instance()
                    .warn(this,
                        "Histogram delta application failed after successful commit",
                        e);
              }
              try {
                cleanupSnapshotIndex();
              } catch (final RuntimeException e) {
                // Cleanup is best-effort — its failure must never mask a successful commit.
                // The commit is already durable (WAL flushed, pages applied). If cleanup
                // throws, stale snapshot entries simply accumulate until the next successful
                // cleanup pass.
                LogManager.instance()
                    .warn(this, "Snapshot index cleanup failed after successful commit", e);
              }
            }
          }
        } finally {
          atomicOperationsManager.ensureThatComponentsUnlocked(atomicOperation);
          session.getMetadata().clearThreadLocalSchemaSnapshot();
        }
      } finally {
        stateLock.readLock().unlock();
      }

      if (logger.isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "%d Committed transaction %d on database '%s' (result=%s)",
                logger, Thread.currentThread().threadId(),
                frontendTransaction.getId(),
                session.getDatabaseName(),
                result);
      }
      return result;
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void commitIndexes(DatabaseSessionEmbedded db, AtomicOperation atomicOperation,
      final Map<String, FrontendTransactionIndexChanges> indexesToCommit) {
    for (final var changes : indexesToCommit.values()) {
      var index = changes.getIndex();
      try {
        if (changes.cleared) {
          doClearIndex(atomicOperation, index.getIndexId());
        }

        for (final var changesPerKey : changes.changesPerKey.values()) {
          applyTxChanges(db, changesPerKey, index);
        }

        applyTxChanges(db, changes.nullKeyChanges, index);
      } catch (final InvalidIndexEngineIdException e) {
        throw BaseException.wrapException(new StorageException(name, "Error during index commit"),
            e, name);
      }
    }
  }

  private void applyTxChanges(DatabaseSessionEmbedded session,
      FrontendTransactionIndexChangesPerKey changes, Index index)
      throws InvalidIndexEngineIdException {
    assert !(changes.key instanceof RID orid) || orid.isPersistent();
    for (var op : index.interpretTxKeyChanges(changes)) {
      switch (op.getOperation()) {
        case PUT -> index.doPut(session, this, changes.key, op.getValue().getIdentity());
        case REMOVE -> {
          if (op.getValue() != null) {
            index.doRemove(session, this, changes.key, op.getValue().getIdentity());
          } else {
            index.doRemove(this, changes.key, session);
          }
        }
        case CLEAR -> {
          // SHOULD NEVER BE THE CASE HANDLE BY cleared FLAG
        }
      }
    }
  }

  /**
   * Persists accumulated index count deltas to the BTree entry point pages
   * within the current WAL atomic operation. Called between
   * {@code commitIndexes()} and the catch clause in the commit flow, so any
   * failure triggers the existing rollback path.
   *
   * <p>Mirrors the defensive checks of {@link #applyIndexCountDeltas}: skips
   * engines with out-of-bounds IDs, null entries, or non-{@link BTreeIndexEngine}
   * instances (e.g., if an engine was concurrently dropped, leaving a stale delta).
   */
  private void persistIndexCountDeltas(AtomicOperation atomicOperation) {
    var holder = atomicOperation.getIndexCountDeltas();
    if (holder == null) {
      return;
    }
    for (var entry : holder.getDeltas().int2ObjectEntrySet()) {
      int engineId = entry.getIntKey();
      var delta = entry.getValue();
      if (engineId >= 0 && engineId < indexEngines.size()) {
        var engine = indexEngines.get(engineId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          btreeEngine.persistCountDelta(
              atomicOperation, delta.getTotalDelta(), delta.getNullDelta());
        }
      }
    }
  }

  /**
   * Applies index entry count deltas accumulated during the transaction to
   * the engines' in-memory {@code AtomicLong} counters. Called after
   * {@code endTxCommit()} succeeds so that counters always reflect committed
   * state only. On rollback, the delta holder is discarded with the
   * operation.
   */
  private void applyIndexCountDeltas(AtomicOperation atomicOperation) {
    var holder = atomicOperation.getIndexCountDeltas();
    if (holder == null) {
      return;
    }
    for (var entry : holder.getDeltas().int2ObjectEntrySet()) {
      int engineId = entry.getIntKey();
      var delta = entry.getValue();
      // Engine may have been dropped concurrently — the commit is already
      // durable, so the delta for a removed engine is stale and safe to skip.
      // Safety: indexEngines is a plain ArrayList; concurrent structural
      // modification is prevented by the stateLock read lock held on the
      // commit path and the atomic operation serialization for index
      // creation/deletion.
      if (engineId >= 0 && engineId < indexEngines.size()) {
        var engine = indexEngines.get(engineId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          btreeEngine.addToApproximateEntriesCount(delta.getTotalDelta());
          btreeEngine.addToApproximateNullCount(delta.getNullDelta());
        }
      }
    }
  }

  /**
   * Applies histogram deltas accumulated during the transaction to the
   * in-memory CHM cache. Called after {@code endTxCommit()} succeeds so
   * that the cache always reflects committed state only.
   */
  private void applyHistogramDeltas(AtomicOperation atomicOperation) {
    var holder = atomicOperation.getHistogramDeltas();
    if (holder == null) {
      return;
    }
    for (var entry : holder.getDeltas().entrySet()) {
      var engineId = entry.getKey();
      var delta = entry.getValue();
      // Engine may have been dropped concurrently — the commit is already
      // durable, so the delta for a removed engine is stale and safe to skip.
      // Safety: indexEngines is a plain ArrayList; concurrent structural
      // modification is prevented by the stateLock read lock held on the
      // commit path and the atomic operation serialization for index
      // creation/deletion.
      if (engineId < indexEngines.size()) {
        var engine = indexEngines.get(engineId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          var mgr = btreeEngine.getHistogramManager();
          if (mgr != null) {
            mgr.applyDelta(delta);
          }
        }
      }
    }
  }

  public int loadIndexEngine(final String name) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var engine = indexEngineNameMap.get(name);
        if (engine == null) {
          return -1;
        }
        final var indexId = indexEngines.indexOf(engine);
        assert indexId == engine.getId();
        return generateIndexId(indexId, engine);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public int loadExternalIndexEngine(
      final IndexMetadata indexMetadata, final Map<String, String> engineProperties,
      AtomicOperation atomicOperation) {
    final var indexDefinition = indexMetadata.getIndexDefinition();
    try {
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        // this method introduced for binary compatibility only
        if (configuration.getBinaryFormatVersion(atomicOperation) > 15) {
          return -1;
        }
        if (indexEngineNameMap.containsKey(indexMetadata.getName())) {
          throw new IndexException(name,
              "Index with name " + indexMetadata.getName() + " already exists");
        }
        makeStorageDirty();

        final var valueSerializerId = StreamSerializerRID.INSTANCE.getId();

        final var keySerializer = determineKeySerializer(indexDefinition, atomicOperation);
        if (keySerializer == null) {
          throw new IndexException(name, "Can not determine key serializer");
        }
        final var keySize = determineKeySize(indexDefinition);
        final var keyTypes =
            Optional.of(indexDefinition).map(IndexDefinition::getTypes).orElse(null);
        var generatedId = indexEngines.size();
        final var engineData =
            new IndexEngineData(
                generatedId,
                indexMetadata,
                true,
                valueSerializerId,
                keySerializer.getId(),
                keyTypes,
                keySize,
                null,
                null,
                engineProperties);

        final var engine = Indexes.createIndexEngine(this, engineData);

        engine.load(engineData, atomicOperation);

        // Wire histogram manager for B-tree engines (migration path —
        // binary format version <= 15 has no .ixs files)
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          wireHistogramManagerOnLoad(btreeEngine, engineData, atomicOperation);
        }

        indexEngineNameMap.put(indexMetadata.getName(), engine);
        indexEngines.add(engine);
        ((CollectionBasedStorageConfiguration) configuration)
            .addIndexEngine(atomicOperation, indexMetadata.getName(), engineData);

        return generateIndexId(engineData.getIndexId(), engine);
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cannot add index engine " + indexMetadata.getName() + " in storage."),
            e, name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public int addIndexEngine(
      final IndexMetadata indexMetadata,
      final Map<String, String> engineProperties) {
    final var indexDefinition = indexMetadata.getIndexDefinition();

    try {
      if (indexDefinition == null) {
        throw new IndexException(name, "Index definition has to be provided");
      }
      final var keyTypes = indexDefinition.getTypes();
      if (keyTypes == null) {
        throw new IndexException(name, "Types of indexed keys have to be provided");
      }

      stateLock.writeLock().lock();
      try {
        checkOpennessAndMigration();

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            atomicOperation -> {
              final var keySerializer = determineKeySerializer(indexDefinition, atomicOperation);
              if (keySerializer == null) {
                throw new IndexException(name, "Can not determine key serializer");
              }

              final var keySize = determineKeySize(indexDefinition);
              if (indexEngineNameMap.containsKey(indexMetadata.getName())) {
                // OLD INDEX FILE ARE PRESENT: THIS IS THE CASE OF PARTIAL/BROKEN INDEX
                LogManager.instance()
                    .warn(
                        this,
                        "Index with name '%s' already exists, removing it and re-create the index",
                        indexMetadata.getName());
                final var engine = indexEngineNameMap.remove(indexMetadata.getName());
                if (engine != null) {
                  indexEngines.set(engine.getId(), null);

                  engine.delete(atomicOperation);
                  ((CollectionBasedStorageConfiguration) configuration)
                      .deleteIndexEngine(atomicOperation, indexMetadata.getName());
                }
              }
              final var valueSerializerId =
                  StreamSerializerRID.INSTANCE.getId();
              final var ctxCfg = configuration.getContextConfiguration();
              final var cfgEncryptionKey =
                  ctxCfg.getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY);
              var genenrateId = indexEngines.size();
              final var engineData =
                  new IndexEngineData(
                      genenrateId,
                      indexMetadata,
                      true,
                      valueSerializerId,
                      keySerializer.getId(),
                      keyTypes,
                      keySize,
                      null,
                      cfgEncryptionKey,
                      engineProperties);

              final var engine = Indexes.createIndexEngine(this, engineData);

              engine.create(atomicOperation, engineData);

              // Create and wire histogram manager for B-tree engines
              if (engine instanceof BTreeIndexEngine btreeEngine) {
                var mgr = createAndWireHistogramManager(
                    btreeEngine, engineData, atomicOperation);
                mgr.createStatsFile(atomicOperation);
              }

              indexEngineNameMap.put(indexMetadata.getName(), engine);
              indexEngines.add(engine);

              ((CollectionBasedStorageConfiguration) configuration)
                  .addIndexEngine(atomicOperation, indexMetadata.getName(), engineData);

              return generateIndexId(engineData.getIndexId(), engine);
            });
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cannot add index engine " + indexMetadata.getName() + " in storage."),
            e, name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  public BinarySerializer<?> resolveObjectSerializer(final byte serializerId) {
    return componentsFactory.binarySerializerFactory.getObjectSerializer(serializerId);
  }

  /**
   * Wires a histogram manager into a B-tree engine during database open.
   * If the .ixs file exists, opens it; otherwise creates a new one with
   * initial counters from the B-tree (migration path).
   */
  private void wireHistogramManagerOnLoad(
      BTreeIndexEngine btreeEngine,
      IndexEngineData engineData,
      AtomicOperation atomicOperation) {
    try {
      var mgr = createAndWireHistogramManager(
          btreeEngine, engineData, atomicOperation);
      if (mgr.statsFileExists(atomicOperation)) {
        // Normal path: .ixs file exists, load from it
        mgr.openStatsFile(atomicOperation);
      } else {
        // Migration path: no .ixs file, create with counters from B-tree
        long totalCount = btreeEngine.getTotalCount(atomicOperation);
        long nullCount = btreeEngine.getNullCount(atomicOperation);
        // For single-value: distinctCount == non-null count (each key is unique)
        // For multi-value: distinctCount == non-null count (overestimates
        // because the same key can map to multiple RIDs; corrected on first
        // rebalance when exact NDV is computed from the key scan)
        long distinctCount = totalCount - nullCount;
        mgr.createStatsFileWithCounters(
            atomicOperation, totalCount, distinctCount, nullCount);
      }
    } catch (IOException e) {
      // Histogram manager failure must not prevent database open.
      // Clean up any partially-populated cache entry and null out the
      // partially-wired manager to avoid broken state (fileId == -1, no
      // snapshot in cache) causing issues on onPut/onRemove.
      histogramSnapshotCache.remove(engineData.getIndexId());
      btreeEngine.setHistogramManager(null);
      LogManager.instance().warn(
          this,
          "Failed to wire histogram manager for index '%s': %s",
          engineData.getName(), e.getMessage());
    }
  }

  /**
   * Creates an {@link IndexHistogramManager}, wires it into the given
   * B-tree engine, and returns it. The caller is responsible for calling
   * {@code createStatsFile()}, {@code openStatsFile()}, or
   * {@code createStatsFileWithCounters()} after this method returns.
   *
   * @param engine          the B-tree engine to wire the manager into
   * @param engineData      engine metadata for serializer resolution
   * @param atomicOperation current atomic operation (for binary format check)
   */
  private IndexHistogramManager createAndWireHistogramManager(
      BTreeIndexEngine engine,
      IndexEngineData engineData,
      AtomicOperation atomicOperation) {
    boolean isSingleValue = engine instanceof BTreeSingleValueIndexEngine;

    // Determine the histogram key serializer: for composite indexes,
    // boundaries are leading-field values, so we need the leading field's
    // type serializer rather than the composite key serializer.
    var keyTypes = engineData.getKeyTypes();
    BinarySerializer<?> histogramKeySerializer;
    byte histogramSerializerId;
    if (keyTypes != null && keyTypes.length > 1) {
      var leadingType = keyTypes[0];
      if (leadingType == PropertyTypeInternal.STRING
          && configuration.getBinaryFormatVersion(atomicOperation) >= 13) {
        histogramKeySerializer = UTF8Serializer.INSTANCE;
      } else {
        histogramKeySerializer =
            componentsFactory.binarySerializerFactory
                .getObjectSerializer(leadingType);
      }
      histogramSerializerId = histogramKeySerializer.getId();
    } else {
      histogramSerializerId = engineData.getKeySerializedId();
      histogramKeySerializer =
          resolveObjectSerializer(histogramSerializerId);
    }

    var mgr = new IndexHistogramManager(
        this,
        engineData.getName(),
        engineData.getIndexId(),
        isSingleValue,
        histogramSnapshotCache,
        histogramKeySerializer,
        componentsFactory.binarySerializerFactory,
        histogramSerializerId);

    int keyFieldCount = (keyTypes != null) ? keyTypes.length : 1;
    mgr.setKeyFieldCount(keyFieldCount);

    // Wire the sorted key stream function for background rebalance.
    // The atomic operation is created by the caller (on the rebalance thread)
    // so that snapshot visibility and tsMin tracking are thread-local correct.
    if (isSingleValue) {
      mgr.setKeyStreamSupplier(
          atomicOp -> ((BTreeSingleValueIndexEngine) engine).keyStream(atomicOp));
    } else {
      mgr.setKeyStreamSupplier(
          atomicOp -> ((BTreeMultiValueIndexEngine) engine).keyStream(atomicOp));
    }
    engine.setHistogramManager(mgr);

    // Propagate the rebalance semaphore to limit concurrent rebalance tasks.
    mgr.setRebalanceSemaphore(histogramRebalanceSemaphore);

    // Propagate the executor if already available (index created after
    // database open). Before database open, histogramExecutor is null
    // and setHistogramExecutor() will wire it for all engines.
    var exec = histogramExecutor;
    if (exec != null) {
      mgr.setBackgroundExecutor(exec);
    }

    return mgr;
  }

  private static int generateIndexId(final int internalId, final BaseIndexEngine indexEngine) {
    return indexEngine.getEngineAPIVersion() << ((IntegerSerializer.INT_SIZE << 3) - 5)
        | internalId;
  }

  private static int extractInternalId(final int externalId) {
    if (externalId < 0) {
      throw new IllegalStateException("Index id has to be positive");
    }

    return externalId & 0x7_FF_FF_FF;
  }

  public static int extractEngineAPIVersion(final int externalId) {
    return externalId >>> ((IntegerSerializer.INT_SIZE << 3) - 5);
  }

  private static int determineKeySize(final IndexDefinition indexDefinition) {
    if (indexDefinition == null) {
      return 1;
    } else {
      return indexDefinition.getTypes().length;
    }
  }

  private BinarySerializer<?> determineKeySerializer(final IndexDefinition indexDefinition,
      AtomicOperation atomicOperation) {
    if (indexDefinition == null) {
      throw new StorageException(name, "Index definition has to be provided");
    }

    final var keyTypes = indexDefinition.getTypes();
    if (keyTypes == null || keyTypes.length == 0) {
      throw new StorageException(name, "Types of index keys has to be defined");
    }
    if (keyTypes.length < indexDefinition.getProperties().size()) {
      throw new StorageException(name,
          "Types are provided only for "
              + keyTypes.length
              + " fields. But index definition has "
              + indexDefinition.getProperties().size()
              + " fields.");
    }

    final BinarySerializer<?> keySerializer;
    if (indexDefinition.getTypes().length > 1) {
      keySerializer = CompositeKeySerializer.INSTANCE;
    } else {
      final var keyType = indexDefinition.getTypes()[0];

      if (keyType == PropertyTypeInternal.STRING
          && configuration.getBinaryFormatVersion(atomicOperation) >= 13) {
        return UTF8Serializer.INSTANCE;
      }

      final var currentStorageComponentsFactory = componentsFactory;
      if (currentStorageComponentsFactory != null) {
        keySerializer =
            currentStorageComponentsFactory.binarySerializerFactory.getObjectSerializer(keyType);
      } else {
        throw new IllegalStateException(
            "Cannot load binary serializer, storage is not properly initialized");
      }
    }

    return keySerializer;
  }

  public void deleteIndexEngine(int indexId)
      throws InvalidIndexEngineIdException {
    final var internalIndexId = extractInternalId(indexId);

    try {
      stateLock.writeLock().lock();
      try {
        checkOpennessAndMigration();
        checkIndexId(internalIndexId);

        makeStorageDirty();

        final var engine = indexEngines.get(internalIndexId);
        assert internalIndexId == engine.getId();

        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> {
              engine.delete(atomicOperation);
              ((CollectionBasedStorageConfiguration) configuration)
                  .deleteIndexEngine(atomicOperation, engine.getName());
            });

        // Update in-memory maps only AFTER the atomic operation commits
        // successfully. If the atomic operation rolls back, the maps remain
        // consistent so that addIndexEngine()'s "OLD INDEX FILE" recovery
        // branch can find and clean up the stale engine.
        indexEngines.set(internalIndexId, null);
        indexEngineNameMap.remove(engine.getName());

      } catch (final IOException e) {
        throw BaseException.wrapException(new StorageException(name, "Error on index deletion"), e,
            name);
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void checkIndexId(final int indexId) throws InvalidIndexEngineIdException {
    if (indexId < 0 || indexId >= indexEngines.size() || indexEngines.get(indexId) == null) {
      throw new InvalidIndexEngineIdException(
          "Engine with id " + indexId + " is not registered inside of storage");
    }
  }

  public boolean removeKeyFromIndex(final int indexId, final Object key,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    final var internalIndexId = extractInternalId(indexId);
    try {
      return removeKeyFromIndexInternal(atomicOperation, internalIndexId, key);
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private boolean removeKeyFromIndexInternal(
      final AtomicOperation atomicOperation, final int indexId, final Object key)
      throws InvalidIndexEngineIdException {
    try {
      checkIndexId(indexId);

      final var engine = indexEngines.get(indexId);
      if (engine.getEngineAPIVersion() == IndexEngine.VERSION) {
        return ((IndexEngine) engine).remove(this, atomicOperation, key);
      } else {
        final var v1IndexEngine = (V1IndexEngine) engine;
        if (!v1IndexEngine.isMultiValue()) {
          return ((SingleValueIndexEngine) engine).remove(atomicOperation, key);
        } else {
          throw new StorageException(name,
              "To remove entry from multi-value index not only key but value also should be"
                  + " provided");
        }
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error during removal of entry with key " + key + " from index "),
          e, name);
    }
  }

  public void clearIndex(final int indexId) {
    try {
      final var internalIndexId = extractInternalId(indexId);
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();

        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> doClearIndex(atomicOperation, internalIndexId));
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void doClearIndex(final AtomicOperation atomicOperation,
      final int indexId)
      throws InvalidIndexEngineIdException {
    try {
      checkIndexId(indexId);

      final var engine = indexEngines.get(indexId);
      assert indexId == engine.getId();

      engine.clear(this, atomicOperation);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during clearing of index"),
          e, name);
    }
  }

  public Stream<RID> getIndexValues(int indexId, final Object key, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doGetIndexValues(indexId, key, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RID> doGetIndexValues(final int indexId, final Object key,
      AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return ((V1IndexEngine) engine).get(key, atomicOperation);
  }

  public BaseIndexEngine getIndexEngine(int indexId) throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      checkIndexId(indexId);

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var engine = indexEngines.get(indexId);
        assert indexId == engine.getId();
        return engine;
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public <T> void callIndexEngine(
      final boolean readOperation, int indexId, final IndexEngineCallback<T> callback)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        if (readOperation) {
          makeStorageDirty();
        }

        doCallIndexEngine(indexId, callback);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private <T> void doCallIndexEngine(final int indexId, final IndexEngineCallback<T> callback)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);

    callback.callEngine(engine);
  }

  public void putRidIndexEntry(int indexId, final Object key, final RID value,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    final var internalIndexId = extractInternalId(indexId);

    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    try {
      putRidIndexEntryInternal(atomicOperation, internalIndexId, key, value);
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void putRidIndexEntryInternal(
      final AtomicOperation atomicOperation, final int indexId, final Object key,
      final RID value)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert engine.getId() == indexId;

    ((V1IndexEngine) engine).put(atomicOperation, key, value);
  }

  public boolean removeRidIndexEntry(int indexId, final Object key, final RID value,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    final var internalIndexId = extractInternalId(indexId);

    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    try {
      return removeRidIndexEntryInternal(atomicOperation, internalIndexId, key, value);
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private boolean removeRidIndexEntryInternal(
      final AtomicOperation atomicOperation, final int indexId, final Object key,
      final RID value)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert engine.getId() == indexId;

    return ((MultiValueIndexEngine) engine).remove(atomicOperation, key, value);
  }

  /**
   * Puts the given value under the given key into this storage for the index with the given index
   * id. Validates the operation using the provided validator.
   *
   * @param indexId   the index id of the index to put the value into.
   * @param key       the key to put the value under.
   * @param value     the value to put.
   * @param validator the operation validator.
   * @return {@code true} if a new key was inserted, {@code false} if an existing key was updated
   *     in-place or the validator rejected the operation (IGNORE).
   * @see IndexEngineValidator#validate(Object, Object, Object)
   */
  @SuppressWarnings("UnusedReturnValue")
  public boolean validatedPutIndexValue(
      final int indexId,
      final Object key,
      final RID value,
      final IndexEngineValidator<Object, RID> validator, @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    final var internalIndexId = extractInternalId(indexId);

    try {
      return doValidatedPutIndexValue(atomicOperation, internalIndexId, key, value, validator);
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private boolean doValidatedPutIndexValue(
      AtomicOperation atomicOperation,
      final int indexId,
      final Object key,
      final RID value,
      final IndexEngineValidator<Object, RID> validator)
      throws InvalidIndexEngineIdException {
    try {
      checkIndexId(indexId);

      final var engine = indexEngines.get(indexId);
      assert indexId == engine.getId();

      if (engine instanceof IndexEngine indexEngine) {
        return indexEngine.validatedPut(atomicOperation, key, value, validator);
      }

      if (engine instanceof SingleValueIndexEngine singleValueIndexEngine) {
        return singleValueIndexEngine
            .validatedPut(atomicOperation, key, value.getIdentity(), validator);
      }

      throw new IllegalStateException(
          "Invalid type of index engine " + engine.getClass().getName());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Cannot put key " + key + " value " + value + " entry to the index"),
          e, name);
    }
  }

  public Stream<RawPair<Object, RID>> iterateIndexEntriesBetween(
      int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesBetween(
            indexId, rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer,
            atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RawPair<Object, RID>> doIterateIndexEntriesBetween(
      final int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesBetween(
        rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer, atomicOperation);
  }

  public Stream<RawPair<Object, RID>> iterateIndexEntriesMajor(
      int indexId,
      final Object fromKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesMajor(indexId, fromKey, isInclusive, ascSortOrder,
            transformer, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RawPair<Object, RID>> doIterateIndexEntriesMajor(
      final int indexId,
      final Object fromKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder, transformer,
        atomicOperation);
  }

  public Stream<RawPair<Object, RID>> iterateIndexEntriesMinor(
      int indexId,
      final Object toKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doIterateIndexEntriesMinor(indexId, toKey, isInclusive, ascSortOrder, transformer,
            atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RawPair<Object, RID>> doIterateIndexEntriesMinor(
      final int indexId,
      final Object toKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMinor(toKey, isInclusive, ascSortOrder, transformer,
        atomicOperation);
  }

  public Stream<RawPair<Object, RID>> getIndexStream(
      int indexId, final IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        return doGetIndexStream(indexId, valuesTransformer, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RawPair<Object, RID>> doGetIndexStream(
      final int indexId, final IndexEngineValuesTransformer valuesTransformer,
      AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.stream(valuesTransformer, atomicOperation);
  }

  public Stream<RawPair<Object, RID>> getIndexDescStream(
      int indexId, final IndexEngineValuesTransformer valuesTransformer,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doGetIndexDescStream(indexId, valuesTransformer, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<RawPair<Object, RID>> doGetIndexDescStream(
      final int indexId, final IndexEngineValuesTransformer valuesTransformer,
      AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.descStream(valuesTransformer, atomicOperation);
  }

  public Stream<Object> getIndexKeyStream(int indexId, @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doGetIndexKeyStream(indexId, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Stream<Object> doGetIndexKeyStream(final int indexId, AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.keyStream(atomicOperation);
  }

  public long getIndexSize(int indexId, final IndexEngineValuesTransformer transformer,
      @Nonnull AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doGetIndexSize(indexId, transformer, atomicOperation);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final InvalidIndexEngineIdException ie) {
      throw logAndPrepareForRethrow(ie);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private long doGetIndexSize(final int indexId, final IndexEngineValuesTransformer transformer,
      AtomicOperation atomicOperation)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.size(this, transformer, atomicOperation);
  }

  private void rollback(final Throwable error, @Nonnull AtomicOperation atomicOperation)
      throws IOException {
    atomicOperationsManager.endAtomicOperation(atomicOperation, error);
  }

  public void moveToErrorStateIfNeeded(final Throwable error) {
    if (error != null
        && !((error instanceof HighLevelException)
            || (error instanceof NeedRetryException)
            || (error instanceof InternalErrorException))) {
      setInError(error);
    }
  }

  @Override
  public final void synch() {
    try {
      stateLock.readLock().lock();
      try {
        // Flush dirty histogram stats before freezing — see
        // flushDirtyHistograms() Javadoc for deadlock details.
        if (!isInError()) {
          flushDirtyHistograms();
        }

        doSynch();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  /**
   * Core sync logic: freeze operations, flush engines and WAL, unfreeze.
   * Extracted so that {@link #freeze} can call it after flushing histograms
   * itself — calling {@link #synch()} from {@code freeze()} would deadlock
   * because the frozen {@code OperationsFreezer} would block the histogram
   * flush's {@code executeInsideAtomicOperation()} call.
   */
  private void doSynch() {
    final var synchStartedAt = System.nanoTime();
    final var lockId = atomicOperationsManager.freezeWriteOperations(null);
    try {
      checkOpennessAndMigration();

      if (!isInError()) {
        for (final var indexEngine : indexEngines) {
          try {
            if (indexEngine != null) {
              indexEngine.flush();
            }
          } catch (final Throwable t) {
            LogManager.instance()
                .error(
                    this,
                    "Error while flushing index via index engine of class %s.",
                    t,
                    indexEngine.getClass().getSimpleName());
          }
        }

        flushAllData();

      } else {
        LogManager.instance()
            .error(
                this,
                "Sync can not be performed because of internal error in storage %s",
                null,
                this.name);
      }

    } finally {
      atomicOperationsManager.unfreezeWriteOperations(lockId);
      synchDuration.setNanos(System.nanoTime() - synchStartedAt);
    }
  }

  @Nullable @Override
  public final String getPhysicalCollectionNameById(final int iCollectionId) {
    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        if (iCollectionId < 0 || iCollectionId >= collections.size()) {
          return null;
        }

        return collections.get(iCollectionId) != null ? collections.get(iCollectionId).getName()
            : null;
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public String getCollectionName(DatabaseSessionEmbedded database, int collectionId) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      if (collectionId == RID.COLLECTION_ID_INVALID) {
        throw new StorageException(name, "Invalid collection id was provided: " + collectionId);
      }

      return doGetAndCheckCollection(collectionId).getName();

    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public final int getCollections() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return collectionMap.size();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final Set<StorageCollection> getCollectionInstances() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final Set<StorageCollection> result = new HashSet<>(1024);

        // ADD ALL THE COLLECTIONS
        for (final var c : collections) {
          if (c != null) {
            result.add(c);
          }
        }

        return result;

      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final void freeze(DatabaseSessionEmbedded db, final boolean throwException) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        // Flush dirty histogram stats before freezing — see
        // flushDirtyHistograms() Javadoc for deadlock details.
        if (!isInError()) {
          flushDirtyHistograms();
        }

        if (throwException) {
          atomicOperationsManager.freezeWriteOperations(
              () -> new ModificationOperationProhibitedException(name,
                  "Modification requests are prohibited"));
        } else {
          atomicOperationsManager.freezeWriteOperations(null);
        }

        final List<FreezableStorageComponent> frozenIndexes = new ArrayList<>(indexEngines.size());
        try {
          for (final var indexEngine : indexEngines) {
            if (indexEngine instanceof FreezableStorageComponent freezable) {
              freezable.freeze(db, false);
              frozenIndexes.add(freezable);
            }
          }
        } catch (final Exception e) {
          // RELEASE ALL THE FROZEN INDEXES
          for (final var indexEngine : frozenIndexes) {
            indexEngine.release(db);
          }

          throw BaseException.wrapException(
              new StorageException(name, "Error on freeze of storage '" + name + "'"), e, name);
        }

        // Use doSynch() instead of synch() — histograms were already
        // flushed above and the operations are already frozen by us.
        doSynch();
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final void release(DatabaseSessionEmbedded db) {
    try {
      for (final var indexEngine : indexEngines) {
        if (indexEngine instanceof FreezableStorageComponent freezable) {
          freezable.release(db);
        }
      }

      atomicOperationsManager.unfreezeWriteOperations(-1);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final boolean isRemote() {
    return false;
  }

  public boolean wereDataRestoredAfterOpen() {
    return wereDataRestoredAfterOpen;
  }

  public boolean wereNonTxOperationsPerformedInPreviousOpen() {
    return wereNonTxOperationsPerformedInPreviousOpen;
  }

  @Override
  public final void reload(DatabaseSessionEmbedded database) {
    try {
      close(database);
      open(new ContextConfiguration());
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  /** {@inheritDoc} */
  @Override
  public final void pageIsBroken(final String fileName, final long pageIndex) {
    LogManager.instance()
        .error(
            this,
            "In storage %s file with name '%s' has broken page under the index %d",
            null,
            name,
            fileName,
            pageIndex);

    if (status == STATUS.CLOSED) {
      return;
    }

    setInError(new StorageException(name, "Page " + pageIndex + " is broken in file " + fileName));

    try {
      makeStorageDirty();
    } catch (final IOException e) {
      // ignore
    }
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public final void requestCheckpoint() {
    try {
      if (!walVacuumInProgress.get() && walVacuumInProgress.compareAndSet(false, true)) {
        YouTrackDBEnginesManager.instance().getFuzzyCheckpointExecutor()
            .submit(new WALVacuum(this));
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  @Override
  public final PhysicalPosition[] higherPhysicalPositions(
      DatabaseSessionEmbedded session, final int currentCollectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentCollectionId == -1) {
        return new PhysicalPosition[0];
      }

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(currentCollectionId);
        return collection.higherPositions(physicalPosition, limit, atomicOperation);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + currentCollectionId + " is invalid in storage '" + name + '\''),
            ioe, name);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final PhysicalPosition[] ceilingPhysicalPositions(
      DatabaseSessionEmbedded session, final int collectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (collectionId == -1) {
        return new PhysicalPosition[0];
      }

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(collectionId);
        return collection.ceilingPositions(physicalPosition, limit, atomicOperation);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + collectionId + " is invalid in storage '" + name + '\''),
            ioe, name);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final PhysicalPosition[] lowerPhysicalPositions(
      DatabaseSessionEmbedded session, final int currentCollectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentCollectionId == -1) {
        return new PhysicalPosition[0];
      }

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(currentCollectionId);

        return collection.lowerPositions(physicalPosition, limit, atomicOperation);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + currentCollectionId + " is invalid in storage '" + name + '\''),
            ioe, name);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final PhysicalPosition[] floorPhysicalPositions(
      DatabaseSessionEmbedded session, final int collectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (collectionId == -1) {
        return new PhysicalPosition[0];
      }

      var transaction = session.getActiveTransaction();
      var atomicOperation = transaction.getAtomicOperation();

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();
        final var collection = doGetAndCheckCollection(collectionId);

        return collection.floorPositions(physicalPosition, limit, atomicOperation);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + collectionId + " is invalid in storage '" + name + '\''),
            ioe, name);
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final RecordConflictStrategy getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  @Override
  public final void setConflictStrategy(final RecordConflictStrategy conflictResolver) {
    Objects.requireNonNull(conflictResolver);
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> doSetConflictStrategy(conflictResolver, atomicOperation));
    } catch (final Exception e) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Exception during setting of conflict strategy "
                  + conflictResolver.getName()
                  + " for storage "
                  + name),
          e, name);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void doSetConflictStrategy(
      RecordConflictStrategy conflictResolver, AtomicOperation atomicOperation) {

    if (recordConflictStrategy == null
        || !recordConflictStrategy.getName().equals(conflictResolver.getName())) {

      this.recordConflictStrategy = conflictResolver;
      ((CollectionBasedStorageConfiguration) configuration)
          .setConflictStrategy(atomicOperation, conflictResolver.getName());
    }
  }

  @SuppressWarnings("unused")
  protected abstract LogSequenceNumber copyWALToBackup(
      ZipOutputStream zipOutputStream, long startSegment) throws IOException;

  @Nullable @SuppressWarnings("unused")
  public StorageRecoverListener getRecoverListener() {
    return recoverListener;
  }

  @SuppressWarnings("unused")
  public void unregisterRecoverListener(final StorageRecoverListener recoverListener) {
    if (this.recoverListener == recoverListener) {
      this.recoverListener = null;
    }
  }

  @SuppressWarnings("unused")
  protected abstract File createWalTempDirectory();

  @SuppressWarnings("unused")
  protected abstract WriteAheadLog createWalFromIBUFiles(
      File directory,
      final ContextConfiguration contextConfiguration,
      final Locale locale,
      byte[] iv)
      throws IOException;

  /**
   * Checks if the storage is open. If it's closed an exception is raised.
   */
  protected final void checkOpennessAndMigration() {
    final var status = this.status;

    if (status == STATUS.MIGRATION) {
      throw new StorageException(name,
          "Storage data are under migration procedure, please wait till data will be migrated.");
    }

    if (status != STATUS.OPEN) {
      throw new StorageException(name, "Storage " + name + " is not opened.");
    }
  }

  protected boolean isInError() {
    return this.error.get() != null;
  }

  public void checkErrorState() {
    if (this.error.get() != null) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Internal error happened in storage "
                  + name
                  + " please restart the server or re-open the storage to undergo the restore"
                  + " process and fix the error."),
          this.error.get(), name);
    }
  }

  public final void makeFuzzyCheckpoint() {
    // check every 1 ms.
    while (true) {
      try {
        if (stateLock.readLock().tryLock(1, TimeUnit.MILLISECONDS)) {
          break;
        }
      } catch (java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("Fuzzy check point was interrupted"), e, name);
      }

      // Bail out if the storage is no longer in an operational state.
      // The original condition used || which is always true (tautology) —
      // it should be && to express "status is neither OPEN nor MIGRATION".
      if (status != STATUS.OPEN && status != STATUS.MIGRATION) {
        return;
      }
    }

    try {

      if (status != STATUS.OPEN && status != STATUS.MIGRATION) {
        return;
      }

      // Flush dirty histogram statistics to .ixs pages (Section 3.2).
      // Runs before the WAL checkpoint so that the flushed pages are
      // included in writeCache.syncDataFiles().
      flushDirtyHistograms();

      var beginLSN = writeAheadLog.begin();
      var endLSN = writeAheadLog.end();

      final var minLSNSegment = writeCache.getMinimalNotFlushedSegment();

      long fuzzySegment;

      if (minLSNSegment != null) {
        fuzzySegment = minLSNSegment;
      } else {
        if (endLSN == null) {
          return;
        }

        fuzzySegment = endLSN.getSegment();
      }

      atomicOperationsTable.compactTable();
      final var minAtomicOperationSegment =
          atomicOperationsTable.getSegmentEarliestNotPersistedOperation();
      if (minAtomicOperationSegment >= 0 && fuzzySegment > minAtomicOperationSegment) {
        fuzzySegment = minAtomicOperationSegment;
      }

      LogManager.instance()
          .debug(
              this,
              "Before fuzzy checkpoint: min LSN segment is %s, "
                  + "WAL begin is %s, WAL end is %s fuzzy segment is %d",
              logger, minLSNSegment,
              beginLSN,
              endLSN,
              fuzzySegment);

      if (fuzzySegment > beginLSN.getSegment() && beginLSN.getSegment() < endLSN.getSegment()) {
        LogManager.instance().debug(this, "Making fuzzy checkpoint", logger);
        writeCache.syncDataFiles(fuzzySegment);

        beginLSN = writeAheadLog.begin();
        endLSN = writeAheadLog.end();

        LogManager.instance()
            .debug(this, "After fuzzy checkpoint: WAL begin is %s WAL end is %s", logger, beginLSN,
                endLSN);
      } else {
        LogManager.instance().debug(this, "No reason to make fuzzy checkpoint", logger);
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(new YTIOException("Error during fuzzy checkpoint"), ioe,
          name);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void deleteTreeLinkBag(final BTreeBasedLinkBag ridBag,
      @Nonnull AtomicOperation atomicOperation) {
    try {
      checkOpennessAndMigration();

      doDeleteTreeLinkBag(ridBag, atomicOperation);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void doDeleteTreeLinkBag(BTreeBasedLinkBag ridBag, AtomicOperation atomicOperation) {
    final var collectionPointer = ridBag.getCollectionPointer();
    checkOpennessAndMigration();

    try {
      makeStorageDirty();
      linkCollectionsBTreeManager.delete(atomicOperation, collectionPointer, name);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error during deletion of rid bag", e);
      throw BaseException.wrapException(
          new StorageException(name, "Error during deletion of ridbag"),
          e, name);
    }

    ridBag.confirmDelete();
  }

  /**
   * Iterates all B-tree index engines and flushes dirty histogram state
   * to their .ixs pages. Called during fuzzy checkpoint, synch, close,
   * and recovery.
   *
   * <p><b>Important:</b> this method must NOT be called inside a frozen
   * {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local
   * .paginated.atomicoperations.operationsfreezer.OperationsFreezer}
   * scope. Each engine's flush creates its own AtomicOperation via
   * {@code executeInsideAtomicOperation()}, which would deadlock if the
   * freezer is already frozen.
   *
   * <p>Failures are logged but never propagated — histogram persistence is
   * best-effort and must not block checkpoint or shutdown.
   */
  private void flushDirtyHistograms() {
    for (var engine : indexEngines) {
      if (engine instanceof BTreeIndexEngine btreeEngine) {
        var mgr = btreeEngine.getHistogramManager();
        if (mgr != null) {
          try {
            mgr.flushIfDirty();
          } catch (Exception e) {
            LogManager.instance().error(this,
                "Failed to flush histogram stats for engine %d (%s)"
                    + " — histogram data may be stale after restart",
                e, btreeEngine.getId(), mgr.getName());
          }
        }
      }
    }
  }

  /**
   * Waits for any in-progress background histogram rebalances to finish
   * and permanently blocks future ones. Must be called before clearing
   * index engines and deleting/closing the cache to prevent background
   * rebalance threads from holding page references on deleted pages.
   *
   * <p>Uses {@link IndexHistogramManager#closeStatsFile()} which
   * internally calls {@code waitForAndBlockRebalance()}, flushes dirty
   * data, removes the cache entry, and resets the fileId.
   */
  private void cancelHistogramRebalances() {
    for (var engine : indexEngines) {
      if (engine instanceof BTreeIndexEngine btreeEngine) {
        var mgr = btreeEngine.getHistogramManager();
        if (mgr != null) {
          try {
            mgr.closeStatsFile();
          } catch (Exception e) {
            LogManager.instance().error(this,
                "Failed to close histogram stats for engine %d (%s)",
                e, btreeEngine.getId(), mgr.getName());
          }
        }
      }
    }
  }

  /**
   * Propagates a general-purpose executor to all histogram managers for
   * background rebalance work. Called after the database is fully open.
   * <p>
   * <b>Important:</b> The executor must NOT be the {@code ioExecutor} used
   * by {@code AsynchronousFileChannel} for I/O completions. Running blocking
   * page reads on the ioExecutor deadlocks because the completion callbacks
   * need the same thread pool.
   * <p>
   * Also triggers a proactive rebalance check on each manager — if mutations
   * accumulated before a crash exceeded the threshold, a background rebalance
   * is scheduled immediately.
   *
   * @param executor the general-purpose executor (must NOT be the ioExecutor
   *                 used by AsynchronousFileChannel — see class Javadoc)
   */
  public void setHistogramExecutor(ExecutorService executor) {
    this.histogramExecutor = executor;
    stateLock.readLock().lock();
    try {
      for (var engine : indexEngines) {
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          var mgr = btreeEngine.getHistogramManager();
          if (mgr != null) {
            mgr.setBackgroundExecutor(executor);
          }
        }
      }
    } finally {
      stateLock.readLock().unlock();
    }
  }

  /**
   * Flushes WAL, write cache, and cuts the log. Does <b>not</b> flush
   * histogram data — callers that need histogram persistence must call
   * {@link #flushDirtyHistograms()} separately <b>before</b> this method,
   * and outside any frozen {@code OperationsFreezer} scope (see
   * {@link #flushDirtyHistograms()} Javadoc for details).
   */
  protected void flushAllData() {
    try {
      writeAheadLog.flush();

      // so we will be able to cut almost all the log
      writeAheadLog.appendNewSegment();

      final var lastLSN = writeAheadLog.log(new EmptyWALRecord());
      writeCache.flush();

      atomicOperationsTable.compactTable();
      final var operationSegment = atomicOperationsTable.getSegmentEarliestOperationInProgress();
      if (operationSegment >= 0) {
        throw new IllegalStateException(
            "Can not perform full checkpoint if some of atomic operations in progress");
      }

      writeAheadLog.flush();

      writeAheadLog.cutTill(lastLSN);

      clearStorageDirty();

    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during checkpoint creation for storage " + name), ioe,
          name);
    }
  }

  protected StartupMetadata checkIfStorageDirty() throws IOException {
    return new StartupMetadata(-1);
  }

  protected void initConfiguration(
      final ContextConfiguration contextConfiguration,
      AtomicOperation atomicOperation) {
  }

  @SuppressWarnings({"EmptyMethod"})
  protected final void postCreateSteps() {
  }

  protected void preCreateSteps() throws IOException {
  }

  protected abstract void initWalAndDiskCache(ContextConfiguration contextConfiguration)
      throws IOException, java.lang.InterruptedException;

  protected abstract void postCloseSteps(
      @SuppressWarnings("unused") boolean onDelete, boolean internalError, long lastTxId)
      throws IOException;

  @SuppressWarnings({"EmptyMethod"})
  protected Map<String, Object> preCloseSteps() {
    return new HashMap<>(2);
  }

  protected void postDeleteSteps() {
  }

  protected void makeStorageDirty() throws IOException {
  }

  protected void clearStorageDirty() throws IOException {
  }

  protected boolean isDirty() {
    return false;
  }

  @Nullable protected String getOpenedAtVersion() {
    return null;
  }

  @Nonnull
  private StorageReadResult readRecordInternal(@Nonnull final RecordIdInternal rid,
      @Nonnull AtomicOperation atomicOperation) {
    if (!rid.isPersistent()) {
      throw new RecordNotFoundException(name,
          rid, "Cannot read record "
              + rid
              + " since the position is invalid in database '"
              + name
              + '\'');
    }
    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();
      final StorageCollection collection;
      try {
        collection = doGetAndCheckCollection(rid.getCollectionId());
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(new RecordNotFoundException(name, rid), e, name);
      }
      return doReadRecord(collection, rid, atomicOperation);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public boolean recordExists(DatabaseSessionEmbedded session, RID rid,
      @Nonnull AtomicOperation atomicOperation) {
    if (!rid.isPersistent()) {
      throw new RecordNotFoundException(name,
          rid, "Cannot read record "
              + rid
              + " since the position is invalid in database '"
              + name
              + '\'');
    }
    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();
      final StorageCollection collection;
      try {
        collection = doGetAndCheckCollection(rid.getCollectionId());
      } catch (IllegalArgumentException e) {
        return false;
      }
      return doRecordExists(name, collection, rid, atomicOperation);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void endTxCommit(AtomicOperation atomicOperation) throws IOException {
    atomicOperationsManager.endAtomicOperation(atomicOperation, null);
  }

  public AtomicOperation startStorageTx() {
    checkOpennessAndMigration();

    var atomicOperation = atomicOperationsManager.startAtomicOperation();

    var holder = tsMinThreadLocal.get();
    assert holder.activeTxCount >= 0 : "activeTxCount is negative: " + holder.activeTxCount;

    // Capture diagnostic metadata before the volatile tsMin write, so the monitor never
    // sees an active tsMin without valid diagnostic fields. Symmetric with resetTsMin(),
    // which clears diagnostic fields before the opaque tsMin reset.
    if (holder.activeTxCount == 0) {
      boolean captureStackTrace = configuration != null
          && configuration.getContextConfiguration().getValueAsBoolean(
              GlobalConfiguration.STORAGE_TX_CAPTURE_STACK_TRACE);
      holder.captureDiagnostics(
          YouTrackDBEnginesManager.instance().getTicker().approximateNanoTime(),
          Thread.currentThread(),
          captureStackTrace);
    }

    long snapshotMin = atomicOperation.getAtomicOperationsSnapshot().minActiveOperationTs();
    // Use min to preserve the oldest snapshot when multiple txs overlap on this thread.
    // GC may retain entries slightly longer than necessary, but correctness is preserved.
    holder.tsMin = Math.min(holder.tsMin, snapshotMin);
    holder.activeTxCount++;
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }

    return atomicOperation;
  }

  /**
   * Decrements the current thread's active transaction count and resets {@code tsMin} to
   * {@code Long.MAX_VALUE} when no transactions remain active. Called at transaction end
   * (commit or rollback). Multiple sessions on the same thread may have overlapping
   * transactions, so {@code tsMin} is only cleared when the last one ends.
   *
   * <p>The reset uses an opaque write ({@link TsMinHolder#setTsMinOpaque}) instead of a
   * volatile write: no {@code StoreLoad} barrier is needed because the owning thread has no
   * subsequent loads that depend on the reset being globally visible immediately.
   *
   * <p>When the last transaction on the thread closes ({@code activeTxCount} reaches 0),
   * this method also triggers snapshot index cleanup. This ensures stale snapshot entries
   * are evicted after both read and write transactions, not just write commits. The cleanup
   * is best-effort: it uses {@code tryLock()} internally, so it never blocks, and any
   * failure is logged but does not propagate — it must not mask transaction close errors.
   */
  public void resetTsMin() {
    var holder = tsMinThreadLocal.get();
    assert holder.activeTxCount > 0 : "activeTxCount underflow: " + holder.activeTxCount;
    if (holder.activeTxCount <= 0) {
      throw new IllegalStateException(
          "resetTsMin called with no active transactions (activeTxCount="
              + holder.activeTxCount + ")");
    }
    holder.activeTxCount--;
    if (holder.activeTxCount == 0) {
      // Clear diagnostic fields before resetting tsMin. The monitor reads tsMin first:
      // if it sees tsMin != MAX_VALUE, it expects txStartTimeNanos to be non-zero.
      // Clearing diagnostic fields first ensures that if the monitor sees an active tsMin,
      // the diagnostic fields are still valid (not yet cleared). The opaque write of tsMin
      // below does not provide a StoreStore barrier, so without this ordering the monitor
      // could see stale tsMin but cleared txStartTimeNanos.
      holder.clearDiagnostics();
      holder.setTsMinOpaque(Long.MAX_VALUE);
      try {
        cleanupSnapshotIndex();
      } catch (final RuntimeException e) {
        // Cleanup is best-effort — its failure must never mask a successful transaction
        // close. Stale snapshot entries simply accumulate until the next successful
        // cleanup pass.
        LogManager.instance()
            .warn(this, "Snapshot index cleanup failed during resetTsMin", e);
      }
    }
  }

  private void startTxCommit(AtomicOperation atomicOperation) {
    atomicOperationsManager.startToApplyOperations(atomicOperation);
  }

  private void recoverIfNeeded() throws Exception {
    if (isDirty()) {
      LogManager.instance()
          .warn(
              this,
              "Storage '"
                  + name
                  + "' was not closed properly. Will try to recover from write ahead log");
      try {
        final var openedAtVersion = getOpenedAtVersion();

        if (openedAtVersion != null && !openedAtVersion.equals(
            YouTrackDBConstants.getRawVersion())) {
          throw new StorageException(name,
              "Database has been opened at version "
                  + openedAtVersion
                  + " but is attempted to be restored at version "
                  + YouTrackDBConstants.getRawVersion()
                  + ". Please use correct version to restore database.");
        }

        wereDataRestoredAfterOpen = true;

        // Delete non-durable files before WAL replay — their data is unrecoverable
        // (no WAL records exist). The returned set is used by restoreAtomicUnit() to
        // skip WAL records referencing these files.
        deletedNonDurableFileIds = writeCache.deleteNonDurableFilesOnRecovery(readCache);

        try {
          restoreFromWAL();
        } finally {
          // Clear after replay — no longer needed, and prevents stale references
          deletedNonDurableFileIds = new IntOpenHashSet();
        }

        if (recoverListener != null) {
          recoverListener.onStorageRecover();
        }

        flushDirtyHistograms();
        flushAllData();
      } catch (final Exception e) {
        LogManager.instance().error(this, "Exception during storage data restore", e);
        throw e;
      }

      LogManager.instance().info(this, "Storage data recover was completed");
    }
  }

  private PhysicalPosition doCreateRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      @Nonnull final byte[] content,
      long recordVersion,
      final byte recordType,
      final StorageCollection collection,
      final PhysicalPosition allocated) {
    //noinspection ConstantValue
    if (content == null) {
      throw new IllegalArgumentException("Record is null");
    }

    collection.meters().create().record();
    PhysicalPosition ppos;
    try {
      ppos = collection.createRecord(content, recordType, allocated, atomicOperation);
      if (rid instanceof ChangeableRecordId changeableRecordId) {
        changeableRecordId.setCollectionPosition(ppos.collectionPosition);
      } else {
        throw new DatabaseException(name,
            "Provided record is not new and its position can not be changed");
      }
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error on creating record in collection: " + collection, e);
      throw DatabaseException.wrapException(
          new StorageException(name, "Error during creation of record"), e, name);
    }

    if (logger.isDebugEnabled()) {
      LogManager.instance()
          .debug(this, "Created record %s v.%s size=%d bytes", logger, rid, recordVersion,
              content.length);
    }

    return ppos;
  }

  private long doUpdateRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      final boolean updateContent,
      byte[] content,
      final long version,
      final byte recordType,
      final StorageCollection collection) {

    collection.meters().update().record();
    try {
      final var ppos =
          collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()),
              atomicOperation);

      if (ppos == null || ppos.recordVersion != version) {
        final var dbVersion = ppos == null ? -1 : ppos.recordVersion;
        throw new ConcurrentModificationException(
            name, rid, dbVersion, version, RecordOperation.UPDATED);
      }

      final var newRecordVersion = atomicOperation.getCommitTs();
      ppos.recordVersion = newRecordVersion;
      if (updateContent) {
        collection.updateRecord(
            rid.getCollectionPosition(), content, recordType, atomicOperation);
      } else {
        collection.updateRecordVersion(
            rid.getCollectionPosition(), atomicOperation);
      }

      if (logger.isDebugEnabled()) {
        LogManager.instance()
            .debug(this, "Updated record %s v.%s size=%d", logger, rid, newRecordVersion,
                content.length);
      }

      return newRecordVersion;
    } catch (final ConcurrentModificationException e) {
      collection.meters().conflict().record();
      throw e;
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error on updating record " + rid + " (collection: " + collection.getName() + ")"),
          ioe, name);
    }
  }

  private void doDeleteRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      final long version,
      final StorageCollection collection) {
    collection.meters().delete().record();
    try {
      final var ppos =
          collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()),
              atomicOperation);

      if (ppos == null) {
        // ALREADY DELETED
        return;
      }

      // MVCC TRANSACTION: CHECK IF VERSION IS THE SAME
      if (version > -1 && ppos.recordVersion != version) {
        collection.meters().conflict().record();
        throw new ConcurrentModificationException(name, rid, ppos.recordVersion, version,
            RecordOperation.DELETED);
      }

      collection.deleteRecord(atomicOperation, ppos.collectionPosition);

      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Deleted record %s v.%s", logger, rid, version);
      }

    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error on deleting record " + rid + "( collection: " + collection.getName() + ")"),
          ioe, name);
    }
  }

  @Nonnull
  private StorageReadResult doReadRecord(final StorageCollection collection,
      final RecordIdInternal rid, AtomicOperation atomicOperation) {
    collection.meters().read().record();
    try {
      final var result = collection.readRecord(rid.getCollectionPosition(),
          atomicOperation);
      if (logger.isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "Read record %s v.%s size=%s bytes",
                logger, rid,
                result.recordVersion(),
                result instanceof RawBuffer rb
                    ? (rb.buffer() != null ? rb.buffer().length : 0)
                    : result instanceof RawPageBuffer rpb ? rpb.contentLength()
                        : 0);
      }

      return result;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during read of record with rid = " + rid), e, name);
    }
  }

  private static boolean doRecordExists(String dbName, final StorageCollection collectionSegment,
      final RID rid, AtomicOperation atomicOperation) {
    try {
      return collectionSegment.exists(rid.getCollectionPosition(), atomicOperation);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(dbName, "Error during read of record with rid = " + rid), e, dbName);
    }
  }

  private int createCollectionFromConfig(final StorageCollectionConfiguration config,
      AtomicOperation atomicOperation)
      throws IOException {
    var collection = collectionMap.get(config.name().toLowerCase(Locale.ROOT));

    if (collection != null) {
      collection.configure(this, config);
      return -1;
    }

    collection =
        StorageCollectionFactory.createCollection(
            config.name(), configuration.getVersion(atomicOperation), config.getBinaryVersion(),
            this);

    collection.configure(this, config);

    return registerCollection(collection);
  }

  private void setCollection(final int id, final StorageCollection collection) {
    if (collections.size() <= id) {
      while (collections.size() < id) {
        collections.add(null);
      }

      collections.add(collection);
    } else {
      collections.set(id, collection);
    }
  }

  /**
   * Register the collection internally.
   *
   * @param collection SQLCollection implementation
   * @return The id (physical position into the array) of the new collection just created. First is
   * 0.
   */
  private int registerCollection(final StorageCollection collection) {
    final int id;

    if (collection != null) {
      // CHECK FOR DUPLICATION OF NAMES
      if (collectionMap.containsKey(collection.getName().toLowerCase(Locale.ROOT))) {
        throw new ConfigurationException(name,
            "Cannot add collection '"
                + collection.getName()
                + "' because it is already registered in database '"
                + name
                + "'");
      }
      // CREATE AND ADD THE NEW REF SEGMENT
      collectionMap.put(collection.getName().toLowerCase(Locale.ROOT), collection);
      id = collection.getId();
    } else {
      id = collections.size();
    }

    setCollection(id, collection);

    return id;
  }

  private int doAddCollection(final AtomicOperation atomicOperation, final String collectionName)
      throws IOException {
    // FIND THE FIRST AVAILABLE COLLECTION ID
    var collectionPos = collections.size();
    for (var i = 0; i < collections.size(); ++i) {
      if (collections.get(i) == null) {
        collectionPos = i;
        break;
      }
    }

    return doAddCollection(atomicOperation, collectionName, collectionPos);
  }

  private int doAddCollection(
      final AtomicOperation atomicOperation, String collectionName, final int collectionPos)
      throws IOException {
    final PaginatedCollection collection;
    if (collectionName != null) {
      collectionName = collectionName.toLowerCase(Locale.ROOT);

      collection =
          StorageCollectionFactory.createCollection(
              collectionName,
              configuration.getVersion(atomicOperation),
              configuration
                  .getContextConfiguration()
                  .getValueAsInteger(GlobalConfiguration.STORAGE_COLLECTION_VERSION),
              this);
      collection.configure(collectionPos, collectionName);
    } else {
      collection = null;
    }

    var createdCollectionId = -1;

    if (collection != null) {
      collection.create(atomicOperation);
      createdCollectionId = registerCollection(collection);

      ((CollectionBasedStorageConfiguration) configuration)
          .updateCollection(atomicOperation, collection.generateCollectionConfig());

      linkCollectionsBTreeManager.createComponent(atomicOperation, createdCollectionId);
    }

    return createdCollectionId;
  }

  @Override
  public void setCollectionAttribute(final int id, final ATTRIBUTES attribute,
      final Object value) {
    stateLock.writeLock().lock();
    try {
      checkOpennessAndMigration();
      if (id >= collections.size()) {
        return;
      }

      final var collection = collections.get(id);

      if (collection == null) {
        return;
      }

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> doSetCollectionAttributed(atomicOperation, attribute, value,
              collection));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void doSetCollectionAttributed(
      final AtomicOperation atomicOperation,
      final ATTRIBUTES attribute,
      final Object value,
      final StorageCollection collection) {
    final var stringValue = Optional.ofNullable(value).map(Object::toString).orElse(null);
    switch (attribute) {
      case NAME -> {
        Objects.requireNonNull(stringValue);

        final var oldName = collection.getName();
        collection.setCollectionName(stringValue);
        collectionMap.remove(oldName.toLowerCase(Locale.ROOT));
        collectionMap.put(stringValue.toLowerCase(Locale.ROOT), collection);
      }
      case CONFLICTSTRATEGY -> collection.setRecordConflictStrategy(stringValue);
      default -> throw new IllegalArgumentException(
          "Runtime change of attribute '" + attribute + "' is not supported");
    }

    ((CollectionBasedStorageConfiguration) configuration)
        .updateCollection(atomicOperation,
            ((PaginatedCollection) collection).generateCollectionConfig());
  }

  private boolean dropCollectionInternal(final AtomicOperation atomicOperation,
      final int collectionId)
      throws IOException {
    final var collection = collections.get(collectionId);

    if (collection == null) {
      return true;
    }

    collection.delete(atomicOperation);

    collectionMap.remove(collection.getName().toLowerCase(Locale.ROOT));
    collections.set(collectionId, null);

    return false;
  }

  protected void doShutdown() throws IOException {
    shutdownDuration.timed(() -> {
      if (status == STATUS.CLOSED) {
        return;
      }

      stopStaleTransactionMonitor();

      if (status != STATUS.OPEN && !isInError()) {
        throw BaseException.wrapException(
            new StorageException(name, "Storage " + name + " was not opened, so can not be closed"),
            this.error.get(), name);
      }

      status = STATUS.CLOSING;

      if (!isInError()) {
        // Cancel in-progress histogram rebalances, flush dirty data, and
        // block future rebalances — must happen before flushAllData so
        // that no background thread holds page references.
        cancelHistogramRebalances();
        flushAllData();
      }

      preCloseSteps();

      if (!isInError()) {
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> {
              // we close all files inside cache system so we only clear index metadata and close
              // non core indexes
              for (final var engine : indexEngines) {
                if (engine != null
                    && !(engine instanceof BTreeSingleValueIndexEngine
                        || engine instanceof BTreeMultiValueIndexEngine)) {
                  engine.close();
                }
              }
              ((CollectionBasedStorageConfiguration) configuration).close(atomicOperation);
            });
      } else {
        LogManager.instance()
            .error(
                this,
                "Because of JVM error happened inside of storage it can not be properly closed",
                null);
      }

      linkCollectionsBTreeManager.close();

      // we close all files inside cache system so we only clear collection metadata
      collections.clear();
      collectionMap.clear();
      indexEngines.clear();
      indexEngineNameMap.clear();
      sharedSnapshotIndex.clear();
      visibilityIndex.clear();
      snapshotIndexSize.set(0);
      sharedEdgeSnapshotIndex.clear();
      edgeVisibilityIndex.clear();
      edgeSnapshotIndexSize.set(0);
      sharedIndexesSnapshot.clear();
      indexesSnapshotVisibilityIndex.clear();
      sharedNullIndexesSnapshot.clear();
      nullIndexSnapshotVisibilityIndex.clear();
      indexesSnapshotEntriesCount.set(0);

      if (writeCache != null) {
        writeCache.removeBackgroundExceptionListener(this);
        writeCache.removePageIsBrokenListener(this);
      }

      writeAheadLog.removeCheckpointListener(this);

      try {
        if (readCache != null) {
          readCache.closeStorage(writeCache);
        }
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during closing of disk cache", e);
      }

      try {
        writeAheadLog.close();
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during closing of write ahead log", e);
      }

      postCloseSteps(false, isInError(), idGen.getLastId());

      migration = new CountDownLatch(1);
      status = STATUS.CLOSED;
    });
  }

  private void doShutdownOnDelete() {
    if (status == STATUS.CLOSED) {
      return;
    }

    stopStaleTransactionMonitor();

    if (status != STATUS.OPEN && !isInError()) {
      throw BaseException.wrapException(
          new StorageException(name, "Storage " + name + " was not opened, so can not be closed"),
          this.error.get(), name);
    }

    status = STATUS.CLOSING;
    try {
      if (!isInError()) {
        preCloseSteps();

        // Cancel any in-progress histogram rebalances and block future
        // ones before clearing engines and deleting the cache. A running
        // rebalance holds page read locks; deleting the cache while
        // those locks are held causes "page is used" errors and JVM
        // crashes.
        cancelHistogramRebalances();

        for (final var engine : indexEngines) {
          if (engine != null
              && !(engine instanceof BTreeSingleValueIndexEngine
                  || engine instanceof BTreeMultiValueIndexEngine)) {
            // delete method is implemented only in non native indexes, so they do not use ODB
            // atomic operation
            engine.delete(null);
          }
        }

        linkCollectionsBTreeManager.close();

        // we close all files inside cache system so we only clear collection metadata
        collections.clear();
        collectionMap.clear();
        indexEngines.clear();
        indexEngineNameMap.clear();
        sharedSnapshotIndex.clear();
        visibilityIndex.clear();
        snapshotIndexSize.set(0);
        sharedEdgeSnapshotIndex.clear();
        edgeVisibilityIndex.clear();
        edgeSnapshotIndexSize.set(0);
        sharedIndexesSnapshot.clear();
        indexesSnapshotVisibilityIndex.clear();
        sharedNullIndexesSnapshot.clear();
        nullIndexSnapshotVisibilityIndex.clear();
        indexesSnapshotEntriesCount.set(0);

        if (writeCache != null) {
          writeCache.removeBackgroundExceptionListener(this);
          writeCache.removePageIsBrokenListener(this);
        }

        writeAheadLog.removeCheckpointListener(this);

        if (readCache != null) {
          try {
            readCache.deleteStorage(writeCache);
          } catch (final Exception e) {
            LogManager.instance().error(this, "Error during deletion of read cache", e);
          }
        }

        try {
          writeAheadLog.delete();
        } catch (final Exception e) {
          LogManager.instance().error(this, "Error during deletion of write ahead log", e);
        }
      } else {
        LogManager.instance()
            .error(
                this,
                "Because of JVM error happened inside of storage it can not be properly closed",
                null);
      }
      postCloseSteps(true, isInError(), idGen.getLastId());
      migration = new CountDownLatch(1);
      status = STATUS.CLOSED;
    } catch (final IOException e) {
      final var message = "Error on closing of storage '" + name;
      LogManager.instance().error(this, message, e);

      throw BaseException.wrapException(new StorageException(name, message), e, name);
    }
  }

  @SuppressWarnings("unused")
  protected void closeCollections() throws IOException {
    for (final var collection : collections) {
      if (collection != null) {
        collection.close(true);
      }
    }
    collections.clear();
    collectionMap.clear();
  }

  @SuppressWarnings("unused")
  protected void closeIndexes(final AtomicOperation atomicOperation) {
    for (final var engine : indexEngines) {
      if (engine != null) {
        engine.close();
      }
    }

    indexEngines.clear();
    indexEngineNameMap.clear();
  }

  private void commitEntry(
      FrontendTransactionImpl frontendTransaction,
      final AtomicOperation atomicOperation,
      final RecordOperation txEntry,
      final PhysicalPosition allocated,
      final RecordSerializer serializer) {
    final var rec = txEntry.record;
    if (txEntry.type != RecordOperation.DELETED && !rec.isDirty())
    // NO OPERATION
    {
      return;
    }
    final var rid = rec.getIdentity();

    if (txEntry.type == RecordOperation.UPDATED && rid.isNew())
    // OVERWRITE OPERATION AS CREATE
    {
      txEntry.type = RecordOperation.CREATED;
    }

    final var collection = doGetAndCheckCollection(rid.getCollectionId());

    var db = frontendTransaction.getDatabaseSession();

    switch (txEntry.type) {
      case RecordOperation.CREATED -> {
        final byte[] stream;
        try {
          stream = serializer.toStream(frontendTransaction.getDatabaseSession(), rec);
          if (stream == null) {
            throw new IllegalArgumentException("Record content is null");
          }
        } catch (RuntimeException e) {
          throw BaseException.wrapException(
              new CommitSerializationException(db.getDatabaseName(),
                  "Error During Record Serialization"),
              e, name);
        }
        if (allocated != null) {
          final PhysicalPosition ppos;
          final var recordType = rec.getRecordType();
          final var recordVersion = rec.getVersion() > -1 ? atomicOperation.getCommitTs() : 0;
          ppos =
              doCreateRecord(
                  atomicOperation,
                  rid,
                  stream,
                  recordVersion,
                  recordType,
                  collection, allocated);
          rec.setVersion(ppos.recordVersion);
        } else {
          final var updatedVersion =
              doUpdateRecord(
                  atomicOperation,
                  rid,
                  rec.isContentChanged(),
                  stream,
                  -2,
                  rec.getRecordType(),
                  collection);
          rec.setVersion(updatedVersion);
        }
      }
      case RecordOperation.UPDATED -> {
        final byte[] stream;
        try {
          stream = serializer.toStream(frontendTransaction.getDatabaseSession(), rec);
        } catch (RuntimeException e) {
          throw BaseException.wrapException(
              new CommitSerializationException(db.getDatabaseName(),
                  "Error During Record Serialization"),
              e, name);
        }

        final var version =
            doUpdateRecord(
                atomicOperation,
                rid,
                rec.isContentChanged(),
                stream,
                rec.getVersion(),
                rec.getRecordType(),
                collection);
        rec.setVersion(version);
      }
      case RecordOperation.DELETED -> {
        if (rec instanceof EntityImpl entity) {
          LinkBagDeleter.deleteAllRidBags(entity, frontendTransaction);
        }
        doDeleteRecord(atomicOperation, rid, rec.getVersionNoLoad(), collection);
      }
      default -> throw new StorageException(name, "Unknown record operation " + txEntry.type);
    }

    // RESET TRACKING
    if (rec instanceof EntityImpl entity) {
      entity.clearTrackData();
      entity.clearTransactionTrackData();
    }

    rec.unsetDirty();
  }

  private void checkCollectionSegmentIndexRange(final int iCollectionId) {
    if (iCollectionId < 0 || iCollectionId > collections.size() - 1) {
      throw new IllegalArgumentException(
          "Collection segment #" + iCollectionId + " does not exist in database '" + name + "'");
    }
  }

  private void restoreFromWAL() throws IOException {
    final var begin = writeAheadLog.begin();
    if (begin == null) {
      LogManager.instance()
          .error(this, "Restore is not possible because write ahead log is empty.", null);
      return;
    }

    LogManager.instance().info(this, "Looking for last checkpoint...");

    writeAheadLog.addCutTillLimit(begin);
    try {
      restoreFromBeginning();
    } finally {
      writeAheadLog.removeCutTillLimit(begin);
    }
  }

  public abstract String fullBackup(final Path backupDirectory);

  public abstract String fullBackup(Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      Consumer<String> ibuFileRemover);

  public abstract String backup(final Path backupDirectory);

  public abstract String backup(final Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      final Consumer<String> ibuFileRemover);

  public abstract void restoreFromBackup(final Path backupDirectory, String expectedUUID);

  public abstract void restoreFromBackup(final Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID);

  private void restoreFromBeginning() throws IOException {
    LogManager.instance().info(this, "Data restore procedure is started.");

    final var lsn = writeAheadLog.begin();

    writeCache.restoreModeOn();
    try {
      restoreFrom(writeAheadLog, lsn);
    } finally {
      writeCache.restoreModeOff();
    }
  }

  @SuppressWarnings("UnusedReturnValue")
  protected LogSequenceNumber restoreFrom(WriteAheadLog writeAheadLog, LogSequenceNumber lsn)
      throws IOException {
    final var atLeastOnePageUpdate = new ModifiableBoolean();

    long recordsProcessed = 0;
    long maxOperationUnitId = -1;

    final var reportBatchSize =
        GlobalConfiguration.WAL_REPORT_AFTER_OPERATIONS_DURING_RESTORE.getValueAsInteger();
    final var operationUnits =
        new Long2ObjectOpenHashMap<List<WALRecord>>(1024);

    long lastReportTime = 0;
    LogSequenceNumber lastUpdatedLSN = null;

    try {
      var records = writeAheadLog.read(lsn, 1_000);

      while (!records.isEmpty()) {
        for (final var walRecord : records) {
          switch (walRecord) {
            case AtomicUnitEndRecord atomicUnitEndRecord -> {
              final var opId = atomicUnitEndRecord.getOperationUnitId();
              if (opId > maxOperationUnitId) {
                maxOperationUnitId = opId;
              }
              final var atomicUnit = operationUnits.remove(opId);

              // in case of data restore from fuzzy checkpoint part of operations may be already
              // flushed to the disk
              if (atomicUnit != null) {
                atomicUnit.add(walRecord);
                restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);
                lastUpdatedLSN = walRecord.getLsn();
              }
            }
            case AtomicUnitStartRecord oAtomicUnitStartRecord -> {
              final List<WALRecord> operationList = new ArrayList<>(1024);

              assert !operationUnits.containsKey(oAtomicUnitStartRecord.getOperationUnitId());

              operationUnits.put(oAtomicUnitStartRecord.getOperationUnitId(), operationList);
              operationList.add(walRecord);
            }
            case OperationUnitRecord operationUnitRecord -> {
              var operationList =
                  operationUnits.computeIfAbsent(
                      operationUnitRecord.getOperationUnitId(), k -> new ArrayList<>(1024));
              operationList.add(operationUnitRecord);
            }
            case NonTxOperationPerformedWALRecord ignored -> {
              if (!wereNonTxOperationsPerformedInPreviousOpen) {
                LogManager.instance()
                    .warn(
                        this,
                        "Non tx operation was used during data modification we will need index"
                            + " rebuild.");
                wereNonTxOperationsPerformedInPreviousOpen = true;
              }
            }
            case null, default -> LogManager.instance()
                .debug(this, "Record %s will be skipped during data restore",
                    logger, walRecord);
          }

          recordsProcessed++;

          final var currentTime = System.currentTimeMillis();
          if ((reportBatchSize > 0 && recordsProcessed % reportBatchSize == 0)
              || currentTime - lastReportTime > WAL_RESTORE_REPORT_INTERVAL) {
            final var additionalArgs =
                new Object[] {recordsProcessed, walRecord.getLsn(), writeAheadLog.end()};
            LogManager.instance()
                .info(
                    this,
                    "%d operations were processed, current LSN is %s last LSN is %s",
                    additionalArgs);
            lastReportTime = currentTime;
          }
        }

        records = writeAheadLog.next(records.getLast().getLsn(), 1_000);
      }
    } catch (final WALPageBrokenException e) {
      LogManager.instance()
          .error(
              this,
              "Data restore was paused because broken WAL page was found. The rest of changes will"
                  + " be rolled back.",
              e);
    } catch (final RuntimeException e) {
      LogManager.instance()
          .error(
              this,
              "Data restore was paused because of exception. The rest of changes will be rolled"
                  + " back.",
              e);
    }

    // After WAL replay, synchronize idGen with the highest operationUnitId seen.
    // This is critical after backup/restore where the idGen counter may be stale.
    if (maxOperationUnitId >= 0 && maxOperationUnitId >= idGen.getLastId()) {
      idGen.setStartId(maxOperationUnitId + 1);
    }

    return lastUpdatedLSN;
  }

  protected final void restoreAtomicUnit(
      final List<WALRecord> atomicUnit, final ModifiableBoolean atLeastOnePageUpdate)
      throws IOException {
    assert atomicUnit.getLast() instanceof AtomicUnitEndRecord;
    for (final var walRecord : atomicUnit) {
      switch (walRecord) {
        case FileDeletedWALRecord fileDeletedWALRecord -> {
          // Skip WAL records for files deleted during crash recovery (non-durable files)
          if (deletedNonDurableFileIds.contains(
              writeCache.internalFileId(fileDeletedWALRecord.getFileId()))) {
            continue;
          }
          if (writeCache.exists(fileDeletedWALRecord.getFileId())) {
            readCache.deleteFile(fileDeletedWALRecord.getFileId(), writeCache);
          }
        }
        case FileCreatedWALRecord fileCreatedCreatedWALRecord -> {
          // Skip re-creating non-durable files that were deleted during crash recovery
          if (deletedNonDurableFileIds.contains(
              writeCache.internalFileId(fileCreatedCreatedWALRecord.getFileId()))) {
            continue;
          }
          if (!writeCache.exists(fileCreatedCreatedWALRecord.getFileName())) {
            readCache.addFile(
                fileCreatedCreatedWALRecord.getFileName(),
                fileCreatedCreatedWALRecord.getFileId(),
                writeCache);
          }
        }
        case UpdatePageRecord updatePageRecord -> {
          var fileId = updatePageRecord.getFileId();

          // Skip page updates for non-durable files deleted during crash recovery
          if (deletedNonDurableFileIds.contains(writeCache.internalFileId(fileId))) {
            continue;
          }

          if (!writeCache.exists(fileId)) {
            final var fileName = writeCache.restoreFileById(fileId);

            if (fileName == null) {
              throw new StorageException(name,
                  "File with id "
                      + fileId
                      + " was deleted from storage, the rest of operations can not be restored");
            } else {
              LogManager.instance()
                  .warn(
                      this,
                      "Previously deleted file with name "
                          + fileName
                          + " was deleted but new empty file was added to continue restore process");
            }
          }

          final var pageIndex = updatePageRecord.getPageIndex();
          fileId = writeCache.externalFileId(writeCache.internalFileId(fileId));

          var cacheEntry = readCache.loadForWrite(fileId, pageIndex, writeCache, true, null);
          if (cacheEntry == null) {
            do {
              if (cacheEntry != null) {
                readCache.releaseFromWrite(cacheEntry, writeCache, true);
              }

              cacheEntry = readCache.allocateNewPage(fileId, writeCache, null);
            } while (cacheEntry.getPageIndex() != pageIndex);
          }

          try {
            final var durablePage = new DurablePage(cacheEntry);
            var pageLsn = durablePage.getLsn();
            if (durablePage.getLsn().compareTo(walRecord.getLsn()) < 0) {
              if (!pageLsn.equals(updatePageRecord.getInitialLsn())) {
                LogManager.instance()
                    .error(
                        this,
                        "Page with index "
                            + pageIndex
                            + " and file "
                            + writeCache.fileNameById(fileId)
                            + " was changed before page restore was started. Page will be restored"
                            + " from WAL, but it may contain changes that were not present before"
                            + " storage crash and data may be lost. Initial LSN is "
                            + updatePageRecord.getInitialLsn()
                            + ", but page contains changes with LSN "
                            + pageLsn,
                        null);
              }
              durablePage.restoreChanges(updatePageRecord.getChanges());
              durablePage.setLsn(updatePageRecord.getLsn());
            }
          } finally {
            readCache.releaseFromWrite(cacheEntry, writeCache, true);
          }

          atLeastOnePageUpdate.setValue(true);
        }
        //noinspection unused
        case AtomicUnitStartRecord atomicUnitStartRecord -> {
          //noinspection UnnecessaryContinue
          continue;
        }
        //noinspection unused
        case AtomicUnitEndRecord atomicUnitEndRecord -> {
          //noinspection UnnecessaryContinue
          continue;

        }
        //noinspection unused
        case HighLevelTransactionChangeRecord highLevelTransactionChangeRecord -> {
          //noinspection UnnecessaryContinue
          continue;
        }
        case null, default -> {
          assert walRecord != null;
          LogManager.instance()
              .error(
                  this,
                  "Invalid WAL record type was passed %s. Given record will be skipped.",
                  null,
                  walRecord.getClass());

          assert false : "Invalid WAL record type was passed " + walRecord.getClass().getName();
        }
      }
    }
  }

  @SuppressWarnings("unused")
  public void setStorageConfigurationUpdateListener(
      final StorageConfigurationUpdateListener storageConfigurationUpdateListener) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      ((CollectionBasedStorageConfiguration) configuration)
          .setConfigurationUpdateListener(storageConfigurationUpdateListener);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void pauseConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      ((CollectionBasedStorageConfiguration) configuration).pauseUpdateNotifications();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void fireConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();
      ((CollectionBasedStorageConfiguration) configuration).fireUpdateNotifications();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @SuppressWarnings("unused")
  protected static Int2ObjectMap<List<RecordIdInternal>> getRidsGroupedByCollection(
      final Collection<RecordIdInternal> rids) {
    final var ridsPerCollection = new Int2ObjectOpenHashMap<List<RecordIdInternal>>(8);
    for (final var rid : rids) {
      final var group =
          ridsPerCollection.computeIfAbsent(rid.getCollectionId(),
              k -> new ArrayList<>(rids.size()));
      group.add(rid);
    }
    return ridsPerCollection;
  }

  private static void lockIndexes(
      final SortedMap<String, FrontendTransactionIndexChanges> indexes,
      AtomicOperation atomicOperation) {
    for (final var changes : indexes.values()) {
      changes.getIndex().acquireAtomicExclusiveLock(atomicOperation);
    }
  }

  private static void lockCollections(final TreeMap<Integer, StorageCollection> collectionsToLock,
      AtomicOperation atomicOperation) {
    for (final var collection : collectionsToLock.values()) {
      collection.acquireAtomicExclusiveLock(atomicOperation);
    }
  }

  private void lockLinkBags(
      final TreeMap<Integer, StorageCollection> collections,
      @Nonnull AtomicOperation atomicOperation) {
    for (final var collectionId : collections.keySet()) {
      var bTree = linkCollectionsBTreeManager.getComponentByCollectionId(
          collectionId, atomicOperation);
      // The B-tree should always exist: it is created with the collection
      // (doAddCollection) and repaired during storage open
      // (checkRidBagsPresence). lockCollections() already serializes
      // concurrent commits to the same collection, so a null here would
      // not cause a race — but it would indicate a bug elsewhere.
      assert bTree != null
          : "Link bag B-tree missing for collection " + collectionId;
      if (bTree != null) {
        atomicOperationsManager.acquireExclusiveLockTillOperationComplete(
            atomicOperation, bTree);
      }
    }
  }

  protected RuntimeException logAndPrepareForRethrow(final RuntimeException runtimeException) {
    if (!(runtimeException instanceof HighLevelException
        || runtimeException instanceof NeedRetryException
        || runtimeException instanceof InternalErrorException
        || runtimeException instanceof IllegalArgumentException)) {
      final var iAdditionalArgs =
          new Object[] {
              System.identityHashCode(runtimeException), getURL(), YouTrackDBConstants.getVersion()
          };
      LogManager.instance()
          .error(this, "Exception `%08X` in storage `%s`: %s", runtimeException, iAdditionalArgs);
    }

    if (runtimeException instanceof BaseException baseException) {
      baseException.setDbName(name);
    }

    return runtimeException;
  }

  protected final Error logAndPrepareForRethrow(final Error error) {
    return logAndPrepareForRethrow(error, true);
  }

  protected Error logAndPrepareForRethrow(final Error error, final boolean putInReadOnlyMode) {
    if (!(error instanceof HighLevelException)) {
      if (putInReadOnlyMode) {
        setInError(error);
      }

      final var iAdditionalArgs =
          new Object[] {System.identityHashCode(error), getURL(), YouTrackDBConstants.getVersion()};
      LogManager.instance()
          .error(this, "Exception `%08X` in storage `%s`: %s", error, iAdditionalArgs);
    }

    return error;
  }

  protected final RuntimeException logAndPrepareForRethrow(final Throwable throwable) {
    return logAndPrepareForRethrow(throwable, true);
  }

  protected RuntimeException logAndPrepareForRethrow(
      final Throwable throwable, final boolean putInReadOnlyMode) {
    if (!(throwable instanceof HighLevelException
        || throwable instanceof NeedRetryException
        || throwable instanceof InternalErrorException)) {
      if (putInReadOnlyMode) {
        setInError(throwable);
      }
      final var iAdditionalArgs =
          new Object[] {System.identityHashCode(throwable), getURL(),
              YouTrackDBConstants.getVersion()};
      LogManager.instance()
          .error(this, "Exception `%08X` in storage `%s`: %s", throwable, iAdditionalArgs);
    }
    if (throwable instanceof BaseException baseException) {
      baseException.setDbName(name);
    }
    return new RuntimeException(throwable);
  }

  private InvalidIndexEngineIdException logAndPrepareForRethrow(
      final InvalidIndexEngineIdException exception) {
    final var iAdditionalArgs =
        new Object[] {System.identityHashCode(exception), getURL(),
            YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .error(this, "Exception `%08X` in storage `%s` : %s", exception, iAdditionalArgs);
    return exception;
  }

  @Override
  public final void setSchemaRecordId(final String schemaRecordId) {
    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setSchemaRecordId(atomicOperation,
              schemaRecordId));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setDateFormat(final String dateFormat) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setDateFormat(atomicOperation, dateFormat));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setTimeZone(final TimeZone timeZoneValue) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setTimeZone(atomicOperation, timeZoneValue));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setLocaleLanguage(final String locale) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setLocaleLanguage(atomicOperation, locale));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setCharset(final String charset) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setCharset(atomicOperation, charset));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setIndexMgrRecordId(final String indexMgrRecordId) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setIndexMgrRecordId(atomicOperation,
              indexMgrRecordId));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setDateTimeFormat(final String dateTimeFormat) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setDateTimeFormat(atomicOperation,
              dateTimeFormat));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setLocaleCountry(final String localeCountry) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setLocaleCountry(atomicOperation, localeCountry));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setValidation(final boolean validation) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setValidation(atomicOperation, validation));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void removeProperty(final String property) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.removeProperty(atomicOperation, property));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setProperty(final String property, final String value) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> storageConfiguration.setProperty(atomicOperation, property, value));
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void setRecordSerializer(final String recordSerializer, final int version) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> {
            storageConfiguration.setRecordSerializer(atomicOperation, recordSerializer);
            storageConfiguration.setRecordSerializerVersion(atomicOperation, version);
          });
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public final void clearProperties() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (CollectionBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          storageConfiguration::clearProperties);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  void runWALVacuum() {
    stateLock.readLock().lock();
    try {

      if (status == STATUS.CLOSED) {
        return;
      }

      final var nonActiveSegments = writeAheadLog.nonActiveSegments();
      if (nonActiveSegments.length == 0) {
        return;
      }

      long flushTillSegmentId;
      if (nonActiveSegments.length == 1) {
        flushTillSegmentId = writeAheadLog.activeSegment();
      } else {
        flushTillSegmentId =
            (nonActiveSegments[0] + nonActiveSegments[nonActiveSegments.length - 1]) / 2;
      }

      long minDirtySegment;
      do {
        writeCache.flushTillSegment(flushTillSegmentId);

        // we should take active segment BEFORE min write cache LSN call
        // to avoid case when new data are changed before call
        final var activeSegment = writeAheadLog.activeSegment();
        final var minLSNSegment = writeCache.getMinimalNotFlushedSegment();

        minDirtySegment = Objects.requireNonNullElse(minLSNSegment, activeSegment);
      } while (minDirtySegment < flushTillSegmentId);

      atomicOperationsTable.compactTable();
      final var operationSegment = atomicOperationsTable.getSegmentEarliestNotPersistedOperation();
      if (operationSegment >= 0 && minDirtySegment > operationSegment) {
        minDirtySegment = operationSegment;
      }

      if (minDirtySegment <= nonActiveSegments[0]) {
        return;
      }

      writeCache.syncDataFiles(minDirtySegment);
    } catch (final Exception e) {
      LogManager.instance()
          .error(
              this, "Error during flushing of data for fuzzy checkpoint, in storage %s", e, name);
    } finally {
      stateLock.readLock().unlock();
      walVacuumInProgress.set(false);
    }
  }

  @Override
  public int[] getCollectionsIds(Set<String> filterCollections) {
    try {

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();
        var result = new int[filterCollections.size()];
        var i = 0;
        for (var collectionName : filterCollections) {
          if (collectionName == null) {
            throw new IllegalArgumentException("Collection name is null");
          }

          if (collectionName.isEmpty()) {
            throw new IllegalArgumentException("Collection name is empty");
          }

          // SEARCH IT BETWEEN PHYSICAL COLLECTIONS
          final var segment = collectionMap.get(collectionName.toLowerCase(Locale.ROOT));
          if (segment != null) {
            result[i] = segment.getId();
          } else {
            result[i] = -1;
          }
          i++;
        }
        return result;
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public YouTrackDBInternalEmbedded getContext() {
    return this.context;
  }

  /**
   * Attempts to evict stale snapshot index entries if the index exceeds the configured threshold.
   * Called from {@link #resetTsMin()} at the end of every storage transaction (both read and
   * write), ensuring prompt cleanup even in read-heavy workloads where write commits are
   * infrequent.
   *
   * <p>Uses {@code tryLock} so only one thread cleans at a time — other threads skip cleanup
   * if the lock is already held (no point blocking; the cleaning thread will handle it). The
   * threshold is re-checked under the lock (double-checked pattern) to avoid redundant work
   * if another thread already cleaned enough entries.
   *
   * <p>Uses {@link #snapshotIndexSize} (an {@code AtomicLong} counter) instead of
   * {@code ConcurrentSkipListMap.size()} for the threshold check. The latter is O(n) — it
   * traverses the entire map — and calling it on every transaction close causes resource
   * exhaustion under sustained heavy concurrent load (e.g., 30-minute soak tests with 10+
   * threads). The counter is incremented during {@code flushSnapshotBuffers()} and decremented
   * during {@code evictStaleSnapshotEntries()}, providing an O(1) approximate size check.
   */
  private void cleanupSnapshotIndex() {
    int threshold = configuration.getContextConfiguration()
        .getValueAsInteger(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD);
    long combinedSize =
        snapshotIndexSize.get() + edgeSnapshotIndexSize.get() + indexesSnapshotEntriesCount.get();
    if (combinedSize <= threshold) {
      return;
    }
    if (!snapshotCleanupLock.tryLock()) {
      return;
    }
    try {
      combinedSize =
          snapshotIndexSize.get() + edgeSnapshotIndexSize.get() + indexesSnapshotEntriesCount.get();
      if (combinedSize <= threshold) {
        return;
      }
      long lwm = computeGlobalLowWaterMark();
      evictStaleSnapshotEntries(
          lwm, sharedSnapshotIndex, visibilityIndex,
          snapshotIndexSize, collections);
      evictStaleEdgeSnapshotEntries(
          lwm, sharedEdgeSnapshotIndex, edgeVisibilityIndex,
          edgeSnapshotIndexSize);
      evictStaleIndexesSnapshotEntries(lwm, sharedIndexesSnapshot,
          indexesSnapshotVisibilityIndex, indexesSnapshotEntriesCount);
      evictStaleIndexesSnapshotEntries(lwm, sharedNullIndexesSnapshot,
          nullIndexSnapshotVisibilityIndex, indexesSnapshotEntriesCount);
    } finally {
      snapshotCleanupLock.unlock();
    }
  }

  /**
   * Periodic records GC task entry point. Performs two duties:
   * <ol>
   *   <li>Opportunistically cleans the snapshot/visibility indexes (same work as
   *       {@link #cleanupSnapshotIndex()}, using {@code tryLock()} — if another thread is
   *       already cleaning, this step is skipped).</li>
   *   <li>Iterates over all collections in the storage and reclaims dead records from those
   *       that exceed the GC trigger threshold.</li>
   * </ol>
   *
   * <p>Called by the periodic scheduled task ({@code PeriodicRecordsGc}) on the
   * {@code fuzzyCheckpointExecutor}. The method is safe to call concurrently — snapshot
   * cleanup uses {@code tryLock()}, and the per-collection GC is serialized by the
   * collection's component lock inside {@code collectDeadRecords()}.
   *
   * <p><b>Deadlock prevention:</b> This method acquires {@code stateLock.readLock()} for
   * its entire duration. Without it, the delete path ({@code doShutdownOnDelete}) can
   * acquire {@code stateLock.writeLock()} and proceed to
   * {@code WOWCache.delete()} → {@code filesLock.writeLock()} while this GC task is
   * still running. The GC writes pages through the read cache, which acquires a
   * {@code PageFrame} exclusive lock and then calls {@code WOWCache.store()} →
   * {@code filesLock.readLock()}. This creates a 3-way deadlock:
   * <ol>
   *   <li>main holds {@code filesLock} (write) → waits for DeleteFileTask on flush
   *       executor</li>
   *   <li>GC thread holds {@code PageFrame} lock → needs {@code filesLock} (read)</li>
   *   <li>flush executor needs the same {@code PageFrame} lock</li>
   * </ol>
   * Holding {@code stateLock.readLock()} forces the delete path to wait until this
   * method completes, preventing the deadlock.
   */
  public void periodicRecordsGc() {
    if (status != STATUS.OPEN) {
      return;
    }

    // Acquire the state read lock to prevent concurrent storage deletion.
    // If the write lock is already held (storage is being deleted/closed),
    // bail out immediately — there is no point in GC'ing a dying storage.
    if (!stateLock.readLock().tryLock()) {
      return;
    }
    try {
      if (status != STATUS.OPEN) {
        return;
      }

      // Step 1: Opportunistically clean snapshot/visibility indexes.
      try {
        cleanupSnapshotIndex();
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during snapshot index cleanup"
            + " in periodic records GC for storage '%s'", e, name);
      }

      // Step 2: Reclaim dead records from collections that exceed the threshold.
      var contextConfig = configuration.getContextConfiguration();
      int minThreshold = contextConfig
          .getValueAsInteger(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD);
      float scaleFactor = contextConfig
          .getValueAsFloat(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR);

      for (var collection : collections) {
        if (status != STATUS.OPEN) {
          return;
        }
        if (collection instanceof PaginatedCollectionV2 pc
            && pc.isGcTriggered(minThreshold, scaleFactor)) {
          try {
            pc.collectDeadRecords(sharedSnapshotIndex);
          } catch (Exception e) {
            LogManager.instance().error(this, "Error during records GC"
                + " for collection '%s' in storage '%s'", e, pc.getName(), name);
          }
        }
      }
    } finally {
      stateLock.readLock().unlock();
    }
  }

  /**
   * Core eviction logic: removes all visibility/snapshot entries with {@code recordTs} strictly
   * below the given low-water-mark. Extracted as a static method for direct unit testing (same
   * pattern as {@link #computeGlobalLowWaterMark(Set, long)}).
   *
   * <p>The {@code lwm} parameter is always a concrete upper bound — either an active
   * transaction's snapshot timestamp or {@code idGen.getLastId()} when no transactions are
   * active. It is never {@code Long.MAX_VALUE} in normal operation because
   * {@link #computeGlobalLowWaterMark()} falls back to {@code idGen.getLastId()} when all
   * threads are idle.
   *
   * <p>Delegates to
   * {@link #evictStaleSnapshotEntries(long, ConcurrentSkipListMap, ConcurrentSkipListMap,
   * AtomicLong, List)} with a {@code null} collections list (no dead record counting).
   *
   * @param lwm the global low-water-mark; entries with {@code recordTs < lwm} are evicted
   * @param snapshotIndex the shared snapshot index to remove stale entries from
   * @param visibilityIdx the visibility index to scan and remove stale entries from
   * @param sizeCounter   approximate size counter to decrement for each evicted snapshot entry
   */
  static void evictStaleSnapshotEntries(
      long lwm,
      ConcurrentSkipListMap<SnapshotKey, PositionEntry> snapshotIndex,
      ConcurrentSkipListMap<VisibilityKey, SnapshotKey> visibilityIdx,
      @Nonnull AtomicLong sizeCounter) {
    evictStaleSnapshotEntries(lwm, snapshotIndex, visibilityIdx, sizeCounter, null);
  }

  /**
   * Core eviction logic: removes all visibility/snapshot entries with {@code recordTs} strictly
   * below the given low-water-mark, and optionally increments per-collection dead record
   * counters.
   *
   * <p>When {@code collections} is non-null, each evicted snapshot entry increments the
   * {@link PaginatedCollectionV2#deadRecordCount} of the corresponding collection (looked up
   * by {@link SnapshotKey#componentId()}). This feeds the records GC trigger condition.
   *
   * @param lwm the global low-water-mark; entries with {@code recordTs < lwm} are evicted
   * @param snapshotIndex the shared snapshot index to remove stale entries from
   * @param visibilityIdx the visibility index to scan and remove stale entries from
   * @param sizeCounter   approximate size counter to decrement for each evicted snapshot entry
   * @param collections   storage collections indexed by collection id; nullable for unit tests
   *                      or callers that do not need dead record counting
   */
  static void evictStaleSnapshotEntries(
      long lwm,
      ConcurrentSkipListMap<SnapshotKey, PositionEntry> snapshotIndex,
      ConcurrentSkipListMap<VisibilityKey, SnapshotKey> visibilityIdx,
      @Nonnull AtomicLong sizeCounter,
      @Nullable List<StorageCollection> collections) {
    // Sentinel key: (lwm, MIN, MIN) is the smallest possible key with recordTs==lwm,
    // so headMap(exclusive) captures everything with recordTs < lwm.
    var staleEntries = visibilityIdx.headMap(
        new VisibilityKey(lwm, Integer.MIN_VALUE, Long.MIN_VALUE), false);
    var iterator = staleEntries.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var snapshotKey = entry.getValue();
      if (snapshotIndex.remove(snapshotKey) != null) {
        sizeCounter.decrementAndGet();
        // Increment the per-collection dead record counter so the records GC
        // trigger condition can detect when enough dead records have accumulated.
        if (collections != null) {
          int id = snapshotKey.componentId();
          if (id >= 0 && id < collections.size()) {
            var coll = collections.get(id);
            if (coll instanceof PaginatedCollectionV2 pc) {
              pc.incrementDeadRecordCount();
            }
          }
        }
      }
      iterator.remove();
    }
  }

  /**
   * Core eviction logic for edge snapshot entries: removes all edge visibility/snapshot entries
   * with {@code recordTs} strictly below the given low-water-mark. Follows the same pattern as
   * {@link #evictStaleSnapshotEntries} but for edge-specific key types and without dead record
   * counting (edge snapshots do not participate in records GC).
   *
   * @param lwm the global low-water-mark; entries with {@code recordTs < lwm} are evicted
   * @param edgeSnapshotIndex the shared edge snapshot index to remove stale entries from
   * @param edgeVisibilityIdx the edge visibility index to scan and remove stale entries from
   * @param sizeCounter approximate size counter to decrement for each evicted snapshot entry
   */
  static void evictStaleEdgeSnapshotEntries(
      long lwm,
      ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> edgeSnapshotIndex,
      ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> edgeVisibilityIdx,
      @Nonnull AtomicLong sizeCounter) {
    // Sentinel key: (lwm, MIN, MIN, MIN, MIN) is the smallest possible key with recordTs==lwm,
    // so headMap(exclusive) captures everything with recordTs < lwm.
    var staleEntries = edgeVisibilityIdx.headMap(
        new EdgeVisibilityKey(lwm, Integer.MIN_VALUE, Long.MIN_VALUE,
            Integer.MIN_VALUE, Long.MIN_VALUE),
        false);
    var iterator = staleEntries.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var edgeSnapshotKey = entry.getValue();
      if (edgeSnapshotIndex.remove(edgeSnapshotKey) != null) {
        sizeCounter.decrementAndGet();
      }
      iterator.remove();
    }
  }

  /**
   * Core eviction logic for index snapshot entries: removes all index snapshot/version entries
   * with version strictly below the given low-water-mark. Follows the same pattern as
   * {@link #evictStaleSnapshotEntries} and {@link #evictStaleEdgeSnapshotEntries} but for
   * index-specific key types. Operates on the raw maps owned by AbstractStorage.
   *
   * @param lwm the global low-water-mark; entries with version < lwm are evicted
   * @param snapshotData the index snapshot data map to remove stale entries from
   * @param versionIdx the version index to scan and remove stale entries from
   * @param sizeCounter approximate entry count to decrement (2 per evicted pair)
   */
  static void evictStaleIndexesSnapshotEntries(
      long lwm,
      ConcurrentSkipListMap<CompositeKey, RID> snapshotData,
      ConcurrentSkipListMap<CompositeKey, CompositeKey> versionIdx,
      AtomicLong sizeCounter) {
    if (lwm == Long.MAX_VALUE) {
      return;
    }
    // Sentinel: a CompositeKey whose last element is LWM, with minimal preceding keys
    // so that headMap captures everything with version < LWM
    var sentinel = new CompositeKey(Long.MIN_VALUE, lwm);
    var stale = versionIdx.headMap(sentinel);
    long evicted = 0;
    // Use iterator.remove() instead of stale.clear() to avoid a race with
    // concurrent addSnapshotPair(): clear() removes ALL entries currently in the
    // live headMap view, including entries added after the for-loop iterator
    // passed them. That would orphan the corresponding snapshotData entries
    // (never cleaned up → slow memory leak). With iterator.remove(), only
    // entries whose snapshotData was already cleaned are removed from versionIdx.
    for (var it = stale.entrySet().iterator(); it.hasNext();) {
      var entry = it.next();
      snapshotData.remove(entry.getKey());
      snapshotData.remove(entry.getValue());
      it.remove();
      evicted += 2;
    }
    if (evicted > 0) {
      // Clamp to zero: concurrent clear() and eviction may both decrement
      // for the same entries (ConcurrentSkipListMap.remove is idempotent
      // but both callers counted entries during their own iteration pass).
      final long delta = evicted;
      sizeCounter.updateAndGet(current -> Math.max(0, current - delta));
    }
  }

  /**
   * Starts the stale transaction monitor if enabled in the configuration. Called once when
   * the storage transitions to OPEN status.
   */
  private void startStaleTransactionMonitor() {
    if (configuration == null) {
      return;
    }
    var ctx = configuration.getContextConfiguration();
    if (!ctx.getValueAsBoolean(GlobalConfiguration.STORAGE_TX_MONITOR_ENABLED)) {
      return;
    }
    var monitor = new StaleTransactionMonitor(
        name, tsMins, snapshotIndexSize, idGen,
        YouTrackDBEnginesManager.instance().getTicker(),
        ctx,
        YouTrackDBEnginesManager.instance().getMetricsRegistry());
    this.staleTransactionMonitor = monitor;
    monitor.start(YouTrackDBEnginesManager.instance().getScheduledPool());
  }

  /**
   * Stops the stale transaction monitor if running. Called during shutdown.
   */
  private void stopStaleTransactionMonitor() {
    var monitor = staleTransactionMonitor;
    if (monitor != null) {
      monitor.stop();
      staleTransactionMonitor = null;
    }
  }

  public IndexesSnapshot subIndexSnapshot(long indexId) {
    return new IndexesSnapshot(
        sharedIndexesSnapshot, indexesSnapshotVisibilityIndex, indexesSnapshotEntriesCount,
        indexId);
  }

  public IndexesSnapshot subNullIndexSnapshot(long indexId) {
    return new IndexesSnapshot(
        sharedNullIndexesSnapshot, nullIndexSnapshotVisibilityIndex, indexesSnapshotEntriesCount,
        indexId);
  }

  /**
   * Resolves the sub-{@link IndexesSnapshot} for an index engine by its name.
   * Used by {@code BTree} during tombstone GC to check for active snapshot entries.
   *
   * @return the scoped snapshot, or {@code null} if no engine with this name exists
   */
  @Nullable public IndexesSnapshot getIndexSnapshotByEngineName(String engineName) {
    stateLock.readLock().lock();
    try {
      var engine = indexEngineNameMap.get(engineName);
      if (engine == null) {
        return null;
      }
      return subIndexSnapshot(engine.getId());
    } finally {
      stateLock.readLock().unlock();
    }
  }

  /**
   * Resolves the null-key sub-{@link IndexesSnapshot} for an index engine by its name.
   * Currently used only by test infrastructure for snapshot cleanup; reserved for
   * future null-key tree GC support (Track 2).
   *
   * @return the scoped null snapshot, or {@code null} if no engine with this name exists
   */
  @Nullable public IndexesSnapshot getNullIndexSnapshotByEngineName(String engineName) {
    stateLock.readLock().lock();
    try {
      var engine = indexEngineNameMap.get(engineName);
      if (engine == null) {
        return null;
      }
      return subNullIndexSnapshot(engine.getId());
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private static final String NULL_TREE_SUFFIX = "$null";

  /**
   * Checks whether any snapshot entries exist for the given user-key prefix with
   * {@code version >= lwm} in the index identified by {@code engineName}.
   * For null-key trees (name ending with {@code "$null"}), the null snapshot is used.
   *
   * <p>Queries the shared {@code ConcurrentSkipListMap} directly without creating
   * an intermediate {@link IndexesSnapshot} instance.
   *
   * <p><b>Note:</b> This method acquires {@code stateLock.readLock()} to resolve
   * the engine name to an ID. If the caller already knows the engine ID (e.g.,
   * from a cached field), prefer {@link #hasActiveIndexSnapshotEntriesById} to
   * avoid the lock acquisition.
   *
   * @param engineName the index engine name (may end with "$null" for null-key trees)
   * @param userKeyPrefix the key prefix (all CompositeKey elements except the version)
   * @param lwm the global low-water mark
   * @return {@code true} if at least one snapshot entry exists with version >= lwm,
   *     or {@code false} if no engine or no matching entries exist
   */
  public boolean hasActiveIndexSnapshotEntries(
      String engineName, CompositeKey userKeyPrefix, long lwm) {
    final String resolvedName;
    final NavigableMap<CompositeKey, RID> snapshotMap;
    if (engineName.endsWith(NULL_TREE_SUFFIX)) {
      resolvedName = engineName.substring(
          0, engineName.length() - NULL_TREE_SUFFIX.length());
      snapshotMap = sharedNullIndexesSnapshot;
    } else {
      resolvedName = engineName;
      snapshotMap = sharedIndexesSnapshot;
    }

    // indexEngineNameMap is a plain HashMap — guard with stateLock.readLock()
    // to avoid racing with concurrent index creation/deletion.
    final long indexId;
    stateLock.readLock().lock();
    try {
      var engine = indexEngineNameMap.get(resolvedName);
      if (engine == null) {
        return false;
      }
      indexId = engine.getId();
    } finally {
      stateLock.readLock().unlock();
    }

    return hasActiveSnapshotEntriesInMap(snapshotMap, indexId, userKeyPrefix, lwm);
  }

  /**
   * Lock-free variant of {@link #hasActiveIndexSnapshotEntries} that accepts a
   * pre-resolved engine ID and a flag to select the snapshot map. This avoids
   * acquiring {@code stateLock.readLock()} for the {@code indexEngineNameMap}
   * lookup, eliminating the lock-ordering inversion when called while holding
   * a BTree component lock.
   *
   * @param indexId the pre-resolved index engine ID
   * @param useNullSnapshot {@code true} to query the null-key snapshot map
   * @param userKeyPrefix the key prefix (all CompositeKey elements except the version)
   * @param lwm the global low-water mark
   * @return {@code true} if at least one snapshot entry exists with version >= lwm
   */
  public boolean hasActiveIndexSnapshotEntriesById(
      long indexId, boolean useNullSnapshot, CompositeKey userKeyPrefix, long lwm) {
    final NavigableMap<CompositeKey, RID> snapshotMap =
        useNullSnapshot ? sharedNullIndexesSnapshot : sharedIndexesSnapshot;
    return hasActiveSnapshotEntriesInMap(snapshotMap, indexId, userKeyPrefix, lwm);
  }

  private static boolean hasActiveSnapshotEntriesInMap(
      NavigableMap<CompositeKey, RID> snapshotMap,
      long indexId,
      CompositeKey userKeyPrefix,
      long lwm) {
    var prefixKeys = userKeyPrefix.getKeys();
    int size = prefixKeys.size();

    var lower = new CompositeKey(size + 2);
    lower.addKey(indexId);
    for (int i = 0; i < size; i++) {
      lower.addKey(prefixKeys.get(i));
    }
    lower.addKey(lwm);

    var upper = new CompositeKey(size + 2);
    upper.addKey(indexId);
    for (int i = 0; i < size; i++) {
      upper.addKey(prefixKeys.get(i));
    }
    upper.addKey(Long.MAX_VALUE);

    return !snapshotMap.subMap(lower, true, upper, true).isEmpty();
  }

  /**
   * Computes the global low-water-mark. Reads {@code idGen.getLastId()} <b>before</b> scanning
   * {@code tsMins} so that any transaction starting after the read has {@code tsMin >= fallback},
   * eliminating the race where a new transaction's {@code tsMin} is lower than the fallback.
   *
   * @return the minimum active {@code tsMin}, or {@code idGen.getLastId()} when no transactions
   *     are active. Never returns {@code Long.MAX_VALUE}.
   */
  public long computeGlobalLowWaterMark() {
    // Read the fallback BEFORE scanning tsMins. Because idGen is monotonic
    // and every new transaction sets tsMin >= idGen.getLastId() at its start
    // time, any transaction that begins after this read will have
    // tsMin >= fallbackLwm.
    long fallbackLwm = idGen.getLastId();
    return computeGlobalLowWaterMark(tsMins, fallbackLwm);
  }

  public ConcurrentSkipListMap<SnapshotKey, PositionEntry> getSharedSnapshotIndex() {
    return sharedSnapshotIndex;
  }

  public ConcurrentSkipListMap<VisibilityKey, SnapshotKey> getVisibilityIndex() {
    return visibilityIndex;
  }

  public AtomicLong getSnapshotIndexSize() {
    return snapshotIndexSize;
  }

  public ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> getSharedEdgeSnapshotIndex() {
    return sharedEdgeSnapshotIndex;
  }

  public ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> getEdgeVisibilityIndex() {
    return edgeVisibilityIndex;
  }

  public AtomicLong getEdgeSnapshotIndexSize() {
    return edgeSnapshotIndexSize;
  }

  public AtomicLong getIndexesSnapshotEntriesCount() {
    return indexesSnapshotEntriesCount;
  }

  /**
   * Computes the global low-water-mark by iterating all registered {@link TsMinHolder}s and
   * returning the minimum {@link TsMinHolder#tsMin} value. Entries with {@code Long.MAX_VALUE}
   * represent idle threads (no active transaction) and are effectively ignored since any real
   * timestamp will be smaller.
   *
   * <p>When no holders are registered or all are idle (minimum is {@code Long.MAX_VALUE}), falls
   * back to {@code currentId}. This ensures that when no transactions are active, the LWM equals
   * the latest generated operation id — all committed record versions have
   * {@code version <= currentId}, and any transaction starting after the caller read
   * {@code currentId} has {@code tsMin >= currentId}, so stale versions with
   * {@code V_new <= currentId} are unreachable.
   *
   * @param tsMins the set of per-thread {@link TsMinHolder} instances
   * @param currentId fallback value when all holders are idle; typically
   *     {@code idGen.getLastId()} read <b>before</b> scanning {@code tsMins}
   * @return the minimum active timestamp, or {@code currentId} when no transactions are active
   */
  static long computeGlobalLowWaterMark(Set<TsMinHolder> tsMins, long currentId) {
    long min = Long.MAX_VALUE;
    // Weakly-consistent iteration over the Guava concurrent weak-key set.
    // No explicit synchronization needed — stale or missing entries are
    // acceptable (same TOCTOU tolerance as the previous synchronized version).
    for (TsMinHolder holder : tsMins) {
      long ts = holder.tsMin;
      if (ts < min) {
        min = ts;
      }
    }
    if (min == Long.MAX_VALUE) {
      min = currentId;
    }
    return min;
  }
}
