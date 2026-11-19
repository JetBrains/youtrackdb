package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/** Differential oracle backed by independent frozen text, predicate and slot collectors. */
public final class TextShapeOracle {
  private TextShapeOracle() {
  }

  /** Require a warm lookup through the internal fast-hit counters, not route telemetry. */
  public static void requireFastHit(Runnable compile) {
    var counters = new GremlinShapeBuffer.Counters();
    GremlinShapeBuffer.startCounting(counters);
    try {
      compile.run();
      org.junit.Assert.assertEquals(1, counters.leases);
      org.junit.Assert.assertEquals(1, counters.emissions);
      org.junit.Assert.assertEquals(1, counters.hits);
      org.junit.Assert.assertEquals(0, counters.fallbacks);
    } finally {
      GremlinShapeBuffer.stopCounting();
    }
  }

  private static int nestingDepth(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> traversal) {
    int depth = 1;
    for (var step : traversal.getSteps()) {
      if (step instanceof org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent parent) {
        for (var child : parent.getLocalChildren()) {
          depth = Math.max(depth, 1 + nestingDepth(child));
        }
        for (var child : parent.getGlobalChildren()) {
          depth = Math.max(depth, 1 + nestingDepth(child));
        }
      }
    }
    return depth;
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
    var tokenReference = Track4TokenExtractor.extract(GremlinStepWalker.PRODUCTION_RECOGNISERS,
        GremlinStepWalker.TRANSPARENT_STEPS, t, session, settings.orderIncludesMissingKey(),
        settings.nullPlacements(), settings.polymorphic(), environment);
    org.junit.Assert.assertTrue(tokenReference.key().matches(actual.key()));
    org.junit.Assert.assertEquals(
        Track4TokenEncoder.predicateStructure(
            org.apache.tinkerpop.gremlin.process.traversal.P.eq(1),
            false),
        GremlinShapeEncoder.predicateStructure(
            org.apache.tinkerpop.gremlin.process.traversal.P.eq(1), false));
    try (var lease = GremlinShapeBuffer.acquire()) {
      if (lease != null) {
        try {
          var fast = GremlinShapeExtractor.extract(GremlinStepWalker.PRODUCTION_RECOGNISERS,
              GremlinStepWalker.TRANSPARENT_STEPS, t, settings.orderIncludesMissingKey(),
              settings.nullPlacements(), settings.polymorphic(), environment,
              new GremlinShapeEncoder(session.getSchema(), lease));
          try {
            var events = GremlinShapeBuffer.class.getDeclaredField("eventCount");
            events.setAccessible(true);
            System.out.printf(java.util.Locale.ROOT,
                "Fast corpus bounds tokens=%d events=%d depth=%d%n",
                fast.key().size(), events.getInt(lease), nestingDepth(t));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
          org.junit.Assert.assertTrue(tokenReference.key().matches(fast.lookupKey()));
          org.junit.Assert.assertEquals(actual.key(), fast.lookupKey());
          org.junit.Assert.assertEquals(fast.lookupKey(), actual.key());
          org.junit.Assert.assertEquals(tokenReference.bindings(), fast.bindings());
          org.junit.Assert.assertEquals(tokenReference.hasContributions(), fast.hasContributions());
          org.junit.Assert.assertEquals(tokenReference.nativeOperands(), fast.nativeOperands());
        } catch (GremlinShapeBuffer.Unsupported unsupported) {
          // This corpus also exercises deliberate full-path fallbacks.
        }
      }
    }
    org.junit.Assert.assertEquals(reference.key(), actual.key().toString());
    org.junit.Assert.assertEquals(reference.complete(), actual.complete());
    org.junit.Assert.assertEquals(reference.bindings(), actual.bindings());
    org.junit.Assert.assertEquals(reference.hasContributions(), actual.hasContributions());
    org.junit.Assert.assertEquals(reference.nativeOperands(), actual.nativeOperands());
  }
}
