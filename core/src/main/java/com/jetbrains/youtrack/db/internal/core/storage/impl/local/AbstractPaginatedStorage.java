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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local;

import com.google.common.util.concurrent.Striped;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ClusterDoesNotExistException;
import com.jetbrains.youtrack.db.api.exception.CommitSerializationException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentCreateException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.HighLevelException;
import com.jetbrains.youtrack.db.api.exception.InvalidDatabaseNameException;
import com.jetbrains.youtrack.db.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.StorageDoesNotExistException;
import com.jetbrains.youtrack.db.api.exception.StorageExistsException;
import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.Stopwatch;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrack.db.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.config.StorageClusterConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfigurationUpdateListener;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBagDeleter;
import com.jetbrains.youtrack.db.internal.core.exception.InternalErrorException;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidInstanceIdException;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.IndexMetadata;
import com.jetbrains.youtrack.db.internal.core.index.Indexes;
import com.jetbrains.youtrack.db.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrack.db.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.query.live.YTLiveQueryMonitorEmbedded;
import com.jetbrains.youtrack.db.internal.core.record.RecordVersionHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrack.db.internal.core.sql.executor.LiveQueryListenerImpl;
import com.jetbrains.youtrack.db.internal.core.storage.IdentifiableStorage;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.ReadRecordResult;
import com.jetbrains.youtrack.db.internal.core.storage.RecordCallback;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCluster;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCluster.ATTRIBUTES;
import com.jetbrains.youtrack.db.internal.core.storage.StorageOperationResult;
import com.jetbrains.youtrack.db.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.PaginatedCluster;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.PaginatedCluster.RECORD_STATUS;
import com.jetbrains.youtrack.db.internal.core.storage.config.ClusterBasedStorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.StorageTransaction;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.NonTxOperationPerformedWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.OperationUnitRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.PaginatedClusterFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageBrokenException;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeBasedRidBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManagerShared;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @since 28.03.13
 */
public abstract class AbstractPaginatedStorage
    implements CheckpointRequestListener,
    IdentifiableStorage,
    BackgroundExceptionListener,
    FreezableStorageComponent,
    PageIsBrokenListener,
    Storage {

  private static final int WAL_RESTORE_REPORT_INTERVAL = 30 * 1000; // milliseconds

  private static final Comparator<RecordOperation> COMMIT_RECORD_OPERATION_COMPARATOR =
      Comparator.comparing(
          o -> o.record.getIdentity());
  public static final ThreadGroup storageThreadGroup;

  protected static final ScheduledExecutorService fuzzyCheckpointExecutor;

  static {
    var parentThreadGroup = Thread.currentThread().getThreadGroup();

    final var parentThreadGroupBackup = parentThreadGroup;

    var found = false;

    while (parentThreadGroup.getParent() != null) {
      if (parentThreadGroup.equals(YouTrackDBEnginesManager.instance().getThreadGroup())) {
        parentThreadGroup = parentThreadGroup.getParent();
        found = true;
        break;
      } else {
        parentThreadGroup = parentThreadGroup.getParent();
      }
    }

    if (!found) {
      parentThreadGroup = parentThreadGroupBackup;
    }

    storageThreadGroup = new ThreadGroup(parentThreadGroup, "YouTrackDB Storage");

    fuzzyCheckpointExecutor =
        ThreadPoolExecutors.newSingleThreadScheduledPool("Fuzzy Checkpoint", storageThreadGroup);
  }

  protected volatile BTreeCollectionManagerShared sbTreeCollectionManager;

  /**
   * Lock is used to atomically update record versions.
   */
  private final Striped<Lock> recordVersionManager = Striped.lazyWeakLock(1024);

  private final Map<String, StorageCluster> clusterMap = new HashMap<>();
  private final List<StorageCluster> clusters = new CopyOnWriteArrayList<>();

  private volatile ThreadLocal<StorageTransaction> transaction;
  private final AtomicBoolean walVacuumInProgress = new AtomicBoolean();

  protected volatile WriteAheadLog writeAheadLog;
  @Nullable
  private StorageRecoverListener recoverListener;

  protected volatile ReadCache readCache;
  protected volatile WriteCache writeCache;

  private volatile RecordConflictStrategy recordConflictStrategy =
      YouTrackDBEnginesManager.instance().getRecordConflictStrategy().getDefaultImplementation();

  protected volatile AtomicOperationsManager atomicOperationsManager;
  private volatile boolean wereNonTxOperationsPerformedInPreviousOpen;
  private final int id;

  private final Map<String, BaseIndexEngine> indexEngineNameMap = new HashMap<>();
  private final List<BaseIndexEngine> indexEngines = new ArrayList<>();
  private final AtomicOperationIdGen idGen = new AtomicOperationIdGen();

  private boolean wereDataRestoredAfterOpen;
  private UUID uuid;
  private volatile byte[] lastMetadata = null;

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

  protected AtomicReference<Throwable> error = new AtomicReference<>(null);
  protected YouTrackDBInternal context;
  private volatile CountDownLatch migration = new CountDownLatch(1);

  private volatile int backupRunning = 0;
  private volatile int ddlRunning = 0;

  protected final Lock backupLock = new ReentrantLock();
  protected final Condition backupIsDone = backupLock.newCondition();

  private final Stopwatch dropDuration;
  private final Stopwatch synchDuration;
  private final Stopwatch shutdownDuration;

  public AbstractPaginatedStorage(
      final String name, final String filePath, final int id, YouTrackDBInternal context) {
    this.context = context;
    this.name = checkName(name);

    url = filePath;

    stateLock = new ScalableRWLock();

    this.id = id;
    sbTreeCollectionManager = new BTreeCollectionManagerShared(this);
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
  public Storage getUnderlying() {
    return this;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getURL() {
    return url;
  }

  @Override
  public void close(DatabaseSessionInternal session) {
    var sessions = sessionCount.decrementAndGet();

    if (sessions < 0) {
      throw new StorageException(name,
          "Amount of closed sessions in storage "
              + name
              + " is bigger than amount of open sessions");
    }
    lastCloseTime = System.currentTimeMillis();
  }

  public long getSessionsCount() {
    return sessionCount.get();
  }

  public long getLastCloseTime() {
    return lastCloseTime;
  }

  @Override
  public boolean dropCluster(DatabaseSessionInternal session, final String iClusterName) {
    return dropCluster(session, getClusterIdByName(iClusterName));
  }

  @Override
  public long countRecords(DatabaseSessionInternal session) {
    long tot = 0;

    for (var c : getClusterInstances()) {
      if (c != null) {
        tot += c.getEntries() - c.getTombstonesCount();
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
  public boolean isAssigningClusterIds() {
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

  private static TreeMap<String, FrontendTransactionIndexChanges> getSortedIndexOperations(
      final FrontendTransaction clientTx) {
    return new TreeMap<>(clientTx.getIndexOperations());
  }

  @Override
  public final void open(
      DatabaseSessionInternal remote, final String iUserName,
      final String iUserPassword,
      final ContextConfiguration contextConfiguration) {
    open(contextConfiguration);
  }

  public final void open(final ContextConfiguration contextConfiguration) {
    checkPageSizeAndRelatedParametersInGlobalConfiguration(name);
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
          transaction = new ThreadLocal<>();

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
              null,
              atomicOperation -> {
                if (ClusterBasedStorageConfiguration.exists(writeCache)) {
                  configuration = new ClusterBasedStorageConfiguration(this);
                  ((ClusterBasedStorageConfiguration) configuration)
                      .load(contextConfiguration, atomicOperation);

                  // otherwise delayed to disk based storage to convert old format to new format.
                }

                initConfiguration(contextConfiguration, atomicOperation);
              });

          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              (atomicOperation) -> {
                var uuid = configuration.getUuid();
                if (uuid == null) {
                  uuid = UUID.randomUUID().toString();
                  configuration.setUuid(atomicOperation, uuid);
                }
                this.uuid = UUID.fromString(uuid);
              });

          checkPageSizeAndRelatedParameters();

          componentsFactory = new CurrentStorageComponentsFactory(configuration);

          sbTreeCollectionManager.load();

          atomicOperationsManager.executeInsideAtomicOperation(null, this::openClusters);
          openIndexes();

          atomicOperationsManager.executeInsideAtomicOperation(
              null,
              (atomicOperation) -> {
                final var cs = configuration.getConflictStrategy();
                if (cs != null) {
                  // SET THE CONFLICT STORAGE STRATEGY FROM THE LOADED CONFIGURATION
                  doSetConflictStrategy(
                      YouTrackDBEnginesManager.instance().getRecordConflictStrategy()
                          .getStrategy(cs),
                      atomicOperation);
                }
                if (lastMetadata == null) {
                  lastMetadata = startupMetadata.txMetadata;
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

          atomicOperationsManager.executeInsideAtomicOperation(null, this::checkRidBagsPresence);
          status = STATUS.OPEN;
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

    final var additionalArgs = new Object[]{getURL(), YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .info(this, "Storage '%s' is opened under YouTrackDB distribution : %s", additionalArgs);
  }

  protected abstract void readIv() throws IOException;

  @SuppressWarnings("unused")
  protected abstract byte[] getIv();

  /**
   * @inheritDoc
   */
  @Override
  public final String getCreatedAtVersion() {
    return configuration.getCreatedAtVersion();
  }

  protected final void openIndexes() {
    final var cf = componentsFactory;
    if (cf == null) {
      throw new StorageException(name, "Storage '" + name + "' is not properly initialized");
    }
    final var indexNames = configuration.indexEngines();
    var counter = 0;

    // avoid duplication of index engine ids
    for (final var indexName : indexNames) {
      final var engineData = configuration.getIndexEngine(indexName, -1);
      if (counter <= engineData.getIndexId()) {
        counter = engineData.getIndexId() + 1;
      }
    }

    for (final var indexName : indexNames) {
      final var engineData = configuration.getIndexEngine(indexName, counter);

      final var engine = Indexes.createIndexEngine(this, engineData);

      engine.load(engineData);

      indexEngineNameMap.put(engineData.getName(), engine);
      while (engineData.getIndexId() >= indexEngines.size()) {
        indexEngines.add(null);
      }
      indexEngines.set(engineData.getIndexId(), engine);
      counter++;
    }
  }

  protected final void openClusters(final AtomicOperation atomicOperation) throws IOException {
    // OPEN BASIC SEGMENTS
    int pos;

    // REGISTER CLUSTER
    final var configurationClusters = configuration.getClusters();
    for (var i = 0; i < configurationClusters.size(); ++i) {
      final var clusterConfig = configurationClusters.get(i);

      if (clusterConfig != null) {
        pos = createClusterFromConfig(clusterConfig);

        try {
          if (pos == -1) {
            clusters.get(i).open(atomicOperation);
          } else {
            clusters.get(pos).open(atomicOperation);
          }
        } catch (final FileNotFoundException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error on loading cluster '"
                      + configurationClusters.get(i).getName()
                      + "' ("
                      + i
                      + "): file not found. It will be excluded from current database '"
                      + name
                      + "'.",
                  e);

          clusterMap.remove(configurationClusters.get(i).getName().toLowerCase());

          setCluster(i, null);
        }
      } else {
        setCluster(i, null);
      }
    }
  }

  private void checkRidBagsPresence(final AtomicOperation operation) {
    for (final var cluster : clusters) {
      if (cluster != null) {
        final var clusterId = cluster.getId();

        if (!BTreeCollectionManagerShared.isComponentPresent(operation, clusterId)) {
          LogManager.instance()
              .info(
                  this,
                  "Cluster with id %d does not have associated rid bag, fixing ...",
                  clusterId);
          sbTreeCollectionManager.createComponent(operation, clusterId);
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
        close(null);
        throw e;
      } catch (final IOException e) {
        close(null);
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

    final var additionalArgs = new Object[]{getURL(), YouTrackDBConstants.getVersion()};
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
    transaction = new ThreadLocal<>();

    preCreateSteps();
    makeStorageDirty();

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        (atomicOperation) -> {
          configuration = new ClusterBasedStorageConfiguration(this);
          ((ClusterBasedStorageConfiguration) configuration)
              .create(atomicOperation, contextConfiguration);
          configuration.setUuid(atomicOperation, uuid.toString());

          componentsFactory = new CurrentStorageComponentsFactory(configuration);

          sbTreeCollectionManager.load();

          status = STATUS.OPEN;

          sbTreeCollectionManager = new BTreeCollectionManagerShared(this);

          // ADD THE METADATA CLUSTER TO STORE INTERNAL STUFF
          doAddCluster(atomicOperation, MetadataDefault.CLUSTER_INTERNAL_NAME);

          ((ClusterBasedStorageConfiguration) configuration)
              .setCreationVersion(atomicOperation, YouTrackDBConstants.getVersion());
          ((ClusterBasedStorageConfiguration) configuration)
              .setPageSize(
                  atomicOperation,
                  GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10);
          ((ClusterBasedStorageConfiguration) configuration)
              .setMaxKeySize(
                  atomicOperation, GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger());

          generateDatabaseInstanceId(atomicOperation);
          clearStorageDirty();
          postCreateSteps();
        });
  }

  protected void generateDatabaseInstanceId(AtomicOperation atomicOperation) {
    ((ClusterBasedStorageConfiguration) configuration)
        .setProperty(atomicOperation, DATABASE_INSTANCE_ID, UUID.randomUUID().toString());
  }

  protected UUID readDatabaseInstanceId() {
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

  private void checkPageSizeAndRelatedParameters() {
    final var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;
    final var maxKeySize = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();

    if (configuration.getPageSize() != -1 && configuration.getPageSize() != pageSize) {
      throw new StorageException(name,
          "Storage is created with value of "
              + configuration.getPageSize()
              + " parameter equal to "
              + GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getKey()
              + " but current value is "
              + pageSize);
    }

    if (configuration.getMaxKeySize() != -1 && configuration.getMaxKeySize() != maxKeySize) {
      throw new StorageException(name,
          "Storage is created with value of "
              + configuration.getMaxKeySize()
              + " parameter equal to "
              + GlobalConfiguration.BTREE_MAX_KEY_SIZE.getKey()
              + " but current value is "
              + maxKeySize);
    }
  }

  @Override
  public final boolean isClosed(DatabaseSessionInternal database) {
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
  public final void close(DatabaseSessionInternal database, final boolean force) {
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

  public boolean check(final boolean verbose, final CommandOutputListener listener) {
    try {
      listener.onMessage("Check of storage is started...");

      stateLock.readLock().lock();
      try {
        final var lockId = atomicOperationsManager.freezeAtomicOperations(null, null);
        try {

          checkOpennessAndMigration();

          final var start = System.currentTimeMillis();

          final var pageErrors =
              writeCache.checkStoredPages(verbose ? listener : null);

          var errors =
              pageErrors.length > 0 ? pageErrors.length + " with errors." : " without errors.";
          listener.onMessage(
              "Check of storage completed in "
                  + (System.currentTimeMillis() - start)
                  + "ms. "
                  + errors);

          return pageErrors.length == 0;
        } finally {
          atomicOperationsManager.releaseAtomicOperations(lockId);
        }
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
  public final int addCluster(DatabaseSessionInternal database, final String clusterName,
      final Object... parameters) {
    try {
      checkBackupRunning();
      stateLock.writeLock().lock();
      try {
        if (clusterMap.containsKey(clusterName)) {
          throw new ConfigurationException(
              database.getDatabaseName(),
              String.format("Cluster with name:'%s' already exists", clusterName));
        }
        checkOpennessAndMigration();

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            null, (atomicOperation) -> doAddCluster(atomicOperation, clusterName));

      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error in creation of new cluster '" + clusterName), e,
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
  public final int addCluster(DatabaseSessionInternal database, final String clusterName,
      final int requestedId) {
    try {
      checkBackupRunning();
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        if (requestedId < 0) {
          throw new ConfigurationException(database.getDatabaseName(),
              "Cluster id must be positive!");
        }
        if (requestedId < clusters.size() && clusters.get(requestedId) != null) {
          throw new ConfigurationException(
              database.getDatabaseName(), "Requested cluster ID ["
              + requestedId
              + "] is occupied by cluster with name ["
              + clusters.get(requestedId).getName()
              + "]");
        }
        if (clusterMap.containsKey(clusterName)) {
          throw new ConfigurationException(
              database.getDatabaseName(),
              String.format("Cluster with name:'%s' already exists", clusterName));
        }

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            null, atomicOperation -> doAddCluster(atomicOperation, clusterName, requestedId));

      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error in creation of new cluster '" + clusterName + "'"), e,
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
  public final boolean dropCluster(DatabaseSessionInternal database, final int clusterId) {
    try {
      checkBackupRunning();
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        if (clusterId < 0 || clusterId >= clusters.size()) {
          throw new IllegalArgumentException(
              "Cluster id '"
                  + clusterId
                  + "' is outside the of range of configured clusters (0-"
                  + (clusters.size() - 1)
                  + ") in database '"
                  + name
                  + "'");
        }

        makeStorageDirty();

        return atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation -> {
              if (dropClusterInternal(atomicOperation, clusterId)) {
                return false;
              }

              ((ClusterBasedStorageConfiguration) configuration)
                  .dropCluster(atomicOperation, clusterId);
              sbTreeCollectionManager.deleteComponentByClusterId(atomicOperation, clusterId);

              return true;
            });
      } catch (final Exception e) {
        throw BaseException.wrapException(
            new StorageException(name, "Error while removing cluster '" + clusterId + "'"), e,
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

  private void checkClusterId(int clusterId) {
    if (clusterId < 0 || clusterId >= clusters.size()) {
      throw new ClusterDoesNotExistException(name,
          "Cluster id '"
              + clusterId
              + "' is outside the of range of configured clusters (0-"
              + (clusters.size() - 1)
              + ") in database '"
              + name
              + "'");
    }
  }

  @Override
  public String getClusterNameById(int clusterId) {
    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        checkClusterId(clusterId);
        final var cluster = clusters.get(clusterId);
        if (cluster == null) {
          throwClusterDoesNotExist(clusterId);
        }

        return cluster.getName();
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
  public long getClusterRecordsSizeById(int clusterId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkClusterId(clusterId);
        final var cluster = clusters.get(clusterId);
        if (cluster == null) {
          throwClusterDoesNotExist(clusterId);
        }

        return cluster.getRecordsSize();
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
  public long getClusterRecordsSizeByName(String clusterName) {
    Objects.requireNonNull(clusterName);

    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = clusterMap.get(clusterName.toLowerCase());
        if (cluster == null) {
          throwClusterDoesNotExist(clusterName);
        }

        return cluster.getRecordsSize();
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
  public String getClusterRecordConflictStrategy(int clusterId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkClusterId(clusterId);
        final var cluster = clusters.get(clusterId);
        if (cluster == null) {
          throwClusterDoesNotExist(clusterId);
        }

        return Optional.ofNullable(cluster.getRecordConflictStrategy())
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
  public boolean isSystemCluster(int clusterId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkClusterId(clusterId);
        final var cluster = clusters.get(clusterId);
        if (cluster == null) {
          throwClusterDoesNotExist(clusterId);
        }

        return cluster.isSystemCluster();
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

  private void throwClusterDoesNotExist(int clusterId) {
    throw new ClusterDoesNotExistException(name,
        "Cluster with id " + clusterId + " does not exist inside of storage " + name);
  }

  private void throwClusterDoesNotExist(String clusterName) {
    throw new ClusterDoesNotExistException(name,
        "Cluster with name `" + clusterName + "` does not exist inside of storage " + name);
  }

  @Override
  public final int getId() {
    return id;
  }

  public UUID getUuid() {
    return uuid;
  }

  @Override
  public final BTreeCollectionManager getSBtreeCollectionManager() {
    return sbTreeCollectionManager;
  }

  public ReadCache getReadCache() {
    return readCache;
  }

  public WriteCache getWriteCache() {
    return writeCache;
  }

  @Override
  public final long count(DatabaseSessionInternal session, final int iClusterId) {
    return count(session, iClusterId, false);
  }

  @Override
  public final long count(DatabaseSessionInternal session, final int clusterId,
      final boolean countTombstones) {
    try {
      if (clusterId == -1) {
        throw new StorageException(name,
            "Cluster Id " + clusterId + " is invalid in database '" + name + "'");
      }

      // COUNT PHYSICAL CLUSTER IF ANY
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = clusters.get(clusterId);
        if (cluster == null) {
          return 0;
        }

        if (countTombstones) {
          return cluster.getEntries();
        }

        return cluster.getEntries() - cluster.getTombstonesCount();
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
  public final long count(DatabaseSessionInternal session, final int[] iClusterIds) {
    return count(session, iClusterIds, false);
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
  public final long count(DatabaseSessionInternal session, final int[] iClusterIds,
      final boolean countTombstones) {
    try {
      long tot = 0;

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        for (final var iClusterId : iClusterIds) {
          if (iClusterId >= clusters.size()) {
            throw new ConfigurationException(
                session.getDatabaseName(),
                "Cluster id " + iClusterId + " was not found in database '" + name + "'");
          }

          if (iClusterId > -1) {
            final var c = clusters.get(iClusterId);
            if (c != null) {
              tot += c.getEntries() - (countTombstones ? 0L : c.getTombstonesCount());
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

  public final StorageOperationResult<PhysicalPosition> createRecord(
      final RecordId rid,
      final byte[] content,
      final int recordVersion,
      final byte recordType,
      final RecordCallback<Long> callback) {
    try {

      final var cluster = doGetAndCheckCluster(rid.getClusterId());
      if (transaction.get() != null) {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        return doCreateRecord(
            atomicOperation, rid, content, recordVersion, recordType, callback, cluster, null);
      }

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        makeStorageDirty();

        return atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation ->
                doCreateRecord(
                    atomicOperation,
                    rid,
                    content,
                    recordVersion,
                    recordType,
                    callback,
                    cluster,
                    null));
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
  public final RecordMetadata getRecordMetadata(DatabaseSessionInternal session,
      final RID rid) {
    try {
      if (rid.isNew()) {
        throw new StorageException(name,
            "Passed record with id " + rid + " is new and cannot be stored.");
      }

      stateLock.readLock().lock();
      try {

        final var cluster = doGetAndCheckCluster(rid.getClusterId());
        checkOpennessAndMigration();

        final var ppos =
            cluster.getPhysicalPosition(new PhysicalPosition(rid.getClusterPosition()));
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

  public Iterator<ClusterBrowsePage> browseCluster(final int clusterId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final int finalClusterId;
        if (clusterId == RID.CLUSTER_ID_INVALID) {
          // GET THE DEFAULT CLUSTER
          throw new StorageException(name, "Cluster Id " + clusterId + " is invalid");
        } else {
          finalClusterId = clusterId;
        }
        return new Iterator<>() {
          @Nullable
          private ClusterBrowsePage page;
          private long lastPos = -1;

          @Override
          public boolean hasNext() {
            if (page == null) {
              page = nextPage(finalClusterId, lastPos);
              if (page != null) {
                lastPos = page.getLastPosition();
              }
            }
            return page != null;
          }

          @Override
          public ClusterBrowsePage next() {
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

  private ClusterBrowsePage nextPage(final int clusterId, final long lastPosition) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = doGetAndCheckCluster(clusterId);
        return cluster.nextPage(lastPosition);
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

  private StorageCluster doGetAndCheckCluster(final int clusterId) {
    checkClusterSegmentIndexRange(clusterId);

    final var cluster = clusters.get(clusterId);
    if (cluster == null) {
      throw new IllegalArgumentException("Cluster " + clusterId + " is null");
    }
    return cluster;
  }

  @Override
  public @Nonnull ReadRecordResult readRecord(
      DatabaseSessionInternal session, final RecordId rid, boolean fetchPreviousRid,
      boolean fetchNextRid) {
    try {
      return readRecord(rid, fetchPreviousRid, fetchNextRid);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  public final void updateRecord(
      final RecordId rid,
      final boolean updateContent,
      final byte[] content,
      final int version,
      final byte recordType,
      @SuppressWarnings("unused") final int mode,
      final RecordCallback<Integer> callback) {
    try {
      assert transaction.get() == null;

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        // GET THE SHARED LOCK AND GET AN EXCLUSIVE LOCK AGAINST THE RECORD
        final var lock = recordVersionManager.get(rid);
        lock.lock();
        try {
          checkOpennessAndMigration();

          makeStorageDirty();

          final var cluster = doGetAndCheckCluster(rid.getClusterId());
          atomicOperationsManager.calculateInsideAtomicOperation(
              null,
              atomicOperation ->
                  doUpdateRecord(
                      atomicOperation,
                      rid,
                      updateContent,
                      content,
                      version,
                      recordType,
                      callback,
                      cluster));
        } finally {
          lock.unlock();
        }
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
  public final Set<String> getClusterNames() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return Collections.unmodifiableSet(clusterMap.keySet());
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
  public final int getClusterIdByName(final String clusterName) {
    try {
      if (clusterName == null) {
        throw new IllegalArgumentException("Cluster name is null");
      }

      if (clusterName.isEmpty()) {
        throw new IllegalArgumentException("Cluster name is empty");
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        // SEARCH IT BETWEEN PHYSICAL CLUSTERS

        final var segment = clusterMap.get(clusterName.toLowerCase());
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
      final var clustersToLock = new TreeMap<Integer, StorageCluster>();

      final Set<RecordOperation> newRecords = new TreeSet<>(COMMIT_RECORD_OPERATION_COMPARATOR);

      for (final var txEntry : entries) {

        if (txEntry.type == RecordOperation.CREATED) {
          newRecords.add(txEntry);
          final var clusterId = txEntry.getRecordId().getClusterId();
          clustersToLock.put(clusterId, doGetAndCheckCluster(clusterId));
        }
      }
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              lockClusters(clustersToLock);

              var session = clientTx.getDatabaseSession();
              for (final var txEntry : newRecords) {
                final var rec = txEntry.record;
                if (!rec.getIdentity().isPersistent()) {
                  if (rec.isDirty()) {
                    // This allocate a position for a new record
                    final var rid = rec.getIdentity();
                    final var oldRID = rid.copy();
                    final var cluster = doGetAndCheckCluster(rid.getClusterId());
                    final var ppos =
                        cluster.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    rid.setClusterPosition(ppos.clusterPosition);
                    assert clientTx.assertIdentityChangedAfterCommit(oldRID, rid);
                  }
                } else {
                  // This allocate position starting from a valid rid, used in distributed for
                  // allocate the same position on other nodes
                  final var rid = rec.getIdentity();
                  final var cluster =
                      (PaginatedCluster) doGetAndCheckCluster(rid.getClusterId());
                  var recordStatus = cluster.getRecordStatus(rid.getClusterPosition());
                  if (recordStatus == RECORD_STATUS.NOT_EXISTENT) {
                    var ppos =
                        cluster.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    while (ppos.clusterPosition < rid.getClusterPosition()) {
                      ppos =
                          cluster.allocatePosition(
                              rec.getRecordType(), atomicOperation);
                    }
                    if (ppos.clusterPosition != rid.getClusterPosition()) {
                      throw new ConcurrentCreateException(session.getDatabaseName(),
                          rid, new RecordId(rid.getClusterId(), ppos.clusterPosition));
                    }
                  } else if (recordStatus == RECORD_STATUS.PRESENT
                      || recordStatus == RECORD_STATUS.REMOVED) {
                    final var ppos =
                        cluster.allocatePosition(
                            rec.getRecordType(), atomicOperation);
                    throw new ConcurrentCreateException(session.getDatabaseName(),
                        rid, new RecordId(rid.getClusterId(), ppos.clusterPosition));
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
   * @param transaction the transaction to commit
   * @param allocated   true if the operation is pre-allocated commit
   * @return The list of operations applied by the transaction
   */
  protected List<RecordOperation> commit(
      final FrontendTransactionImpl transaction, final boolean allocated) {
    // XXX: At this moment, there are two implementations of the commit method. One for regular
    // client transactions and one for
    // implicit micro-transactions. The implementations are quite identical, but operate on slightly
    // different data. If you change
    // this method don't forget to change its counterpart:
    //
    //
    try {
      final var session = transaction.getDatabaseSession();
      final var indexOperations =
          getSortedIndexOperations(transaction);

      session.getMetadata().makeThreadLocalSchemaSnapshot();

      final var recordOperations = transaction.getRecordOperationsInternal();
      final var clustersToLock = new TreeMap<Integer, StorageCluster>();
      final Map<RecordOperation, Integer> clusterOverrides = new IdentityHashMap<>(8);

      final Set<RecordOperation> newRecords = new TreeSet<>(COMMIT_RECORD_OPERATION_COMPARATOR);
      for (final var recordOperation : recordOperations) {
        var record = recordOperation.record;
        if (recordOperation.type == RecordOperation.CREATED
            || recordOperation.type == RecordOperation.UPDATED) {

          if (record.isUnloaded()) {
            throw new IllegalStateException(
                "Unloaded record " + record.getIdentity() + " cannot be committed");
          }

          if (record instanceof EntityImpl) {
            ((EntityImpl) record).validate();
          }
        }

        if (recordOperation.type == RecordOperation.UPDATED
            || recordOperation.type == RecordOperation.DELETED) {
          final var clusterId = recordOperation.record.getIdentity().getClusterId();
          clustersToLock.put(clusterId, doGetAndCheckCluster(clusterId));
        } else if (recordOperation.type == RecordOperation.CREATED) {
          newRecords.add(recordOperation);

          final RID rid = record.getIdentity();

          var clusterId = rid.getClusterId();

          if (record.isDirty()
              && clusterId == RID.CLUSTER_ID_INVALID
              && record instanceof EntityImpl) {
            // TRY TO FIX CLUSTER ID TO THE DEFAULT CLUSTER ID DEFINED IN SCHEMA CLASS

            var cls = ((EntityImpl) record).getImmutableSchemaClass(session);
            if (cls != null) {
              clusterId = cls.getClusterForNewInstance((EntityImpl) record);
              clusterOverrides.put(recordOperation, clusterId);
            }
          }
          clustersToLock.put(clusterId, doGetAndCheckCluster(clusterId));
        }
      }

      final List<RecordOperation> result = new ArrayList<>(8);
      stateLock.readLock().lock();
      try {
        try {
          checkOpennessAndMigration();

          makeStorageDirty();

          Throwable error = null;
          startStorageTx(transaction);
          try {
            final var atomicOperation = atomicOperationsManager.getCurrentOperation();
            lockClusters(clustersToLock);

            final Map<RecordOperation, PhysicalPosition> positions = new IdentityHashMap<>(8);
            for (final var recordOperation : newRecords) {
              final var rec = recordOperation.record;

              if (allocated) {
                if (rec.getIdentity().isPersistent()) {
                  positions.put(
                      recordOperation,
                      new PhysicalPosition(rec.getIdentity().getClusterPosition()));
                } else {
                  throw new StorageException(name,
                      "Impossible to commit a transaction with not valid rid in pre-allocated"
                          + " commit");
                }
              } else if (rec.isDirty() && !rec.getIdentity().isPersistent()) {
                final var rid = rec.getIdentity();
                final var oldRID = rid.copy();

                final var clusterOverride = clusterOverrides.get(recordOperation);
                final int clusterId =
                    Optional.ofNullable(clusterOverride).orElseGet(rid::getClusterId);

                final var cluster = doGetAndCheckCluster(clusterId);

                var physicalPosition =
                    cluster.allocatePosition(
                        rec.getRecordType(),
                        atomicOperation);

                if (rid.getClusterPosition() > -1) {
                  // CREATE EMPTY RECORDS UNTIL THE POSITION IS REACHED. THIS IS THE CASE WHEN A
                  // SERVER IS OUT OF SYNC
                  // BECAUSE A TRANSACTION HAS BEEN ROLLED BACK BEFORE TO SEND THE REMOTE CREATES.
                  // SO THE OWNER NODE DELETED
                  // RECORD HAVING A HIGHER CLUSTER POSITION
                  while (rid.getClusterPosition() > physicalPosition.clusterPosition) {
                    physicalPosition =
                        cluster.allocatePosition(
                            rec.getRecordType(),
                            atomicOperation);
                  }

                  if (rid.getClusterPosition() != physicalPosition.clusterPosition) {
                    throw new ConcurrentCreateException(name,
                        rid, new RecordId(cluster.getId(), physicalPosition.clusterPosition));
                  }
                }
                positions.put(recordOperation, physicalPosition);

                rid.setClusterAndPosition(cluster.getId(), physicalPosition.clusterPosition);
                assert transaction.assertIdentityChangedAfterCommit(oldRID, rid);
              }
            }
            lockRidBags(clustersToLock);

            for (final var recordOperation : recordOperations) {
              commitEntry(
                  transaction,
                  atomicOperation,
                  recordOperation,
                  positions.get(recordOperation),
                  session.getSerializer());
              result.add(recordOperation);
            }
            lockIndexes(indexOperations);

            commitIndexes(transaction.getDatabaseSession(), indexOperations);
          } catch (final IOException | RuntimeException e) {
            error = e;
            if (e instanceof RuntimeException) {
              throw ((RuntimeException) e);
            } else {
              throw BaseException.wrapException(
                  new StorageException(name, "Error during transaction commit"), e, name);
            }
          } finally {
            if (error != null) {
              rollback(error);
            } else {
              endStorageTx();
            }
            this.transaction.set(null);
          }
        } finally {
          atomicOperationsManager.ensureThatComponentsUnlocked();
          session.getMetadata().clearThreadLocalSchemaSnapshot();
        }
      } finally {
        stateLock.readLock().unlock();
      }

      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "%d Committed transaction %d on database '%s' (result=%s)",
                Thread.currentThread().getId(),
                transaction.getId(),
                session.getDatabaseName(),
                result);
      }
      return result;
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      atomicOperationsManager.alarmClearOfAtomicOperation();
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void commitIndexes(DatabaseSessionInternal db,
      final Map<String, FrontendTransactionIndexChanges> indexesToCommit) {
    for (final var changes : indexesToCommit.values()) {
      var index = changes.getIndex();
      try {
        if (changes.cleared) {
          clearIndex(index.getIndexId());
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

  private void applyTxChanges(DatabaseSessionInternal session,
      FrontendTransactionIndexChangesPerKey changes, Index index)
      throws InvalidIndexEngineIdException {
    assert !(changes.key instanceof RID orid) || orid.isPersistent();
    for (var op : index.interpretTxKeyChanges(changes)) {
      switch (op.getOperation()) {
        case PUT:
          index.doPut(session, this, changes.key, op.getValue().getIdentity());
          break;
        case REMOVE:
          if (op.getValue() != null) {
            index.doRemove(session, this, changes.key, op.getValue().getIdentity());
          } else {
            index.doRemove(this, changes.key, session);
          }
          break;
        case CLEAR:
          // SHOULD NEVER BE THE CASE HANDLE BY cleared FLAG
          break;
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
      final IndexMetadata indexMetadata, final Map<String, String> engineProperties) {
    final var indexDefinition = indexMetadata.getIndexDefinition();
    try {
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        // this method introduced for binary compatibility only
        if (configuration.getBinaryFormatVersion() > 15) {
          return -1;
        }
        if (indexEngineNameMap.containsKey(indexMetadata.getName())) {
          throw new IndexException(name,
              "Index with name " + indexMetadata.getName() + " already exists");
        }
        makeStorageDirty();

        final var valueSerializerId = StreamSerializerRID.INSTANCE.getId();

        final var keySerializer = determineKeySerializer(indexDefinition);
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

        engine.load(engineData);

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              indexEngineNameMap.put(indexMetadata.getName(), engine);
              indexEngines.add(engine);
              ((ClusterBasedStorageConfiguration) configuration)
                  .addIndexEngine(atomicOperation, indexMetadata.getName(), engineData);
            });
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

      final var keySerializer = determineKeySerializer(indexDefinition);
      if (keySerializer == null) {
        throw new IndexException(name, "Can not determine key serializer");
      }

      final var keySize = determineKeySize(indexDefinition);

      checkBackupRunning();
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation -> {
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
                  ((ClusterBasedStorageConfiguration) configuration)
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
              indexEngineNameMap.put(indexMetadata.getName(), engine);
              indexEngines.add(engine);

              ((ClusterBasedStorageConfiguration) configuration)
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

  private BinarySerializer<?> determineKeySerializer(final IndexDefinition indexDefinition) {
    if (indexDefinition == null) {
      throw new StorageException(name, "Index definition has to be provided");
    }

    final var keyTypes = indexDefinition.getTypes();
    if (keyTypes == null || keyTypes.length == 0) {
      throw new StorageException(name, "Types of index keys has to be defined");
    }
    if (keyTypes.length < indexDefinition.getFields().size()) {
      throw new StorageException(name,
          "Types are provided only for "
              + keyTypes.length
              + " fields. But index definition has "
              + indexDefinition.getFields().size()
              + " fields.");
    }

    final BinarySerializer<?> keySerializer;
    if (indexDefinition.getTypes().length > 1) {
      keySerializer = CompositeKeySerializer.INSTANCE;
    } else {
      final var keyType = indexDefinition.getTypes()[0];

      if (keyType == PropertyTypeInternal.STRING && configuration.getBinaryFormatVersion() >= 13) {
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
      checkBackupRunning();
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        checkIndexId(internalIndexId);

        makeStorageDirty();

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation -> {
              final var engine =
                  deleteIndexEngineInternal(atomicOperation, internalIndexId);
              final var engineName = engine.getName();
              ((ClusterBasedStorageConfiguration) configuration)
                  .deleteIndexEngine(atomicOperation, engineName);
            });

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

  private BaseIndexEngine deleteIndexEngineInternal(
      final AtomicOperation atomicOperation, final int indexId)
      throws IOException {
    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();
    indexEngines.set(indexId, null);
    engine.delete(atomicOperation);

    final var engineName = engine.getName();
    indexEngineNameMap.remove(engineName);
    return engine;
  }

  private void checkIndexId(final int indexId) throws InvalidIndexEngineIdException {
    if (indexId < 0 || indexId >= indexEngines.size() || indexEngines.get(indexId) == null) {
      throw new InvalidIndexEngineIdException(
          "Engine with id " + indexId + " is not registered inside of storage");
    }
  }

  public boolean removeKeyFromIndex(final int indexId, final Object key)
      throws InvalidIndexEngineIdException {
    final var internalIndexId = extractInternalId(indexId);

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
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

  public void clearIndex(final int indexId)
      throws InvalidIndexEngineIdException {
    try {
      if (transaction.get() != null) {
        final var atomicOperation = atomicOperationsManager.getCurrentOperation();
        doClearIndex(atomicOperation, indexId);
        return;
      }

      final var internalIndexId = extractInternalId(indexId);
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();

        atomicOperationsManager.executeInsideAtomicOperation(
            null, atomicOperation -> doClearIndex(atomicOperation, internalIndexId));
      } finally {
        stateLock.readLock().unlock();
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

  public Object getIndexValue(DatabaseSessionInternal db, int indexId, final Object key)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);
    try {
      if (transaction.get() != null) {
        return doGetIndexValue(db, indexId, key);
      }
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();
        return doGetIndexValue(db, indexId, key);
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

  private Object doGetIndexValue(DatabaseSessionInternal db, final int indexId,
      final Object key)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    if (engineAPIVersion != 0) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 0 but found " + engineAPIVersion);
    }
    checkIndexId(indexId);
    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();
    return ((IndexEngine) engine).get(db, key);
  }

  public Stream<RID> getIndexValues(int indexId, final Object key)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    indexId = extractInternalId(indexId);

    try {

      if (transaction.get() != null) {
        return doGetIndexValues(indexId, key);
      }

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        return doGetIndexValues(indexId, key);
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

  private Stream<RID> doGetIndexValues(final int indexId, final Object key)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return ((V1IndexEngine) engine).get(key);
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

  @Nullable
  public <T> T callIndexEngine(
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

        return doCallIndexEngine(indexId, callback);
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

  private <T> T doCallIndexEngine(final int indexId, final IndexEngineCallback<T> callback)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);

    return callback.callEngine(engine);
  }

  public void putRidIndexEntry(int indexId, final Object key, final RID value)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    final var internalIndexId = extractInternalId(indexId);

    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
      assert atomicOperation != null;
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

  public boolean removeRidIndexEntry(int indexId, final Object key, final RID value)
      throws InvalidIndexEngineIdException {
    final var engineAPIVersion = extractEngineAPIVersion(indexId);
    final var internalIndexId = extractInternalId(indexId);

    if (engineAPIVersion != 1) {
      throw new IllegalStateException(
          "Unsupported version of index engine API. Required 1 but found " + engineAPIVersion);
    }

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
      assert atomicOperation != null;
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
   * @return {@code true} if the validator allowed the put, {@code false} otherwise.
   * @see IndexEngineValidator#validate(Object, Object, Object)
   */
  @SuppressWarnings("UnusedReturnValue")
  public boolean validatedPutIndexValue(
      final int indexId,
      final Object key,
      final RID value,
      final IndexEngineValidator<Object, RID> validator)
      throws InvalidIndexEngineIdException {
    final var internalIndexId = extractInternalId(indexId);

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
      assert atomicOperation != null;
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

      if (engine instanceof IndexEngine) {
        return ((IndexEngine) engine).validatedPut(atomicOperation, key, value, validator);
      }

      if (engine instanceof SingleValueIndexEngine) {
        return ((SingleValueIndexEngine) engine)
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
      DatabaseSessionInternal db, int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesBetween(db,
            indexId, rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesBetween(db,
            indexId, rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer);
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
      DatabaseSessionInternal db, final int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesBetween(db
        , rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer);
  }

  public Stream<RawPair<Object, RID>> iterateIndexEntriesMajor(
      int indexId,
      final Object fromKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesMajor(indexId, fromKey, isInclusive, ascSortOrder, transformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesMajor(indexId, fromKey, isInclusive, ascSortOrder, transformer);
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
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder, transformer);
  }

  public Stream<RawPair<Object, RID>> iterateIndexEntriesMinor(
      int indexId,
      final Object toKey,
      final boolean isInclusive,
      final boolean ascSortOrder,
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesMinor(indexId, toKey, isInclusive, ascSortOrder, transformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesMinor(indexId, toKey, isInclusive, ascSortOrder, transformer);
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
      final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMinor(toKey, isInclusive, ascSortOrder, transformer);
  }

  public Stream<RawPair<Object, RID>> getIndexStream(
      int indexId, final IndexEngineValuesTransformer valuesTransformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexStream(indexId, valuesTransformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexStream(indexId, valuesTransformer);
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
      final int indexId, final IndexEngineValuesTransformer valuesTransformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.stream(valuesTransformer);
  }

  public Stream<RawPair<Object, RID>> getIndexDescStream(
      int indexId, final IndexEngineValuesTransformer valuesTransformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexDescStream(indexId, valuesTransformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexDescStream(indexId, valuesTransformer);
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
      final int indexId, final IndexEngineValuesTransformer valuesTransformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.descStream(valuesTransformer);
  }

  public Stream<Object> getIndexKeyStream(int indexId) throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexKeyStream(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexKeyStream(indexId);
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

  private Stream<Object> doGetIndexKeyStream(final int indexId)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.keyStream();
  }

  public long getIndexSize(int indexId, final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexSize(indexId, transformer);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexSize(indexId, transformer);
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

  private long doGetIndexSize(final int indexId, final IndexEngineValuesTransformer transformer)
      throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.size(this, transformer);
  }

  public boolean hasIndexRangeQuerySupport(int indexId) throws InvalidIndexEngineIdException {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doHasRangeQuerySupport(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doHasRangeQuerySupport(indexId);
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

  private boolean doHasRangeQuerySupport(final int indexId) throws InvalidIndexEngineIdException {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.hasRangeQuerySupport();
  }

  private void rollback(final Throwable error) throws IOException {
    assert transaction.get() != null;
    atomicOperationsManager.endAtomicOperation(error);

    assert atomicOperationsManager.getCurrentOperation() == null;
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
  public final boolean checkForRecordValidity(final PhysicalPosition ppos) {
    try {
      return ppos != null && !RecordVersionHelper.isTombstone(ppos.recordVersion);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  @Override
  public final void synch() {
    try {
      stateLock.readLock().lock();
      try {

        final var synchStartedAt = System.nanoTime();
        final var lockId = atomicOperationsManager.freezeAtomicOperations(null, null);
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
          atomicOperationsManager.releaseAtomicOperations(lockId);
          synchDuration.setNanos(System.nanoTime() - synchStartedAt);
        }
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
  public final String getPhysicalClusterNameById(final int iClusterId) {
    try {
      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();

        if (iClusterId < 0 || iClusterId >= clusters.size()) {
          return null;
        }

        return clusters.get(iClusterId) != null ? clusters.get(iClusterId).getName() : null;
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
  public String getClusterName(DatabaseSessionInternal database, int clusterId) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      if (clusterId == RID.CLUSTER_ID_INVALID) {
        throw new StorageException(name, "Invalid cluster id was provided: " + clusterId);
      }

      return doGetAndCheckCluster(clusterId).getName();

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

  @Override
  public final long getSize(DatabaseSessionInternal session) {
    try {
      try {
        long size = 0;

        stateLock.readLock().lock();
        try {

          checkOpennessAndMigration();

          for (final var c : clusters) {
            if (c != null) {
              size += c.getRecordsSize();
            }
          }
        } finally {
          stateLock.readLock().unlock();
        }

        return size;
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name, "Cannot calculate records size"),
            ioe, name);
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
  public final int getClusters() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return clusterMap.size();
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
  public final Set<StorageCluster> getClusterInstances() {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final Set<StorageCluster> result = new HashSet<>(1024);

        // ADD ALL THE CLUSTERS
        for (final var c : clusters) {
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
  public final void freeze(DatabaseSessionInternal db, final boolean throwException) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        if (throwException) {
          atomicOperationsManager.freezeAtomicOperations(
              ModificationOperationProhibitedException.class,
              "Modification requests are prohibited");
        } else {
          atomicOperationsManager.freezeAtomicOperations(null, null);
        }

        final List<FreezableStorageComponent> frozenIndexes = new ArrayList<>(indexEngines.size());
        try {
          for (final var indexEngine : indexEngines) {
            if (indexEngine instanceof FreezableStorageComponent) {
              ((FreezableStorageComponent) indexEngine).freeze(db, false);
              frozenIndexes.add((FreezableStorageComponent) indexEngine);
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

        synch();
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
  public final void release(DatabaseSessionInternal db) {
    try {
      for (final var indexEngine : indexEngines) {
        if (indexEngine instanceof FreezableStorageComponent) {
          ((FreezableStorageComponent) indexEngine).release(db);
        }
      }

      atomicOperationsManager.releaseAtomicOperations(-1);
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
  public final void reload(DatabaseSessionInternal database) {
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

  @SuppressWarnings("unused")
  public static String getMode() {
    return "rw";
  }

  /**
   * @inheritDoc
   */
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
  public final void requestCheckpoint() {
    try {
      if (!walVacuumInProgress.get() && walVacuumInProgress.compareAndSet(false, true)) {
        fuzzyCheckpointExecutor.submit(new WALVacuum(this));
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
      DatabaseSessionInternal session, final int currentClusterId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentClusterId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = doGetAndCheckCluster(currentClusterId);
        return cluster.higherPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cluster Id " + currentClusterId + " is invalid in storage '" + name + '\''),
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
      DatabaseSessionInternal session, final int clusterId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (clusterId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = doGetAndCheckCluster(clusterId);
        return cluster.ceilingPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cluster Id " + clusterId + " is invalid in storage '" + name + '\''),
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
      DatabaseSessionInternal session, final int currentClusterId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentClusterId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var cluster = doGetAndCheckCluster(currentClusterId);

        return cluster.lowerPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cluster Id " + currentClusterId + " is invalid in storage '" + name + '\''),
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
      DatabaseSessionInternal session, final int clusterId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (clusterId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();
        final var cluster = doGetAndCheckCluster(clusterId);

        return cluster.floorPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cluster Id " + clusterId + " is invalid in storage '" + name + '\''),
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
          null, atomicOperation -> doSetConflictStrategy(conflictResolver, atomicOperation));
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
      ((ClusterBasedStorageConfiguration) configuration)
          .setConflictStrategy(atomicOperation, conflictResolver.getName());
    }
  }

  @SuppressWarnings("unused")
  protected abstract LogSequenceNumber copyWALToIncrementalBackup(
      ZipOutputStream zipOutputStream, long startSegment) throws IOException;

  @SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
  protected abstract boolean isWriteAllowedDuringIncrementalBackup();

  @Nullable
  @SuppressWarnings("unused")
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
    checkErrorState();

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

      if (status != STATUS.OPEN || status != STATUS.MIGRATION) {
        return;
      }
    }

    try {

      if (status != STATUS.OPEN || status != STATUS.MIGRATION) {
        return;
      }

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
              minLSNSegment,
              beginLSN,
              endLSN,
              fuzzySegment);

      if (fuzzySegment > beginLSN.getSegment() && beginLSN.getSegment() < endLSN.getSegment()) {
        LogManager.instance().debug(this, "Making fuzzy checkpoint");
        writeCache.syncDataFiles(fuzzySegment, lastMetadata);

        beginLSN = writeAheadLog.begin();
        endLSN = writeAheadLog.end();

        LogManager.instance()
            .debug(this, "After fuzzy checkpoint: WAL begin is %s WAL end is %s", beginLSN, endLSN);
      } else {
        LogManager.instance().debug(this, "No reason to make fuzzy checkpoint");
      }
    } catch (final IOException ioe) {
      throw BaseException.wrapException(new YTIOException("Error during fuzzy checkpoint"), ioe,
          name);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void deleteTreeRidBag(final BTreeBasedRidBag ridBag) {
    try {
      checkOpennessAndMigration();

      assert transaction.get() != null;
      deleteTreeRidBag(ridBag, atomicOperationsManager.getCurrentOperation());
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void deleteTreeRidBag(BTreeBasedRidBag ridBag, AtomicOperation atomicOperation) {
    final var collectionPointer = ridBag.getCollectionPointer();
    checkOpennessAndMigration();

    try {
      makeStorageDirty();
      sbTreeCollectionManager.delete(atomicOperation, collectionPointer, name);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error during deletion of rid bag", e);
      throw BaseException.wrapException(
          new StorageException(name, "Error during deletion of ridbag"),
          e, name);
    }

    ridBag.confirmDelete();
  }

  protected void flushAllData() {
    try {
      writeAheadLog.flush();

      // so we will be able to cut almost all the log
      writeAheadLog.appendNewSegment();

      final LogSequenceNumber lastLSN;
      if (lastMetadata != null) {
        lastLSN = writeAheadLog.log(new MetaDataRecord(lastMetadata));
      } else {
        lastLSN = writeAheadLog.log(new EmptyWALRecord());
      }

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
    return new StartupMetadata(-1, null);
  }

  protected void initConfiguration(
      final ContextConfiguration contextConfiguration,
      AtomicOperation atomicOperation)
      throws IOException {
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

  protected String getOpenedAtVersion() {
    return null;
  }

  @Nonnull
  private ReadRecordResult readRecord(final RecordId rid, boolean fetchPreviousRid,
      boolean fetchNextRid) {
    if (!rid.isPersistent()) {
      throw new RecordNotFoundException(name,
          rid, "Cannot read record "
          + rid
          + " since the position is invalid in database '"
          + name
          + '\'');
    }

    if (transaction.get() != null) {
      checkOpennessAndMigration();
      final StorageCluster cluster;
      try {
        cluster = doGetAndCheckCluster(rid.getClusterId());
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(new RecordNotFoundException(name, rid), e, name);
      }
      // Disabled this assert have no meaning anymore
      // assert iLockingStrategy.equals(LOCKING_STRATEGY.DEFAULT);
      return doReadRecord(cluster, rid, fetchPreviousRid, fetchNextRid);
    }

    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();
      final StorageCluster cluster;
      try {
        cluster = doGetAndCheckCluster(rid.getClusterId());
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(new RecordNotFoundException(name, rid), e, name);
      }
      return doReadRecord(cluster, rid, fetchPreviousRid, fetchNextRid);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public boolean recordExists(DatabaseSessionInternal session, RID rid) {
    if (!rid.isPersistent()) {
      throw new RecordNotFoundException(name,
          rid, "Cannot read record "
          + rid
          + " since the position is invalid in database '"
          + name
          + '\'');
    }

    if (transaction.get() != null) {
      checkOpennessAndMigration();
      final StorageCluster cluster;
      try {
        cluster = doGetAndCheckCluster(rid.getClusterId());
      } catch (IllegalArgumentException e) {
        return false;
      }

      return doRecordExists(name, cluster, rid);
    }

    stateLock.readLock().lock();
    try {
      checkOpennessAndMigration();
      final StorageCluster cluster;
      try {
        cluster = doGetAndCheckCluster(rid.getClusterId());
      } catch (IllegalArgumentException e) {
        return false;
      }
      return doRecordExists(name, cluster, rid);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void endStorageTx() throws IOException {
    atomicOperationsManager.endAtomicOperation(null);
    assert atomicOperationsManager.getCurrentOperation() == null;
  }

  private void startStorageTx(final FrontendTransaction clientTx) throws IOException {
    final var storageTx = transaction.get();
    assert storageTx == null || storageTx.getClientTx().getId() == clientTx.getId();
    assert atomicOperationsManager.getCurrentOperation() == null;
    transaction.set(new StorageTransaction(clientTx));
    try {
      final var atomicOperation =
          atomicOperationsManager.startAtomicOperation(clientTx.getMetadata());
      if (clientTx.getMetadata() != null) {
        this.lastMetadata = clientTx.getMetadata();
      }
      clientTx.storageBegun();
      var ops = clientTx.getSerializedOperations();
      while (ops.hasNext()) {
        var next = ops.next();
        writeAheadLog.log(
            new HighLevelTransactionChangeRecord(atomicOperation.getOperationUnitId(), next));
      }
    } catch (final RuntimeException e) {
      transaction.set(null);
      throw e;
    }
  }

  public void metadataOnly(byte[] metadata) {
    try {
      atomicOperationsManager.executeInsideAtomicOperation(metadata, (op) -> {
      });
      this.lastMetadata = metadata;
    } catch (IOException e) {
      throw logAndPrepareForRethrow(e);
    }
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
        restoreFromWAL();

        if (recoverListener != null) {
          recoverListener.onStorageRecover();
        }

        flushAllData();
      } catch (final Exception e) {
        LogManager.instance().error(this, "Exception during storage data restore", e);
        throw e;
      }

      LogManager.instance().info(this, "Storage data recover was completed");
    }
  }

  private StorageOperationResult<PhysicalPosition> doCreateRecord(
      final AtomicOperation atomicOperation,
      final RecordId rid,
      @Nonnull final byte[] content,
      int recordVersion,
      final byte recordType,
      final RecordCallback<Long> callback,
      final StorageCluster cluster,
      final PhysicalPosition allocated) {
    //noinspection ConstantValue
    if (content == null) {
      throw new IllegalArgumentException("Record is null");
    }

    if (recordVersion > -1) {
      recordVersion++;
    } else {
      recordVersion = 0;
    }

    cluster.meters().create().record();
    PhysicalPosition ppos;
    try {
      ppos = cluster.createRecord(content, recordVersion, recordType, allocated, atomicOperation);
      rid.setClusterPosition(ppos.clusterPosition);

      final var context = RecordSerializationContext.getContext();
      if (context != null) {
        context.executeOperations(atomicOperation, this);
      }
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error on creating record in cluster: " + cluster, e);
      throw DatabaseException.wrapException(
          new StorageException(name, "Error during creation of record"), e, name);
    }

    if (callback != null) {
      callback.call(rid, ppos.clusterPosition);
    }

    if (LogManager.instance().isDebugEnabled()) {
      LogManager.instance()
          .debug(this, "Created record %s v.%s size=%d bytes", rid, recordVersion, content.length);
    }

    return new StorageOperationResult<>(ppos);
  }

  private StorageOperationResult<Integer> doUpdateRecord(
      final AtomicOperation atomicOperation,
      final RecordId rid,
      final boolean updateContent,
      byte[] content,
      final int version,
      final byte recordType,
      final RecordCallback<Integer> callback,
      final StorageCluster cluster) {

    cluster.meters().update().record();
    try {

      final var ppos =
          cluster.getPhysicalPosition(new PhysicalPosition(rid.getClusterPosition()));
      if (!checkForRecordValidity(ppos)) {
        final var recordVersion = -1;
        if (callback != null) {
          callback.call(rid, recordVersion);
        }

        return new StorageOperationResult<>(recordVersion);
      }

      var contentModified = false;
      if (updateContent) {
        final var recVersion = new AtomicInteger(version);
        final var dbVersion = new AtomicInteger(ppos.recordVersion);

        final var newContent = checkAndIncrementVersion(rid, recVersion, dbVersion, name);

        ppos.recordVersion = dbVersion.get();

        // REMOVED BECAUSE DISTRIBUTED COULD UNDO AN OPERATION RESTORING A LOWER VERSION
        // assert ppos.recordVersion >= oldVersion;

        if (newContent != null) {
          contentModified = true;
          content = newContent;
        }
      }

      if (updateContent) {
        cluster.updateRecord(
            rid.getClusterPosition(), content, ppos.recordVersion, recordType, atomicOperation);
      }

      final var context = RecordSerializationContext.getContext();
      if (context != null) {
        context.executeOperations(atomicOperation, this);
      }

      // if we do not update content of the record we should keep version of the record the same
      // otherwise we would have issues when two records may have the same version but different
      // content
      final int newRecordVersion;
      if (updateContent) {
        newRecordVersion = ppos.recordVersion;
      } else {
        newRecordVersion = version;
      }

      if (callback != null) {
        callback.call(rid, newRecordVersion);
      }

      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance()
            .debug(this, "Updated record %s v.%s size=%d", rid, newRecordVersion, content.length);
      }

      if (contentModified) {
        return new StorageOperationResult<>(newRecordVersion, content, false);
      } else {
        return new StorageOperationResult<>(newRecordVersion);
      }
    } catch (final ConcurrentModificationException e) {
      cluster.meters().conflict().record();
      throw e;
    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error on updating record " + rid + " (cluster: " + cluster.getName() + ")"),
          ioe, name);
    }
  }

  private void doDeleteRecord(
      final AtomicOperation atomicOperation,
      final RecordId rid,
      final int version,
      final StorageCluster cluster) {
    cluster.meters().delete().record();
    try {

      final var ppos =
          cluster.getPhysicalPosition(new PhysicalPosition(rid.getClusterPosition()));

      if (ppos == null) {
        // ALREADY DELETED
        return;
      }

      // MVCC TRANSACTION: CHECK IF VERSION IS THE SAME
      if (version > -1 && ppos.recordVersion != version) {
        cluster.meters().conflict().record();
        throw new ConcurrentModificationException(name
            , rid, ppos.recordVersion, version, RecordOperation.DELETED);
      }

      cluster.deleteRecord(atomicOperation, ppos.clusterPosition);

      final var context = RecordSerializationContext.getContext();
      if (context != null) {
        context.executeOperations(atomicOperation, this);
      }

      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance().debug(this, "Deleted record %s v.%s", rid, version);
      }

    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error on deleting record " + rid + "( cluster: " + cluster.getName() + ")"),
          ioe, name);
    }
  }

  @Nonnull
  private ReadRecordResult doReadRecord(
      final StorageCluster cluster, final RecordId rid, boolean fetchPreviousRid,
      boolean fetchNextRid) {
    cluster.meters().read().record();
    try {

      final var buff = cluster.readRecord(rid.getClusterPosition());
      RecordId prevRid = null;
      RecordId nextRid = null;

      if (fetchNextRid) {
        var positions = cluster.higherPositions(new PhysicalPosition(rid.getClusterPosition()), 1);
        if (positions != null && positions.length > 0) {
          nextRid = new RecordId(rid.getClusterId(), positions[0].clusterPosition);
        }
      }

      if (fetchPreviousRid) {
        var positions = cluster.lowerPositions(new PhysicalPosition(rid.getClusterPosition()), 1);
        if (positions != null && positions.length > 0) {
          prevRid = new RecordId(rid.getClusterId(), positions[0].clusterPosition);
        }
      }

      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "Read record %s v.%s size=%d bytes",
                rid,
                buff.version,
                buff.buffer != null ? buff.buffer.length : 0);
      }

      return new ReadRecordResult(buff, prevRid, nextRid);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during read of record with rid = " + rid), e, name);
    }
  }

  private static boolean doRecordExists(String dbName, final StorageCluster clusterSegment,
      final RID rid) {
    try {
      return clusterSegment.exists(rid.getClusterPosition());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(dbName, "Error during read of record with rid = " + rid), e, dbName);
    }
  }

  private int createClusterFromConfig(final StorageClusterConfiguration config)
      throws IOException {
    var cluster = clusterMap.get(config.getName().toLowerCase());

    if (cluster != null) {
      cluster.configure(this, config);
      return -1;
    }

    cluster =
        PaginatedClusterFactory.createCluster(
            config.getName(), configuration.getVersion(), config.getBinaryVersion(), this);

    cluster.configure(this, config);

    return registerCluster(cluster);
  }

  private void setCluster(final int id, final StorageCluster cluster) {
    if (clusters.size() <= id) {
      while (clusters.size() < id) {
        clusters.add(null);
      }

      clusters.add(cluster);
    } else {
      clusters.set(id, cluster);
    }
  }

  /**
   * Register the cluster internally.
   *
   * @param cluster SQLCluster implementation
   * @return The id (physical position into the array) of the new cluster just created. First is 0.
   */
  private int registerCluster(final StorageCluster cluster) {
    final int id;

    if (cluster != null) {
      // CHECK FOR DUPLICATION OF NAMES
      if (clusterMap.containsKey(cluster.getName().toLowerCase())) {
        throw new ConfigurationException(name,
            "Cannot add cluster '"
                + cluster.getName()
                + "' because it is already registered in database '"
                + name
                + "'");
      }
      // CREATE AND ADD THE NEW REF SEGMENT
      clusterMap.put(cluster.getName().toLowerCase(), cluster);
      id = cluster.getId();
    } else {
      id = clusters.size();
    }

    setCluster(id, cluster);

    return id;
  }

  private int doAddCluster(final AtomicOperation atomicOperation, final String clusterName)
      throws IOException {
    // FIND THE FIRST AVAILABLE CLUSTER ID
    var clusterPos = clusters.size();
    for (var i = 0; i < clusters.size(); ++i) {
      if (clusters.get(i) == null) {
        clusterPos = i;
        break;
      }
    }

    return doAddCluster(atomicOperation, clusterName, clusterPos);
  }

  private int doAddCluster(
      final AtomicOperation atomicOperation, String clusterName, final int clusterPos)
      throws IOException {
    final PaginatedCluster cluster;
    if (clusterName != null) {
      clusterName = clusterName.toLowerCase();

      cluster =
          PaginatedClusterFactory.createCluster(
              clusterName,
              configuration.getVersion(),
              configuration
                  .getContextConfiguration()
                  .getValueAsInteger(GlobalConfiguration.STORAGE_CLUSTER_VERSION),
              this);
      cluster.configure(clusterPos, clusterName);
    } else {
      cluster = null;
    }

    var createdClusterId = -1;

    if (cluster != null) {
      cluster.create(atomicOperation);
      createdClusterId = registerCluster(cluster);

      ((ClusterBasedStorageConfiguration) configuration)
          .updateCluster(atomicOperation, cluster.generateClusterConfig());

      sbTreeCollectionManager.createComponent(atomicOperation, createdClusterId);
    }

    return createdClusterId;
  }

  @Override
  public boolean setClusterAttribute(final int id, final ATTRIBUTES attribute, final Object value) {
    checkBackupRunning();
    stateLock.writeLock().lock();
    try {

      checkOpennessAndMigration();

      if (id >= clusters.size()) {
        return false;
      }

      final var cluster = clusters.get(id);

      if (cluster == null) {
        return false;
      }

      makeStorageDirty();

      return atomicOperationsManager.calculateInsideAtomicOperation(
          null,
          atomicOperation -> doSetClusterAttributed(atomicOperation, attribute, value, cluster));
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

  private boolean doSetClusterAttributed(
      final AtomicOperation atomicOperation,
      final ATTRIBUTES attribute,
      final Object value,
      final StorageCluster cluster) {
    final var stringValue = Optional.ofNullable(value).map(Object::toString).orElse(null);
    switch (attribute) {
      case NAME:
        Objects.requireNonNull(stringValue);

        final var oldName = cluster.getName();
        cluster.setClusterName(stringValue);
        clusterMap.remove(oldName.toLowerCase());
        clusterMap.put(stringValue.toLowerCase(), cluster);
        break;
      case CONFLICTSTRATEGY:
        cluster.setRecordConflictStrategy(stringValue);
        break;
      default:
        throw new IllegalArgumentException(
            "Runtime change of attribute '" + attribute + "' is not supported");
    }

    ((ClusterBasedStorageConfiguration) configuration)
        .updateCluster(atomicOperation, ((PaginatedCluster) cluster).generateClusterConfig());
    return true;
  }

  private boolean dropClusterInternal(final AtomicOperation atomicOperation, final int clusterId)
      throws IOException {
    final var cluster = clusters.get(clusterId);

    if (cluster == null) {
      return true;
    }

    cluster.delete(atomicOperation);

    clusterMap.remove(cluster.getName().toLowerCase());
    clusters.set(clusterId, null);

    return false;
  }

  protected void doShutdown() throws IOException {

    shutdownDuration.timed(() -> {
      if (status == STATUS.CLOSED) {
        return;
      }

      if (status != STATUS.OPEN && !isInError()) {
        throw BaseException.wrapException(
            new StorageException(name, "Storage " + name + " was not opened, so can not be closed"),
            this.error.get(), name);
      }

      status = STATUS.CLOSING;

      if (!isInError()) {
        flushAllData();
        preCloseSteps();

        atomicOperationsManager.executeInsideAtomicOperation(
            null,
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
              ((ClusterBasedStorageConfiguration) configuration).close(atomicOperation);
            });

        sbTreeCollectionManager.close();

        // we close all files inside cache system so we only clear cluster metadata
        clusters.clear();
        clusterMap.clear();
        indexEngines.clear();
        indexEngineNameMap.clear();

        if (writeCache != null) {
          writeCache.removeBackgroundExceptionListener(this);
          writeCache.removePageIsBrokenListener(this);
        }

        writeAheadLog.removeCheckpointListener(this);

        if (readCache != null) {
          readCache.closeStorage(writeCache);
        }

        writeAheadLog.close();
      } else {
        LogManager.instance()
            .error(
                this,
                "Because of JVM error happened inside of storage it can not be properly closed",
                null);
      }

      postCloseSteps(false, isInError(), idGen.getLastId());
      transaction = null;
      lastMetadata = null;
      migration = new CountDownLatch(1);
      status = STATUS.CLOSED;
    });
  }

  private void doShutdownOnDelete() {
    if (status == STATUS.CLOSED) {
      return;
    }

    if (status != STATUS.OPEN && !isInError()) {
      throw BaseException.wrapException(
          new StorageException(name, "Storage " + name + " was not opened, so can not be closed"),
          this.error.get(), name);
    }

    status = STATUS.CLOSING;
    try {
      if (!isInError()) {
        preCloseSteps();

        for (final var engine : indexEngines) {
          if (engine != null
              && !(engine instanceof BTreeSingleValueIndexEngine
              || engine instanceof BTreeMultiValueIndexEngine)) {
            // delete method is implemented only in non native indexes, so they do not use ODB
            // atomic operation
            engine.delete(null);
          }
        }

        sbTreeCollectionManager.close();

        // we close all files inside cache system so we only clear cluster metadata
        clusters.clear();
        clusterMap.clear();
        indexEngines.clear();
        indexEngineNameMap.clear();

        if (writeCache != null) {
          writeCache.removeBackgroundExceptionListener(this);
          writeCache.removePageIsBrokenListener(this);
        }

        writeAheadLog.removeCheckpointListener(this);

        if (readCache != null) {
          readCache.deleteStorage(writeCache);
        }

        writeAheadLog.delete();
      } else {
        LogManager.instance()
            .error(
                this,
                "Because of JVM error happened inside of storage it can not be properly closed",
                null);
      }
      postCloseSteps(true, isInError(), idGen.getLastId());
      transaction = null;
      lastMetadata = null;
      migration = new CountDownLatch(1);
      status = STATUS.CLOSED;
    } catch (final IOException e) {
      final var message = "Error on closing of storage '" + name;
      LogManager.instance().error(this, message, e);

      throw BaseException.wrapException(new StorageException(name, message), e, name);
    }
  }

  @SuppressWarnings("unused")
  protected void closeClusters() throws IOException {
    for (final var cluster : clusters) {
      if (cluster != null) {
        cluster.close(true);
      }
    }
    clusters.clear();
    clusterMap.clear();
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

  private static byte[] checkAndIncrementVersion(
      final RecordId rid, final AtomicInteger version, final AtomicInteger iDatabaseVersion,
      String dbName) {
    final var v = version.get();
    switch (v) {
      // DOCUMENT UPDATE, NO VERSION CONTROL
      case -1:
        iDatabaseVersion.incrementAndGet();
        break;

      // DOCUMENT UPDATE, NO VERSION CONTROL, NO VERSION UPDATE
      case -2:
        break;

      default:
        // MVCC CONTROL AND RECORD UPDATE OR WRONG VERSION VALUE
        // MVCC TRANSACTION: CHECK IF VERSION IS THE SAME
        if (v < -2) {
          // OVERWRITE VERSION: THIS IS USED IN CASE OF FIX OF RECORDS IN DISTRIBUTED MODE
          version.set(RecordVersionHelper.clearRollbackMode(v));
          iDatabaseVersion.set(version.get());
        } else if (v != iDatabaseVersion.get()) {
          throw new ConcurrentModificationException(dbName
              , rid, iDatabaseVersion.get(), v, RecordOperation.UPDATED);
        } else
        // OK, INCREMENT DB VERSION
        {
          iDatabaseVersion.incrementAndGet();
        }
    }
    return null;
  }

  private void commitEntry(
      FrontendTransactionImpl transcation,
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

    RecordSerializationContext.pushContext();
    try {
      final var cluster = doGetAndCheckCluster(rid.getClusterId());

      var db = transcation.getDatabaseSession();
      switch (txEntry.type) {
        case RecordOperation.CREATED: {
          final byte[] stream;
          try {
            stream = serializer.toStream(transcation.getDatabaseSession(), rec);
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
            ppos =
                doCreateRecord(
                    atomicOperation,
                    rid,
                    stream,
                    rec.getVersion(),
                    recordType,
                    null,
                    cluster,
                    allocated)
                    .getResult();

            rec.setVersion(ppos.recordVersion);
          } else {
            final var updateRes =
                doUpdateRecord(
                    atomicOperation,
                    rid,
                    rec.isContentChanged(),
                    stream,
                    -2,
                    rec.getRecordType(),
                    null,
                    cluster);
            final int iVersion = updateRes.getResult();
            rec.setVersion(iVersion);
            if (updateRes.getModifiedRecordContent() != null) {
              final int iVersion1 = updateRes.getResult();
              final var iBuffer = updateRes.getModifiedRecordContent();
              rec.fill(rid, iVersion1, iBuffer, false);
            }
          }
          break;
        }
        case RecordOperation.UPDATED: {
          final byte[] stream;
          try {
            stream = serializer.toStream(transcation.getDatabaseSession(), rec);
          } catch (RuntimeException e) {
            throw BaseException.wrapException(
                new CommitSerializationException(db.getDatabaseName(),
                    "Error During Record Serialization"),
                e, name);
          }

          final var updateRes =
              doUpdateRecord(
                  atomicOperation,
                  rid,
                  rec.isContentChanged(),
                  stream,
                  rec.getVersion(),
                  rec.getRecordType(),
                  null,
                  cluster);
          final int iVersion = updateRes.getResult();
          rec.setVersion(iVersion);
          if (updateRes.getModifiedRecordContent() != null) {
            final int iVersion1 = updateRes.getResult();
            final var iBuffer = updateRes.getModifiedRecordContent();
            rec.fill(rid, iVersion1, iBuffer, false);
          }

          break;
        }
        case RecordOperation.DELETED: {
          if (rec instanceof EntityImpl entity) {
            RidBagDeleter.deleteAllRidBags(entity);
          }
          doDeleteRecord(atomicOperation, rid, rec.getVersionNoLoad(), cluster);
          break;
        }
        default:
          throw new StorageException(name, "Unknown record operation " + txEntry.type);
      }
    } finally {
      RecordSerializationContext.pullContext();
    }

    // RESET TRACKING
    if (rec instanceof EntityImpl) {
      ((EntityImpl) rec).clearTrackData();
      ((EntityImpl) rec).clearTransactionTrackData();
    }

    rec.txEntry = null;
    rec.unsetDirty();
  }

  private void checkClusterSegmentIndexRange(final int iClusterId) {
    if (iClusterId < 0 || iClusterId > clusters.size() - 1) {
      throw new IllegalArgumentException(
          "Cluster segment #" + iClusterId + " does not exist in database '" + name + "'");
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

  @SuppressWarnings("CanBeFinal")
  @Override
  public String incrementalBackup(DatabaseSessionInternal session, final String backupDirectory,
      final CallableFunction<Void, Void> started)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Incremental backup is supported only in enterprise version");
  }

  @Override
  public void fullIncrementalBackup(final OutputStream stream)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Incremental backup is supported only in enterprise version");
  }

  @SuppressWarnings("CanBeFinal")
  @Override
  public void restoreFromIncrementalBackup(DatabaseSessionInternal session,
      final String filePath) {
    throw new UnsupportedOperationException(
        "Incremental backup is supported only in enterprise version");
  }

  @Override
  public void restoreFullIncrementalBackup(DatabaseSessionInternal session,
      final InputStream stream)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Incremental backup is supported only in enterprise version");
  }

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

    final var reportBatchSize =
        GlobalConfiguration.WAL_REPORT_AFTER_OPERATIONS_DURING_RESTORE.getValueAsInteger();
    final var operationUnits =
        new Long2ObjectOpenHashMap<List<WALRecord>>(1024);
    final Map<Long, byte[]> operationMetadata = new LinkedHashMap<>(1024);

    long lastReportTime = 0;
    LogSequenceNumber lastUpdatedLSN = null;

    try {
      var records = writeAheadLog.read(lsn, 1_000);

      while (!records.isEmpty()) {
        for (final var walRecord : records) {
          switch (walRecord) {
            case AtomicUnitEndRecord atomicUnitEndRecord -> {
              final var atomicUnit =
                  operationUnits.remove(atomicUnitEndRecord.getOperationUnitId());

              // in case of data restore from fuzzy checkpoint part of operations may be already
              // flushed to the disk
              if (atomicUnit != null) {
                atomicUnit.add(walRecord);
                if (!restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate)) {
                  return lastUpdatedLSN;
                } else {
                  lastUpdatedLSN = walRecord.getLsn();
                }
              }
              var metadata = operationMetadata.remove(atomicUnitEndRecord.getOperationUnitId());
              if (metadata != null) {
                this.lastMetadata = metadata;
              }
            }
            case AtomicUnitStartRecord oAtomicUnitStartRecord -> {
              if (walRecord instanceof AtomicUnitStartMetadataRecord) {
                var metadata = ((AtomicUnitStartMetadataRecord) walRecord).getMetadata();
                operationMetadata.put(
                    ((AtomicUnitStartRecord) walRecord).getOperationUnitId(), metadata);
              }

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
            case MetaDataRecord metaDataRecord -> {
              this.lastMetadata = metaDataRecord.getMetadata();
              lastUpdatedLSN = walRecord.getLsn();
            }
            case null, default -> LogManager.instance()
                .warn(this, "Record %s will be skipped during data restore", walRecord);
          }

          recordsProcessed++;

          final var currentTime = System.currentTimeMillis();
          if (reportBatchSize > 0 && recordsProcessed % reportBatchSize == 0
              || currentTime - lastReportTime > WAL_RESTORE_REPORT_INTERVAL) {
            final var additionalArgs =
                new Object[]{recordsProcessed, walRecord.getLsn(), writeAheadLog.end()};
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

    return lastUpdatedLSN;
  }

  protected final boolean restoreAtomicUnit(
      final List<WALRecord> atomicUnit, final ModifiableBoolean atLeastOnePageUpdate)
      throws IOException {
    assert atomicUnit.getLast() instanceof AtomicUnitEndRecord;
    for (final var walRecord : atomicUnit) {
      switch (walRecord) {
        case FileDeletedWALRecord fileDeletedWALRecord -> {
          if (writeCache.exists(fileDeletedWALRecord.getFileId())) {
            readCache.deleteFile(fileDeletedWALRecord.getFileId(), writeCache);
          }
        }
        case FileCreatedWALRecord fileCreatedCreatedWALRecord -> {
          if (!writeCache.exists(fileCreatedCreatedWALRecord.getFileName())) {
            readCache.addFile(
                fileCreatedCreatedWALRecord.getFileName(),
                fileCreatedCreatedWALRecord.getFileId(),
                writeCache);
          }
        }
        case UpdatePageRecord updatePageRecord -> {
          var fileId = updatePageRecord.getFileId();
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
    return true;
  }

  @SuppressWarnings("unused")
  public void setStorageConfigurationUpdateListener(
      final StorageConfigurationUpdateListener storageConfigurationUpdateListener) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      ((ClusterBasedStorageConfiguration) configuration)
          .setConfigurationUpdateListener(storageConfigurationUpdateListener);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void pauseConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      ((ClusterBasedStorageConfiguration) configuration).pauseUpdateNotifications();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void fireConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();
      ((ClusterBasedStorageConfiguration) configuration).fireUpdateNotifications();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @SuppressWarnings("unused")
  protected static Int2ObjectMap<List<RecordId>> getRidsGroupedByCluster(
      final Collection<RecordId> rids) {
    final var ridsPerCluster = new Int2ObjectOpenHashMap<List<RecordId>>(8);
    for (final var rid : rids) {
      final var group =
          ridsPerCluster.computeIfAbsent(rid.getClusterId(), k -> new ArrayList<>(rids.size()));
      group.add(rid);
    }
    return ridsPerCluster;
  }

  private static void lockIndexes(final TreeMap<String, FrontendTransactionIndexChanges> indexes) {
    for (final var changes : indexes.values()) {
      changes.getIndex().acquireAtomicExclusiveLock();
    }
  }

  private static void lockClusters(final TreeMap<Integer, StorageCluster> clustersToLock) {
    for (final var cluster : clustersToLock.values()) {
      cluster.acquireAtomicExclusiveLock();
    }
  }

  private void lockRidBags(
      final TreeMap<Integer, StorageCluster> clusters) {
    final var atomicOperation = atomicOperationsManager.getCurrentOperation();

    for (final var clusterId : clusters.keySet()) {
      atomicOperationsManager.acquireExclusiveLockTillOperationComplete(
          atomicOperation, BTreeCollectionManagerShared.generateLockName(clusterId));
    }
  }

  protected RuntimeException logAndPrepareForRethrow(final RuntimeException runtimeException) {
    if (!(runtimeException instanceof HighLevelException
        || runtimeException instanceof NeedRetryException
        || runtimeException instanceof InternalErrorException
        || runtimeException instanceof IllegalArgumentException)) {
      final var iAdditionalArgs =
          new Object[]{
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
          new Object[]{System.identityHashCode(error), getURL(), YouTrackDBConstants.getVersion()};
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
          new Object[]{System.identityHashCode(throwable), getURL(),
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
        new Object[]{System.identityHashCode(exception), getURL(),
            YouTrackDBConstants.getVersion()};
    LogManager.instance()
        .error(this, "Exception `%08X` in storage `%s` : %s", exception, iAdditionalArgs);
    return exception;
  }

  @Override
  public final StorageConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public final void setSchemaRecordId(final String schemaRecordId) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              storageConfiguration.setSchemaRecordId(atomicOperation, schemaRecordId));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, atomicOperation -> storageConfiguration.setDateFormat(atomicOperation, dateFormat));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, atomicOperation -> storageConfiguration.setLocaleLanguage(atomicOperation, locale));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, atomicOperation -> storageConfiguration.setCharset(atomicOperation, charset));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              storageConfiguration.setIndexMgrRecordId(atomicOperation, indexMgrRecordId));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              storageConfiguration.setDateTimeFormat(atomicOperation, dateTimeFormat));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
  public final void setClusterSelection(final String clusterSelection) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              storageConfiguration.setClusterSelection(atomicOperation, clusterSelection));
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
  public final void setMinimumClusters(final int minimumClusters) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      storageConfiguration.setMinimumClusters(minimumClusters);
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, atomicOperation -> storageConfiguration.setValidation(atomicOperation, validation));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, atomicOperation -> storageConfiguration.removeProperty(atomicOperation, property));
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          (ClusterBasedStorageConfiguration) configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null, storageConfiguration::clearProperties);
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

  public Optional<byte[]> getLastMetadata() {
    return Optional.ofNullable(lastMetadata);
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

      writeCache.syncDataFiles(minDirtySegment, lastMetadata);
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
  public int[] getClustersIds(Set<String> filterClusters) {
    try {

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();
        var result = new int[filterClusters.size()];
        var i = 0;
        for (var clusterName : filterClusters) {
          if (clusterName == null) {
            throw new IllegalArgumentException("Cluster name is null");
          }

          if (clusterName.isEmpty()) {
            throw new IllegalArgumentException("Cluster name is empty");
          }

          // SEARCH IT BETWEEN PHYSICAL CLUSTERS
          final var segment = clusterMap.get(clusterName.toLowerCase());
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

  public void startDDL() {
    backupLock.lock();
    try {
      waitBackup();
      //noinspection NonAtomicOperationOnVolatileField
      this.ddlRunning += 1;
    } finally {
      backupLock.unlock();
    }
  }

  public void endDDL() {
    backupLock.lock();
    try {
      assert this.ddlRunning > 0;
      //noinspection NonAtomicOperationOnVolatileField
      this.ddlRunning -= 1;

      if (this.ddlRunning == 0) {
        backupIsDone.signalAll();
      }
    } finally {
      backupLock.unlock();
    }
  }

  private void waitBackup() {
    while (isIcrementalBackupRunning()) {
      try {
        backupIsDone.await();
      } catch (java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("Interrupted wait for backup to finish"), e, name);
      }
    }
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal sessionPool, String query,
      LiveQueryResultListener listener, Map<String, ?> args) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    var queryListener = new LiveQueryListenerImpl(listener, query, sessionPool, (Map) args);
    return new YTLiveQueryMonitorEmbedded(queryListener.getToken(), sessionPool);
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal sessionPool, String query,
      LiveQueryResultListener listener, Object... args) {
    var queryListener = new LiveQueryListenerImpl(listener, query, sessionPool, args);
    return new YTLiveQueryMonitorEmbedded(queryListener.getToken(), sessionPool);
  }

  protected void checkBackupRunning() {
    waitBackup();
  }

  @Override
  public YouTrackDBInternal getContext() {
    return this.context;
  }

  public boolean isMemory() {
    return false;
  }

  @SuppressWarnings("unused")
  protected void endBackup() {
    backupLock.lock();
    try {
      assert this.backupRunning > 0;
      //noinspection NonAtomicOperationOnVolatileField
      this.backupRunning -= 1;

      if (this.backupRunning == 0) {
        backupIsDone.signalAll();
      }
    } finally {
      backupLock.unlock();
    }
  }

  public boolean isIcrementalBackupRunning() {
    return this.backupRunning > 0;
  }

  protected boolean isDDLRunning() {
    return this.ddlRunning > 0;
  }

  @SuppressWarnings("unused")
  protected void startBackup() {
    backupLock.lock();
    try {
      while (isDDLRunning()) {
        try {
          backupIsDone.await();
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("Interrupted wait for backup to finish"), e, name);
        }
      }
      //noinspection NonAtomicOperationOnVolatileField
      this.backupRunning += 1;
    } finally {
      backupLock.unlock();
    }
  }
}
