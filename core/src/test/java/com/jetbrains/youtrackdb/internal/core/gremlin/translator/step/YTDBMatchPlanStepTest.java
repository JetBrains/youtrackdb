package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Unit tests for {@link YTDBMatchPlanStep}. Stubs {@link InternalExecutionPlan} and the
 * underlying {@link ExecutionStream} via Mockito so the boundary step's plumbing — alias-
 * keyed result extraction, close ordering, exception-path close, and clone independence —
 * can be exercised without spinning up a real database.
 *
 * <p>The graph is also mocked: the projection helper merely wraps a YTDB raw vertex into
 * a {@link YTDBVertexImpl}, whose ctor only dereferences the identifiable's
 * {@code getIdentity()} (returns null for a Mockito mock, which is harmless here) and
 * stores both references. End-to-end correctness against a real graph and real plan is
 * deferred to the integration smoke test that registers the strategy and runs
 * {@code g.V().toList()}.
 *
 * <p>Stubs on the shared {@code traversal} mock are installed with {@link
 * org.mockito.Mockito#lenient()} because the ctor-only tests do not exercise
 * {@code getGraph} / {@code getTraverserSetSupplier} / {@code getTraverserGenerator}; with
 * default-strict stubbing those would surface as {@code UnnecessaryStubbingException} once
 * the suite adopts a Mockito JUnit runner.
 */
public class YTDBMatchPlanStepTest {

  private YTDBGraphInternal graph;
  private YTDBTransaction tx;
  private DatabaseSessionEmbedded threadSession;
  private Traversal.Admin<Object, Vertex> traversal;
  private InternalExecutionPlan plan;
  private CommandContext ctx;
  private ExecutionStream stream;

  @Before
  public void setUp() {
    graph = mock(YTDBGraphInternal.class);
    traversal = freshTraversal(graph);
    plan = mock(InternalExecutionPlan.class);
    ctx = mock(CommandContext.class);
    stream = mock(ExecutionStream.class);
    // createIterator() rebinds the plan to the session active on the iteration thread before
    // running it (see YTDBMatchPlanStep.createIterator). Stub that chain — graph.tx() →
    // YTDBTransaction, readWrite() a no-op, getDatabaseSession() the thread session — so the
    // iteration tests exercise the real rebind path. Lenient because ctor-only tests never
    // reach createIterator.
    tx = mock(YTDBTransaction.class);
    threadSession = mock(DatabaseSessionEmbedded.class);
    lenient().when(graph.tx()).thenReturn(tx);
    lenient().when(tx.getDatabaseSession()).thenReturn(threadSession);
    // Default plan stubs used by every iteration test. Lenient because ctor-only tests
    // never invoke them.
    lenient().when(plan.getContext()).thenReturn(ctx);
    lenient().when(plan.start()).thenReturn(stream);
  }

  // ---- Constructor null guards ----

  /** The boundary alias must be non-null at construction. */
  @Test
  public void ctor_nullBoundaryAlias_throwsNpe() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new YTDBMatchPlanStep<>(
                traversal, Vertex.class, plan, null, BoundaryOutputType.ELEMENT))
        .withMessage("boundaryAlias must not be null");
  }

  /** The plan must be non-null at construction. */
  @Test
  public void ctor_nullPlan_throwsNpe() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new YTDBMatchPlanStep<>(
                traversal, Vertex.class, null, "v", BoundaryOutputType.ELEMENT))
        .withMessage("plan must not be null");
  }

  /** The output type must be non-null at construction. */
  @Test
  public void ctor_nullOutputType_throwsNpe() {
    assertThatNullPointerException()
        .isThrownBy(() -> new YTDBMatchPlanStep<>(traversal, Vertex.class, plan, "v", null))
        .withMessage("outputType must not be null");
  }

  // ---- Iterator construction & projection ----

  /**
   * Regression for the remote-path {@code SessionNotActivatedException}: the boundary step must
   * rebind its plan to the session active on the current (iteration) thread before running it.
   * The plan is built at strategy-application time, which on the server can run on a different
   * thread than iteration; each server worker thread owns its own pooled session, and running
   * the plan against the compile-time session throws {@code SessionNotActivatedException} because
   * YouTrackDB record reads require the session to be active on the reading thread. This test
   * pins the rebind order: {@code createIterator()} opens the transaction ({@code readWrite}) and
   * pushes the thread session onto the plan's context before {@code plan.start()}, so the plan
   * always runs against the thread-active session. The cross-thread reproduction (compile on one
   * thread, iterate on another) is covered end-to-end by the remote TinkerPop process suite
   * ({@code GraphBinaryRemoteGraphProcessExtendedTest}) that CI runs; this unit test pins only the
   * rebind order, since a mock session asserts nothing about the active thread.
   */
  @Test
  public void createIterator_rebindsThreadSessionOntoPlanContext_beforeStart() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.createIterator();

    var order = inOrder(tx, ctx, plan);
    order.verify(tx).readWrite();
    order.verify(ctx).setDatabaseSession(threadSession);
    order.verify(plan).start();
  }

  /**
   * Drives a stream of two rows through the iterator and asserts each emitted vertex wraps
   * the matching raw vertex in order. Verifies (a) {@code row.getVertex("v")} is called once
   * per row, (b) wrappers point at distinct raw entities, and (c) auto-close fires when the
   * stream signals exhaustion. Pulling two rows (rather than one) confirms each
   * {@code next()} pulls a fresh {@link Result} instead of caching the first one.
   */
  @Test
  public void iterator_pullsTwoRowsThenExhausts_andClosesStream() {
    var row1 = mock(Result.class);
    var row2 = mock(Result.class);
    var ytdbVertex1 =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var ytdbVertex2 =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);

    // Five hasNext stubs cover the call pattern: external (T) → next defensive (T) →
    // external (T) → next defensive (T) → external (F, triggers auto-close).
    when(stream.hasNext(ctx)).thenReturn(true, true, true, true, false);
    when(stream.next(ctx)).thenReturn(row1, row2);
    when(row1.getVertex("v")).thenReturn(ytdbVertex1);
    when(row2.getVertex("v")).thenReturn(ytdbVertex2);

    var step = elementStep("v");
    var it = step.createIterator();

    assertThat(it.hasNext()).isTrue();
    var first = it.next();
    assertThat(it.hasNext()).isTrue();
    var second = it.next();
    assertThat(it.hasNext()).isFalse(); // triggers auto-close

    // Each emitted vertex wraps the corresponding raw vertex — guards against a
    // projection that ever caches/reuses a single Result.
    assertThat(rawEntityOf(first)).isSameAs(ytdbVertex1);
    assertThat(rawEntityOf(second)).isSameAs(ytdbVertex2);
    verify(row1).getVertex("v");
    verify(row2).getVertex("v");

    var order = inOrder(stream, plan);
    order.verify(stream, atLeastOnce()).close(ctx);
    order.verify(plan, atLeastOnce()).close();
  }

  /**
   * Empty stream (zero rows): the first {@code hasNext} returns false and triggers auto-
   * close. Verifies the iterator yields nothing and the close hook runs exactly once. This
   * is the canonical "no matches" case (e.g. {@code g.V(missingId)}).
   */
  @Test
  public void iterator_emptyStream_closesAfterFirstHasNext() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    var it = step.createIterator();

    assertThat(it.hasNext()).isFalse();

    verify(stream, times(1)).close(ctx);
    verify(plan, times(1)).close();
    verify(stream, never()).next(ctx);
  }

  /**
   * Single-row stream: stresses the auto-close trigger immediately after the first
   * {@code next}. Off-by-one regressions where exhaustion is detected on the wrong call
   * boundary would surface here but not in the multi-row test.
   */
  @Test
  public void iterator_singleRow_emitsThenAutoCloses() {
    var row = mock(Result.class);
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    when(stream.hasNext(ctx)).thenReturn(true, true, false);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");
    var it = step.createIterator();

    assertThat(it.hasNext()).isTrue();
    var only = it.next();
    assertThat(it.hasNext()).isFalse(); // auto-close

    assertThat(rawEntityOf(only)).isSameAs(rawVertex);
    verify(stream, atLeastOnce()).close(ctx);
    verify(plan, atLeastOnce()).close();
  }

  /**
   * Verifies the projection helper returns null when the row does not bind the boundary
   * alias to a vertex. This is the contract for optional matches that did not yield a
   * binding — downstream native steps treat null payload the same as any other absence.
   */
  @Test
  public void projectElement_missingAlias_returnsNull() {
    var row = mock(Result.class);
    when(row.getVertex("missing")).thenReturn(null);

    var step = elementStep("missing");

    assertThat(step.projectElement(row, graph)).isNull();
  }

  /**
   * Verifies the projection helper looks the alias up under the exact name the step was
   * configured with — not a fixed default — and that each wrapper wraps the raw vertex
   * looked up under that step's alias (not the other one).
   */
  @Test
  public void projectElement_usesConfiguredAlias_andWrapsCorrectRawVertex() {
    var row = mock(Result.class);
    var vertexUnderA =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var vertexUnderB =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    when(row.getVertex("a")).thenReturn(vertexUnderA);
    when(row.getVertex("b")).thenReturn(vertexUnderB);

    var stepA = elementStep("a");
    var stepB = elementStep("b");

    var resultA = stepA.projectElement(row, graph);
    var resultB = stepB.projectElement(row, graph);

    // Each wrapper points at the raw vertex that the step's alias resolved to — not just
    // *some* distinct raw vertex. Swapping the aliases between stepA / stepB would fail
    // here.
    assertThat(rawEntityOf(resultA)).isSameAs(vertexUnderA);
    assertThat(rawEntityOf(resultB)).isSameAs(vertexUnderB);
    verify(row).getVertex("a");
    verify(row).getVertex("b");
  }

  /**
   * {@code next} on a depleted iterator throws {@link NoSuchElementException} per the
   * {@link java.util.Iterator} contract. The boundary step's iterator does not depend on
   * the user calling {@code hasNext} first.
   */
  @Test
  public void iterator_nextOnExhaustedStream_throwsNoSuchElement() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    var it = step.createIterator();

    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(it::next);
  }

  /**
   * Calling {@code next} repeatedly past exhaustion must continue to throw NSE — the
   * iterator is not allowed to silently swallow subsequent calls or re-pull the stream.
   * Locks the {@link java.util.Iterator} contract for past-end behaviour.
   */
  @Test
  public void iterator_nextRepeatedlyPastExhaustion_keepsThrowing() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    var it = step.createIterator();

    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(it::next);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(it::next);
    verify(stream, never()).next(ctx);
  }

  /**
   * If {@code getTraversal().getGraph()} returns an empty {@link Optional}, the step's
   * iterator construction surfaces the failure as {@link NoSuchElementException} from the
   * {@code orElseThrow()} call. Equally important: because graph resolution happens BEFORE
   * {@code plan.start()}, neither the stream nor the plan was ever opened — so there is
   * nothing to leak. This locks the resolve-before-open ordering.
   */
  @Test
  public void createIterator_traversalHasNoGraph_throwsBeforeOpeningPlan() {
    // Replace the @Before traversal mock with one whose getGraph returns empty.
    @SuppressWarnings("unchecked")
    var emptyGraphTraversal = (Traversal.Admin<Object, Vertex>) mock(Traversal.Admin.class);
    lenient().when(emptyGraphTraversal.getGraph()).thenReturn(Optional.empty());
    lenient()
        .when(emptyGraphTraversal.getTraverserGenerator())
        .thenReturn(B_O_TraverserGenerator.instance());
    lenient()
        .when(emptyGraphTraversal.getTraverserSetSupplier())
        .thenReturn((Supplier) TraverserSet::new);

    var step =
        new YTDBMatchPlanStep<>(
            emptyGraphTraversal, Vertex.class, plan, "v", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::createIterator);

    verify(plan, never()).start();
    verify(stream, never()).close(ctx);
    verify(plan, never()).close();
  }

  // ---- Close lifecycle ----

  /**
   * Explicit iterator close (modelling early termination by a downstream {@code LimitStep})
   * closes both the stream and the plan even when the underlying stream still has rows
   * queued. Stream-close runs before plan-close so the last step can flush any buffered
   * state before the plan releases its resources.
   */
  @Test
  public void close_earlyTermination_closesStreamThenPlan() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");
    var it = openIterator(step);

    it.next(); // pull one element to force lazy stream open
    it.close();

    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();
  }

  /**
   * Calling {@code close} twice does not double-invoke the close hook. The wrapper's
   * {@code closed} flag guards against this; we lock the contract here so a future refactor
   * removing the guard fails this test.
   */
  @Test
  public void close_isIdempotent() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    var it = openIterator(step);

    it.hasNext(); // false → auto-close fires once
    it.close(); // explicit second close must be a no-op

    verify(stream, times(1)).close(ctx);
    verify(plan, times(1)).close();
  }

  /**
   * If {@code stream.close} throws, the plan is still closed. The close hook uses a
   * try/finally to guarantee plan.close runs regardless of stream-close success — without
   * that, every stream-close failure would leak the plan's resources.
   */
  @Test
  public void close_streamCloseThrows_planStillClosed() {
    when(stream.hasNext(ctx)).thenReturn(false);
    doThrow(new RuntimeException("stream close failed")).when(stream).close(ctx);

    var step = elementStep("v");
    var it = openIterator(step);

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(it::hasNext) // false → auto-close → throws from stream.close
        .withMessageContaining("stream close failed");

    // InOrder pins that stream-close ran first (and threw) and plan-close ran after.
    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();
  }

  // ---- createIterator failure-path close ----

  /**
   * When {@code plan.start()} itself throws (e.g. an inner step's {@code internalStart}
   * fails after opening cursors), the boundary step must close the plan before propagating
   * the exception so the cursors don't leak. The graph must already have been resolved
   * before the throw, but no stream exists yet, so only the plan is closed.
   */
  @Test
  public void createIterator_planStartThrows_closesPlanAndPropagates() {
    when(plan.start()).thenThrow(new RuntimeException("plan start blew up"));

    var step = elementStep("v");

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::createIterator)
        .withMessageContaining("plan start blew up");

    verify(plan, times(1)).close();
    verify(stream, never()).close(ctx); // no stream was opened
  }

  /**
   * The boundary step is single-shot per instance: a second {@code createIterator} on the
   * same step throws {@link IllegalStateException} rather than silently re-iterating the
   * already-drained plan. Cloned steps get their own plan copy and reset the flag so each
   * clone gets one fresh iteration — pinned by
   * {@link #clone_iteratesOwnPlanCopyIndependentlyOfOriginal()}. The guard fires before a
   * second {@code plan.start()}, so only one start is observed.
   */
  @Test
  public void createIterator_secondCallOnSameInstance_throwsIllegalState() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.createIterator();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(step::createIterator)
        .withMessageContaining("invoked twice");
    verify(plan, times(1)).start();
  }

  // ---- Clone semantics ----

  /**
   * {@link YTDBMatchPlanStep#clone()} gives the clone its OWN deep {@code copy()} of the
   * plan — it does NOT share the original's plan instance. This is the per-execution
   * thread-safety contract: a {@code SelectExecutionPlan}-family plan carries mutable
   * per-run state and must be copied before each independent execution (mirroring
   * {@code HashJoinMatchStep}). The clone remains a distinct step instance and preserves the
   * alias / output-type configuration. Neither plan is started merely by cloning.
   */
  @Test
  public void clone_copiesPlan_forIndependentExecution() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    when(plan.copy(any())).thenReturn(copiedPlan);

    var original = elementStep("v");
    var cloned = original.clone();

    assertThat(cloned).isNotSameAs(original);
    // The clone owns the copy, and the original still owns the original plan.
    assertThat(cloned.getPlan()).isSameAs(copiedPlan);
    assertThat(cloned.getPlan()).isNotSameAs(original.getPlan());
    assertThat(original.getPlan()).isSameAs(plan);
    // Cloning must not eagerly start either plan — start() is lazy, on first iteration.
    verify(plan, never()).start();
    verify(copiedPlan, never()).start();
    // Configuration survives the clone.
    assertThat(cloned.getBoundaryAlias()).isEqualTo("v");
    assertThat(cloned.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * The clone's plan copy must be taken against an ISOLATED CHILD context — a fresh
   * {@link BasicCommandContext} parented to the original plan's context — not against the
   * original context itself. This is the per-execution isolation that mirrors
   * {@code HashJoinMatchStep}: the child owns its own unsynchronised variable maps
   * ({@code $current}, {@code $matched}, step statistics), so the original's and the clone's
   * executions cannot race on or leak that per-run context state. Copying against the shared
   * context (the pre-fix behaviour) would leave both plans pointing at the SAME context.
   *
   * <p>The captured argument is asserted to be (a) a distinct instance from the original
   * context (not the shared {@code ctx}), (b) a {@link BasicCommandContext}, and (c) parented
   * to the original context so the database session / input parameters / timeout still
   * resolve through it.
   */
  @Test
  public void clone_copiesPlanAgainstIsolatedChildContext() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    var contextCaptor = ArgumentCaptor.forClass(CommandContext.class);
    when(plan.copy(contextCaptor.capture())).thenReturn(copiedPlan);

    var original = elementStep("v");
    original.clone();

    var copyContext = contextCaptor.getValue();
    // Not the shared original context — an isolated copy.
    assertThat(copyContext).isNotSameAs(ctx);
    // A fresh BasicCommandContext (the isolation vehicle used by HashJoinMatchStep).
    assertThat(copyContext).isInstanceOf(BasicCommandContext.class);
    // Parented to the original context so session/params/timeout resolve through it while
    // the child's own variable maps stay independent.
    assertThat(copyContext.getParent()).isSameAs(ctx);
  }

  /**
   * The clone's {@code iteratorSupplier} must be re-bound to the clone, not still point at
   * the original. Reading the inherited {@code GraphStep.iteratorSupplier} field via
   * reflection: after {@code clone()}, the original's and clone's suppliers must be
   * distinct instances (both are method references over {@code createIterator}, but bound
   * to different receiver instances). If the clone's supplier still pointed at the
   * original (the contract this test locks), iterating the clone would call the original's
   * {@code createIterator} and start the ORIGINAL's plan instead of the clone's copy.
   */
  @Test
  public void clone_supplierIsReboundToCloneInstance() throws Exception {
    var copiedPlan = mock(InternalExecutionPlan.class);
    when(plan.copy(any())).thenReturn(copiedPlan);

    var original = elementStep("v");
    var cloned = original.clone();

    Field f =
        org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep.class.getDeclaredField(
            "iteratorSupplier");
    f.setAccessible(true);
    var originalSupplier = f.get(original);
    var clonedSupplier = f.get(cloned);

    assertThat(originalSupplier).isNotNull();
    assertThat(clonedSupplier).isNotNull();
    assertThat(clonedSupplier).isNotSameAs(originalSupplier);
  }

  /**
   * Behavioural clone independence: when both the original and the clone are iterated, each
   * starts its OWN plan (the original starts {@code plan}; the clone starts its
   * {@code copy()}). This is what {@code plan.copy()} in {@code clone()} buys — two fully
   * independent executions with no shared mutable plan state. A regression that shared the
   * plan (or re-bound the clone's supplier to the original) would drive both sides through
   * the SAME plan and either short-circuit on the {@code started} guard or corrupt results.
   *
   * <p>Note: TinkerPop's {@code AbstractStep.clone()} sets the cloned step's traversal to
   * {@code EmptyTraversal.instance()} until the host traversal re-attaches it. The test
   * re-attaches manually via {@code setTraversal} so the clone's {@code createIterator} can
   * resolve the graph the same way it would in production.
   */
  @Test
  public void clone_iteratesOwnPlanCopyIndependentlyOfOriginal() {
    // The clone's plan is a distinct mock with its own stream, so we can prove each side
    // started its own plan.
    var copiedPlan = mock(InternalExecutionPlan.class);
    var copiedCtx = mock(CommandContext.class);
    var copiedStream = mock(ExecutionStream.class);
    when(plan.copy(any())).thenReturn(copiedPlan);
    when(copiedPlan.getContext()).thenReturn(copiedCtx);
    when(copiedPlan.start()).thenReturn(copiedStream);
    when(stream.hasNext(ctx)).thenReturn(false);
    when(copiedStream.hasNext(copiedCtx)).thenReturn(false);

    var original = elementStep("v");
    var cloned = original.clone();
    // Mirror TinkerPop's post-clone re-attachment: the cloned step needs a traversal whose
    // getGraph() returns the test fixture, otherwise createIterator() throws NSE from the
    // orElseThrow on EmptyTraversal.
    cloned.setTraversal(traversal);

    original.createIterator();
    cloned.createIterator();

    // Each side started exactly its own plan once — proving the two executions are
    // independent and do not share a single plan instance.
    verify(plan, times(1)).start();
    verify(copiedPlan, times(1)).start();
  }

  // ---- Re-iteration via reset() ----

  /**
   * After {@code reset()} the step is re-armed and {@code createIterator()} may open the plan
   * again on the SAME instance — honouring {@link org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep}'s
   * reset contract (reset makes a start step re-iterable). Before the fix, {@code reset()}
   * re-armed only the inherited fields and left the local guard set, so the second
   * {@code createIterator()} threw {@link IllegalStateException} instead of re-running the
   * plan. This test drives one iteration, resets, and drives a second — asserting the second
   * open succeeds, rewinds the plan via {@code plan.reset(ctx)} (the plan's step chain must
   * be rewound before re-execution), and starts the plan a second time.
   */
  @Test
  public void reset_thenCreateIterator_reRunsPlanOnSameInstance() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.createIterator(); // first arming — opens the plan
    step.reset(); // re-arm in place

    // Second open must NOT throw and must re-start the plan.
    step.createIterator();

    verify(plan, times(2)).start();
    // The re-arm rewinds the plan's step chain before the second start so the second pass
    // runs from the top rather than against consumed cursors.
    verify(plan, times(1)).reset(ctx);
  }

  /**
   * The session rebind must run on every arming, not only the first. TinkerPop may {@code reset()}
   * a start step and re-iterate it on a different thread than the first pass (traversal reuse), so
   * a second {@code createIterator()} must re-resolve and re-push the thread-active session before
   * the second {@code plan.start()}. This pins the rebind as unconditional: a refactor that hoisted
   * it behind the {@code everStarted} guard would rebind only once and reintroduce the remote-path
   * {@code SessionNotActivatedException} on the re-run.
   */
  @Test
  public void reset_thenCreateIterator_rebindsSessionAgainBeforeSecondStart() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.createIterator(); // first arming
    step.reset(); // re-arm in place
    step.createIterator(); // second arming

    // Two full armings → two rebinds, each opening the transaction and pushing the thread session.
    verify(tx, times(2)).readWrite();
    verify(ctx, times(2)).setDatabaseSession(threadSession);
  }

  /**
   * A never-started step that is reset (e.g. TinkerPop resets a start step before any
   * iteration) must NOT call {@code plan.reset(ctx)} on its first open — there is no consumed
   * state to rewind, and rewinding a fresh plan is wasted work. This pins the {@code everStarted}
   * guard: the plan is rewound only when re-arming a plan that actually ran.
   */
  @Test
  public void reset_beforeFirstIteration_doesNotRewindPlanOnFirstOpen() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.reset(); // reset before any createIterator
    step.createIterator(); // first open

    verify(plan, times(1)).start();
    verify(plan, never()).reset(any());
  }

  /**
   * Cloning must not rewind the ORIGINAL's plan. {@code AbstractStep.clone()} invokes
   * {@code reset()} on the freshly-cloned instance while the clone still aliases the
   * original's plan reference (the clone's own copy is installed later in {@code clone()}).
   * If {@code reset()} rewound the plan there, it would corrupt the original's in-flight run.
   * This test drives the original to {@code started}, then clones it, and asserts the
   * original's plan was never {@code reset()} as a side effect of cloning.
   */
  @Test
  public void clone_afterOriginalStarted_doesNotResetOriginalsPlan() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    when(plan.copy(any())).thenReturn(copiedPlan);
    when(stream.hasNext(ctx)).thenReturn(false);

    var original = elementStep("v");
    original.createIterator(); // original is now started (everStarted=true)

    original.clone(); // AbstractStep.clone() calls reset() on the clone mid-construction

    // The original's plan must never be rewound by the clone path.
    verify(plan, never()).reset(any());
  }

  // ---- Clone field-write (no reflection) ----

  /**
   * The clone installs its plan copy via a plain field write, not reflection. The {@code plan}
   * field is non-final precisely so {@code clone()} can assign it directly after
   * {@code super.clone()} — avoiding a post-construction reflective write to a final field,
   * which would void the JMM final-field publication guarantee. This test locks the field's
   * non-final modifier so a future change back to {@code final} (which would reintroduce the
   * reflective-write smell) fails here rather than silently regressing the visibility contract.
   */
  @Test
  public void planField_isNonFinal_soCloneAssignsWithoutReflection() throws Exception {
    Field planField = YTDBMatchPlanStep.class.getDeclaredField("plan");
    assertThat(java.lang.reflect.Modifier.isFinal(planField.getModifiers()))
        .as("plan field must be non-final so clone() can assign the copy without reflection")
        .isFalse();
  }

  // ---- Test helpers ----

  private YTDBMatchPlanStep<Object, Vertex> elementStep(String alias) {
    return new YTDBMatchPlanStep<>(
        traversal, Vertex.class, plan, alias, BoundaryOutputType.ELEMENT);
  }

  /**
   * Casts the step's iterator to {@link CloseableIterator} so tests can call {@code close}
   * directly — the production iterator is in fact a {@code CloseableIteratorWithCallback},
   * which implements {@code CloseableIterator}. Centralising the cast in one helper makes
   * the assumption explicit; if it ever stops holding, every close-related test fails in
   * one place.
   */
  private static CloseableIterator<Vertex> openIterator(
      YTDBMatchPlanStep<Object, Vertex> step) {
    return (CloseableIterator<Vertex>) step.createIterator();
  }

  /**
   * Reflectively reads the {@code fastPathEntity} field on a {@link YTDBVertexImpl}. The
   * field is the raw YTDB entity the wrapper was constructed with; comparing it via
   * identity is how we verify a projected wrapper was built from the right raw vertex
   * without reaching into a real graph context.
   */
  private static Object rawEntityOf(Vertex tinkerVertex) {
    assertThat(tinkerVertex).isInstanceOf(YTDBVertexImpl.class);
    try {
      Field f =
          Class.forName("com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl")
              .getDeclaredField("fastPathEntity");
      f.setAccessible(true);
      return f.get(tinkerVertex);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Failed to read fastPathEntity via reflection", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Traversal.Admin<Object, Vertex> freshTraversal(YTDBGraphInternal graph) {
    var traversal = (Traversal.Admin<Object, Vertex>) mock(Traversal.Admin.class);
    lenient().when(traversal.getGraph()).thenReturn(Optional.of((Graph) graph));
    lenient()
        .when(traversal.getTraverserGenerator())
        .thenReturn(B_O_TraverserGenerator.instance());
    // AbstractStep's ctor calls traversal.getTraverserSetSupplier().get() to initialise
    // its starts/ends sets; supply a real empty TraverserSet so the super-ctor doesn't
    // NPE on the mock's default-null return. Typed Supplier (no raw cast) so signature
    // changes surface at compile time.
    Supplier<TraverserSet<Object>> traverserSetSupplier = TraverserSet::new;
    lenient().when(traversal.getTraverserSetSupplier()).thenReturn((Supplier) traverserSetSupplier);
    return traversal;
  }
}
