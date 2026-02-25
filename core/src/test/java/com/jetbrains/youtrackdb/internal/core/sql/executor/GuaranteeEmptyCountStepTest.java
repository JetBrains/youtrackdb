package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests the guarantee-empty-count logic of {@link GuaranteeEmptyCountStep},
 * which ensures that bare {@code SELECT count(*) FROM ...} queries (without
 * GROUP BY) return a single row with 0 even when the upstream is empty.
 *
 * <p>Covers: empty upstream (synthetic zero row), non-empty upstream
 * (pass-through with limit 1), missing previous step error, copy contract,
 * cacheability, and pretty printing.
 */
public class GuaranteeEmptyCountStepTest extends DbTestBase {

  // --- Core semantics: empty upstream produces synthetic zero-count row ---

  /**
   * When the upstream produces no records, the step must emit a single
   * synthetic result with the projection alias mapped to 0L. This ensures
   * the SQL semantics where {@code count(*)} over an empty set returns 0,
   * not zero rows.
   *
   * Scenario: upstream is empty.
   * Expected: one result row with property "cnt" = 0L.
   */
  @Test
  public void emptyUpstreamProducesSyntheticZeroCountRow() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var step = new GuaranteeEmptyCountStep(item, ctx, false);
    step.setPrevious(upstreamOf(ctx, 0));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isTrue();
    var row = stream.next(ctx);
    // SQL semantics: count(*) over empty set must be 0L (Long), not null
    assertThat(row.<Long>getProperty("cnt")).isEqualTo(0L);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * The synthetic zero-count result must use the projection alias, not a
   * hardcoded name. This verifies that different alias names are correctly
   * propagated.
   *
   * Scenario: upstream is empty, alias is "total".
   * Expected: result property "total" = 0L.
   */
  @Test
  public void syntheticZeroCountUsesProjectionAlias() {
    var ctx = newContext();
    var item = fakeProjectionItem("total");

    var step = new GuaranteeEmptyCountStep(item, ctx, false);
    step.setPrevious(upstreamOf(ctx, 0));

    var stream = step.start(ctx);
    var row = stream.next(ctx);
    assertThat(row.<Long>getProperty("total")).isEqualTo(0L);
    // The property should NOT exist under a different name
    assertThat(row.getPropertyNames()).containsExactly("total");
    stream.close(ctx);
  }

  // --- Core semantics: non-empty upstream passes through limited to 1 ---

  /**
   * When the upstream has at least one record, the step passes it through.
   * Since {@code count(*)} without GROUP BY always yields a single row,
   * the step limits the output to 1.
   *
   * Scenario: upstream produces one record with property "cnt" = 42L.
   * Expected: that record is passed through unchanged.
   */
  @Test
  public void nonEmptyUpstreamPassesThroughFirstResult() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var step = new GuaranteeEmptyCountStep(item, ctx, false);
    step.setPrevious(upstreamWithValues(ctx, List.of(42L)));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isTrue();
    var row = stream.next(ctx);
    assertThat(row.<Long>getProperty("cnt")).isEqualTo(42L);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * When the upstream has multiple records, only the first is emitted due
   * to the limit(1) call. This verifies that the step correctly constrains
   * output to a single row even if the upstream erroneously produces more.
   *
   * Scenario: upstream produces three records with values 10L, 20L, 30L.
   * Expected: only the first record (10L) is emitted.
   */
  @Test
  public void nonEmptyUpstreamLimitsToOneResult() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var step = new GuaranteeEmptyCountStep(item, ctx, false);
    step.setPrevious(upstreamWithValues(ctx, List.of(10L, 20L, 30L)));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isTrue();
    var row = stream.next(ctx);
    assertThat(row.<Long>getProperty("cnt")).isEqualTo(10L);
    // limit(1) ensures no more results
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // --- Error handling: missing previous step ---

  /**
   * The step requires an upstream source. If no previous step is set,
   * it must throw an IllegalStateException with a descriptive message.
   */
  @Test
  public void throwsWhenNoPreviousStep() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var step = new GuaranteeEmptyCountStep(item, ctx, false);
    // Do NOT set previous step

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires a previous step");
  }

  // --- copy() contract ---

  /**
   * copy() must produce a new, independent step instance that preserves
   * the projection item and profiling settings. The copied step should
   * function identically when given the same upstream.
   *
   * Scenario: copy the step, connect to an empty upstream.
   * Expected: the copy produces the same synthetic zero-count row.
   */
  @Test
  public void copyProducesEquivalentStep() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var original = new GuaranteeEmptyCountStep(item, ctx, false);
    var copied = (GuaranteeEmptyCountStep) original.copy(ctx);

    assertThat(copied).isNotSameAs(original);

    // Verify the copy functions correctly with an empty upstream
    copied.setPrevious(upstreamOf(ctx, 0));
    var stream = copied.start(ctx);
    var row = stream.next(ctx);
    assertThat(row.<Long>getProperty("cnt")).isEqualTo(0L);
    stream.close(ctx);
  }

  /**
   * The copy must also work correctly with a non-empty upstream, proving
   * the pass-through path is preserved.
   */
  @Test
  public void copyWorksWithNonEmptyUpstream() {
    var ctx = newContext();
    var item = fakeProjectionItem("cnt");

    var original = new GuaranteeEmptyCountStep(item, ctx, true);
    var copied = (GuaranteeEmptyCountStep) original.copy(ctx);
    // Verify that profiling flag is preserved through copy
    assertThat(copied.isProfilingEnabled()).isTrue();

    copied.setPrevious(upstreamWithValues(ctx, List.of(99L)));
    var stream = copied.start(ctx);
    var row = stream.next(ctx);
    assertThat(row.<Long>getProperty("cnt")).isEqualTo(99L);
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  // --- canBeCached() ---

  /**
   * GuaranteeEmptyCountStep is always cacheable because the projection
   * item is a structural AST node that is deep-copied per execution.
   */
  @Test
  public void canBeCachedAlwaysReturnsTrue() {
    var ctx = newContext();
    var step = new GuaranteeEmptyCountStep(fakeProjectionItem("x"), ctx, false);
    assertThat(step.canBeCached()).isTrue();
  }

  // --- prettyPrint() ---

  /**
   * prettyPrint should contain the "GUARANTEE FOR ZERO COUNT" marker text
   * that identifies this step in query execution plans.
   */
  @Test
  public void prettyPrintContainsStepIdentifier() {
    var ctx = newContext();
    var step = new GuaranteeEmptyCountStep(fakeProjectionItem("cnt"), ctx, false);

    var output = step.prettyPrint(0, 2);
    assertThat(output).contains("GUARANTEE FOR ZERO COUNT");
  }

  /**
   * prettyPrint at a deeper depth should include indentation.
   */
  @Test
  public void prettyPrintRespectsDepthIndentation() {
    var ctx = newContext();
    var step = new GuaranteeEmptyCountStep(fakeProjectionItem("cnt"), ctx, false);

    var depth0 = step.prettyPrint(0, 2);
    var depth2 = step.prettyPrint(2, 2);
    // Deeper depth means more leading whitespace
    assertThat(depth2.length()).isGreaterThan(depth0.length());
    assertThat(depth2).contains("GUARANTEE FOR ZERO COUNT");
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
   * Creates a fake {@link SQLProjectionItem} with the given alias name.
   * The item's {@code getProjectionAliasAsString()} returns the alias,
   * and {@code copy()} produces a fresh instance with the same alias.
   */
  private SQLProjectionItem fakeProjectionItem(String aliasName) {
    var aliasId = new SQLIdentifier(aliasName);
    return new SQLProjectionItem(-1) {
      {
        this.alias = aliasId;
      }

      @Override
      public SQLIdentifier getProjectionAlias() {
        return aliasId;
      }

      @Override
      public SQLProjectionItem copy() {
        return fakeProjectionItem(aliasName);
      }
    };
  }

  /**
   * Creates an upstream step that produces {@code count} empty records.
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
   * Creates an upstream step that produces records with property "cnt" set
   * to the given values, simulating an aggregation result upstream.
   */
  private AbstractExecutionStep upstreamWithValues(
      CommandContext ctx, List<Long> values) {
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
          for (var value : values) {
            var r = new ResultInternal(c.getDatabaseSession());
            r.setProperty("cnt", value);
            results.add(r);
          }
          done = true;
        }
        return ExecutionStream.resultIterator(results.iterator());
      }
    };
  }
}
