package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

/**
 * Boundary step that bridges a compiled YTDB MATCH plan to TinkerPop's traverser-driven iteration.
 *
 * <p>When the Gremlin-to-MATCH strategy recognises a traversal end-to-end it replaces the entire
 * step list with a single {@code YTDBMatchPlanStep} carrying the compiled {@link
 * InternalExecutionPlan}, the alias under which the matched element appears in a result row, and the
 * {@link BoundaryOutputType} that dictates how each row projects onto a traverser payload.
 * Translation is all-or-nothing, so the boundary step is always the traversal's only step.
 *
 * <p>The step extends {@link AbstractStep} directly, mirroring the fork's own element-emitting start
 * steps ({@code AddVertexStartStep}, {@code AddEdgeStartStep}). It is deliberately <em>not</em> a
 * {@code GraphStep}: it carries none of that class's id / has-container / {@code Configuring}
 * surface, and staying off the {@code GraphStep} hierarchy keeps {@code YTDBGraphStepStrategy}'s
 * rebuild loop from ever folding the boundary into a {@code YTDBGraphStep}.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li><b>Construction:</b> the strategy builds the plan and constructs the step. No execution
 *       work runs yet.
 *   <li><b>Iteration:</b> the first {@link #processNextStart()} opens the plan's {@link
 *       ExecutionStream}. It rebinds the plan's context to the session active on the current
 *       (iteration) thread first — the plan may have been compiled on a different thread, and YTDB
 *       record reads require the session active on the reading thread. Each subsequent call pulls
 *       one {@link Result} row, projects it per {@link BoundaryOutputType}, and generates a
 *       traverser. Wrapping goes through {@link YTDBVertexImpl} so downstream native steps see
 *       TinkerPop element types.
 *   <li><b>Exhaustion:</b> when the stream runs dry the arming's <em>stream</em> is closed but the
 *       plan is kept open, so a {@link #reset()} + reopen can rewind and re-run it — a closed
 *       {@link SelectExecutionPlan} cannot be cleanly restarted (its steps' close guard is sticky,
 *       so a re-run's cursors would leak). The plan itself is closed by the {@link #close()}
 *       TinkerPop fires on exhaustion (via {@code DefaultTraversal.hasNext()} closing the traversal
 *       through {@code CloseableIterator.closeIterator} once the boundary signals no more rows).
 *   <li><b>Iteration failure:</b> when iterating it throws, the stream <em>and</em> the plan are
 *       released immediately before the exception propagates. TinkerPop auto-closes the traversal
 *       only on normal exhaustion, never on a thrown exception, so deferring the plan close would
 *       leak the cursor. The iteration failure stays the primary exception; a release failure is
 *       attached with {@code addSuppressed}.
 *   <li><b>Close:</b> {@link #close()} (which TinkerPop invokes on exhaustion and on early
 *       termination — e.g. a downstream limit cuts iteration short — via {@code Traversal.close()}
 *       closing every {@link AutoCloseable} step) closes the stream first, then the plan. It is
 *       idempotent.
 *   <li><b>Reset:</b> {@link #reset()} re-arms the step for a fresh pass on the same instance. It
 *       does not close the open stream directly (see the field notes); the next open closes a
 *       lingering cursor and rewinds the plan.
 *   <li><b>Clone:</b> {@link #clone()} gives the clone its own deep {@link
 *       InternalExecutionPlan#copy(com.jetbrains.youtrackdb.internal.core.command.CommandContext)
 *       plan copy} against an isolated child context. A {@link SelectExecutionPlan}-family plan
 *       carries mutable per-run state (its step chain and the context's {@code $current} /
 *       {@code $matched} / statistics maps, all plain {@code HashMap}s), so two executions must not
 *       share it. TinkerPop clones a traversal once per execution, so cloning is the per-execution
 *       isolation point (mirroring {@code HashJoinMatchStep}).
 * </ul>
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted element type (a TinkerPop {@link Vertex} under the single supported output
 *            type; later tracks parameterise this)
 */
public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E>
    implements AutoCloseable {

  // Non-final so clone() installs the clone's own plan copy with a plain field write. A final field
  // would force a reflective write after super.clone() froze it, voiding the JMM final-field
  // publication guarantee for any thread that later receives the clone without a happens-before
  // edge. A plain write inside clone(), before the clone is published, has no such hazard.
  private InternalExecutionPlan plan;
  private final Class<E> returnClass;
  private final String boundaryAlias;
  private final BoundaryOutputType outputType;
  /** Positional-parameter values for this walk ({@code ?} slots), or empty when none. */
  private final Map<Object, Object> inputParameters;

  // The current arming's open stream, or null before the first open / after close. Single source of
  // truth — there is no inherited iterator to shadow.
  private ExecutionStream openStream;

  // The graph resolved for the current arming; used to wrap projected vertices.
  private YTDBGraphInternal armingGraph;

  /**
   * The lifecycle position of a {@link YTDBMatchPlanStep}. One value replaces the four interlocking
   * booleans the step used to carry ({@code armed} / {@code everStarted} / {@code done} / {@code
   * closed}): every transition is now a single field write, and a reader tracks one state instead of
   * a quadruple whose legal combinations had to be inferred.
   */
  private enum State {
    /**
     * Constructed, or {@link #reset()} before the plan ever ran. The next open starts the plan
     * WITHOUT rewinding it — there is no consumed state to rewind.
     */
    NEW,
    /** The stream is open and being iterated. */
    OPEN,
    /**
     * The stream drained and its cursor was closed, but the plan is left OPEN so a {@link #reset()}
     * + reopen can rewind and re-run it. {@link #processNextStart()} ends immediately in this state;
     * {@link #close()} closes the still-open plan.
     */
    DRAINED,
    /**
     * {@link #reset()} after at least one run. The next open closes any cursor a partial consume
     * left open and rewinds the plan ({@code plan.reset}) before starting it.
     */
    REARMED,
    /**
     * The plan is closed for good — by {@link #close()} or the terminal iteration-failure path.
     * Terminal: {@link #processNextStart()} ends immediately and {@link #close()} is a no-op.
     */
    CLOSED
  }

  private State state = State.NEW;

  /**
   * Constructs a boundary step backed by the given execution plan.
   *
   * @param traversal     the host traversal (must not be null)
   * @param returnClass   the TinkerPop element class the step emits (currently {@link
   *                      Vertex}{@code .class})
   * @param plan          the compiled MATCH plan (must not be null)
   * @param boundaryAlias the alias under which the matched element appears in each {@link Result}
   *                      row (must not be null)
   * @param outputType    how each row projects onto a traverser payload (must not be null)
   */
  public YTDBMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull InternalExecutionPlan plan,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType) {
    this(traversal, returnClass, plan, boundaryAlias, outputType, Map.of());
  }

  /**
   * Constructs a boundary step backed by the given execution plan and per-walk input parameters.
   *
   * @param traversal     the host traversal (must not be null)
   * @param returnClass   the TinkerPop element class the step emits (currently {@link
   *                      Vertex}{@code .class})
   * @param plan          the compiled MATCH plan (must not be null)
   * @param boundaryAlias the alias under which the matched element appears in each {@link Result}
   *                      row (must not be null)
   * @param outputType    how each row projects onto a traverser payload (must not be null)
   * @param inputParameters positional-parameter values keyed by slot; empty when the walk bound none
   */
  public YTDBMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull InternalExecutionPlan plan,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Map<Object, Object> inputParameters) {
    super(traversal);
    this.returnClass = returnClass;
    this.plan = plan;
    this.boundaryAlias = boundaryAlias;
    this.outputType = outputType;
    this.inputParameters = Map.copyOf(inputParameters);
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

  /** The TinkerPop element class the step emits. */
  public Class<E> getReturnClass() {
    return returnClass;
  }

  /**
   * Renders a one-line marker identifying this as a translated MATCH boundary, e.g. {@code
   * YTDBMatchPlanStep(node,ELEMENT)}. Because the strategy replaces a recognised traversal's whole
   * native chain with this single step, {@code traversal.explain()} shows this marker in place of
   * the native step boxes — the visible signal that translation happened. The marker stays concise;
   * the MATCH plan tree is reachable via {@link #getPlan()} and YQL's EXPLAIN tooling.
   */
  @Override
  public String toString() {
    return StringFactory.stepString(this, boundaryAlias, outputType);
  }

  /**
   * Pulls the next matched element as a traverser, opening the plan's stream on the first call.
   * Throws {@link FastNoSuchElementException} once the stream is exhausted, closing the arming's
   * stream as it does so; the plan stays open for a possible {@link #reset()} and is closed by the
   * {@link #close()} TinkerPop fires on exhaustion. A failure while iterating the stream closes both
   * the stream and the plan before propagating — TinkerPop does not auto-close on a thrown exception
   * — so a stream that threw part-way does not leak until traversal teardown.
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected Traverser.Admin<E> processNextStart() {
    if (state == State.DRAINED || state == State.CLOSED) {
      // Exhausted or closed for good: no more rows.
      throw FastNoSuchElementException.instance();
    }
    if (state == State.NEW || state == State.REARMED) {
      // First open, or a reopen after reset(). openArming() rewinds the plan iff we are REARMED.
      openStream = openArming();
      state = State.OPEN;
    }
    var ctx = plan.getContext();
    boolean hasRow;
    E element = null;
    try {
      hasRow = openStream.hasNext(ctx);
      if (hasRow) {
        element = (E) project(openStream.next(ctx));
      }
    } catch (RuntimeException | Error e) {
      // A failure while iterating the open stream (hasNext / next / projection) is terminal: release
      // the stream AND the plan before propagating. TinkerPop auto-closes the traversal only on
      // normal exhaustion (DefaultTraversal.hasNext -> closeIterator), never on a thrown exception,
      // so deferring the plan close here would leak the cursor until traversal teardown. The
      // iteration failure stays primary; a release failure is attached with addSuppressed. Moving to
      // CLOSED both ends iteration and marks the plan closed, so the just-closed plan is never
      // re-run.
      state = State.CLOSED;
      try {
        releaseStreamAndClosePlan();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    if (hasRow) {
      // Start-step traverser generation, as AddVertexStartStep does it. The raw Step cast is
      // needed because generate() expects Step<E, ?> but this is Step<S, E> (S != E for an element
      // source), so the generic self-reference cannot be expressed without erasure.
      return getTraversal().getTraverserGenerator().generate(element, (Step) this, 1L);
    }
    // Stream drained: close only this arming's STREAM and keep the plan open, so a reset() + reopen
    // can rewind and re-run it — a closed SelectExecutionPlan cannot be cleanly restarted (its
    // steps' close guard is sticky, so a re-run's cursors would leak). The plan is closed by the
    // close() TinkerPop fires right after this signals no more rows (DefaultTraversal.hasNext ->
    // CloseableIterator.closeIterator), and on early termination. Moving to DRAINED stops further
    // iteration until a reset re-arms.
    state = State.DRAINED;
    releaseStream();
    throw FastNoSuchElementException.instance();
  }

  /**
   * Opens the plan's stream for a fresh arming. Closes any stream left open by a superseded arming
   * (a reset after a partial consume), rebinds the plan to the thread-active session, rewinds the
   * plan if it has run before, then starts it.
   *
   * <p>The graph is resolved before {@code plan.start()} so a resolution failure leaks nothing: the
   * plan has not been started. A missing graph throws {@link IllegalStateException} rather than the
   * {@link java.util.NoSuchElementException} of a bare {@code orElseThrow()} — the latter is the
   * iteration-end signal that {@link AbstractStep#hasNext()} swallows, which would turn a genuine
   * "no attached graph" bug into a silent empty result.
   */
  private ExecutionStream openArming() {
    if (openStream != null) {
      // Stale cursor from a prior arming. Close it, but keep the plan alive — the same plan
      // instance re-runs. Deferred from reset() (see reset()'s note) so cloning cannot tear down
      // the original's still-aliased stream.
      openStream.close(plan.getContext());
      openStream = null;
    }
    armingGraph =
        (YTDBGraphInternal) getTraversal()
            .getGraph()
            .orElseThrow(
                () -> new IllegalStateException(
                    "YTDBMatchPlanStep cannot iterate: the host traversal has no attached"
                        + " graph. The boundary step is only installed on YTDB-backed"
                        + " traversals, so this indicates the step was driven after being"
                        + " detached from its graph."));
    var ctx = plan.getContext();
    // Rebind to the session active on THIS (iteration) thread before running. The plan may have
    // been compiled on another thread, and each server worker thread owns its own pooled session;
    // running against the compile-time session throws SessionNotActivatedException because YTDB
    // record reads require the session active on the reading thread. Both sessions belong to the
    // same database and share the schema/statistics the plan was compiled against, so the swap is
    // execution-safe. Unconditional (every arming): a re-iteration after reset() may run on a
    // different thread than the first pass.
    var tx = armingGraph.tx();
    tx.readWrite();
    ctx.setDatabaseSession(tx.getDatabaseSession());
    if (!inputParameters.isEmpty()) {
      ctx.setInputParameters(inputParameters);
    }
    // Rewind before re-running: REARMED means the plan already ran in a prior pass and its step
    // chain must be reset before it can execute again. A first open (NEW) has nothing to rewind.
    if (state == State.REARMED) {
      plan.reset(ctx);
    }
    ExecutionStream stream;
    try {
      stream = plan.start();
    } catch (RuntimeException | Error e) {
      // A partial start may have claimed cursors before throwing — release the plan before
      // propagating so nothing leaks. The original failure stays primary.
      try {
        plan.close();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    return stream;
  }

  /**
   * Closes the current arming's stream without touching the plan. Used on normal exhaustion, where
   * the plan must stay open so a {@link #reset()} + reopen can rewind and re-run it; the plan is
   * closed later by {@link #close()}.
   */
  private void releaseStream() {
    var stream = openStream;
    openStream = null;
    armingGraph = null;
    if (stream != null) {
      stream.close(plan.getContext());
    }
  }

  /**
   * Closes the current arming's stream and then the plan. The stream-close failure is the primary
   * exception; a plan-close failure is attached with {@code addSuppressed} rather than masking it.
   * Used on the terminal paths — an iteration failure and {@link #close()} — where the plan is not
   * re-run.
   */
  private void releaseStreamAndClosePlan() {
    var ctx = plan.getContext();
    var stream = openStream;
    openStream = null;
    armingGraph = null;
    if (stream == null) {
      plan.close();
      return;
    }
    try {
      stream.close(ctx);
    } catch (RuntimeException | Error e) {
      try {
        plan.close();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    plan.close();
  }

  /**
   * Re-arms the step for re-iteration on the same instance, honouring TinkerPop's reset contract
   * (a reset start step can be driven again). A started step (OPEN or DRAINED) moves to REARMED, so
   * its next open rewinds and re-runs the plan; a NEW step that never ran stays NEW (its first open
   * must not rewind an unstarted plan), and a CLOSED step stays CLOSED (a plan closed for good is
   * not revived by a reset).
   *
   * <p>The open stream is deliberately not closed here. {@code AbstractStep.clone()} calls {@code
   * reset()} on the freshly-cloned instance while that clone still aliases THIS stream (the clone's
   * own references are cleared afterwards in {@link #clone()}); closing here would tear down the
   * original's in-flight cursor. Deferring the close to the next open (in {@link #openArming()})
   * removes that hazard without a guard flag.
   */
  @Override
  public void reset() {
    super.reset();
    if (state == State.OPEN || state == State.DRAINED) {
      state = State.REARMED;
    }
  }

  /**
   * Closes the plan's resources. Called by TinkerPop on stream exhaustion and on early traversal
   * termination (both through {@code Traversal.close()}, which closes every {@link AutoCloseable}
   * step). This is where the plan is closed on the normal path: exhaustion moves the step to DRAINED
   * — stream closed, plan left open so a reset before close can re-iterate — and leaves the plan for
   * this call to close. Idempotent via the CLOSED state. Gating entry on CLOSED rather than DRAINED
   * is deliberate — DRAINED still holds an open plan, so a DRAINED-gated early return would skip the
   * plan close and leak the cursor.
   */
  @Override
  public void close() {
    if (state == State.CLOSED) {
      return;
    }
    // Any state past NEW has started the plan (the old `everStarted` guard): a NEW step never opened
    // and holds nothing to release.
    boolean started = state != State.NEW;
    state = State.CLOSED;
    if (openStream != null) {
      // A stream is still open (partial consume, or a reset that deferred its close): release the
      // stream and the plan.
      releaseStreamAndClosePlan();
    } else if (started) {
      // Exhaustion already closed the stream; close the still-open plan now.
      plan.close();
    }
    // Never opened and never started: nothing to release.
  }

  @Override
  public YTDBMatchPlanStep<S, E> clone() {
    var cloned = (YTDBMatchPlanStep<S, E>) super.clone();
    // Give the clone its own deep plan copy against an ISOLATED CHILD context — a fresh
    // BasicCommandContext parented to the original plan's context — mirroring HashJoinMatchStep's
    // build-side isolation. The child owns its own unsynchronised $current / $matched / statistics
    // maps, so the original's and the clone's executions cannot race on or leak that per-run state,
    // while database session, input parameters, and timeout still resolve through the parent.
    // Copying against plan.getContext() directly would leave both plans on the same context,
    // defeating the isolation this clone exists to provide.
    //
    // INVARIANT the isolation depends on: the parent (template) context must stay free of per-run
    // variables. A child write propagates UP to the parent only for a key the parent already holds
    // (BasicCommandContext.setVariable / setSystemVariable), so as long as the template context
    // carries no $current / $matched / alias / LET bindings, each clone writes those to its own
    // child map and concurrent clones never touch the shared parent. Track 2's single-node g.V()
    // pattern seeds no such variables, so the invariant holds. A later track that seeds alias or LET
    // variables onto the plan's context at BUILD time would break it — the shared parent would then
    // be written concurrently through its unsynchronised maps. See the clone-isolation note in the
    // design doc.
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(plan.getContext());
    // Plain field write: the field is non-final (see its declaration), the copy is independent, and
    // the write happens before the clone is published to any other thread.
    cloned.plan = plan.copy(isolatedCtx);
    // Drop the per-arming references super.clone() copied by value and put the clone in its NEW
    // starting state. The clone's plan copy has never run and owns no open stream; without the state
    // reset it would inherit the original's lifecycle position — a clone taken from an already-closed
    // step would be born CLOSED and never close its own fresh plan copy.
    cloned.openStream = null;
    cloned.armingGraph = null;
    cloned.state = State.NEW;
    return cloned;
  }

  /** Projects one result row onto the configured output payload. */
  private Object project(Result row) {
    return switch (outputType) {
      case ELEMENT -> projectElement(row, armingGraph);
    };
  }

  /**
   * Projects the matched element bound to {@link #boundaryAlias}, dispatching on {@link
   * #returnClass}: a vertex-producing prefix ({@code g.V()}, {@code .out(...)}) emits a TinkerPop
   * {@link Vertex}, an edge-producing prefix ({@code g.E()}, {@code .outE(...)}) a {@link Edge}.
   *
   * <p>Only the vertex arm is wired today — the translator recognises no edge-producing prefix in
   * the current scope, so {@code returnClass} is always {@code Vertex.class} and the edge arm is
   * unreachable. The branch is written out anyway so the field's role (it selects the element kind,
   * orthogonally to {@link #outputType} selecting the payload shape) is visible before the edge
   * track lands; that track fills in edge projection in place of the throw.
   *
   * <p>Package-private so unit tests can exercise projection directly.
   */
  Object projectElement(Result row, YTDBGraphInternal graph) {
    if (Vertex.class.isAssignableFrom(returnClass)) {
      return projectVertex(row, graph);
    }
    if (Edge.class.isAssignableFrom(returnClass)) {
      throw new UnsupportedOperationException(
          "Gremlin-to-MATCH edge projection is not implemented yet; the translator recognises only"
              + " vertex-producing prefixes in the current scope (returnClass="
              + returnClass.getName() + ").");
    }
    throw new IllegalStateException(
        "Boundary return class must be a Vertex or Edge subtype, but was "
            + returnClass.getName() + ".");
  }

  /**
   * Extracts the matched vertex from {@code row} under {@link #boundaryAlias} and wraps it as a
   * TinkerPop {@link Vertex}. Returns {@code null} when the row does not bind the alias to a vertex
   * (e.g. an optional node that did not match) — downstream native steps treat a null payload as
   * "absent", the same as any other null.
   */
  private Vertex projectVertex(Result row, YTDBGraphInternal graph) {
    var rawVertex = row.getVertex(boundaryAlias);
    if (rawVertex == null) {
      return null;
    }
    return new YTDBVertexImpl(graph, rawVertex);
  }
}
