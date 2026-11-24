package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Arrays;
import java.util.List;

/** Frozen Track 4 immutable token-key reference, independent of production hashing and equality. */
final class Track4TokenKey {
  private final Object[] tokens;
  private final int hash;

  Track4TokenKey(List<Object> tokens) {
    this.tokens = tokens.toArray();
    hash = Arrays.hashCode(this.tokens);
  }

  boolean matches(Object candidate) {
    if (!(candidate instanceof GremlinShapeTokens actual) || actual.size() != tokens.length) {
      return false;
    }
    for (int i = 0; i < tokens.length; i++) {
      if (!tokens[i].equals(actual.token(i))) {
        return false;
      }
    }
    return true;
  }

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
    return other instanceof Track4TokenKey key && Arrays.equals(tokens, key.tokens);
  }
}
