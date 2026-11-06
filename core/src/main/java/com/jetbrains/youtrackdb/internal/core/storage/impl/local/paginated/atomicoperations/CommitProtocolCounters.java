package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import java.util.concurrent.atomic.LongAdder;

/**
 * Completed commit applies for one storage. The owning {@link AtomicOperationsManager} creates
 * fresh counters on storage reopen. Failed apply or apply cleanup contributes nothing.
 *
 * <p>Zero-change commits are not a protocol. For mutating commits selection precedence is forced,
 * file lifecycle, no pin budget, over limit, reservation denied, then page set. Thus a forced
 * lifecycle commit is FORCED, and a positive budget below the page count is OVER_LIMIT.
 * Reads are weakly consistent during concurrent commits. There is no shared total counter.
 */
public final class CommitProtocolCounters {

  public enum Outcome {
    /** All changed pages applied through a whole-set reservation. */
    PAGE_SET,
    /** Epoch apply because at least one file is created or deleted. */
    FILE_LIFECYCLE,
    /** Epoch apply because page count exceeds min(configured commit page limit, positive write-pin budget). */
    OVER_LIMIT,
    /** Epoch apply because write-pin budget is zero, including the in-memory engine. */
    NO_PIN_BUDGET,
    /** Epoch apply because the cache refused the whole-set reservation. */
    RESERVATION_DENIED,
    /** Epoch apply required by the test override, including non-null hook installation. */
    FORCED,
    /** No shared-cache mutation, excluded from protocol totals. */
    ZERO_CHANGE
  }

  private final LongAdder[] counts = new LongAdder[Outcome.values().length];

  public CommitProtocolCounters() {
    for (int i = 0; i < counts.length; i++) {
      counts[i] = new LongAdder();
    }
  }

  void record(Outcome outcome) {
    counts[outcome.ordinal()].increment();
  }

  /** Returns a weakly consistent count. Compare snapshots only when writers are quiescent. */
  public long count(Outcome outcome) {
    return counts[outcome.ordinal()].sum();
  }

  /** Derives the epoch total without adding an increment to the commit path. */
  public long epochCount() {
    return count(Outcome.FILE_LIFECYCLE) + count(Outcome.OVER_LIMIT)
        + count(Outcome.NO_PIN_BUDGET) + count(Outcome.RESERVATION_DENIED)
        + count(Outcome.FORCED);
  }
}
