package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/** Differential oracle backed by independent frozen text, predicate and slot collectors. */
public final class TextShapeOracle {
  private TextShapeOracle() {
  }

  public static void
      verify(org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> t) {
    var session = com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
        .resolveYtdbSession(t);
    var settings =
        com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
            .translationSettings(t, session);
    var environment = TranslationEnvironment.resolve(t, session);
    var actual = GremlinStepWalker.extractShape(t, session, settings.orderIncludesMissingKey(),
        settings.nullPlacements(), settings.polymorphic(), environment);
    var reference = FrozenTextExtractor.extract(GremlinStepWalker.PRODUCTION_RECOGNISERS,
        GremlinStepWalker.TRANSPARENT_STEPS, t, session, settings.orderIncludesMissingKey(),
        settings.nullPlacements(), settings.polymorphic(), environment);
    org.junit.Assert.assertEquals(reference.key(), actual.key().toString());
    org.junit.Assert.assertEquals(reference.complete(), actual.complete());
    org.junit.Assert.assertEquals(reference.bindings(), actual.bindings());
    org.junit.Assert.assertEquals(reference.hasContributions(), actual.hasContributions());
    org.junit.Assert.assertEquals(reference.nativeOperands(), actual.nativeOperands());
  }
}
