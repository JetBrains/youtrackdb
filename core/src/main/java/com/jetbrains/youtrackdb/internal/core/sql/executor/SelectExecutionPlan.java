package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The physical execution plan for a SELECT query -- a doubly-linked list of
 * {@link ExecutionStepInternal} nodes assembled by {@link SelectExecutionPlanner}.
 *
 * <h2>Step chain structure</h2>
 * <pre>
 *  steps list:   [step0, step1, step2, ..., stepN]
 *
 *  Linked list:
 *    step0  &lt;--prev-- step1  &lt;--prev-- step2  ...  &lt;--prev-- stepN
 *           --next--&gt;        --next--&gt;               --next--&gt;
 *
 *  lastStep = stepN  (the terminal step whose start() is called by the user)
 * </pre>
 *
 * <h2>Pull-based execution</h2>
 * Calling {@link #start()} invokes {@code lastStep.start(ctx)}, which recursively
 * pulls from its predecessor via {@code prev.start(ctx)}, forming a lazy
 * evaluation pipeline. Each step transforms or filters the
 * {@link ExecutionStream} it receives from its predecessor.
 *
 * <h2>Caching</h2>
 * Plans can be cached in the {@link com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache}
 * for reuse across identical queries. Cached plans are deep-copied via {@link #copy(CommandContext)}
 * before each execution to ensure thread safety. A plan is cacheable only if every
 * step in the chain reports {@link ExecutionStepInternal#canBeCached()} as {@code true}.
 *
 * @see SelectExecutionPlanner
 * @see ExecutionStepInternal
 */
public class SelectExecutionPlan implements InternalExecutionPlan {
  /** Opaque location identifier (e.g. database name) associated with this plan. */
  private String location;

  /** The command context for this execution (carries database session, variables, profiling). */
  protected CommandContext ctx;

  /** Ordered list of all steps in the chain (index 0 = source, last = terminal). */
  protected List<ExecutionStepInternal> steps = new ArrayList<>();

  /** Reference to the last step in the chain (the one whose start() kicks off execution). */
  private ExecutionStepInternal lastStep = null;

  /** The original SQL statement text (for logging / plan cache keys). */
  private String statement;

  /** A parameterized/generic form of the statement (for cache grouping). */
  private String genericStatement;

  public SelectExecutionPlan(CommandContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  /** Closes the plan by propagating the close signal from the terminal step backward. */
  @Override
  public void close() {
    lastStep.close();
  }

  /**
   * Begins pull-based execution by calling {@code start()} on the terminal (last) step,
   * which recursively pulls from its predecessor all the way back to the source step.
   */
  @Override
  public ExecutionStream start() {
    return lastStep.start(ctx);
  }

  /**
   * Returns a multi-line, human-readable representation of the full execution plan
   * by concatenating the pretty-print output of each step in order.
   */
  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    for (var i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      result.append(step.prettyPrint(depth, indent));
      if (i < steps.size() - 1) {
        result.append("\n");
      }
    }
    return result.toString();
  }

  @Override
  public void reset(CommandContext ctx) {
    steps.forEach(ExecutionStepInternal::reset);
  }

  /**
   * Appends a new step to the end of the chain, linking it to the current last step.
   *
   * <pre>
   *  Before: ... &lt;-&gt; lastStep
   *  After:  ... &lt;-&gt; lastStep &lt;-&gt; nextStep
   *                                 ^--- new lastStep
   * </pre>
   */
  public void chain(ExecutionStepInternal nextStep) {
    if (lastStep != null) {
      lastStep.setNext(nextStep);
      nextStep.setPrevious(lastStep);
    }
    lastStep = nextStep;
    steps.add(nextStep);
  }

  /**
   * Returns the internal step list as an untyped {@code List<ExecutionStep>} view.
   *
   * <p><b>Note:</b> this returns the live internal list (not a defensive copy).
   * Callers must not add or remove elements. The raw cast from
   * {@code List<ExecutionStepInternal>} is safe because {@link ExecutionStepInternal}
   * extends {@link ExecutionStep}.
   */
  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    return (List) steps;
  }

  public void setSteps(List<ExecutionStepInternal> steps) {
    this.steps = steps;
    if (!steps.isEmpty()) {
      lastStep = steps.getLast();
    } else {
      lastStep = null;
    }
  }

  /**
   * Converts this plan into a {@link Result} for introspection (e.g. EXPLAIN output),
   * including type, cost, pretty-print text, and the serialized step list.
   */
  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSessionEmbedded db) {
    var result = new ResultInternal(db);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty(JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty(
        "steps",
        steps == null ? null
            : steps.stream().map(x ->
                x.toResult(db)).collect(Collectors.toList()));
    return result;
  }

  /** Returns 0 (cost-based optimization is not yet implemented). */
  @Override
  public long getCost() {
    return 0L;
  }

  /**
   * Serializes this plan into a {@link Result} for persistent storage or transmission.
   * Unlike {@link #toResult}, which is for human-readable introspection (EXPLAIN),
   * this method produces a machine-readable form that can be deserialized back into
   * a live plan via {@link #deserialize}.
   */
  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty(JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty(
        "steps",
        steps == null ? null
            : steps.stream().map(x -> x.serialize(session)).collect(Collectors.toList()));
    return result;
  }

  @Override
  public void deserialize(Result serializedExecutionPlan, DatabaseSessionEmbedded session) {
    List<Result> serializedSteps = serializedExecutionPlan.getProperty("steps");
    for (var serializedStep : serializedSteps) {
      try {
        String className = serializedStep.getProperty(JAVA_TYPE);
        var step =
            (ExecutionStepInternal) Class.forName(className).newInstance();
        step.deserialize(serializedStep, session);
        chain(step);
      } catch (Exception e) {
        throw BaseException.wrapException(
            new CommandExecutionException(session,
                "Cannot deserialize execution step:" + serializedStep),
            e, session);
      }
    }
  }

  @Override
  public InternalExecutionPlan copy(CommandContext ctx) {
    var copy = new SelectExecutionPlan(ctx);
    copyOn(copy, ctx);
    return copy;
  }

  /**
   * Deep-copies all steps from this plan into {@code copy}, re-linking prev/next pointers.
   * Used by {@link #copy(CommandContext)} and subclass overrides (e.g. script plans).
   *
   * <pre>
   *  Original chain:
   *    step0 &lt;--prev-- step1 &lt;--prev-- step2
   *          --next--&gt;       --next--&gt;
   *
   *  After copyOn():
   *    copy0 &lt;--prev-- copy1 &lt;--prev-- copy2    (independent chain)
   *          --next--&gt;       --next--&gt;
   *
   *  The two chains share no mutable state.
   * </pre>
   */
  protected void copyOn(SelectExecutionPlan copy, CommandContext ctx) {
    ExecutionStepInternal lastStep = null;

    for (var step : this.steps) {
      var newStep =
          (ExecutionStepInternal) step.copy(ctx);
      newStep.setPrevious(lastStep);
      if (lastStep != null) {
        lastStep.setNext(newStep);
      }
      lastStep = newStep;
      copy.getSteps().add(newStep);
    }

    copy.lastStep = copy.steps.isEmpty() ? null : copy.steps.getLast();
    copy.location = this.location;
    copy.statement = this.statement;
  }

  @Override
  public boolean canBeCached() {
    for (var step : steps) {
      if (!step.canBeCached()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String getStatement() {
    return statement;
  }

  @Override
  public void setStatement(String statement) {
    this.statement = statement;
  }

  @Override
  public String getGenericStatement() {
    return this.genericStatement;
  }

  @Override
  public void setGenericStatement(String stm) {
    this.genericStatement = stm;
  }
}
