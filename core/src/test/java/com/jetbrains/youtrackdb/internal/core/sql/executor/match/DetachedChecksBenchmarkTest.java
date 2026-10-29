package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** The same trial setup guards every JMH case against a wrong path or a weakened fixture. */
@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class DetachedChecksBenchmarkTest {

  @Parameterized.Parameters(name = "{0}/{1}")
  public static Collection<Object[]> cases() throws Exception {
    var cases = new ArrayList<Object[]>();
    for (var kind : DetachedChecksBenchmark.class.getField("kind")
        .getAnnotation(org.openjdk.jmh.annotations.Param.class).value()) {
      for (var scenario : DetachedChecksBenchmark.class.getField("scenario")
          .getAnnotation(org.openjdk.jmh.annotations.Param.class).value()) {
        cases.add(new Object[] {kind, scenario});
      }
    }
    return cases;
  }

  private final String kind;
  private final String scenario;

  public DetachedChecksBenchmarkTest(String kind, String scenario) {
    this.kind = kind;
    this.scenario = scenario;
  }

  /** Each fixture must produce its exact multiset, engage the expected engine and remain reusable. */
  @Test
  public void fixtureHasExpectedRowsRepetitionTranslationAndExecutedPath() throws Exception {
    // The category isolates global planner settings from other classes. The lock also serializes
    // parameter instances when a runner enables parallel methods, since they share a database path.
    synchronized (DetachedChecksBenchmarkTest.class) {
      var benchmark = new DetachedChecksBenchmark();
      benchmark.kind = kind;
      benchmark.scenario = scenario;
      try {
        benchmark.setup();
        benchmark.verifyExecutionChecksum(benchmark.execute());
        benchmark.verifyExecutionChecksum(benchmark.execute());
      } finally {
        benchmark.teardown();
      }
    }
  }
}
