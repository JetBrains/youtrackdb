package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.concurrent.atomic.AtomicInteger;

/// Supplies a real translator once, then exercises the strategy's swallowed-failure path.
public final class FailingTranslator {

  private FailingTranslator() {
  }

  /// The second call declines or throws, then subsequent calls translate successfully.
  public static GremlinToMatchStrategy onSecondCall(AtomicInteger calls, boolean throwsFailure) {
    return new GremlinToMatchStrategy(traversal -> {
      if (calls.incrementAndGet() == 2) {
        if (throwsFailure) {
          throw new IllegalStateException("injected transient translation failure");
        }
        return null;
      }
      return GremlinToMatchTranslator.translate(traversal);
    });
  }

  public static GremlinToMatchStrategy afterFirstCall(AtomicInteger calls) {
    return new GremlinToMatchStrategy(traversal -> {
      if (calls.incrementAndGet() > 1) {
        throw new IllegalStateException("injected translation failure");
      }
      return GremlinToMatchTranslator.translate(traversal);
    });
  }
}
