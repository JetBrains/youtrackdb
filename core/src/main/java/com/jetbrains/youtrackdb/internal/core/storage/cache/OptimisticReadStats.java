package com.jetbrains.youtrackdb.internal.core.storage.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * JVM-global diagnostic counters for the optimistic read path. Because
 * {@link OptimisticReadFailedException} is a stackless singleton, optimistic-read failures
 * are otherwise invisible to profilers and benchmarks; these counters make the failure
 * modes observable — in particular the split between per-page <b>stamp</b> aborts and
 * cross-page <b>apply-phase epoch</b> aborts, which is the key evidence for measuring how
 * often the storage-wide {@link ApplyPhaseEpoch} forces readers onto the pinned fallback
 * path (YTDB-1203).
 *
 * <p>Cost: counters are incremented <em>only on failure paths</em> (which already pay for
 * an exception throw and a shared-lock fallback), so the happy path is untouched.
 * {@link LongAdder} keeps increments contention-free under many reader threads.
 *
 * <p>Scope: counters are static and therefore aggregated across all storages in the JVM.
 * They are intended for benchmarks, tests, and ad-hoc diagnostics — not for per-storage
 * monitoring.
 */
public final class OptimisticReadStats {

  // Optimistic attempts that failed per-page stamp validation (page modified/evicted
  // between read and validation). Incremented from OptimisticReadScope.
  private static final LongAdder STAMP_ABORTS = new LongAdder();

  // Optimistic attempts whose stamps were all valid but that failed the apply-phase
  // epoch check (a commit-time apply overlapped the read window). Incremented from
  // OptimisticReadScope.validateOrThrow().
  private static final LongAdder EPOCH_ABORTS = new LongAdder();

  // Total optimistic read attempts that fell back to the CAS-pinned path, whatever the
  // reason (stamp abort, epoch abort, cache miss, exclusive lock held, in-tx changes,
  // speculative-read exception, ...). Incremented from
  // StorageComponent.executeOptimisticStorageRead's fallback catch.
  private static final LongAdder FALLBACKS = new LongAdder();

  private OptimisticReadStats() {
  }

  /** Records an optimistic read abort caused by per-page stamp validation failure. */
  public static void onStampAbort() {
    STAMP_ABORTS.increment();
  }

  /** Records an optimistic read abort caused by the apply-phase epoch check. */
  public static void onEpochAbort() {
    EPOCH_ABORTS.increment();
  }

  /** Records a fallback of an optimistic read attempt to the CAS-pinned path. */
  public static void onFallback() {
    FALLBACKS.increment();
  }

  /** Returns the total number of stamp-validation aborts since start (or last reset). */
  public static long stampAborts() {
    return STAMP_ABORTS.sum();
  }

  /** Returns the total number of apply-phase epoch aborts since start (or last reset). */
  public static long epochAborts() {
    return EPOCH_ABORTS.sum();
  }

  /** Returns the total number of pinned-path fallbacks since start (or last reset). */
  public static long fallbacks() {
    return FALLBACKS.sum();
  }

  /**
   * Resets all counters to zero. Intended for benchmark iteration boundaries; racing
   * increments from concurrent readers may survive the reset (LongAdder.reset is not
   * atomic with respect to concurrent adds) — acceptable for diagnostic use.
   */
  public static void reset() {
    STAMP_ABORTS.reset();
    EPOCH_ABORTS.reset();
    FALLBACKS.reset();
  }
}
