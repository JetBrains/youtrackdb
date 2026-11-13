package com.jetbrains.youtrackdb.internal.common.thread;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/**
 * The same as thread {@link ScheduledThreadPoolExecutor} but also logs all exceptions happened
 * inside of the tasks which caused tasks to stop.
 */
public class ScheduledThreadPoolExecutorWithLogging extends ScheduledThreadPoolExecutor {

  private static final ThreadLocal<ScheduledThreadPoolExecutorWithLogging> CURRENT =
      new ThreadLocal<>();

  /** Returns true only while this executor is running a task on its worker. */
  public boolean isExecutingOnCurrentThread() {
    return CURRENT.get() == this;
  }

  @Override
  protected void beforeExecute(Thread thread, Runnable task) {
    super.beforeExecute(thread, task);
    CURRENT.set(this);
  }

  public ScheduledThreadPoolExecutorWithLogging(int corePoolSize, ThreadFactory threadFactory) {
    super(corePoolSize, threadFactory);
    // Remove cancelled tasks from the queue immediately to prevent accumulation
    // in long-running processes with many schedule/cancel cycles (e.g., WAL instances).
    setRemoveOnCancelPolicy(true);
  }

  @SuppressWarnings("unused")
  public ScheduledThreadPoolExecutorWithLogging(
      int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
    super(corePoolSize, threadFactory, handler);
    setRemoveOnCancelPolicy(true);
  }

  @Override
  protected void afterExecute(Runnable r, Throwable t) {
    CURRENT.remove();
    super.afterExecute(r, t);

    if ((t == null) && r instanceof Future<?> future) {
      // scheduled futures can block execution forever if they are not done
      if (future.isDone()) {
        try {
          future.get();
        } catch (CancellationException ce) {
          // ignore it we cancel tasks on shutdown that is normal
        } catch (ExecutionException ee) {
          t = ee.getCause();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt(); // ignore/reset
        }
      }
    }

    if (t != null) {
      final var thread = Thread.currentThread();
      LogManager.instance().error(this, "Exception in thread '%s'", t, thread.getName());
    }
  }
}
