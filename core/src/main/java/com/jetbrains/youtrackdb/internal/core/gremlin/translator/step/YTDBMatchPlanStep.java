package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Single-plan boundary step: the concrete {@link AbstractMatchPlanStep} that iterates exactly one
 * compiled MATCH plan. The Gremlin-to-MATCH strategy builds this step when a recognised traversal
 * translates to a single {@link InternalExecutionPlan}; the shared open / drain / close lifecycle
 * and the row projection live in the base, and this class supplies the one plan through the
 * plan-seam hooks.
 *
 * <p>Clone: an eager step ({@code copyOnOpen=false}) still copies in {@link #clone()}. A lazy
 * step shares the cache template and copies on first {@link #openArming()} instead, so
 * {@code applyStrategies()} is a hash lookup plus a splice. A {@link SelectExecutionPlan}-family
 * plan carries mutable per-run state (its step chain and the context's {@code $current} /
 * {@code $matched} / statistics maps, all plain {@code HashMap}s), so two executions must not
 * share it. The shared template is never started or closed by this step.
 *
 * @param <S> upstream traverser type (always {@code Object} for a start step)
 * @param <E> emitted payload type ({@link Vertex} for {@link BoundaryOutputType#ELEMENT}; Map /
 *            scalar / value for the other output types — the Element bound is historical for the
 *            ELEMENT path and is unchecked-cast for non-element payloads)
 */
public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractMatchPlanStep<S, E> {

  // Shared closed template. Never started or closed by this step when copyOnOpen is true; the
  // cache owns it. Eager steps set this to the same instance as plan.
  private final InternalExecutionPlan template;

  // When true, plan stays null until the first openArming() copies template. The strategy sets
  // this for cache-backed single-plan translations so apply() does not copy.
  private final boolean copyOnOpen;

  // Non-final so clone() / first-open copy install the live plan with a plain field write. A final
  // field would force a reflective write after super.clone() froze it, voiding the JMM final-field
  // publication guarantee for any thread that later receives the clone without a happens-before
  // edge. A plain write inside clone(), before the clone is published, has no such hazard.
  private InternalExecutionPlan plan;

  /**
   * Constructs a single-plan boundary step backed by the given execution plan.
   *
   * @param traversal     the host traversal (must not be null)
   * @param returnClass   the TinkerPop element class the step emits (currently {@link
   *                      Vertex}{@code .class})
   * @param plan          the compiled MATCH plan (must not be null)
   * @param boundaryAlias the alias under which the matched element appears in each result row (must
   *                      not be null)
   * @param outputType    how each row projects onto a traverser payload (must not be null)
   */
  public YTDBMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull InternalExecutionPlan plan,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType) {
    this(
        traversal,
        returnClass,
        plan,
        boundaryAlias,
        outputType,
        Map.of(),
        ResultShaping.NONE);
  }

  /**
   * Full constructor including the boundary row-projection shaping for result-shaping terminators.
   *
   * @param shaping the row-projection shaping ({@link ResultShaping}): row dropping ({@code
   *     dropNullRows} / {@code dropOnAbsent}), presence-checked keys, valueMap list wrapping,
   *     group-map accumulation, singleton-map unwrapping, and elementMap token keys
   */
  public YTDBMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull InternalExecutionPlan plan,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Map<Object, Object> inputParameters,
      @Nonnull ResultShaping shaping) {
    this(traversal, returnClass, plan, boundaryAlias, outputType, inputParameters, shaping, false);
  }

  /**
   * @param copyOnOpen when {@code true}, {@code plan} is a shared cache template: {@link #getPlan()}
   *     returns it until the first open, which installs an isolated copy. {@code close()} before
   *     the first open must not close the template. Eager tests and RID-bearing / uncacheable
   *     plans pass {@code false}.
   */
  public YTDBMatchPlanStep(
      @Nonnull Traversal.Admin<S, E> traversal,
      @Nonnull Class<E> returnClass,
      @Nonnull InternalExecutionPlan plan,
      @Nonnull String boundaryAlias,
      @Nonnull BoundaryOutputType outputType,
      @Nonnull Map<Object, Object> inputParameters,
      @Nonnull ResultShaping shaping,
      boolean copyOnOpen) {
    super(traversal, returnClass, boundaryAlias, outputType, inputParameters, shaping);
    this.template = plan;
    this.copyOnOpen = copyOnOpen;
    this.plan = copyOnOpen ? null : plan;
  }

  /**
   * The compiled execution plan the step iterates over.
   *
   * <p>Not a fixed object across the step's whole life: a re-arm after {@link #close()} swaps in a
   * copy (see {@link #replaceClosedPlanWithCopy()}). Read within a pass — anywhere from its first
   * row to the {@code close()} that ends it, which is where {@code YTDBQueryMetricsStep} reads it —
   * this returns the plan that produced that pass's rows. See the "Which plan object an observer
   * sees" section of {@link AbstractMatchPlanStep}.
   */
  public InternalExecutionPlan getPlan() {
    return plan != null ? plan : template;
  }

  @Override
  protected void preparePlanForArming() {
    if (copyOnOpen && plan == null) {
      plan = copyTemplate();
    }
  }

  @Override
  public YTDBMatchPlanStep<S, E> clone() {
    var cloned = (YTDBMatchPlanStep<S, E>) super.clone();
    if (copyOnOpen) {
      // Share the cache template; each clone copies on its own first open. Copying here would put
      // the copy back on the applyStrategies path this flag exists to keep off it.
      cloned.plan = null;
      cloned.resetLifecycleForClone();
      return cloned;
    }
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
    // child map and concurrent clones never touch the shared parent. The single-node g.V() pattern
    // seeds no such variables, so the invariant holds. A pattern that seeds alias or LET variables
    // onto the plan's context at BUILD time would break it — the shared parent would then
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
    cloned.resetLifecycleForClone();
    return cloned;
  }

  private InternalExecutionPlan copyTemplate() {
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(template.getContext());
    var copy = template.copy(isolatedCtx);
    assert copy != null && copy != template
        : "InternalExecutionPlan.copy returned " + (copy == null ? "null" : "the same instance")
            + "; the copy-on-open boundary step would start the shared cache template";
    return copy;
  }

  // ---- Plan-seam hooks: the single plan supplies the stream, context, rewind, and close. ----

  @Override
  protected CommandContext planContext() {
    return plan.getContext();
  }

  @Override
  protected void rewindPlan(CommandContext ctx) {
    plan.reset(ctx);
  }

  @Override
  protected void replaceClosedPlanWithCopy() {
    var closedPlan = plan;
    // Copy against the closed plan's OWN context, NOT against a fresh child context parented to it
    // the way clone() does — the base's hook Javadoc gives the full reasoning. Short version: a
    // re-arm has a single live plan, so it needs no isolation, and the completed pass has already
    // seeded this context with the per-run state clone()'s isolation assert forbids.
    plan = closedPlan.copy(closedPlan.getContext());
    // A copy() that handed back the same instance would put us right back on the closed chain: the
    // re-run would silently produce nothing and leak the cursors it claimed. Costs nothing in
    // production (assertions disabled) and turns a silent empty result into an immediate failure.
    assert plan != null && plan != closedPlan
        : "InternalExecutionPlan.copy returned " + (plan == null ? "null" : "the same instance")
            + "; the re-armed boundary step would restart a closed plan";
  }

  @Override
  protected ExecutionStream startPlanStream() {
    return plan.start();
  }

  @Override
  protected void closePlan() {
    if (plan == null) {
      return;
    }
    plan.close();
  }
}
