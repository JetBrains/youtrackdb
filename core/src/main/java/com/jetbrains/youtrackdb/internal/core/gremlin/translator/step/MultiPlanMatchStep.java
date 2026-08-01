package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Multi-plan boundary step: the concrete {@link AbstractMatchPlanStep} that concatenates the result
 * streams of N compiled MATCH plans, one per {@code union(...)} child. The Gremlin-to-MATCH strategy
 * builds this step when a recognised traversal translates to a {@code union} whose children each
 * become their own {@link SelectExecutionPlan}; the shared open / drain / close lifecycle and the
 * row projection live in the base, and this class supplies the N plans through the plan-seam hooks.
 *
 * <h2>Concatenation, not cartesian product</h2>
 * The children are concatenated: {@code union(c1, …, cN)} emits every row of {@code c1}, then every
 * row of {@code c2}, and so on, so the result multiset is {@code |c1| + … + |cN|}. This is the
 * opposite of MATCH's {@code splitDisjointPatterns}, which joins disconnected patterns by cartesian
 * product; union must therefore build one full plan per child and concatenate rather than ride a
 * single multi-pattern MATCH.
 *
 * <h2>One live stream at a time</h2>
 * {@link #startPlanStream()} realizes the base's single-stream hook as one {@link
 * MultipleExecutionStream} over an {@link ExecutionStreamProducer} that opens each child plan
 * <em>lazily</em>: child {@code i+1} is opened only after child {@code i} has drained and been
 * closed. Two consequences fall out of that laziness for free:
 * <ul>
 *   <li><b>Exception stops the advance.</b> A failure while iterating child {@code i} propagates up
 *       through the concatenator into the base's {@link #processNextStart()} terminal handler before
 *       child {@code i+1} is ever opened — so the children after the failing one never start.
 *   <li><b>Bounded footprint.</b> Only one child plan holds an open stream at any instant; the base
 *       projects the concatenation as if it were one stream, so row projection and the ordered
 *       list-shaping post-process apply once over the whole union (this is what lets a later {@code
 *       union().fold()} fold the whole union into one list rather than one list per child).
 * </ul>
 *
 * <h2>Post-concatenation reductions</h2>
 * A recognised {@code count()}, {@code limit()} / {@code range()} / {@code skip()}, or {@code
 * dedup()} following the union arrives as an ordered {@link PostConcatOp} list, and {@link
 * #startPlanStream()} may therefore return something other than the plain concatenator. There are
 * two count paths. A <em>lone</em> {@code Count} is pushed down: the strategy rewrote every child to
 * {@code RETURN count(*)} at build time, so {@link #sumChildCountStreams()} opens each child, reads
 * its one scalar row, and emits a single summed {@code count} column without ever materialising the
 * element concatenation. Any other op list keeps the children on elements and wraps the
 * concatenator with the {@link PostConcatStreams} decorators, applying the ops in recognised order —
 * a skip counter, a limit that early-stops the concatenator so later children never open, a global
 * identity set for {@code dedup}, and a terminal drain-and-count for a {@code Count} that follows
 * another reduction. Order matters, so the wrapping follows the list rather than a fixed
 * precedence: {@code union(…).limit(5).count()}
 * counts at most five rows, which is what the recogniser accepted.
 *
 * <h2>Per-child isolated, session-rebound context</h2>
 * Each child plan carries its own {@link CommandContext} (with its own positional parameters,
 * installed at build time — the base's shared parameter map is deliberately empty here). The base
 * rebinds the coordinator context ({@link #planContext()}) to the iteration-thread session in {@code
 * openArming()}; the producer reads that session back and rebinds each child's own context before
 * opening it, then iterates and closes the child stream against that child's own context via {@link
 * ChildContextStream}. Each child therefore executes exactly as it would under the single-plan
 * {@link YTDBMatchPlanStep} — start-time context and iteration-time context are the same, so a step
 * that resolves the session or reads {@code $current} / {@code $matched} at iteration time (e.g.
 * {@code LoaderExecutionStream}) never sees its state split across two contexts. No edit to {@link
 * AbstractMatchPlanStep} is needed: pre-binding each child stream this way is sufficient.
 *
 * <h2>Close-all, including un-run children</h2>
 * {@link #closePlan()} closes <em>every</em> child plan, not only those the producer opened. When an
 * exception stops the advance at child {@code i}, children {@code i+1 …} were never started but their
 * step chains still exist and must be released; closing an un-run {@link SelectExecutionPlan} is safe
 * (its close simply propagates backward through an unstarted chain). A single child's close failure
 * never masks the others — the first failure stays primary and the rest attach with {@code
 * addSuppressed}.
 *
 * <h2>Clone</h2>
 * {@link #clone()} gives the clone its own deep copy of <em>each</em> child plan against its own
 * isolated child context, mirroring {@link YTDBMatchPlanStep#clone()} per child. Union's multi-alias
 * children make cross-execution context bleed a real hazard (each child owns unsynchronised {@code
 * $current} / {@code $matched} / statistics maps), so every child is isolated independently. The
 * clone also gets a fresh coordinator context so two concurrent clones never race on the coordinator.
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted payload type ({@link Vertex} for {@link BoundaryOutputType#ELEMENT}; Map /
 *            scalar / value for the other output types — the Element bound is historical for the
 *            ELEMENT path and is unchecked-cast for non-element payloads)
 */
public final class MultiPlanMatchStep<S, E extends Element> extends AbstractMatchPlanStep<S, E> {

  // The ordered child plans, concatenated in declared union order. Non-final for the same JMM reason
  // as YTDBMatchPlanStep.plan: clone() installs the clone's own child-plan copies with a plain field
  // write before the clone is published, avoiding a post-super.clone() reflective write to a final
  // field (which would void the final-field publication guarantee).
  private List<InternalExecutionPlan> plans;

  private CommandContext coordinatorContext;

  /** Ordered post-concat reductions; empty for a plain union with no suffix barriers. */
  private final List<PostConcatOp> postConcatOps;

  /**
   * Constructs a multi-plan boundary step over the given ordered child plans with no result shaping.
   */
  public MultiPlanMatchStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull List<InternalExecutionPlan> plans,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType) {
    this(traversal, returnClass, plans, boundaryAlias, outputType, ResultShaping.NONE, List.of());
  }

  /**
   * Canonical constructor: ordered child plans, row-projection shaping, and the ordered
   * post-concatenation reductions ({@code count}/{@code limit}/{@code dedup}) the recogniser
   * accepted after the union.
   */
  public MultiPlanMatchStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull List<InternalExecutionPlan> plans,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull ResultShaping shaping,
      @Nonnull List<PostConcatOp> postConcatOps) {
    super(traversal, returnClass, boundaryAlias, outputType, Map.of(), shaping);
    this.plans = List.copyOf(plans);
    if (this.plans.isEmpty()) {
      throw new IllegalArgumentException(
          "MultiPlanMatchStep requires at least one child plan; a union with no children should have"
              + " been declined at recognition time.");
    }
    this.coordinatorContext = new BasicCommandContext();
    this.postConcatOps = List.copyOf(postConcatOps);
  }

  /**
   * The ordered child execution plans this step concatenates.
   *
   * <p>Not a fixed list across the step's whole life: a re-arm after {@link #close()} swaps in
   * copies of every child (see {@link #replaceClosedPlanWithCopy()}). Read within a pass — anywhere
   * from its first row to the {@code close()} that ends it, which is where {@code
   * YTDBQueryMetricsStep} reads it — this returns the child plans that produced that pass's rows.
   * See the "Which plan object an observer sees" section of {@link AbstractMatchPlanStep}.
   */
  public List<InternalExecutionPlan> getPlans() {
    return plans;
  }

  /** Ordered post-concatenation reductions applied after the child streams combine. */
  public List<PostConcatOp> getPostConcatOps() {
    return postConcatOps;
  }

  @Override
  public MultiPlanMatchStep<S, E> clone() {
    var cloned = (MultiPlanMatchStep<S, E>) super.clone();
    // Give the clone its own deep copy of EVERY child plan, each against its OWN isolated child
    // context — a fresh BasicCommandContext parented to that child's original context — mirroring
    // YTDBMatchPlanStep.clone() per child. Union's multi-alias children make cross-execution context
    // bleed a real hazard: each child's SelectExecutionPlan carries mutable per-run state ($current /
    // $matched / statistics, all plain HashMaps), so the original's and the clone's executions must
    // not share it. Copying a child against its shared original context would leave both plans on the
    // same context, defeating the isolation this clone exists to provide.
    //
    // INVARIANT the isolation depends on (see YTDBMatchPlanStep.clone()): each child's parent
    // (template) context must stay free of per-run variables, because a child write propagates UP to
    // the parent only for a key the parent already holds. A union child pattern that seeded alias /
    // LET bindings onto its plan's context at BUILD time would break it; the recognised union shapes
    // seed none.
    var copies = new ArrayList<InternalExecutionPlan>(plans.size());
    for (var childPlan : plans) {
      var templateContext = childPlan.getContext();
      // Fail fast if the INVARIANT above is ever violated. The isolation only holds while the shared
      // template (parent) context carries no per-run state: a child write propagates UP to a key the
      // parent already holds (BasicCommandContext.setVariable / setSystemVariable), so a seeded
      // parent would be written concurrently through its unsynchronised maps by two clones. This
      // assert turns that silent, load-dependent corruption into an immediate failure the moment a
      // future recogniser change starts seeding an alias / LET / $current / $current_match /
      // $matched / $depth binding onto a child's context at build time, rather than a rare fault
      // that appears only under production concurrency. It covers EVERY system-variable slot rather
      // than the two the element path happens to use: the MATCH edge-traversal path writes
      // $current_match per candidate and restores it with a null value afterwards, and fastutil's
      // key-presence tracking is value-independent, so a child that matched zero rows seeds that
      // slot without ever touching $matched. Zero cost in production (assertions disabled).
      // getVariables() is null only for a test mock context, which carries no per-run state and is
      // treated here as empty.
      var templateVariables = templateContext.getVariables();
      assert (templateVariables == null || templateVariables.isEmpty())
          && seededSystemVariable(templateContext) < 0
          : "union child template context carries per-run state (system variable slot "
              + seededSystemVariable(templateContext)
              + ", or a normal variable); clone isolation cannot keep concurrent clones from racing"
              + " on the shared parent context — the recogniser must seed no per-run binding onto a"
              + " child plan context at build time";
      var isolatedCtx = new BasicCommandContext();
      isolatedCtx.setParentWithoutOverridingChild(templateContext);
      copies.add(childPlan.copy(isolatedCtx));
    }
    // Plain field writes: both fields are non-final (see their declarations), the copies are
    // independent, and the writes happen before the clone is published to any other thread.
    cloned.plans = List.copyOf(copies);
    // Fresh coordinator so two clones never share one; a shared coordinator would let concurrent
    // armings race on its unsynchronised session field.
    cloned.coordinatorContext = new BasicCommandContext();
    // Drop the per-arming references super.clone() copied by value and put the clone in its NEW
    // starting state — without this a clone taken from an already-closed step would be born CLOSED
    // and never close its own fresh plan copies.
    cloned.resetLifecycleForClone();
    return cloned;
  }

  /**
   * Every system-variable slot {@link CommandContext} declares, so the clone-isolation assert
   * rejects a seeded template context whatever wrote it rather than only the slots the element path
   * happens to use. A fifth slot added to {@code CommandContext} must be added here too.
   */
  private static final int[] SYSTEM_VARIABLE_SLOTS = {
      CommandContext.VAR_CURRENT,
      CommandContext.VAR_CURRENT_MATCH,
      CommandContext.VAR_MATCHED,
      CommandContext.VAR_DEPTH
  };

  /**
   * Returns the first system-variable slot {@code context} holds, or {@code -1} when it holds none.
   * Used only by the clone-isolation assert, which reports the offending slot id in its message.
   */
  private static int seededSystemVariable(CommandContext context) {
    for (int slot : SYSTEM_VARIABLE_SLOTS) {
      if (context.hasSystemVariable(slot)) {
        return slot;
      }
    }
    return -1;
  }

  // ---- Plan-seam hooks: N child plans, one live stream at a time. ----

  @Override
  protected CommandContext planContext() {
    return coordinatorContext;
  }

  @Override
  protected void rewindPlan(CommandContext ctx) {
    // Rewind EVERY child's step chain so a re-armed union re-runs all children from the first. The
    // base calls this only when REARMED (the step already ran at least once). Reset each child
    // against its own context; SelectExecutionPlan.reset ignores the argument, but passing the
    // child's own context keeps the seam faithful to the single-plan path.
    for (var childPlan : plans) {
      childPlan.reset(childPlan.getContext());
    }
  }

  @Override
  protected void replaceClosedPlanWithCopy() {
    // Copy EVERY child, so a re-armed union re-runs all of them from the first — the same breadth
    // as rewindPlan above, which resets every child rather than only those the producer opened.
    // Each child is copied against its OWN context, NOT against a fresh child context parented to
    // it the way clone() does; the base's hook Javadoc gives the full reasoning, and here the
    // difference is sharpest, because reusing clone()'s recipe would trip clone()'s own isolation
    // assert on any child that ran (that assert is what documents the seeded per-run state a
    // completed pass leaves behind).
    var copies = new ArrayList<InternalExecutionPlan>(plans.size());
    for (var childPlan : plans) {
      var copy = childPlan.copy(childPlan.getContext());
      // A copy() that handed back the same instance would put this child right back on its closed
      // chain: the re-run would silently drop that child's rows from the union and leak the cursors
      // it claimed. Zero cost in production (assertions disabled).
      assert copy != null && copy != childPlan
          : "InternalExecutionPlan.copy returned " + (copy == null ? "null" : "the same instance")
              + "; the re-armed union would restart a closed child plan";
      copies.add(copy);
    }
    // Plain field write, as in clone(): the field is non-final (see its declaration) and the write
    // happens on the iterating thread before any row of the new pass is produced.
    plans = List.copyOf(copies);
  }

  @Override
  protected ExecutionStream startPlanStream() {
    // Lone count(): children were rewritten to RETURN count(*) at build time — sum their scalar
    // rows. Keeps per-arm SQL count optimisations and avoids draining the element concatenation.
    if (PostConcatOp.isPushDownCountOnly(postConcatOps)) {
      return sumChildCountStreams();
    }
    final var childPlans = plans;
    var producer =
        new ExecutionStreamProducer() {
          private final Iterator<InternalExecutionPlan> iter = childPlans.iterator();

          @Override
          public boolean hasNext(CommandContext ctx) {
            return iter.hasNext();
          }

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var childPlan = iter.next();
            var childContext = childPlan.getContext();
            childContext.setDatabaseSession(ctx.getDatabaseSession());
            return new ChildContextStream(childPlan.start(), childContext);
          }

          @Override
          public void close(CommandContext ctx) {
            // No-op: plans are released by closePlan().
          }
        };
    ExecutionStream stream = new MultipleExecutionStream(producer);
    for (PostConcatOp op : postConcatOps) {
      stream = applyPostConcatOp(stream, op);
    }
    return stream;
  }

  /**
   * Opens each child count plan, reads its single scalar row, and emits one summed {@code count}
   * result for {@link BoundaryOutputType#SCALAR} projection.
   */
  private ExecutionStream sumChildCountStreams() {
    final var childPlans = plans;
    final var boundary = getBoundaryAlias();
    return new ExecutionStream() {
      private Result pending;
      private boolean computed;

      @Override
      public boolean hasNext(CommandContext ctx) {
        ensure(ctx);
        return pending != null;
      }

      @Override
      public Result next(CommandContext ctx) {
        ensure(ctx);
        if (pending == null) {
          throw new IllegalStateException("no summed count row");
        }
        var out = pending;
        pending = null;
        return out;
      }

      @Override
      public void close(CommandContext ctx) {
        pending = null;
      }

      private void ensure(CommandContext ctx) {
        if (computed) {
          return;
        }
        computed = true;
        long total = 0L;
        for (var childPlan : childPlans) {
          var childContext = childPlan.getContext();
          childContext.setDatabaseSession(ctx.getDatabaseSession());
          var childStream = new ChildContextStream(childPlan.start(), childContext);
          try {
            if (!childStream.hasNext(childContext)) {
              continue;
            }
            var row = childStream.next(childContext);
            total += scalarCount(row, boundary);
            // Drain any unexpected extra rows so the child closes cleanly.
            while (childStream.hasNext(childContext)) {
              childStream.next(childContext);
            }
          } finally {
            childStream.close(childContext);
          }
        }
        pending = PostConcatStreams.singleCountRow(ctx, total);
      }
    };
  }

  /**
   * Reads the scalar total out of one child's pushed-down {@code RETURN count(*)} row. The row shape
   * is pinned by the build-time rewrite — exactly one non-boundary column holding a number — but the
   * rewrite lives in another package, so a mismatch is reported rather than absorbed: returning
   * {@code 0} for an unreadable cell would under-report the union total with nothing downstream able
   * to tell the difference.
   */
  private static long scalarCount(Result row, String boundaryAlias) {
    for (String name : row.getPropertyNames()) {
      if (!name.equals(boundaryAlias)) {
        var value = row.getProperty(name);
        if (value instanceof Number number) {
          return number.longValue();
        }
        throw new IllegalStateException(
            "union child count row column '"
                + name
                + "' holds "
                + (value == null ? "null" : value.getClass().getName())
                + " instead of a number; the child plan was not rewritten to RETURN count(*)");
      }
    }
    throw new IllegalStateException(
        "union child count row carries no column other than the boundary alias '"
            + boundaryAlias
            + "'; the child plan was not rewritten to RETURN count(*)");
  }

  private ExecutionStream applyPostConcatOp(ExecutionStream stream, PostConcatOp op) {
    return switch (op) {
      case PostConcatOp.Count ignored -> PostConcatStreams.count(stream);
      case PostConcatOp.Range range -> {
        ExecutionStream s = stream;
        if (range.skip() > 0) {
          s = PostConcatStreams.skip(s, range.skip());
        }
        if (range.limit() >= 0) {
          s = s.limit(range.limit());
        }
        yield s;
      }
      case PostConcatOp.Dedup ignored -> PostConcatStreams.dedup(stream, getBoundaryAlias());
    };
  }

  @Override
  protected void closePlan() {
    // Close EVERY child plan, including children the producer never opened: an exception stops the
    // advance at child i, so children i+1 … were never started, but their step chains still exist
    // and must be released. Closing an un-run SelectExecutionPlan is safe — close propagates backward
    // through the (unstarted) chain and is idempotent. Keep closing the rest even if one throws: the
    // first failure stays primary and the rest attach with addSuppressed, so a single child's close
    // failure never leaks the other children's resources.
    Throwable first = null;
    for (var childPlan : plans) {
      try {
        childPlan.close();
      } catch (RuntimeException | Error e) {
        if (first == null) {
          first = e;
        } else {
          first.addSuppressed(e);
        }
      }
    }
    if (first instanceof Error error) {
      throw error;
    }
    if (first instanceof RuntimeException runtime) {
      throw runtime;
    }
    // first is either null or a checked Throwable, which plan.close() cannot throw (its signature is
    // unchecked-only); the two rethrows above cover every reachable case.
    assert first == null : "plan.close() threw a checked exception, which its signature forbids";
  }

  /**
   * Wraps a child plan's {@link ExecutionStream} so it iterates and closes against the child's OWN
   * {@link CommandContext} rather than the coordinator context {@link MultipleExecutionStream}
   * threads through it. This keeps every child byte-identical to the single-plan {@link
   * YTDBMatchPlanStep} path, where the iteration context is always the plan's own context — a step
   * that resolves the session or reads {@code $current} / {@code $matched} at iteration time (e.g.
   * {@code LoaderExecutionStream}) must see the same context it was started with.
   */
  private static final class ChildContextStream implements ExecutionStream {

    private final ExecutionStream delegate;
    private final CommandContext childContext;

    ChildContextStream(@Nonnull ExecutionStream delegate, @Nonnull CommandContext childContext) {
      this.delegate = Objects.requireNonNull(delegate, "child stream");
      this.childContext = Objects.requireNonNull(childContext, "child context");
    }

    @Override
    public boolean hasNext(CommandContext ignored) {
      return delegate.hasNext(childContext);
    }

    @Override
    public Result next(CommandContext ignored) {
      return delegate.next(childContext);
    }

    @Override
    public void close(CommandContext ignored) {
      delegate.close(childContext);
    }
  }
}
