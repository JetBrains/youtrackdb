package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * Shared base for the boundary steps that bridge a compiled YTDB MATCH plan to TinkerPop's
 * traverser-driven iteration. When the Gremlin-to-MATCH strategy recognises a traversal end-to-end
 * it replaces the entire step list with a single boundary step; translation is all-or-nothing, so a
 * boundary step is always the traversal's only step.
 *
 * <p>This base owns the parts that are the same whether the boundary reads one plan or several: the
 * single-{@link ExecutionStream} open / drain / close primitives, the per-arming row projection (the
 * graph is injected per arming, never captured at construction, so a re-armed or cloned step always
 * projects against the graph resolved for the current pass), the {@link ResultShaping} read, and
 * {@link AutoCloseable}. The single-vs-N-plan orchestration — which plan(s) supply the stream, how
 * the plan is rewound, and how the plan(s) are closed — is delegated to the concrete subclasses
 * through the {@link #planContext()} / {@link #rewindPlan(CommandContext)} / {@link
 * #startPlanStream()} / {@link #closePlan()} hooks. {@code YTDBMatchPlanStep} is the single-plan
 * concrete form; a multi-plan form reuses the same lifecycle without re-implementing it.
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
 *       ExecutionStream} via {@link #openArming()}. It rebinds the plan's context to the session
 *       active on the current (iteration) thread first — the plan may have been compiled on a
 *       different thread, and YTDB record reads require the session active on the reading thread.
 *       Each subsequent call pulls one {@link Result} row, projects it per {@link
 *       BoundaryOutputType}, and generates a traverser. Wrapping goes through {@link YTDBVertexImpl}
 *       so downstream native steps see TinkerPop element types.
 *   <li><b>Exhaustion:</b> when the stream runs dry the arming's <em>stream</em> is closed but the
 *       plan is kept open, so a {@link #reset()} + reopen can rewind and re-run it — a closed {@link
 *       SelectExecutionPlan} cannot be restarted at all (its steps' close guard is sticky), which is
 *       why re-arming from a closed plan has to copy it instead of rewinding it (see {@link
 *       #replaceClosedPlanWithCopy()}). The plan itself is closed by the {@link #close()} TinkerPop
 *       fires on exhaustion (via {@code DefaultTraversal.hasNext()} closing the traversal through
 *       {@code CloseableIterator.closeIterator} once the boundary signals no more rows).
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
 *   <li><b>Reset after close:</b> a closed step re-arms too, by <em>replacing</em> the plan instead
 *       of rewinding it — see {@link #replaceClosedPlanWithCopy()}. Most re-iterations take this
 *       path: {@code toList()} closes the traversal in a {@code finally}, so every {@code
 *       traversal.toList(); admin.reset(); traversal.toList()} sequence arrives closed. A close
 *       that arrived before the step ever opened is the one exception: it released nothing, so the
 *       re-arm starts the original plan rather than a copy of it. A plan start that threw does not
 *       qualify — its own handler already closed the plan — so that step re-arms through the copy
 *       path like any other closed step.
 * </ul>
 *
 * <h2>The whole state machine</h2>
 * The prose above and the per-constant Javadoc each describe a slice. This table is the machine in
 * one place; when the two disagree, the enum constants are authoritative.
 *
 * <table border="1">
 *   <caption>State transitions and their effect on the plan</caption>
 *   <tr><th>From</th><th>Trigger</th><th>To</th><th>Effect on the plan</th></tr>
 *   <tr><td>NEW</td><td>open</td><td>OPEN</td><td>started, not rewound (it is pristine)</td></tr>
 *   <tr><td>NEW</td><td>{@code close()}</td><td>CLOSED_UNSTARTED</td><td>nothing released</td></tr>
 *   <tr><td>NEW</td><td>start threw</td><td>CLOSED</td><td>released by the failure handler</td></tr>
 *   <tr><td>OPEN</td><td>stream drained</td><td>DRAINED</td><td>cursor closed, plan left open</td></tr>
 *   <tr><td>OPEN / DRAINED</td><td>{@code reset()}</td><td>REARMED</td><td>untouched; the next open rewinds</td></tr>
 *   <tr><td>OPEN / DRAINED</td><td>{@code close()}</td><td>CLOSED</td><td>stream and plan released</td></tr>
 *   <tr><td>REARMED</td><td>open</td><td>OPEN</td><td>rewound in place, same plan object</td></tr>
 *   <tr><td>CLOSED</td><td>{@code reset()}</td><td>REARMED_AFTER_CLOSE</td><td>still closed; the copy is deferred</td></tr>
 *   <tr><td>CLOSED_UNSTARTED</td><td>{@code reset()}</td><td>NEW</td><td>untouched, still pristine</td></tr>
 *   <tr><td>REARMED_AFTER_CLOSE</td><td>open</td><td>OPEN</td><td>replaced by a fresh copy, then started</td></tr>
 * </table>
 *
 * <p>CLOSED, CLOSED_UNSTARTED and DRAINED all end {@link #processNextStart()} immediately. The two
 * CLOSED states make {@link #close()} a no-op; DRAINED does not, because it still holds an open plan.
 *
 * <h2>Which plan object an observer sees</h2>
 * The two re-arm paths install different plan objects — a rewind keeps the same plan, a re-arm
 * after close swaps in a copy — so the plan a subclass accessor exposes ({@code
 * YTDBMatchPlanStep.getPlan()}, {@code MultiPlanMatchStep.getPlans()}) is not stable across a step's
 * whole life. The contract those accessors carry, and the reason the swap is deferred to the next
 * open rather than done inside {@link #reset()}, is: <b>an observer reading the plan at any point
 * from the start of a pass to the {@link #close()} that ends it sees the plan object that produced
 * that pass's rows.</b> {@code YTDBQueryMetricsStep} reads the plan from inside the listener
 * callback it fires on close, so this is what makes the reported execution plan belong to the run
 * being reported. Between a {@link #reset()} and the next open the accessor still returns the
 * previous pass's plan, which is the same window in which the step has produced no new rows to
 * attribute.
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted payload type ({@link Vertex} for {@link BoundaryOutputType#ELEMENT}; Map /
 *            scalar / value for the other output types — the Element bound is historical for the
 *            ELEMENT path and is unchecked-cast for non-element payloads)
 */
public abstract class AbstractMatchPlanStep<S, E extends Element> extends AbstractStep<S, E>
    implements AutoCloseable {

  /**
   * RETURN aliases for {@code group} / {@code groupCount} key and value columns — must match
   * {@code GremlinAggregateAssembler.GROUP_KEY_ALIAS} / {@code GROUP_VALUE_ALIAS}.
   */
  private static final String GROUP_KEY_ALIAS = "key";

  private static final String GROUP_VALUE_ALIAS = "value";

  /** Sentinel from {@link #projectOrSkip} when the row must not emit a traverser. */
  private static final Object SKIP = new Object();

  private final Class<E> returnClass;
  private final String boundaryAlias;
  private final BoundaryOutputType outputType;
  /** Positional-parameter values for this walk ({@code ?} slots), or empty when none. */
  private final Map<Object, Object> inputParameters;

  /**
   * Boundary shaping — the seven row-projection flags that dictate how each MATCH row projects onto
   * a traverser (row dropping via {@code dropNullRows} / {@code dropOnAbsent}, presence-checked
   * property keys, valueMap list wrapping, group-map accumulation, singleton-map unwrapping, and
   * elementMap token keys) plus the ordered list-shaping ops applied to the projected payload stream
   * afterward ({@link ResultShaping#listShapingOps()}, empty for every traversal that has no
   * list-shaping terminator).
   */
  private final ResultShaping shaping;

  /** {@link ResultShaping#presencePropertyKeys()} as a set, for O(1) membership checks in {@link
   *  #projectMap}. */
  private final Set<String> presenceKeySet;

  // The current arming's open stream, or null before the first open / after close. Single source of
  // truth — there is no inherited iterator to shadow.
  private ExecutionStream openStream;

  // The current arming's shaped payload iterator: the projected rows (or the drained group map) fed
  // through the ordered list-shaping stage. Built lazily on the first pull of an arming and rebuilt
  // fresh on every (re)open; null before the first build and after the stream is released. One
  // traverser is emitted per payload this iterator yields, so a cardinality-changing op emits more
  // or fewer traversers than the source produced — the exhaustion signal is this iterator running
  // dry, not the underlying stream.
  private Iterator<Object> shapedPayloads;

  // The graph resolved for the current arming; used to wrap projected vertices.
  private YTDBGraphInternal armingGraph;

  /**
   * The lifecycle position of the boundary step. One value replaces the four interlocking booleans
   * the step used to carry ({@code armed} / {@code everStarted} / {@code done} / {@code closed}):
   * every transition is now a single field write, and a reader tracks one state instead of a
   * quadruple whose legal combinations had to be inferred.
   */
  private enum State {
    /**
     * The plan this step holds has never been started. Reached three ways: construction,
     * {@link #reset()} before the plan ever ran, and {@link #reset()} from
     * {@link #CLOSED_UNSTARTED}. The next open starts the plan WITHOUT rewinding it — there is no
     * consumed state to rewind.
     *
     * <p><b>Invariant:</b> a NEW step's plan is pristine. Every route that closes or otherwise
     * consumes the plan MUST record a state; leaving the state NEW after closing a plan makes the
     * next open start a dead chain, which yields no rows because {@code AbstractExecutionStep}'s
     * close guard is sticky. Three sites read NEW as "pristine" and would be wrong if a fourth
     * route violated it: the rewind skip in {@link #openArming()}, {@link #close()}'s mapping to
     * {@link #CLOSED_UNSTARTED}, and {@link #reset()}'s CLOSED_UNSTARTED-to-NEW edge.
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
     * {@link #reset()} after at least one run that left the plan open. The next open closes any
     * cursor a partial consume left open and rewinds the plan ({@code plan.reset}) before starting
     * it.
     */
    REARMED,
    /**
     * The plan is closed — by {@link #close()}, by the terminal iteration-failure path, or by a
     * plan start that threw and released the plan on its way out. {@link #processNextStart()} ends
     * immediately and {@link #close()} is a no-op. A {@link #reset()} moves to {@link
     * #REARMED_AFTER_CLOSE} rather than reviving the closed plan.
     */
    CLOSED,
    /**
     * {@link #close()} arrived before the step ever opened, so it found nothing to release and the
     * plan is still unstarted. Iteration is terminal here exactly as in {@link #CLOSED}, but a
     * {@link #reset()} returns to {@link #NEW} rather than {@link #REARMED_AFTER_CLOSE}: the plan
     * this step holds has never run, so the next open can just start it. Folding this case into
     * {@link #CLOSED} would send it down the copy path instead, deep-copying a pristine plan (once
     * per child in the multi-plan step) and dropping the original with nothing left to close it.
     * The distinction is reachable from a caller that opens a traversal and returns before
     * iterating: {@code Traversal.close()} still closes every {@link AutoCloseable} step.
     *
     * <p>Only a {@link #NEW} step can arrive here, and {@link #NEW} is trustworthy as "the plan is
     * pristine" because the one route that closes a plan while the step has yet to open — a {@link
     * #startPlanStream()} that throws — records {@link #CLOSED} as it releases the plan.
     */
    CLOSED_UNSTARTED,
    /**
     * {@link #reset()} after the plan was closed. The next open cannot rewind — a closed plan's
     * steps stay closed, because {@code AbstractExecutionStep}'s close guard is sticky and {@code
     * ExecutionStepInternal.reset()} does not clear it — so it swaps in a fresh copy of the plan
     * via {@link #replaceClosedPlanWithCopy()} and starts that instead.
     */
    REARMED_AFTER_CLOSE
  }

  private State state = State.NEW;

  /**
   * Constructs a boundary base with the projection metadata shared by every boundary step.
   *
   * @param traversal       the host traversal (must not be null)
   * @param returnClass     the TinkerPop element class the step emits (currently {@link
   *                        Vertex}{@code .class})
   * @param boundaryAlias   the alias under which the matched element appears in each {@link Result}
   *                        row (must not be null)
   * @param outputType      how each row projects onto a traverser payload (must not be null)
   * @param inputParameters positional-parameter values for this walk ({@code ?} slots)
   * @param shaping         the row-projection shaping ({@link ResultShaping})
   */
  protected AbstractMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Map<Object, Object> inputParameters,
      @Nonnull ResultShaping shaping) {
    super(traversal);
    this.returnClass = returnClass;
    this.boundaryAlias = boundaryAlias;
    this.outputType = outputType;
    this.inputParameters = Map.copyOf(inputParameters);
    this.shaping = shaping;
    this.presenceKeySet = Set.copyOf(shaping.presencePropertyKeys());
  }

  /** The alias the step uses to look up the matched element in each row. */
  public String getBoundaryAlias() {
    return boundaryAlias;
  }

  /** The boundary output mode this step is configured for. */
  public BoundaryOutputType getOutputType() {
    return outputType;
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
   * the MATCH plan tree is reachable via YQL's EXPLAIN tooling.
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
    if (state == State.DRAINED || state == State.CLOSED || state == State.CLOSED_UNSTARTED) {
      // Exhausted or closed: no more payloads until a reset() re-arms the step.
      throw FastNoSuchElementException.instance();
    }
    if (state == State.NEW || state == State.REARMED || state == State.REARMED_AFTER_CLOSE) {
      // First open, or a reopen after reset(). openArming() rewinds the plan iff we are REARMED and
      // replaces it with a fresh copy iff we are REARMED_AFTER_CLOSE.
      openStream = openArming();
      state = State.OPEN;
      // Drop any shaped iterator a superseded arming left behind so the pull below rebuilds it
      // against the freshly opened stream. openArming() is outside the try below because a plan-start
      // failure is released by openArming() itself (closePlan, not the stream — none was opened).
      shapedPayloads = null;
    }
    try {
      if (shapedPayloads == null) {
        // Build the shared list-shaping stage on first pull of this arming — inside the try because
        // the group-barrier source drains the stream eagerly, and a drain failure must release the
        // plan through the terminal handler below.
        shapedPayloads = openShapedPayloads();
      }
      if (!shapedPayloads.hasNext()) {
        // The shaped stream is dry (the underlying stream is exhausted and every op has emitted all
        // it will). Close the arming's stream, keep the plan open for a possible reset + reopen.
        state = State.DRAINED;
        releaseStream();
        throw FastNoSuchElementException.instance();
      }
      return getTraversal().getTraverserGenerator().generate(shapedPayloads.next(), (Step) this,
          1L);
    } catch (FastNoSuchElementException e) {
      throw e;
    } catch (RuntimeException | Error e) {
      // A failure while producing the next payload (stream hasNext / next, projection, or an op) is
      // terminal: release the stream AND the plan before propagating. TinkerPop auto-closes the
      // traversal only on normal exhaustion (DefaultTraversal.hasNext -> closeIterator), never on a
      // thrown exception, so deferring the plan close here would leak the cursor until traversal
      // teardown. The iteration failure stays primary; a release failure is attached with
      // addSuppressed. Moving to CLOSED both ends iteration and marks the plan closed, so the
      // just-closed plan is never re-run.
      state = State.CLOSED;
      try {
        releaseStreamAndClosePlan();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  /**
   * Builds this arming's shaped payload iterator: the projection source (per-row for element / value
   * / map paths, or the drained group map for {@code group} / {@code groupCount}) with the ordered
   * list-shaping ops threaded through it. This is the single stage both projection paths reach, so a
   * list-shaping op composes over the group-barrier map exactly as it does over the per-row stream.
   */
  private Iterator<Object> openShapedPayloads() {
    Iterator<Object> source =
        shaping.accumulateMap() ? accumulatedGroupMapSource() : rowProjectionSource();
    return applyListShaping(source);
  }

  /**
   * Threads the ordered list-shaping ops over {@code source}, or returns {@code source} untouched
   * when there is no op. The empty-list case is a structural bypass, not a no-op stage wrapped around
   * the source: the projection stream flows straight through, so a traversal with no list-shaping
   * terminator keeps its per-row laziness — the first pull produces the first payload without
   * draining the stream. Wrapping even an empty op chain in a collect-apply-emit stage would pass
   * every behaviour-neutral test while destroying first-result latency and bounded memory.
   */
  private Iterator<Object> applyListShaping(Iterator<Object> source) {
    var ops = shaping.listShapingOps();
    if (ops.isEmpty()) {
      return source;
    }
    var shaped = source;
    for (ListShapingOp op : ops) {
      shaped = op.apply(shaped);
    }
    return shaped;
  }

  /**
   * A lazy iterator over the per-row projection payloads: each pull advances the underlying stream
   * to the next row that projects to a non-{@link #SKIP} payload, so {@code dropNullRows} / {@code
   * dropOnAbsent} rows are consumed but never emitted. Nulls are legitimate payloads (an unmatched
   * optional element projects to {@code null}), so emission is tracked by a separate flag rather
   * than a null sentinel.
   */
  private Iterator<Object> rowProjectionSource() {
    var ctx = planContext();
    var stream = openStream;
    return new Iterator<>() {
      private Object bufferedPayload;
      private boolean hasBuffered;

      @Override
      public boolean hasNext() {
        if (hasBuffered) {
          return true;
        }
        while (stream.hasNext(ctx)) {
          var payload = projectOrSkip(stream.next(ctx));
          if (payload != SKIP) {
            bufferedPayload = payload;
            hasBuffered = true;
            return true;
          }
        }
        return false;
      }

      @Override
      public Object next() {
        if (!hasBuffered && !hasNext()) {
          throw new NoSuchElementException();
        }
        var payload = bufferedPayload;
        bufferedPayload = null;
        hasBuffered = false;
        return payload;
      }
    };
  }

  /**
   * The group-barrier source: drains every GROUP BY row into one {@link LinkedHashMap} and yields it
   * as a single payload — native {@code group} / {@code groupCount} are barrier steps, so the whole
   * stream must be consumed before the one map emits. Eager by nature; called inside {@link
   * #processNextStart()}'s try so a drain failure releases the plan.
   */
  private Iterator<Object> accumulatedGroupMapSource() {
    var ctx = planContext();
    var map = new LinkedHashMap<Object, Object>();
    while (openStream.hasNext(ctx)) {
      var row = openStream.next(ctx);
      map.put(
          // Bare group()/groupCount() GROUP BY the element identity (@rid), and native keys the map
          // by the Vertex — so wrap the RID as a Vertex here, the same RID→Vertex conversion
          // elementMap columns use. Property / label key modulators are plain values and pass through.
          convertMapColumn(GROUP_KEY_ALIAS, row.getProperty(GROUP_KEY_ALIAS)),
          convertGroupValue(row.getProperty(GROUP_VALUE_ALIAS)));
    }
    return List.<Object>of(map).iterator();
  }

  /**
   * Opens the plan's stream for a fresh arming. Closes any stream left open by a superseded arming
   * (a reset after a partial consume), replaces the plan with a fresh copy when the previous pass
   * closed it, rebinds the plan to the thread-active session, rewinds the plan if it ran before and
   * is still open, then starts it.
   *
   * <p>The graph is resolved before {@link #startPlanStream()} so a resolution failure leaks
   * nothing: the plan has not been started. A missing graph throws {@link IllegalStateException}
   * rather than the {@link java.util.NoSuchElementException} of a bare {@code orElseThrow()} — the
   * latter is the iteration-end signal that {@link AbstractStep#hasNext()} swallows, which would
   * turn a genuine "no attached graph" bug into a silent empty result.
   */
  /**
   * Hook at the start of {@link #openArming()} so a subclass can materialise a live plan copy
   * before {@link #planContext()} is read. The single-plan step uses this to copy a shared cache
   * template on first open; the default is a no-op.
   */
  protected void preparePlanForArming() {
  }

  private ExecutionStream openArming() {
    preparePlanForArming();
    if (openStream != null) {
      // Stale cursor from a prior arming. Close it, but keep the plan alive — the same plan
      // instance re-runs. Deferred from reset() (see reset()'s note) so cloning cannot tear down
      // the original's still-aliased stream.
      openStream.close(planContext());
      openStream = null;
    }
    armingGraph =
        (YTDBGraphInternal) getTraversal()
            .getGraph()
            .orElseThrow(
                () -> new IllegalStateException(
                    "MATCH boundary step cannot iterate: the host traversal has no attached"
                        + " graph. The boundary step is only installed on YTDB-backed"
                        + " traversals, so this indicates the step was driven after being"
                        + " detached from its graph."));
    if (state == State.REARMED_AFTER_CLOSE) {
      // Swap the closed plan for a fresh copy BEFORE planContext() is read below. Today the swap is
      // invisible to planContext(): both hooks copy against the closed plan's own context, so the
      // read returns the same object either way. The ordering is held anyway as a constraint on
      // future hooks: a hook that derives its own context for the copy needs the session rebind —
      // and every later use of ctx here — addressed to the context the copy actually runs against.
      // The stale-cursor block above never fires in this state, because openStream is null on every
      // route that reaches it — but by a different argument per route, so check the one you care
      // about rather than assuming a single mechanism. processNextStart()'s terminal handler nulls
      // it through releaseStreamAndClosePlan(). close() reaches its closePlan() arm only when
      // openStream == null is the branch condition. openArming()'s own start-failure handler below
      // runs after the stale-cursor block above has already nulled it.
      replaceClosedPlanWithCopy();
    }
    var ctx = planContext();
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
      rewindPlan(ctx);
    }
    ExecutionStream stream;
    try {
      stream = startPlanStream();
    } catch (RuntimeException | Error e) {
      // A partial start may have claimed cursors before throwing — release the plan before
      // propagating so nothing leaks. The original failure stays primary.
      //
      // Record CLOSED first, so a closePlan() that itself throws still leaves the state honest.
      // The write matters beyond bookkeeping: this is the one path that closes the plan
      // while the step is still NEW, and NEW is read everywhere else as "this plan is pristine"
      // — the first open skips the rewind, and close() maps it to CLOSED_UNSTARTED, whose reset
      // returns the step to NEW to start that plan. Leaving the state NEW here would send a
      // later close() + reset() + pull down that path and start a chain that is already closed,
      // which yields no rows because the steps' close guard is sticky. CLOSED sends the same
      // sequence down the copy path instead, which is the only way a closed plan runs again.
      state = State.CLOSED;
      try {
        closePlan();
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
    // Drop the shaped iterator with the stream it read from — a re-arm rebuilds it against the fresh
    // stream, so a stale iterator must never outlive its source.
    shapedPayloads = null;
    if (stream != null) {
      stream.close(planContext());
    }
  }

  /**
   * Closes the current arming's stream and then the plan. The stream-close failure is the primary
   * exception; a plan-close failure is attached with {@code addSuppressed} rather than masking it.
   * Used on the terminal paths — an iteration failure and {@link #close()} — where the plan is not
   * re-run.
   */
  private void releaseStreamAndClosePlan() {
    var ctx = planContext();
    var stream = openStream;
    openStream = null;
    armingGraph = null;
    // Drop the shaped iterator with the stream it read from (see releaseStream()).
    shapedPayloads = null;
    if (stream == null) {
      closePlan();
      return;
    }
    try {
      stream.close(ctx);
    } catch (RuntimeException | Error e) {
      try {
        closePlan();
      } catch (RuntimeException | Error suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    closePlan();
  }

  /**
   * Re-arms the step for re-iteration on the same instance, honouring TinkerPop's reset contract
   * (a reset start step can be driven again). A started step whose plan is still open (OPEN or
   * DRAINED) moves to REARMED, so its next open rewinds and re-runs that plan; a CLOSED step moves
   * to REARMED_AFTER_CLOSE, so its next open runs a fresh copy of the plan instead; a NEW step that
   * never ran stays NEW, because its first open must not rewind an unstarted plan. A
   * CLOSED_UNSTARTED step — closed before it ever opened — returns to NEW for the same reason: its
   * plan has never run, so the next open starts it rather than copying it.
   *
   * <p>Neither the plan copy nor the stream close happens here — both are deferred to the next open
   * (in {@link #openArming()}), for the same reason. {@code AbstractStep.clone()} calls {@code
   * reset()} on the freshly-cloned instance while that clone still aliases THIS step's stream and
   * plan; the clone's own references are installed afterwards by the concrete step's {@code
   * clone()}. Closing the stream here would tear down the original's in-flight cursor, and copying
   * the plan here would mint a copy that the clone immediately discards and never closes. Deferring
   * both removes the hazards without a guard flag, and it is also what keeps the accessor contract
   * in the class comment ("an observer sees the plan that produced the pass's rows") true across a
   * reset that is never followed by a re-run.
   */
  @Override
  public void reset() {
    super.reset();
    if (state == State.OPEN || state == State.DRAINED) {
      state = State.REARMED;
    } else if (state == State.CLOSED) {
      state = State.REARMED_AFTER_CLOSE;
    } else if (state == State.CLOSED_UNSTARTED) {
      state = State.NEW;
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
   *
   * <p>REARMED_AFTER_CLOSE is deliberately not gated. A re-arm that actually ran leaves the step in
   * OPEN — {@link #processNextStart()} assigns OPEN as soon as {@link #openArming()} returns — so a
   * close observing REARMED_AFTER_CLOSE is looking at a re-arm that never got that far, and that is
   * exactly why the state must fall through. {@link #openArming()} installs the plan copy before the
   * session rebind and before the try that guards the plan start, and it runs outside {@link
   * #processNextStart()}'s terminal handler, so a throw between the two (the transaction rebind, for
   * one) leaves a live, unstarted copy that only this call can release. A throw from the guarded
   * start itself does not reach here in this state: that handler releases the copy and records
   * CLOSED, which the gate above catches. When the re-arm was simply never driven, the plan close
   * below lands on the already-closed original, which every {@code InternalExecutionPlan} treats as
   * a no-op.
   */
  @Override
  public void close() {
    if (state == State.CLOSED || state == State.CLOSED_UNSTARTED) {
      return;
    }
    if (state == State.NEW) {
      // Never opened: no stream, and a plan that has claimed nothing. Record that the close found
      // the step unstarted, so a reset() afterwards can start this plan instead of copying it.
      // A start that threw does not land here — openArming()'s handler closes the plan and records
      // CLOSED — so NEW at this point really does mean the plan is untouched.
      state = State.CLOSED_UNSTARTED;
      return;
    }
    // Every remaining state has started the plan (the old `everStarted` guard).
    state = State.CLOSED;
    if (openStream != null) {
      // A stream is still open (partial consume, or a reset that deferred its close): release the
      // stream and the plan.
      releaseStreamAndClosePlan();
    } else {
      // Exhaustion already closed the stream; close the still-open plan now.
      closePlan();
    }
  }

  /**
   * Resets the per-arming lifecycle fields to the NEW starting state on this instance. A concrete
   * step's {@code clone()} calls this on the freshly-cloned instance after installing the clone's
   * own plan copy: the clone must drop the per-arming references {@code super.clone()} copied by
   * value (its {@code openStream} / {@code armingGraph}) and start in NEW, or a clone taken from an
   * already-closed step would be born CLOSED and never close its own fresh plan copy. Kept in the
   * base so the concrete step never needs to reach the private lifecycle fields or the {@code State}
   * enum directly.
   */
  protected final void resetLifecycleForClone() {
    this.openStream = null;
    this.armingGraph = null;
    this.shapedPayloads = null;
    this.state = State.NEW;
  }

  /**
   * Projects one result row onto the configured output payload, or returns {@link #SKIP} when the
   * row must not emit a traverser ({@code dropOnAbsent} / {@code dropNullRows}).
   */
  private Object projectOrSkip(Result row) {
    return switch (outputType) {
      case ELEMENT -> projectElement(row, armingGraph);
      case MAP -> projectMap(row);
      case SINGLE_VALUE -> projectSingleValue(row);
      case SCALAR -> projectScalar(row);
    };
  }

  /**
   * Builds a {@link Map} from RETURN columns. The boundary-entity column (when present) is stripped
   * from the emitted map and used only for {@link EntityImpl#hasProperty} classification of
   * presence-checked keys. Absent keys are omitted; present-with-null keys are included.
   *
   * <p>The entity is loaded only when a presence-checked key needs {@code hasProperty} — a
   * {@code select} / {@code select().by(...)} map has an empty presence set, so it must not
   * {@code getEntity} the boundary alias (MATCH already stored that column as a RID, and a load
   * here would re-fetch a vertex the payload never reads). {@code unwrapSingletonMap} emits the
   * single column directly rather than allocating a one-entry {@link LinkedHashMap} and throwing
   * it away.
   */
  private Object projectMap(Result row) {
    var entity = presenceKeySet.isEmpty() ? null : resolveEntity(row);
    if (shaping.unwrapSingletonMap()) {
      var unwrapped = unwrapSingletonColumn(row, entity);
      if (unwrapped != BUILD_MAP) {
        return unwrapped;
      }
    }
    var names = row.getPropertyNames();
    var map = new LinkedHashMap<Object, Object>(Math.max(names.size(), 4));
    for (String name : names) {
      putMapColumn(map, row, name, entity);
    }
    return map;
  }

  /**
   * Sentinel: {@link #unwrapSingletonColumn} saw more than one emitted column, so {@link
   * #projectMap} must build the map. Distinct from a legitimate {@code null} payload.
   */
  private static final Object BUILD_MAP = new Object();

  /**
   * Native {@code SelectOneStep} shape: one non-boundary column becomes the traverser payload, not
   * a singleton map. Zero emitted columns still return an empty map (same as building-then-unwrapping
   * when every presence-checked key was absent). More than one column falls through to the map path
   * so a mis-set flag cannot drop entries.
   */
  private Object unwrapSingletonColumn(Result row, @Nullable EntityImpl entity) {
    String onlyName = null;
    int emitted = 0;
    for (String name : row.getPropertyNames()) {
      if (name.equals(boundaryAlias)) {
        continue;
      }
      if (presenceKeySet.contains(name) && (entity == null || !entity.hasProperty(name))) {
        continue;
      }
      emitted++;
      if (emitted > 1) {
        return BUILD_MAP;
      }
      onlyName = name;
    }
    if (emitted == 0) {
      return new LinkedHashMap<Object, Object>();
    }
    return mapColumnValue(row, onlyName, entity);
  }

  private void putMapColumn(
      LinkedHashMap<Object, Object> map, Result row, String name, @Nullable EntityImpl entity) {
    if (name.equals(boundaryAlias)) {
      return;
    }
    var value = mapColumnValue(row, name, entity);
    if (value == SKIP) {
      return;
    }
    map.put(mapKeyForColumn(name), value);
  }

  /**
   * One RETURN column as a map value, or {@link #SKIP} when a presence-checked key is absent (the
   * column must not appear in the map).
   */
  private Object mapColumnValue(Result row, String name, @Nullable EntityImpl entity) {
    if (presenceKeySet.contains(name)) {
      if (entity == null || !entity.hasProperty(name)) {
        return SKIP;
      }
      var value = convertValue(entity.getProperty(name));
      return shaping.wrapMapValuesInLists() ? Collections.singletonList(value) : value;
    }
    return convertMapColumn(name, row.getProperty(name));
  }

  /**
   * Converts a non-presence MAP column. Select labels often arrive as bare {@link RID}s and must
   * become TinkerPop vertices; {@code elementMap}'s {@code id} column must stay a RID (native uses
   * {@code T.id}).
   */
  private Object convertMapColumn(String columnName, Object raw) {
    if (raw instanceof RID rid) {
      if (shaping.elementMapTokens() && "id".equals(columnName)) {
        return rid;
      }
      return new YTDBVertexImpl(armingGraph, rid);
    }
    return convertValue(raw);
  }

  private Object mapKeyForColumn(String name) {
    if (shaping.elementMapTokens()) {
      // Aliases wired by GremlinProjectionAssembler.ELEMENT_MAP_KEY_ID / _LABEL.
      if ("id".equals(name)) {
        return org.apache.tinkerpop.gremlin.structure.T.id;
      }
      if ("label".equals(name)) {
        return org.apache.tinkerpop.gremlin.structure.T.label;
      }
    }
    return name;
  }

  /**
   * Emits a single property value. With {@code dropOnAbsent}, rows whose property is absent on the
   * entity are skipped; present-with-null still emits a {@code null} traverser.
   */
  private Object projectSingleValue(Result row) {
    if (shaping.dropOnAbsent() && !shaping.presencePropertyKeys().isEmpty()) {
      var key = shaping.presencePropertyKeys().getFirst();
      var entity = resolveEntity(row);
      if (entity == null || !entity.hasProperty(key)) {
        return SKIP;
      }
      var value = convertValue(entity.getProperty(key));
      if (shaping.dropNullRows() && value == null) {
        return SKIP;
      }
      return value;
    }
    var value = primaryProjectedValue(row);
    if (shaping.dropNullRows() && value == null) {
      return SKIP;
    }
    return value;
  }

  /**
   * Emits a scalar aggregate cell. {@code dropNullRows} drops the empty-input null cell for {@code
   * sum}/{@code min}/{@code max}/{@code mean}; {@code count} keeps its {@code 0}.
   */
  private Object projectScalar(Result row) {
    var value = primaryProjectedValue(row);
    if (shaping.dropNullRows() && value == null) {
      return SKIP;
    }
    return convertValue(value);
  }

  /**
   * First non-boundary RETURN column value — used for {@code SINGLE_VALUE} / {@code SCALAR} when the
   * assembler did not pin presence keys (or as a fallback).
   */
  private Object primaryProjectedValue(Result row) {
    for (String name : row.getPropertyNames()) {
      if (!name.equals(boundaryAlias)) {
        return convertValue(row.getProperty(name));
      }
    }
    return null;
  }

  private EntityImpl resolveEntity(Result row) {
    var entity = row.getEntity(boundaryAlias);
    if (entity instanceof EntityImpl entityImpl) {
      return entityImpl;
    }
    return null;
  }

  /**
   * Converts MATCH cell values into TinkerPop-facing payloads: entities become {@link
   * YTDBVertexImpl}, nested lists are mapped recursively, RIDs stay as id objects.
   */
  private Object convertValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Vertex) {
      return value;
    }
    if (value instanceof com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex ytdbVertex) {
      return new YTDBVertexImpl(armingGraph, ytdbVertex);
    }
    if (value instanceof Entity entity) {
      if (entity.isVertex()) {
        return new YTDBVertexImpl(armingGraph, entity.asVertex());
      }
      return entity;
    }
    if (value instanceof List<?> list) {
      var out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(convertValue(item));
      }
      return out;
    }
    return value;
  }

  /**
   * Group VALUE conversion for bare {@code group()} / {@code group().by(k)}: {@code list(alias)}
   * collects the grouped elements as a list of RIDs, so wrap each as a {@link YTDBVertexImpl} to
   * match native (whose group values are a list of Vertex). Count values ({@code groupCount} /
   * {@code by(__.count())}) are numbers and pass through {@link #convertValue} unchanged. Kept
   * separate from {@link #convertValue} so a link-typed {@code values(k)} / {@code valueMap(k)}
   * property (a legitimate RID) is not silently reinterpreted as a vertex.
   */
  private Object convertGroupValue(Object value) {
    if (value instanceof RID rid) {
      return new YTDBVertexImpl(armingGraph, rid);
    }
    if (value instanceof List<?> list) {
      var out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(convertGroupValue(item));
      }
      return out;
    }
    return convertValue(value);
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

  // ---- Plan-seam hooks ----
  //
  // The single-vs-N-plan orchestration lives in the concrete subclass: it decides which plan
  // supplies the stream, how the plan is rewound between passes, and how the plan(s) are closed.
  // The lifecycle primitives above drive one live stream at a time through these four hooks.

  /**
   * The command context the current arming's stream iterates against and is closed against. For the
   * single-plan form this is the plan's context; a multi-plan form returns the context of the plan
   * whose stream is currently live.
   */
  protected abstract CommandContext planContext();

  /**
   * Rewinds the plan's step chain before a re-run. Called by {@link #openArming()} only when the
   * step is REARMED — a plan that already ran in a prior pass must be reset before it can execute
   * again; a first open has nothing to rewind.
   */
  protected abstract void rewindPlan(CommandContext ctx);

  /**
   * Replaces the plan this step holds with a fresh {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan#copy copy} of it, for
   * a re-arm after the previous pass closed the plan. Called by {@link #openArming()} only when the
   * step is REARMED_AFTER_CLOSE, and before {@link #planContext()} is read for that arming.
   *
   * <p><b>Why a copy rather than a rewind.</b> {@code AbstractExecutionStep} guards {@code close()}
   * with a private sticky flag and {@code ExecutionStepInternal.reset()} defaults to a no-op that
   * does not clear it, so {@code SelectExecutionPlan.reset(ctx)} cannot revive a closed chain. A
   * rewind-and-restart of a closed plan would run a dead step chain: the visible symptom is an empty
   * result, and the invisible one is leaked cursors. Copying rebuilds the chain from unstarted steps
   * instead.
   *
   * <p><b>Context derivation, and why it is not {@code clone()}'s.</b> Implementations copy against
   * the closed plan's OWN context rather than minting a fresh child context parented to it, which is
   * what {@code clone()} does. The two situations differ in what they must protect against.
   * {@code clone()} isolates two step instances that execute <em>concurrently</em> off one set of
   * unsynchronised per-run maps, and it can afford a child context because it runs against a
   * build-time context no pass has touched. A re-arm has one live plan at a time on one instance,
   * so there is nothing to isolate from — and by then a completed pass has seeded exactly the
   * per-run context state that {@code MultiPlanMatchStep.clone()}'s isolation assert rejects, so
   * reusing that recipe here would fire the assert under {@code -ea}. Reusing the plan's own context
   * also keeps the two re-arm paths differing in one thing only, the plan object: the REARMED path
   * rewinds in place against this same context.
   */
  protected abstract void replaceClosedPlanWithCopy();

  /**
   * Starts the plan and returns its {@link ExecutionStream}. Called by {@link #openArming()} after
   * the session rebind and any rewind; the base wraps the call so a partial start that already
   * claimed cursors is released via {@link #closePlan()} before the failure propagates.
   */
  protected abstract ExecutionStream startPlanStream();

  /**
   * Closes the underlying plan (or, for a multi-plan form, every plan it owns). Called on the
   * terminal paths — an iteration failure, a partial-start failure, and {@link #close()}.
   */
  protected abstract void closePlan();
}
