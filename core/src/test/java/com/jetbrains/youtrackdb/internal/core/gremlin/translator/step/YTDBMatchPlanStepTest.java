package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Unit tests for {@link YTDBMatchPlanStep}. Stubs {@link InternalExecutionPlan} and the underlying
 * {@link ExecutionStream} via Mockito so the boundary step's plumbing — alias-keyed result
 * extraction, close ordering, exception-path close, reset re-arming, and clone independence — can be
 * exercised without a real database.
 *
 * <p>The step extends {@code AbstractStep}; iteration is driven through its {@code protected
 * processNextStart()} (package-visible to this same-package test), which opens the plan's stream on
 * first call, projects one row per call, and throws {@link NoSuchElementException} (a {@code
 * FastNoSuchElementException}) once the stream is exhausted, closing the arming's stream as it does
 * so. The plan itself is closed by {@code close()}, which TinkerPop fires on exhaustion; these
 * tests drive {@code processNextStart()} directly, so they call {@code close()} explicitly to
 * observe the plan close.
 *
 * <p>The graph is mocked: the projection helper wraps a YTDB raw vertex into a {@link
 * YTDBVertexImpl}, whose ctor only dereferences the identifiable's {@code getIdentity()} (null for a
 * mock, harmless here) and stores both references. End-to-end correctness against a real graph and
 * plan is covered by the integration smoke test that registers the strategy and runs {@code
 * g.V().toList()}.
 *
 * <p>Stubs on the shared {@code traversal} mock use {@link org.mockito.Mockito#lenient()}: this
 * class runs plain {@code mock()} with no Mockito runner, so strict-stub detection is inert today
 * and {@code lenient()} is pre-emptive — adopting {@code MockitoJUnitRunner} later would not surface
 * {@code UnnecessaryStubbingException} on the ctor-only tests, which never reach {@code
 * processNextStart}.
 */
@SuppressWarnings({"unchecked", "rawtypes", "resource"})
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
    // processNextStart() rebinds the plan to the session active on the iteration thread before
    // running it (see openArming). Stub that chain — graph.tx() → YTDBTransaction, readWrite() a
    // no-op, getDatabaseSession() the thread session — so iteration tests exercise the real rebind
    // path. Lenient because ctor-only tests never reach it.
    tx = mock(YTDBTransaction.class);
    threadSession = mock(DatabaseSessionEmbedded.class);
    lenient().when(graph.tx()).thenReturn(tx);
    lenient().when(tx.getDatabaseSession()).thenReturn(threadSession);
    // Default plan stubs used by every iteration test. Lenient because ctor-only tests never invoke
    // them.
    lenient().when(plan.getContext()).thenReturn(ctx);
    lenient().when(plan.start()).thenReturn(stream);
  }

  // ---- Iteration & projection ----

  /**
   * Regression for the remote-path {@code SessionNotActivatedException}: the boundary step must
   * rebind its plan to the session active on the current (iteration) thread before running it. The
   * plan is built at strategy-application time, which on the server can run on a different thread
   * than iteration; each worker thread owns its own pooled session, and running the plan against
   * the compile-time session throws {@code SessionNotActivatedException} because YTDB record reads
   * require the session active on the reading thread. This pins the rebind order: the first
   * {@code processNextStart()} opens the transaction ({@code readWrite}) and pushes the thread
   * session onto the plan's context before {@code plan.start()}. The cross-thread reproduction is
   * covered end-to-end by the remote TinkerPop process suite; here a mock session asserts only the
   * order.
   */
  @Test
  public void processNextStart_rebindsThreadSessionOntoPlanContext_beforeStart() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    var order = inOrder(tx, ctx, plan);
    order.verify(tx).readWrite();
    order.verify(ctx).setDatabaseSession(threadSession);
    order.verify(plan).start();
  }

  /**
   * Drives two rows through the step and asserts each generated traverser carries the matching raw
   * vertex in order. Verifies (a) {@code row.getVertex("v")} is read once per row, (b) wrappers
   * point at distinct raw entities, and (c) exhaustion closes the arming's stream once but leaves
   * the plan open, and the explicit {@code close()} then closes the plan. Pulling two rows confirms
   * each call pulls a fresh {@link Result} rather than caching the first.
   */
  @Test
  public void processNextStart_pullsTwoRowsThenExhausts_andClosesStream() {
    var row1 = mock(Result.class);
    var row2 = mock(Result.class);
    var ytdbVertex1 =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var ytdbVertex2 =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);

    // One hasNext probe per processNextStart: true (row1) → true (row2) → false (exhaust/close).
    when(stream.hasNext(ctx)).thenReturn(true, true, false);
    when(stream.next(ctx)).thenReturn(row1, row2);
    when(row1.getVertex("v")).thenReturn(ytdbVertex1);
    when(row2.getVertex("v")).thenReturn(ytdbVertex2);

    var step = elementStep("v");

    var first = step.processNextStart().get();
    var second = step.processNextStart().get();
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(step::processNextStart); // exhaustion triggers close

    // Each generated payload wraps the corresponding raw vertex — guards against a projection that
    // ever caches/reuses a single Result.
    assertThat(assertRawEntityOf(first)).isSameAs(ytdbVertex1);
    assertThat(assertRawEntityOf(second)).isSameAs(ytdbVertex2);
    verify(row1).getVertex("v");
    verify(row2).getVertex("v");

    // Exhaustion closes the arming's STREAM but leaves the plan open (so a reset before close could
    // re-iterate); the plan is closed by close(), which TinkerPop fires on exhaustion — here driven
    // explicitly because the test calls processNextStart directly rather than through a traversal.
    verify(stream, times(1)).close(ctx);
    verify(plan, never()).close();

    step.close();
    var order = inOrder(stream, plan);
    order.verify(stream, times(1)).close(ctx); // close() does not re-close the released stream
    order.verify(plan, times(1)).close();
  }

  /**
   * Empty stream (zero rows): the first {@code processNextStart()} probes {@code hasNext} once, gets
   * false, closes the arming's stream, and throws. This is the canonical "no matches" case (e.g.
   * {@code g.V(missingId)}). The row is never pulled; the plan is closed by the explicit
   * {@code close()}, not by exhaustion.
   */
  @Test
  public void processNextStart_emptyStream_closesImmediately() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    // Exhaustion closes the stream but leaves the plan open; close() closes the plan.
    verify(stream, times(1)).close(ctx);
    verify(plan, never()).close();
    verify(stream, never()).next(ctx);

    step.close();
    verify(plan, times(1)).close();
  }

  /**
   * Single-row stream: stresses the exhaustion/close trigger immediately after the first row.
   * Off-by-one regressions where exhaustion is detected on the wrong boundary would surface here but
   * not in the multi-row test. Exhaustion closes the stream; the plan is closed by {@code close()}.
   */
  @Test
  public void processNextStart_singleRow_emitsThenCloses() {
    var row = mock(Result.class);
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    when(stream.hasNext(ctx)).thenReturn(true, false);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");

    var only = step.processNextStart().get();
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(step::processNextStart); // exhaustion → stream close

    assertThat(assertRawEntityOf(only)).isSameAs(rawVertex);
    verify(stream, times(1)).close(ctx);
    verify(plan, never()).close();

    step.close();
    verify(plan, times(1)).close();
  }

  /**
   * Calling {@code processNextStart()} again past exhaustion keeps throwing {@link
   * NoSuchElementException} and does NOT re-probe or re-open the stream: the {@code done} guard
   * short-circuits, so {@code hasNext} is probed exactly once (on the exhausting call) and the plan
   * is started exactly once.
   */
  @Test
  public void processNextStart_pastExhaustion_keepsThrowingWithoutReopening() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    verify(stream, times(1)).hasNext(ctx);
    verify(stream, never()).next(ctx);
    verify(plan, times(1)).start();
  }

  /**
   * When the host traversal has no attached graph, opening throws {@link IllegalStateException} —
   * deliberately NOT {@link NoSuchElementException}, which {@code AbstractStep.hasNext()} swallows as
   * end-of-iteration and would turn a genuine "no graph" bug into a silent empty result. Because
   * graph resolution happens before {@code plan.start()}, neither the stream nor the plan was opened,
   * so nothing leaks. This locks the resolve-before-open ordering.
   */
  @Test
  public void processNextStart_traversalHasNoGraph_throwsBeforeOpeningPlan() {
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

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("no attached graph");

    verify(plan, never()).start();
    verify(stream, never()).close(ctx);
    verify(plan, never()).close();
  }

  // ---- Projection helper ----

  /**
   * The projection helper returns null when the row does not bind the boundary alias to a vertex.
   * This is the contract for optional matches that did not yield a binding — downstream native steps
   * treat a null payload the same as any other absence.
   */
  @Test
  public void projectElement_missingAlias_returnsNull() {
    var row = mock(Result.class);
    when(row.getVertex("missing")).thenReturn(null);

    var step = elementStep("missing");

    assertThat(step.projectElement(row, graph)).isNull();
  }

  /**
   * The projection helper looks the alias up under the exact name the step was configured with — not
   * a fixed default — and each wrapper wraps the raw vertex resolved under that step's alias.
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

    // Each wrapper points at the raw vertex its alias resolved to — swapping the aliases between
    // stepA / stepB would fail here.
    assertThat(assertRawEntityOf(resultA)).isSameAs(vertexUnderA);
    assertThat(assertRawEntityOf(resultB)).isSameAs(vertexUnderB);
    verify(row).getVertex("a");
    verify(row).getVertex("b");
  }

  /**
   * Projection dispatches on the configured {@code returnClass}. An edge boundary is reserved but
   * not yet translated, so projecting one throws {@link UnsupportedOperationException} rather than
   * silently emitting a vertex. This pins that only vertex projection is wired in the current scope
   * and documents the arm the edge track later fills in; the message names the offending class.
   */
  @Test
  public void projectElement_edgeReturnClass_throwsUnsupportedUntilEdgeTrack() {
    var row = mock(Result.class);
    var step =
        new YTDBMatchPlanStep(traversal, Edge.class, plan, "e", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> step.projectElement(row, graph))
        .withMessageContaining("edge projection");
  }

  /**
   * A return class that is neither a {@link Vertex} nor an {@link Edge} subtype is a translator bug,
   * not a runtime condition, so projection fails fast with {@link IllegalStateException} naming the
   * offending class. {@code Element.class} is neither subtype, so it exercises the fall-through arm.
   */
  @Test
  public void projectElement_nonVertexNonEdgeReturnClass_throwsIllegalState() {
    var row = mock(Result.class);
    var step =
        new YTDBMatchPlanStep(traversal, Element.class, plan, "x", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> step.projectElement(row, graph))
        .withMessageContaining("Vertex or Edge");
  }

  // ---- Close lifecycle ----

  /**
   * Explicit {@code close()} (modelling early termination by a downstream {@code LimitStep}) closes
   * both the stream and the plan even when the stream still has rows queued. Stream-close runs
   * before plan-close so the last step can flush buffered state before the plan releases resources.
   */
  @Test
  public void close_earlyTermination_closesStreamThenPlan() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true); // never exhausts on its own
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");
    step.processNextStart(); // open the stream, pull one element

    step.close();

    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();
  }

  /**
   * {@code close()} is idempotent: a second call after the first is a no-op (the {@code closed}
   * guard), so the stream and plan are each closed exactly once.
   */
  @Test
  public void close_isIdempotent() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");
    step.processNextStart(); // open

    step.close();
    step.close(); // second close must be a no-op

    verify(stream, times(1)).close(ctx);
    verify(plan, times(1)).close();
  }

  /**
   * If {@code stream.close} throws, the plan is still closed. The close path uses try/catch to
   * guarantee plan.close runs regardless — without it, every stream-close failure would leak the
   * plan's resources. Stream-close runs first and its exception propagates.
   */
  @Test
  public void close_streamCloseThrows_planStillClosed() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);
    doThrow(new RuntimeException("stream close failed")).when(stream).close(ctx);

    var step = elementStep("v");
    step.processNextStart(); // open

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::close)
        .withMessageContaining("stream close failed");

    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();
  }

  /**
   * When BOTH {@code stream.close} and {@code plan.close} throw during close, the stream-close
   * failure is the primary exception and the plan-close failure is attached via {@code addSuppressed}
   * — the plan-close exception must not mask the stream error the operator needs to see.
   */
  @Test
  public void close_bothStreamAndPlanCloseThrow_streamErrorPrimary_planErrorSuppressed() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);
    doThrow(new RuntimeException("stream close failed")).when(stream).close(ctx);
    doThrow(new RuntimeException("plan close failed")).when(plan).close();

    var step = elementStep("v");
    step.processNextStart(); // open

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::close)
        .withMessageContaining("stream close failed")
        .satisfies(
            e -> assertThat(e.getSuppressed())
                .anySatisfy(s -> assertThat(s).hasMessageContaining("plan close failed")));

    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();
  }

  /**
   * When {@code plan.start()} itself throws (e.g. an inner step's {@code internalStart} fails after
   * opening cursors), the step closes the plan before propagating so the cursors don't leak. The
   * graph was resolved before the throw, but no stream exists yet, so only the plan is closed.
   */
  @Test
  public void processNextStart_planStartThrows_closesPlanAndPropagates() {
    when(plan.start()).thenThrow(new RuntimeException("plan start blew up"));

    var step = elementStep("v");

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("plan start blew up");

    verify(plan, times(1)).close();
    verify(stream, never()).close(ctx); // no stream was opened
  }

  /**
   * A failure while iterating the open stream (here {@code stream.next()} throws mid-iteration) must
   * close the stream and then the plan before propagating, so a stream that blew up part-way does
   * not leak until traversal teardown. This is the design's boundary-step lifecycle contract for
   * exceptions during iteration. Also asserts the failed arming is terminal: a subsequent {@code
   * processNextStart()} ends (throws {@link NoSuchElementException}) without re-opening the
   * already-closed plan, proving {@code done} was set on the failure path.
   */
  @Test
  public void processNextStart_streamThrowsMidIteration_closesStreamThenPlan_andArmingIsTerminal() {
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenThrow(new RuntimeException("stream blew up mid-iteration"));

    var step = elementStep("v");

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("stream blew up mid-iteration");

    // The failing arming released the stream first, then the plan.
    InOrder order = inOrder(stream, plan);
    order.verify(stream).close(ctx);
    order.verify(plan).close();

    // Terminal: the next pull ends without re-opening the closed plan (started exactly once).
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    verify(plan, times(1)).start();
  }

  /**
   * When iteration throws AND the release triggered by that failure also throws (here {@code
   * stream.next()} blows up and {@code stream.close()} then fails during release), the iteration
   * failure stays the primary exception and the release failure is attached via {@code
   * addSuppressed}. This exercises the {@code addSuppressed} arm of {@code processNextStart}'s
   * own catch block — a distinct site from the {@code close()} both-throw path, which suppresses a
   * plan-close error onto a stream-close error rather than onto an iteration error.
   */
  @Test
  public void processNextStart_streamThrows_andReleaseAlsoThrows_suppressesReleaseError() {
    when(stream.hasNext(ctx)).thenReturn(true);
    when(stream.next(ctx)).thenThrow(new RuntimeException("iteration blew up"));
    doThrow(new RuntimeException("stream close failed")).when(stream).close(ctx);

    var step = elementStep("v");

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("iteration blew up")
        .satisfies(
            e -> assertThat(e.getSuppressed())
                .anySatisfy(s -> assertThat(s).hasMessageContaining("stream close failed")));
  }

  // ---- Clone semantics ----

  /**
   * {@link YTDBMatchPlanStep#clone()} gives the clone its OWN deep {@code copy()} of the plan — it
   * does NOT share the original's plan instance. A {@code SelectExecutionPlan}-family plan carries
   * mutable per-run state and must be copied before each independent execution (mirroring {@code
   * HashJoinMatchStep}). The clone is a distinct instance and preserves the alias / output-type
   * configuration. Neither plan is started merely by cloning.
   */
  @Test
  public void clone_copiesPlan_forIndependentExecution() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    when(plan.copy(any())).thenReturn(copiedPlan);

    var original = elementStep("v");
    var cloned = original.clone();

    assertThat(cloned).isNotSameAs(original);
    assertThat(cloned.getPlan()).isSameAs(copiedPlan);
    assertThat(cloned.getPlan()).isNotSameAs(original.getPlan());
    assertThat(original.getPlan()).isSameAs(plan);
    verify(plan, never()).start();
    verify(copiedPlan, never()).start();
    assertThat(cloned.getBoundaryAlias()).isEqualTo("v");
    assertThat(cloned.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * The clone's plan copy must be taken against an ISOLATED CHILD context — a fresh {@link
   * BasicCommandContext} parented to the original plan's context — not against the original context
   * itself. This mirrors {@code HashJoinMatchStep}: the child owns its own unsynchronised variable
   * maps ({@code $current}, {@code $matched}, statistics), so the original's and the clone's
   * executions cannot race on or leak that per-run state. Copying against the shared context would
   * leave both plans on the SAME context.
   */
  @Test
  public void clone_copiesPlanAgainstIsolatedChildContext() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    var contextCaptor = ArgumentCaptor.forClass(CommandContext.class);
    when(plan.copy(contextCaptor.capture())).thenReturn(copiedPlan);

    var original = elementStep("v");
    original.clone();

    var copyContext = contextCaptor.getValue();
    assertThat(copyContext).isNotSameAs(ctx);
    assertThat(copyContext).isInstanceOf(BasicCommandContext.class);
    assertThat(copyContext.getParent()).isSameAs(ctx);
  }

  /**
   * Behavioural clone independence: when both the original and the clone are driven, each starts its
   * OWN plan (the original starts {@code plan}; the clone starts its {@code copy()}). This is what
   * {@code plan.copy()} in {@code clone()} buys — two independent executions with no shared mutable
   * plan state. A regression that shared the plan would drive both sides through the same instance.
   *
   * <p>Note: {@code AbstractStep.clone()} detaches the clone's traversal to {@code
   * EmptyTraversal.instance()} until the host re-attaches it. The test re-attaches manually via
   * {@code setTraversal} so the clone can resolve the graph the way it would in production.
   */
  @Test
  public void clone_startsOwnPlanCopyIndependentlyOfOriginal() {
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
    cloned.setTraversal(traversal);

    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(original::processNextStart);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(cloned::processNextStart);

    // Each side started exactly its own plan once — proving the executions are independent.
    verify(plan, times(1)).start();
    verify(copiedPlan, times(1)).start();
  }

  /**
   * Concurrency guard for clone independence under real interleaving. The sequential clone tests pin
   * the object-graph shape (distinct plan copy, isolated child context); this one drives two clones
   * on two threads a {@link CyclicBarrier} releases together, so their open / start / close paths
   * overlap. {@code clone()} deep-copies the plan against an isolated child {@link CommandContext} so
   * two concurrent executions cannot race on per-run context state (the {@code $current} / {@code
   * $matched} maps are plain HashMaps). Each clone gets its own plan-copy mock, so a regression that
   * re-shared one plan — or minted one shared child context — shows up here as a wrong per-plan start
   * count or a captured-context collision, not a heisenbug under load.
   */
  @Test
  public void clone_twoClonesDrivenConcurrently_eachRunsOwnPlanCopy() throws Exception {
    var copyA = mock(InternalExecutionPlan.class);
    var copyB = mock(InternalExecutionPlan.class);
    var ctxA = mock(CommandContext.class);
    var ctxB = mock(CommandContext.class);
    var streamA = mock(ExecutionStream.class);
    var streamB = mock(ExecutionStream.class);
    // Two clone() calls make two plan.copy() calls; hand each clone its own copy so the concurrent
    // runs touch disjoint mocks. Capture the child contexts to prove two distinct instances.
    var childCtxCaptor = ArgumentCaptor.forClass(CommandContext.class);
    when(plan.copy(childCtxCaptor.capture())).thenReturn(copyA, copyB);
    when(copyA.getContext()).thenReturn(ctxA);
    when(copyB.getContext()).thenReturn(ctxB);
    when(copyA.start()).thenReturn(streamA);
    when(copyB.start()).thenReturn(streamB);
    when(streamA.hasNext(ctxA)).thenReturn(false);
    when(streamB.hasNext(ctxB)).thenReturn(false);

    var original = elementStep("v");
    var cloneA = original.clone();
    var cloneB = original.clone();
    cloneA.setTraversal(traversal);
    cloneB.setTraversal(traversal);

    var childContexts = childCtxCaptor.getAllValues();
    assertThat(childContexts).hasSize(2);
    assertThat(childContexts.get(0)).isNotSameAs(childContexts.get(1));

    var barrier = new CyclicBarrier(2);
    var errors = new CopyOnWriteArrayList<Throwable>();
    Runnable driveA =
        () -> {
          try {
            barrier.await();
            cloneA.forEachRemaining(t -> {
            });
          } catch (Throwable t) {
            errors.add(t);
          }
        };
    Runnable driveB =
        () -> {
          try {
            barrier.await();
            cloneB.forEachRemaining(t -> {
            });
          } catch (Throwable t) {
            errors.add(t);
          }
        };
    var tA = new Thread(driveA, "cloneA-driver");
    var tB = new Thread(driveB, "cloneB-driver");
    tA.start();
    tB.start();
    tA.join(5_000);
    tB.join(5_000);

    // A timed join returns whether the thread finished OR the timeout elapsed. Assert both threads
    // actually terminated: a regression that made forEachRemaining hang would otherwise leave the
    // errors list empty (the thread is stuck, not throwing) and leak two live threads into the
    // shared surefire fork while the test still went green. A completed join is also what
    // establishes the happens-before edge the verify() calls below rely on.
    assertThat(tA.isAlive()).as("driver A must terminate, not hang").isFalse();
    assertThat(tB.isAlive()).as("driver B must terminate, not hang").isFalse();

    assertThat(errors).as("no driver thread threw during concurrent iteration").isEmpty();
    verify(copyA, times(1)).start();
    verify(copyB, times(1)).start();
    verify(plan, never()).start();
  }

  // ---- Re-iteration via reset() ----

  /**
   * After {@code reset()} the step is re-armed and {@code processNextStart()} may open the plan again
   * on the SAME instance — honouring TinkerPop's reset contract (reset makes a start step
   * re-iterable). This drives one (empty) arming, resets, and drives a second — asserting the second
   * open re-starts the plan and rewinds it via {@code plan.reset(ctx)} first (the plan's step chain
   * must be rewound before re-execution).
   */
  @Test
  public void reset_thenProcessNextStart_reRunsPlanOnSameInstance() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(step::processNextStart); // arming 1
    step.reset();
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(step::processNextStart); // arming 2

    verify(plan, times(2)).start();
    verify(plan, times(1)).reset(ctx);
  }

  /**
   * The session rebind runs on every arming, not only the first. TinkerPop may {@code reset()} a
   * start step and re-iterate it on a different thread (traversal reuse), so a second open must
   * re-resolve and re-push the thread-active session before the second {@code plan.start()}. A
   * refactor that rebound only once would reintroduce the remote-path {@code
   * SessionNotActivatedException} on the re-run.
   */
  @Test
  public void reset_thenProcessNextStart_rebindsSessionAgainBeforeSecondStart() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    // Arming 1: the empty stream makes the first open exhaust and throw; the rebind already ran.
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    step.reset();
    // Arming 2: a fresh open must rebind again before the second start.
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    verify(tx, times(2)).readWrite();
    verify(ctx, times(2)).setDatabaseSession(threadSession);
  }

  /**
   * A never-started step that is reset (e.g. TinkerPop resets a start step before any iteration)
   * must NOT call {@code plan.reset(ctx)} on its first open — there is no consumed state to rewind.
   * This pins the {@code everStarted} guard: the plan is rewound only when re-arming a plan that
   * actually ran.
   */
  @Test
  public void reset_beforeFirstIteration_doesNotRewindPlanOnFirstOpen() {
    when(stream.hasNext(ctx)).thenReturn(false);

    var step = elementStep("v");
    step.reset(); // reset before any processNextStart
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    verify(plan, times(1)).start();
    verify(plan, never()).reset(any());
  }

  /**
   * A partially-consumed arming that is {@code reset()} before exhaustion keeps the open stream
   * alive until the next open (deferred close, so the {@code reset()} that {@code
   * AbstractStep.clone()} triggers cannot tear down a still-aliased original stream). The next open
   * closes the stale cursor (stream only — the plan is re-run in place) and rewinds the plan. This
   * asserts: reset itself closes nothing; the second open closes the stale stream once, keeps the
   * plan alive, and re-runs it (rewind + start).
   */
  @Test
  public void reset_afterPartialConsume_deferStreamClose_thenReRunsKeepingPlan() {
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true); // never exhausts → stream left open
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");
    step.processNextStart(); // open, consume one row, stream un-exhausted

    step.reset();

    // reset() defers the close — nothing released yet.
    verify(stream, never()).close(ctx);
    verify(plan, never()).close();

    // Second open closes the stale cursor (stream only) and re-runs the SAME plan (rewind + start).
    step.processNextStart();
    verify(stream, times(1)).close(ctx);
    verify(plan, never()).close();
    verify(plan, times(2)).start();
    verify(plan, times(1)).reset(ctx);
  }

  /**
   * Cloning must not rewind the ORIGINAL's plan. {@code AbstractStep.clone()} invokes {@code
   * reset()} on the freshly-cloned instance while it still aliases the original's plan reference (the
   * clone's own copy is installed later in {@code clone()}). {@code reset()} never rewinds the plan
   * (rewind is deferred to the next open, which only the arming's own instance reaches), so cloning a
   * started original never resets its plan.
   */
  @Test
  public void clone_afterOriginalStarted_doesNotResetOriginalsPlan() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    when(plan.copy(any())).thenReturn(copiedPlan);
    when(stream.hasNext(ctx)).thenReturn(false);

    var original = elementStep("v");
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(original::processNextStart); // original started (everStarted=true)

    original.clone(); // AbstractStep.clone() calls reset() on the clone mid-construction

    verify(plan, never()).reset(any());
  }

  /**
   * Cloning an original that has an OPEN stream must NOT close that stream. {@code
   * AbstractStep.clone()} triggers {@code reset()} on the fresh clone while it still aliases the
   * original's {@code openStream}; the deferred-close design (reset does not close, clone nulls the
   * clone's reference) keeps that reset from tearing down the original's in-flight cursor. This drives
   * the original to an open stream, clones it, and asserts the original's stream was never closed as a
   * side effect.
   */
  @Test
  public void clone_whileOriginalStreamOpen_doesNotCloseOriginalStream() {
    var copiedPlan = mock(InternalExecutionPlan.class);
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    var row = mock(Result.class);
    when(plan.copy(any())).thenReturn(copiedPlan);
    when(stream.hasNext(ctx)).thenReturn(true); // stays open
    when(stream.next(ctx)).thenReturn(row);
    when(row.getVertex("v")).thenReturn(rawVertex);

    var original = elementStep("v");
    original.processNextStart(); // opens the plan → openStream set, un-exhausted

    original.clone(); // AbstractStep.clone() calls reset() on the clone mid-construction

    verify(stream, never()).close(ctx);
  }

  // ---- Clone field-write (no reflection) ----

  /**
   * The clone installs its plan copy via a plain field write, not reflection. The {@code plan} field
   * is non-final precisely so {@code clone()} can assign it directly after {@code super.clone()} —
   * avoiding a post-construction reflective write to a final field, which would void the JMM
   * final-field publication guarantee. This locks the modifier so a change back to {@code final}
   * fails here rather than silently regressing the visibility contract.
   */
  @Test
  public void planField_isNonFinal_soCloneAssignsWithoutReflection() throws Exception {
    Field planField = YTDBMatchPlanStep.class.getDeclaredField("plan");
    assertThat(java.lang.reflect.Modifier.isFinal(planField.getModifiers()))
        .as("plan field must be non-final so clone() can assign the copy without reflection")
        .isFalse();
  }

  // ---- Ordered list-shaping post-process ----

  /**
   * The empty-op case (every traversal that exists today) is a structural bypass, not a no-op stage
   * wrapped around the projection stream: pulling the first traverser advances the underlying stream
   * by exactly one row. This pins first-result latency — a collect-apply-emit stage would drain the
   * whole stream before emitting even with no op, passing every behaviour-neutral assertion while
   * destroying laziness and bounded memory. The stream has three rows available; only the first is
   * pulled.
   */
  @Test
  public void listShaping_emptyOps_firstPullAdvancesStreamByOneRow_notDrained() {
    var row1 = mock(Result.class);
    var row2 = mock(Result.class);
    var row3 = mock(Result.class);
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    when(stream.hasNext(ctx)).thenReturn(true, true, true, false);
    when(stream.next(ctx)).thenReturn(row1, row2, row3);
    when(row1.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v"); // ResultShaping.NONE — empty listShapingOps

    step.processNextStart(); // pull exactly one traverser

    // First result cost one row, not the whole (3-row) stream: the empty-op bypass stays lazy.
    verify(stream, times(1)).next(ctx);
    verify(stream, times(1)).hasNext(ctx);
  }

  /**
   * A parameter-bearing, cardinality-changing op proves the carrier is a stream stage, not a 1→1
   * row-mapper: {@code TagRepeatOp("X", 2)} emits two payloads for every one upstream payload, so a
   * single projected row yields two traversers — a shape no row-mapper could produce. The stage also
   * streams across source rows: with two rows queued, both copies of row 1 are served before row 2 is
   * pulled. This makes the laziness falsifiable — an eager collect-apply-emit stage would drain both
   * rows before emitting anything, so the first-pull source count (one, not two) fails for the eager
   * shape while a single-row stream could not tell the two apart. Driven over the SCALAR path so each
   * projected payload is a deterministic value.
   */
  @Test
  public void listShaping_flatMapOp_expandsEachRowLazily_secondRowNotPulledUntilFirstDrained() {
    var row1 = mock(Result.class);
    var row2 = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true, true, false);
    when(stream.next(ctx)).thenReturn(row1, row2);
    when(row1.getPropertyNames()).thenReturn(List.of("c"));
    when(row1.getProperty("c")).thenReturn(5L);
    when(row2.getPropertyNames()).thenReturn(List.of("c"));
    when(row2.getProperty("c")).thenReturn(6L);

    var step =
        shapedStep(
            "v",
            BoundaryOutputType.SCALAR,
            ResultShaping.NONE.withListShapingOps(List.of(new TagRepeatOp("X", 2))));

    // Read payloads through wildcard traversers so get() returns Object: the payload is a String,
    // not the Vertex the ELEMENT-typed generic would checkcast to.
    Traverser.Admin<?> t1 = step.processNextStart();
    assertThat(t1.get()).isEqualTo("X:5");
    // Lazy across rows: the first copy is served after pulling only row 1; row 2 is untouched.
    verify(stream, times(1)).next(ctx);

    Traverser.Admin<?> t2 = step.processNextStart();
    assertThat(t2.get()).isEqualTo("X:5"); // 2nd copy served from the op buffer — no new source pull
    verify(stream, times(1)).next(ctx);

    Traverser.Admin<?> t3 = step.processNextStart();
    // Only now, with row 1's expansion drained, is row 2 pulled — the stage streamed, not drained.
    assertThat(t3.get()).isEqualTo("X:6");
    verify(stream, times(2)).next(ctx);
  }

  /**
   * Ops apply in declared order, left to right: {@code [A, B]} nests as {@code B:A:…} while the
   * reversed {@code [B, A]} nests as {@code A:B:…}, so the two orderings resolve to observably
   * distinct application sequences. This is why the carrier is an ordered list — order-less flags
   * could not tell {@code reverse().unfold()} from {@code unfold().reverse()}. Driven over the SCALAR
   * path so the projected payload is a deterministic value the tags wrap.
   */
  @Test
  public void listShaping_opsApplyInDeclaredOrder_leftToRight() {
    assertThat(runSingleScalarThroughOps(new TagRepeatOp("A", 1), new TagRepeatOp("B", 1)))
        .isEqualTo("B:A:5");
    assertThat(runSingleScalarThroughOps(new TagRepeatOp("B", 1), new TagRepeatOp("A", 1)))
        .isEqualTo("A:B:5");
  }

  /**
   * The list-shaping stage is the single seam both projection paths reach — including the
   * group-barrier {@code group} / {@code groupCount} path, which drains the stream into one map and
   * returns before per-row projection. A cardinality-changing op over that path expands the single
   * accumulated map into several traversers, so ops registered by {@code fold} / {@code unfold} /
   * {@code reverse} / {@code tail} after a {@code group} would not silently no-op.
   */
  @Test
  public void listShaping_appliesOnGroupBarrierPath() {
    var row = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true, false);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getProperty("key")).thenReturn("k1");
    when(row.getProperty("value")).thenReturn(7L);

    var step =
        shapedStep(
            "v",
            BoundaryOutputType.MAP,
            ResultShaping.NONE.withAccumulateMap(true).withListShapingOps(
                List.of(new TagRepeatOp("G", 2))));

    // Read payloads through wildcard traversers so get() returns Object (the payload is a String).
    Traverser.Admin<?> t1 = step.processNextStart();
    var first = t1.get();
    Traverser.Admin<?> t2 = step.processNextStart();
    var second = t2.get();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    // The one accumulated map became two traversers: the op ran on the group-barrier path. The
    // accumulated map is fully deterministic ({k1=7}), so assert the exact payload on both copies
    // rather than a prefix — a regression that appended trailing content or reshaped the map is
    // caught here.
    assertThat(first).isEqualTo("G:{k1=7}");
    assertThat(second).isEqualTo("G:{k1=7}");
  }

  /**
   * A null payload is a legitimate emission, not an absence: an unmatched optional element projects
   * to a null vertex, and the projection source buffers and emits it rather than skipping it. This
   * drives a null-projecting row ahead of a real-vertex row and asserts the null is emitted first
   * (order preserved) and the real vertex second. The projection source distinguishes "a null is
   * buffered" from "nothing buffered" with a separate has-buffered flag precisely because null is a
   * valid payload; a regression to a null sentinel (treating a null payload as "not buffered") would
   * loop past the null, dropping the optional and shifting the emitted sequence, and this test would
   * catch it.
   */
  @Test
  public void processNextStart_nullPayloadIsEmittedNotDropped_andOrderPreserved() {
    var row1 = mock(Result.class); // optional miss → null vertex
    var row2 = mock(Result.class); // real vertex
    var rawVertex =
        mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
    when(stream.hasNext(ctx)).thenReturn(true, true, false);
    when(stream.next(ctx)).thenReturn(row1, row2);
    when(row1.getVertex("v")).thenReturn(null); // legitimate null payload
    when(row2.getVertex("v")).thenReturn(rawVertex);

    var step = elementStep("v");

    var first = step.processNextStart().get(); // the null payload, not row2
    var second = step.processNextStart().get();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    assertThat(first).isNull(); // null buffered and emitted via the has-buffered flag
    assertThat(assertRawEntityOf(second)).isSameAs(rawVertex);
  }

  /**
   * A partial consume followed by {@code reset()} and reopen rebuilds the shaped iterator fresh
   * against the rewound plan rather than resuming the abandoned arming's op buffer. This consumes one
   * of a buffering op's three copies, resets mid-buffer, then reopens: the post-reset pull must
   * re-project from the stream (a fresh copy), not serve a leftover buffered copy. The stream pull
   * count is the discriminator — a rebuilt iterator re-projects the row (two source pulls across the
   * two armings), a resumed stale iterator would serve its buffer without touching the stream (one).
   * A regression that stopped nulling the shaped iterator on reopen would keep the stale buffer and
   * fail that count.
   */
  @Test
  public void listShaping_resetAfterPartialConsume_rebuildsShapedIteratorFresh() {
    var row = mock(Result.class);
    // One row per arming; the buffering op fans it to three copies, of which the test consumes one.
    when(stream.hasNext(ctx)).thenReturn(true, true, false);
    when(stream.next(ctx)).thenReturn(row);
    when(row.getPropertyNames()).thenReturn(List.of("c"));
    when(row.getProperty("c")).thenReturn(5L);

    var step =
        shapedStep(
            "v",
            BoundaryOutputType.SCALAR,
            ResultShaping.NONE.withListShapingOps(List.of(new TagRepeatOp("X", 3))));

    // Wildcard traverser so get() returns Object (the payload is a String).
    Traverser.Admin<?> t1 = step.processNextStart();
    assertThat(t1.get()).isEqualTo("X:5"); // 1 of 3; two copies left in the op buffer
    step.reset(); // arming abandoned mid-buffer

    Traverser.Admin<?> t2 = step.processNextStart();
    assertThat(t2.get()).isEqualTo("X:5"); // fresh projection, not the leftover buffered copy
    verify(stream, times(2)).next(ctx); // rebuilt re-projects (2); a resumed stale buffer would be 1
    verify(plan, times(2)).start();
    verify(plan, times(1)).reset(ctx);
  }

  /**
   * An N→1 window-drain op — the cardinality class {@code fold} and {@code tail} use — collapses many
   * source rows into one bounded payload. The op eagerly consumes the whole projection stream inside
   * {@code processNextStart}'s try (the same eager-drain-inside-try path {@code group} / {@code
   * groupCount} take) and emits its single result; a second pull exhausts. This exercises the drain
   * class through the boundary base, which {@code TagRepeatOp} (1→1 and 1→N only) never reaches.
   */
  @Test
  public void listShaping_drainOp_manyRowsCollapseToOnePayload() {
    var r1 = mock(Result.class);
    var r2 = mock(Result.class);
    var r3 = mock(Result.class);
    when(stream.hasNext(ctx)).thenReturn(true, true, true, false);
    when(stream.next(ctx)).thenReturn(r1, r2, r3);
    for (var r : List.of(r1, r2, r3)) {
      when(r.getPropertyNames()).thenReturn(List.of("c"));
      when(r.getProperty("c")).thenReturn(1L);
    }

    var step =
        shapedStep(
            "v",
            BoundaryOutputType.SCALAR,
            ResultShaping.NONE.withListShapingOps(List.of(new DrainToSizeOp())));

    // Wildcard traverser so get() returns Object (the drained payload is a Long count).
    Traverser.Admin<?> t = step.processNextStart();
    assertThat(t.get()).isEqualTo(3L); // three rows drained into one payload
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    verify(stream, times(3)).next(ctx); // the whole stream was consumed to produce the one payload
  }

  /**
   * The seven pre-existing {@code withX} builders each thread {@code listShapingOps} through as the
   * new constructor argument, so a previously-set op list must survive every one of them. This
   * asserts each builder carries a pre-set ops list forward unchanged — a copy-paste that passed an
   * empty list instead of the field would silently reset the ops and corrupt a traversal that pins
   * ops before a later flag override.
   */
  @Test
  public void resultShaping_withBuilders_preserveListShapingOps() {
    var ops = List.<ListShapingOp>of(new TagRepeatOp("X", 1));
    var base = ResultShaping.NONE.withListShapingOps(ops);

    assertThat(base.withDropNullRows(true).listShapingOps()).isEqualTo(ops);
    assertThat(base.withDropOnAbsent(true).listShapingOps()).isEqualTo(ops);
    assertThat(base.withPresencePropertyKeys(List.of("k")).listShapingOps()).isEqualTo(ops);
    assertThat(base.withWrapMapValuesInLists(true).listShapingOps()).isEqualTo(ops);
    assertThat(base.withAccumulateMap(true).listShapingOps()).isEqualTo(ops);
    assertThat(base.withUnwrapSingletonMap(true).listShapingOps()).isEqualTo(ops);
    assertThat(base.withElementMapTokens(true).listShapingOps()).isEqualTo(ops);
  }

  /**
   * Drives a single deterministic SCALAR payload ({@code 5}) through the given ops in order and
   * returns the sole emitted payload as a string. Used to assert declared-order composition without
   * a real graph.
   */
  private String runSingleScalarThroughOps(ListShapingOp... ops) {
    var localPlan = mock(InternalExecutionPlan.class);
    var localCtx = mock(CommandContext.class);
    var localStream = mock(ExecutionStream.class);
    lenient().when(localPlan.getContext()).thenReturn(localCtx);
    lenient().when(localPlan.start()).thenReturn(localStream);
    var row = mock(Result.class);
    when(localStream.hasNext(localCtx)).thenReturn(true, false);
    when(localStream.next(localCtx)).thenReturn(row);
    when(row.getPropertyNames()).thenReturn(List.of("c"));
    when(row.getProperty("c")).thenReturn(5L);

    var step =
        new YTDBMatchPlanStep<>(
            traversal,
            Vertex.class,
            localPlan,
            "v",
            BoundaryOutputType.SCALAR,
            java.util.Map.of(),
            ResultShaping.NONE.withListShapingOps(List.of(ops)));

    var payloads = new ArrayList<>();
    step.forEachRemaining(t -> payloads.add(t.get()));
    assertThat(payloads).hasSize(1);
    return String.valueOf(payloads.getFirst());
  }

  /**
   * Test-only placeholder {@link ListShapingOp}: a parameter-bearing, cardinality-changing stream
   * stage. For each upstream payload {@code p} it emits {@code times} copies of {@code tag + ":" +
   * p}, so it is a 1→N flat-map that no 1→1 row-mapper could express. It stays lazy — {@code
   * hasNext} pulls at most one upstream payload to refill its buffer — so it exercises the
   * framework's laziness as well as its cardinality contract. The real {@code fold} / {@code unfold}
   * / {@code reverse} / {@code tail} ops replace it later; the framework carries them unchanged.
   */
  private record TagRepeatOp(String tag, int times) implements ListShapingOp {

    @Override
    public Iterator<Object> apply(Iterator<Object> upstream) {
      return new Iterator<>() {
        private final ArrayDeque<Object> buffer = new ArrayDeque<>();

        @Override
        public boolean hasNext() {
          while (buffer.isEmpty() && upstream.hasNext()) {
            var payload = upstream.next();
            for (int i = 0; i < times; i++) {
              buffer.add(tag + ":" + payload);
            }
          }
          return !buffer.isEmpty();
        }

        @Override
        public Object next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          return buffer.removeFirst();
        }
      };
    }
  }

  /**
   * Test-only placeholder {@link ListShapingOp} in the N→1 window-drain cardinality class — the class
   * {@code fold} / {@code tail} use. It eagerly consumes the whole upstream on the first {@code
   * hasNext}, then emits a single payload: the count of upstream payloads it drained. Modelling the
   * eager drain lets that cardinality class be exercised through the boundary base without the real
   * {@code fold} / {@code tail} ops, which arrive later.
   */
  private record DrainToSizeOp() implements ListShapingOp {

    @Override
    public Iterator<Object> apply(Iterator<Object> upstream) {
      return new Iterator<>() {
        // null until the drain runs; then false while the single payload is pending, true once served.
        private Boolean emitted;
        private Object value;

        @Override
        public boolean hasNext() {
          if (emitted == null) {
            long count = 0;
            while (upstream.hasNext()) {
              upstream.next();
              count++;
            }
            value = count;
            emitted = false;
          }
          return !emitted;
        }

        @Override
        public Object next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          emitted = true;
          return value;
        }
      };
    }
  }

  // ---- Test helpers ----

  private YTDBMatchPlanStep<Object, Vertex> elementStep(String alias) {
    return new YTDBMatchPlanStep<>(
        traversal, Vertex.class, plan, alias, BoundaryOutputType.ELEMENT);
  }

  private YTDBMatchPlanStep<Object, Vertex> shapedStep(
      String alias, BoundaryOutputType outputType, ResultShaping shaping) {
    return new YTDBMatchPlanStep<>(
        traversal, Vertex.class, plan, alias, outputType, java.util.Map.of(), shaping);
  }

  /**
   * Asserts the projected element is a {@link YTDBVertexImpl}, then reflectively reads and returns
   * its {@code fastPathEntity} field: the raw YTDB entity the wrapper was built from. Comparing that
   * entity by identity verifies a projected wrapper wraps the right raw vertex without a real graph.
   * The {@code assert} prefix marks that the helper fails the test when the element is not a {@code
   * YTDBVertexImpl}, so its embedded type check does not read as a plain accessor.
   */
  private static Object assertRawEntityOf(Object tinkerVertex) {
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
    lenient().when(traversal.getGraph()).thenReturn(Optional.of(graph));
    lenient()
        .when(traversal.getTraverserGenerator())
        .thenReturn(B_O_TraverserGenerator.instance());
    // AbstractStep's ctor calls traversal.getTraverserSetSupplier().get() to initialise its
    // starts/ends sets; supply a real empty TraverserSet so the super-ctor does not NPE on the
    // mock's default-null return. Typed Supplier so signature changes surface at compile time.
    Supplier<TraverserSet<Object>> traverserSetSupplier = TraverserSet::new;
    lenient().when(traversal.getTraverserSetSupplier()).thenReturn(traverserSetSupplier);
    return traversal;
  }
}
