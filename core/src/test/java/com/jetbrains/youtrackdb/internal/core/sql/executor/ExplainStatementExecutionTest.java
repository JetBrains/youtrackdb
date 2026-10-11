package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of EXPLAIN SQL statements.
 */
public class ExplainStatementExecutionTest extends DbTestBase {

  /** Subquery sources keep an empty subSteps list in the structured EXPLAIN document. */
  @Test
  public void testExplainSubquerySourceHasEmptySubSteps() {
    assertExplainSourceHasEmptySubSteps("SELECT FROM (SELECT 1 AS value)", SubQueryStep.class);
  }

  /** Disconnected MATCH patterns keep Cartesian children out of structured EXPLAIN subSteps. */
  @Test
  public void testExplainCartesianProductHasEmptySubSteps() {
    session.createVertexClass("ExplainLeft");
    session.createVertexClass("ExplainRight");
    assertExplainSourceHasEmptySubSteps(
        "MATCH {class: ExplainLeft, as: a}, {class: ExplainRight, as: b} RETURN a, b",
        CartesianProductStep.class);
  }

  private void assertExplainSourceHasEmptySubSteps(String sql,
      Class<? extends ExecutionStepInternal> sourceType) {
    try (var result = session.query("EXPLAIN " + sql)) {
      assertThat(result.hasNext()).isTrue();
      Result document = result.next().getProperty("executionPlan");
      List<Result> steps = document.getProperty("steps");
      var sources = steps.stream()
          .filter(step -> sourceType.getName()
              .equals(step.getProperty(InternalExecutionPlan.JAVA_TYPE)))
          .toList();
      assertThat(sources).hasSize(1);
      assertThat(sources.getFirst().<List<Result>>getProperty("subSteps")).isEmpty();
      var source = result.getExecutionPlan().getSteps().stream()
          .filter(sourceType::isInstance).findFirst().orElseThrow();
      assertThat(source.getSubSteps()).isEmpty();
      assertThat(source.toResult(session).<List<Result>>getProperty("subSteps")).isEmpty();
      assertThat(result.hasNext()).isFalse();
    }
  }

  /** EXPLAIN exposes both plan formats for a SELECT that has no FROM target. */
  @Test
  public void testExplainSelectNoTarget() {
    var result = session.query("explain select 1 as one, 2 as two, 2+3");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next.getProperty("executionPlan"));
    Assert.assertNotNull(next.getProperty("executionPlanAsString"));

    var plan = result.getExecutionPlan();
    Assert.assertNotNull(plan);
    Assert.assertTrue(plan instanceof SelectExecutionPlan);

    result.close();
  }
}
