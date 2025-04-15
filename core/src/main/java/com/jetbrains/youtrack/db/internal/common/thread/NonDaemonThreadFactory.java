package com.jetbrains.youtrack.db.internal.common.thread;

import com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class NonDaemonThreadFactory implements ThreadFactory {

  private final AtomicLong counter = new AtomicLong();
  private final String prefix;

  public NonDaemonThreadFactory(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Thread newThread(Runnable r) {
    final var thread = new Thread(r, prefix + " #" + counter.incrementAndGet());
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler());

    return thread;
  }
}
