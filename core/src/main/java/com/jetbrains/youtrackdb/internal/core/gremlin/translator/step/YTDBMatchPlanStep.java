package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.util.CloseableIteratorWithCallback;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

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
 *   <li><b>Iteration:</b> on first {@code processNextStart}, the supplier fires. It first
 *       rebinds the plan's context to the session active on the current (iteration) thread —
 *       the plan was built on a possibly-different thread, and YouTrackDB record reads require
 *       the session to be active on the reading thread (see {@code createIterator}). Then
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
 *       deep copy} of the plan, copied against an <em>isolated child</em>
 *       {@code CommandContext} (fresh {@code BasicCommandContext} parented to the original
 *       plan's context, mirroring {@code HashJoinMatchStep}'s build-side isolation). A
 *       {@link SelectExecutionPlan}-family plan carries mutable per-run state — both the
 *       step chain and the context's variable maps ({@code $current}, {@code $matched},
 *       step statistics), which are plain unsynchronised {@code HashMap}s. Copying against
 *       an isolated child gives the clone its own variable maps while still resolving the
 *       shared database session, input parameters, and timeout through the parent, so two
 *       executions cannot race on or leak per-run context state. Cloning also resets the
 *       inherited {@code iterator}/{@code done} fields that {@link GraphStep} maintains
 *       privately and re-binds the iterator supplier to the clone.
 * </ul>
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted element type (currently always a TinkerPop {@link Vertex} per the
 *            single supported output type; later tracks parameterise this)
 */
public final class YTDBMatchPlanStep<S, E extends Element> extends GraphStep<S, E> {

  // Non-final so clone() can install the clone's independent plan copy with a plain field
  // write. A final field would force a post-construction reflective write after
  // super.clone() has frozen it, which voids the JMM final-field publication guarantee for
  // any thread that later receives the clone without a happens-before edge. A normal field
  // write inside clone() (before the clone is handed to any other thread) has no such hazard.
  private InternalExecutionPlan plan;
  private final String boundaryAlias;
  private final BoundaryOutputType outputType;

  /**
   * Guards against opening this instance's plan twice within a single arming.
   * Each boundary step owns a single {@link InternalExecutionPlan} instance; the plan's
   * underlying step chain does not reset its execution state on a second {@code plan.start()}
   * call (it would silently re-iterate against already-consumed cursors). Set {@code true}
   * by {@link #createIterator()} once the plan is started; a second {@code createIterator}
   * call without an intervening {@link #reset()} throws {@link IllegalStateException} rather
   * than letting the silent-divergence bug reach end users. {@link #reset()} clears this flag
   * to re-arm the step for a fresh iteration; {@link #clone()} clears it on the clone so each
   * clone starts from a clean slate.
   */
  private boolean started;

  /**
   * Tracks whether this instance's plan has ever been started. Unlike {@link #started} (which
   * {@link #reset()} clears), this stays {@code true} across a reset so {@link #createIterator()}
   * knows a re-arm needs a {@code plan.reset(ctx)} before {@code plan.start()} — the plan's
   * step chain must be rewound before it can be re-executed. A never-started plan skips the
   * reset. {@link #clone()} clears this on the clone (its copied plan has never run).
   */
  private boolean everStarted;

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
    super(traversal, returnClass, /*isStart*/ true /*ids*/);
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
   * Renders a stable, one-line marker identifying this as a translated MATCH boundary step,
   * e.g. {@code YTDBMatchPlanStep(node,ELEMENT)}. Because the strategy replaces a recognised
   * traversal's entire native step chain with this single step, {@code traversal.explain()}
   * shows this marker in place of the native step boxes — the visible signal that translation
   * happened. Per-track end-to-end tests assert on the presence of this marker to pin which
   * shapes translate and which decline (see {@code GremlinToMatchSmokeTest}). The marker is
   * deliberately concise: it does not inline the MATCH plan tree, which stays reachable via
   * {@link #getPlan()} and YQL's EXPLAIN tooling.
   */
  @Override
  public String toString() {
    return StringFactory.stepString(this, boundaryAlias, outputType);
  }

  /**
   * Produces a fresh iterator that pulls rows from the plan's {@link ExecutionStream} and
   * projects each one to a TinkerPop element. Package-private rather than {@code private}
   * so the unit test can drive it directly with a stub plan.
   *
   * <p>Throws {@link IllegalStateException} on a second invocation within a single arming —
   * a second {@code plan.start()} on an already-open plan would silently re-iterate against
   * consumed cursors. To re-run the plan, either {@link #reset()} the step (which re-arms it
   * in place, rewinding the plan via {@code plan.reset(ctx)} on the next open) or
   * {@link #clone()} it (which gives the clone its own fresh plan copy). When the step was
   * previously opened and then reset, this method rewinds the plan with
   * {@link InternalExecutionPlan#reset(com.jetbrains.youtrackdb.internal.core.command.CommandContext)}
   * before re-starting it, so the second pass runs from the top of the plan's step chain.
   */
  Iterator<E> createIterator() {
    if (started) {
      throw new IllegalStateException(
          "YTDBMatchPlanStep.createIterator() invoked twice without an intervening reset(); "
              + "boundary steps own a single execution plan and open it once per arming. "
              + "Call reset() to re-run the plan in place, or clone the step to drive an "
              + "independent second pass.");
    }
    var ctx = plan.getContext();
    // Resolve the graph first — getTraversal().getGraph() can throw (Optional.empty().orElseThrow)
    // or the cast to YTDBGraphInternal can throw. Doing it before plan.start() means a failure
    // here leaks nothing: the plan has not been started and the close hook is only installed once
    // CloseableIteratorWithCallback has been constructed.
    var graph = (YTDBGraphInternal) getTraversal().getGraph().orElseThrow();
    // Rebind the plan to the session active on the CURRENT (iteration) thread before running it.
    // The plan was built at strategy-application time against the session active then. On the
    // server each worker thread owns its own pooled session (the graph's underlying session is
    // thread-local), and traversal compilation can run on a different thread than iteration.
    // Running the plan against the compile-time session throws SessionNotActivatedException:
    // YouTrackDB record reads assert the session is active on the current thread, and only the
    // iteration thread's own session is. Re-resolving here, the same way the native YTDBGraphStep
    // does in elements(), keeps the plan bound to the thread-active session. Both sessions belong
    // to the same database and share the schema and statistics the plan was compiled against, so
    // the swap is execution-safe. This must precede plan.reset()/plan.start() so the rewind and
    // the run both see the correct session.
    var tx = graph.tx();
    tx.readWrite();
    ctx.setDatabaseSession(tx.getDatabaseSession());
    // A re-arm after a prior run (reset() cleared `started` but left `everStarted` set) must
    // rewind the plan's step chain before re-starting it — otherwise plan.start() would
    // re-iterate against already-consumed cursors. A never-started plan needs no rewind.
    if (everStarted) {
      plan.reset(ctx);
    }
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
    everStarted = true;
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
  public YTDBMatchPlanStep<S, E> clone() {
    // super.clone() (AbstractStep.clone) already re-arms the inherited iterator/done state
    // and calls reset() on the clone. We must NOT call reset() again here, and reset() must
    // not touch the plan: at this point cloned.plan still aliases the ORIGINAL's plan
    // (super.clone() shallow-copies the reference), so resetting the plan through the clone
    // would corrupt the original's in-flight run. reset() therefore only re-arms the
    // per-instance guard (started) and never re-executes the plan — the plan is re-opened
    // lazily in createIterator() when needed. See reset()'s Javadoc.
    var cloned = (YTDBMatchPlanStep<S, E>) super.clone();
    // Give the clone its OWN deep copy of the plan rather than sharing the original's.
    // A SelectExecutionPlan-family plan carries mutable per-run state (both an independent
    // step chain AND the CommandContext's variable maps), so it is not safe to execute
    // concurrently or to re-run without a copy(); the plan's own Javadoc mandates
    // "copied via copy() before each execution". TinkerPop clones a traversal (and thus
    // every step) once per execution, so copying here is exactly the per-execution
    // isolation point.
    //
    // Copy against an ISOLATED CHILD context — a fresh BasicCommandContext parented to the
    // original plan's context — rather than the original context itself. This mirrors
    // HashJoinMatchStep's build-side isolation (buildPlan.copy(isolatedCtx)). The child owns
    // its own unsynchronised variable maps ($current, $matched, step statistics) so the
    // original's and the clone's executions cannot race on or leak that state, while it still
    // resolves the database session, input parameters, and timeout through the parent. Copying
    // against plan.getContext() directly would leave both plans pointing at the SAME context,
    // defeating the isolation this clone exists to provide.
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(plan.getContext());
    // super.clone()'s shallow copy left `cloned.plan` pointing at the original plan. The
    // field is non-final (see the field declaration), so assign the independent copy with a
    // plain write — no reflection, and no JMM final-field-freeze hazard because the write
    // happens before the clone is published to any other thread.
    cloned.plan = plan.copy(isolatedCtx);
    // Clear both open-tracking flags on the clone. super.clone() copies primitive fields by
    // value, so without this the clone would inherit the original's flags: `started=true`
    // would make the clone's first createIterator() throw, and `everStarted=true` would make
    // it needlessly rewind its already-fresh copied plan. The clone's copy has never run.
    cloned.started = false;
    cloned.everStarted = false;
    // Re-bind the supplier to the clone, not the original. setIteratorSupplier(this::...)
    // in the ctor captured the original instance; without re-binding here, iterating the
    // clone would call createIterator() on the original step and start the ORIGINAL's plan
    // instead of the clone's copy. Pointing the supplier at the clone keeps the two
    // executions independent — each starts its own plan copy on its own demand.
    cloned.setIteratorSupplier(cloned::createIterator);
    return cloned;
  }

  /**
   * Re-arms this step for re-iteration, honouring {@link GraphStep}'s reset contract: after
   * {@code reset()} a start step can be driven again. Besides clearing the inherited
   * {@code done}/{@code iterator} state (via {@code super.reset()}), this clears the local
   * {@code started} guard so the next {@link #createIterator()} may open the plan again.
   *
   * <p>This deliberately does <em>not</em> call {@code plan.reset(ctx)} here. {@code reset()}
   * is invoked by {@code AbstractStep.clone()} on the freshly-cloned instance while the clone
   * still aliases the ORIGINAL's plan (the clone's own copy is installed later in
   * {@link #clone()}); rewinding the plan here would corrupt the original's in-flight run.
   * The plan rewind is therefore deferred to {@code createIterator()}, which rewinds via
   * {@code plan.reset(ctx)} only when re-arming a previously-opened plan (tracked by
   * {@code everStarted}), by which point the instance owns the plan it will run.
   *
   * <p>Without this override, {@code GraphStep.reset()} would re-arm only the inherited
   * fields and leave {@code started == true}, so a TinkerPop path that resets a start step
   * and re-iterates it (traversal reuse rather than a fresh clone) would trip the guard in
   * {@code createIterator()} and throw {@link IllegalStateException} instead of re-running
   * the plan. {@link #clone()} remains the isolation point for independent/concurrent
   * executions; {@code reset()} is the in-place re-run path on a single instance.
   */
  @Override
  public void reset() {
    super.reset();
    // Only the per-arming guard is cleared here; everStarted persists so createIterator()
    // knows a re-open needs a plan.reset(ctx). The plan itself is NOT rewound here — see the
    // Javadoc for why (the clone-aliasing hazard during super.clone()).
    started = false;
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
