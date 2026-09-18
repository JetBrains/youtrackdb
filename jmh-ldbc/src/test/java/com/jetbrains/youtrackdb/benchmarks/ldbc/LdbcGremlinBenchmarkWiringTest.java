package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.Date;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;

/** Verifies that each Interactive Complex 2 benchmark identity selects its intended workload. */
public class LdbcGremlinBenchmarkWiringTest {

  private static final Date CURATED_DATE = new Date(1000);
  private static final Date LIVE_DATE = new Date(2000);

  /** The legacy benchmark retains its measured method identity. */
  @Test
  public void legacyBenchmarkRetainsIdentity() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrdered");
  }

  /** The ordered-limit benchmark retains its measured method identity. */
  @Test
  public void orderedLimitBenchmarkRetainsIdentity() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrderedLimit");
  }

  /** The legacy workload reads the curated parameter source from shared benchmark state. */
  @Test
  public void legacyWorkloadUsesCuratedParameters() {
    var state = new StubState();

    assertEquals(11, LdbcGremlinTranslatorBenchmark.LegacyIc2Workload.personId(state, 7));
    assertSame(
        CURATED_DATE, LdbcGremlinTranslatorBenchmark.LegacyIc2Workload.maxDate(state, 7));
  }

  /** The ordered-limit workload reads the live parameter source from translator-arm state. */
  @Test
  public void orderedLimitWorkloadUsesLiveParameters() {
    var arm = new StubArm();

    assertEquals(22, LdbcGremlinTranslatorBenchmark.OrderedLimitIc2Workload.personId(arm, 7));
    assertSame(
        LIVE_DATE, LdbcGremlinTranslatorBenchmark.OrderedLimitIc2Workload.maxDate(arm, 7));
  }

  private static void assertBenchmarkAnnotation(String methodName) throws NoSuchMethodException {
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod(
        methodName, LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class);
    assertNotNull(method.getAnnotation(Benchmark.class));
  }

  private static final class StubState extends LdbcBenchmarkState {

    @Override
    public long ic2PersonId(long index) {
      return 11;
    }

    @Override
    public Date ic2MaxDate(long index) {
      return CURATED_DATE;
    }
  }

  private static final class StubArm extends LdbcGremlinTranslatorBenchmark.TranslatorArm {

    @Override
    long ic2PersonId(long index) {
      return 22;
    }

    @Override
    Date ic2MaxDate(long index) {
      return LIVE_DATE;
    }
  }
}
