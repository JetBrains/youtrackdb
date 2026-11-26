package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.thread.ScheduledThreadPoolExecutorWithLogging;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

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
    if (onCommitExecutor(executor)) {
      throw new IllegalStateException("File lifecycle call from the commitExecutor() thread");
    }
  }

  static void requireRegistrationCaller(ScheduledExecutorService executor) throws IOException {
    if (onCommitExecutor(executor)) {
      throw new IOException("File registration call from the commitExecutor() thread");
    }
  }

  private static boolean onCommitExecutor(ScheduledExecutorService executor) {
    return ((ScheduledThreadPoolExecutorWithLogging) executor).isExecutingOnCurrentThread();
  }

  static <T> T awaitRegistration(String storageName, Supplier<Future<T>> submit)
      throws IOException {
    final var step = new FileLifecycleStep(storageName, "File registration");
    Throwable failure = null;
    try {
      return step.awaitRegistrationResult(submit);
    } catch (IOException | RuntimeException | Error primary) {
      failure = primary;
      throw primary;
    } finally {
      step.finishRegistration(failure);
    }
  }

  private <T> T awaitRegistrationResult(Supplier<Future<T>> submit) throws IOException {
    try {
      return awaitCompletion(submit.get());
    } catch (RejectedExecutionException | CancellationException failure) {
      throw new IOException("File registration step did not run", failure);
    } catch (ExecutionException failure) {
      switch (failure.getCause()) {
        case IOException io -> throw io;
        case RuntimeException runtime -> throw runtime;
        case Error error -> throw error;
        default -> throw new IOException("File registration failed", failure.getCause());
      }
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

  void awaitClose(Future<Void> future) {
    try {
      awaitCompletion(future);
    } catch (CancellationException failure) {
      throw closeWorkFailure(storageName, operation, failure);
    } catch (ExecutionException failure) {
      switch (failure.getCause()) {
        case RuntimeException runtime -> throw runtime;
        case Error error -> throw error;
        default -> throw closeWorkFailure(storageName, operation, failure);
      }
    }
  }

  static BaseException closeWorkFailure(String storageName, String operation, Throwable failure) {
    return BaseException.wrapException(
        new WriteCacheException(storageName, operation + " was abnormally terminated"),
        failure, storageName);
  }

  static StorageException closeInUse(String storageName, int fileId) {
    return new StorageException(storageName,
        "Can not close file with id " + fileId + " because it is still in use");
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

  private void finishRegistration(Throwable failure) throws IOException {
    if (!restoreInterruption()) {
      return;
    }
    final var report = new IOException(operation + " was interrupted", interruption);
    if (failure == null) {
      throw report;
    }
    if (failure instanceof RuntimeException) {
      // Replay can log an unchecked failure and continue. The interrupt must stop replay.
      report.addSuppressed(failure);
      throw report;
    }
    failure.addSuppressed(report);
  }

  private boolean restoreInterruption() {
    if (accepted && Thread.interrupted() && interruption == null) {
      interruption = new InterruptedException();
    }
    if (interruption != null) {
      Thread.currentThread().interrupt();
      return true;
    }
    return false;
  }

  @Override
  public void close() {
    if (restoreInterruption()) {
      throw BaseException.wrapException(
          new ThreadInterruptedException(operation + " was interrupted"), interruption,
          storageName);
    }
  }
}
