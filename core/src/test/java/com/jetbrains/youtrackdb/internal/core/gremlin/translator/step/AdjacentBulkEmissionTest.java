package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.RequirementsStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;
import org.junit.Test;

/** Direct boundary tests use production generator selection, not a mocked generator contract. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AdjacentBulkEmissionTest {

  /** Adjacent runs compress, separated repeats stay separate, and read-ahead is projected once. */
  @Test
  public void adjacentRunsUseFewerRealGenerationsAndNeverReprojectPendingValues() {
    var h = new Harness("a", "a", "b", "a", "a");
    var generator = spy(h.traversal.getTraverserGenerator());
    assertThat(generator).isInstanceOf(B_O_TraverserGenerator.class);
    doReturn(generator).when(h.traversal).getTraverserGenerator();
    assertThat(h.bulks()).containsExactly(2L, 1L, 2L);
    verify(generator, times(3)).generate(any(), any(), anyLong());
    h.rows.forEach(row -> verify(row).getProperty("value"));
    h.step.close();
    var unique = new Harness(java.util.stream.IntStream.range(0, 20).boxed().toArray());
    assertThat(unique.bulks()).hasSize(20).allMatch(bulk -> bulk == 1);
    unique.step.close();
  }

  /** Exact immutable scalar types and null merge, but mutable values and containers never do. */
  @Test
  public void immutableWhitelistAndContainers() {
    for (Object value : new Object[] {null, "s", true, 'c', (byte) 1, (short) 1, 1, 1L,
        1F, 1D, BigInteger.ONE, BigDecimal.ONE}) {
      var h = new Harness(value, value);
      assertThat(h.bulks()).as("type %s", value).containsExactly(2L);
      h.step.close();
    }
    for (Object value : List.of(List.of(1), Map.of("k", 1), new AtomicInteger(1))) {
      var h = new Harness(value, value);
      assertThat(h.bulks()).containsExactly(1L, 1L);
      h.step.close();
    }
    var h = new Harness(1, 1L, null, null, "a", null);
    assertThat(h.bulks()).containsExactly(1L, 1L, 2L, 1L, 1L);
    h.step.close();
  }

  /** Element equality uses kind and id, not wrapper identity or equality with a scalar id. */
  @Test
  public void elementsMergeOnlyForTheSameKindAndIdentity() {
    var v = new ReferenceVertex(1);
    var edge = new ReferenceEdge(1, "link", v, v);
    var h = new Harness(v, new ReferenceVertex(1), new ReferenceVertex(2), edge,
        new ReferenceEdge(1, "other", v, v), 1);
    assertThat(h.bulks()).containsExactly(2L, 1L, 2L, 1L);
    h.step.close();
  }

  /** Live child position, labels, sacks, disabled bulk and cached path generators forbid D1. */
  @Test
  public void ineligibleEmissionsStayUnitBulk() {
    for (int mode = 0; mode < 6; mode++) {
      var h = new Harness("a", "a");
      switch (mode) {
        case 0 -> new TraversalFilterStep<>(new DefaultTraversal<>(), h.traversal);
        case 1 -> h.step.addLabel("visible");
        case 2 -> h.traversal.getSideEffects().setSack(() -> 1, null, Integer::sum);
        case 3 -> h.traversal.addStep(new RequirementsStep<>(h.traversal,
            java.util.Set.of(TraverserRequirement.ONE_BULK)));
        case 4 -> {
          var identity = new IdentityStep<>(h.traversal);
          identity.addLabel("cachedPath");
          h.traversal.addStep(identity);
          assertThat(h.traversal.getTraverserGenerator().getProvidedRequirements())
              .contains(TraverserRequirement.LABELED_PATH);
          h.traversal.removeStep(identity);
        }
        default -> {
          h.traversal.getSideEffects().setSack(() -> 1, null, null);
          h.traversal.getTraverserGenerator();
          // A cached sack generator remains ineligible without a live sack initializer.
          h.traversal.getSideEffects().setSack(null, null, null);
        }
      }
      assertThat(h.bulks()).as("mode %s", mode).containsExactly(1L, 1L);
      h.step.close();
    }
  }

  /** A long run reads at most the fixed bound per emission and partial close stops all reads. */
  @Test
  public void longRunsStayBoundedAndEmptyInputExhausts() {
    var h = new Harness(Collections.nCopies(600, "a").toArray());
    assertThat(h.step.processNextStart().bulk()).isEqualTo(AbstractMatchPlanStep.MAX_EMISSION_BULK);
    assertThat(h.reads.get()).isEqualTo(AbstractMatchPlanStep.MAX_EMISSION_BULK);
    assertThat(h.bulks()).containsExactly(256L, 88L);
    h.step.close();
    var partial = new Harness("a", "a", "b", "c");
    assertThat(partial.step.processNextStart().bulk()).isEqualTo(2);
    partial.step.close();
    assertThatThrownBy(partial.step::processNextStart).isInstanceOf(NoSuchElementException.class);
    assertThat(partial.reads.get()).isEqualTo(3);
    var empty = new Harness();
    assertThat(empty.bulks()).isEmpty();
    empty.step.close();
  }

  /** A shallow clone after read-ahead must not clear the original's different pending value. */
  @Test
  public void cloneResetAndRestartDropOnlyTheirOwnPendingReferences() {
    var h = new Harness("a", "a", "b");
    var copy = mock(InternalExecutionPlan.class);
    when(copy.getContext()).thenReturn(h.context);
    when(copy.start()).thenAnswer(call -> ExecutionStream.resultIterator(h.rows.iterator()));
    when(copy.copy(any())).thenReturn(mock(InternalExecutionPlan.class));
    when(h.plan.copy(any())).thenReturn(copy);
    assertThat(h.step.processNextStart().bulk()).isEqualTo(2);
    var clone = h.step.clone();
    clone.setTraversal(h.traversal);
    assertThat((Object) h.step.processNextStart().get()).isEqualTo("b");
    assertThat(clone.processNextStart().bulk()).isEqualTo(2);
    clone.close();
    h.step.reset();
    assertThat(h.step.processNextStart().bulk()).isEqualTo(2);
    h.step.close();
    h.step.reset();
    assertThat(h.step.processNextStart().bulk()).isEqualTo(2);
    h.step.close();
  }

  /** Look-ahead failures close immediately, preserve accepted results, and never look exhausted. */
  @Test
  public void failuresAreDeferredUntilTheAcceptedRunIsDelivered() {
    for (Throwable failure : List.of(new IllegalStateException("read"), new AssertionError("read"),
        new NoSuchElementException("broken iterator"))) {
      var h = new Harness("a", "a");
      var stream = mock(ExecutionStream.class);
      when(stream.hasNext(h.context)).thenReturn(true, true).thenThrow(failure);
      when(stream.next(h.context)).thenReturn(h.rows.get(0), h.rows.get(1));
      when(h.plan.start()).thenReturn(stream);
      var cleanup = new IllegalArgumentException("close");
      org.mockito.Mockito.doThrow(cleanup).when(stream).close(h.context);
      var accepted = h.step.processNextStart();
      assertThat((Object) accepted.get()).isEqualTo("a");
      assertThat(accepted.bulk()).isEqualTo(2);
      verify(stream).close(h.context);
      verify(h.plan).close();
      assertThat(failure.getSuppressed()).containsExactly(cleanup);
      for (int repeat = 0; repeat < 2; repeat++) {
        if (failure instanceof NoSuchElementException) {
          assertThatThrownBy(h.step::processNextStart).isInstanceOf(IllegalStateException.class)
              .hasCause(failure);
        } else {
          assertThatThrownBy(h.step::processNextStart).isSameAs(failure);
        }
      }
      h.step.close();
      assertThatThrownBy(h.step::processNextStart).isInstanceOf(NoSuchElementException.class);
    }
  }

  /** A next-row failure also preserves the accepted run and remains an error through hasNext. */
  @Test
  public void nextFailureCannotBecomeTraversalExhaustion() {
    var h = new Harness("a", "a");
    var stream = mock(ExecutionStream.class);
    var failure = new IllegalStateException("next failed");
    when(stream.hasNext(h.context)).thenReturn(true);
    when(stream.next(h.context)).thenReturn(h.rows.get(0), h.rows.get(1)).thenThrow(failure);
    when(h.plan.start()).thenReturn(stream);
    assertThat(h.step.processNextStart().bulk()).isEqualTo(2);
    verify(stream).close(h.context);
    verify(h.plan).close();
    assertThatThrownBy(h.step::hasNext).isSameAs(failure);
    h.step.close();
  }

  /** Concurrent copies from one lazy template own their pending values and generated bulks. */
  @Test
  public void concurrentTemplateCopiesDoNotSharePendingValues() throws Exception {
    var h = new Harness("a", "a", "b");
    when(h.plan.copy(any())).thenAnswer(call -> {
      var copy = mock(InternalExecutionPlan.class);
      when(copy.getContext()).thenReturn(call.getArgument(0));
      when(copy.start()).thenAnswer(ignored -> ExecutionStream.resultIterator(h.rows.iterator()));
      return copy;
    });
    var template = new YTDBMatchPlanStep<>(h.traversal, Vertex.class, h.plan, "v",
        BoundaryOutputType.SCALAR, Map.of(), ResultShaping.NONE, true);
    var barrier = new CyclicBarrier(2);
    Callable<List<Long>> run = () -> {
      var clone = template.clone();
      // Traverser requirements and generators are lazily initialized per worker.
      var traversal = new DefaultTraversal<Object, Vertex>(h.traversal.getGraph().orElseThrow());
      traversal.addStep(clone);
      var bulks = new ArrayList<Long>();
      bulks.add(clone.processNextStart().bulk());
      barrier.await(5, TimeUnit.SECONDS);
      assertThat((Object) clone.processNextStart().get()).isEqualTo("b");
      clone.close();
      return bulks;
    };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(run);
      var second = executor.submit(run);
      assertThat(first.get(10, TimeUnit.SECONDS)).containsExactly(2L);
      assertThat(second.get(10, TimeUnit.SECONDS)).containsExactly(2L);
    }
    verify(h.plan, never()).start();
  }

  private static final class Harness {

    final DefaultTraversal<Object, Vertex> traversal;
    final InternalExecutionPlan plan = mock(InternalExecutionPlan.class);
    final BasicCommandContext context = new BasicCommandContext();
    final List<Result> rows = new ArrayList<>();
    final AtomicInteger reads = new AtomicInteger();
    final YTDBMatchPlanStep<Object, Vertex> step;

    Harness(Object... values) {
      var graph = mock(YTDBGraphInternal.class);
      var tx = mock(YTDBTransaction.class);
      when(graph.tx()).thenReturn(tx);
      var session = mock(DatabaseSessionEmbedded.class);
      when(tx.getDatabaseSession()).thenReturn(session);
      context.setDatabaseSession(session);
      traversal = spy(new DefaultTraversal<>(graph));
      for (var value : values) {
        var row = mock(Result.class);
        when(row.getPropertyNames()).thenReturn(List.of("value"));
        when(row.getProperty("value")).thenReturn(value);
        rows.add(row);
      }
      when(plan.getContext()).thenReturn(context);
      when(plan.start()).thenAnswer(call -> ExecutionStream.resultIterator(rows.stream()
          .peek(ignored -> reads.incrementAndGet()).iterator()));
      step = new YTDBMatchPlanStep<>(traversal, Vertex.class, plan, "v",
          BoundaryOutputType.SCALAR);
      traversal.addStep(step);
    }

    List<Long> bulks() {
      var bulks = new ArrayList<Long>();
      while (true) {
        try {
          Traverser.Admin<?> next = step.processNextStart();
          bulks.add(next.bulk());
        } catch (NoSuchElementException exhausted) {
          return bulks;
        }
      }
    }
  }
}
