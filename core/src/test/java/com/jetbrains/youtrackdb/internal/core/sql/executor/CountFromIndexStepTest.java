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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests the {@link CountFromIndexStep} which provides an O(1) optimization for
 * {@code SELECT count(*) FROM index:indexName} queries by reading the index
 * entry count directly from the index manager instead of scanning records.
 *
 * <p>Covers:
 * <ul>
 *   <li>Correct count value returned from index metadata</li>
 *   <li>Result property uses the provided alias name</li>
 *   <li>Only a single result is produced (the count value)</li>
 *   <li>Predecessor step is drained for side effects before producing results</li>
 *   <li>Pretty-print rendering with index target information</li>
 *   <li>Step is never cacheable (index entry counts change between executions)</li>
 *   <li>Copy produces an independent, functional step</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class CountFromIndexStepTest extends TestUtilsFixture {

  private static final String PROPERTY_NAME = "testPropertyName";
  private static final String PROPERTY_VALUE = "testPropertyValue";
  private static final String ALIAS = "size";
  private String indexName;

  private final SQLIndexIdentifier.Type identifierType;

  public CountFromIndexStepTest(SQLIndexIdentifier.Type identifierType) {
    this.identifierType = identifierType;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> types() {
    return Arrays.asList(
        new Object[][]{
            {SQLIndexIdentifier.Type.INDEX},
            {SQLIndexIdentifier.Type.VALUES},
            {SQLIndexIdentifier.Type.VALUESASC},
            {SQLIndexIdentifier.Type.VALUESDESC},
        });
  }

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    var clazz = createClassInstance();
    clazz.createProperty(PROPERTY_NAME, PropertyType.STRING);
    var className = clazz.getName();
    indexName = className + "." + PROPERTY_NAME;
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, PROPERTY_NAME);

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = (EntityImpl) session.newEntity(className);
      document.setProperty(PROPERTY_NAME, PROPERTY_VALUE);

      session.commit();
    }
  }

  // --- Core counting behavior ---

  /**
   * The step should read the index entry count directly from the index manager,
   * returning the exact number of records that were inserted into the index.
   */
  @Test
  public void shouldCountRecordsOfIndex() {
    var ctx = newContext();
    var step = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(20L);
  }

  /**
   * When the index has no entries, the step should return a count of zero
   * rather than an empty stream — a count query always produces exactly one row.
   */
  @Test
  public void emptyIndexReturnsZeroCount() {
    // Create a separate class with an empty index (no records inserted)
    var emptyClass = createClassInstance();
    emptyClass.createProperty("emptyProp", PropertyType.STRING);
    var emptyIndexName = emptyClass.getName() + ".emptyProp";
    emptyClass.createIndex(
        emptyIndexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "emptyProp");

    var name = new SQLIndexName(-1);
    name.setValue(emptyIndexName);
    var identifier = new SQLIndexIdentifier(-1);
    identifier.setIndexName(name);
    identifier.setIndexNameString(name.getValue());
    identifier.setType(identifierType);

    var ctx = newContext();
    var step = new CountFromIndexStep(identifier, ALIAS, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(0L);
  }

  /**
   * The result property should be set under the alias name provided at
   * construction time, allowing different aliases like "cnt" or "total".
   */
  @Test
  public void resultUsesProvidedAlias() {
    var customAlias = "totalEntries";
    var ctx = newContext();
    var step = new CountFromIndexStep(createIndexIdentifier(), customAlias, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results.get(0).<Long>getProperty(customAlias)).isEqualTo(20L);
    // Verify only the custom alias is present, not the default
    assertThat(results.get(0).getPropertyNames()).containsExactly(customAlias);
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
    var step = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, false);

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
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(20L);
  }

  // --- prettyPrint ---

  /**
   * prettyPrint should render the step label containing "CALCULATE INDEX SIZE"
   * along with the index identifier's string representation.
   */
  @Test
  public void prettyPrintRendersStepLabelAndIndexTarget() {
    var ctx = newContext();
    var identifier = createIndexIdentifier();
    var step = new CountFromIndexStep(identifier, ALIAS, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CALCULATE INDEX SIZE");
    assertThat(output).contains(indexName);
  }

  /**
   * prettyPrint with non-zero depth applies the expected indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("CALCULATE INDEX SIZE");
  }

  // --- canBeCached ---

  /**
   * CountFromIndexStep is never cacheable because the index entry count
   * changes between executions as records are inserted or deleted, so the
   * result must always be recomputed.
   */
  @Test
  public void stepIsNeverCacheable() {
    var ctx = newContext();
    var step = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // --- copy ---

  /**
   * copy() should produce a new CountFromIndexStep that is structurally
   * equivalent but not the same instance. The copy should preserve the
   * alias, index target, and profiling setting.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var ctx = newContext();
    var step = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(CountFromIndexStep.class);
    var copiedStep = (CountFromIndexStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    // The copy should also not be cacheable (same semantics)
    assertThat(copiedStep.canBeCached()).isFalse();
    // The copy should preserve the alias and index target
    var results = drain(copiedStep.start(ctx), ctx);
    assertThat(results.get(0).getPropertyNames()).contains(ALIAS);
  }

  /**
   * A copied step should be fully functional — producing the correct index
   * entry count independently of the original step.
   */
  @Test
  public void copiedStepCountsRecordsIndependently() {
    var ctx = newContext();
    var original = new CountFromIndexStep(createIndexIdentifier(), ALIAS, ctx, false);
    var copied = (CountFromIndexStep) original.copy(ctx);

    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Long>getProperty(ALIAS)).isEqualTo(20L);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SQLIndexIdentifier createIndexIdentifier() {
    var name = new SQLIndexName(-1);
    name.setValue(indexName);
    var identifier = new SQLIndexIdentifier(-1);
    identifier.setIndexName(name);
    identifier.setIndexNameString(name.getValue());
    identifier.setType(identifierType);
    return identifier;
  }

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
