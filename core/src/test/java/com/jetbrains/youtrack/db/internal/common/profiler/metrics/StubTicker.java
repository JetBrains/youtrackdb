package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class StubTicker implements Ticker {

  private final long granularity;
  private volatile long time;

  public StubTicker(long initialNanoTime, long granularity) {
    this.time = initialNanoTime;
    this.granularity = granularity;
  }

  public StubTicker(long granularity) {
    this(new Random().nextLong(0, Long.MAX_VALUE / 10), granularity);
  }

  @Override
  public void start() {

  }

  public void setTime(long time) {
    this.time = time;
  }

  public void advanceTime(long time) {
    this.time += time;
  }

  public void advanceTime(long time, TimeUnit unit) {
    advanceTime(unit.toNanos(time));
  }

  @Override
  public long lastNanoTime() {
    return time;
  }

  @Override
  public long currentNanoTime() {
    return time;
  }

  @Override
  public long getTick() {
    return time / granularity;
  }

  @Override
  public long getGranularity() {
    return granularity;
  }

  @Override
  public void stop() {

  }
}
