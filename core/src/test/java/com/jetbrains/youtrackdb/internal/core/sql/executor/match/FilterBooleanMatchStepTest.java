package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deterministic execution witnesses for short circuit, first match and row ownership. */
public class FilterBooleanMatchStepTest {

  /** OR stops before the failing arm, probes only once per duplicate and returns original rows. */
  @Test
  public void orShortCircuitStopsAtFirstMatchAndPreservesDuplicates() {
    var ctx = context();
    var row = new ResultInternal(null);
    row.setProperty("p", "original");
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, row);
    var calls = new AtomicInteger();
    var closes = new AtomicInteger();
    var tree = node(ctx, FilterBooleanMatchStep.Kind.OR,
        List.of(probe(ctx, calls, closes, true, false),
            probe(ctx, new AtomicInteger(), new AtomicInteger(), true, true)));
    tree.setPrevious(source(ctx, List.of(row, row)));
    var stream = tree.start(ctx);
    try {
      assertThat(stream.stream(ctx).toList()).containsExactly(row, row);
      assertThat(calls).hasValue(2);
      assertThat(closes).hasValue(2);
      assertThat(row.<String>getProperty("p")).isEqualTo("original");
      assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_MATCHED)).isSameAs(row);
    } finally {
      stream.close(ctx);
      tree.close();
    }
  }

  /** AND stops on false, NOT inverts the whole result, and empty boolean groups obey identities. */
  @Test
  public void andNotAndEmptyGroupsShortCircuit() {
    var ctx = context();
    var row = new ResultInternal(null);
    var calls = new AtomicInteger();
    var and = node(ctx, FilterBooleanMatchStep.Kind.AND,
        List.of(probe(ctx, calls, new AtomicInteger(), false, false),
            probe(ctx, new AtomicInteger(), new AtomicInteger(), true, true)));
    var not = node(ctx, FilterBooleanMatchStep.Kind.NOT, List.of(and));
    not.setPrevious(source(ctx, List.of(row)));
    var stream = not.start(ctx);
    assertThat(stream.stream(ctx).toList()).containsExactly(row);
    assertThat(calls).hasValue(1);
    stream.close(ctx);
    not.close();
    for (var kind : List.of(FilterBooleanMatchStep.Kind.AND, FilterBooleanMatchStep.Kind.OR)) {
      var empty = node(ctx, kind, List.of());
      empty.setPrevious(source(ctx, List.of(row)));
      stream = empty.start(ctx);
      assertThat(stream.hasNext(ctx)).isEqualTo(kind == FilterBooleanMatchStep.Kind.AND);
      stream.close(ctx);
      empty.close();
    }
  }

  /** Probe errors propagate after stream cleanup and restoration of outer bindings. */
  @Test
  public void probeExceptionRestoresOuterMatchedRow() {
    var ctx = context();
    var sentinel = new ResultInternal(null);
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, sentinel);
    var calls = new AtomicInteger();
    var closes = new AtomicInteger();
    var tree = probe(ctx, calls, closes, true, true);
    tree.setPrevious(source(ctx, List.of(new ResultInternal(null))));
    var stream = tree.start(ctx);
    try {
      assertThatThrownBy(() -> stream.hasNext(ctx)).isInstanceOf(IllegalStateException.class)
          .hasMessage("probe error");
      assertThat(calls).hasValue(1);
      assertThat(closes).hasValue(1);
      assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_MATCHED)).isSameAs(sentinel);
    } finally {
      stream.close(ctx);
      tree.close();
    }
  }

  /** Cacheability includes every descendant, even an arm that never runs. */
  @Test
  public void nonCacheableDescendantAndMissingSourceAreVisible() {
    var ctx = context();
    var leaf = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.empty();
      }

      @Override
      public boolean canBeCached() {
        return false;
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        throw new UnsupportedOperationException("non-cacheable fixture");
      }
    };
    var probe = new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
        List.of(), null, null, List.of(leaf), null);
    var tree = node(ctx, FilterBooleanMatchStep.Kind.OR, List.of(probe));
    assertThat(tree.canBeCached()).isFalse();
    assertThatThrownBy(() -> tree.start(ctx)).isInstanceOf(IllegalStateException.class);
  }

  /** Overflow builds once per start, never once per row, then rebuilds after reset. */
  @Test
  public void overflowBuildCountIsStickyUntilTheNextStart() {
    var ctx = context();
    var builds = new AtomicInteger();
    var probes = new AtomicInteger();
    var hash = mock(HashJoinMatchStep.class);
    when(hash.buildHashSet(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      builds.incrementAndGet();
      return null;
    });
    var nested = probe(ctx, probes, new AtomicInteger(), true, false);
    var tree = new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
        List.of(), null, null,
        nested.getSubSteps().stream().map(AbstractExecutionStep.class::cast).toList(), hash);
    tree.setPrevious(source(ctx, List.of(new ResultInternal(null), new ResultInternal(null))));
    for (int start = 1; start <= 2; start++) {
      var stream = tree.start(ctx);
      assertThat(stream.stream(ctx).toList()).hasSize(2);
      stream.close(ctx);
      assertThat(builds).hasValue(start);
      assertThat(probes).hasValue(start * 2);
      tree.reset();
    }
    tree.close();
  }

  /** A failed build propagates and the next start attempts a fresh build before upstream runs. */
  @Test
  public void failedBuildIsNeverPublishedAsBuilt() {
    var ctx = context();
    var builds = new AtomicInteger();
    var hash = mock(HashJoinMatchStep.class);
    when(hash.buildHashSet(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      if (builds.incrementAndGet() == 1) {
        throw new IllegalStateException("build failed");
      }
      return new java.util.HashSet<JoinKey>();
    });
    var tree = new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
        List.of(), null, null, List.of(), hash);
    tree.setPrevious(source(ctx, List.of(new ResultInternal(null))));
    assertThatThrownBy(() -> tree.start(ctx)).hasMessage("build failed");
    var stream = tree.start(ctx);
    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(builds).hasValue(2);
    stream.close(ctx);
    tree.close();
  }

  /** FILTER publishes both bindings and restores existing or absent bindings on every exit. */
  @Test
  public void filterRestoresBindingsOnTrueFalseAndException() {
    for (var seeded : List.of(true, false)) {
      for (int outcome = 0; outcome < 3; outcome++) {
        var ctx = context();
        var sentinel = new ResultInternal(null);
        if (seeded) {
          ctx.setSystemVariable(CommandContext.VAR_MATCHED, sentinel);
          ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, sentinel);
        }
        var record = new ResultInternal(null);
        record.setProperty("name", "alias");
        var row = new ResultInternal(null);
        row.setProperty("p", record);
        int expected = outcome;
        var condition = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause(-1) {
          @Override
          public boolean matchesFilters(Result currentRecord, CommandContext context) {
            assertThat(context.<Object>getSystemVariable(CommandContext.VAR_MATCHED))
                .isSameAs(row);
            assertThat(context.<Object>getSystemVariable(CommandContext.VAR_CURRENT_MATCH))
                .isSameAs(currentRecord);
            if (expected == 2) {
              throw new IllegalStateException("filter error");
            }
            return expected == 1;
          }
        };
        var tree = new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.FILTER,
            List.of(), "p", condition, List.of(), null);
        tree.setPrevious(source(ctx, List.of(row)));
        var stream = tree.start(ctx);
        try {
          if (outcome == 2) {
            assertThatThrownBy(() -> stream.hasNext(ctx)).hasMessage("filter error");
          } else {
            assertThat(stream.hasNext(ctx)).isEqualTo(outcome == 1);
          }
          assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_MATCHED))
              .isSameAs(seeded ? sentinel : null);
          assertThat(ctx.<Object>getSystemVariable(CommandContext.VAR_CURRENT_MATCH))
              .isSameAs(seeded ? sentinel : null);
        } finally {
          stream.close(ctx);
          tree.close();
        }
      }
    }
  }

  private BasicCommandContext context() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.assertIfNotActive()).thenReturn(true);
    return new BasicCommandContext(session);
  }

  private FilterBooleanMatchStep node(CommandContext ctx, FilterBooleanMatchStep.Kind kind,
      List<FilterBooleanMatchStep> children) {
    return new FilterBooleanMatchStep(ctx, false, kind, children, null, null, List.of(), null);
  }

  private FilterBooleanMatchStep probe(CommandContext ctx, AtomicInteger calls,
      AtomicInteger closes, boolean matches, boolean fail) {
    var step = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        var matched = context.<ResultInternal>getSystemVariable(CommandContext.VAR_MATCHED);
        matched.setProperty("p", "probe local");
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext context) {
            calls.incrementAndGet();
            if (fail) {
              throw new IllegalStateException("probe error");
            }
            return matches;
          }

          @Override
          public Result next(CommandContext context) {
            throw new AssertionError("first-match probes must not drain results");
          }

          @Override
          public void close(CommandContext context) {
            closes.incrementAndGet();
          }
        };
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        throw new UnsupportedOperationException("counter fixture is not a cache template");
      }
    };
    return new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
        List.of(), null, null, List.of(step), null);
  }

  private AbstractExecutionStep source(CommandContext ctx, List<Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStream internalStart(CommandContext context) {
        return ExecutionStream.resultIterator(rows.iterator());
      }

      @Override
      public ExecutionStep copy(CommandContext context) {
        return source(context, rows);
      }
    };
  }
}
