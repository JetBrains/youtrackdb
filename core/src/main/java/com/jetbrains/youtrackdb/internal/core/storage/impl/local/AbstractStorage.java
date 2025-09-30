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

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CollectionDoesNotExistException;
import com.jetbrains.youtrackdb.api.exception.CommitSerializationException;
import com.jetbrains.youtrackdb.api.exception.ConcurrentCreateException;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.api.exception.InvalidDatabaseNameException;
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.StorageDoesNotExistException;
import com.jetbrains.youtrackdb.api.exception.StorageExistsException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.RID;
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
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfigurationUpdateListener;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBagDeleter;
import com.jetbrains.youtrackdb.internal.core.exception.InternalErrorException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidInstanceIdException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.MultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.SingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.query.live.YTLiveQueryMonitorEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LiveQueryListenerImpl;
import com.jetbrains.youtrackdb.internal.core.storage.IdentifiableStorage;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.ReadRecordResult;
import com.jetbrains.youtrackdb.internal.core.storage.RecordCallback;
import com.jetbrains.youtrackdb.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection.RECORD_STATUS;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageTransaction;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
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
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
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
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @since 28.03.13
 */
public abstract class AbstractStorage
    implements CheckpointRequestListener,
    IdentifiableStorage,
    BackgroundExceptionListener,
    FreezableStorageComponent,
    PageIsBrokenListener,
    Storage {

  private static final Logger logger = LoggerFactory.getLogger(AbstractStorage.class);
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

  protected volatile LinkCollectionsBTreeManagerShared linkCollectionsBTreeManager;

  private final Map<String, StorageCollection> collectionMap = new HashMap<>();
  private final List<StorageCollection> collections = new CopyOnWriteArrayList<>();

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

  private final Map<String, V1IndexEngine> indexEngineNameMap = new HashMap<>();
  private final List<V1IndexEngine> indexEngines = new ArrayList<>();
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

  protected volatile CollectionBasedStorageConfiguration configuration;
  protected volatile CurrentStorageComponentsFactory componentsFactory;
  protected final String name;
  private final AtomicLong version = new AtomicLong();

  protected volatile STATUS status = STATUS.CLOSED;

  protected AtomicReference<Throwable> error = new AtomicReference<>(null);
  protected YouTrackDBInternalEmbedded context;
  private volatile CountDownLatch migration = new CountDownLatch(1);

  private volatile int backupRunning = 0;
  private volatile int ddlRunning = 0;

  protected final Lock backupLock = new ReentrantLock();
  protected final Condition backupIsDone = backupLock.newCondition();

  private final Stopwatch dropDuration;
  private final Stopwatch synchDuration;
  private final Stopwatch shutdownDuration;

  public AbstractStorage(
      final String name, final String filePath, final int id,
      YouTrackDBInternalEmbedded context) {
    this.context = context;
    this.name = checkName(name);

    url = filePath;

    stateLock = new ScalableRWLock();

    this.id = id;
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
  public boolean freeCollection(DatabaseSessionInternal session, final String iCollectionName) {
    return freeCollection(session, getCollectionIdByName(iCollectionName));
  }

  @Override
  public long countRecords(DatabaseSessionInternal session) {
    long tot = 0;

    for (var c : getCollectionInstances()) {
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
                if (CollectionBasedStorageConfiguration.exists(writeCache)) {
                  configuration = new CollectionBasedStorageConfiguration(this);
                  configuration
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

          linkCollectionsBTreeManager.load();

          atomicOperationsManager.executeInsideAtomicOperation(null, this::openCollections);
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

  protected final void openCollections(final AtomicOperation atomicOperation) throws IOException {
    // OPEN BASIC SEGMENTS
    int pos;

    // REGISTER COLLECTION
    final var configurationCollections = configuration.getCollections();
    for (var i = 0; i < configurationCollections.size(); ++i) {
      final var collectionConfig = configurationCollections.get(i);

      if (collectionConfig != null) {
        pos = createCollectionFromConfig(collectionConfig);

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
                      + configurationCollections.get(i).getName()
                      + "' ("
                      + i
                      + "): file not found. It will be excluded from current database '"
                      + name
                      + "'.",
                  e);

          collectionMap.remove(configurationCollections.get(i).getName().toLowerCase());

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
          configuration = new CollectionBasedStorageConfiguration(this);
          configuration
              .create(atomicOperation, contextConfiguration);
          configuration.setUuid(atomicOperation, uuid.toString());

          componentsFactory = new CurrentStorageComponentsFactory(configuration);

          linkCollectionsBTreeManager.load();

          status = STATUS.OPEN;

          linkCollectionsBTreeManager = new LinkCollectionsBTreeManagerShared(this);

          // ADD THE METADATA COLLECTION TO STORE INTERNAL STUFF
          doAddCollection(atomicOperation, SessionMetadata.COLLECTION_INTERNAL_NAME);

          configuration
              .setCreationVersion(atomicOperation, YouTrackDBConstants.getVersion());
          configuration
              .setPageSize(
                  atomicOperation,
                  GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10);
          configuration
              .setMaxKeySize(
                  atomicOperation, GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger());

          generateDatabaseInstanceId(atomicOperation);
          clearStorageDirty();
          postCreateSteps();
        });
  }

  protected void generateDatabaseInstanceId(AtomicOperation atomicOperation) {
    configuration
        .setProperty(atomicOperation, DATABASE_INSTANCE_ID, UUID.randomUUID().toString());
  }

  @Nullable
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
        final var lockId = atomicOperationsManager.freezeAtomicOperations(null);
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
  public final int addCollection(DatabaseSessionInternal database, final String collectionName,
      final Object... parameters) {
    try {
      checkBackupRunning();
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
            null, (atomicOperation) -> doAddCollection(atomicOperation, collectionName));

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
  public final int addCollection(DatabaseSessionInternal database, final String collectionName,
      final int requestedId) {
    try {
      checkBackupRunning();
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
            null, atomicOperation -> doAddCollection(atomicOperation, collectionName, requestedId));

      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Error in creation of new collection '" + collectionName + "'"), e,
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


  public final int allocateCollection() {
    try {
      checkBackupRunning();
      stateLock.writeLock().lock();
      try {
        checkOpennessAndMigration();
        makeStorageDirty();

        return atomicOperationsManager.calculateInsideAtomicOperation(
            null, this::doAddCollection);

      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Error in creation of adding of new collection"), e,
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
  public final boolean freeCollection(DatabaseSessionInternal database, final int collectionId) {
    try {
      checkBackupRunning();
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
            null,
            atomicOperation -> {
              if (dropCollectionInternal(atomicOperation, collectionId)) {
                return false;
              }

              configuration
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
  public long getCollectionRecordsSizeById(int collectionId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        checkCollectionId(collectionId);
        final var collection = collections.get(collectionId);
        if (collection == null) {
          throwCollectionDoesNotExist(collectionId);
        }

        return collection.getRecordsSize();
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
  public long getCollectionRecordsSizeByName(String collectionName) {
    Objects.requireNonNull(collectionName);

    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = collectionMap.get(collectionName.toLowerCase());
        if (collection == null) {
          throwCollectionDoesNotExist(collectionName);
        }

        return collection.getRecordsSize();
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

  private void throwCollectionDoesNotExist(String collectionName) {
    throw new CollectionDoesNotExistException(name,
        "Collection with name `" + collectionName + "` does not exist inside of storage " + name);
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
  public final long count(DatabaseSessionInternal session, final int iCollectionId) {
    return count(session, iCollectionId, false);
  }

  @Override
  public final long count(DatabaseSessionInternal session, final int collectionId,
      final boolean countTombstones) {
    try {
      if (collectionId == -1) {
        throw new StorageException(name,
            "Collection Id " + collectionId + " is invalid in database '" + name + "'");
      }

      // COUNT PHYSICAL COLLECTION IF ANY
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = collections.get(collectionId);
        if (collection == null) {
          return 0;
        }

        if (countTombstones) {
          return collection.getEntries();
        }

        return collection.getEntries() - collection.getTombstonesCount();
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
  public final long count(DatabaseSessionInternal session, final int[] iCollectionIds) {
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
  public final long count(DatabaseSessionInternal session, final int[] iCollectionIds,
      final boolean countTombstones) {
    try {
      long tot = 0;

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

  @Nullable
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

        final var collection = doGetAndCheckCollection(rid.getCollectionId());
        checkOpennessAndMigration();

        final var ppos =
            collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()));
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

  public Iterator<CollectionBrowsePage> browseCollection(final int collectionId) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final int finalCollectionId;
        if (collectionId == RID.COLLECTION_ID_INVALID) {
          // GET THE DEFAULT COLLECTION
          throw new StorageException(name, "Collection Id " + collectionId + " is invalid");
        } else {
          finalCollectionId = collectionId;
        }
        return new Iterator<>() {
          @Nullable
          private CollectionBrowsePage page;
          private long lastPos = -1;

          @Override
          public boolean hasNext() {
            if (page == null) {
              page = nextPage(finalCollectionId, lastPos);
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

  private CollectionBrowsePage nextPage(final int collectionId, final long lastPosition) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(collectionId);
        return collection.nextPage(lastPosition);
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
  public @Nonnull ReadRecordResult readRecord(
      DatabaseSessionInternal session, final RecordIdInternal rid, boolean fetchPreviousRid,
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

        final var segment = collectionMap.get(collectionName.toLowerCase());
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
            null,
            atomicOperation -> {
              lockCollections(collectionsToLock);

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
                  var recordStatus = collection.getRecordStatus(rid.getCollectionPosition());
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
              && record instanceof EntityImpl) {
            // TRY TO FIX COLLECTION ID TO THE DEFAULT COLLECTION ID DEFINED IN SCHEMA CLASS

            var cls = ((EntityImpl) record).getImmutableSchemaClass();
            if (cls != null) {
              collectionId = cls.getCollectionForNewInstance((EntityImpl) record);
              collectionOverrides.put(recordOperation, collectionId);
            }
          }
          collectionsToLock.put(collectionId, doGetAndCheckCollection(collectionId));
        }
      }

      final List<RecordOperation> result = new ArrayList<>(8);
      stateLock.readLock().lock();
      try {
        try {
          checkOpennessAndMigration();

          makeStorageDirty();

          Throwable error = null;
          startStorageTx(frontendTransaction);
          try {
            final var atomicOperation = atomicOperationsManager.getCurrentOperation();
            lockCollections(collectionsToLock);
            lockLinkBags(collectionsToLock);
            lockIndexes(indexOperations);

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

            commitIndexes(frontendTransaction.getDatabaseSession(), indexOperations);
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

      if (logger.isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "%d Committed transaction %d on database '%s' (result=%s)",
                logger, Thread.currentThread().getId(),
                frontendTransaction.getId(),
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
      if (changes.cleared) {
        clearIndex(index.getIndexId());
      }

      for (final var changesPerKey : changes.changesPerKey.values()) {
        applyTxChanges(db, changesPerKey, index);
      }

      applyTxChanges(db, changes.nullKeyChanges, index);
    }
  }

  private void applyTxChanges(DatabaseSessionInternal session,
      FrontendTransactionIndexChangesPerKey changes, Index index) {
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

  private int addIndexEngine(
      final SchemaIndexEntity indexEntity,
      final Map<String, String> engineProperties) {
    try {
      final var keyTypes = indexEntity.getKeyTypes();
      if (keyTypes == null) {
        throw new IndexException(name, "Types of indexed keys have to be provided");
      }

      final var keySerializer = determineKeySerializer(keyTypes);
      if (keySerializer == null) {
        throw new IndexException(name, "Can not determine key serializer");
      }

      final var keySize = keyTypes.size();

      checkBackupRunning();
      stateLock.writeLock().lock();
      try {

        checkOpennessAndMigration();

        makeStorageDirty();
        return atomicOperationsManager.calculateInsideAtomicOperation(
            null,
            atomicOperation -> {
              if (indexEngineNameMap.containsKey(indexEntity.getName())) {
                // OLD INDEX FILE ARE PRESENT: THIS IS THE CASE OF PARTIAL/BROKEN INDEX
                LogManager.instance()
                    .warn(
                        this,
                        "Index with name '%s' already exists, removing it and re-create the index",
                        indexEntity.getName());
                final var engine = indexEngineNameMap.remove(indexEntity.getName());
                if (engine != null) {
                  indexEngines.set(engine.getId(), null);

                  engine.delete(atomicOperation);
                  configuration
                      .deleteIndexEngine(atomicOperation, indexEntity.getName());
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
                      indexEntity.getName(),
                      indexEntity.getIndexType().name(),
                      indexEntity.getIndexType() != INDEX_TYPE.UNIQUE,
                      valueSerializerId,
                      keySerializer.getId(),
                      keyTypes.toArray(new PropertyTypeInternal[0]),
                      !indexEntity.isNullValuesIgnored(),
                      keySize,
                      null,
                      cfgEncryptionKey,
                      engineProperties);

              final var engine = Indexes.createIndexEngine(this, engineData);

              engine.create(atomicOperation, engineData);
              indexEngineNameMap.put(indexEntity.getName(), engine);
              indexEngines.add(engine);

              configuration
                  .addIndexEngine(atomicOperation, indexEntity.getName(), engineData);

              return generateIndexId(engineData.getIndexId(), engine);
            });
      } catch (final IOException e) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Cannot add index engine " + indexEntity.getName() + " in storage."),
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
          && configuration.getBinaryFormatVersion() >= 13) {
        return UTF8Serializer.INSTANCE;
      }

      final var currentStorageComponentsFactory = componentsFactory;
      if (currentStorageComponentsFactory != null) {
        keySerializer =
            currentStorageComponentsFactory.binarySerializerFactory.getObjectSerializer(
                keyType);
      } else {
        throw new IllegalStateException(
            "Cannot load binary serializer, storage is not properly initialized");
      }
    }

    return keySerializer;
  }

  private BinarySerializer<?> determineKeySerializer(List<PropertyTypeInternal> keyTypes) {
    final BinarySerializer<?> keySerializer;
    if (keyTypes.size() > 1) {
      keySerializer = CompositeKeySerializer.INSTANCE;
    } else {
      final var keyType = keyTypes.getFirst();

      if (keyType == PropertyTypeInternal.STRING) {
        return UTF8Serializer.INSTANCE;
      }

      final var currentStorageComponentsFactory = componentsFactory;
      if (currentStorageComponentsFactory != null) {
        keySerializer =
            currentStorageComponentsFactory.binarySerializerFactory.getObjectSerializer(
                keyType);
      } else {
        throw new IllegalStateException(
            "Cannot load binary serializer, storage is not properly initialized");
      }
    }

    return keySerializer;
  }

  public void deleteIndexEngine(int indexId) {
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
              configuration
                  .deleteIndexEngine(atomicOperation, engineName);
            });

      } catch (final IOException e) {
        throw BaseException.wrapException(new StorageException(name, "Error on index deletion"),
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

  private void checkIndexId(final int indexId) {
    if (indexId < 0 || indexId >= indexEngines.size() || indexEngines.get(indexId) == null) {
      throw new DatabaseException(name,
          "Engine with id " + indexId + " is not registered inside of storage");
    }
  }

  public void removeKeyFromIndex(final int indexId, final Object key) {
    final var internalIndexId = extractInternalId(indexId);

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
      removeKeyFromIndexInternal(atomicOperation, internalIndexId, key);
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void removeKeyFromIndexInternal(
      final AtomicOperation atomicOperation, final int indexId, final Object key) {
    try {
      checkIndexId(indexId);

      final var engine = indexEngines.get(indexId);

      final var v1IndexEngine = (V1IndexEngine) engine;
      if (!v1IndexEngine.isMultiValue()) {
        ((SingleValueIndexEngine) engine).remove(atomicOperation, key);
      } else {
        throw new StorageException(name,
            "To remove entry from multi-value index not only key but value also should be"
                + " provided");
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
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void doClearIndex(final AtomicOperation atomicOperation,
      final int indexId) {
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

  public Iterator<RID> getIndexValues(int indexId, final Object key) {
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
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private Iterator<RID> doGetIndexValues(final int indexId, final Object key) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return ((V1IndexEngine) engine).get(key);
  }

  public BaseIndexEngine getIndexEngine(int indexId) {
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
      final boolean writeOperation, int indexId, final IndexEngineCallback<T> callback) {
    indexId = extractInternalId(indexId);

    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        if (writeOperation) {
          makeStorageDirty();
        }

        return doCallIndexEngine(indexId, callback);
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

  private <T> T doCallIndexEngine(final int indexId, final IndexEngineCallback<T> callback) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);

    return callback.callEngine(engine);
  }

  public void putRidIndexEntry(int indexId, final Object key, final RID value) {
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
      final RID value) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert engine.getId() == indexId;

    ((V1IndexEngine) engine).put(atomicOperation, key, value);
  }

  public void removeRidIndexEntry(int indexId, final Object key, final RID value) {
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
      removeRidIndexEntryInternal(atomicOperation, internalIndexId, key, value);

    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void removeRidIndexEntryInternal(
      final AtomicOperation atomicOperation, final int indexId, final Object key,
      final RID value) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert engine.getId() == indexId;

    ((MultiValueIndexEngine) engine).remove(atomicOperation, key, value);
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
      final IndexEngineValidator<Object, RID> validator) {
    final var internalIndexId = extractInternalId(indexId);

    try {
      assert transaction.get() != null;
      final var atomicOperation = atomicOperationsManager.getCurrentOperation();
      assert atomicOperation != null;
      return doValidatedPutIndexValue(atomicOperation, internalIndexId, key, value, validator);
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
      final IndexEngineValidator<Object, RID> validator) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    if (engine instanceof SingleValueIndexEngine) {
      return ((SingleValueIndexEngine) engine)
          .validatedPut(atomicOperation, key, value.getIdentity(), validator);
    }

    throw new IllegalStateException(
        "Invalid type of index engine " + engine.getClass().getName());
  }

  public Iterator<RawPair<Object, RID>> iterateIndexEntriesBetween(
      DatabaseSessionEmbedded db, int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesBetween(db,
            indexId, rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesBetween(db,
            indexId, rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder);
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

  private Iterator<RawPair<Object, RID>> doIterateIndexEntriesBetween(
      DatabaseSessionEmbedded db, final int indexId,
      final Object rangeFrom,
      final boolean fromInclusive,
      final Object rangeTo,
      final boolean toInclusive,
      final boolean ascSortOrder) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesBetween(db
        , rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder);
  }

  public Iterator<RawPair<Object, RID>> iterateIndexEntriesMajor(
      int indexId,
      final Object fromKey,
      final boolean isInclusive,
      final boolean ascSortOrder) {
    indexId = extractInternalId(indexId);
    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesMajor(indexId, fromKey, isInclusive, ascSortOrder
        );
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesMajor(indexId, fromKey, isInclusive, ascSortOrder
        );
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

  private Iterator<RawPair<Object, RID>> doIterateIndexEntriesMajor(
      final int indexId,
      final Object fromKey,
      final boolean isInclusive,
      final boolean ascSortOrder) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder);
  }

  public Iterator<RawPair<Object, RID>> iterateIndexEntriesMinor(
      int indexId,
      final Object toKey,
      final boolean isInclusive,
      final boolean ascSortOrder) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doIterateIndexEntriesMinor(indexId, toKey, isInclusive, ascSortOrder
        );
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doIterateIndexEntriesMinor(indexId, toKey, isInclusive, ascSortOrder
        );
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

  private Iterator<RawPair<Object, RID>> doIterateIndexEntriesMinor(
      final int indexId,
      final Object toKey,
      final boolean isInclusive,
      final boolean ascSortOrder) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.iterateEntriesMinor(toKey, isInclusive, ascSortOrder);
  }

  public Iterator<RawPair<Object, RID>> getIndexIterator(
      int indexId) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexAscIterator(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexAscIterator(indexId);
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

  private Iterator<RawPair<Object, RID>> doGetIndexAscIterator(
      final int indexId) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.ascEntries();
  }

  public Iterator<RawPair<Object, RID>> getIndexDescIterator(
      int indexId) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexDescStream(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexDescStream(indexId);
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

  private Iterator<RawPair<Object, RID>> doGetIndexDescStream(
      final int indexId) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.descEntries();
  }

  public Iterator<Object> getIndexKeys(int indexId) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexKeys(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexKeys(indexId);
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

  private Iterator<Object> doGetIndexKeys(final int indexId) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.keys();
  }

  public long getIndexSize(int indexId) {
    indexId = extractInternalId(indexId);

    try {
      if (transaction.get() != null) {
        return doGetIndexSize(indexId);
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        return doGetIndexSize(indexId);
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

  private long doGetIndexSize(final int indexId) {
    checkIndexId(indexId);

    final var engine = indexEngines.get(indexId);
    assert indexId == engine.getId();

    return engine.size(this);
  }

  public boolean hasIndexRangeQuerySupport(int indexId) {
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
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee, false);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t, false);
    }
  }

  private boolean doHasRangeQuerySupport(final int indexId) {
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
  public final void synch() {
    try {
      stateLock.readLock().lock();
      try {

        final var synchStartedAt = System.nanoTime();
        final var lockId = atomicOperationsManager.freezeAtomicOperations(null);
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

  @Nullable
  @Override
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
  public String getCollectionName(DatabaseSessionInternal database, int collectionId) {
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

  @Override
  public final long getSize(DatabaseSessionInternal session) {
    try {
      try {
        long size = 0;

        stateLock.readLock().lock();
        try {

          checkOpennessAndMigration();

          for (final var c : collections) {
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
  public final void freeze(DatabaseSessionInternal db, final boolean throwException) {
    try {
      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        if (throwException) {
          atomicOperationsManager.freezeAtomicOperations(
              () -> new ModificationOperationProhibitedException(name,
                  "Modification requests are prohibited"));
        } else {
          atomicOperationsManager.freezeAtomicOperations(null);
        }

        final List<FreezableStorageComponent> frozenIndexes = new ArrayList<>(
            indexEngines.size());
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

    setInError(
        new StorageException(name, "Page " + pageIndex + " is broken in file " + fileName));

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
      DatabaseSessionInternal session, final int currentCollectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentCollectionId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(currentCollectionId);
        return collection.higherPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + currentCollectionId + " is invalid in storage '" + name
                    + '\''),
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
      DatabaseSessionInternal session, final int collectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (collectionId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(collectionId);
        return collection.ceilingPositions(physicalPosition, limit);
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
      DatabaseSessionInternal session, final int currentCollectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (currentCollectionId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {

        checkOpennessAndMigration();

        final var collection = doGetAndCheckCollection(currentCollectionId);

        return collection.lowerPositions(physicalPosition, limit);
      } catch (final IOException ioe) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Collection Id " + currentCollectionId + " is invalid in storage '" + name
                    + '\''),
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
      DatabaseSessionInternal session, final int collectionId,
      final PhysicalPosition physicalPosition, int limit) {
    try {
      if (collectionId == -1) {
        return new PhysicalPosition[0];
      }

      stateLock.readLock().lock();
      try {
        checkOpennessAndMigration();
        final var collection = doGetAndCheckCollection(collectionId);

        return collection.floorPositions(physicalPosition, limit);
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
      configuration
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
              logger, minLSNSegment,
              beginLSN,
              endLSN,
              fuzzySegment);

      if (fuzzySegment > beginLSN.getSegment() && beginLSN.getSegment() < endLSN.getSegment()) {
        LogManager.instance().debug(this, "Making fuzzy checkpoint", logger);
        writeCache.syncDataFiles(fuzzySegment, lastMetadata);

        beginLSN = writeAheadLog.begin();
        endLSN = writeAheadLog.end();

        LogManager.instance()
            .debug(this, "After fuzzy checkpoint: WAL begin is %s WAL end is %s", logger,
                beginLSN,
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

  public void deleteTreeLinkBag(final BTreeBasedLinkBag ridBag) {
    try {
      checkOpennessAndMigration();

      assert transaction.get() != null;
      deleteTreeLinkBag(ridBag, atomicOperationsManager.getCurrentOperation());
    } catch (final RuntimeException ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Error ee) {
      throw logAndPrepareForRethrow(ee);
    } catch (final Throwable t) {
      throw logAndPrepareForRethrow(t);
    }
  }

  private void deleteTreeLinkBag(BTreeBasedLinkBag ridBag, AtomicOperation atomicOperation) {
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
          new StorageException(name, "Error during checkpoint creation for storage " + name),
          ioe,
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

  @Nullable
  protected String getOpenedAtVersion() {
    return null;
  }

  @Nonnull
  private ReadRecordResult readRecord(final RecordIdInternal rid, boolean fetchPreviousRid,
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
      final StorageCollection collection;
      try {
        collection = doGetAndCheckCollection(rid.getCollectionId());
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(new RecordNotFoundException(name, rid), e, name);
      }
      // Disabled this assert have no meaning anymore
      // assert iLockingStrategy.equals(LOCKING_STRATEGY.DEFAULT);
      return doReadRecord(collection, rid, fetchPreviousRid, fetchNextRid);
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
      return doReadRecord(collection, rid, fetchPreviousRid, fetchNextRid);
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
      final StorageCollection collection;
      try {
        collection = doGetAndCheckCollection(rid.getCollectionId());
      } catch (IllegalArgumentException e) {
        return false;
      }

      return doRecordExists(name, collection, rid);
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
      return doRecordExists(name, collection, rid);
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
    assert storageTx == null || storageTx.clientTx().getId() == clientTx.getId();
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

  private PhysicalPosition doCreateRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      @Nonnull final byte[] content,
      int recordVersion,
      final byte recordType,
      final RecordCallback<Long> callback,
      final StorageCollection collection,
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

    collection.meters().create().record();
    PhysicalPosition ppos;
    try {
      ppos = collection.createRecord(content, recordVersion, recordType, allocated,
          atomicOperation);
      if (rid instanceof ChangeableRecordId changeableRecordId) {
        changeableRecordId.setCollectionPosition(ppos.collectionPosition);
      } else {
        throw new DatabaseException(name,
            "Provided record is not new and its position can not be changed");
      }
    } catch (final Exception e) {
      LogManager.instance()
          .error(this, "Error on creating record in collection: " + collection, e);
      throw DatabaseException.wrapException(
          new StorageException(name, "Error during creation of record"), e, name);
    }

    if (callback != null) {
      callback.call(rid, ppos.collectionPosition);
    }

    if (logger.isDebugEnabled()) {
      LogManager.instance()
          .debug(this, "Created record %s v.%s size=%d bytes", logger, rid, recordVersion,
              content.length);
    }

    return ppos;
  }

  private int doUpdateRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      byte[] content,
      final int version,
      final byte recordType,
      final StorageCollection collection) {

    collection.meters().update().record();
    try {
      final var ppos =
          collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()));

      if (ppos == null || ppos.recordVersion != version) {
        final var dbVersion = ppos == null ? -1 : ppos.recordVersion;
        throw new ConcurrentModificationException(
            name, rid, dbVersion, version, RecordOperation.UPDATED);
      }

      ppos.recordVersion = version + 1;
      collection.updateRecord(
          rid.getCollectionPosition(), content, ppos.recordVersion, recordType,
          atomicOperation);

      final var newRecordVersion = ppos.recordVersion;
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
              "Error on updating record " + rid + " (collection: " + collection.getName()
                  + ")"),
          ioe, name);
    }
  }

  private void doDeleteRecord(
      final AtomicOperation atomicOperation,
      final RecordIdInternal rid,
      final int version,
      final StorageCollection collection) {
    collection.meters().delete().record();
    try {

      final var ppos =
          collection.getPhysicalPosition(new PhysicalPosition(rid.getCollectionPosition()));

      if (ppos == null) {
        // ALREADY DELETED
        return;
      }

      // MVCC TRANSACTION: CHECK IF VERSION IS THE SAME
      if (version > -1 && ppos.recordVersion != version) {
        collection.meters().conflict().record();
        throw new ConcurrentModificationException(name
            , rid, ppos.recordVersion, version, RecordOperation.DELETED);
      }

      collection.deleteRecord(atomicOperation, ppos.collectionPosition);

      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Deleted record %s v.%s", logger, rid, version);
      }

    } catch (final IOException ioe) {
      throw BaseException.wrapException(
          new StorageException(name,
              "Error on deleting record " + rid + "( collection: " + collection.getName()
                  + ")"),
          ioe, name);
    }
  }

  @Nonnull
  private ReadRecordResult doReadRecord(
      final StorageCollection collection, final RecordIdInternal rid, boolean fetchPreviousRid,
      boolean fetchNextRid) {
    collection.meters().read().record();
    try {

      final var buff = collection.readRecord(rid.getCollectionPosition());
      RecordIdInternal prevRid = null;
      RecordIdInternal nextRid = null;

      if (fetchNextRid) {
        var positions = collection.higherPositions(
            new PhysicalPosition(rid.getCollectionPosition()), 1);
        if (positions != null && positions.length > 0) {
          nextRid = new RecordId(rid.getCollectionId(), positions[0].collectionPosition);
        }
      }

      if (fetchPreviousRid) {
        var positions = collection.lowerPositions(
            new PhysicalPosition(rid.getCollectionPosition()),
            1);
        if (positions != null && positions.length > 0) {
          prevRid = new RecordId(rid.getCollectionId(), positions[0].collectionPosition);
        }
      }

      if (logger.isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "Read record %s v.%s size=%d bytes",
                logger, rid,
                buff.version(),
                buff.buffer() != null ? buff.buffer().length : 0);
      }

      return new ReadRecordResult(buff, prevRid, nextRid);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(name, "Error during read of record with rid = " + rid), e, name);
    }
  }

  private static boolean doRecordExists(String dbName,
      final StorageCollection collectionSegment,
      final RID rid) {
    try {
      return collectionSegment.exists(rid.getCollectionPosition());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(dbName, "Error during read of record with rid = " + rid), e,
          dbName);
    }
  }

  private int createCollectionFromConfig(final StorageCollectionConfiguration config)
      throws IOException {
    var collection = collectionMap.get(config.getName().toLowerCase());

    if (collection != null) {
      collection.configure(this, config);
      return -1;
    }

    collection =
        StorageCollectionFactory.createCollection(
            config.getName(), configuration.getVersion(), config.getBinaryVersion(), this);

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
      if (collectionMap.containsKey(collection.getName().toLowerCase())) {
        throw new ConfigurationException(name,
            "Cannot add collection '"
                + collection.getName()
                + "' because it is already registered in database '"
                + name
                + "'");
      }
      // CREATE AND ADD THE NEW REF SEGMENT
      collectionMap.put(collection.getName().toLowerCase(), collection);
      id = collection.getId();
    } else {
      id = collections.size();
    }

    setCollection(id, collection);

    return id;
  }

  private int doAddCollection(final AtomicOperation atomicOperation,
      final String collectionName)
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
      collectionName = collectionName.toLowerCase();

      collection =
          StorageCollectionFactory.createCollection(
              collectionName,
              configuration.getVersion(),
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

      configuration
          .updateCollection(atomicOperation, collection.generateCollectionConfig());

      linkCollectionsBTreeManager.createComponent(atomicOperation, createdCollectionId);
    }

    return createdCollectionId;
  }

  private int doAddCollection(
      final AtomicOperation atomicOperation) throws IOException {
    final PaginatedCollection collection;

    var nextIndex = collections.size();
    var collectionName = "$entity_collection_" + nextIndex;
    collection =
        StorageCollectionFactory.createCollection(
            collectionName,
            configuration.getVersion(),
            configuration
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.STORAGE_COLLECTION_VERSION),
            this);
    collection.configure(collections.size(), collectionName);

    var createdCollectionId = -1;
    collection.create(atomicOperation);
    createdCollectionId = registerCollection(collection);
    assert createdCollectionId == nextIndex;

    configuration
        .updateCollection(atomicOperation, collection.generateCollectionConfig());

    linkCollectionsBTreeManager.createComponent(atomicOperation, createdCollectionId);

    return createdCollectionId;
  }

  @Override
  public void setCollectionAttribute(final int id, final ATTRIBUTES attribute,
      final Object value) {
    checkBackupRunning();
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

      atomicOperationsManager.calculateInsideAtomicOperation(
          null,
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

  private boolean doSetCollectionAttributed(
      final AtomicOperation atomicOperation,
      final ATTRIBUTES attribute,
      final Object value,
      final StorageCollection collection) {
    final var stringValue = Optional.ofNullable(value).map(Object::toString).orElse(null);
    switch (attribute) {
      case NAME:
        Objects.requireNonNull(stringValue);

        final var oldName = collection.getName();
        collection.setCollectionName(stringValue);
        collectionMap.remove(oldName.toLowerCase());
        collectionMap.put(stringValue.toLowerCase(), collection);
        break;
      case CONFLICTSTRATEGY:
        collection.setRecordConflictStrategy(stringValue);
        break;
      default:
        throw new IllegalArgumentException(
            "Runtime change of attribute '" + attribute + "' is not supported");
    }

    configuration
        .updateCollection(atomicOperation,
            ((PaginatedCollection) collection).generateCollectionConfig());
    return true;
  }

  private boolean dropCollectionInternal(final AtomicOperation atomicOperation,
      final int collectionId)
      throws IOException {
    final var collection = collections.get(collectionId);

    if (collection == null) {
      return true;
    }

    collection.delete(atomicOperation);

    collectionMap.remove(collection.getName().toLowerCase());
    collections.set(collectionId, null);

    return false;
  }

  protected void doShutdown() throws IOException {

    shutdownDuration.timed(() -> {
      if (status == STATUS.CLOSED) {
        return;
      }

      if (status != STATUS.OPEN && !isInError()) {
        throw BaseException.wrapException(
            new StorageException(name,
                "Storage " + name + " was not opened, so can not be closed"),
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
              configuration.close(atomicOperation);
            });

        linkCollectionsBTreeManager.close();

        // we close all files inside cache system so we only clear collection metadata
        collections.clear();
        collectionMap.clear();
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
          new StorageException(name,
              "Storage " + name + " was not opened, so can not be closed"),
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

        linkCollectionsBTreeManager.close();

        // we close all files inside cache system so we only clear collection metadata
        collections.clear();
        collectionMap.clear();
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
      case RecordOperation.CREATED: {
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
          ppos =
              doCreateRecord(
                  atomicOperation,
                  rid,
                  stream,
                  rec.getVersion(),
                  recordType,
                  null,
                  collection, allocated);

          rec.setVersion(ppos.recordVersion);
        } else {
          final var updatedVersion =
              doUpdateRecord(
                  atomicOperation,
                  rid,
                  stream,
                  -2,
                  rec.getRecordType(),
                  collection);
          rec.setVersion(updatedVersion);
        }
        break;
      }
      case RecordOperation.UPDATED: {
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
                stream,
                rec.getVersion(),
                rec.getRecordType(),
                collection);
        rec.setVersion(version);
        break;
      }
      case RecordOperation.DELETED: {
        if (rec instanceof EntityImpl entity) {
          LinkBagDeleter.deleteAllRidBags(entity, frontendTransaction);
        }
        doDeleteRecord(atomicOperation, rid, rec.getVersionNoLoad(),
            collection);
        break;
      }
      default:
        throw new StorageException(name, "Unknown record operation " + txEntry.type);
    }

    // RESET TRACKING
    if (rec instanceof EntityImpl) {
      ((EntityImpl) rec).clearTrackData();
      ((EntityImpl) rec).clearTransactionTrackData();
    }

    rec.unsetDirty();
  }

  private void checkCollectionSegmentIndexRange(final int iCollectionId) {
    if (iCollectionId < 0 || iCollectionId > collections.size() - 1) {
      throw new IllegalArgumentException(
          "Collection segment #" + iCollectionId + " does not exist in database '" + name
              + "'");
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

      configuration
          .setConfigurationUpdateListener(storageConfigurationUpdateListener);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void pauseConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      configuration.pauseUpdateNotifications();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public void fireConfigurationUpdateNotifications() {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();
      configuration.fireUpdateNotifications();
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

  private void lockIndexes(
      final TreeMap<String, FrontendTransactionIndexChanges> indexes) {
    for (final var changes : indexes.values()) {
      var indexId = changes.getIndex().getIndexId();
      var indexEngine = indexEngines.get(indexId);
      indexEngine.acquireAtomicExclusiveLock();
    }
  }

  private static void lockCollections(
      final TreeMap<Integer, StorageCollection> collectionsToLock) {
    for (final var collection : collectionsToLock.values()) {
      collection.acquireAtomicExclusiveLock();
    }
  }

  private void lockLinkBags(
      final TreeMap<Integer, StorageCollection> collections) {
    final var atomicOperation = atomicOperationsManager.getCurrentOperation();

    for (final var collectionId : collections.keySet()) {
      atomicOperationsManager.acquireExclusiveLockTillOperationComplete(
          atomicOperation, LinkCollectionsBTreeManagerShared.generateLockName(collectionId));
    }
  }

  protected RuntimeException logAndPrepareForRethrow(final RuntimeException runtimeException) {
    if (!(runtimeException instanceof HighLevelException
        || runtimeException instanceof NeedRetryException
        || runtimeException instanceof InternalErrorException
        || runtimeException instanceof IllegalArgumentException)) {
      final var iAdditionalArgs =
          new Object[]{
              System.identityHashCode(runtimeException), getURL(),
              YouTrackDBConstants.getVersion()
          };
      LogManager.instance()
          .error(this, "Exception `%08X` in storage `%s`: %s", runtimeException,
              iAdditionalArgs);
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
          new Object[]{System.identityHashCode(error), getURL(),
              YouTrackDBConstants.getVersion()};
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
          configuration;

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
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          configuration;

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
      final var storageConfiguration = configuration;

      makeStorageDirty();
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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

      final var storageConfiguration = configuration;

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
          configuration;

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
          configuration;

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
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> storageConfiguration.setLocaleCountry(atomicOperation,
              localeCountry));
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
  public final void setCollectionSelection(final String collectionSelection) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              storageConfiguration.setCollectionSelection(atomicOperation,
                  collectionSelection));
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
  public final void setMinimumCollections(final int minimumCollections) {
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();

      final var storageConfiguration =
          configuration;

      makeStorageDirty();

      storageConfiguration.setMinimumCollections(minimumCollections);
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
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
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
          configuration;

      makeStorageDirty();

      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation -> storageConfiguration.setProperty(atomicOperation, property,
              value));
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
          configuration;

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
          configuration;

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
              this, "Error during flushing of data for fuzzy checkpoint, in storage %s", e,
              name);
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
          final var segment = collectionMap.get(collectionName.toLowerCase());
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
    while (isIncrementalBackupRunning()) {
      try {
        backupIsDone.await();
      } catch (java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("Interrupted wait for backup to finish"), e, name);
      }
    }
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal<DatabaseSession> sessionPool, String
          query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener, Map<String, ?> args) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    var queryListener = new LiveQueryListenerImpl(listener, query, sessionPool, (Map) args);
    return new YTLiveQueryMonitorEmbedded(queryListener.getToken(), sessionPool);
  }

  @Override
  public LiveQueryMonitor live(DatabasePoolInternal<DatabaseSession> sessionPool, String
          query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener, Object... args) {
    var queryListener = new LiveQueryListenerImpl(listener, query, sessionPool, args);
    return new YTLiveQueryMonitorEmbedded(queryListener.getToken(), sessionPool);
  }

  protected void checkBackupRunning() {
    waitBackup();
  }

  @Override
  public YouTrackDBInternalEmbedded getContext() {
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

  @Override
  public boolean isIncrementalBackupRunning() {
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
