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
 */

package com.jetbrains.youtrackdb.internal.core.engine;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.jnr.Native;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Memory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import java.util.Locale;

/**
 * Manages common initialization logic for memory and disk engines. These engines are tight
 * together through dependency to {@link ByteBufferPool}, which is hard to reconfigure if
 * initialization logic is separate.
 */
public class MemoryAndLocalPaginatedEnginesInitializer {

  /**
   * Shared initializer instance.
   */
  public static final MemoryAndLocalPaginatedEnginesInitializer INSTANCE =
      new MemoryAndLocalPaginatedEnginesInitializer();

  private boolean initialized = false;

  /**
   * Initializes common parts of memory and disk engines if not initialized yet. Does nothing if
   * engines already initialized.
   */
  public void initialize() {
    if (initialized) {
      return;
    }
    initialized = true;

    configureDefaults();

    Memory.checkCacheMemoryConfiguration();
    Memory.fixCommonConfigurationProblems();
  }

  private void configureDefaults() {
    if (!GlobalConfiguration.DISK_CACHE_SIZE.isChanged()) {
      configureDefaultDiskCacheSize();
    }

    if (!GlobalConfiguration.WAL_RESTORE_BATCH_SIZE.isChanged()) {
      configureDefaultWalRestoreBatchSize();
    }
  }

  private void configureDefaultWalRestoreBatchSize() {
    final var jvmMaxMemory = Runtime.getRuntime().maxMemory();
    if (jvmMaxMemory > 2L * FileUtils.GIGABYTE)
    // INCREASE WAL RESTORE BATCH SIZE TO 50K INSTEAD OF DEFAULT 1K
    {
      GlobalConfiguration.WAL_RESTORE_BATCH_SIZE.setValue(50000);
    } else if (jvmMaxMemory > 512 * FileUtils.MEGABYTE)
    // INCREASE WAL RESTORE BATCH SIZE TO 10K INSTEAD OF DEFAULT 1K
    {
      GlobalConfiguration.WAL_RESTORE_BATCH_SIZE.setValue(10000);
    }
  }

  private void configureDefaultDiskCacheSize() {
    final var osMemory = Native.instance().getMemoryLimit(true);
    if (osMemory == null) {
      LogManager.instance()
          .warn(
              this,
              "Can not determine amount of memory installed on machine, default size of disk cache"
                  + " will be used");
      return;
    }

    final var jvmMaxMemory = Memory.getCappedRuntimeMaxMemory(2L * 1024 * 1024 * 1024 /* 2GB */);
    LogManager.instance()
        .info(this, "JVM can use maximum %dMB of heap memory", jvmMaxMemory / (1024 * 1024));

    long diskCacheInMB;
    if (osMemory.insideContainer) {
      final var additionalArgs =
          new Object[]{
              GlobalConfiguration.MEMORY_LEFT_TO_CONTAINER.getValueAsString(),
              GlobalConfiguration.MEMORY_LEFT_TO_CONTAINER.getKey()
          };
      LogManager.instance()
          .info(
              this,
              "Because YouTrackDB is running inside a container %s of memory will be left unallocated"
                  + " according to the setting '%s' not taking into account heap memory",
              additionalArgs);

      diskCacheInMB =
          (calculateMemoryLeft(
              osMemory.memoryLimit,
              GlobalConfiguration.MEMORY_LEFT_TO_CONTAINER.getKey(),
              GlobalConfiguration.MEMORY_LEFT_TO_CONTAINER.getValueAsString())
              - jvmMaxMemory)
              / (1024 * 1024);
    } else {
      final var additionalArgs =
          new Object[]{
              GlobalConfiguration.MEMORY_LEFT_TO_OS.getValueAsString(),
              GlobalConfiguration.MEMORY_LEFT_TO_OS.getKey()
          };
      LogManager.instance()
          .info(
              this,
              "Because YouTrackDB is running outside a container %s of memory will be left "
                  + "unallocated according to the setting '%s' not taking into account heap memory",
              additionalArgs);

      diskCacheInMB =
          (calculateMemoryLeft(
              osMemory.memoryLimit,
              GlobalConfiguration.MEMORY_LEFT_TO_OS.getKey(),
              GlobalConfiguration.MEMORY_LEFT_TO_OS.getValueAsString())
              - jvmMaxMemory)
              / (1024 * 1024);
    }

    if (diskCacheInMB > 0) {
      LogManager.instance()
          .info(
              this,
              "YouTrackDB auto-config DISKCACHE=%,dMB (heap=%,dMB os=%,dMB)",
              diskCacheInMB,
              jvmMaxMemory / 1024 / 1024,
              osMemory.memoryLimit / 1024 / 1024);

      GlobalConfiguration.DISK_CACHE_SIZE.setValue(diskCacheInMB);
    } else {
      // LOW MEMORY: SET IT TO 256MB ONLY
      diskCacheInMB = ReadCache.MIN_CACHE_SIZE;
      LogManager.instance()
          .warn(
              this,
              "Not enough physical memory available for DISKCACHE: %,dMB (heap=%,dMB). Set lower"
                  + " Maximum Heap (-Xmx setting on JVM) and restart YouTrackDB. Now running with"
                  + " DISKCACHE="
                  + diskCacheInMB
                  + "MB",
              osMemory.memoryLimit / 1024 / 1024,
              jvmMaxMemory / 1024 / 1024);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(diskCacheInMB);

      LogManager.instance()
          .info(
              this,
              "YouTrackDB config DISKCACHE=%,dMB (heap=%,dMB os=%,dMB)",
              diskCacheInMB,
              jvmMaxMemory / 1024 / 1024,
              osMemory.memoryLimit / 1024 / 1024);
    }
  }

  private long calculateMemoryLeft(long memoryLimit, String parameter, String memoryLeft) {
    if (memoryLeft == null) {
      warningInvalidMemoryLeftValue(parameter, null);
      return memoryLimit;
    }

    memoryLeft = memoryLeft.toLowerCase(Locale.ENGLISH);
    if (memoryLeft.length() < 2) {
      warningInvalidMemoryLeftValue(parameter, memoryLeft);
      return memoryLimit;
    }

    final var lastChar = memoryLeft.charAt(memoryLeft.length() - 1);
    if (lastChar == '%') {
      final var percentValue = memoryLeft.substring(0, memoryLeft.length() - 1);

      final int percent;
      try {
        percent = Integer.parseInt(percentValue);
      } catch (NumberFormatException e) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      if (percent < 0 || percent >= 100) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      return (int) ((memoryLimit * (100.0 - percent)) / 100.0);
    } else if (lastChar == 'b') {
      final var bytesValue = memoryLeft.substring(0, memoryLeft.length() - 1);
      final long bytes;
      try {
        bytes = Long.parseLong(bytesValue);
      } catch (NumberFormatException e) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      if (bytes < 0) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      return memoryLimit - bytes;
    } else if (lastChar == 'k') {
      final var kbytesValue = memoryLeft.substring(0, memoryLeft.length() - 1);
      final long kbytes;
      try {
        kbytes = Long.parseLong(kbytesValue);
      } catch (NumberFormatException e) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      final var bytes = kbytes * 1024;
      if (bytes < 0) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      return memoryLimit - bytes;
    } else if (lastChar == 'm') {
      final var mbytesValue = memoryLeft.substring(0, memoryLeft.length() - 1);
      final long mbytes;
      try {
        mbytes = Long.parseLong(mbytesValue);
      } catch (NumberFormatException e) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      final var bytes = mbytes * 1024 * 1024;
      if (bytes < 0) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      return memoryLimit - bytes;
    } else if (lastChar == 'g') {
      final var gbytesValue = memoryLeft.substring(0, memoryLeft.length() - 1);
      final long gbytes;
      try {
        gbytes = Long.parseLong(gbytesValue);
      } catch (NumberFormatException e) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      final var bytes = gbytes * 1024 * 1024 * 1024;
      if (bytes < 0) {
        warningInvalidMemoryLeftValue(parameter, memoryLeft);
        return memoryLimit;
      }

      return memoryLimit - bytes;
    } else {
      warningInvalidMemoryLeftValue(parameter, memoryLeft);
      return memoryLimit;
    }
  }

  private void warningInvalidMemoryLeftValue(String parameter, String memoryLeft) {
    LogManager.instance()
        .warn(
            this,
            "Invalid value of '%s' parameter ('%s') memory limit will not be decreased",
            memoryLeft,
            parameter);
  }
}
