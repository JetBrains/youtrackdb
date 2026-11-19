package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.List;

/**
 * Immutable token sequence. Equality compares every token, not just its hash. Token boundaries
 * refine the length-prefixed text encoding, so equal keys always imply equal text keys.
 */
final class GremlinShapeKey implements GremlinShapeTokens {

  private final Object[] tokens;
  private final long hash;

  GremlinShapeKey(List<Object> tokens) {
    this(tokens.toArray());
  }

  GremlinShapeKey(Object[] tokens) {
    this.tokens = tokens.clone();
    long running = GremlinShapeBuffer.SEED;
    for (var token : this.tokens) {
      running = GremlinShapeTokens.mix(running, token);
    }
    hash = GremlinShapeBuffer.finishHash(running);
  }

  @Override
  public int size() {
    return tokens.length;
  }

  @Override
  public Object token(int index) {
    return tokens[index];
  }

  @Override
  public long hash64() {
    return hash;
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
    return GremlinShapeTokens.fold(hash);
  }

  @Override
  public boolean equals(Object other) {
    return GremlinShapeTokens.equal(this, other);
  }
}
