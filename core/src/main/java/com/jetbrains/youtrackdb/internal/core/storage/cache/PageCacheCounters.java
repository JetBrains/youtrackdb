package com.jetbrains.youtrackdb.internal.core.storage.cache;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Cache progress measurements. Each cache exposes only the measurements it owns. */
public final class PageCacheCounters {
  private PageCacheCounters() {
  }

  /** Shared read-cache eviction measurements. */
  public static final class Eviction {
    private final LongAdder evictionStops = new LongAdder();
    private final LongAccumulator largestExcess = new LongAccumulator(Long::max, 0);

    public void recordEvictionStop() {
      evictionStops.increment();
    }

    public void recordExcess(long excess) {
      largestExcess.accumulate(Math.max(0, excess));
    }

    public long evictionStops() {
      return evictionStops.sum();
    }

    public long largestExcess() {
      return largestExcess.get();
    }
  }

  /** Storage write-cache flush measurements. */
  public static final class Flush {
    private final LongAdder flushSkips = new LongAdder();

    public void recordFlushSkip() {
      flushSkips.increment();
    }

    public long flushSkips() {
      return flushSkips.sum();
    }
  }
}
