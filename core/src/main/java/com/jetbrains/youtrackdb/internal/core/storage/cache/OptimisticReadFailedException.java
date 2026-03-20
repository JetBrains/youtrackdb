package com.jetbrains.youtrackdb.internal.core.storage.cache;

/**
 * Thrown when an optimistic read detects that one or more page stamps have been invalidated
 * (e.g., the page was evicted or modified). This is not an error — it signals the caller to
 * fall back to the CAS-pinned read path.
 *
 * <p>Singleton instance with no stack trace for zero-allocation throws on the hot path.
 */
public final class OptimisticReadFailedException extends RuntimeException {

  public static final OptimisticReadFailedException INSTANCE =
      new OptimisticReadFailedException();

  private OptimisticReadFailedException() {
    super(null, null, true, false);
  }
}
