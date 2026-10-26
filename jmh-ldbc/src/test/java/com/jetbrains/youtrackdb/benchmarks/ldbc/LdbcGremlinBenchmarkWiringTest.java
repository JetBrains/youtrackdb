package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.Arrays;
import java.util.Date;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;

/** Verifies workload selection for the IC2 and pre-valueMap bulking benchmark identities. */
public class LdbcGremlinBenchmarkWiringTest {

  private static final Date CURATED_DATE = new Date(1000);
  private static final Date LIVE_DATE = new Date(2000);

  /** The actual legacy benchmark completes and reads only curated state. */
  @Test
  public void legacyBenchmarkInvocationUsesCuratedState() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrdered");
    var state = new StubState();
    var arm = new StubArm();

    var rows = new LdbcGremlinTranslatorBenchmark()
        .gremlin_ic2_friendsMessagesOrdered(state, arm);

    assertTrue(rows.isEmpty());
    assertTrue(state.personIdRead);
    assertTrue(state.maxDateRead);
    assertFalse(arm.personIdRead);
    assertFalse(arm.maxDateRead);
  }

  /** The actual ordered-limit benchmark completes and reads only live arm state. */
  @Test
  public void orderedLimitBenchmarkInvocationUsesLiveArm() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrderedLimit");
    var state = new StubState();
    var arm = new StubArm();

    var rows = new LdbcGremlinTranslatorBenchmark()
        .gremlin_ic2_friendsMessagesOrderedLimit(state, arm);

    assertTrue(rows.isEmpty());
    assertFalse(state.personIdRead);
    assertFalse(state.maxDateRead);
    assertTrue(arm.personIdRead);
    assertTrue(arm.maxDateRead);
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

  /** JMH exposes all four forms and the real benchmark consumes its checked feed on either arm. */
  @Test
  public void bulkingBenchmarkUsesAllDeclaredFormsAndCheckedIds() throws Exception {
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod("gremlin_bulkingValueMap",
        LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class,
        LdbcGremlinTranslatorBenchmark.BulkingParameters.class);
    assertNotNull(method.getAnnotation(Benchmark.class));
    var forms = LdbcGremlinTranslatorBenchmark.BulkingParameters.class.getField("form")
        .getAnnotation(Param.class).value();
    assertEquals(
        Arrays.stream(GremlinTraversalShapes.BulkingForm.values()).map(Enum::name).toList(),
        Arrays.asList(forms));
    for (var form : GremlinTraversalShapes.BulkingForm.values()) {
      var parameters = new LdbcGremlinTranslatorBenchmark.BulkingParameters() {
        @Override
        long id(long index) {
          return 22;
        }
      };
      parameters.form = form;
      for (boolean enabled : new boolean[] {true, false}) {
        var arm = new StubArm();
        arm.translatorEnabled = enabled;
        assertTrue(new LdbcGremlinTranslatorBenchmark()
            .gremlin_bulkingValueMap(new StubState(), arm, parameters).isEmpty());
      }
    }
  }

  /** Every form rejects an empty feed. Duplicate, singleton, and unique cases enforce their names. */
  @Test
  public void bulkingCountsRejectInvalidParametersWithCountsInTheMessage() {
    for (var form : GremlinTraversalShapes.BulkingForm.values()) {
      assertTrue(assertThrows(IllegalStateException.class,
          () -> form.requireCounts(42, 0, 0)).getMessage().contains("parameter 42"));
    }
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.DUPLICATE.requireCounts(42, 2, 2));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.ONE_ROW.requireCounts(42, 2, 1));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.UNIQUE.requireCounts(42, 2, 1));
  }

  /** Null and empty parameter pools fail before a transaction or timed invocation can start. */
  @Test
  public void bulkingTrialRejectsUninitializedAndEmptyPools() {
    for (long[] ids : new long[][] {null, new long[0]}) {
      var arm = new LdbcGremlinTranslatorBenchmark.TranslatorArm() {
        @Override
        long[] bulkingIds(GremlinTraversalShapes.BulkingForm form) {
          return ids;
        }
      };
      var parameters = new LdbcGremlinTranslatorBenchmark.BulkingParameters();
      parameters.form = GremlinTraversalShapes.BulkingForm.DUPLICATE;
      assertTrue(assertThrows(IllegalStateException.class,
          () -> parameters.setUp(new StubState(), arm)).getMessage()
          .contains("empty parameter feed"));
    }
  }

  private static void assertBenchmarkAnnotation(String methodName) throws NoSuchMethodException {
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod(
        methodName, LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class);
    assertNotNull(method.getAnnotation(Benchmark.class));
  }

  private static final class CallbackTraversalSource extends YTDBGraphTraversalSource {

    private CallbackTraversalSource() {
      super(EmptyGraph.instance());
    }

    @Override
    public <X extends Exception, R> R computeInTx(
        FailableFunction<YTDBGraphTraversalSource, R, X> code) throws X {
      return code.apply(this);
    }
  }

  private static final class StubState extends LdbcBenchmarkState {

    private boolean personIdRead;
    private boolean maxDateRead;

    private StubState() {
      traversal = new CallbackTraversalSource();
    }

    @Override
    public long ic2PersonId(long index) {
      personIdRead = true;
      return 11;
    }

    @Override
    public Date ic2MaxDate(long index) {
      maxDateRead = true;
      return CURATED_DATE;
    }
  }

  private static final class StubArm extends LdbcGremlinTranslatorBenchmark.TranslatorArm {

    private boolean personIdRead;
    private boolean maxDateRead;

    @Override
    long ic2PersonId(long index) {
      personIdRead = true;
      return 22;
    }

    @Override
    Date ic2MaxDate(long index) {
      maxDateRead = true;
      return LIVE_DATE;
    }
  }
}
