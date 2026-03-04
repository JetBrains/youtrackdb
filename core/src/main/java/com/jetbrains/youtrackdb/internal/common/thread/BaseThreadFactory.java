package com.jetbrains.youtrackdb.internal.common.thread;

import com.jetbrains.youtrackdb.internal.common.util.UncaughtExceptionHandler;
import java.util.concurrent.ThreadFactory;

abstract class BaseThreadFactory implements ThreadFactory {

  private final ThreadGroup parentThreadGroup;

  protected BaseThreadFactory(ThreadGroup parentThreadGroup) {
    this.parentThreadGroup = parentThreadGroup;
  }

  @Override
  public final Thread newThread(final Runnable r) {
    final var thread = new Thread(parentThreadGroup, r);
    thread.setDaemon(true);
    thread.setName(nextThreadName());
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler());
    return thread;
  }

  protected abstract String nextThreadName();
}
