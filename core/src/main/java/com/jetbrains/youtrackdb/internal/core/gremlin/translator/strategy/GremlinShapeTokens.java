package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/** Exact typed tokens shared by frozen keys and lookup-only leased probes. */
sealed interface GremlinShapeTokens permits GremlinShapeKey, GremlinShapeBuffer {
  int size();

  Object token(int index);

  long hash64();

  static long mix(long hash, Object token) {
    // Strings reuse their cached hash. Equality still compares their complete contents.
    long value = ((long) token.hashCode() << 1) ^ (token instanceof Integer ? 1 : 0);
    return (hash ^ value) * 0x100000001b3L;
  }

  static int fold(long hash) {
    return (int) (hash ^ (hash >>> 32));
  }

  static boolean equal(GremlinShapeTokens self, Object other) {
    if (!(other instanceof GremlinShapeTokens tokens)
        || self.hash64() != tokens.hash64() || self.size() != tokens.size()) {
      return false;
    }
    for (int i = 0; i < self.size(); i++) {
      if (!self.token(i).equals(tokens.token(i))) {
        return false;
      }
    }
    return true;
  }
}
