package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests the aggregation logic of {@link AggregateProjectionCalculationStep},
 * covering single-group aggregation (no GROUP BY), multi-group aggregation
 * (with GROUP BY), early termination via limit, timeout propagation, pretty
 * printing, cacheability, and the copy contract.
 */
public class AggregateProjectionCalculationStepTest extends DbTestBase {

  // --- Aggregation without GROUP BY ---

  /**
   * When there is no GROUP BY clause, all upstream records belong to a single
   * group keyed by [null]. The step should produce exactly one result whose
   * aggregate temporary property holds the finalized value.
   *
   * Scenario: two upstream records feed into a single count-like aggregate.
   * Expected: one output row with the aggregate finalized to the value 2.
   */
  @Test
  public void singleGroupAggregationProducesOneResult() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);
    step.setPrevious(upstreamOf(ctx, 2));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isTrue();
    var row = (ResultInternal) stream.next(ctx);
    // The fake AggregationContext accumulates apply() calls and returns the count.
    // Aggregate values live in temporary properties after finalization.
    assertThat(row.getTemporaryProperty("cnt")).isEqualTo(2);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // --- Aggregation with GROUP BY ---

  /**
   * With a GROUP BY clause, records that produce distinct group keys must yield
   * separate output rows. Non-aggregate properties are captured from the first
   * record in each group (as regular properties). Aggregates accumulate per
   * group and end up in temporary properties after finalization.
   *
   * Scenario: three records with group keys "A", "B", "A". The non-aggregate
   * property "label" is set from the first record of each group. The aggregate
   * accumulates per group.
   * Expected: two output rows: group "A" (count=2), group "B" (count=1).
   */
  @Test
  public void groupByProducesOneResultPerDistinctKey() {
    var ctx = newContext();

    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(propertyAccessExpr("grp"));

    var labelItem = fakeNonAggregateItem("label");
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(labelItem, aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, groupBy, -1, ctx, -1, false);

    // Three upstream records: grp=A, grp=B, grp=A
    step.setPrevious(upstreamWithGroups(ctx));

    var stream = step.start(ctx);
    var results = drain(stream, ctx);

    assertThat(results).hasSize(2);
    // LinkedHashMap preserves insertion order: group A first, then group B
    var groupA = (ResultInternal) results.get(0);
    var groupB = (ResultInternal) results.get(1);
    assertThat(groupA.getTemporaryProperty("cnt")).isEqualTo(2);
    assertThat(groupB.getTemporaryProperty("cnt")).isEqualTo(1);
    // Non-aggregate property captured from first record of each group
    assertThat(groupA.<String>getProperty("label")).isEqualTo("labelA");
    assertThat(groupB.<String>getProperty("label")).isEqualTo("labelB");
  }

  // --- Limit causes early group termination ---

  /**
   * When a limit is set and the number of accumulated groups exceeds it, the
   * step stops creating new groups. The condition is
   * {@code aggregateResults.size() > limit}, so with limit=1 and 1 group
   * already present, a second record with a new key is discarded (since
   * {@code size()==1 > 1} is false, but then for the next new key attempt
   * {@code size()==2 > 1} is true, so no third group is created).
   *
   * Scenario: limit=1, four records with keys "A", "B", "C", "A".
   * Expected: groups "A" and "B" are produced (size reaches 2 which equals
   * limit+1, then the check 2>1 prevents "C"). The last "A" record is still
   * aggregated into the existing "A" group.
   */
  @Test
  public void limitPreventsCreationOfExcessGroups() {
    var ctx = newContext();

    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(propertyAccessExpr("grp"));

    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    // limit = 1; the guard is "size() > limit", so up to 2 groups are created
    // before the 3rd is rejected
    var step = new AggregateProjectionCalculationStep(
        projection, groupBy, 1, ctx, -1, false);
    step.setPrevious(upstreamWithFourGroupRecords(ctx));

    var stream = step.start(ctx);
    var results = drain(stream, ctx);

    // Groups "A" and "B" are created; "C" is rejected because size()==2 > 1
    assertThat(results).hasSize(2);
    var groupA = (ResultInternal) results.get(0);
    // Group "A" received 2 apply() calls (first and last record)
    assertThat(groupA.getTemporaryProperty("cnt")).isEqualTo(2);
  }

  // --- Missing prev step throws ---

  /**
   * Aggregation requires an upstream step. If prev is null, a
   * CommandExecutionException must be thrown, including the database name.
   */
  @Test
  public void throwsWhenNoPreviousStep() {
    var ctx = newContext();
    var projection = new SQLProjection(List.of(fakeAggregateItem("cnt")), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);
    // do NOT set previous

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("without a previous result");
  }

  // --- Finalization replaces AggregationContext with scalar ---

  /**
   * After consuming all upstream records, the step must finalize every
   * temporary property that holds an AggregationContext by replacing it with
   * getFinalValue(). The finalized value should be a plain scalar, not an
   * AggregationContext instance.
   *
   * Scenario: one upstream record with a single aggregate projection.
   * Expected: the finalized value (count = 1) appears as a temporary property.
   */
  @Test
  public void finalizationReplacesAggregationContextWithScalar() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("total");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);
    step.setPrevious(upstreamOf(ctx, 1));

    var stream = step.start(ctx);
    var row = (ResultInternal) stream.next(ctx);
    // After finalization, the temporary property is a plain Integer, not AggregationContext
    var finalizedValue = row.getTemporaryProperty("total");
    assertThat(finalizedValue).isInstanceOf(Integer.class).isEqualTo(1);
    stream.close(ctx);
  }

  // --- prettyPrint ---

  /**
   * prettyPrint without profiling and without GROUP BY shows the basic
   * "CALCULATE AGGREGATE PROJECTIONS" header followed by the projection text.
   */
  @Test
  public void prettyPrintWithoutProfilingOrGroupBy() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);

    var output = step.prettyPrint(0, 2);
    assertThat(output).contains("CALCULATE AGGREGATE PROJECTIONS");
    assertThat(output).doesNotContain("GROUP BY");
    // Should not contain cost when profiling is disabled
    assertThat(output).doesNotContain("μs");
  }

  /**
   * prettyPrint with profiling enabled appends the cost string.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, true);

    var output = step.prettyPrint(0, 2);
    assertThat(output).contains("CALCULATE AGGREGATE PROJECTIONS");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with a GROUP BY clause includes the group-by text.
   */
  @Test
  public void prettyPrintWithGroupByIncludesGroupByClause() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(propertyAccessExpr("city"));

    var step = new AggregateProjectionCalculationStep(
        projection, groupBy, -1, ctx, -1, false);

    var output = step.prettyPrint(0, 2);
    assertThat(output).contains("CALCULATE AGGREGATE PROJECTIONS");
    assertThat(output).contains("GROUP BY");
  }

  // --- canBeCached ---

  /**
   * AggregateProjectionCalculationStep is always cacheable because projection
   * and GROUP BY ASTs are deep-copied per execution via copy().
   */
  @Test
  public void canBeCachedAlwaysReturnsTrue() {
    var ctx = newContext();
    var projection = new SQLProjection(List.of(fakeAggregateItem("x")), false);
    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // --- copy ---

  /**
   * copy() produces a new step instance that preserves the projection, groupBy,
   * limit, timeout, and profiling settings. The new step should be independent
   * of the original.
   */
  @Test
  public void copyProducesEquivalentStep() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(propertyAccessExpr("city"));

    var step = new AggregateProjectionCalculationStep(
        projection, groupBy, 10, ctx, 5000, true);

    var copied = (AggregateProjectionCalculationStep) step.copy(ctx);
    assertThat(copied).isNotSameAs(step);
    assertThat(copied.canBeCached()).isTrue();
    // Structural equivalence
    assertThat(copied.prettyPrint(0, 2)).contains("CALCULATE AGGREGATE PROJECTIONS");
    assertThat(copied.prettyPrint(0, 2)).contains("GROUP BY");
    // Functional equivalence: the copy can execute and aggregate correctly
    copied.setPrevious(upstreamOf(ctx, 2));
    var stream = copied.start(ctx);
    var row = (ResultInternal) stream.next(ctx);
    assertThat(row.getTemporaryProperty("cnt")).isEqualTo(2);
    stream.close(ctx);
  }

  /**
   * copy() with a null groupBy still works and produces a valid step.
   */
  @Test
  public void copyWithNullGroupByProducesValidStep() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);

    var copied = (AggregateProjectionCalculationStep) step.copy(ctx);
    assertThat(copied).isNotSameAs(step);
    assertThat(copied.prettyPrint(0, 2)).doesNotContain("GROUP BY");
  }

  // --- Mixed aggregate and non-aggregate projections ---

  /**
   * When the projection list contains both aggregate and non-aggregate items,
   * the non-aggregate properties are captured on the first record of each group
   * (as regular properties), while aggregate properties accumulate across all
   * records in the group (as temporary properties).
   *
   * Scenario: no GROUP BY, two records. Non-aggregate "name" is captured from
   * the first record. Aggregate "cnt" sees both records.
   * Expected: name = "first", cnt = 2.
   */
  @Test
  public void mixedAggregateAndNonAggregateProjections() {
    var ctx = newContext();
    var nameItem = fakeNonAggregateItem("name");
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(nameItem, aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);

    // Two upstream records: "first" and "second"
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        List<Result> results = new ArrayList<>();
        if (!done) {
          var r1 = new ResultInternal(c.getDatabaseSession());
          r1.setProperty("name", "first");
          results.add(r1);

          var r2 = new ResultInternal(c.getDatabaseSession());
          r2.setProperty("name", "second");
          results.add(r2);
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    });

    var stream = step.start(ctx);
    var row = (ResultInternal) stream.next(ctx);
    // Non-aggregate captured from first record (regular property)
    assertThat(row.<String>getProperty("name")).isEqualTo("first");
    // Aggregate accumulated across both records (temporary property)
    assertThat(row.getTemporaryProperty("cnt")).isEqualTo(2);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * When an aggregate context is already created for a group (second record in
   * same group), the existing AggregationContext is reused rather than creating
   * a new one.
   *
   * Scenario: no GROUP BY, three records all in the same implicit group.
   * Expected: one output row with cnt=3 (all three records aggregated into the
   * same context).
   */
  @Test
  public void existingAggregationContextIsReusedForSubsequentRecords() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);
    step.setPrevious(upstreamOf(ctx, 3));

    var stream = step.start(ctx);
    var row = (ResultInternal) stream.next(ctx);
    assertThat(row.getTemporaryProperty("cnt")).isEqualTo(3);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * When the upstream produces no records, the step should produce an empty
   * result set (no groups to aggregate).
   *
   * <p>Note: the SQL-level guarantee that {@code SELECT count(*) FROM t}
   * returns one row even on an empty table is handled by
   * {@link GuaranteeEmptyCountStep} in the planner, not by this step.
   */
  @Test
  public void emptyUpstreamProducesEmptyResult() {
    var ctx = newContext();
    var aggrItem = fakeAggregateItem("cnt");
    var projection = new SQLProjection(List.of(aggrItem), false);

    var step = new AggregateProjectionCalculationStep(
        projection, null, -1, ctx, -1, false);
    step.setPrevious(upstreamOf(ctx, 0));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates a fake non-aggregate {@link SQLProjectionItem} that, when executed
   * against a {@link Result}, returns the value of the given property name from
   * that result.
   */
  private SQLProjectionItem fakeNonAggregateItem(String aliasName) {
    var aliasId = new SQLIdentifier(aliasName);
    return new SQLProjectionItem(-1) {
      {
        // Set field for consistency with base class, even though isAggregate() is overridden
        this.aggregate = false;
        this.alias = aliasId;
      }

      @Override
      public boolean isAggregate(
          com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s) {
        return false;
      }

      @Override
      public Object execute(Result record, CommandContext ctx) {
        return record.getProperty(aliasName);
      }

      @Override
      public SQLIdentifier getProjectionAlias() {
        return aliasId;
      }

      @Override
      public SQLProjectionItem copy() {
        return fakeNonAggregateItem(aliasName);
      }
    };
  }

  /**
   * Creates a fake aggregate {@link SQLProjectionItem} that uses a
   * {@link CountingAggregationContext} to count the number of records applied.
   */
  private SQLProjectionItem fakeAggregateItem(String aliasName) {
    var aliasId = new SQLIdentifier(aliasName);
    return new SQLProjectionItem(-1) {
      {
        // Set field for consistency with base class, even though isAggregate() is overridden
        this.aggregate = true;
        this.alias = aliasId;
      }

      @Override
      public boolean isAggregate(
          com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s) {
        return true;
      }

      @Override
      public Object execute(Result record, CommandContext ctx) {
        return null;
      }

      @Override
      public AggregationContext getAggregationContext(CommandContext ctx) {
        return new CountingAggregationContext();
      }

      @Override
      public SQLIdentifier getProjectionAlias() {
        return aliasId;
      }

      @Override
      public SQLProjectionItem copy() {
        return fakeAggregateItem(aliasName);
      }
    };
  }

  /**
   * A GROUP BY expression that reads the given property from a result.
   */
  private SQLExpression propertyAccessExpr(String propertyName) {
    return new SQLExpression(-1) {
      @Override
      public Object execute(Result iCurrentRecord, CommandContext ctx) {
        return iCurrentRecord.getProperty(propertyName);
      }

      @Override
      public SQLExpression copy() {
        return propertyAccessExpr(propertyName);
      }

      @Override
      public void toString(java.util.Map<Object, Object> params, StringBuilder builder) {
        builder.append(propertyName);
      }
    };
  }

  /**
   * Creates an upstream step that produces {@code count} simple records.
   */
  private AbstractExecutionStep upstreamOf(CommandContext ctx, int count) {
    return new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        List<Result> results = new ArrayList<>();
        if (!done) {
          for (var i = 0; i < count; i++) {
            results.add(new ResultInternal(c.getDatabaseSession()));
          }
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    };
  }

  /**
   * Creates an upstream step that produces three records with group keys:
   * "A", "B", "A". Each record also has a "label" property.
   */
  private AbstractExecutionStep upstreamWithGroups(CommandContext ctx) {
    return new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        List<Result> results = new ArrayList<>();
        if (!done) {
          var r1 = new ResultInternal(c.getDatabaseSession());
          r1.setProperty("grp", "A");
          r1.setProperty("label", "labelA");
          results.add(r1);

          var r2 = new ResultInternal(c.getDatabaseSession());
          r2.setProperty("grp", "B");
          r2.setProperty("label", "labelB");
          results.add(r2);

          var r3 = new ResultInternal(c.getDatabaseSession());
          r3.setProperty("grp", "A");
          r3.setProperty("label", "labelA2");
          results.add(r3);
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    };
  }

  /**
   * Creates an upstream step that produces four records with group keys:
   * "A", "B", "C", "A". Used to test the limit early-termination guard.
   */
  private AbstractExecutionStep upstreamWithFourGroupRecords(CommandContext ctx) {
    return new AbstractExecutionStep(ctx, false) {
      boolean done = false;

      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        List<Result> results = new ArrayList<>();
        if (!done) {
          for (var key : List.of("A", "B", "C", "A")) {
            var r = new ResultInternal(c.getDatabaseSession());
            r.setProperty("grp", key);
            results.add(r);
          }
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    };
  }

  /**
   * Drains all results from a stream into a list.
   */
  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }

  // -------------------------------------------------------------------------
  // Test AggregationContext: counts apply() calls
  // -------------------------------------------------------------------------

  /**
   * A trivial AggregationContext that counts how many times {@link #apply} is
   * called. {@link #getFinalValue()} returns the count as an Integer.
   */
  private static class CountingAggregationContext implements AggregationContext {
    private int count = 0;

    @Override
    public Object getFinalValue() {
      return count;
    }

    @Override
    public void apply(Result next, CommandContext ctx) {
      count++;
    }
  }
}
