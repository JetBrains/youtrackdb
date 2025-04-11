package com.jetbrains.youtrack.db.internal.core.db;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import java.util.TimerTask;

/**
 * Default implementation of {@link CachedDatabasePoolFactory}
 *
 * <p>Used in {@link YouTrackDBEmbedded} by default
 *
 * <p>Works like LRU cache
 *
 * <p>How it works: 1. Pool cache capacity is 100 2. We have 100 pools in cache 3. We want get 101
 * pool 4. First we will remove pool which used long time ago from pool cache 5. Then we add new
 * pool from point 3 to pool cache
 */
public class CachedDatabasePoolFactoryImpl implements CachedDatabasePoolFactory {

  /**
   * Max size of connections which one pool can contains
   */
  private volatile int maxPoolSize = 100;

  private volatile boolean closed;
  private final ConcurrentLinkedHashMap<String, DatabasePoolInternal> poolCache;
  private final YouTrackDBInternal youTrackDB;
  private final long timeout;

  /**
   * @param youtrackDB instance of {@link YouTrackDB} which will be used for create new database
   *                   pools {@link DatabasePoolInternal}
   * @param capacity   capacity of pool cache, by default is 100
   * @param timeout    timeout in milliseconds which means that every timeout will be executed task
   *                   for clean up cache from closed pools
   */
  public CachedDatabasePoolFactoryImpl(YouTrackDBInternal youtrackDB, int capacity, long timeout) {
    poolCache =
        new ConcurrentLinkedHashMap.Builder<String, DatabasePoolInternal>()
            .maximumWeightedCapacity(capacity)
            .listener((identity, databasePool) -> databasePool.close())
            .build();
    this.youTrackDB = youtrackDB;
    this.timeout = timeout;
    scheduleCleanUpCache(createCleanUpTask());
  }

  protected void scheduleCleanUpCache(TimerTask task) {
    youTrackDB.schedule(task, timeout, timeout);
  }

  private TimerTask createCleanUpTask() {
    return new TimerTask() {
      @Override
      public void run() {
        if (closed) {
          cancel();
        } else {
          cleanUpCache();
        }
      }
    };
  }

  private void cleanUpCache() {
    synchronized (this) {
      for (var pool : poolCache.values()) {
        var delta = System.currentTimeMillis() - pool.getLastCloseTime();
        if (pool.isUnused() && delta > timeout) {
          pool.close();
        }
      }
      poolCache.values().removeIf(DatabasePoolInternal::isClosed);
    }
  }

  /**
   * Get or create database pool instance for given user
   *
   * <p>Get or create database pool: 1. Create string database + username + password 2. Create key
   * by hashing this string using SHA-256 3. Try to get pool from cache 4. If pool is in cache and
   * pool is not closed, so return this pool 5. If pool is not in cache or pool is closed, so create
   * new pool and put it in cache
   *
   * @param database name of database
   * @param username name of user which need access to database
   * @param password user password
   * @return {@link DatabasePoolInternal} which is new instance of pool or instance from pool
   * storage
   */
  @Override
  public DatabasePoolInternal get(
      String database, String username, String password, YouTrackDBConfigImpl parentConfig) {
    checkForClose();

    var key = SecurityManager.createSHA256(database + username + password);

    var pool = poolCache.get(key);
    if (pool != null && !pool.isClosed()) {
      return pool;
    }

    var config = (YouTrackDBConfigImpl)
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, maxPoolSize)
            .build();

    if (parentConfig != null) {
      config.setParent(parentConfig);
    }
    pool = new DatabasePoolImpl(youTrackDB, database, username, password, config);

    poolCache.put(key, pool);

    return pool;
  }

  /**
   * Close all open pools and clear pool storage
   */
  @Override
  public CachedDatabasePoolFactory reset() {
    poolCache.forEach((key, pool) -> pool.close());
    poolCache.clear();
    return this;
  }

  /**
   * Close all open pools and clear pool storage. Set flag closed to true, so this instance can't be
   * used again. Need create new instance of {@link CachedDatabasePoolFactory} after close one of
   * factories.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      reset();
    }
  }

  /**
   * @return true if factory is closed
   */
  @Override
  public boolean isClosed() {
    return closed;
  }

  /**
   * @return max pool size. Default is 64
   */
  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  /**
   * Set max pool connections size which will be used for create new {@link SessionPoolImpl}
   *
   * @param maxPoolSize max pool connections size
   * @return this instance
   */
  public CachedDatabasePoolFactory setMaxPoolSize(int maxPoolSize) {
    this.maxPoolSize = maxPoolSize;
    return this;
  }

  /**
   * @throws IllegalStateException if pool factory is closed
   */
  private void checkForClose() {
    if (closed) {
      throw new IllegalStateException("Cached pool factory is closed!");
    }
  }
}
