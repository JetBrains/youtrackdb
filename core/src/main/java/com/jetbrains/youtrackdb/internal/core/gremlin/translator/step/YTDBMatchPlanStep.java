package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.util.CloseableIteratorWithCallback;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Boundary step that bridges the YTDB MATCH execution stream to TinkerPop's traverser-driven
 * iteration model.
 *
 * <p>When the Gremlin-to-MATCH strategy translates a fully-recognised traversal end-to-end,
 * it replaces the entire step list with a single {@code YTDBMatchPlanStep} carrying the
 * compiled {@link InternalExecutionPlan}, the alias under which the matched element appears
 * in the result row, and the {@link BoundaryOutputType} that dictates how each row is
 * projected onto a TinkerPop traverser payload. Translation is all-or-nothing — any
 * unrecognised step in the traversal causes the strategy to decline the whole traversal,
 * so the boundary step is always the only step in the resulting traversal.
 *
 * <p>This step extends {@link GraphStep} (rather than the more generic {@code AbstractStep})
 * so it inherits the start-step traverser-spawning semantics that other YTDB graph-step
 * subclasses (notably {@code YTDBGraphStep}) rely on. The actual element source is wired
 * via {@link GraphStep#setIteratorSupplier(java.util.function.Supplier)}; the supplier
 * lazily opens the plan's {@link ExecutionStream} on first iteration.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li><b>Construction:</b> the strategy assembles the plan via
 *       {@code MatchExecutionPlanner} and constructs this step. No execution work runs yet.
 *   <li><b>Iteration:</b> on first {@code processNextStart}, the supplier fires:
 *       {@code plan.start()} produces an {@link ExecutionStream}; each call pulls one
 *       {@link Result} row, projects it per {@link BoundaryOutputType}, and emits a
 *       traverser. Wrapping happens via {@link YTDBVertexImpl} (using the graph from the
 *       traversal) so downstream native steps see TinkerPop element types.
 *   <li><b>Close:</b> exhaustion of the iterator (auto-detected by
 *       {@link CloseableIteratorWithCallback}) and explicit {@code GraphStep.close()} both
 *       trigger the close hook, which closes the {@link ExecutionStream} first and then
 *       the plan. The hook is idempotent.
 *   <li><b>Clone:</b> {@link #clone()} produces a step that holds its own independent
 *       {@link InternalExecutionPlan#copy(com.jetbrains.youtrackdb.internal.core.command.CommandContext)
 *       deep copy} of the plan (mirroring {@code HashJoinMatchStep}'s per-execution
 *       isolation). A {@link SelectExecutionPlan}-family plan carries mutable per-run state
 *       and must be copied before each independent execution; sharing one plan across the
 *       original and the clone would let two executions race for the same single-shot step
 *       chain. Cloning also resets the inherited {@code iterator}/{@code done} fields that
 *       {@link GraphStep} maintains privately and re-binds the iterator supplier to the
 *       clone.
 * </ul>
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted element type (currently always a TinkerPop {@link Vertex} per the
 *            single supported output type; later tracks parameterise this)
 */
public final class YTDBMatchPlanStep<S, E extends Element> extends GraphStep<S, E> {

  private final InternalExecutionPlan plan;
  private final String boundaryAlias;
  private final BoundaryOutputType outputType;

  /**
   * Tracks whether {@link #createIterator()} has already opened this instance's plan.
   * Each boundary step owns a single {@link InternalExecutionPlan} instance; the plan's
   * underlying step chain does not reset its execution state on a second {@code plan.start()}
   * call (it would silently re-iterate against already-consumed cursors). The flag turns a
   * second {@code createIterator} call on the same instance into an explicit
   * {@link IllegalStateException} rather than letting the silent-divergence bug reach end
   * users. {@link #clone()} gives the clone its own fresh plan copy and resets this flag, so
   * each newly-cloned step starts from a clean slate; the first iteration on either side
   * trips the flag for that side only.
   */
  private boolean started;

  /**
   * Constructs a new boundary step backed by the given execution plan.
   *
   * @param traversal     the host traversal (must not be null)
   * @param returnClass   the TinkerPop element class the step emits (currently
   *                      {@link Vertex}{@code .class})
   * @param plan          the compiled MATCH plan (must not be null)
   * @param boundaryAlias the alias under which the matched element appears in each
   *                      {@link Result} row (must not be null)
   * @param outputType    how each row is projected onto a traverser payload (must not be
   *                      null)
   */
  public YTDBMatchPlanStep(
      Traversal.Admin<S, E> traversal,
      Class<E> returnClass,
      InternalExecutionPlan plan,
      String boundaryAlias,
      BoundaryOutputType outputType) {
    super(traversal, returnClass, /*isStart*/ true, /*ids*/ new Object[0]);
    this.plan = Objects.requireNonNull(plan, "plan must not be null");
    this.boundaryAlias = Objects.requireNonNull(boundaryAlias, "boundaryAlias must not be null");
    this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
    setIteratorSupplier(this::createIterator);
  }

  /** The alias the step uses to look up the matched element in each row. */
  public String getBoundaryAlias() {
    return boundaryAlias;
  }

  /** The boundary output mode this step is configured for. */
  public BoundaryOutputType getOutputType() {
    return outputType;
  }

  /** The compiled execution plan the step iterates over. */
  public InternalExecutionPlan getPlan() {
    return plan;
  }

  /**
   * Produces a fresh iterator that pulls rows from the plan's {@link ExecutionStream} and
   * projects each one to a TinkerPop element. Package-private rather than {@code private}
   * so the unit test can drive it directly with a stub plan.
   *
   * <p>Throws {@link IllegalStateException} on a second invocation against the same step
   * instance — this instance's plan is single-shot, and a second {@code plan.start()} on a
   * drained plan would silently produce wrong results. Cloned steps get their own fresh
   * plan copy and reset the flag in {@link #clone()}, so each clone gets one fresh start.
   */
  Iterator<E> createIterator() {
    if (started) {
      throw new IllegalStateException(
          "YTDBMatchPlanStep.createIterator() invoked twice on the same step instance; "
              + "boundary steps own a single execution plan and are not re-iterable. "
              + "Re-execute the traversal (or clone the step) to drive a second pass.");
    }
    var ctx = plan.getContext();
    // Resolve the graph BEFORE opening the stream — getTraversal().getGraph() can throw
    // (Optional.empty().orElseThrow) or the cast to YTDBGraphInternal can throw. Opening
    // the stream first would leak it on either failure because the close hook is only
    // installed once CloseableIteratorWithCallback has been constructed.
    var graph = (YTDBGraphInternal) getTraversal().getGraph().orElseThrow();
    // `plan.start()` is itself non-trivial work — it walks the plan's step chain and
    // each step's `internalStart(ctx)` may open cursors, prefetch rows, or otherwise
    // claim resources before throwing. Wrap it in its own try so that a partial start
    // still gets the plan released. If start succeeds, we mark the step as started and
    // hand the stream to the wrapper's close hook (which then owns the cleanup).
    ExecutionStream stream;
    try {
      stream = plan.start();
    } catch (RuntimeException | Error e) {
      try {
        plan.close();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    started = true;
    try {
      var rowIterator = new ResultProjectionIterator<E>(stream, graph);
      // The close hook fires both on natural exhaustion (CloseableIteratorWithCallback's
      // hasNext-based auto-close) and on explicit GraphStep.close (which TinkerPop invokes
      // when the traversal terminates early — e.g. a downstream LimitStep cuts iteration
      // short — or when an exception aborts the run). It closes the stream first so the
      // plan's last step can flush before the plan itself releases resources.
      return new CloseableIteratorWithCallback<>(
          rowIterator,
          () -> {
            try {
              stream.close(ctx);
            } finally {
              plan.close();
            }
          });
    } catch (RuntimeException | Error e) {
      // Anything that fails between plan.start() and the wrapper's construction (OOM,
      // unexpected NPE, etc.) must close the just-opened stream and the plan to avoid
      // leaking the consumer-side cursor. The throw is preserved so callers see the
      // original failure.
      try {
        stream.close(ctx);
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      try {
        plan.close();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public YTDBMatchPlanStep<S, E> clone() {
    var cloned = (YTDBMatchPlanStep<S, E>) super.clone();
    // Reset the inherited iterator/done state on the clone so it starts fresh.
    cloned.reset();
    // Give the clone its OWN deep copy of the plan rather than sharing the original's.
    // A SelectExecutionPlan-family plan carries mutable per-run state and is not safe to
    // execute concurrently or to re-run without a copy(); the plan's own Javadoc mandates
    // "copied via copy() before each execution". TinkerPop clones a traversal (and thus
    // every step) once per execution, so copying here is exactly the per-execution
    // isolation point. Mirrors HashJoinMatchStep, which copies its build-side plan against
    // an isolated context before each start(). Copy against the original plan's context so
    // the copy shares the same database session while owning an independent step chain.
    var copiedPlan = plan.copy(plan.getContext());
    // super.clone()'s shallow copy left `cloned.plan` pointing at the original plan; the
    // field is final, so overwrite it reflectively to install the clone's independent copy.
    setPlanField(cloned, copiedPlan);
    // Reset the started flag so the clone can drive its own first iteration. Without this
    // the clone would inherit the original's `started=true` (super.clone() copies primitive
    // fields by value) and createIterator() would throw immediately.
    cloned.started = false;
    // Re-bind the supplier to the clone, not the original. setIteratorSupplier(this::...)
    // in the ctor captured the original instance; without re-binding here, iterating the
    // clone would call createIterator() on the original step and start the ORIGINAL's plan
    // instead of the clone's copy. Pointing the supplier at the clone keeps the two
    // executions independent — each starts its own plan copy on its own demand.
    cloned.setIteratorSupplier(cloned::createIterator);
    return cloned;
  }

  /**
   * Overwrites the {@code final} {@link #plan} field on a freshly-cloned instance. Java's
   * {@link Object#clone()} produces a shallow copy whose {@code final} fields already point
   * at the original's referents; {@link #clone()} needs to install an independent plan copy,
   * so it must bypass the final-field write barrier via reflection. This is confined to the
   * clone path and never runs on a live, published instance.
   */
  private static void setPlanField(YTDBMatchPlanStep<?, ?> target, InternalExecutionPlan value) {
    try {
      var field = YTDBMatchPlanStep.class.getDeclaredField("plan");
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      // The field name is a compile-time constant in this class; a failure here is a
      // programming error (field renamed without updating this reflective write), so fail
      // loudly rather than silently leaving the clone sharing the original's plan.
      throw new IllegalStateException("Failed to install cloned execution plan", e);
    }
  }

  /**
   * Lazily-projecting iterator over the plan's row stream. Each {@code next} pulls one
   * {@link Result} and projects it to the configured TinkerPop element type. Looping the
   * projection logic into a separate inner class (rather than computing it inline in
   * {@code createIterator}) makes the {@link CloseableIteratorWithCallback} wrapping
   * trivial — the wrapper's hasNext-based auto-close hook only needs to inspect a stable
   * underlying iterator, not a stream that might be in mid-projection.
   */
  private final class ResultProjectionIterator<T> implements Iterator<T> {

    private final ExecutionStream stream;
    private final YTDBGraphInternal graph;

    ResultProjectionIterator(ExecutionStream stream, YTDBGraphInternal graph) {
      this.stream = stream;
      this.graph = graph;
    }

    @Override
    public boolean hasNext() {
      return stream.hasNext(plan.getContext());
    }

    @Override
    public T next() {
      // The defensive hasNext call on the next() path lets callers invoke next()
      // directly without a paired hasNext() and still get the standard
      // NoSuchElementException on past-end calls (the test
      // iterator_nextOnExhaustedStream_throwsNoSuchElement and the repeated-call
      // sibling test pin this contract). The cost is one extra stream-chain walk per
      // row when callers do hasNext()-then-next() — a small constant that later phases
      // can revisit (e.g. a cached-hasNext shape) once there is a perf baseline.
      if (!stream.hasNext(plan.getContext())) {
        throw new NoSuchElementException();
      }
      Result row = stream.next(plan.getContext());
      return project(row);
    }

    @SuppressWarnings("unchecked")
    private T project(Result row) {
      return switch (outputType) {
        case ELEMENT -> (T) projectElement(row, graph);
      };
    }
  }

  /**
   * Extracts the matched vertex from {@code row} under {@link #boundaryAlias} and wraps it
   * as a TinkerPop {@link Vertex}. Returns {@code null} when the row does not bind the
   * alias to a vertex (e.g. the alias was an optional node that did not match) — downstream
   * native steps treat null as "absent" the same way they would for any other null
   * traverser payload.
   *
   * <p>Package-private so unit tests can exercise the projection logic without going
   * through the full iterator lifecycle.
   */
  Vertex projectElement(Result row, YTDBGraphInternal graph) {
    var rawVertex = row.getVertex(boundaryAlias);
    if (rawVertex == null) {
      return null;
    }
    return new YTDBVertexImpl(graph, rawVertex);
  }
}
