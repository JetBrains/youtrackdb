package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import java.math.BigDecimal;

/** Process-wide settings. Apply before YourTracks.instance starts the engine. */
public record WriterSettings(int bufferMiB, int writeCachePercent, int pageKiB,
    int commitLimit, double pinPercent) {

  private static final String PREFIX = "youtrackdb.storage.diskCache.";
  public static final int RECORD_PAGE_BOUND = 6;

  public WriterSettings {
    if (bufferMiB < 1 || writeCachePercent < 0 || writeCachePercent >= 100
        || pageKiB < 1 || commitLimit < 2 || !Double.isFinite(pinPercent)
        || pinPercent <= 0 || pinPercent > 15) {
      throw new IllegalArgumentException("Invalid explicit writer settings");
    }
  }

  public long readCachePages() {
    return (long) bufferMiB * 1024 * (100 - writeCachePercent) / 100 / pageKiB;
  }

  public long pinPages() {
    return BigDecimal.valueOf(pinPercent).multiply(BigDecimal.valueOf(readCachePages()))
        .movePointLeft(2).longValue();
  }

  public void checkCapacity(int writers, int pagesPerCommit) {
    validateRun(writers, 1);
    if (pagesPerCommit < 1 || pagesPerCommit > commitLimit
        || pinPages() < (long) writers * pagesPerCommit) {
      throw new IllegalArgumentException("Insufficient explicit commit capacity: pins="
          + pinPages() + ", required=" + (long) writers * pagesPerCommit
          + ", limit=" + commitLimit);
    }
  }

  public static void validateRun(int writers, int forks) {
    if ((writers != 1 && writers != 4) || forks != 1) {
      throw new IllegalArgumentException("Use one fork and one or four writers, never -f 0");
    }
  }

  public void apply(int writers, int pagesPerCommit) {
    // Reject before seed creation. Never increase a user's budget to make a run fit.
    checkCapacity(writers, pagesPerCommit);
    System.setProperty(PREFIX + "bufferSize", Integer.toString(bufferMiB));
    System.setProperty(PREFIX + "writeCachePart", Integer.toString(writeCachePercent));
    System.setProperty(PREFIX + "pageSize", Integer.toString(pageKiB));
    System.setProperty(PREFIX + "commitPageLimit", Integer.toString(commitLimit));
    System.setProperty(PREFIX + "commitPinBudgetPercent", Double.toString(pinPercent));
    // Reading back detects an enum initialized before these properties were applied.
    verifySettings(Boolean.getBoolean("pagelock.expectUnavailable"));
    if (YouTrackDBEnginesManager.instance().getEngineIfRunning("disk") != null) {
      throw new IllegalStateException(
          "Writer settings require a fresh JVM: disk engine is running");
    }
  }

  private void verifySettings(boolean unavailable) {
    String[] keys = {"bufferSize", "writeCachePart", "pageSize", "commitPageLimit",
        "commitPinBudgetPercent"};
    double[] expected = {bufferMiB, writeCachePercent, pageKiB, commitLimit, pinPercent};
    for (int i = 0; i < keys.length; i++) {
      var setting = GlobalConfiguration.findByKey(PREFIX + keys[i]);
      // Only the two page-set settings are absent on develop.
      if (setting == null && unavailable && i >= 3) {
        continue;
      }
      if (setting == null || Double.compare(setting.getValueAsDouble(), expected[i]) != 0) {
        throw new IllegalStateException("Writer setting did not take effect: " + keys[i]
            + ", expected " + expected[i] + ", actual "
            + (setting == null ? "missing" : setting.getValueAsString()));
      }
    }
  }

  /** Missing APIs are permitted only on develop. A partial API is always an error. */
  public void verifyCache(ReadCache cache, boolean unavailable) {
    verifySettings(unavailable);
    String[] names = {"commitPageLimit", "writePinBudget", "reservedWritePins"};
    long[] expected = {commitLimit, pinPages(), 0};
    int found = 0;
    for (int i = 0; i < names.length; i++) {
      try {
        var method = cache.getClass().getMethod(names[i]);
        found++;
        long actual = ((Number) CounterProbes.invoke(method, cache, new Object[0])).longValue();
        if (actual != expected[i]) {
          throw new IllegalStateException(names[i] + ": expected " + expected[i]
              + ", actual " + actual);
        }
      } catch (NoSuchMethodException absent) {
        // Develop has none of these methods.
      }
    }
    if (found != (unavailable ? 0 : names.length)) {
      throw new IllegalStateException("Missing, partial, or unexpected cache API");
    }
  }
}
