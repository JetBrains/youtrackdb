package com.jetbrains.youtrackdb.internal.core.db;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import javax.annotation.Nullable;

public class CommandTimeoutChecker {

  private final boolean active;
  private final long maxMills;
  private final ConcurrentHashMap<Thread, Long> running = new ConcurrentHashMap<>();
  private final ScheduledFuture<?> timer;

  public CommandTimeoutChecker(long timeout, SchedulerInternal scheduler) {
    this.maxMills = timeout;
    if (timeout > 0) {
      Runnable task = this::check;
      this.timer = scheduler.schedule(task, timeout / 10, timeout / 10);
      active = true;
    } else {
      this.timer = null;
      active = false;
    }
  }

  protected void check() {
    if (active) {
      var curTime = System.nanoTime() / 1000000;
      var iter = running.entrySet().iterator();
      while (iter.hasNext()) {
        var entry = iter.next();
        if (curTime > entry.getValue()) {
          entry.getKey().interrupt();
          iter.remove();
        }
      }
    }
  }

  public void startCommand(@Nullable Long timeout) {
    if (active) {
      var current = System.nanoTime() / 1000000;
      running.put(Thread.currentThread(), current + (timeout != null ? timeout : maxMills));
    }
  }

  public void endCommand() {
    if (active) {
      running.remove(Thread.currentThread());
    }
  }

  public void close() {
    if (timer != null) {
      timer.cancel(false);
    }
  }
}
