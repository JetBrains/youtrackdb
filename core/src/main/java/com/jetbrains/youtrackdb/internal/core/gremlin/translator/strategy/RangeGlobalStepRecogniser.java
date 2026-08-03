package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;

/**
 * Recogniser for {@code RangeGlobalStep} / {@code RangeGlobalStepPlaceholder}: {@code limit(n)},
 * {@code skip(n)}, and {@code range(low, high)} map to {@code SQLSkip}/{@code SQLLimit} on a
 * single-plan walk, or to a {@link PostConcatOp.Range} after a union (early-stop on the
 * concatenator).
 *
 * <h2>A single-plan slice ends the walk</h2>
 *
 * The clauses this recogniser sets belong to the assembled statement, and MATCH applies them after
 * everything else the walk contributes. Gremlin applies a slice where the user wrote it. Left
 * unguarded, {@code g.V().limit(2).out()} compiles to the statement {@code g.V().out().limit(2)}
 * compiles to and slices the hop's output instead of its input — the first two out-neighbours of
 * the whole graph against native's out-neighbours of the first two vertices. {@link
 * GremlinStepWalker}'s dispatch loop closes that: once a clause is set, the next step declines the
 * whole walk unless it is a pure projection, whose contribution lands on the far side of the clause
 * anyway. The gate sits there rather than here because every recogniser has to respect it,
 * including ones added later.
 *
 * <p>The decline is priced the same way the post-union one is, and for the same reason — the
 * all-or-nothing walk gives up the prefix too. {@code g.V().has("name", x).limit(2).values("name")}
 * runs end to end on the native traverser pipeline, so its non-leading filters and its hops run per
 * traverser instead of inside one plan. A <em>leading</em> {@code has()} keeps index-backed access
 * either way, since {@code YTDBGraphStepStrategy} folds it into {@code YTDBGraphStep}. What the
 * gate cannot give back is the pushed-down slice itself: a translated {@code LIMIT} stops the scan
 * inside the engine, and the native {@code RangeGlobalStep} only stops the traverser stream.
 *
 * <h2>Why a post-union slice needs a following {@code count()}</h2>
 *
 * A slice selects rows <em>by position</em>, and the multi-plan boundary's positions are not
 * native's. {@code MultiPlanMatchStep} emits child one's rows, then child two's; TinkerPop's
 * {@code union(...)} is a branch step that interleaves the arms as it consumes each incoming
 * traverser. On an eight-vertex {@code knows} chain both orders carry the same fourteen rows, but
 * {@code union(out(), in()).limit(3)} reads three rows out of the first arm on the translated side
 * against a mixture of both arms natively — a different multiset returned silently, under a kill
 * switch that defaults on.
 *
 * <p>Matching native's order is not available: each union child is its own compiled MATCH plan over
 * the whole start set, so there is no per-traverser interleaving point to reproduce, and MATCH
 * promises no arrival order of its own. The slice therefore only survives when what follows reduces
 * it to a cardinality — {@code min(n, total)} and {@code max(0, total - n)} are the same whichever
 * order the arms arrived in. {@link #recognizePostUnion} accepts a positional slice only when the
 * very next step is {@code count()}, and declines every other post-union slice to the native
 * pipeline. A slice that normalises away to nothing ({@code skip(0)}, {@code range(0, -1)}) selects
 * no position at all and is accepted unconditionally.
 *
 * <h2>What the decline costs</h2>
 *
 * A DECLINE aborts the whole walk rather than only the slice — {@link GremlinStepWalker} has no
 * partial-splice path — so {@code g.V().has("name", x).out("knows").union(out(), in()).limit(10)}
 * gives up its prefix's MATCH plan too and runs end to end on the native traverser pipeline.
 * Compile cost falls, because the N per-arm sub-walks are never paid and nothing caches them.
 * Execution pays for it twice: the prefix's non-leading filters and hops run per traverser instead
 * of inside one plan, and the concatenator's early stop is gone, so every arm executes where a
 * {@link PostConcatOp.Range} used to leave the later ones unopened. A <em>leading</em> {@code
 * has()} keeps index-backed access either way, since {@code YTDBGraphStepStrategy} folds it into
 * {@code YTDBGraphStep}, so the loss is confined to non-leading filters and wide unions.
 *
 * <p>Post-concat {@code order()} is the exit, not a wider accept surface: a slice after a total
 * sort picks the same rows whichever order the arms arrived in, so {@code
 * union(...).order().by(k).limit(n)} becomes translatable once post-concat sort exists, with a
 * unique key or an explicit tie-break to pin the ties. Trimming the decline to recover compile
 * coverage in the meantime would re-admit shapes whose answer depends on arrival order, which is
 * the defect this gate exists to close.
 */
final class RangeGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final RangeGlobalStepRecogniser INSTANCE = new RangeGlobalStepRecogniser();

  private RangeGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof RangeGlobalStepContract<?> range)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    if (ctx.hasUnionCarrier()) {
      return recognizePostUnion(cursor, ctx, range);
    }
    // A second range/limit/skip has no clear MATCH composition rule in Phase 1. The walker's
    // single-plan slice gate declines any step after a captured slice, so in production it reaches
    // a second slice before dispatch hands it here; this branch stays as the direct-invocation
    // guard and keeps the recogniser correct on its own.
    if (ctxHasSkipOrLimit(ctx)) {
      return Outcome.DECLINE;
    }

    var normalized = normalize(range);
    if (normalized == null) {
      return Outcome.DECLINE;
    }
    if (normalized.noop()) {
      return Outcome.ACCEPTED;
    }
    if (normalized.skip() > 0) {
      ctx.setSkip(ProjectionExpressionFactories.skip(normalized.skip()));
    }
    if (normalized.limit() >= 0) {
      ctx.setLimit(ProjectionExpressionFactories.limit(normalized.limit()));
    }
    return Outcome.ACCEPTED;
  }

  private static Outcome recognizePostUnion(
      StepCursor cursor, RecognitionContext ctx, RangeGlobalStepContract<?> range) {
    for (var op : ctx.postConcatOps()) {
      if (op instanceof PostConcatOp.Range || op instanceof PostConcatOp.Count) {
        // Second range, or range after count: decline (count already collapsed the stream).
        return Outcome.DECLINE;
      }
    }
    var normalized = normalize(range);
    if (normalized == null) {
      return Outcome.DECLINE;
    }
    if (normalized.noop()) {
      return Outcome.ACCEPTED;
    }
    // Positional gate (see the class Javadoc): the concatenation's row order is not native's, so a
    // slice may only stand when the very next step collapses it to a cardinality. An op between the
    // slice and the count does not qualify — union(...).limit(3).dedup().count() counts the distinct
    // rows OF THE FIRST THREE, and which three those are is exactly what the two orders disagree
    // about.
    if (!followedByCount(cursor)) {
      return Outcome.DECLINE;
    }
    ctx.appendPostConcatOp(new PostConcatOp.Range(normalized.skip(), normalized.limit()));
    return Outcome.ACCEPTED;
  }

  /**
   * Whether the step the cursor is about to hand back to dispatch is {@code count()}. Matched on the
   * exact class, mirroring the walker's class-keyed dispatch: a {@code CountGlobalStep} subclass has
   * no registry entry and would decline the traversal anyway, so treating it as a count here would
   * be the one direction that is not fail-closed.
   */
  private static boolean followedByCount(StepCursor cursor) {
    Step<?, ?> next = cursor.peek();
    return next != null && next.getClass() == CountGlobalStep.class;
  }

  /**
   * Whether {@code step} would contribute a real {@link PostConcatOp.Range} — a slice that selects
   * by position — rather than normalising away to nothing or being a shape {@link #recognize}
   * declines outright. {@link GremlinStepWalker#postUnionSuffixTranslatable} reads this through the
   * {@link StepRecogniser} seam so its pre-fork look-ahead applies the same positional gate {@link
   * #recognizePostUnion} applies, and so a no-op slice keeps reaching the fork exactly as before.
   *
   * <p>The un-normalisable case is reachable from the DSL, not only through a mis-registered step
   * class. {@code RangeGlobalStep}'s constructor rejects a range only when both bounds are set and
   * {@code low > high}, so {@code limit(-5)} throws — it builds {@code RangeGlobalStep(0, -5)} —
   * while {@code skip(-5)} and {@code range(-5, 10)} construct, survive strategy application, and
   * arrive here. {@link #normalize} returns {@code null} for them and this answers {@code false},
   * which is the safe direction: the look-ahead applies no gate, the fork is paid, and
   * {@link #recognizePostUnion} declines at the same {@code normalize} call one fork later. The
   * cost is N discarded sub-walks on a query nobody writes; the answer stays right.
   */
  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    if (!(step instanceof RangeGlobalStepContract<?> range)) {
      return false;
    }
    var normalized = normalize(range);
    return normalized != null && !normalized.noop();
  }

  /**
   * The single-plan and post-union branches differ only in where the normalised range goes — SQL
   * {@code SKIP}/{@code LIMIT} clauses versus a {@link PostConcatOp.Range} — so both read the step
   * through here and the normalisation rules stay in one place.
   *
   * @param skip rows to drop before emitting; never negative
   * @param limit rows to emit after the skip, or {@code -1} for unbounded (skip-only)
   * @param noop whether the range drops nothing and bounds nothing, so it needs no clause at all
   */
  private record NormalizedRange(long skip, long limit, boolean noop) {
  }

  /** Normalises the step's low/high pair, or returns {@code null} when the shape must decline. */
  private static NormalizedRange normalize(RangeGlobalStepContract<?> range) {
    var lowObj = range.getLowRange();
    var highObj = range.getHighRange();
    if (lowObj == null || highObj == null) {
      return null;
    }
    long low = lowObj;
    long high = highObj;
    if (low < 0) {
      return null;
    }
    var unboundedHigh = high < 0 || high == Long.MAX_VALUE;
    if (unboundedHigh) {
      // range(0, -1) / skip(0) is a no-op — accept without clauses.
      return new NormalizedRange(low, -1L, low == 0);
    }
    if (high < low) {
      // Native emits no traversers; LIMIT 0 matches that empty result.
      high = low;
    }
    return new NormalizedRange(low, high - low, false);
  }

  private static boolean ctxHasSkipOrLimit(RecognitionContext ctx) {
    return ctx.skip() != null || ctx.limit() != null;
  }
}
