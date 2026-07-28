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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.B_O_TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link MultiPlanMatchStep}, the N-plan boundary step that concatenates one MATCH
 * plan per {@code union(...)} child. Each child plan is a mocked {@link InternalExecutionPlan} whose
 * {@code start()} returns a {@link ListStream} test double — a real, non-mock {@link ExecutionStream}
 * that delivers a fixed row list and records the {@link CommandContext} it was iterated and closed
 * against. That lets the tests exercise the true {@code MultipleExecutionStream} concatenation logic
 * (rather than a brittle mock {@code hasNext} sequence) while still asserting, from the recorded
 * context, that each child iterates and closes against its OWN context — the {@code ChildContextStream}
 * fidelity guarantee — not the coordinator context the base threads down.
 *
 * <p>Iteration is driven through the base's package-visible {@code processNextStart()}, which opens
 * the concatenated stream on first call, projects one row per call, and throws {@link
 * NoSuchElementException} once every child is drained. The plan is closed by {@code close()}, which
 * TinkerPop fires on exhaustion; these tests call it explicitly because they drive {@code
 * processNextStart()} directly.
 *
 * <p>The graph is mocked and the session-rebind chain ({@code graph.tx()} → {@code readWrite()} →
 * {@code getDatabaseSession()}) is stubbed exactly as in {@code YTDBMatchPlanStepTest}, so iteration
 * exercises the real per-arming rebind — here propagated to each child's own context by the producer.
 * End-to-end correctness against a real graph and real plans is covered by the union recogniser's
 * integration tests once the recogniser lands.
 */
@SuppressWarnings({"unchecked", "rawtypes", "resource"})
public class MultiPlanMatchStepTest {

  private YTDBGraphInternal graph;
  private YTDBTransaction tx;
  private DatabaseSessionEmbedded threadSession;
  private Traversal.Admin<Object, Vertex> traversal;

  @Before
  public void setUp() {
    graph = mock(YTDBGraphInternal.class);
    traversal = freshTraversal(graph);
    // Session-rebind chain the base's openArming() drives on every arming: graph.tx() →
    // YTDBTransaction, readWrite() a no-op, getDatabaseSession() the thread-active session. The base
    // pushes this session onto the coordinator context; the producer then propagates it to each
    // child's own context before opening the child.
    tx = mock(YTDBTransaction.class);
    threadSession = mock(DatabaseSessionEmbedded.class);
    lenient().when(graph.tx()).thenReturn(tx);
    lenient().when(tx.getDatabaseSession()).thenReturn(threadSession);
  }

  // ---- Concatenation & one-live-stream ----

  /**
   * The core union contract: {@code union(c1, c2)} emits every row of the first child, then every
   * row of the second, in order — the concatenated multiset {@code |c1| + |c2|}, never a cartesian
   * product. Two rows from child one and one from child two yield exactly three traversers wrapping
   * the three raw vertices in declared order; a fourth pull exhausts.
   */
  @Test
  public void processNextStart_concatenatesChildrenInOrder_multisetIsSum() {
    var raw1 = rawVertex();
    var raw2 = rawVertex();
    var raw3 = rawVertex();
    var c1 = child(ListStream.of(vertexRow(raw1), vertexRow(raw2)));
    var c2 = child(ListStream.of(vertexRow(raw3)));

    var step = elementStep(c1, c2);

    var first = step.processNextStart().get();
    var second = step.processNextStart().get();
    var third = step.processNextStart().get();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

    assertThat(rawEntityOf(first)).isSameAs(raw1);
    assertThat(rawEntityOf(second)).isSameAs(raw2);
    assertThat(rawEntityOf(third)).isSameAs(raw3);
    // Each child ran exactly once, in order.
    verify(c1.plan, times(1)).start();
    verify(c2.plan, times(1)).start();
  }

  /**
   * One live child stream at a time: the second child's plan is not started until the first child
   * has drained. After the first (and only) row of child one is pulled, child two has not been
   * opened; the next pull drains child one, closes its stream, then opens child two. This is the
   * property that makes an exception in an earlier child stop the advance before a later child ever
   * starts, and that bounds the step's footprint to a single open plan.
   */
  @Test
  public void processNextStart_opensChildLazily_secondChildNotStartedUntilFirstDrains() {
    var raw1 = rawVertex();
    var raw2 = rawVertex();
    var c1 = child(ListStream.of(vertexRow(raw1)));
    var c2 = child(ListStream.of(vertexRow(raw2)));

    var step = elementStep(c1, c2);

    step.processNextStart(); // pull child one's only row
    // Child two is still closed: lazy open means it starts only when child one drains.
    verify(c2.plan, never()).start();
    assertThat(c2.stream.startedIterating()).isFalse();

    step.processNextStart(); // drains child one, opens child two, pulls its row
    verify(c2.plan, times(1)).start();

    // Child one's stream was closed before child two opened — never two live streams at once.
    var order = inOrder(c1.plan, c2.plan);
    order.verify(c1.plan).start();
    order.verify(c2.plan).start();
    assertThat(c1.stream.closeCount()).isGreaterThanOrEqualTo(1);
  }

  /**
   * Each child iterates and closes against its OWN context — the {@code ChildContextStream} guarantee
   * that keeps every child byte-identical to the single-plan path. The recorded iteration context on
   * each child's stream is that child's own context, not the coordinator context {@code
   * MultipleExecutionStream} threads through the concatenator. A regression that dropped the wrapper
   * would record the coordinator context here.
   */
  @Test
  public void processNextStart_iteratesEachChildAgainstItsOwnContext() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    });

    assertThat(c1.stream.lastIterationContext()).isSameAs(c1.ctx);
    assertThat(c2.stream.lastIterationContext()).isSameAs(c2.ctx);
    assertThat(c1.stream.lastCloseContext()).isSameAs(c1.ctx);
    assertThat(c2.stream.lastCloseContext()).isSameAs(c2.ctx);
  }

  // ---- Per-child session rebind & positional parameters ----

  /**
   * The producer rebinds each child's OWN context to the iteration-thread session before opening it —
   * the per-child equivalent of the single-plan {@code openArming()} rebind. Both children get the
   * thread session pushed onto their own context, so a child compiled on another thread reads records
   * against the active session instead of throwing {@code SessionNotActivatedException}.
   */
  @Test
  public void processNextStart_rebindsThreadSessionOntoEachChildContext_beforeStart() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    });

    verify(c1.ctx).setDatabaseSession(threadSession);
    verify(c2.ctx).setDatabaseSession(threadSession);
  }

  /**
   * Positional-parameter isolation: the step installs NO parameter map onto any child (union keeps
   * each child's {@code ?}-slot values on that child's own context, set at build time), and it hands
   * the base an empty parameter map so the base never pushes a shared map down either. Two children
   * with different parameter values therefore never collide through this step. Asserts the negative
   * (no {@code setInputParameters} on either child) plus the empty base map read reflectively.
   */
  @Test
  public void step_installsNoParametersOntoChildren_andBaseParameterMapIsEmpty() throws Exception {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    });

    verify(c1.ctx, never()).setInputParameters(any());
    verify(c2.ctx, never()).setInputParameters(any());

    Field paramField = AbstractMatchPlanStep.class.getDeclaredField("inputParameters");
    paramField.setAccessible(true);
    var baseParams = (Map<Object, Object>) paramField.get(step);
    assertThat(baseParams)
        .as("union hands the base an empty parameter map; per-child params live on child contexts")
        .isEmpty();
  }

  // ---- Exception stops the advance ----

  /**
   * An exception while iterating the first child stops the advance: the later children are never
   * started, every child plan (including the un-started ones) is closed, and the original iteration
   * exception stays primary. This is the union realization of the boundary step's terminal-failure
   * contract — the concatenator opens child {@code i+1} only after child {@code i} drains, so a throw
   * in child one means children two and three never open, yet their plans still get closed.
   */
  @Test
  public void
      processNextStart_firstChildThrows_laterChildrenNeverStarted_allClosed_originalPrimary() {
    var c1 = child(ListStream.throwing(new RuntimeException("child one blew up")));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));
    var c3 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2, c3);

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("child one blew up");

    // Later children never opened — the advance stopped at the failing child.
    verify(c2.plan, never()).start();
    verify(c3.plan, never()).start();
    // Every child plan is closed, including the two that never ran.
    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
    verify(c3.plan, times(1)).close();
  }

  /**
   * When iteration throws AND a child's {@code close()} throws during the terminal release, the
   * iteration failure stays primary and the close failure attaches via {@code addSuppressed} — the
   * close error must not mask the iteration error the operator needs. Here child one's iteration
   * fails and the un-run child two's close fails; the primary is the iteration error, child two's
   * close error is suppressed onto it, and every child is still closed.
   */
  @Test
  public void
      processNextStart_iterationThrows_andChildCloseThrows_iterationPrimary_closeSuppressed() {
    var c1 = child(ListStream.throwing(new RuntimeException("iteration blew up")));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));
    var c3 = child(ListStream.of(vertexRow(rawVertex())));
    doThrow(new RuntimeException("child two close failed")).when(c2.plan).close();

    var step = elementStep(c1, c2, c3);

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("iteration blew up")
        .satisfies(
            e -> assertThat(e.getSuppressed())
                .anySatisfy(s -> assertThat(s).hasMessageContaining("child two close failed")));

    // All children were still asked to close despite child two throwing.
    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
    verify(c3.plan, times(1)).close();
  }

  // ---- Close lifecycle ----

  /**
   * On normal exhaustion the arming's concatenated stream is closed but the child plans stay open,
   * so a {@code reset()} + reopen could re-run them; the explicit {@code close()} TinkerPop fires
   * then closes every child plan exactly once. Mirrors the single-plan step's drain-then-close
   * lifecycle, extended to all N children.
   */
  @Test
  public void close_afterNormalDrain_closesEveryChildPlanOnce() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    }); // drain both children to exhaustion

    // Exhaustion closed the streams but left the plans open for a possible reset before close.
    verify(c1.plan, never()).close();
    verify(c2.plan, never()).close();

    step.close();
    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
  }

  /**
   * {@code closePlan} closes EVERY child even when an early child's close throws: the first close
   * failure is primary and later ones attach via {@code addSuppressed}, so one child's failure never
   * leaks the remaining children's resources. Driven through the explicit {@code close()} on a step
   * whose children all ran; child one and child two both fail to close.
   */
  @Test
  public void close_multipleChildCloseFailures_firstPrimary_restSuppressed_allAttempted() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));
    var c3 = child(ListStream.of(vertexRow(rawVertex())));
    doThrow(new RuntimeException("close one failed")).when(c1.plan).close();
    doThrow(new RuntimeException("close two failed")).when(c2.plan).close();

    var step = elementStep(c1, c2, c3);
    step.forEachRemaining(t -> {
    });

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(step::close)
        .withMessageContaining("close one failed")
        .satisfies(
            e -> assertThat(e.getSuppressed())
                .anySatisfy(s -> assertThat(s).hasMessageContaining("close two failed")));

    // Every child was asked to close despite the first two throwing — child three still closed.
    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
    verify(c3.plan, times(1)).close();
  }

  /** {@code close()} is idempotent: a second call closes nothing again. */
  @Test
  public void close_isIdempotent() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    });

    step.close();
    step.close();

    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
  }

  // ---- Re-iteration via reset() ----

  /**
   * After {@code reset()} the step re-arms and re-runs the whole union on the same instance: every
   * child plan is rewound ({@code reset}) once and re-started, and the thread session is re-bound
   * onto each child again. This pins that a re-armed union restarts from the first child with all
   * children rewound, honouring TinkerPop's reset contract for a re-iterable start step.
   */
  @Test
  public void reset_thenProcessNextStart_rewindsAndReRunsEveryChild() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.forEachRemaining(t -> {
    }); // arming one
    step.reset();

    // Re-arm both children so the rewound streams re-deliver their rows.
    c1.stream.rewind();
    c2.stream.rewind();
    step.forEachRemaining(t -> {
    }); // arming two

    verify(c1.plan, times(2)).start();
    verify(c2.plan, times(2)).start();
    verify(c1.plan, times(1)).reset(any());
    verify(c2.plan, times(1)).reset(any());
    // Session rebound again on the second arming (two armings × one rebind per child).
    verify(c1.ctx, times(2)).setDatabaseSession(threadSession);
    verify(c2.ctx, times(2)).setDatabaseSession(threadSession);
  }

  /**
   * A never-started step that is reset (TinkerPop may reset a start step before any iteration) must
   * NOT rewind any child on its first open — there is no consumed state to rewind. Pins the base's
   * NEW-vs-REARMED guard through the multi-plan rewind hook.
   */
  @Test
  public void reset_beforeFirstIteration_doesNotRewindAnyChildOnFirstOpen() {
    var c1 = child(ListStream.of(vertexRow(rawVertex())));
    var c2 = child(ListStream.of(vertexRow(rawVertex())));

    var step = elementStep(c1, c2);
    step.reset(); // reset before any iteration
    step.forEachRemaining(t -> {
    });

    verify(c1.plan, times(1)).start();
    verify(c2.plan, times(1)).start();
    verify(c1.plan, never()).reset(any());
    verify(c2.plan, never()).reset(any());
  }

  // ---- Clone semantics ----

  /**
   * {@link MultiPlanMatchStep#clone()} gives the clone its OWN deep copy of every child plan — it
   * shares none of the originals — and preserves the alias / output-type configuration. Cloning
   * starts no plan. This is the per-child analogue of {@code YTDBMatchPlanStep.clone()}.
   */
  @Test
  public void clone_copiesEveryChildPlan_forIndependentExecution() {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());
    var copy1 = mock(InternalExecutionPlan.class);
    var copy2 = mock(InternalExecutionPlan.class);
    when(c1.plan.copy(any())).thenReturn(copy1);
    when(c2.plan.copy(any())).thenReturn(copy2);

    var original = elementStep(c1, c2);
    var cloned = original.clone();

    assertThat(cloned).isNotSameAs(original);
    assertThat(cloned.getPlans()).containsExactly(copy1, copy2);
    assertThat(original.getPlans()).containsExactly(c1.plan, c2.plan);
    verify(c1.plan, never()).start();
    verify(c2.plan, never()).start();
    verify(copy1, never()).start();
    verify(copy2, never()).start();
    assertThat(cloned.getBoundaryAlias()).isEqualTo("v");
    assertThat(cloned.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * Each child copy is taken against its OWN isolated child context — a fresh {@link
   * BasicCommandContext} parented to that child's original context — not against the shared original
   * context. Two children produce two distinct isolated contexts, each parented to its own child's
   * context. This is what keeps concurrent executions of a clone from racing on any child's per-run
   * variable maps.
   */
  @Test
  public void clone_copiesEachChildAgainstItsOwnIsolatedChildContext() {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());
    var captor1 = ArgumentCaptor.forClass(CommandContext.class);
    var captor2 = ArgumentCaptor.forClass(CommandContext.class);
    when(c1.plan.copy(captor1.capture())).thenReturn(mock(InternalExecutionPlan.class));
    when(c2.plan.copy(captor2.capture())).thenReturn(mock(InternalExecutionPlan.class));

    elementStep(c1, c2).clone();

    var ctx1 = captor1.getValue();
    var ctx2 = captor2.getValue();
    assertThat(ctx1).isInstanceOf(BasicCommandContext.class);
    assertThat(ctx2).isInstanceOf(BasicCommandContext.class);
    assertThat(ctx1).isNotSameAs(ctx2);
    assertThat(ctx1.getParent()).isSameAs(c1.ctx);
    assertThat(ctx2.getParent()).isSameAs(c2.ctx);
  }

  /**
   * Concurrency guard for clone isolation across multi-alias children under real interleaving. Two
   * clones are driven on two threads a {@link CyclicBarrier} releases together, so their open / start
   * / close paths overlap. Each clone deep-copied every child against its own isolated child context
   * and got its own coordinator context, so a regression that re-shared a child plan, minted one
   * shared child context, or shared the coordinator would surface here as a wrong per-copy start
   * count or a live-thread hang — not a heisenbug under load. Each clone's child copies deliver empty
   * streams, so both clones simply drain to exhaustion.
   */
  @Test
  public void clone_twoClonesDrivenConcurrently_eachRunsOwnChildCopies() throws Exception {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());
    // Two clone() calls copy each child twice; hand each clone its own copies so the concurrent runs
    // touch disjoint plan mocks. Each copy delivers an empty stream against its own context.
    var copy1A = emptyCopy();
    var copy1B = emptyCopy();
    var copy2A = emptyCopy();
    var copy2B = emptyCopy();
    when(c1.plan.copy(any())).thenReturn(copy1A.plan, copy1B.plan);
    when(c2.plan.copy(any())).thenReturn(copy2A.plan, copy2B.plan);

    var original = elementStep(c1, c2);
    var cloneA = original.clone();
    var cloneB = original.clone();
    cloneA.setTraversal(traversal);
    cloneB.setTraversal(traversal);

    var barrier = new CyclicBarrier(2);
    var errors = new CopyOnWriteArrayList<Throwable>();
    Runnable driveA = drive(cloneA, barrier, errors);
    Runnable driveB = drive(cloneB, barrier, errors);
    var tA = new Thread(driveA, "union-cloneA");
    var tB = new Thread(driveB, "union-cloneB");
    tA.start();
    tB.start();
    tA.join(5_000);
    tB.join(5_000);

    // Both drivers must terminate, not hang: a timed join returns on either completion or timeout, so
    // a regression that deadlocked forEachRemaining would leave errors empty (stuck, not throwing)
    // and leak live threads while the test still went green. A completed join also establishes the
    // happens-before edge the verify() calls below rely on.
    assertThat(tA.isAlive()).as("driver A must terminate, not hang").isFalse();
    assertThat(tB.isAlive()).as("driver B must terminate, not hang").isFalse();
    assertThat(errors).as("no driver thread threw during concurrent iteration").isEmpty();

    // Clone A ran copyA of each child; clone B ran copyB of each child; the originals never ran.
    verify(copy1A.plan, times(1)).start();
    verify(copy1B.plan, times(1)).start();
    verify(copy2A.plan, times(1)).start();
    verify(copy2B.plan, times(1)).start();
    verify(c1.plan, never()).start();
    verify(c2.plan, never()).start();
  }

  // ---- Constructor validation & field modifiers ----

  /** A union with no children is a recognition-time bug; the constructor rejects an empty plan list. */
  @Test
  public void constructor_emptyPlanList_throwsIllegalArgument() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> new MultiPlanMatchStep<>(
                traversal, Vertex.class, List.of(), "v", BoundaryOutputType.ELEMENT))
        .withMessageContaining("at least one child plan");
  }

  /** {@code getPlans()} returns the child plans in declared order. */
  @Test
  public void getPlans_returnsChildPlansInOrder() {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());
    var c3 = child(ListStream.of());

    assertThat(elementStep(c1, c2, c3).getPlans())
        .containsExactly(c1.plan, c2.plan, c3.plan);
  }

  /**
   * The {@code plans} and {@code coordinatorContext} fields must be non-final so {@code clone()} can
   * install the clone's own copies with a plain field write — a post-{@code super.clone()} reflective
   * write to a final field would void the JMM final-field publication guarantee. Locks both modifiers
   * so a change back to {@code final} fails here rather than silently regressing the visibility
   * contract.
   */
  @Test
  public void cloneFields_areNonFinal_soCloneAssignsWithoutReflection() throws Exception {
    Field plansField = MultiPlanMatchStep.class.getDeclaredField("plans");
    Field coordField = MultiPlanMatchStep.class.getDeclaredField("coordinatorContext");
    assertThat(Modifier.isFinal(plansField.getModifiers()))
        .as("plans field must be non-final for clone() to assign copies without reflection")
        .isFalse();
    assertThat(Modifier.isFinal(coordField.getModifiers()))
        .as("coordinatorContext field must be non-final for clone() to assign a fresh coordinator")
        .isFalse();
  }

  // ---- Test helpers ----

  private MultiPlanMatchStep<Object, Vertex> elementStep(Child... children) {
    var plans = new ArrayList<InternalExecutionPlan>();
    for (var c : children) {
      plans.add(c.plan);
    }
    return new MultiPlanMatchStep<>(
        traversal, Vertex.class, plans, "v", BoundaryOutputType.ELEMENT);
  }

  /**
   * Builds one child: a mocked {@link InternalExecutionPlan} whose {@code getContext()} returns a
   * mock context and whose {@code start()} returns the given {@link ListStream} double. Stubs are
   * lenient because the clone-only tests never start the plan.
   */
  private Child child(ListStream stream) {
    var plan = mock(InternalExecutionPlan.class);
    var ctx = mock(CommandContext.class);
    lenient().when(plan.getContext()).thenReturn(ctx);
    lenient().when(plan.start()).thenReturn(stream);
    return new Child(plan, ctx, stream);
  }

  /** A child copy that delivers an empty stream against its own context — used by clone tests. */
  private Child emptyCopy() {
    return child(ListStream.of());
  }

  private static Runnable drive(
      MultiPlanMatchStep<Object, Vertex> step, CyclicBarrier barrier,
      List<Throwable> errors) {
    return () -> {
      try {
        barrier.await();
        step.forEachRemaining(t -> {
        });
      } catch (Throwable t) {
        errors.add(t);
      }
    };
  }

  /** A result row that binds the boundary alias {@code "v"} to the given raw YTDB vertex. */
  private static Result vertexRow(
      com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex raw) {
    var row = mock(Result.class);
    lenient().when(row.getVertex("v")).thenReturn(raw);
    return row;
  }

  private static com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex rawVertex() {
    return mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
  }

  /**
   * Asserts the projected element is a {@link YTDBVertexImpl} and returns the raw entity it wraps
   * (its {@code fastPathEntity} field), so a projected wrapper can be compared to the raw vertex its
   * alias resolved to without a real graph. The {@code assert} prefix marks the embedded type check.
   */
  private static Object rawEntityOf(Object tinkerVertex) {
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
    // AbstractStep's ctor calls traversal.getTraverserSetSupplier().get(); supply a real empty set so
    // the super-ctor does not NPE on the mock's default-null return.
    Supplier<TraverserSet<Object>> traverserSetSupplier = TraverserSet::new;
    lenient().when(traversal.getTraverserSetSupplier()).thenReturn(traverserSetSupplier);
    return traversal;
  }

  /** A child plan mock bundled with its context mock and the {@link ListStream} its {@code start()} returns. */
  private record Child(InternalExecutionPlan plan, CommandContext ctx, ListStream stream) {

  }

  /**
   * A real (non-mock) {@link ExecutionStream} test double that delivers a fixed list of rows and
   * records the {@link CommandContext} it is iterated and closed against. Using a real stream keeps
   * the concatenation tests robust against the exact number of {@code hasNext} probes {@code
   * MultipleExecutionStream} makes, while the recorded context lets a test assert that each child was
   * driven against its OWN context (the {@code ChildContextStream} guarantee). A throwing variant
   * models a child that fails mid-iteration.
   */
  private static final class ListStream implements ExecutionStream {

    private final List<Result> rows;
    private final RuntimeException throwOnNext;
    private int pos;
    private int closeCount;
    private boolean startedIterating;
    private CommandContext lastIterationContext;
    private CommandContext lastCloseContext;

    private ListStream(List<Result> rows, RuntimeException throwOnNext) {
      this.rows = rows;
      this.throwOnNext = throwOnNext;
    }

    static ListStream of(Result... rows) {
      return new ListStream(new ArrayList<>(List.of(rows)), null);
    }

    /** A child stream that reports a row available but throws when that row is pulled. */
    static ListStream throwing(RuntimeException error) {
      return new ListStream(new ArrayList<>(), error);
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      startedIterating = true;
      lastIterationContext = ctx;
      return throwOnNext != null || pos < rows.size();
    }

    @Override
    public Result next(CommandContext ctx) {
      startedIterating = true;
      lastIterationContext = ctx;
      if (throwOnNext != null) {
        throw throwOnNext;
      }
      return rows.get(pos++);
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
      lastCloseContext = ctx;
    }

    /** Re-arms this stream so a re-iterated child re-delivers its rows. */
    void rewind() {
      pos = 0;
    }

    boolean startedIterating() {
      return startedIterating;
    }

    int closeCount() {
      return closeCount;
    }

    CommandContext lastIterationContext() {
      return lastIterationContext;
    }

    CommandContext lastCloseContext() {
      return lastCloseContext;
    }
  }
}
