package com.jetbrains.youtrack.db.internal.common.thread;

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
    return thread;
  }

  protected abstract String nextThreadName();
}
