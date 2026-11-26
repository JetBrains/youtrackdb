package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import java.util.Objects;
import java.util.Optional;

/// Retainable success or failure. Only the failure type name survives the callback.
/// This value never holds an exception, its message or its cause chain.
public final class CompletionOutcome {

  private final boolean success;
  private final Optional<String> errorType;

  // Factories are the only construction path, so callers cannot supply arbitrary error text.
  private CompletionOutcome(boolean success, Optional<String> errorType) {
    this.success = success;
    this.errorType = errorType;
  }

  /// A successful operation has no error type.
  public static CompletionOutcome succeeded() {
    return new CompletionOutcome(true, Optional.empty());
  }

  /// Copy only the class name. The caller must not retain the supplied failure.
  public static CompletionOutcome failed(Throwable failure) {
    return new CompletionOutcome(false, Optional.of(failure.getClass().getName()));
  }

  public boolean success() {
    return success;
  }

  public Optional<String> errorType() {
    return errorType;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CompletionOutcome outcome
        && success == outcome.success && errorType.equals(outcome.errorType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(success, errorType);
  }

  @Override
  public String toString() {
    return "CompletionOutcome[success=" + success + ", errorType=" + errorType + "]";
  }
}
