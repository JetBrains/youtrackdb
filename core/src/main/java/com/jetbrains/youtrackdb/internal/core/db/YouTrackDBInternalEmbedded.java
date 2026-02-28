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

package com.jetbrains.youtrackdb.internal.core.db;

import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.WARNING_DEFAULT_USERS;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.thread.SourceTraceExecutorService;
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.script.ScriptManager;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.security.DefaultSecuritySystem;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class YouTrackDBInternalEmbedded implements YouTrackDBInternal {

  /**
   * Keeps track of next possible storage id.
   */
  private static final AtomicInteger nextStorageId = new AtomicInteger();

  /**
   * Storage IDs current assigned to the storage.
   */
  private static final Set<Integer> currentStorageIds =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  private final Map<String, AbstractStorage> storages = new ConcurrentHashMap<>();
  private final Map<String, SharedContext> sharedContexts = new ConcurrentHashMap<>();
  private final Set<DatabasePoolInternal> pools = Collections.newSetFromMap(
      new ConcurrentHashMap<>());
  private final YouTrackDBConfigImpl configuration;
  private final Path basePath;
  private final Engine memory;
  private final Engine disk;
  private final YouTrackDBEnginesManager youTrack;
  private final boolean serverMode;
  private final CachedDatabasePoolFactory cachedPoolFactory;
  private volatile boolean open = true;
  private final ExecutorService executor;
  private final ExecutorService ioExecutor;
  private final Timer timer;
  private TimerTask autoCloseTimer = null;
  private final ScriptManager scriptManager = new ScriptManager();
  private final SystemDatabase systemDatabase;
  private final DefaultSecuritySystem securitySystem;
  private final CommandTimeoutChecker timeoutChecker;

  private volatile long maxWALSegmentSize = -1;
  private volatile long doubleWriteLogMaxSegSize = -1;

  private final ReentrantLock fileMetadataLock = new ReentrantLock();

  public YouTrackDBInternalEmbedded(String directoryPath, YouTrackDBConfig configuration,
      YouTrackDBEnginesManager youTrack, boolean serverMode) {
    super();

    this.youTrack = youTrack;
    this.serverMode = serverMode;
    youTrack.onEmbeddedFactoryInit(this);
    memory = youTrack.getEngine("memory");
    disk = youTrack.getEngine("disk");
    basePath = Path.of(directoryPath.trim()).toAbsolutePath().normalize();

    this.configuration =
        (YouTrackDBConfigImpl) (configuration != null ? configuration
            : YouTrackDBConfig.defaultConfig());

    MemoryAndLocalPaginatedEnginesInitializer.INSTANCE.initialize();

    youTrack.addYouTrackDB(this);
    executor = newExecutor();
    ioExecutor = newIoExecutor();
    var timerName = "embedded:" + basePath;
    timer = new Timer("YouTrackDB Timer[" + timerName + "]");

    cachedPoolFactory = createCachedDatabasePoolFactory();

    initAutoClose();

    var timeout = getLongConfig(GlobalConfiguration.COMMAND_TIMEOUT);
    timeoutChecker = new CommandTimeoutChecker(timeout, this);
    systemDatabase = new SystemDatabase(this);
    securitySystem = new DefaultSecuritySystem();

    securitySystem.activate(this, this.configuration.getSecurityConfig());
  }

  private void initAutoClose() {

    var autoClose = getBoolConfig(GlobalConfiguration.AUTO_CLOSE_AFTER_DELAY);
    if (autoClose) {
      var autoCloseDelay = getIntConfig(GlobalConfiguration.AUTO_CLOSE_DELAY);
      final var delay = (long) autoCloseDelay * 60 * 1000;
      initAutoClose(delay);
    }
  }

  @Nullable
  private ExecutorService newIoExecutor() {
    if (getBoolConfig(GlobalConfiguration.EXECUTOR_POOL_IO_ENABLED)) {
      var ioSize = excutorMaxSize(GlobalConfiguration.EXECUTOR_POOL_IO_MAX_SIZE);
      var exec =
          ThreadPoolExecutors.newScalingThreadPool(
              "YouTrackDB-IO", 1, excutorBaseSize(ioSize), ioSize, 30, TimeUnit.MINUTES);
      if (getBoolConfig(GlobalConfiguration.EXECUTOR_DEBUG_TRACE_SOURCE)) {
        exec = new SourceTraceExecutorService(exec);
      }
      return exec;
    } else {
      return null;
    }
  }

  private ExecutorService newExecutor() {
    var size = excutorMaxSize(GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE);
    var exec =
        ThreadPoolExecutors.newScalingThreadPool(
            "YouTrackDBEmbedded", 1, excutorBaseSize(size), size, 30, TimeUnit.MINUTES);
    if (getBoolConfig(GlobalConfiguration.EXECUTOR_DEBUG_TRACE_SOURCE)) {
      exec = new SourceTraceExecutorService(exec);
    }
    return exec;
  }

  private boolean getBoolConfig(GlobalConfiguration config) {
    return this.configuration.getConfiguration().getValueAsBoolean(config);
  }

  private int getIntConfig(GlobalConfiguration config) {
    return this.configuration.getConfiguration().getValueAsInteger(config);
  }

  private long getLongConfig(GlobalConfiguration config) {
    return this.configuration.getConfiguration().getValueAsLong(config);
  }

  private int excutorMaxSize(GlobalConfiguration config) {
    var size = getIntConfig(config);
    if (size == 0) {
      LogManager.instance()
          .warn(
              this,
              "Configuration "
                  + config.getKey()
                  + " has a value 0 using number of CPUs as base value");
      size = Runtime.getRuntime().availableProcessors();
    } else if (size <= -1) {
      size = Runtime.getRuntime().availableProcessors();
    }
    return size;
  }

  private static int excutorBaseSize(int size) {
    int baseSize;

    if (size > 10) {
      baseSize = size / 10;
    } else if (size > 4) {
      baseSize = size / 2;
    } else {
      baseSize = size;
    }
    return baseSize;
  }

  private CachedDatabasePoolFactory createCachedDatabasePoolFactory() {
    var capacity = getIntConfig(GlobalConfiguration.DB_CACHED_POOL_CAPACITY);
    long timeout = getIntConfig(GlobalConfiguration.DB_CACHED_POOL_CLEAN_UP_TIMEOUT);
    return new CachedDatabasePoolFactoryImpl(this, capacity, timeout);
  }

  public void initAutoClose(long delay) {
    final var scheduleTime = delay / 3;
    autoCloseTimer =
        new TimerTask() {
          @Override
          public void run() {
            YouTrackDBInternalEmbedded.this.execute(() -> checkAndCloseStorages(delay));
          }
        };
    schedule(autoCloseTimer, scheduleTime, scheduleTime);
  }

  private synchronized void checkAndCloseStorages(long delay) {
    Set<String> toClose = new HashSet<>();
    for (var storage : storages.values()) {
      if (storage.getType().equalsIgnoreCase(DatabaseType.DISK.name())
          && storage.getSessionsCount() == 0) {
        var currentTime = System.currentTimeMillis();
        if (currentTime > storage.getLastCloseTime() + delay) {
          toClose.add(storage.getName());
        }
      }
    }
    for (var storage : toClose) {
      forceDatabaseClose(storage);
    }
  }

  private long calculateInitialMaxWALSegSize() throws IOException {
    var walPath =
        configuration.getConfiguration().getValueAsString(GlobalConfiguration.WAL_LOCATION);

    if (walPath == null) {
      walPath = basePath.toString();
    }

    final var fileStore = Files.getFileStore(Paths.get(walPath));
    final var freeSpace = fileStore.getUsableSpace();

    long filesSize;
    try {
      filesSize =
          Files.walk(Paths.get(walPath))
              .mapToLong(
                  p -> {
                    try {
                      if (Files.isRegularFile(p)) {
                        return Files.size(p);
                      }

                      return 0;
                    } catch (IOException | UncheckedIOException e) {
                      LogManager.instance()
                          .error(this, "Error during calculation of free space for database", e);
                      return 0;
                    }
                  })
              .sum();
    } catch (IOException | UncheckedIOException e) {
      LogManager.instance().error(this, "Error during calculation of free space for database", e);

      filesSize = 0;
    }

    var maxSegSize = getLongConfig(GlobalConfiguration.WAL_MAX_SEGMENT_SIZE) * 1024 * 1024;

    if (maxSegSize <= 0) {
      var sizePercent = getIntConfig(GlobalConfiguration.WAL_MAX_SEGMENT_SIZE_PERCENT);
      if (sizePercent <= 0) {
        throw new DatabaseException(basePath.toString(),
            "Invalid configuration settings. Can not set maximum size of WAL segment");
      }

      maxSegSize = (freeSpace + filesSize) / 100 * sizePercent;
    }

    final var minSegSizeLimit = (long) (freeSpace * 0.25);

    var minSegSize = getLongConfig(GlobalConfiguration.WAL_MIN_SEG_SIZE) * 1024 * 1024;

    if (minSegSize > minSegSizeLimit) {
      minSegSize = minSegSizeLimit;
    }

    if (minSegSize > 0 && maxSegSize < minSegSize) {
      maxSegSize = minSegSize;
    }
    return maxSegSize;
  }

  private long calculateDoubleWriteLogMaxSegSize(Path storagePath) throws IOException {
    final var fileStore = Files.getFileStore(storagePath);
    final var freeSpace = fileStore.getUsableSpace();

    long filesSize;
    try {
      filesSize =
          Files.walk(storagePath)
              .mapToLong(
                  p -> {
                    try {
                      if (Files.isRegularFile(p)) {
                        return Files.size(p);
                      }

                      return 0;
                    } catch (IOException | UncheckedIOException e) {
                      LogManager.instance()
                          .error(this, "Error during calculation of free space for database", e);

                      return 0;
                    }
                  })
              .sum();
    } catch (IOException | UncheckedIOException e) {
      LogManager.instance().error(this, "Error during calculation of free space for database", e);

      filesSize = 0;
    }

    var maxSegSize =
        getLongConfig(GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_MAX_SEG_SIZE) * 1024 * 1024;

    if (maxSegSize <= 0) {
      var sizePercent =
          getIntConfig(GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_MAX_SEG_SIZE_PERCENT);

      if (sizePercent <= 0) {
        throw new DatabaseException(basePath.toString(),
            "Invalid configuration settings. Can not set maximum size of WAL segment");
      }

      maxSegSize = (freeSpace + filesSize) / 100 * sizePercent;
    }

    var minSegSize =
        getLongConfig(GlobalConfiguration.STORAGE_DOUBLE_WRITE_LOG_MIN_SEG_SIZE) * 1024 * 1024;

    if (minSegSize > 0 && maxSegSize < minSegSize) {
      maxSegSize = minSegSize;
    }
    return maxSegSize;
  }

  @Override
  public YouTrackDBImpl newYouTrackDb() {
    return YTDBGraphFactory.ytdbInstance(basePath.toString(), () -> this);
  }

  @Override
  public DatabaseSessionEmbedded open(String name, String user, String password) {
    return open(name, user, password, null);
  }

  public DatabaseSessionEmbedded openNoAuthenticate(String name, String user) {
    checkDatabaseName(name);
    try {
      final DatabaseSessionEmbedded embedded;
      var config = solveConfig(null);
      synchronized (this) {
        checkOpen();
        var storage = getAndOpenStorage(name, config);
        embedded = newSessionInstance(storage, config);
      }
      embedded.rebuildIndexes();
      embedded.internalOpen(user, "nopwd", false);
      embedded.callOnOpenListeners();
      return embedded;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(basePath.toString(), "Cannot open database '" + name + "'"), e,
          basePath.toString());
    }
  }

  private DatabaseSessionEmbedded newSessionInstance(
      AbstractStorage storage, YouTrackDBConfigImpl config) {
    var embedded = new DatabaseSessionEmbedded(storage, serverMode);
    embedded.init(config, getOrCreateSharedContext(storage));
    return embedded;
  }

  private static DatabaseSessionEmbedded newCreateSessionInstance(
      AbstractStorage storage, YouTrackDBConfigImpl config, SharedContext sharedContext) {
    var embedded = new DatabaseSessionEmbedded(storage, false);
    embedded.internalCreate(config, sharedContext);
    return embedded;
  }

  public DatabaseSessionEmbedded openNoAuthorization(String name) {
    checkDatabaseName(name);
    try {
      final DatabaseSessionEmbedded embedded;
      var config = solveConfig(null);
      synchronized (this) {
        checkOpen();
        var storage = getAndOpenStorage(name, config);
        embedded = newSessionInstance(storage, config);
      }
      embedded.rebuildIndexes();
      embedded.callOnOpenListeners();
      return embedded;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(basePath.toString(), "Cannot open database '" + name + "'"), e,
          basePath.toString());
    }
  }

  @Override
  public DatabaseSessionEmbedded open(
      String name, String user, String password, YouTrackDBConfig config) {
    checkDatabaseName(name);
    checkDefaultPassword(name, user, password);
    try {
      final DatabaseSessionEmbedded embedded;
      synchronized (this) {
        checkOpen();
        config = solveConfig((YouTrackDBConfigImpl) config);
        var storage = getAndOpenStorage(name, (YouTrackDBConfigImpl) config);

        embedded = newSessionInstance(storage, (YouTrackDBConfigImpl) config);
      }
      embedded.rebuildIndexes();
      embedded.internalOpen(user, password);
      embedded.callOnOpenListeners();
      return embedded;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(basePath.toString(), "Cannot open database '" + name + "'"), e,
          basePath.toString());
    }
  }

  @Override
  public DatabaseSessionEmbedded open(
      AuthenticationInfo authenticationInfo, YouTrackDBConfig config) {
    try {
      final DatabaseSessionEmbedded embedded;
      synchronized (this) {
        checkOpen();
        config = solveConfig((YouTrackDBConfigImpl) config);
        if (authenticationInfo.getDatabase().isEmpty()) {
          throw new SecurityException("Authentication info do not contain the database");
        }
        var database = authenticationInfo.getDatabase().get();
        var storage = getAndOpenStorage(database,
            (YouTrackDBConfigImpl) config);
        embedded = newSessionInstance(storage, (YouTrackDBConfigImpl) config);
      }
      embedded.rebuildIndexes();
      embedded.internalOpen(authenticationInfo);
      embedded.callOnOpenListeners();
      return embedded;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException(basePath.toString(),
              "Cannot open database '" + authenticationInfo.getDatabase() + "'"),
          e, basePath.toString());
    }
  }

  private AbstractStorage getAndOpenStorage(String name, YouTrackDBConfigImpl config) {
    var storage = getOrInitStorage(name);
    // THIS OPEN THE STORAGE ONLY THE FIRST TIME
    try {
      // THIS OPEN THE STORAGE ONLY THE FIRST TIME
      storage.open(config.getConfiguration());
    } catch (RuntimeException e) {
      storages.remove(storage.getName());

      throw e;
    }
    return storage;
  }

  private void checkDefaultPassword(String database, String user, String password) {
    if ((("admin".equals(user) && "admin".equals(password))
        || ("reader".equals(user) && "reader".equals(password))
        || ("writer".equals(user) && "writer".equals(password)))
        && WARNING_DEFAULT_USERS.getValueAsBoolean()) {
      LogManager.instance()
          .warn(
              this,
              String.format(
                  "IMPORTANT! Using default password is unsafe, please change password for user"
                      + " '%s' on database '%s'",
                  user, database));
    }
  }

  private YouTrackDBConfigImpl solveConfig(YouTrackDBConfigImpl config) {
    if (config != null) {
      config.setParent(this.configuration);
      return config;
    } else {
      var cfg = (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig();
      cfg.setParent(this.configuration);
      return cfg;
    }
  }

  @Override
  public DatabaseSessionEmbedded poolOpen(
      String name, String user, String password, DatabasePoolInternal pool) {
    final DatabaseSessionEmbedded embedded;
    synchronized (this) {
      checkOpen();
      var storage = getAndOpenStorage(name, pool.getConfig());
      embedded = newPooledSessionInstance(pool, storage, getOrCreateSharedContext(storage),
          serverMode);
    }
    embedded.rebuildIndexes();
    embedded.internalOpen(user, password);
    embedded.callOnOpenListeners();
    return embedded;
  }


  public DatabaseSessionEmbedded poolOpenNoAuthenticate(String name, String user,
      DatabasePoolInternal pool) {
    final DatabaseSessionEmbedded embedded;
    synchronized (this) {
      checkOpen();
      var storage = getAndOpenStorage(name, pool.getConfig());
      embedded = newPooledSessionInstance(pool, storage, getOrCreateSharedContext(storage),
          serverMode);
    }

    embedded.rebuildIndexes();
    embedded.internalOpen(user, "nopwd", false);
    embedded.callOnOpenListeners();

    return embedded;
  }

  private static DatabaseSessionEmbedded newPooledSessionInstance(
      DatabasePoolInternal pool, AbstractStorage storage,
      SharedContext sharedContext, boolean serverMode) {
    var embedded = new DatabaseSessionEmbeddedPooled(pool, storage, serverMode);
    embedded.init(pool.getConfig(), sharedContext);
    return embedded;
  }

  private AbstractStorage getOrInitStorage(String name) {
    var storage = storages.get(name);
    if (storage == null) {
      if (basePath == null) {
        throw new DatabaseException(basePath.toString(),
            "Cannot open database '" + name + "' because it does not exists");
      }
      var storagePath = Paths.get(buildName(name));
      if (DiskStorage.exists(storagePath)) {
        name = storagePath.getFileName().toString();
      }

      storage = storages.get(name);
      if (storage == null) {
        storage =
            (AbstractStorage)
                disk.createStorage(
                    buildName(name),
                    getMaxWalSegSize(),
                    getDoubleWriteLogMaxSegSize(),
                    generateStorageId(),
                    this);
        if (storage.exists()) {
          storages.put(name, storage);
        }
      }
    }
    return storage;
  }

  private static int generateStorageId() {
    var storageId = Math.abs(nextStorageId.getAndIncrement());
    while (!currentStorageIds.add(storageId)) {
      storageId = Math.abs(nextStorageId.getAndIncrement());
    }

    return storageId;
  }

  public synchronized AbstractStorage getStorage(String name) {
    return storages.get(name);
  }

  private String buildName(String name) {
    if (basePath == null) {
      throw new DatabaseException(basePath.toString(),
          "YouTrackDB instanced created without physical path, only memory databases are allowed");
    }
    return basePath + "/" + name;
  }

  private long getDoubleWriteLogMaxSegSize() {
    try {
      var currentSize = doubleWriteLogMaxSegSize;
      if (currentSize > 0) {
        return currentSize;
      }

      fileMetadataLock.lock();
      try {
        currentSize = doubleWriteLogMaxSegSize;
        if (currentSize > 0) {
          return currentSize;
        }

        if (!Files.exists(basePath)) {
          LogManager.instance()
              .info(this, "Directory " + basePath + " does not exist, try to create it.");

          Files.createDirectories(basePath);
        }

        doubleWriteLogMaxSegSize = calculateDoubleWriteLogMaxSegSize(basePath);
      } finally {
        fileMetadataLock.unlock();
      }

      return doubleWriteLogMaxSegSize;
    } catch (IOException e) {
      throw CoreException.wrapException(
          new DatabaseException(basePath.toString(),
              "Error during calculation of maximum of size of double write log segment."), e,
          basePath.toString());
    }
  }

  private long getMaxWalSegSize() {
    try {
      var currentSize = maxWALSegmentSize;
      if (currentSize > 0) {
        return currentSize;
      }

      fileMetadataLock.lock();
      try {
        currentSize = maxWALSegmentSize;
        if (currentSize > 0) {
          return currentSize;
        }

        if (!Files.exists(basePath)) {
          LogManager.instance()
              .info(this, "Directory " + basePath + " does not exist, try to create it.");

          Files.createDirectories(basePath);
        }

        var newSize = calculateInitialMaxWALSegSize();
        if (newSize <= 0) {
          throw new DatabaseException(basePath.toString(),
              "Invalid configuration settings. Can not set maximum size of WAL segment");
        }

        maxWALSegmentSize = newSize;
        LogManager.instance()
            .info(
                this, "WAL maximum segment size is set to %,d MB", newSize / 1024 / 1024);
      } finally {
        fileMetadataLock.unlock();
      }

      return maxWALSegmentSize;
    } catch (IOException e) {
      throw CoreException.wrapException(
          new DatabaseException(basePath.toString(),
              "Error during calculation of maximum of size of WAL segment."), e,
          basePath.toString());
    }
  }

  @Override
  public void create(String name, String user, String password, DatabaseType type) {
    create(name, user, password, type, null);
  }

  @Override
  public void create(
      String name, String user, String password, DatabaseType type, YouTrackDBConfig config) {
    create(name, user, password, type, config, true, null);
  }

  @Override
  public void create(
      String name,
      String user,
      String password,
      DatabaseType type,
      YouTrackDBConfig config,
      boolean failIfExists,
      DatabaseTask<Void> createOps) {
    if (createOps != null) {
      createStorage(name, type, (YouTrackDBConfigImpl) config, failIfExists,
          (storage, embedded) -> createOps.call(embedded));
    } else {
      createStorage(name, type, (YouTrackDBConfigImpl) config, failIfExists, null);
    }
  }

  @Override
  public void restore(
      String name,
      String path,
      YouTrackDBConfig config) {
    restore(name, path, null, config);
  }

  @Override
  public void restore(String name, String path,
      @Nullable String expectedUUID, YouTrackDBConfig config) {
    createStorage(name, DatabaseType.DISK, (YouTrackDBConfigImpl) config, true,
        (storage, embedded) -> {
          storage.restoreFromBackup(Path.of(path), expectedUUID);
          embedded.getSharedContext().getSchema().reload(embedded);
          embedded.getSharedContext().getIndexManager().reload(embedded);
        });
  }

  @Override
  public void restore(String name, Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
      YouTrackDBConfig config) {
    createStorage(name, DatabaseType.DISK, (YouTrackDBConfigImpl) config, true,
        (storage, embedded) -> {
          storage.restoreFromBackup(ibuFilesSupplier, ibuInputStreamSupplier, expectedUUID);
          embedded.getSharedContext().getSchema().reload(embedded);
          embedded.getSharedContext().getIndexManager().reload(embedded);
        });
  }

  private void createStorage(String name,
      DatabaseType type,
      YouTrackDBConfigImpl config, boolean failIfExists,
      BiConsumer<AbstractStorage, DatabaseSessionEmbedded> createOps) {
    checkDatabaseName(name);
    final DatabaseSessionEmbedded embedded;
    synchronized (this) {
      if (!exists(name)) {
        try {
          var lowerCaseName = name.toLowerCase(Locale.ROOT);
          if (lowerCaseName.startsWith("ytdb")) {
            throw new IllegalArgumentException("Usage of database names started with 'ytdb'"
                + " is prohibited as it is used as prefix of the names of GraphTraversal instances, provided name : "
                + name);
          }
          if (lowerCaseName.startsWith("server")) {
            throw new IllegalArgumentException(
                "Database name can not start with 'server' prefix, provided name is : " + name);
          }

          config = solveConfig(config);
          AbstractStorage storage;
          if (type == DatabaseType.MEMORY) {
            storage =
                (AbstractStorage)
                    memory.createStorage(
                        name,
                        -1,
                        -1,
                        generateStorageId(),
                        this);
          } else {
            storage =
                (AbstractStorage)
                    disk.createStorage(
                        buildName(name),
                        getMaxWalSegSize(),
                        getDoubleWriteLogMaxSegSize(),
                        generateStorageId(),
                        this);
          }
          storages.put(name, storage);
          embedded = internalCreate(config, storage);

          if (createOps != null) {
            createOps.accept(storage, embedded);
          }
        } catch (Exception e) {
          throw BaseException.wrapException(
              new DatabaseException(basePath.toString(), "Cannot create database '" + name + "'"),
              e,
              basePath.toString());
        }

        embedded.callOnCreateListeners();
      } else {
        if (failIfExists) {
          throw new DatabaseException(basePath.toString(),
              "Cannot create new database '" + name + "' because it already exists");
        } else {
          LogManager.instance()
              .info(this, "Database '%s' already exists, nothing to do", name);
          return;
        }

      }
    }
    embedded.callOnCreateListeners();
  }

  private DatabaseSessionEmbedded internalCreate(
      YouTrackDBConfigImpl config, AbstractStorage storage) {
    storage.create(config.getConfiguration());
    return newCreateSessionInstance(storage, config, getOrCreateSharedContext(storage));
  }

  private synchronized SharedContext getOrCreateSharedContext(
      AbstractStorage storage) {
    var result = sharedContexts.get(storage.getName());
    if (result == null) {
      result = createSharedContext(storage);
      sharedContexts.put(storage.getName(), result);
    }
    return result;
  }

  private SharedContext createSharedContext(AbstractStorage storage) {
    return new SharedContext(storage, this);
  }

  @Override
  public synchronized boolean exists(String name) {
    checkOpen();
    Storage storage = storages.get(name);
    if (storage == null) {
      if (basePath != null) {
        return DiskStorage.exists(Paths.get(buildName(name)));
      } else {
        return false;
      }
    }
    return storage.exists();
  }

  @Override
  public void drop(String name, String user, String password) {
    synchronized (this) {
      checkOpen();
    }
    checkDatabaseName(name);
    try {
      var db = openNoAuthenticate(name, user);
      for (var it = youTrack.getDbLifecycleListeners();
          it.hasNext(); ) {
        it.next().onDrop(db);
      }
      db.close();
    } finally {
      synchronized (this) {
        if (exists(name)) {
          var storage = getOrInitStorage(name);
          var sharedContext = sharedContexts.get(name);
          if (sharedContext != null) {
            sharedContext.close();
          }
          final var storageId = storage.getId();
          try {
            storage.delete();
          } finally {
            // Always remove the storage from internal maps, even if delete() threw.
            // Leaving a partially-deleted storage in the maps would poison the
            // YouTrackDB instance for all subsequent operations on any database.
            storages.remove(name);
            currentStorageIds.remove(storageId);
            sharedContexts.remove(name);
          }
        }
      }
    }
  }

  private interface DatabaseFound {

    void found(String name);
  }

  @Override
  public synchronized Set<String> listDatabases(String user, String password) {
    checkOpen();
    // SEARCH IN CONFIGURED PATHS
    final Set<String> databases = new HashSet<>();
    // SEARCH IN DEFAULT DATABASE DIRECTORY
    if (basePath != null) {
      scanDatabaseDirectory(basePath.toFile(), databases::add);
    }
    databases.addAll(this.storages.keySet());
    // TODO: Verify validity this generic permission on guest
    if (!securitySystem.isAuthorized(null, "guest", "server.listDatabases.system")) {
      databases.remove(SystemDatabase.SYSTEM_DB_NAME);
    }
    return databases;
  }

  public synchronized void loadAllDatabases() {
    if (basePath != null) {
      scanDatabaseDirectory(
          basePath.toFile(),
          (name) -> {
            if (!storages.containsKey(name)) {
              var storage = getOrInitStorage(name);
              // THIS OPEN THE STORAGE ONLY THE FIRST TIME
              storage.open(configuration.getConfiguration());
            }
          });
    }
  }

  @Override
  public DatabasePoolInternal openPool(String name, String user, String password) {
    return openPool(name, user, password, null);
  }

  @Override
  public DatabasePoolInternal openPool(
      String name, String user, String password, YouTrackDBConfig config) {
    checkDatabaseName(name);
    checkOpen();
    var pool = new DatabasePoolImpl(this, name, user, password,
        solveConfig((YouTrackDBConfigImpl) config));
    pools.add(pool);
    return pool;
  }

  @Override
  public DatabasePoolInternal cachedPool(String database, String user,
      String password) {
    return cachedPool(database, user, password, null);
  }

  @Override
  public DatabasePoolInternal cachedPool(
      String database, String user, String password, YouTrackDBConfig config) {
    checkDatabaseName(database);
    checkOpen();
    var pool =
        cachedPoolFactory.getOrCreate(database, user, password,
            solveConfig((YouTrackDBConfigImpl) config));
    pools.add(pool);
    return pool;
  }

  @Override
  public DatabasePoolInternal cachedPoolNoAuthentication(String database,
      String user, YouTrackDBConfig config) {
    checkDatabaseName(database);
    checkOpen();
    var pool =
        cachedPoolFactory.getOrCreateNoAuthentication(database, user,
            solveConfig((YouTrackDBConfigImpl) config));
    pools.add(pool);
    return pool;
  }

  @Override
  public void close() {
    if (!open) {
      return;
    }
    timeoutChecker.close();
    timer.cancel();
    securitySystem.shutdown();
    executor.shutdown();
    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        LogManager.instance().warn(this, "Failed waiting background operations termination");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    synchronized (this) {
      scriptManager.closeAll();
      internalClose();
      currentStorageIds.clear();
    }
    if (ioExecutor != null) {
      try {
        ioExecutor.shutdown();
        while (!ioExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
          LogManager.instance().warn(this, "Failed waiting background io operations termination");
          ioExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    removeShutdownHook();
  }

  @Override
  public synchronized void internalClose() {
    if (!open) {
      return;
    }
    open = false;
    this.sharedContexts.values().forEach(SharedContext::close);
    final List<AbstractStorage> storagesCopy = new ArrayList<>(storages.values());

    Exception storageException = null;

    for (var stg : storagesCopy) {
      try {
        LogManager.instance().info(this, "- shutdown storage: %s ...", stg.getName());
        stg.shutdown();
      } catch (Exception e) {
        LogManager.instance().warn(this, "-- error on shutdown storage", e);
        storageException = e;
      } catch (Error e) {
        LogManager.instance().warn(this, "-- error on shutdown storage", e);
        throw e;
      }
    }
    this.sharedContexts.clear();
    storages.clear();
    youTrack.onEmbeddedFactoryClose(this);
    if (autoCloseTimer != null) {
      autoCloseTimer.cancel();
    }

    if (storageException != null) {
      throw BaseException.wrapException(
          new StorageException(basePath.toString(), "Error during closing the storages"),
          storageException,
          basePath.toString());
    }
  }

  @Override
  public YouTrackDBConfigImpl getConfiguration() {
    return configuration;
  }

  @Override
  public void removePool(DatabasePoolInternal pool) {
    pools.remove(pool);
  }

  private static void scanDatabaseDirectory(final File directory, DatabaseFound found) {
    if (directory.exists() && directory.isDirectory()) {
      final var files = directory.listFiles();
      if (files != null) {
        for (var db : files) {
          if (db.isDirectory()) {
            for (var cf : db.listFiles()) {
              var fileName = cf.getName();
              if (fileName.equals("database.ocf")
                  || (fileName.startsWith(CollectionBasedStorageConfiguration.COMPONENT_NAME)
                  && fileName.endsWith(
                  CollectionBasedStorageConfiguration.DATA_FILE_EXTENSION))) {
                found.found(db.getName());
                break;
              }
            }
          }
        }
      }
    }
  }

  public synchronized void initCustomStorage(String name, String path) {
    DatabaseSessionEmbedded embedded = null;
    synchronized (this) {
      var exists = DiskStorage.exists(Paths.get(path));
      var storage =
          (AbstractStorage)
              disk.createStorage(
                  path, getMaxWalSegSize(), getDoubleWriteLogMaxSegSize(), generateStorageId(),
                  this);
      // TODO: Add Creation settings and parameters
      if (!exists) {
        embedded = internalCreate(configuration, storage);
      }
      storages.put(name, storage);
    }
    if (embedded != null) {
      embedded.callOnCreateListeners();
    }
  }

  @Override
  public void removeShutdownHook() {
    youTrack.removeYouTrackDB(this);
  }

  public synchronized Collection<Storage> getStorages() {
    return storages.values().stream().map((x) -> (Storage) x).collect(Collectors.toSet());
  }

  @Override
  public synchronized void forceDatabaseClose(String iDatabaseName) {
    var storage = storages.remove(iDatabaseName);
    if (storage != null) {
      var ctx = sharedContexts.remove(iDatabaseName);
      ctx.close();
      storage.shutdown();
    }
  }

  private void checkOpen() {
    if (!open) {
      throw new DatabaseException(basePath.toString(), "YouTrackDB Instance is closed");
    }
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public boolean isEmbedded() {
    return true;
  }

  @Override
  public void schedule(TimerTask task, long delay, long period) {
    timer.schedule(task, delay, period);
  }

  @Override
  public void scheduleOnce(TimerTask task, long delay) {
    timer.schedule(task, delay);
  }

  public <X> Future<X> execute(String database, String user, DatabaseTask<X> task) {
    return executor.submit(
        () -> {
          try (var session = openNoAuthenticate(database, user)) {
            return task.call(session);
          }
        });
  }

  public Future<?> execute(Runnable task) {
    return executor.submit(task);
  }

  public <X> Future<X> execute(Callable<X> task) {
    return executor.submit(task);
  }

  public <X> Future<X> executeNoAuthorizationAsync(String database, DatabaseTask<X> task) {
    return executor.submit(
        () -> {
          if (open) {
            try (var session = openNoAuthorization(database)) {
              return task.call(session);
            }
          } else {
            LogManager.instance()
                .warn(this, " Cancelled execution of task, YouTrackDB instance is closed");
            return null;
          }
        });
  }

  public <X> X executeNoAuthorizationSync(
      DatabaseSessionEmbedded database, DatabaseTask<X> task) {
    var dbName = database.getDatabaseName();
    if (open) {
      try (var session = openNoAuthorization(dbName)) {
        return task.call(session);
      } finally {
        database.activateOnCurrentThread();
      }
    } else {
      throw new DatabaseException(basePath.toString(), "YouTrackDB instance is closed");
    }
  }

  public ScriptManager getScriptManager() {
    return scriptManager;
  }

  @Override
  public SystemDatabase getSystemDatabase() {
    return systemDatabase;
  }

  @Override
  public DefaultSecuritySystem getSecuritySystem() {
    return securitySystem;
  }

  @Override
  public String getBasePath() {
    return basePath.toString();
  }

  @Override
  public boolean isMemoryOnly() {
    return basePath == null;
  }

  private void checkDatabaseName(String name) {
    Objects.requireNonNull(name, "Database name is required");
    if (name.contains("/") || name.contains(":")) {
      throw new DatabaseException(basePath.toString(),
          String.format("Invalid database name:'%s'", name));
    }
    if (name.startsWith("ytdb")) {
      throw new DatabaseException(basePath.toString(),
          String.format("Invalid database name:'%s'. Database name cannot start with 'ytdb'",
              name));
    }
    if (name.startsWith("server")) {
      throw new DatabaseException(basePath.toString(),
          String.format("Invalid database name:'%s'. Database name cannot start with 'server'",
              name));
    }
  }

  public void startCommand(@Nullable Long timeout) {
    timeoutChecker.startCommand(timeout);
  }

  public void endCommand() {
    timeoutChecker.endCommand();
  }

  @Override
  public String getConnectionUrl() {
    var connectionUrl = "embedded:";
    if (basePath != null) {
      connectionUrl += basePath;
    }
    return connectionUrl;
  }

  public ExecutorService getIoExecutor() {
    return ioExecutor;
  }
}
