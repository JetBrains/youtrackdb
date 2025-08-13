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

package com.jetbrains.youtrackdb.internal.common.util;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.jnr.Native;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;

/**
 * Provides various utilities related to memory management and configuration.
 */
public class Memory {

  /**
   * @param unlimitedCap the upper limit on reported memory, if JVM reports unlimited memory.
   * @return same as {@link Runtime#maxMemory()} except that {@code unlimitedCap} limit is applied
   * if JVM reports {@link Long#MAX_VALUE unlimited memory}.
   */
  public static long getCappedRuntimeMaxMemory(long unlimitedCap) {
    final var jvmMaxMemory = Runtime.getRuntime().maxMemory();
    return jvmMaxMemory == Long.MAX_VALUE ? unlimitedCap : jvmMaxMemory;
  }

  /**
   * Calculates the total configured maximum size of all YouTrackDB caches.
   *
   * @return the total maximum size of all YouTrackDB caches in bytes.
   */
  private static long getMaxCacheMemorySize() {
    return GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong() * 1024 * 1024;
  }

  /**
   * Checks the YouTrackDB cache memory configuration and emits a warning if configuration is
   * invalid.
   */
  public static void checkCacheMemoryConfiguration() {
    final var maxHeapSize = Runtime.getRuntime().maxMemory();
    final var maxCacheSize = getMaxCacheMemorySize();
    final var physicalMemory = Native.instance().getMemoryLimit(false);

    if (maxHeapSize != Long.MAX_VALUE
        && physicalMemory != null
        && maxHeapSize + maxCacheSize > physicalMemory.memoryLimit) {
      LogManager.instance()
          .warn(
              Memory.class,
              "The sum of the configured JVM maximum heap size ("
                  + maxHeapSize
                  + " bytes) "
                  + "and the YouTrackDB maximum cache size ("
                  + maxCacheSize
                  + " bytes) is larger than the available physical memory size "
                  + "("
                  + physicalMemory.memoryLimit
                  + " bytes). That may cause out of memory errors, please tune the configuration"
                  + " up. Use the -Xmx JVM option to lower the JVM maximum heap memory size or"
                  + " youtrackdb.storage.diskCache.bufferSize YouTrackDB option to lower memory requirements of"
                  + " the cache.");
    }
  }

  /**
   * Tries to fix some common cache/memory configuration problems:
   *
   * <ul>
   *   <li>Cache size is larger than direct memory size.
   *   <li>Memory chunk size is larger than cache size.
   *       <ul/>
   */
  public static void fixCommonConfigurationProblems() {
    var diskCacheSize = GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong();

    final var max32BitCacheSize = 512;
    if (getJavaBitWidth() == 32 && diskCacheSize > max32BitCacheSize) {
      LogManager.instance()
          .info(
              GlobalConfiguration.class,
              "32 bit JVM is detected. Lowering disk cache size from %,dMB to %,dMB.",
              diskCacheSize,
              max32BitCacheSize);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(max32BitCacheSize);
    }
  }

  private static int getJavaBitWidth() {
    // Figure out whether bit width of running JVM
    // Most of JREs support property "sun.arch.data.model" which is exactly what we need here
    var dataModel = System.getProperty("sun.arch.data.model", "64"); // By default assume 64bit
    var size = 64;
    try {
      size = Integer.parseInt(dataModel);
    } catch (NumberFormatException ignore) {
      // Ignore
    }
    return size;
  }

  private Memory() {
  }
}
