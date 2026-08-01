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
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
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
    // The post-concat count paths build a ResultInternal against the iteration-thread session, and
    // every ResultInternal mutation asserts the session is active. A bare mock answers false and
    // trips that assert under -ea, so report the session as active.
    lenient().when(threadSession.assertIfNotActive()).thenReturn(true);
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
   * Each clone — and the original — gets its OWN coordinator context. {@code clone()} mints a fresh
   * {@link BasicCommandContext} for the coordinator so two concurrent clones never race on its
   * {@code session} field during {@code openArming()}. The field is private with no getter, so it is
   * read reflectively. Deleting the fresh-coordinator line in {@code clone()} makes this fail: the
   * clones would then share the original's coordinator through the {@code super.clone()} shallow
   * copy, which the concurrent drive below cannot catch on its own (both threads write the same
   * session value, a benign same-value write).
   */
  @Test
  public void clone_givesEachCloneAndTheOriginalItsOwnCoordinatorContext() throws Exception {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());
    // clone() deep-copies each child; stub copy so the copies list carries no null (List.copyOf
    // rejects null). The returned copy is irrelevant — this test inspects only the coordinator field.
    when(c1.plan.copy(any())).thenReturn(mock(InternalExecutionPlan.class));
    when(c2.plan.copy(any())).thenReturn(mock(InternalExecutionPlan.class));

    var original = elementStep(c1, c2);
    var cloneA = original.clone();
    var cloneB = original.clone();

    Field field = MultiPlanMatchStep.class.getDeclaredField("coordinatorContext");
    field.setAccessible(true);
    var originalCoordinator = field.get(original);
    var cloneACoordinator = field.get(cloneA);
    var cloneBCoordinator = field.get(cloneB);
    assertThat(cloneACoordinator)
        .as("clone A must get its own coordinator, not share the original's or clone B's")
        .isNotSameAs(originalCoordinator)
        .isNotSameAs(cloneBCoordinator);
    assertThat(cloneBCoordinator)
        .as("clone B must get its own coordinator, not share the original's")
        .isNotSameAs(originalCoordinator);
  }

  /**
   * Fail-fast guard on the clone-isolation invariant: a child's template (parent) context must carry
   * no per-run variable, because a child write propagates up to any key the parent already holds, so
   * a seeded parent shared across clones would be written concurrently through its unsynchronised
   * maps. A normal variable (an alias / LET binding) seeded onto a child's context makes {@code
   * clone()} fail its assertion instead of minting isolation that silently does not isolate.
   */
  @Test
  public void clone_childTemplateContextCarriesNormalVariable_assertionFailsFast() {
    var plan = mock(InternalExecutionPlan.class);
    var seededContext = new BasicCommandContext();
    seededContext.setVariable("someAlias", "bound"); // a per-run alias / LET binding
    lenient().when(plan.getContext()).thenReturn(seededContext);
    var step =
        new MultiPlanMatchStep<>(
            traversal, Vertex.class, List.of(plan), "v", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(step::clone)
        .withMessageContaining("per-run state");
  }

  /**
   * The system-variable leg of the same fail-fast guard: a {@code $current} ({@link
   * CommandContext#VAR_CURRENT}) system variable seeded onto a child's template context also trips
   * the {@code clone()} assertion, because it too would propagate up to a shared parent under
   * concurrent clone execution.
   */
  @Test
  public void clone_childTemplateContextCarriesCurrentSystemVariable_assertionFailsFast() {
    var plan = mock(InternalExecutionPlan.class);
    var seededContext = new BasicCommandContext();
    seededContext.setSystemVariable(CommandContext.VAR_CURRENT, "bound");
    lenient().when(plan.getContext()).thenReturn(seededContext);
    var step =
        new MultiPlanMatchStep<>(
            traversal, Vertex.class, List.of(plan), "v", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(step::clone)
        .withMessageContaining("per-run state");
  }

  /**
   * The guard covers every system-variable slot, not only the two the element path happens to write.
   * {@code $current_match} ({@link CommandContext#VAR_CURRENT_MATCH}) is the reachable gap: the MATCH
   * edge-traversal path writes it per candidate and restores it afterwards — with a null value on
   * the first candidate — and key presence is tracked independently of the value, so a union child
   * that matched nothing seeds this slot while leaving {@code $matched} and {@code $current} clean.
   * A guard enumerating only those two would pass such a context and hand two concurrent clones one
   * shared, unsynchronised parent map.
   */
  @Test
  public void clone_childTemplateContextCarriesCurrentMatchSystemVariable_assertionFailsFast() {
    var plan = mock(InternalExecutionPlan.class);
    var seededContext = new BasicCommandContext();
    // The value a zero-row MATCH child leaves behind when it restores the previous candidate.
    seededContext.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, null);
    lenient().when(plan.getContext()).thenReturn(seededContext);
    var step =
        new MultiPlanMatchStep<>(
            traversal, Vertex.class, List.of(plan), "v", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(step::clone)
        .withMessageContaining("system variable slot " + CommandContext.VAR_CURRENT_MATCH);
  }

  /**
   * The fourth slot, {@code $depth} ({@link CommandContext#VAR_DEPTH}), is written by MATCH's
   * recursive {@code while:} path items. No recognised union shape emits one today, so this pins the
   * guard against a future recogniser widening rather than a live leak.
   */
  @Test
  public void clone_childTemplateContextCarriesDepthSystemVariable_assertionFailsFast() {
    var plan = mock(InternalExecutionPlan.class);
    var seededContext = new BasicCommandContext();
    seededContext.setSystemVariable(CommandContext.VAR_DEPTH, 2);
    lenient().when(plan.getContext()).thenReturn(seededContext);
    var step =
        new MultiPlanMatchStep<>(
            traversal, Vertex.class, List.of(plan), "v", BoundaryOutputType.ELEMENT);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(step::clone)
        .withMessageContaining("system variable slot " + CommandContext.VAR_DEPTH);
  }

  /**
   * Concurrency contract for clone isolation, made falsifiable. Each clone deep-copies every child
   * against its OWN isolated child context, so two clones driven on two threads never share the
   * per-run variable map a real child context owns. This drives that contract for real: each child
   * copy is backed by the very isolated context {@code clone()} minted for it (echoed back from the
   * {@code copy(...)} argument), and each clone's iteration WRITES then READS its driving thread's
   * identity through that context many times, released together by a {@link CyclicBarrier} and
   * repeated under a stress loop. On correctly isolated contexts every read returns the writer's own
   * value, so the run is deterministically green; a regression that re-shared a child context between
   * clones would let one thread observe the other's write (a recorded mismatch) or corrupt the
   * unsynchronised {@code HashMap} (an iteration hang the timed {@code Future.get} turns into a failed
   * test). The earlier version drove empty streams over stateless mocks, so no thread ever wrote the
   * per-run state the isolation exists to keep disjoint.
   */
  @Test
  public void clone_concurrentDrives_noCrossCloneVariableBleed() throws Exception {
    int iterations = 200;
    int cyclesPerProbe = 64;
    var mismatches = new CopyOnWriteArrayList<String>();
    var errors = new CopyOnWriteArrayList<Throwable>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        var c1 = child(ListStream.of());
        var c2 = child(ListStream.of());
        // Every clone() copy call is answered by a fresh plan that wraps the isolated context passed
        // to copy(...) and whose stream probes THAT context. thenAnswer (not thenReturn) is the whole
        // point: it echoes back each clone's own isolated ctx, so the two clones share a context only
        // if clone() failed to isolate them.
        var createdCopies = new ArrayList<InternalExecutionPlan>();
        when(c1.plan.copy(any()))
            .thenAnswer(
                inv -> recordProbeCopy(inv.getArgument(0), cyclesPerProbe, mismatches,
                    createdCopies));
        when(c2.plan.copy(any()))
            .thenAnswer(
                inv -> recordProbeCopy(inv.getArgument(0), cyclesPerProbe, mismatches,
                    createdCopies));

        var original = elementStep(c1, c2);
        var cloneA = original.clone();
        var cloneB = original.clone();
        cloneA.setTraversal(traversal);
        cloneB.setTraversal(traversal);

        var barrier = new CyclicBarrier(2);
        Future<?> futureA = pool.submit(drive(cloneA, barrier, errors));
        Future<?> futureB = pool.submit(drive(cloneB, barrier, errors));
        // A completed get() also establishes the happens-before edge the verify() calls rely on; a
        // corrupted-map hang surfaces as a TimeoutException that fails the test instead of blocking.
        futureA.get(5, TimeUnit.SECONDS);
        futureB.get(5, TimeUnit.SECONDS);

        // Originals never ran; each clone ran its own two copies exactly once.
        verify(c1.plan, never()).start();
        verify(c2.plan, never()).start();
        assertThat(createdCopies).as("two clones deep-copy two children each").hasSize(4);
        for (var copy : createdCopies) {
          verify(copy, times(1)).start();
        }
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(errors).as("no driver thread threw during concurrent iteration").isEmpty();
    assertThat(mismatches).as("no clone observed another clone's per-run variable").isEmpty();
  }

  // ---- Post-concatenation reductions ----

  /**
   * A lone {@code Count} takes the push-down path: the strategy rewrote every child to {@code RETURN
   * count(*)} at build time, so the step reads one scalar row per child and emits their sum as a
   * single SCALAR traverser. Children reporting 2 and 3 must emit exactly one traverser holding 5.
   */
  @Test
  public void lonePushDownCount_sumsChildScalarRowsIntoOneTraverser() {
    var c1 = child(ListStream.of(countRow(2L)));
    var c2 = child(ListStream.of(countRow(3L)));

    var step = scalarStep(List.of(PostConcatOp.Count.INSTANCE), c1, c2);

    assertThat(nextPayload(step)).isEqualTo(5L);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
  }

  /**
   * The push-down path infers its row shape from a rewrite that lives in another class: exactly one
   * non-boundary column holding a number. A cell that is not a number means the child was never
   * rewritten, and absorbing it as zero would under-report the union total with nothing downstream
   * able to notice, so the step fails instead.
   */
  @Test
  public void pushDownCount_nonNumericCountColumn_throwsInsteadOfCountingZero() {
    var step = scalarStep(List.of(PostConcatOp.Count.INSTANCE), child(ListStream.of(
        countRow("not-a-number"))));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("RETURN count(*)");
  }

  /**
   * The other malformed push-down shape: a child row carrying only the boundary column, so there is
   * no count cell at all. Same reasoning as a non-numeric cell — reporting zero would be a silent
   * under-count.
   */
  @Test
  public void pushDownCount_rowWithoutCountColumn_throwsInsteadOfCountingZero() {
    var row = mock(Result.class);
    lenient().when(row.getPropertyNames()).thenReturn(List.of("v"));
    var step = scalarStep(List.of(PostConcatOp.Count.INSTANCE), child(ListStream.of(row)));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(step::processNextStart)
        .withMessageContaining("no column other than the boundary alias");
  }

  /**
   * A {@code Count} that follows another reduction cannot be pushed down, so it drains the
   * concatenation and counts the surviving rows. Two children yielding two rows apiece, with both
   * children repeating one shared vertex identity, dedup to three rows.
   *
   * <p>The test also pins the release discipline the drain depends on. Draining closes each child
   * once as the concatenator advances past it, and closing the concatenator closes the last child a
   * second time; the count wrapper must not add a third close on top, because {@code
   * ExecutionStream} promises no idempotent close.
   */
  @Test
  public void countAfterDedup_countsDistinctRows_andClosesTheConcatenatorOnce() {
    var shared = rawVertex();
    var sharedId = mock(RID.class);
    var c1 = child(ListStream.of(identityRow(shared, sharedId), identityRow(rawVertex(),
        mock(RID.class))));
    var c2 = child(ListStream.of(identityRow(shared, sharedId), identityRow(rawVertex(),
        mock(RID.class))));

    var step =
        scalarStep(List.of(PostConcatOp.Dedup.INSTANCE, PostConcatOp.Count.INSTANCE), c1, c2);

    assertThat(nextPayload(step))
        .as("four concatenated rows, one identity shared across the two children")
        .isEqualTo(3L);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    step.close();

    assertThat(c1.stream.closeCount())
        .as("a non-final child closes once, when the concatenator advances past it")
        .isEqualTo(1);
    assertThat(c2.stream.closeCount())
        .as("the last child closes when it drains and again when the concatenator closes — the"
            + " count wrapper must not add a third")
        .isEqualTo(2);
  }

  /**
   * {@code clone()} must carry the post-concat op list over, or a cloned union would drop its
   * reductions and emit raw concatenated rows.
   */
  @Test
  public void clone_carriesPostConcatOps() {
    var c = child(ListStream.of());
    when(c.plan.copy(any())).thenAnswer(inv -> emptyCopy(inv.getArgument(0)));
    var original = elementStepWithOps(List.of(PostConcatOp.Dedup.INSTANCE), c);

    assertThat(original.clone().getPostConcatOps())
        .isEqualTo(List.of(PostConcatOp.Dedup.INSTANCE));
  }

  /**
   * Clone isolation extends to the state the dedup reduction mints. {@code dedupConcatStream} builds
   * its {@code seen} set per arming, so two clones driven concurrently over rows carrying the SAME
   * vertex identity must each emit that row once — four rows in, one out per clone. Hoisting {@code
   * seen} to a field would make the two clones share it through {@code super.clone()}, and whichever
   * clone lost the race would emit zero rows with no exception and no hang; this test records the
   * per-clone emission count so that regression shows up as a value, not a flake.
   */
  @Test
  public void clone_concurrentDrivesWithDedup_eachCloneKeepsItsOwnSeenSet() throws Exception {
    int iterations = 200;
    var emitted = new CopyOnWriteArrayList<Integer>();
    var errors = new CopyOnWriteArrayList<Throwable>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        var shared = rawVertex();
        var sharedId = mock(RID.class);
        var c1 = child(ListStream.of());
        var c2 = child(ListStream.of());
        // Each clone's copy re-delivers a row bearing the SHARED identity against the isolated
        // context clone() passed in, so the only thing that can make a clone emit zero rows is a
        // seen-set it did not mint itself.
        when(c1.plan.copy(any()))
            .thenAnswer(inv -> identityYieldingCopy(inv.getArgument(0), shared, sharedId));
        when(c2.plan.copy(any()))
            .thenAnswer(inv -> identityYieldingCopy(inv.getArgument(0), shared, sharedId));

        var original = elementStepWithOps(List.of(PostConcatOp.Dedup.INSTANCE), c1, c2);
        var cloneA = original.clone();
        var cloneB = original.clone();
        cloneA.setTraversal(traversal);
        cloneB.setTraversal(traversal);

        var barrier = new CyclicBarrier(2);
        Future<?> futureA = pool.submit(driveCounting(cloneA, barrier, emitted, errors));
        Future<?> futureB = pool.submit(driveCounting(cloneB, barrier, emitted, errors));
        futureA.get(5, TimeUnit.SECONDS);
        futureB.get(5, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(errors).as("no driver thread threw during concurrent iteration").isEmpty();
    assertThat(emitted)
        .as("every clone dedups against its own seen set, so each emits the shared row once")
        .hasSize(2 * iterations)
        .containsOnly(1);
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

  /** An ELEMENT-projecting step carrying the given post-concat reductions. */
  private MultiPlanMatchStep<Object, Vertex> elementStepWithOps(
      List<PostConcatOp> ops, Child... children) {
    return stepWithOps(BoundaryOutputType.ELEMENT, ops, children);
  }

  /**
   * A SCALAR-projecting step carrying the given post-concat reductions — the shape a recognised
   * {@code union(...).count()} produces, where the boundary emits one aggregate cell.
   */
  private MultiPlanMatchStep<Object, Vertex> scalarStep(List<PostConcatOp> ops, Child... children) {
    return stepWithOps(BoundaryOutputType.SCALAR, ops, children);
  }

  private MultiPlanMatchStep<Object, Vertex> stepWithOps(
      BoundaryOutputType outputType, List<PostConcatOp> ops, Child... children) {
    var plans = new ArrayList<InternalExecutionPlan>();
    for (var c : children) {
      plans.add(c.plan);
    }
    return new MultiPlanMatchStep<>(
        traversal, Vertex.class, plans, "v", outputType, ResultShaping.NONE, ops);
  }

  /**
   * Pulls the next traverser's payload as an {@code Object}. The step's {@code E} bound is {@code
   * Vertex} for the element path, so reading a SCALAR count cell through the typed {@code get()}
   * would insert a cast the aggregate payload cannot satisfy.
   */
  private static Object nextPayload(MultiPlanMatchStep<Object, Vertex> step) {
    Traverser.Admin<?> traverser = step.processNextStart();
    return traverser.get();
  }

  /** Answers a {@code copy(ctx)} call with a plan that yields no rows against the given context. */
  private InternalExecutionPlan emptyCopy(CommandContext isolatedContext) {
    var stream = ListStream.of();
    var copy = mock(InternalExecutionPlan.class);
    lenient().when(copy.getContext()).thenReturn(isolatedContext);
    lenient().when(copy.start()).thenReturn(stream);
    return copy;
  }

  /**
   * Answers a {@code copy(ctx)} call with a plan whose stream delivers one row bound to the given
   * raw vertex and identity, against the isolated context {@code clone()} just minted.
   */
  private InternalExecutionPlan identityYieldingCopy(
      CommandContext isolatedContext,
      com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex raw,
      RID identity) {
    // Build the row (and its own stubs) before any stubbing on the copy starts: Mockito refuses a
    // mock interaction that lands between when(...) and thenReturn(...).
    var stream = ListStream.of(identityRow(raw, identity));
    var copy = mock(InternalExecutionPlan.class);
    lenient().when(copy.getContext()).thenReturn(isolatedContext);
    lenient().when(copy.start()).thenReturn(stream);
    return copy;
  }

  private static Runnable driveCounting(
      MultiPlanMatchStep<Object, Vertex> step,
      CyclicBarrier barrier,
      List<Integer> emitted,
      List<Throwable> errors) {
    return () -> {
      try {
        barrier.await();
        var count = new int[1];
        step.forEachRemaining(t -> count[0]++);
        emitted.add(count[0]);
      } catch (Throwable t) {
        errors.add(t);
      }
    };
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

  /**
   * Answers a {@code copy(ctx)} call with a fresh plan mock that reports the isolated context {@code
   * clone()} just passed in ({@code copy.getContext()} echoes the argument) and whose {@code start()}
   * yields a {@link VariableProbeStream} probing THAT context. Recording each created copy lets the
   * caller assert every clone drove its own copies. Echoing the argument back (rather than a
   * pre-canned context) is what makes the concurrent test falsifiable: if {@code clone()} stopped
   * minting a fresh context per child, both clones' copies would report the same shared context and
   * the probe would observe a cross-clone write.
   */
  private InternalExecutionPlan recordProbeCopy(
      CommandContext isolatedContext,
      int cyclesPerProbe,
      List<String> mismatches,
      List<InternalExecutionPlan> createdCopies) {
    var copy = mock(InternalExecutionPlan.class);
    lenient().when(copy.getContext()).thenReturn(isolatedContext);
    lenient().when(copy.start()).thenReturn(new VariableProbeStream(cyclesPerProbe, mismatches));
    createdCopies.add(copy);
    return copy;
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

  /**
   * A result row that binds the boundary alias to the given raw vertex and reports the given
   * identity. The dedup reduction reads the raw boundary property and takes its identity — it never
   * calls {@code getEntity}, which would load the record to recover a RID the row already holds —
   * while the ELEMENT projection reads {@code getVertex(alias)}, so both accessors are stubbed.
   */
  private static Result identityRow(
      com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex raw, RID identity) {
    var row = mock(Result.class);
    lenient().when(row.getVertex("v")).thenReturn(raw);
    lenient().when(row.<Object>getProperty("v")).thenReturn(raw);
    lenient().when(raw.getIdentity()).thenReturn(identity);
    return row;
  }

  /**
   * A pushed-down {@code RETURN count(*)} row: one non-boundary column holding the child's total.
   * The column name mirrors the rendered {@code count(*)} projection the rewrite pins.
   */
  private static Result countRow(Object value) {
    var row = mock(Result.class);
    lenient().when(row.getPropertyNames()).thenReturn(List.of("count(*)"));
    lenient().when(row.getProperty("count(*)")).thenReturn(value);
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

  /**
   * A real (non-mock) {@link ExecutionStream} that yields no rows but, on each {@code hasNext}, stamps
   * the driving thread's name into a per-run variable on the context it is iterated against and reads
   * it straight back {@code cyclesPerProbe} times. On a context that is truly isolated per clone the
   * read always returns the value the same thread just wrote; if two clones shared one context, the
   * other thread's concurrent write surfaces either as a recorded mismatch or as a corrupted
   * {@code HashMap} that hangs the iteration (which the caller's timed {@code Future.get} turns into a
   * failed test). Yielding no rows keeps the probe off the row-projection path so the test isolates
   * the context-sharing hazard, not projection.
   */
  private static final class VariableProbeStream implements ExecutionStream {

    private static final String PROBE_KEY = "probe";
    private final int cyclesPerProbe;
    private final List<String> mismatches;

    VariableProbeStream(int cyclesPerProbe, List<String> mismatches) {
      this.cyclesPerProbe = cyclesPerProbe;
      this.mismatches = mismatches;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      var mine = Thread.currentThread().getName();
      for (int i = 0; i < cyclesPerProbe; i++) {
        ctx.setVariable(PROBE_KEY, mine);
        Thread.onSpinWait(); // widen the interleaving window a shared context would expose
        var seen = ctx.getVariable(PROBE_KEY);
        if (!mine.equals(seen)) {
          mismatches
              .add("thread " + mine + " read back '" + seen + "' from a shared child context");
        }
      }
      return false;
    }

    @Override
    public Result next(CommandContext ctx) {
      throw new NoSuchElementException("probe stream yields no rows");
    }

    @Override
    public void close(CommandContext ctx) {
      // no-op: the probe holds no resources
    }
  }
}
