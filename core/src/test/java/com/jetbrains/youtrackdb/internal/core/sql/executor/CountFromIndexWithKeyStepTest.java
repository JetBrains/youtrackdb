package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests the {@link CountFromIndexWithKeyStep} which provides an optimization for
 * {@code SELECT count(*) FROM ClassName WHERE field = ?} queries by performing a
 * single index key lookup and counting distinct RIDs, instead of scanning and filtering.
 *
 * <p>Covers:
 * <ul>
 *   <li>Correct count value returned for matching key</li>
 *   <li>Zero count when key has no matching records</li>
 *   <li>Distinct RID counting with non-unique index (duplicate keys)</li>
 *   <li>Correct count with unique index</li>
 *   <li>Result property uses the provided alias name</li>
 *   <li>Only a single result is produced (the count value)</li>
 *   <li>Predecessor step is drained for side effects before producing results</li>
 *   <li>No predecessor (prev == null) path</li>
 *   <li>Pretty-print rendering with index target information</li>
 *   <li>Step is never cacheable (index entry counts change between executions)</li>
 *   <li>Copy produces an independent, functional step</li>
 * </ul>
 */
public class CountFromIndexWithKeyStepTest extends TestUtilsFixture {

  private static final String PROPERTY_NAME = "name";
  private static final String MATCHING_VALUE = "alice";
  private static final String NON_MATCHING_VALUE = "nonexistent";
  private static final String ALIAS = "cnt";

  private String indexName;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    var clazz = createClassInstance();
    clazz.createProperty(PROPERTY_NAME, PropertyType.STRING);
    var className = clazz.getName();
    indexName = className + "." + PROPERTY_NAME;
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, PROPERTY_NAME);

    // Insert 10 records with the matching key value
    session.begin();
    for (var i = 0; i < 10; i++) {
      var entity = (EntityImpl) session.newEntity(className);
      entity.setProperty(PROPERTY_NAME, MATCHING_VALUE);
    }
    session.commit();

    // Insert 5 records with a different key value to confirm they are not counted
    session.begin();
    for (var i = 0; i < 5; i++) {
      var entity = (EntityImpl) session.newEntity(className);
      entity.setProperty(PROPERTY_NAME, "bob");
    }
    session.commit();
  }

  // --- Core counting behavior ---

  /**
   * The step should perform an index key lookup and return the count of distinct
   * RIDs matching the given key. Only records with matching key should be counted.
   */
  @Test
  public void shouldCountRecordsMatchingKey() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(10L);
  }

  /**
   * When the key has no matching records in the index, the step should return
   * a count of zero rather than an empty stream — count always produces one row.
   */
  @Test
  public void nonMatchingKeyReturnsZeroCount() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(NON_MATCHING_VALUE), ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(0L);
  }

  /**
   * With a different key value ("bob") that was also inserted, the step should
   * return the correct count for that key, not the total index size.
   */
  @Test
  public void countsOnlyRecordsForSpecificKey() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr("bob"), ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(5L);
  }

  /**
   * With a unique index, the step should return 1 for a key that has one record,
   * validating that the distinct-counting logic is correct for unique indexes.
   */
  @Test
  public void uniqueIndexReturnsSingleMatchCount() {
    var clazz = createClassInstance();
    clazz.createProperty("uid", PropertyType.STRING);
    var uniqueIndexName = clazz.getName() + ".uid";
    clazz.createIndex(uniqueIndexName, SchemaClass.INDEX_TYPE.UNIQUE, "uid");

    session.begin();
    var entity = (EntityImpl) session.newEntity(clazz.getName());
    entity.setProperty("uid", "unique-key");
    session.commit();

    var ctx = newContext();
    var identifier = new SQLIndexIdentifier(uniqueIndexName, SQLIndexIdentifier.Type.INDEX);
    var step = new CountFromIndexWithKeyStep(
        identifier, constantExpr("unique-key"), ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(1L);
  }

  // --- Alias handling ---

  /**
   * The result property should be set under the alias name provided at
   * construction time, allowing different aliases like "total" or "count".
   */
  @Test
  public void resultUsesProvidedAlias() {
    var customAlias = "totalMatches";
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), customAlias, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results.getFirst().<Long>getProperty(customAlias)).isEqualTo(10L);
    assertThat(results.getFirst().getPropertyNames()).containsExactly(customAlias);
  }

  // --- Predecessor draining ---

  /**
   * When a predecessor step is chained before this source step (e.g. a
   * GlobalLetExpressionStep), the predecessor's stream must be started and
   * closed for its side effects before the count is produced.
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeCountIsProduced() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevStreamClosed = new AtomicBoolean(false);
    var prev = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevStarted.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext ctx) {
            return false;
          }

          @Override
          public Result next(CommandContext ctx) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext ctx) {
            prevStreamClosed.set(true);
          }
        };
      }
    };
    step.setPrevious(prev);

    var results = drain(step.start(ctx), ctx);

    assertThat(prevStarted).isTrue();
    assertThat(prevStreamClosed).isTrue();
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(10L);
  }

  /**
   * When no predecessor is set (prev == null), the step should still produce
   * the correct count without any error — the null-check guards the drain.
   */
  @Test
  public void noPredecessorProducesCountWithoutError() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);
    // Do not set a predecessor — prev stays null

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(10L);
  }

  // --- prettyPrint ---

  /**
   * prettyPrint should render the step label "CALCULATE INDEX SIZE BY KEY"
   * along with the index identifier's string representation.
   */
  @Test
  public void prettyPrintRendersStepLabelAndIndexTarget() {
    var ctx = newContext();
    var identifier = createIndexIdentifier();
    var step = new CountFromIndexWithKeyStep(
        identifier, constantExpr(MATCHING_VALUE), ALIAS, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CALCULATE INDEX SIZE BY KEY");
    assertThat(output).contains(indexName);
  }

  /**
   * prettyPrint with non-zero depth applies the expected indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("CALCULATE INDEX SIZE BY KEY");
  }

  // --- canBeCached ---

  /**
   * CountFromIndexWithKeyStep is never cacheable because the index entry count
   * for a given key changes between executions as records are inserted or deleted.
   */
  @Test
  public void stepIsNeverCacheable() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // --- copy ---

  /**
   * copy() should produce a new CountFromIndexWithKeyStep that is structurally
   * equivalent but not the same instance. The copy should preserve the alias,
   * index target, key expression, and profiling setting.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var ctx = newContext();
    var step = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(CountFromIndexWithKeyStep.class);
    var copiedStep = (CountFromIndexWithKeyStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    assertThat(copiedStep.canBeCached()).isFalse();
    var results = drain(copiedStep.start(ctx), ctx);
    assertThat(results.getFirst().getPropertyNames()).contains(ALIAS);
  }

  /**
   * A copied step should be fully functional — producing the correct count
   * for the same key independently of the original step.
   */
  @Test
  public void copiedStepCountsRecordsIndependently() {
    var ctx = newContext();
    var original = new CountFromIndexWithKeyStep(
        createIndexIdentifier(), constantExpr(MATCHING_VALUE), ALIAS, ctx, false);
    var copied = (CountFromIndexWithKeyStep) original.copy(ctx);

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Long>getProperty(ALIAS)).isEqualTo(10L);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SQLIndexIdentifier createIndexIdentifier() {
    return new SQLIndexIdentifier(indexName, SQLIndexIdentifier.Type.INDEX);
  }

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates an SQLExpression that always returns the given constant value
   * when executed — simulating a literal value in a WHERE clause.
   */
  private static SQLExpression constantExpr(String constant) {
    return new SQLExpression(-1) {
      @Override
      public Object execute(Result iCurrentRecord, CommandContext ctx) {
        return constant;
      }

      @Override
      public SQLExpression copy() {
        return constantExpr(constant);
      }

      @Override
      public void toString(Map<Object, Object> params, StringBuilder builder) {
        builder.append("'").append(constant).append("'");
      }
    };
  }

  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    // Index lookups require an active transaction
    session.begin();
    try {
      var results = new ArrayList<Result>();
      while (stream.hasNext(ctx)) {
        results.add(stream.next(ctx));
      }
      stream.close(ctx);
      return results;
    } finally {
      session.rollback();
    }
  }
}
