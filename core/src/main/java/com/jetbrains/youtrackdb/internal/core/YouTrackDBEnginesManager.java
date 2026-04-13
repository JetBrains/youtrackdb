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
package com.jetbrains.youtrackdb.internal.core;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.listener.ListenerManger;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.Profiler;
import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.thread.SourceTraceExecutorService;
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.common.util.ClassLoaderHelper;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.cache.LocalRecordCacheFactory;
import com.jetbrains.youtrackdb.internal.core.cache.LocalRecordCacheFactoryImpl;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategyFactory;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseThreadLocalFactory;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.record.RecordFactoryManager;
import com.jetbrains.youtrackdb.internal.core.shutdown.ShutdownHandler;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTrackDBEnginesManager extends ListenerManger<YouTrackDBListener> {

  private static final Logger logger = LoggerFactory.getLogger(YouTrackDBEnginesManager.class);

  public static final String YOUTRACKDB_HOME = "YOUTRACKDB_HOME";
  public static final String URL_SYNTAX =
      "<engine>:<db-type>:<db-name>[?<db-param>=<db-value>[&]]*";

  private static volatile YouTrackDBEnginesManager instance;
  private static final ReentrantLock initLock = new ReentrantLock();

  private static volatile boolean registerDatabaseByPath = false;

  private final ConcurrentMap<String, Engine> engines = new ConcurrentHashMap<String, Engine>();

  private final AtomicReference<List<RawPair<DatabaseLifecycleListener,
      DatabaseLifecycleListener.PRIORITY>>> dbLifecycleListeners = new AtomicReference<>(List.of());
  private final ThreadGroup threadGroup;
  private final ThreadGroup storageThreadGroup;
  private final ReadWriteLock engineLock = new ReentrantReadWriteLock();

  // Storage pools — single-threaded for sequential execution guarantees.
  private final ScheduledExecutorService walFlushExecutor;
  private final ExecutorService walWriteExecutor;
  private final ScheduledExecutorService fuzzyCheckpointExecutor;
  private final ScheduledExecutorService wowCacheFlushExecutor;

  // Shared scheduled pool — replaces per-instance Timers.
  private final ScheduledExecutorService scheduledPool;

  // Main/IO executors — created lazily via factory methods, shared across all embedded instances.
  private volatile ExecutorService executor;
  private volatile ExecutorService ioExecutor;
  private final RecordConflictStrategyFactory recordConflictStrategy =
      new RecordConflictStrategyFactory();
  private final ReferenceQueue<YouTrackDBStartupListener> removedStartupListenersQueue =
      new ReferenceQueue<YouTrackDBStartupListener>();
  private final ReferenceQueue<YouTrackDBShutdownListener> removedShutdownListenersQueue =
      new ReferenceQueue<YouTrackDBShutdownListener>();
  private final Set<YouTrackDBStartupListener> startupListeners =
      Collections.newSetFromMap(new ConcurrentHashMap<YouTrackDBStartupListener, Boolean>());
  private final Set<WeakHashSetValueHolder<YouTrackDBStartupListener>> weakStartupListeners =
      Collections.newSetFromMap(
          new ConcurrentHashMap<WeakHashSetValueHolder<YouTrackDBStartupListener>, Boolean>());
  private final Set<WeakHashSetValueHolder<YouTrackDBShutdownListener>> weakShutdownListeners =
      Collections.newSetFromMap(
          new ConcurrentHashMap<WeakHashSetValueHolder<YouTrackDBShutdownListener>, Boolean>());

  private final YouTrackDBScheduler scheduler = new YouTrackDBScheduler();
  private volatile Profiler profiler;

  private final PriorityQueue<ShutdownHandler> shutdownHandlers =
      new PriorityQueue<ShutdownHandler>(
          11,
          new Comparator<ShutdownHandler>() {
            @Override
            public int compare(ShutdownHandler handlerOne, ShutdownHandler handlerTwo) {
              if (handlerOne.getPriority() > handlerTwo.getPriority()) {
                return 1;
              }

              if (handlerOne.getPriority() < handlerTwo.getPriority()) {
                return -1;
              }

              return 0;
            }
          });

  private final LocalRecordCacheFactory localRecordCache = new LocalRecordCacheFactoryImpl();

  private final Set<YouTrackDBInternalEmbedded> factories =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  private final Set<YouTrackDBInternal> runningInstances = new HashSet<>();

  private final String os;

  private volatile RecordFactoryManager recordFactoryManager = new RecordFactoryManager();
  private YouTrackDBShutdownHook shutdownHook;
  private DatabaseThreadLocalFactory databaseThreadFactory;
  private volatile boolean active = false;
  private SignalHandler signalHandler;

  /**
   * Indicates that engine is initialized inside of web application container.
   */
  private final boolean insideWebContainer;

  /**
   * Prevents duplications because of recursive initialization.
   */
  private static boolean initInProgress = false;

  // Package-private factory hook for testing. When non-null, startUp() uses this
  // instead of the constructor, allowing tests to inject a subclass whose startup()
  // throws — without relying on Mockito's mockConstruction (broken on JDK 25+).
  static volatile Function<Boolean, YouTrackDBEnginesManager> instanceFactory;

  private static class WeakHashSetValueHolder<T> extends WeakReference<T> {

    private final int hashCode;

    private WeakHashSetValueHolder(T referent, ReferenceQueue<? super T> q) {
      super(referent, q);
      this.hashCode = referent.hashCode();
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (!(o instanceof WeakHashSetValueHolder that)) {
        return false;
      }

      if (hashCode != that.hashCode) {
        return false;
      }

      final var thisObject = get();
      final var thatObject = that.get();

      if (thisObject == null && thatObject == null) {
        return super.equals(that);
      } else if (thisObject != null && thatObject != null) {
        return thisObject.equals(thatObject);
      }

      return false;
    }
  }

  YouTrackDBEnginesManager(boolean insideWebContainer) {
    super(true);
    this.insideWebContainer = insideWebContainer;
    this.os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
    threadGroup = new ThreadGroup("YouTrackDB");
    // Storage thread group is intentionally NOT a child of threadGroup so that
    // threadGroup.interrupt() during shutdown does not disrupt in-progress WAL
    // or cache flush operations.
    storageThreadGroup = new ThreadGroup("YouTrackDB Storage");

    // Storage pools — single-threaded for sequential execution guarantees.
    walFlushExecutor = ThreadPoolExecutors.newSingleThreadScheduledPool(
        "YouTrackDB WAL Flush Task", storageThreadGroup);
    walWriteExecutor = ThreadPoolExecutors.newSingleThreadPool(
        "YouTrackDB WAL Write Task Thread", storageThreadGroup);
    fuzzyCheckpointExecutor = ThreadPoolExecutors.newSingleThreadScheduledPool(
        "Fuzzy Checkpoint", storageThreadGroup);
    wowCacheFlushExecutor = ThreadPoolExecutors.newSingleThreadScheduledPool(
        "YouTrackDB Write Cache Flush Task", storageThreadGroup);

    // Shared scheduled pool replaces per-instance Timer instances.
    // Two threads: one dedicated to GranularTicker high-frequency updates,
    // the other for eviction, auto-close, cron events, etc.
    scheduledPool = ThreadPoolExecutors.newScheduledThreadPool(
        "YouTrackDB Scheduler", threadGroup, 2);

    // Register engines eagerly in the constructor so they are available before
    // the singleton 'instance' reference is published via the volatile write in
    // startUp(). Without this, a concurrent thread can observe a non-null
    // 'instance' (assigned before startup()) but find an empty engines map,
    // causing onEmbeddedFactoryInit() to skip engine startup — leading to
    // "readCache is null" failures when createStorage() is called later.
    registerEngines();
  }

  public boolean isInsideWebContainer() {
    return insideWebContainer;
  }

  public static YouTrackDBEnginesManager instance() {
    if (instance != null) {
      return instance;
    }

    return startUp(false);
  }

  public static YouTrackDBEnginesManager startUp(boolean insideWebContainer) {
    initLock.lock();
    try {
      if (initInProgress) {
        // Re-entrant call during startup (e.g. Profiler.onStartup() ->
        // YouTrackDBScheduler.scheduleTask() -> instance()). The instance is
        // already assigned below, return it instead of null to avoid NPE.
        return instance;
      }

      initInProgress = true;
      if (instance != null) {
        return instance;
      }

      final var factory = instanceFactory;
      instanceFactory = null; // consume once; prevent leaking across calls
      final var youTrack = factory != null
          ? factory.apply(insideWebContainer)
          : new YouTrackDBEnginesManager(insideWebContainer);
      // Assign instance before startup() so that re-entrant calls to instance()
      // during startup (e.g. Profiler.onStartup() -> YouTrackDBScheduler) see the
      // in-progress object instead of returning null. Engine instances are already
      // registered (in the constructor) so concurrent onEmbeddedFactoryInit() calls
      // that observe this early publication will find engines in the map and can
      // start them.
      instance = youTrack;
      YouTrackDBEnginesManager managerToShutdown = null;
      try {
        youTrack.startup();
      } catch (Exception | Error e) {
        instance = null;
        // Defer shutdownPools() to after lock release to avoid holding
        // initLock during potentially long pool termination (up to 25s).
        managerToShutdown = youTrack;
        throw e;
      } finally {
        if (managerToShutdown != null) {
          // Release lock before shutting down pools so other threads
          // are not blocked during the cleanup.
          initInProgress = false;
          initLock.unlock();
          managerToShutdown.shutdownPools();
        }
      }
    } finally {
      // Guard against double-unlock: if the catch branch already
      // released the lock, avoid unlocking again.
      if (initLock.isHeldByCurrentThread()) {
        initInProgress = false;
        initLock.unlock();
      }
    }

    return instance;
  }

  /**
   * Tells if to register database by path. Default is false. Setting to true allows to have
   * multiple databases in different path with the same name.
   *
   * @see #setRegisterDatabaseByPath(boolean)
   */
  public static boolean isRegisterDatabaseByPath() {
    return registerDatabaseByPath;
  }

  /**
   * Register database by path. Default is false. Setting to true allows to have multiple databases
   * in different path with the same name.
   */
  public static void setRegisterDatabaseByPath(final boolean iValue) {
    registerDatabaseByPath = iValue;
  }

  public RecordConflictStrategyFactory getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  public YouTrackDBEnginesManager startup() {
    engineLock.writeLock().lock();
    try {
      if (active)
      // ALREADY ACTIVE
      {
        return this;
      }

      profiler = new Profiler(scheduler, scheduledPool);

      registerWeakYouTrackDBStartupListener(profiler);
      registerWeakYouTrackDBShutdownListener(profiler);

      shutdownHook = new YouTrackDBShutdownHook();
      if (signalHandler == null) {
        signalHandler = new SignalHandler();
        signalHandler.installDefaultSignals();
      }

      // Engines are registered eagerly in the constructor — not here — so they
      // are available before the singleton 'instance' is published. Existing
      // engine instances survive shutdown/restart: onEmbeddedFactoryInit()
      // re-starts them on demand (EngineLocalPaginated.startup() creates a
      // fresh readCache each time).

      if (GlobalConfiguration.ENVIRONMENT_DUMP_CFG_AT_STARTUP.getValueAsBoolean()) {
        dumpConfigurationToLog();
      }

      // Always log the effective pre-filter cap so operators can see the
      // auto-scaled value and know which config key to override.
      var maxRidSetSize =
          GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE;
      LogManager.instance().info(this,
          "Pre-filter maxRidSetSize cap: %d (%s). Override with: %s",
          maxRidSetSize.getValueAsInteger(),
          maxRidSetSize.isChanged() ? "explicitly configured"
              : "auto-scaled from heap",
          maxRidSetSize.getKey());

      active = true;
      scheduler.activate();

      for (var l : startupListeners) {
        try {
          if (l != null) {
            l.onStartup();
          }
        } catch (Exception e) {
          LogManager.instance().error(this, "Error on startup", e);
        }
      }

      purgeWeakStartupListeners();
      for (final var wl : weakStartupListeners) {
        try {
          if (wl != null) {
            final var l = wl.get();
            if (l != null) {
              l.onStartup();
            }
          }

        } catch (Exception e) {
          LogManager.instance().error(this, "Error on startup", e);
        }
      }

      initShutdownQueue();
    } finally {
      engineLock.writeLock().unlock();
    }

    return this;
  }

  /**
   * Add handler which will be executed during {@link #shutdown()} call.
   *
   * @param shutdownHandler Shutdown handler instance.
   */
  public void addShutdownHandler(ShutdownHandler shutdownHandler) {
    engineLock.writeLock().lock();
    try {
      shutdownHandlers.add(shutdownHandler);
    } finally {
      engineLock.writeLock().unlock();
    }
  }

  /**
   * Adds shutdown handlers in order which will be used during execution of shutdown.
   */
  private void initShutdownQueue() {
    addShutdownHandler(new ShutdownYouTrackDBInstancesHandler());
    addShutdownHandler(new ShutdownPendingThreadsHandler());
    addShutdownHandler(new ShutdownCallListenersHandler());
  }

  /**
   * Dumps the global configuration to the log at INFO level.
   */
  private void dumpConfigurationToLog() {
    var baos = new java.io.ByteArrayOutputStream();
    GlobalConfiguration.dumpConfiguration(new java.io.PrintStream(baos));
    LogManager.instance().info(this, "%s", baos);
  }

  /**
   * Shutdown whole YouTrackDB ecosystem. Usually is called during JVM shutdown by JVM shutdown
   * handler. During shutdown all handlers which were registered by the call of
   * {@link #addShutdownHandler(ShutdownHandler)} are called together with pre-registered system
   * shoutdown handlers according to their priority.
   */
  private void registerEngines() {
    var classLoader = YouTrackDBEnginesManager.class.getClassLoader();

    var engines =
        ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader(Engine.class, classLoader);

    Engine engine = null;
    while (engines.hasNext()) {
      try {
        engine = engines.next();
        registerEngine(engine);
      } catch (IllegalArgumentException e) {
        if (engine != null) {
          LogManager.instance().debug(this, "Failed to replace engine " + engine.getName(), logger,
              e);
        }
      }
    }
  }

  public YouTrackDBEnginesManager shutdown() {
    engineLock.writeLock().lock();
    try {
      if (!active) {
        return this;
      }

      YTDBGraphFactory.closeAll();
      active = false;

      LogManager.instance().info(this, "YouTrackDB Engine is shutting down...");
      for (var handler : shutdownHandlers) {
        try {
          LogManager.instance().debug(this, "Shutdown handler %s is going to be called", logger,
              handler);
          handler.shutdown();
          LogManager.instance().debug(this, "Shutdown handler %s completed", logger, handler);
        } catch (Exception e) {
          LogManager.instance()
              .error(this, "Exception during calling of shutdown handler %s", e, handler);
        }
      }

      shutdownHandlers.clear();
      weakShutdownListeners.clear();
      weakStartupListeners.clear();

      shutdownEngines();

      // Drain the WOW cache flush executor: submit a barrier task and wait for it
      // to complete. Because the executor is single-threaded, the barrier runs after
      // all previously submitted tasks (removeWrittenPagesFromCache, delete, etc.)
      // finish, ensuring no concurrent release() calls race with ByteBufferPool.clear()
      // below. We must NOT call shutdownExecutor() here because the executor is a
      // final field that must survive shutdown/startup cycles in tests (see comment
      // on ShutdownPendingThreadsHandler).
      try {
        wowCacheFlushExecutor.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        LogManager.instance()
            .warn(this, "Failed to drain WOW cache flush executor", e);
      }

      LogManager.instance().info(this, "Clearing byte buffer pool");
      ByteBufferPool.instance(null).clear();

      ByteBufferPool.instance(null).checkMemoryLeaks();
      DirectMemoryAllocator.instance().checkMemoryLeaks();

      LogManager.instance().info(this, "YouTrackDB Engine shutdown complete");
      LogManager.flush();
    } finally {
      try {
        removeShutdownHook();
      } finally {
        try {
          removeSignalHandler();
        } finally {
          engineLock.writeLock().unlock();
        }
      }
    }

    return this;
  }

  /**
   * Shuts down all running engines. Called from {@link #shutdown()} after all embedded
   * factory instances and storages have been closed by the shutdown handlers.
   *
   * <p>Engines are shut down here (centrally) rather than eagerly in
   * {@link #onEmbeddedFactoryClose} because eager shutdown creates a race condition
   * during parallel test execution: a thread may call {@code engine.createStorage()}
   * between the moment another thread removes the last factory (shutting down the engine)
   * and the moment the first thread registers its own factory. The engine's readCache
   * becomes null during this window, causing DatabaseException.
   */
  private void shutdownEngines() {
    assert !active : "shutdownEngines() called while manager is still active";
    for (var engine : engines.values()) {
      if (engine.isRunning()) {
        try {
          engine.shutdown();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error shutting down engine '%s'", e,
              engine.getName());
        }
      }
    }
  }

  public boolean isActive() {
    return active;
  }

  public boolean isWindowsOS() {
    return os.contains("win");
  }

  public void registerEngine(final Engine iEngine) throws IllegalArgumentException {
    var engine = engines.get(iEngine.getName());

    if (engine != null) {
      if (!engine.getClass().isAssignableFrom(iEngine.getClass())) {
        throw new IllegalArgumentException("Cannot replace storage " + iEngine.getName());
      }
    }
    engines.put(iEngine.getName(), iEngine);
  }

  /**
   * Returns the engine by its name.
   *
   * @param engineName Engine name to retrieve
   * @return Engine instance of found, otherwise null
   */
  public Engine getEngine(final String engineName) {
    engineLock.readLock().lock();
    try {
      return engines.get(engineName);
    } finally {
      engineLock.readLock().unlock();
    }
  }

  /**
   * Obtains an {@link Engine engine} instance with the given {@code engineName}, if it is
   * {@link Engine#isRunning() running}.
   *
   * @param engineName the name of the engine to obtain.
   * @return the obtained engine instance or {@code null} if no such engine known or the engine is
   * not running.
   */
  @Nullable public Engine getEngineIfRunning(final String engineName) {
    engineLock.readLock().lock();
    try {
      final var engine = engines.get(engineName);
      return engine == null || !engine.isRunning() ? null : engine;
    } finally {
      engineLock.readLock().unlock();
    }
  }

  /**
   * Obtains a {@link Engine#isRunning() running} {@link Engine engine} instance with the given
   * {@code engineName}. If engine is not running, starts it.
   *
   * @param engineName the name of the engine to obtain.
   * @return the obtained running engine instance, never {@code null}.
   * @throws IllegalStateException if an engine with the given is not found or failed to start.
   */
  public Engine getRunningEngine(final String engineName) {
    engineLock.readLock().lock();
    try {
      var engine = engines.get(engineName);
      if (engine == null) {
        throw new IllegalStateException("Engine '" + engineName + "' is not found.");
      }

      if (!engine.isRunning() && !startEngine(engine)) {
        throw new IllegalStateException("Engine '" + engineName + "' is failed to start.");
      }

      return engine;
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public Set<String> getEngines() {
    engineLock.readLock().lock();
    try {
      return Collections.unmodifiableSet(engines.keySet());
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public Collection<Storage> getStorages() {
    List<Storage> storages = new ArrayList<>();
    for (var factory : factories) {
      storages.addAll(factory.getStorages());
    }
    return storages;
  }

  public void removeShutdownHook() {
    if (shutdownHook != null) {
      shutdownHook.cancel();
      shutdownHook = null;
    }
  }

  public void removeSignalHandler() {
    if (signalHandler != null) {
      signalHandler.cancel();
      signalHandler = null;
    }
  }

  public boolean isSelfManagedShutdown() {
    return shutdownHook != null;
  }

  public Iterator<DatabaseLifecycleListener> getDbLifecycleListeners() {
    if (dbLifecycleListeners.get().isEmpty()) {
      return IteratorUtils.emptyIterator();
    }

    return dbLifecycleListeners.get().stream().map(RawPair::first).iterator();
  }

  public void addDbLifecycleListener(final DatabaseLifecycleListener listener) {
    List<RawPair<DatabaseLifecycleListener, DatabaseLifecycleListener.PRIORITY>> initialRef;
    ArrayList<RawPair<DatabaseLifecycleListener, DatabaseLifecycleListener.PRIORITY>> newRef;
    do {
      initialRef = dbLifecycleListeners.get();
      newRef = new ArrayList<>(initialRef.size() + 1);

      final var tmp = new ArrayList<>(initialRef);
      if (listener.getPriority() == null) {
        throw new IllegalArgumentException(
            "Priority of DatabaseLifecycleListener '" + listener + "' cannot be null");
      }

      tmp.add(new RawPair<>(listener, listener.getPriority()));

      for (var p : DatabaseLifecycleListener.PRIORITY.values()) {
        for (var e : tmp) {
          if (e.second() == p) {
            newRef.add(e);
          }
        }
      }

    } while (!dbLifecycleListeners.compareAndSet(initialRef, newRef));
  }

  public void removeDbLifecycleListener(final DatabaseLifecycleListener listener) {
    List<RawPair<DatabaseLifecycleListener, DatabaseLifecycleListener.PRIORITY>> initialRef;
    ArrayList<RawPair<DatabaseLifecycleListener, DatabaseLifecycleListener.PRIORITY>> newRef;
    do {
      initialRef = dbLifecycleListeners.get();
      newRef = new ArrayList<>(initialRef.size() - 1);

      for (var e : initialRef) {
        if (e.first() != listener) {
          newRef.add(e);
        }
      }
    } while (!dbLifecycleListeners.compareAndSet(initialRef, newRef));
  }

  public ThreadGroup getThreadGroup() {
    return threadGroup;
  }

  public ThreadGroup getStorageThreadGroup() {
    return storageThreadGroup;
  }

  public ScheduledExecutorService getWalFlushExecutor() {
    return walFlushExecutor;
  }

  public ExecutorService getWalWriteExecutor() {
    return walWriteExecutor;
  }

  public ScheduledExecutorService getFuzzyCheckpointExecutor() {
    return fuzzyCheckpointExecutor;
  }

  public ScheduledExecutorService getWowCacheFlushExecutor() {
    return wowCacheFlushExecutor;
  }

  public ScheduledExecutorService getScheduledPool() {
    return scheduledPool;
  }

  public ExecutorService getExecutor() {
    return executor;
  }

  @Nullable public ExecutorService getIoExecutor() {
    return ioExecutor;
  }

  /**
   * Creates the main work executor if not already created. Called by
   * {@link YouTrackDBInternalEmbedded} during its construction. Subsequent calls return the
   * already-created executor.
   */
  public synchronized ExecutorService createExecutor(YouTrackDBConfigImpl config) {
    if (executor != null) {
      return executor;
    }

    var maxSize = executorMaxSize(config, GlobalConfiguration.EXECUTOR_POOL_MAX_SIZE);
    var result = ThreadPoolExecutors.newScalingThreadPool(
        "YouTrackDBEmbedded", threadGroup, 1, executorBaseSize(maxSize),
        maxSize, 30, TimeUnit.MINUTES);
    if (config.getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.EXECUTOR_DEBUG_TRACE_SOURCE)) {
      result = new SourceTraceExecutorService(result);
    }
    executor = result;
    return result;
  }

  /**
   * Creates the IO executor if not already created. Called by
   * {@link YouTrackDBInternalEmbedded} during its construction. Returns null if IO pool is
   * disabled in configuration.
   */
  @Nullable public synchronized ExecutorService createIoExecutor(YouTrackDBConfigImpl config) {
    if (!config.getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.EXECUTOR_POOL_IO_ENABLED)) {
      return null;
    }

    if (ioExecutor != null) {
      return ioExecutor;
    }

    var ioSize = executorMaxSize(config, GlobalConfiguration.EXECUTOR_POOL_IO_MAX_SIZE);
    var result = ThreadPoolExecutors.newScalingThreadPool(
        "YouTrackDB-IO", threadGroup, 1, executorBaseSize(ioSize),
        ioSize, 30, TimeUnit.MINUTES);
    if (config.getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.EXECUTOR_DEBUG_TRACE_SOURCE)) {
      result = new SourceTraceExecutorService(result);
    }
    ioExecutor = result;
    return result;
  }

  static int executorMaxSize(YouTrackDBConfigImpl config, GlobalConfiguration param) {
    var size = config.getConfiguration().getValueAsInteger(param);
    if (size == 0) {
      LogManager.instance()
          .warn(YouTrackDBEnginesManager.class,
              "Configuration " + param.getKey()
                  + " has a value 0 using number of CPUs as base value");
      size = Runtime.getRuntime().availableProcessors();
    } else if (size <= -1) {
      size = Runtime.getRuntime().availableProcessors();
    }
    return size;
  }

  static int executorBaseSize(int size) {
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

  /**
   * Gracefully shuts down an executor: calls {@code shutdown()}, waits up to the given timeout,
   * and falls back to {@code shutdownNow()} if the executor does not terminate in time or the
   * waiting thread is interrupted.
   */
  static void shutdownExecutor(
      @Nullable ExecutorService exec, String name, long timeout, TimeUnit unit) {
    if (exec == null) {
      return;
    }
    exec.shutdown();
    try {
      if (!exec.awaitTermination(timeout, unit)) {
        LogManager.instance()
            .warn(YouTrackDBEnginesManager.class, "Forcing shutdown of %s", name);
        exec.shutdownNow();
      }
    } catch (InterruptedException e) {
      exec.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Shuts down all constructor-allocated pools. Used to clean up resources when
   * {@link #startup()} fails and the instance is discarded.
   */
  void shutdownPools() {
    shutdownExecutor(walFlushExecutor, "WAL flush", 5, TimeUnit.SECONDS);
    shutdownExecutor(walWriteExecutor, "WAL write", 5, TimeUnit.SECONDS);
    shutdownExecutor(fuzzyCheckpointExecutor, "fuzzy checkpoint", 5, TimeUnit.SECONDS);
    shutdownExecutor(wowCacheFlushExecutor, "WOW cache flush", 5, TimeUnit.SECONDS);
    shutdownExecutor(scheduledPool, "scheduled pool", 5, TimeUnit.SECONDS);
  }

  public DatabaseThreadLocalFactory getDatabaseThreadFactory() {
    return databaseThreadFactory;
  }

  public RecordFactoryManager getRecordFactoryManager() {
    return recordFactoryManager;
  }

  public void setRecordFactoryManager(final RecordFactoryManager iRecordFactoryManager) {
    recordFactoryManager = iRecordFactoryManager;
  }

  public YouTrackDBScheduler getScheduler() {
    return scheduler;
  }

  public MetricsRegistry getMetricsRegistry() {
    var p = profiler;
    return p != null ? p.getMetricsRegistry() : null;
  }

  public Ticker getTicker() {
    var p = profiler;
    return p != null ? p.getTicker() : null;
  }

  public void registerThreadDatabaseFactory(final DatabaseThreadLocalFactory iDatabaseFactory) {
    databaseThreadFactory = iDatabaseFactory;
  }

  @Override
  public void registerListener(YouTrackDBListener listener) {
    if (listener instanceof YouTrackDBStartupListener startupListener) {
      registerYouTrackDBStartupListener(startupListener);
    }

    super.registerListener(listener);
  }

  @Override
  public void unregisterListener(YouTrackDBListener listener) {
    if (listener instanceof YouTrackDBStartupListener startupListener) {
      unregisterYouTrackDBStartupListener(startupListener);
    }

    super.unregisterListener(listener);
  }

  public void registerYouTrackDBStartupListener(YouTrackDBStartupListener listener) {
    startupListeners.add(listener);
  }

  public void registerWeakYouTrackDBStartupListener(YouTrackDBStartupListener listener) {
    purgeWeakStartupListeners();
    weakStartupListeners.add(
        new WeakHashSetValueHolder<YouTrackDBStartupListener>(listener,
            removedStartupListenersQueue));
  }

  public void unregisterYouTrackDBStartupListener(YouTrackDBStartupListener listener) {
    startupListeners.remove(listener);
  }

  public void unregisterWeakYouTrackDBStartupListener(YouTrackDBStartupListener listener) {
    purgeWeakStartupListeners();
    weakStartupListeners.remove(
        new WeakHashSetValueHolder<YouTrackDBStartupListener>(listener, null));
  }

  public void registerWeakYouTrackDBShutdownListener(YouTrackDBShutdownListener listener) {
    purgeWeakShutdownListeners();
    weakShutdownListeners.add(
        new WeakHashSetValueHolder<YouTrackDBShutdownListener>(
            listener, removedShutdownListenersQueue));
  }

  public void unregisterWeakYouTrackDBShutdownListener(YouTrackDBShutdownListener listener) {
    purgeWeakShutdownListeners();
    weakShutdownListeners.remove(
        new WeakHashSetValueHolder<YouTrackDBShutdownListener>(listener, null));
  }

  @Override
  public void resetListeners() {
    super.resetListeners();

    weakShutdownListeners.clear();

    startupListeners.clear();
    weakStartupListeners.clear();
  }

  public LocalRecordCacheFactory getLocalRecordCache() {
    return localRecordCache;
  }

  @SuppressWarnings("unchecked")
  private void purgeWeakStartupListeners() {
    var queue = removedStartupListenersQueue;
    synchronized (queue) {
      var ref = (WeakHashSetValueHolder<YouTrackDBStartupListener>) queue.poll();
      while (ref != null) {
        weakStartupListeners.remove(ref);
        ref = (WeakHashSetValueHolder<YouTrackDBStartupListener>) queue.poll();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void purgeWeakShutdownListeners() {
    var queue = removedShutdownListenersQueue;
    synchronized (queue) {
      var ref = (WeakHashSetValueHolder<YouTrackDBShutdownListener>) queue.poll();
      while (ref != null) {
        weakShutdownListeners.remove(ref);
        ref = (WeakHashSetValueHolder<YouTrackDBShutdownListener>) queue.poll();
      }
    }
  }

  private boolean startEngine(Engine engine) {
    final var name = engine.getName();

    try {
      engine.startup();
      return true;
    } catch (Exception e) {
      LogManager.instance()
          .error(
              this, "Error during initialization of engine '%s', engine will be removed", e, name);

      try {
        engine.shutdown();
      } catch (Exception se) {
        LogManager.instance().error(this, "Error during engine shutdown", se);
      }

      engines.remove(name);
    }

    return false;
  }

  /**
   * Closes all storages and shutdown all engines.
   */
  public class ShutdownYouTrackDBInstancesHandler implements ShutdownHandler {

    @Override
    public int getPriority() {
      return SHUTDOWN_ENGINES_PRIORITY;
    }

    @Override
    public void shutdown() throws Exception {
      for (var internal : runningInstances) {
        internal.internalClose();
      }
      runningInstances.clear();
    }

    @Override
    public String toString() {
      return getClass().getSimpleName();
    }
  }

  /**
   * Interrupts all threads in YouTrackDB thread group, shuts down lazily-created executors,
   * and stops the scheduler.
   *
   * <p>Infrastructure pools (storage pools, scheduled pool) are NOT shut down here because
   * they are created in the constructor and must survive across shutdown/startup cycles
   * (tests call {@link #shutdown()} then {@link #startup()} on the same instance).
   * These pools use daemon threads and are terminated automatically when the JVM shuts down.
   */
  private class ShutdownPendingThreadsHandler implements ShutdownHandler {

    @Override
    public int getPriority() {
      return SHUTDOWN_PENDING_THREADS_PRIORITY;
    }

    @Override
    public void shutdown() throws Exception {
      scheduler.shutdown();

      // Shut down lazily-created main/IO executors and reset to null
      // so they can be re-created on next startup.
      shutdownExecutor(executor, "main executor", 1, TimeUnit.MINUTES);
      executor = null;
      shutdownExecutor(ioExecutor, "IO executor", 1, TimeUnit.MINUTES);
      ioExecutor = null;

      if (threadGroup != null) {
        threadGroup.interrupt();
      }
    }

    @Override
    public String toString() {
      // it is strange but windows defender block compilation if we get class name programmatically
      // using Class instance
      return "ShutdownPendingThreadsHandler";
    }
  }

  /**
   * Calls all shutdown listeners.
   */
  private class ShutdownCallListenersHandler implements ShutdownHandler {

    @Override
    public int getPriority() {
      return SHUTDOWN_CALL_LISTENERS;
    }

    @Override
    public void shutdown() throws Exception {
      purgeWeakShutdownListeners();
      for (final var wl : weakShutdownListeners) {
        try {
          if (wl != null) {
            final var l = wl.get();
            if (l != null) {
              l.onShutdown();
            }
          }

        } catch (Exception e) {
          LogManager.instance().error(this, "Error during YouTrackDB shutdown", e);
        }
      }

      // CALL THE SHUTDOWN ON ALL THE LISTENERS
      for (var l : browseListeners()) {
        if (l != null) {
          try {
            l.onShutdown();
          } catch (Exception e) {
            LogManager.instance().error(this, "Error during YouTrackDB shutdown", e);
          }
        }
      }

      System.gc();
    }

    @Override
    public String toString() {
      return getClass().getSimpleName();
    }
  }

  // Guards engine startup in onEmbeddedFactoryInit to prevent concurrent startups
  // and ensure the factory is registered atomically with the engine-running check.
  // Also serializes factory-set mutations between init and close.
  // Uses ReentrantLock instead of synchronized to be virtual-thread friendly.
  private final ReentrantLock factoryLifecycleLock = new ReentrantLock();

  public void onEmbeddedFactoryInit(YouTrackDBInternalEmbedded embeddedFactory) {
    factoryLifecycleLock.lock();
    try {
      // No assert on 'active' here: during first startup, startUp() assigns the
      // volatile 'instance' field BEFORE calling startup() (which sets active=true).
      // A concurrent thread calling instance() may return the not-yet-active manager
      // and invoke this method before startup() completes. This is safe because
      // the method starts engines that aren't running, which is correct regardless
      // of the active flag.
      var memory = engines.get("memory");
      if (memory != null && !memory.isRunning()) {
        memory.startup();
      }
      var disc = engines.get("disk");
      if (disc != null && !disc.isRunning()) {
        disc.startup();
      }
      factories.add(embeddedFactory);
    } finally {
      factoryLifecycleLock.unlock();
    }
  }

  public void onEmbeddedFactoryClose(YouTrackDBInternalEmbedded embeddedFactory) {
    factoryLifecycleLock.lock();
    try {
      factories.remove(embeddedFactory);
      // Engines are shut down centrally in shutdownEngines(), not here —
      // see its Javadoc for the race condition rationale.
    } finally {
      factoryLifecycleLock.unlock();
    }
  }

  public void addYouTrackDB(YouTrackDBInternal internal) {
    engineLock.writeLock().lock();
    try {
      runningInstances.add(internal);
    } finally {
      engineLock.writeLock().unlock();
    }
  }

  public void removeYouTrackDB(YouTrackDBInternal internal) {
    engineLock.writeLock().lock();
    try {
      runningInstances.remove(internal);
    } finally {
      engineLock.writeLock().unlock();
    }
  }
}
