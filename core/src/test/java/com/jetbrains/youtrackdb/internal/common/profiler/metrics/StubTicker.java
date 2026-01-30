package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class StubTicker implements Ticker {

  private static final VarHandle TIME;

  static {
    try {
      TIME = MethodHandles.lookup().findVarHandle(StubTicker.class, "time", long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final long granularity;
  @SuppressWarnings("FieldMayBeFinal")
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
    TIME.setVolatile(this, time);
  }

  public void advanceTime(long time) {
    TIME.getAndAdd(this, time);
  }

  @Override
  public long approximateCurrentTimeMillis() {
    return time / 1_000_000;
  }

  public void advanceTime(long time, TimeUnit unit) {
    advanceTime(unit.toNanos(time));
  }

  @Override
  public long approximateNanoTime() {
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
