package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Objects;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
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
 *   <li><b>Exhaustion / close:</b> when the stream runs dry, when iterating it throws, or on an
 *       explicit {@link #close()}
 *       (which TinkerPop invokes on early termination — e.g. a downstream limit cuts iteration
 *       short — via {@code Traversal.close()} closing every {@link AutoCloseable} step), the stream
 *       is closed first, then the plan. A stream-close failure is the primary exception; a
 *       plan-close failure is attached with {@code addSuppressed}. Close is idempotent.
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

  // The current arming's open stream, or null before the first open / after close. Single source of
  // truth — there is no inherited iterator to shadow.
  private ExecutionStream openStream;

  // The graph resolved for the current arming; used to wrap projected vertices.
  private YTDBGraphInternal armingGraph;

  // false means openStream (if any) belongs to a superseded arming and must be closed and replaced
  // on the next open. This is how a reopen after reset() is told apart from continued iteration of
  // the current stream.
  private boolean armed;

  // The plan has run at least once, so it must be rewound (plan.reset) before it can run again.
  private boolean everStarted;

  // This arming is finished — exhausted or explicitly closed, resources released. processNextStart
  // ends immediately while set; reset() clears it to re-arm.
  private boolean done;

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
      Traversal.Admin<S, E> traversal,
      Class<E> returnClass,
      InternalExecutionPlan plan,
      String boundaryAlias,
      BoundaryOutputType outputType) {
    super(traversal);
    this.returnClass = Objects.requireNonNull(returnClass, "returnClass must not be null");
    this.plan = Objects.requireNonNull(plan, "plan must not be null");
    this.boundaryAlias = Objects.requireNonNull(boundaryAlias, "boundaryAlias must not be null");
    this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
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
   * Throws {@link FastNoSuchElementException} once the stream is exhausted, closing the stream and
   * plan as it does so. A failure while iterating the stream also closes the stream and plan before
   * propagating, so a stream that threw part-way does not leak until traversal teardown.
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected Traverser.Admin<E> processNextStart() {
    if (done) {
      throw FastNoSuchElementException.instance();
    }
    if (!armed) {
      openStream = openArming();
      armed = true;
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
      // A failure while iterating the open stream (hasNext / next / projection) releases this
      // arming — stream then plan — before propagating, matching the design's boundary-step
      // lifecycle: an exception must not leave the cursor open until traversal teardown. The
      // iteration failure stays primary; a close failure is attached with addSuppressed. The arming
      // is marked done so a retry does not reopen the just-closed plan.
      done = true;
      try {
        releaseArming();
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
    // Stream drained: release this arming (stream then plan) and signal end.
    done = true;
    releaseArming();
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
    if (everStarted) {
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
    everStarted = true;
    return stream;
  }

  /**
   * Closes the current arming's stream and then the plan. The stream-close failure is the primary
   * exception; a plan-close failure is attached with {@code addSuppressed} rather than masking it.
   */
  private void releaseArming() {
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
   * (a reset start step can be driven again). Besides clearing the inherited state via {@code
   * super.reset()}, it marks any open stream as belonging to a superseded arming.
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
    done = false;
    armed = false;
  }

  /**
   * Closes the plan's resources. Called by TinkerPop on early traversal termination (through {@code
   * Traversal.close()}, which closes every {@link AutoCloseable} step) and internally on stream
   * exhaustion. Idempotent: once the step is finished, further calls are no-ops.
   */
  @Override
  public void close() {
    if (done) {
      return;
    }
    done = true;
    if (armed || openStream != null) {
      releaseArming();
    } else if (everStarted) {
      plan.close();
    }
    // Never opened and never started: nothing to release.
  }

  @Override
  public YTDBMatchPlanStep<S, E> clone() {
    @SuppressWarnings("unchecked")
    var cloned = (YTDBMatchPlanStep<S, E>) super.clone();
    // Give the clone its own deep plan copy against an ISOLATED CHILD context — a fresh
    // BasicCommandContext parented to the original plan's context — mirroring HashJoinMatchStep's
    // build-side isolation. The child owns its own unsynchronised $current / $matched / statistics
    // maps, so the original's and the clone's executions cannot race on or leak that per-run state,
    // while database session, input parameters, and timeout still resolve through the parent.
    // Copying against plan.getContext() directly would leave both plans on the same context,
    // defeating the isolation this clone exists to provide.
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(plan.getContext());
    // Plain field write: the field is non-final (see its declaration), the copy is independent, and
    // the write happens before the clone is published to any other thread.
    cloned.plan = plan.copy(isolatedCtx);
    // Drop the per-arming references super.clone() copied by value. The clone's plan copy has never
    // run and owns no open stream; without this it would inherit the original's open cursor and
    // "already started" flags.
    cloned.openStream = null;
    cloned.armingGraph = null;
    cloned.armed = false;
    cloned.everStarted = false;
    cloned.done = false;
    return cloned;
  }

  /**
   * Projects one result row onto the configured output payload. Package-private accessors ({@link
   * #projectElement}) let unit tests exercise projection without driving the full iterator
   * lifecycle.
   */
  private Object project(Result row) {
    return switch (outputType) {
      case ELEMENT -> projectElement(row, armingGraph);
    };
  }

  /**
   * Extracts the matched vertex from {@code row} under {@link #boundaryAlias} and wraps it as a
   * TinkerPop {@link Vertex}. Returns {@code null} when the row does not bind the alias to a vertex
   * (e.g. an optional node that did not match) — downstream native steps treat a null payload as
   * "absent", the same as any other null.
   *
   * <p>Package-private so unit tests can exercise projection directly.
   */
  Vertex projectElement(Result row, YTDBGraphInternal graph) {
    var rawVertex = row.getVertex(boundaryAlias);
    if (rawVertex == null) {
      return null;
    }
    return new YTDBVertexImpl(graph, rawVertex);
  }
}
