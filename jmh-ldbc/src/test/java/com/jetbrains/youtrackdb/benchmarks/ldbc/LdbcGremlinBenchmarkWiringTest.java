package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** Verifies that each Interactive Complex 2 benchmark identity selects its intended workload. */
public class LdbcGremlinBenchmarkWiringTest {

  private static final Date CURATED_DATE = new Date(1000);
  private static final Date LIVE_DATE = new Date(2000);

  /** The legacy benchmark keeps its annotation and delegates to the curated workload. */
  @Test
  public void legacyBenchmarkUsesLegacyWorkload() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrdered");
    var benchmark = new CapturingBenchmark();

    benchmark.gremlin_ic2_friendsMessagesOrdered(null, null);

    assertSame(LdbcGremlinTranslatorBenchmark.LEGACY_IC2, benchmark.legacyWorkload);
  }

  /** The ordered-limit benchmark keeps its annotation and delegates to the live workload. */
  @Test
  public void orderedLimitBenchmarkUsesOrderedLimitWorkload() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrderedLimit");
    var benchmark = new CapturingBenchmark();

    benchmark.gremlin_ic2_friendsMessagesOrderedLimit(null, null);

    assertSame(
        LdbcGremlinTranslatorBenchmark.ORDERED_LIMIT_IC2, benchmark.orderedLimitWorkload);
  }

  /** The legacy workload reads the curated parameter source from shared benchmark state. */
  @Test
  public void legacyWorkloadUsesCuratedParameters() {
    var parameters = LdbcGremlinTranslatorBenchmark.LEGACY_IC2.parameters(
        new StubState(), new StubArm(), 7);

    assertEquals(new LdbcGremlinTranslatorBenchmark.Ic2Parameters(11, CURATED_DATE), parameters);
  }

  /** The ordered-limit workload reads the live parameter source from translator-arm state. */
  @Test
  public void orderedLimitWorkloadUsesLiveParameters() {
    var parameters = LdbcGremlinTranslatorBenchmark.ORDERED_LIMIT_IC2.parameters(
        new StubState(), new StubArm(), 7);

    assertEquals(new LdbcGremlinTranslatorBenchmark.Ic2Parameters(22, LIVE_DATE), parameters);
  }

  private static void assertBenchmarkAnnotation(String methodName) throws NoSuchMethodException {
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod(
        methodName, LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class);
    assertNotNull(method.getAnnotation(Benchmark.class));
  }

  @State(Scope.Thread)
  public static class CapturingBenchmark extends LdbcGremlinTranslatorBenchmark {

    private LegacyIc2Workload legacyWorkload;
    private OrderedLimitIc2Workload orderedLimitWorkload;

    @Override
    List<Map<Object, Object>> runLegacyIc2(
        LdbcBenchmarkState state, TranslatorArm arm, LegacyIc2Workload workload) {
      legacyWorkload = workload;
      return List.of();
    }

    @Override
    List<Map<String, Object>> runOrderedLimitIc2(
        LdbcBenchmarkState state, TranslatorArm arm, OrderedLimitIc2Workload workload) {
      orderedLimitWorkload = workload;
      return List.of();
    }
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
