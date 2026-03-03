package com.jetbrains.youtrackdb.internal.core;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class YouTrackDBScheduler {

  private volatile boolean active = false;

  public void activate() {
    active = true;
  }

  public void shutdown() {
    active = false;
  }

  public ScheduledFuture<?> scheduleTask(final Runnable task, final long delay,
      final long period) {
    // Wrap task to catch exceptions and keep periodic tasks alive.
    // An uncaught exception in scheduleWithFixedDelay silently stops all future executions.
    // Error is intentionally rethrown to stop the task on serious failures.
    Runnable safeTask = () -> {
      try {
        task.run();
      } catch (Exception e) {
        LogManager.instance()
            .error(
                this,
                "Error during execution of task " + task.getClass().getSimpleName(),
                e);
      } catch (Error e) {
        LogManager.instance()
            .error(
                this,
                "Error during execution of task " + task.getClass().getSimpleName(),
                e);
        throw e;
      }
    };

    if (!active) {
      LogManager.instance().warn(this, "YouTrackDB engine is down. Task will not be scheduled.");
      return null;
    }

    ScheduledExecutorService pool = YouTrackDBEnginesManager.instance().getScheduledPool();
    if (period > 0) {
      return pool.scheduleWithFixedDelay(safeTask, delay, period, TimeUnit.MILLISECONDS);
    } else {
      return pool.schedule(safeTask, delay, TimeUnit.MILLISECONDS);
    }
  }
}
