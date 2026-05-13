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
      for (var entry : running.entrySet()) {
        var thread = entry.getKey();
        var deadline = entry.getValue();
        if (curTime > deadline) {
          thread.interrupt();
          // Conditional remove: between thread.interrupt() above and removing the entry
          // here, the worker thread may wake up from its Thread.sleep, catch the
          // InterruptedException, finish its endCommand calls, and re-register with a
          // fresh deadline via startCommand(). CHM's Iterator.remove() and the
          // unconditional running.remove(thread) overload both remove by key alone, so
          // either would wipe that fresh entry, leaving no future sweep able to interrupt
          // the re-registered command. The two-arg remove(thread, deadline) is a CAS that
          // only removes if the value is still the deadline we just expired, preserving
          // any concurrent re-registration. The race is observable on Windows JDK 25,
          // where the just-interrupted worker is scheduled promptly enough to slip its
          // re-registration in between the two operations above.
          running.remove(thread, deadline);
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
