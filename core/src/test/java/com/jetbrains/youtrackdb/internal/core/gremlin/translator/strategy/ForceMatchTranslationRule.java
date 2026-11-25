package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.junit.rules.ExternalResource;

/** Keeps translator correctness tests on MATCH without changing production routing decisions. */
public final class ForceMatchTranslationRule extends ExternalResource {

  private Boolean previous;

  /** Worker tasks opt in explicitly rather than inheriting test state into arbitrary pools. */
  public static <T> T callWithMatch(java.util.concurrent.Callable<T> body) throws Exception {
    var scope = new ForceMatchTranslationRule();
    scope.before();
    try {
      return body.call();
    } finally {
      scope.after();
    }
  }

  @Override
  protected void before() {
    previous = GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.get();
    GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.set(true);
  }

  @Override
  protected void after() {
    if (previous == null) {
      GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.remove();
    } else {
      GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.set(previous);
    }
  }
}
