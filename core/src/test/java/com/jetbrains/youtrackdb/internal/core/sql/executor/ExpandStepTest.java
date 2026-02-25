package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link ExpandStep}, covering the {@code expand(field)} projection
 * operator logic: null predecessor guard, empty/multi-property error paths,
 * and each branch of the type dispatch in {@code nextResults} (null, Identifiable,
 * Result, Iterator, Iterable, Map, default).
 *
 * <p>Also covers prettyPrint rendering (with/without profiling), cacheability,
 * and the deep-copy contract.
 */
public class ExpandStepTest extends DbTestBase {

  // =========================================================================
  // internalStart: predecessor guard
  // =========================================================================

  /**
   * Starting an ExpandStep without a predecessor throws
   * {@link CommandExecutionException} because there is no upstream data source
   * to expand.
   */
  @Test
  public void startWithNoPredecessorThrowsCommandExecutionException() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("Cannot expand without a target");
  }

  // =========================================================================
  // nextResults: property-name checks
  // =========================================================================

  /**
   * When the upstream result has no properties at all, the expand step
   * produces no output (there is nothing to expand).
   */
  @Test
  public void expandEmptyPropertyNamesProducesNothing() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    var emptyResult = new ResultInternal(ctx.getDatabaseSession());
    step.setPrevious(sourceStep(ctx, List.of(emptyResult)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  /**
   * Expanding a non-entity result with more than one property is invalid
   * because EXPAND operates on a single field. This throws
   * {@link IllegalStateException}.
   */
  @Test
  public void expandNonEntityWithMultiplePropertiesThrows() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    var multiProp = new ResultInternal(ctx.getDatabaseSession());
    multiProp.setProperty("a", 1);
    multiProp.setProperty("b", 2);
    step.setPrevious(sourceStep(ctx, List.of(multiProp)));

    var stream = step.start(ctx);
    assertThatThrownBy(() -> drain(stream, ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid EXPAND on record");
  }

  // =========================================================================
  // nextResults: type dispatch branches
  // =========================================================================

  /**
   * When the single property value is null, EXPAND produces no output.
   */
  @Test
  public void expandNullPropertyValueProducesNothing() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("field", null);
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  /**
   * When the property value is a {@link Result} object, EXPAND passes it
   * through as a single output row.
   */
  @Test
  public void expandResultValuePassesThroughAsIs() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    var inner = new ResultInternal(ctx.getDatabaseSession());
    inner.setProperty("name", "Alice");
    var outer = new ResultInternal(ctx.getDatabaseSession());
    outer.setProperty("field", inner);
    step.setPrevious(sourceStep(ctx, List.of(outer)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("name"))
        .isEqualTo("Alice");
  }

  /**
   * When the property value is an {@link java.util.Iterator}, EXPAND produces
   * one output row per element. This branch is reached when the upstream
   * produces an Iterator directly (e.g. from a custom function).
   */
  @Test
  public void expandIteratorProducesRowPerElement() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, "alias");

    var result = new ResultInternal(ctx.getDatabaseSession());
    // Store an Iterator directly in the content map because
    // setProperty() does not accept Iterator as a valid type.
    result.content.put("field",
        List.of("x", "y", "z").iterator());
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(3);
    assertThat(results.get(0).<String>getProperty("alias"))
        .isEqualTo("x");
    assertThat(results.get(1).<String>getProperty("alias"))
        .isEqualTo("y");
    assertThat(results.get(2).<String>getProperty("alias"))
        .isEqualTo("z");
  }

  /**
   * When the property value is an {@link Iterable} (e.g. a List), EXPAND
   * produces one output row per element using the iterable's iterator.
   */
  @Test
  public void expandIterableProducesRowPerElement() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, "myAlias");

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("field", List.of("a", "b"));
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).<String>getProperty("myAlias"))
        .isEqualTo("a");
    assertThat(results.get(1).<String>getProperty("myAlias"))
        .isEqualTo("b");
  }

  /**
   * When the property value is a {@link Map} with null alias, EXPAND produces
   * one output row per map entry (key-value pair).
   */
  @Test
  public void expandMapProducesRowPerEntry() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("field",
        Map.of("k1", "v1", "k2", "v2"));
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var results = drain(step.start(ctx), ctx);
    // Only assert size: the internal representation of map entries
    // depends on IteratorExecutionStream and is not part of ExpandStep's
    // contract. The full map expansion behavior is tested via SQL in
    // SQLFunctionExpandTest.expandMap().
    assertThat(results).hasSize(2);
  }

  /**
   * Expanding a Map with a non-null alias throws
   * {@link CommandExecutionException} because map expansion requires
   * generating key-value results, which is incompatible with aliasing.
   */
  @Test
  public void expandMapWithAliasThrows() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, "myAlias");

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("field", Map.of("k1", "v1"));
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var stream = step.start(ctx);
    assertThatThrownBy(() -> drain(stream, ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining(
            "Cannot expand a map with a non-null alias");
  }

  /**
   * When the property value does not match any recognized type (not null,
   * Identifiable, Result, Iterator, Iterable, or Map), EXPAND produces
   * no output (the value is silently skipped).
   */
  @Test
  public void expandUnsupportedTypeProducesNothing() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    // An Integer is not Identifiable/Result/Iterator/Iterable/Map
    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("field", 42);
    step.setPrevious(sourceStep(ctx, List.of(result)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // nextResults: Identifiable paths
  // =========================================================================

  /**
   * When the upstream result is an entity, EXPAND extracts the entity itself
   * as the value to expand. A non-embedded entity is an Identifiable, so it
   * is loaded as a full record and returned as a single output row.
   */
  @Test
  public void expandEntityResultLoadsRecord() {
    var ctx = newContext();
    session.createClass("ExpandEntity");

    session.executeInTx(tx -> {
      var entity = session.newEntity("ExpandEntity");
      entity.setProperty("name", "test");

      var step = new ExpandStep(ctx, false, null);
      var entityResult =
          new ResultInternal(ctx.getDatabaseSession(), entity);
      step.setPrevious(sourceStep(ctx, List.of(entityResult)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name"))
          .isEqualTo("test");
    });
  }

  /**
   * Expanding an Identifiable with a non-null alias is not supported and
   * throws {@link CommandExecutionException}. The alias mechanism is only
   * meaningful for collection/iterator expansion.
   */
  @Test
  public void expandIdentifiableWithAliasThrows() {
    var ctx = newContext();
    session.createClass("ExpandAlias");

    session.executeInTx(tx -> {
      var entity = session.newEntity("ExpandAlias");

      var step = new ExpandStep(ctx, false, "myAlias");
      var upstream = new ResultInternal(ctx.getDatabaseSession());
      // Bypass setProperty: it converts Identifiable to a bare RID
      // via convertPropertyValue(), losing the Identifiable type.
      upstream.content.put("field", entity.getIdentity());
      step.setPrevious(sourceStep(ctx, List.of(upstream)));

      var stream = step.start(ctx);
      assertThatThrownBy(() -> drain(stream, ctx))
          .isInstanceOf(CommandExecutionException.class)
          .hasMessageContaining(
              "Cannot expand a record with a non-null alias");
    });
  }

  /**
   * When the expanded Identifiable points to a record that no longer exists
   * (dangling link), the {@link com.jetbrains.youtrackdb.api.exception.RecordNotFoundException}
   * is caught and the missing record is silently skipped.
   */
  @Test
  public void expandDanglingLinkSkipsDeletedRecord() {
    var ctx = newContext();
    var cls = session.createClass("ExpandDanglingLink");
    var collectionId = cls.getCollectionIds()[0];

    session.executeInTx(tx -> {
      var step = new ExpandStep(ctx, false, null);
      // A RID pointing to a non-existent record in a valid collection
      var danglingRid = new RecordId(collectionId, 999999);
      var upstream = new ResultInternal(ctx.getDatabaseSession());
      // Bypass setProperty to store the RID directly as an Identifiable
      upstream.content.put("field", danglingRid);
      step.setPrevious(sourceStep(ctx, List.of(upstream)));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    });
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint without profiling renders the "EXPAND" label with correct
   * indentation but no cost suffix.
   */
  @Test
  public void prettyPrintWithoutProfiling() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    assertThat(step.prettyPrint(0, 2)).isEqualTo("+ EXPAND");
  }

  /**
   * prettyPrint with profiling appends the cost in microseconds.
   */
  @Test
  public void prettyPrintWithProfiling() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, true, null);

    var output = step.prettyPrint(0, 2);
    assertThat(output).startsWith("+ EXPAND");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with non-zero depth prepends the correct indentation.
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);

    assertThat(step.prettyPrint(1, 4)).isEqualTo("    + EXPAND");
  }

  // =========================================================================
  // canBeCached and copy
  // =========================================================================

  /**
   * ExpandStep is cacheable because the expand alias is a fixed string
   * determined at plan time.
   */
  @Test
  public void canBeCachedReturnsTrue() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, false, null);
    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * copy() produces a new ExpandStep that is structurally equivalent but
   * independent: it has the same alias and profiling setting, and can
   * execute independently of the original.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var step = new ExpandStep(ctx, true, "myField");

    var copied = (ExpandStep) step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied.canBeCached()).isTrue();
    // Verify profiling is preserved
    var output = copied.prettyPrint(0, 2);
    assertThat(output).startsWith("+ EXPAND");
    assertThat(output).contains("μs");
    // Verify alias is preserved by expanding an iterable through the copy
    var upstream = new ResultInternal(ctx.getDatabaseSession());
    upstream.setProperty("field", List.of("val"));
    copied.setPrevious(sourceStep(ctx, List.of(upstream)));
    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("myField"))
        .isEqualTo("val");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates a source step that produces the given results on each start().
   */
  private ExecutionStepInternal sourceStep(
      CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(
            new ArrayList<>(rows).iterator());
      }
    };
  }

  /**
   * Drains all results from a stream into a list.
   */
  private List<Result> drain(
      ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
