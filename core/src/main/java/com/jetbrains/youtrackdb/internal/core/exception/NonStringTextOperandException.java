package com.jetbrains.youtrackdb.internal.core.exception;

/**
 * Thrown by a text predicate ({@code CONTAINSTEXT}, {@code ENDSWITH}, {@code STARTSWITH}, {@code
 * MATCHES}) only when it runs in strict mode and meets a present, non-{@code null}, non-{@code
 * String} left operand. Strict mode is opt-in and set exclusively by the Gremlin adapter to mirror
 * native TinkerPop {@code Text} semantics, which are String-only and error on a non-String operand.
 *
 * <p>Default (SQL/GQL) evaluation stays lenient — a non-String operand simply yields {@code false}
 * and never constructs this exception — so parsed-query behavior is unchanged. Extending {@link
 * CommandExecutionException} places this in YouTrackDB's query-error hierarchy and makes it a {@code
 * RuntimeException}, so it propagates through the executor (and TinkerPop's {@code applyStrategies})
 * to the caller. The message names the operator and the offending value's runtime type.
 */
public class NonStringTextOperandException extends CommandExecutionException {

  public NonStringTextOperandException(String operatorToken, Object value) {
    super(operatorToken + " requires a String operand but found a "
        + (value == null ? "null" : value.getClass().getName()));
  }
}
