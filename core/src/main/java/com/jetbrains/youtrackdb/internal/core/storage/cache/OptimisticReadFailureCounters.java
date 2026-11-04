package com.jetbrains.youtrackdb.internal.core.storage.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide failure detections in shared optimistic page lookup, scope validation,
 * and zero-copy result extraction. Only the first failing check in a call is counted.
 * Counts are not fallback totals: component-specific guards are outside this measurement.
 * Sampling is weakly consistent while readers run. There is no counter work on success.
 */
public final class OptimisticReadFailureCounters {

  public enum Cause {
    /** A zero acquisition stamp or an invalid stamp at scope validation or result extraction. */
    STAMP,
    /** An apply was active at capture, or entered before final validation. */
    EPOCH,
    /** A looked-up frame has unassigned coordinates or belongs to another page. */
    COORDINATES,
    /** The shared cache has no live frame for the requested page. */
    LOOKUP_MISS,
    /** The transaction has private changes, so shared contents cannot serve the read. */
    LOCAL_CHANGES,
    /**
     * The recorded cache entry is no longer alive or no longer holds the frame used by the
     * read. Checked before the stamp, including when eviction and pool reuse both occurred.
     */
    FRAME_IDENTITY
  }

  private static final LongAdder[] COUNTS = new LongAdder[Cause.values().length];

  static {
    for (int i = 0; i < COUNTS.length; i++) {
      COUNTS[i] = new LongAdder();
    }
  }

  private OptimisticReadFailureCounters() {
  }

  public static OptimisticReadFailedException failure(Cause cause) {
    COUNTS[cause.ordinal()].increment();
    return OptimisticReadFailedException.INSTANCE;
  }

  public static long count(Cause cause) {
    return COUNTS[cause.ordinal()].sum();
  }
}
