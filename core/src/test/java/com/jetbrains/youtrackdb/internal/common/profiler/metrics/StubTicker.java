package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class StubTicker implements Ticker {

  private final long granularity;
  private final AtomicLong time;

  public StubTicker(long initialNanoTime, long granularity) {
    this.time = new AtomicLong(initialNanoTime);
    this.granularity = granularity;
  }

  public StubTicker(long granularity) {
    this(new Random().nextLong(0, Long.MAX_VALUE / 10), granularity);
  }

  @Override
  public void start() {

  }

  public void setTime(long time) {
    this.time.set(time);
  }

  public void advanceTime(long time) {
    this.time.addAndGet(time);
  }

  public void advanceTime(long time, TimeUnit unit) {
    advanceTime(unit.toNanos(time));
  }

  @Override
  public long lastNanoTime() {
    return time.get();
  }

  @Override
  public long currentNanoTime() {
    return time.get();
  }

  @Override
  public long getTick() {
    return time.get() / granularity;
  }

  @Override
  public long getGranularity() {
    return granularity;
  }

  @Override
  public void stop() {

  }
}
