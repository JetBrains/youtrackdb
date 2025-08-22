package com.jetbrains.youtrackdb.internal.common.profiler;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBScheduler;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBShutdownListener;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBStartupListener;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A component that is responsible for database profiling and monitoring. At the moment it is
 * capable of the following things:
 * <ol>
 *   <li>Various metrics collection and exposing them via JMX (via {@link MetricsRegistry).</li>
 *   <li>Periodic memory check (via {@link MemoryChecker}).</li>
 *   <li>Providing a way to dump environment and thread information (via {@link #dumpEnvironment()} and {@link #threadDump()}).</li>
 * </ol>
 */
public class Profiler implements YouTrackDBStartupListener, YouTrackDBShutdownListener {

  private static final Logger logger = LoggerFactory.getLogger(Profiler.class);

  private final Ticker ticker = new GranularTicker(
      GlobalConfiguration.PROFILER_TICKER_GRANULARITY.getValueAsLong());
  private final MetricsRegistry metricsRegistry = new MetricsRegistry(ticker);
  private final YouTrackDBScheduler scheduler;

  public Profiler(YouTrackDBScheduler scheduler) {
    this.scheduler = scheduler;
  }

  public MetricsRegistry getMetricsRegistry() {
    return metricsRegistry;
  }

  @Override
  public void onStartup() {
    ticker.start();

    final long memoryCheckInterval = GlobalConfiguration.PROFILER_MEMORYCHECK_INTERVAL.getValueAsLong();

    if (memoryCheckInterval > 0) {
      scheduler.scheduleTask(new MemoryChecker(), memoryCheckInterval, memoryCheckInterval);
    }
  }

  @Override
  public void onShutdown() {
    metricsRegistry.shutdown();
    ticker.stop();
  }

  public static String dumpEnvironment() {
    final StringBuilder buffer = new StringBuilder();

    final Runtime runtime = Runtime.getRuntime();

    final long freeSpaceInMB = new File(".").getFreeSpace();
    final long totalSpaceInMB = new File(".").getTotalSpace();

    int stgs = 0;
    long diskCacheUsed = 0;
    long diskCacheTotal = 0;
    for (Storage stg : YouTrackDBEnginesManager.instance().getStorages()) {
      if (stg instanceof DiskStorage) {
        diskCacheUsed += ((DiskStorage) stg).getReadCache().getUsedMemory();
        diskCacheTotal += GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong() * 1024 * 1024;
        stgs++;
      }
    }
    try {
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      ObjectName osMBeanName =
          ObjectName.getInstance(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
      if (mbs.isInstanceOf(osMBeanName, "com.sun.management.OperatingSystemMXBean")) {
        final long osTotalMem =
            ((Number) mbs.getAttribute(osMBeanName, "TotalPhysicalMemorySize")).longValue();
        final long osUsedMem =
            osTotalMem
                - ((Number) mbs.getAttribute(osMBeanName, "FreePhysicalMemorySize")).longValue();

        buffer.append(
            String.format(
                "YouTrackDB Memory profiler: HEAP=%s of %s - DISKCACHE (%s dbs)=%s of %s - OS=%s of"
                    + " %s - FS=%s of %s",
                FileUtils.getSizeAsString(runtime.totalMemory() - runtime.freeMemory()),
                FileUtils.getSizeAsString(runtime.maxMemory()),
                stgs,
                FileUtils.getSizeAsString(diskCacheUsed),
                FileUtils.getSizeAsString(diskCacheTotal),
                FileUtils.getSizeAsString(osUsedMem),
                FileUtils.getSizeAsString(osTotalMem),
                FileUtils.getSizeAsString(freeSpaceInMB),
                FileUtils.getSizeAsString(totalSpaceInMB)));
      }

    } catch (Exception e) {
      // JMX NOT AVAILABLE, AVOID OS DATA
      buffer.append(
          String.format(
              "YouTrackDB Memory profiler: HEAP=%s of %s - DISKCACHE (%s dbs)=%s of %s - FS=%s of %s",
              FileUtils.getSizeAsString(runtime.totalMemory() - runtime.freeMemory()),
              FileUtils.getSizeAsString(runtime.maxMemory()),
              stgs,
              FileUtils.getSizeAsString(diskCacheUsed),
              FileUtils.getSizeAsString(diskCacheTotal),
              FileUtils.getSizeAsString(freeSpaceInMB),
              FileUtils.getSizeAsString(totalSpaceInMB)));
    }

    return buffer.toString();
  }

  public static String threadDump() {
    final StringBuilder dump = new StringBuilder();
    dump.append("THREAD DUMP\n");
    final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    final ThreadInfo[] threadInfos =
        threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 100);
    for (ThreadInfo threadInfo : threadInfos) {
      dump.append('"');
      dump.append(threadInfo.getThreadName());
      dump.append("\" ");
      final Thread.State state = threadInfo.getThreadState();
      dump.append("\n   java.lang.Thread.State: ");
      dump.append(state);
      final StackTraceElement[] stackTraceElements = threadInfo.getStackTrace();
      for (final StackTraceElement stackTraceElement : stackTraceElements) {
        dump.append("\n        at ");
        dump.append(stackTraceElement);
      }
      dump.append("\n\n");
    }
    return dump.toString();
  }

  static final class MemoryChecker implements Runnable {

    @Override
    public void run() {
      try {
        final long jvmTotMemory = Runtime.getRuntime().totalMemory();
        final long jvmMaxMemory = Runtime.getRuntime().maxMemory();

        for (Storage s : YouTrackDBEnginesManager.instance().getStorages()) {
          if (s instanceof DiskStorage) {
            final ReadCache dk = ((DiskStorage) s).getReadCache();
            final WriteCache wk = ((DiskStorage) s).getWriteCache();
            if (dk == null || wk == null)
            // NOT YET READY
            {
              continue;
            }

            final long totalDiskCacheUsedMemory =
                (dk.getUsedMemory() + wk.getExclusiveWriteCachePagesSize()) / FileUtils.MEGABYTE;
            final long maxDiskCacheUsedMemory =
                GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong();

            // CHECK IF THERE IS MORE THAN 40% HEAP UNUSED AND DISK-CACHE IS 80% OF THE MAXIMUM SIZE
            if ((jvmTotMemory * 140 / 100) < jvmMaxMemory
                && (totalDiskCacheUsedMemory * 120 / 100) > maxDiskCacheUsedMemory) {

              final long suggestedMaxHeap = jvmTotMemory * 120 / 100;
              final long suggestedDiskCache =
                  GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong()
                      + (jvmMaxMemory - suggestedMaxHeap) / FileUtils.MEGABYTE;

              LogManager.instance()
                  .info(
                      this,
                      "Database '%s' uses %,dMB/%,dMB of DISKCACHE memory, while Heap is not"
                          + " completely used (usedHeap=%dMB maxHeap=%dMB). To improve performance"
                          + " set maxHeap to %dMB and DISKCACHE to %dMB",
                      s.getName(),
                      totalDiskCacheUsedMemory,
                      maxDiskCacheUsedMemory,
                      jvmTotMemory / FileUtils.MEGABYTE,
                      jvmMaxMemory / FileUtils.MEGABYTE,
                      suggestedMaxHeap / FileUtils.MEGABYTE,
                      suggestedDiskCache);

              LogManager.instance()
                  .info(
                      this,
                      "-> Open server.sh (or server.bat on Windows) and change the following"
                          + " variables: 1) MAXHEAP=-Xmx%dM 2) MAXDISKCACHE=%d",
                      suggestedMaxHeap / FileUtils.MEGABYTE,
                      suggestedDiskCache);
            }
          }
        }
      } catch (Exception e) {
        LogManager.instance().debug(this, "Error on memory checker task", logger, e);
      } catch (Error e) {
        LogManager.instance().debug(this, "Error on memory checker task", logger, e);
        throw e;
      }
    }
  }
}
