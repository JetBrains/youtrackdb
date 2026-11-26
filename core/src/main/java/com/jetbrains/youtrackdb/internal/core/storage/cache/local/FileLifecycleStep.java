package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.thread.ScheduledThreadPoolExecutorWithLogging;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/** Defers interruption reporting until caller-side cleanup of accepted file work has finished. */
final class FileLifecycleStep implements AutoCloseable {

  private final String storageName;
  private final String operation;
  private InterruptedException interruption;
  private boolean accepted;

  FileLifecycleStep(String storageName, String operation) {
    this.storageName = storageName;
    this.operation = operation;
  }

  static void requireCaller(ScheduledExecutorService executor) {
    if (((ScheduledThreadPoolExecutorWithLogging) executor).isExecutingOnCurrentThread()) {
      throw new IllegalStateException("File lifecycle call from the commitExecutor() thread");
    }
  }

  <T> T await(Future<T> future) throws IOException {
    try {
      return awaitCompletion(future);
    } catch (ExecutionException failure) {
      switch (failure.getCause()) {
        case IOException io -> throw io;
        case RuntimeException runtime -> throw runtime;
        case Error error -> throw error;
        default -> throw BaseException.wrapException(
            new WriteCacheException(storageName, operation + " failed"), failure.getCause(),
            storageName);
      }
    }
  }

  <T> T awaitCompletion(Future<T> future) throws ExecutionException {
    accepted = true;
    try {
      while (true) {
        try {
          return future.get();
        } catch (InterruptedException interrupted) {
          interruption = interrupted;
        }
      }
    } finally {
      // A completed future need not check the interrupt bit.
      if (Thread.interrupted() && interruption == null) {
        interruption = new InterruptedException();
      }
    }
  }

  @Override
  public void close() {
    if (accepted && Thread.interrupted() && interruption == null) {
      interruption = new InterruptedException();
    }
    if (interruption != null) {
      Thread.currentThread().interrupt();
      throw BaseException.wrapException(
          new ThreadInterruptedException(operation + " was interrupted"), interruption,
          storageName);
    }
  }
}
