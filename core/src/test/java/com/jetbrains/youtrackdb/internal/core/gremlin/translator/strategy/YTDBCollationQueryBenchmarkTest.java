package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBCollationQueryBenchmark;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.openjdk.jmh.infra.Blackhole;

/** Checks the benchmark preflight and its calling-thread MATCH scope without running JMH timing. */
@Category(SequentialTest.class)
public class YTDBCollationQueryBenchmarkTest {

  /**
   * Both global switch defaults allow all native and MATCH witnesses to pass. Timed entry points
   * also execute on the caller, and the translated control restores the thread-local override.
   */
  @Test
  public void preflightAndMeasurementsKeepTheirEnginesWithoutLeakingTheOverride() throws Exception {
    var configuration = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED;
    var previous = configuration.getValueAsBoolean();
    var blackhole = new Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
    try {
      for (var enabled : new boolean[] {true, false}) {
        configuration.setValue(enabled);
        var benchmark = new YTDBCollationQueryBenchmark();
        try {
          benchmark.setUp();
          assertThat(GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.get()).isNull();
          benchmark.defaultScalarFallback(blackhole);
          benchmark.undeclaredScalarFallback(blackhole);
          benchmark.collatedScalarNativePostHop(blackhole);
          benchmark.collatedScalarNativeById(blackhole);
          benchmark.cachedMembershipEarlyMatch(blackhole);
          benchmark.cachedMembershipNoMatch(blackhole);
          benchmark.parameterizedMembershipEarlyMatch(blackhole);
          benchmark.parameterizedMembershipNoMatch(blackhole);
          benchmark.listEqualityEarlyMismatch(blackhole);
          benchmark.listEqualityFullEqual(blackhole);
          // Repeated controls cover both cold and warm strategy application on the worker thread.
          benchmark.translatedScalarControl(blackhole);
          benchmark.translatedScalarControl(blackhole);
          assertThat(GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.get()).isNull();
        } finally {
          benchmark.tearDown();
        }
      }
    } finally {
      configuration.setValue(previous);
    }
  }
}
