package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Collects and rethrows failures from per-file read-cache cleanup. */
final class FileCleanupFailures {

  private final Throwable first;
  private IdentityHashMap<Throwable, Boolean> seen;
  private ArrayList<Throwable> later;

  FileCleanupFailures(Throwable first) {
    this.first = first;
  }

  static FileCleanupFailures record(FileCleanupFailures failures, Throwable failure) {
    if (failures == null) {
      return new FileCleanupFailures(failure);
    }
    if (failure != failures.first) {
      if (failures.seen == null) {
        failures.seen = new IdentityHashMap<>();
        failures.later = new ArrayList<>();
      }
      if (failures.seen.put(failure, Boolean.TRUE) == null) {
        failures.later.add(failure);
      }
    }
    return failures;
  }

  void rethrow(StorageException pinnedRefusal) {
    // Throwable.addSuppressed takes the primary failure's monitor.
    if (later != null) {
      for (var failure : later) {
        first.addSuppressed(failure);
      }
    }
    if (pinnedRefusal != null) {
      first.addSuppressed(pinnedRefusal);
    }
    if (first instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    }
    throw (Error) first;
  }
}
