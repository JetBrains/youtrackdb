package com.jetbrains.youtrack.db.internal.core.db;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class CommandTimeoutChecker {

  private final boolean active;
  private final long maxMills;
  private final ConcurrentHashMap<Thread, Long> running = new ConcurrentHashMap<>();
  private final TimerTask timer;

  public CommandTimeoutChecker(long timeout, SchedulerInternal scheduler) {
    this.timer =
        new TimerTask() {
          @Override
          public void run() {
            CommandTimeoutChecker.this.check();
          }
        };
    this.maxMills = timeout;
    if (timeout > 0) {
      scheduler.schedule(timer, timeout / 10, timeout / 10);
      active = true;
    } else {
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

  public void startCommand(Optional<Long> timeout) {
    if (active) {
      var current = System.nanoTime() / 1000000;
      running.put(Thread.currentThread(), current + timeout.orElse(maxMills));
    }
  }

  public void endCommand() {
    if (active) {
      running.remove(Thread.currentThread());
    }
  }

  public void close() {
    this.timer.cancel();
  }
}
