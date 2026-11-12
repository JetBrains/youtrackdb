package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable token sequence. Equality compares every token, not just its hash. Token boundaries
 * refine the length-prefixed text encoding, so equal keys always imply equal text keys.
 */
final class GremlinShapeKey {

  private final Object[] tokens;
  private final int hash;

  GremlinShapeKey(List<Object> tokens) {
    this.tokens = tokens.toArray();
    hash = Arrays.hashCode(this.tokens);
  }

  GremlinShapeKey(Object[] tokens) {
    this.tokens = tokens.clone();
    hash = Arrays.hashCode(this.tokens);
  }

  /** Shared text renderer for diagnostics and GremlinShapeEncoder predicate fingerprints. */
  @Override
  public String toString() {
    var text = new StringBuilder();
    for (var token : tokens) {
      text.append(token);
      if (token instanceof Integer) {
        text.append(':');
      }
    }
    return text.toString();
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GremlinShapeKey key && Arrays.equals(tokens, key.tokens);
  }
}
