package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import java.util.concurrent.atomic.LongAdder;

/** A cumulative sum. Concurrent reads can lag updates that are still in progress. */
public interface Counter extends Metric<Long> {

  void add(long value);

  Counter NOOP = new Counter() {
    @Override
    public void add(long value) {
    }

    @Override
    public Long getValue() {
      return 0L;
    }
  };

  static Counter create() {
    return new Counter() {
      private final LongAdder value = new LongAdder();

      @Override
      public void add(long delta) {
        value.add(delta);
      }

      @Override
      public Long getValue() {
        return value.sum();
      }
    };
  }
}
