package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import javax.annotation.Nullable;

/** Ordering helpers for optional write-ahead log positions used by cache protection. */
final class LogSequenceNumberUtils {

  private LogSequenceNumberUtils() {
  }

  /**
   * Returns the earlier position, treating {@code null} as no protection requirement.
   * Equal positions retain {@code first}.
   */
  @Nullable static LogSequenceNumber earliest(
      @Nullable final LogSequenceNumber first, @Nullable final LogSequenceNumber second) {
    if (first == null) {
      return second;
    }
    if (second == null || first.compareTo(second) <= 0) {
      return first;
    }
    return second;
  }
}
