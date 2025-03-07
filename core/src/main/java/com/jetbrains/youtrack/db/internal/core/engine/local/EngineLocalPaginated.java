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

package com.jetbrains.youtrack.db.internal.core.engine.local;

import com.jetbrains.youtrack.db.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.jnr.Native;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.engine.EngineAbstract;
import com.jetbrains.youtrack.db.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.AsyncReadCache;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.fs.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @since 28.03.13
 */
public class EngineLocalPaginated extends EngineAbstract {

  public static final String NAME = "plocal";

  private volatile ReadCache readCache;

  protected final ClosableLinkedContainer<Long, File> files =
      new ClosableLinkedContainer<>(getOpenFilesLimit());

  public EngineLocalPaginated() {
  }

  private static int getOpenFilesLimit() {
    if (GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger() > 0) {
      final Object[] additionalArgs =
          new Object[]{GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger()};
      LogManager.instance()
          .info(
              EngineLocalPaginated.class,
              "Limit of open files for disk cache will be set to %d.",
              additionalArgs);
      return GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger();
    }

    final int defaultLimit = 512;
    final int recommendedLimit = 256 * 1024;

    return Native.instance().getOpenFilesLimit(true, recommendedLimit, defaultLimit);
  }

  @Override
  public void startup() {
    final String userName = System.getProperty("user.name", "unknown");
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
    super.startup();

    final long diskCacheSize =
        calculateReadCacheMaxMemory(
            GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong() * 1024 * 1024);
    final int pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

    if (GlobalConfiguration.DIRECT_MEMORY_PREALLOCATE.getValueAsBoolean()) {
      final int pageCount = (int) (diskCacheSize / pageSize);
      LogManager.instance().info(this, "Allocation of " + pageCount + " pages.");

      final ByteBufferPool bufferPool = ByteBufferPool.instance(null);
      final List<Pointer> pages = new ArrayList<>(pageCount);

      for (int i = 0; i < pageCount; i++) {
        pages.add(bufferPool.acquireDirect(true, Intention.PAGE_PRE_ALLOCATION));
      }

      for (final Pointer pointer : pages) {
        bufferPool.release(pointer);
      }

      pages.clear();
    }

    readCache = new AsyncReadCache(ByteBufferPool.instance(null), diskCacheSize, pageSize);
  }

  private static long calculateReadCacheMaxMemory(final long cacheSize) {
    return (long)
        (cacheSize
            * ((100 - GlobalConfiguration.DISK_WRITE_CACHE_PART.getValueAsInteger()) / 100.0));
  }

  /**
   * @param cacheSize Cache size in bytes.
   * @see ReadCache#changeMaximumAmountOfMemory(long)
   */
  public void changeCacheSize(final long cacheSize) {
    if (readCache != null) {
      readCache.changeMaximumAmountOfMemory(calculateReadCacheMaxMemory(cacheSize));
    }

    // otherwise memory size will be set during cache initialization.
  }

  public Storage createStorage(
      final String dbName,
      long maxWalSegSize,
      long doubleWriteLogMaxSegSize,
      int storageId,
      YouTrackDBInternal context) {
    try {

      return new LocalPaginatedStorage(
          dbName,
          dbName,
          storageId,
          readCache,
          files,
          maxWalSegSize,
          doubleWriteLogMaxSegSize,
          context);
    } catch (Exception e) {
      final String message =
          "Error on opening database: "
              + dbName
              + ". Current location is: "
              + new java.io.File(".").getAbsolutePath();
      LogManager.instance().error(this, message, e);

      throw BaseException.wrapException(new DatabaseException(message), e);
    }
  }

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
      readCache.clear();
      files.clear();
    } finally {
      super.shutdown();
    }
  }
}
