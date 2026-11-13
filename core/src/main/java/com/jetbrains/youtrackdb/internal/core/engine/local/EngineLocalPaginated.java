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

package com.jetbrains.youtrackdb.internal.core.engine.local;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.jnr.Native;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.engine.EngineAbstract;
import com.jetbrains.youtrackdb.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.WTinyLFUPolicy;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage engine implementation for local paginated (disk-based) storage.
 *
 * @since 28.03.13
 */
public class EngineLocalPaginated extends EngineAbstract {

  public static final String NAME = "disk";

  private volatile ReadCache readCache;

  protected final ClosableLinkedContainer<Long, File> files =
      new ClosableLinkedContainer<>(getOpenFilesLimit());

  public EngineLocalPaginated() {
  }

  private static int getOpenFilesLimit() {
    if (GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger() > 0) {
      final var additionalArgs =
          new Object[] {GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger()};
      LogManager.instance()
          .info(
              EngineLocalPaginated.class,
              "Limit of open files for disk cache will be set to %d.",
              additionalArgs);
      return GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger();
    }

    final var defaultLimit = 512;
    final var recommendedLimit = 256 * 1024;

    return Native.instance().getOpenFilesLimit(true, recommendedLimit, defaultLimit);
  }

  @Override
  public void startup() {
    final int commitPageLimit =
        GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.getValueAsInteger();
    if (commitPageLimit < 1) {
      throw new IllegalArgumentException(
          GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.getKey() + "=" + commitPageLimit
              + " must be in range 1 to 2147483647");
    }
    final double percent =
        GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.getValueAsDouble();
    if (!Double.isFinite(percent) || percent < 0
        || percent > WTinyLFUPolicy.MAX_COMMIT_PIN_BUDGET_PERCENT) {
      throw new IllegalArgumentException(
          GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.getKey() + "=" + percent
              + " must be in range 0 to " + WTinyLFUPolicy.MAX_COMMIT_PIN_BUDGET_PERCENT
              + " inclusive");
    }
    final var userName = System.getProperty("user.name", "unknown");
    LogManager.instance().info(this, "System is started under an effective user : `%s`", userName);
    if (Native.instance().isOsRoot()) {
      LogManager.instance()
          .warn(
              this,
              "You are running under the \"root\" user privileges that introduces security risks."
                  + " Please consider to run under a user dedicated to be used to run current"
                  + " server instance.");
    }

    MemoryAndLocalPaginatedEnginesInitializer.INSTANCE.initialize();

    final var diskCacheSize =
        calculateReadCacheMaxMemory(
            GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong() * 1024 * 1024);
    final var pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

    if (GlobalConfiguration.DIRECT_MEMORY_PREALLOCATE.getValueAsBoolean()) {
      final var pageCount = (int) (diskCacheSize / pageSize);
      LogManager.instance().info(this, "Allocation of " + pageCount + " pages.");

      final var bufferPool = ByteBufferPool.instance(null);
      final List<Pointer> pages = new ArrayList<>(pageCount);

      for (var i = 0; i < pageCount; i++) {
        pages.add(bufferPool.acquireDirect(true, Intention.PAGE_PRE_ALLOCATION));
      }

      for (final var pointer : pages) {
        bufferPool.release(pointer);
      }

      pages.clear();
    }

    // Initialize readCache BEFORE setting running=true via super.startup().
    // Otherwise, a concurrent thread in onEmbeddedFactoryInit() may see
    // isRunning()==true and skip startup, then call createStorage() while
    // readCache is still null.
    final int cachePages = (int) (diskCacheSize / pageSize);
    final int pinBudget = commitPinBudgetPages(cachePages, percent);
    if (percent > 0 && pinBudget == 0) {
      LogManager.instance().warn(this,
          "%s=%s rounds down to zero pages for a read cache of %d pages."
              + " The page-set protocol is off.",
          GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.getKey(), percent, cachePages);
    }
    readCache = new LockFreeReadCache(ByteBufferPool.instance(null), diskCacheSize, pageSize,
        pinBudget, commitPageLimit);
    assert readCache != null : "readCache must be initialized before marking engine as running";
    super.startup();
  }

  private static long calculateReadCacheMaxMemory(final long cacheSize) {
    return (long) (cacheSize
        * ((100 - GlobalConfiguration.DISK_WRITE_CACHE_PART.getValueAsInteger()) / 100.0));
  }

  // Decimal multiplication preserves exact whole-page boundaries such as 100000 * 14.99 / 100.
  static int commitPinBudgetPages(int cachePages, double percent) {
    return BigDecimal.valueOf(percent).multiply(BigDecimal.valueOf(cachePages))
        .movePointLeft(2).intValue();
  }

  @Override
  public Storage createStorage(
      final String dbName,
      long maxWalSegSize,
      long doubleWriteLogMaxSegSize,
      int storageId,
      YouTrackDBInternalEmbedded context) {
    try {
      var cache = readCache;
      if (cache == null) {
        throw new DatabaseException(dbName,
            "Disk engine readCache is null — engine has not been started."
                + " This indicates a race condition in engine lifecycle management."
                + " Engine running=" + isRunning());
      }

      return new DiskStorage(
          dbName,
          dbName,
          storageId,
          cache,
          files,
          maxWalSegSize,
          doubleWriteLogMaxSegSize,
          context);
    } catch (Exception e) {
      final var message =
          "Error on opening database: "
              + dbName
              + ". Current location is: "
              + new java.io.File(".").getAbsolutePath();
      LogManager.instance().error(this, message, e);

      throw BaseException.wrapException(new DatabaseException(dbName, message), e, dbName);
    }
  }

  @Override
  public String getName() {
    return NAME;
  }

  public ReadCache getReadCache() {
    return readCache;
  }

  @Override
  public String getNameFromPath(String dbPath) {
    return IOUtils.getRelativePathIfAny(dbPath, null);
  }

  @Override
  public void shutdown() {
    try {
      var cache = readCache;
      if (cache != null) {
        cache.clear();
      }
    } catch (Exception e) {
      LogManager.instance().error(this, "Error clearing read cache during shutdown", e);
    } finally {
      try {
        files.clear();
      } finally {
        super.shutdown();
      }
    }
  }
}
