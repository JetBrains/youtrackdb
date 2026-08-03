package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.UnionStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep.WhereEndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep.WhereStartStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupCountStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MaxGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MinGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SumGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;

/**
 * Walks a {@link Traversal.Admin}'s step list through a {@link StepCursor} and dispatches each head
 * to the {@link StepRecogniser} registered for its exact runtime class in a class-keyed registry
 * ({@code Map<Class<?>, StepRecogniser>}). The walker is the entry point the
 * {@link GremlinToMatchTranslator} delegates to; recognisers carry the per-step recognition logic so
 * the walker itself stays a thin dispatch loop.
 *
 * <h2>All-or-nothing translation</h2>
 *
 * Any step the registered recognisers cannot claim declines the entire walk (returning {@code null}).
 * There is no "partial prefix" mechanism — either every step is recognised or the traversal is
 * declined whole and stays on the native TinkerPop pipeline.
 *
 * <h2>Cursor-driven dispatch</h2>
 *
 * Each iteration peeks the head through the cursor (transparent barrier steps skipped), looks up the
 * recogniser for the head's exact class, and runs it. A missing recogniser declines the whole walk;
 * so does a recogniser's {@link Outcome#DECLINE}. The recogniser advances the cursor by consuming its
 * head and any trailing steps of its shape, so the recognised set is bounded by which step
 * <em>classes</em> have a recogniser, not by a step count: a long but fully-recognised chain
 * translates, and the first unrecognised step class declines. An empty traversal is declined up
 * front. The loop ends when {@link StepCursor#peek()} returns {@code null} — the cursor has skipped
 * every trailing barrier and reached the end of the list, so a walk that reaches the terminator
 * invariant has recognised every step.
 *
 * <h2>Reserved-prefix pre-flight scan</h2>
 *
 * Before dispatching any step, the walker rejects the whole traversal — throwing a {@link
 * ReservedAliasException} — if any user label starts with {@code $}. The {@code $} space is reserved
 * for the translator's minted {@code $g2m_} aliases (see {@link WalkerContext#ANON_VERTEX_ALIAS_PREFIX})
 * and for YouTrackDB's query-context variables, so a user label there is prohibited rather than
 * declined to native. {@link GremlinToMatchStrategy}'s throw-safety net re-throws this one exception
 * type so the query fails loudly, while every other failure still degrades to a native decline. The
 * scan is purely lexical, so it runs before the session-dependent flag resolution.
 *
 * <h2>Resolved flags in the walker; shape gates in recognisers</h2>
 *
 * Every per-step shape gate (start-step shape, vertex-vs-edge, ID convertibility, hasContainer
 * presence, predicate well-formedness, …) lives inside the responsible recogniser. The walker
 * resolves the two traversal-level flags once up front and stores them on the {@link WalkerContext}:
 * the polymorphism flag ({@code YTDBStrategyUtil.isPolymorphic}) and whether {@code
 * EdgeLabelVerificationStrategy} is present. Resolving polymorphism here is safe: {@code
 * isPolymorphic} is null-safe (it gates on an attached YTDB graph and transaction before touching
 * {@code tx()}), and a {@code null} result declines the whole walk.
 *
 * <h2>Result assembly</h2>
 *
 * On a successful walk, the walker calls {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder#build}
 * exactly once to lock the pattern, merges the builder's alias filters with any filters recognisers
 * contributed outside the builder, and packages the {@link MatchPlanInputs} into a {@link
 * GremlinToMatchTranslator.TranslationResult}.
 */
final class GremlinStepWalker {

  /**
   * Step classes the cursor treats as transparent: skipped on every read and counted as consumed.
   * Adding a transparent type is a one-line change here that touches no recogniser.
   *
   * <p>{@link NoOpBarrierStep} is transparent because {@code LazyBarrierStrategy} wedges one
   * between chained hops, and {@code RepeatUnrollStrategy} wedges one between the hops it unrolls a
   * {@code repeat(...)} into. Skipping it preserves the <em>answer set</em>: the barrier merges
   * identical traversers into bulks, and a MATCH plan reaches the same answers by other means.
   *
   * <p>The transparency rule carries no <em>cost</em> bound, for either barrier. Bulking is what
   * keeps a chain of n hops at n passes over the edge set; a MATCH plan enumerates one row per
   * distinct path, which grows as the n-th power of the average degree. That is a property of the
   * chain and not of the syntax it came from, so a hand-written n-hop chain reaches the same
   * enumeration as an unrolled {@code repeat(__.out()).times(n)}. {@link RepeatDeclineStrategy}
   * bounds the {@code repeat(...)} spelling, which is the one the TinkerPop feature suite drove to
   * a stall; a deep hand-written chain is still translated and still pays the enumeration. Bounding
   * that shape needs a depth or fan-out gate the translator does not have yet.
   */
  private static final Set<Class<?>> TRANSPARENT_STEPS =
      Set.of(NoOpBarrierStep.class, WhereStartStep.class, WhereEndStep.class);

  /**
   * Production recogniser registry, keyed on the exact step class. Dispatch is O(1) on the step's
   * runtime class, and a step whose class is not a key declines the whole traversal (all-or-nothing),
   * so an unregistered or unexpected subclass fails safe to the native pipeline rather than being
   * misrouted. The registered families:
   *
   * <ul>
   *   <li><b>Source</b> — {@link StartStepRecogniser} claims the vertex source under {@link
   *       GraphStep}.
   *   <li><b>Traversal</b> — {@link VertexStepRecogniser} owns {@link VertexStep} and {@link
   *       VertexStepPlaceholder} (the latter appears on combinator child sub-traversals after {@code
   *       AdjacentToIncidentStrategy} runs recursively during {@code applyStrategies()}) and routes on
   *       {@code returnsEdge()}: a folded bare hop to {@link VertexHopRecogniser}, an edge-returning
   *       {@code outE(L).has(...).inV()} chain to {@link EdgeHopRecogniser}, and a combinator sub-walk
   *       singleton edge-returning hop to {@link CombinatorFoldedHopRecogniser}.
   *   <li><b>Filter</b> — {@link HasStepRecogniser} ({@link HasStep}), {@link
   *       TraversalFilterStepRecogniser} ({@link TraversalFilterStep}), the connectives {@link
   *       AndStepRecogniser} / {@link OrStepRecogniser} / {@link NotStepRecogniser}, and {@link
   *       WhereTraversalStepRecogniser} / {@link WherePredicateStepRecogniser}.
   *   <li><b>Result shaping</b> — {@link DedupGlobalStepRecogniser}; the projections {@link
   *       PropertiesStepRecogniser} / {@link PropertyMapStepRecogniser} / {@link
   *       ElementMapStepRecogniser} / {@link SelectOneStepRecogniser} / {@link SelectStepRecogniser} /
   *       {@link ProjectStepRecogniser}; and pagination {@link OrderGlobalStepRecogniser} / {@link
   *       RangeGlobalStepRecogniser} ({@link RangeGlobalStep} and {@link RangeGlobalStepPlaceholder}).
   *   <li><b>Aggregate</b> — {@link CountGlobalStepRecogniser}; {@link PropertyAggregateStepRecogniser}
   *       for {@code sum} / {@code min} / {@code max} / {@code mean}; and {@link GroupStepRecogniser} /
   *       {@link GroupCountStepRecogniser}.
   *   <li><b>Branch</b> — {@link UnionStepRecogniser} for mid-traversal {@code union(c1, …, cN)},
   *       emitting a multi-plan translation when every child agrees on the projection contract.
   * </ul>
   */
  private static final Map<Class<?>, StepRecogniser> PRODUCTION_RECOGNISERS =
      Map.ofEntries(
          Map.entry(GraphStep.class, StartStepRecogniser.INSTANCE),
          Map.entry(VertexStep.class, VertexStepRecogniser.INSTANCE),
          Map.entry(VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE),
          Map.entry(HasStep.class, HasStepRecogniser.INSTANCE),
          Map.entry(TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE),
          Map.entry(AndStep.class, AndStepRecogniser.INSTANCE),
          Map.entry(OrStep.class, OrStepRecogniser.INSTANCE),
          Map.entry(NotStep.class, NotStepRecogniser.INSTANCE),
          Map.entry(WhereTraversalStep.class, WhereTraversalStepRecogniser.INSTANCE),
          Map.entry(WherePredicateStep.class, WherePredicateStepRecogniser.INSTANCE),
          Map.entry(DedupGlobalStep.class, DedupGlobalStepRecogniser.INSTANCE),
          Map.entry(PropertiesStep.class, PropertiesStepRecogniser.INSTANCE),
          Map.entry(PropertyMapStep.class, PropertyMapStepRecogniser.INSTANCE),
          Map.entry(ElementMapStep.class, ElementMapStepRecogniser.INSTANCE),
          Map.entry(SelectOneStep.class, SelectOneStepRecogniser.INSTANCE),
          Map.entry(SelectStep.class, SelectStepRecogniser.INSTANCE),
          Map.entry(ProjectStep.class, ProjectStepRecogniser.INSTANCE),
          Map.entry(OrderGlobalStep.class, OrderGlobalStepRecogniser.INSTANCE),
          Map.entry(RangeGlobalStep.class, RangeGlobalStepRecogniser.INSTANCE),
          Map.entry(RangeGlobalStepPlaceholder.class, RangeGlobalStepRecogniser.INSTANCE),
          Map.entry(CountGlobalStep.class, CountGlobalStepRecogniser.INSTANCE),
          Map.entry(SumGlobalStep.class, PropertyAggregateStepRecogniser.INSTANCE),
          Map.entry(MinGlobalStep.class, PropertyAggregateStepRecogniser.INSTANCE),
          Map.entry(MaxGlobalStep.class, PropertyAggregateStepRecogniser.INSTANCE),
          Map.entry(MeanGlobalStep.class, PropertyAggregateStepRecogniser.INSTANCE),
          Map.entry(GroupStep.class, GroupStepRecogniser.INSTANCE),
          Map.entry(GroupCountStep.class, GroupCountStepRecogniser.INSTANCE),
          Map.entry(UnionStep.class, UnionStepRecogniser.INSTANCE));

  /**
   * The only recognisers allowed to claim a step <em>after</em> {@link UnionStepRecogniser} has
   * stashed a multi-plan carrier — the three whose step maps to a {@link
   * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp} the concatenation
   * can absorb ({@code count}, {@code limit}/{@code range}/{@code skip}, {@code dedup}).
   *
   * <p>The gate has to be here rather than left to each recogniser because {@link #buildResult}'s
   * multi-plan branch reads only the boundary metadata, the shaping, and the post-concat ops: a
   * recogniser that writes into the pattern builder, the alias filters, the RETURN projection, the
   * DISTINCT / GROUP BY / ORDER BY / LIMIT / SKIP fields, or the positional-parameter map after a
   * union has its contribution silently discarded, and the query returns rows that ignore the step.
   * Gating on an allow-list keeps the property true by construction: a recogniser added later is
   * declined post-union until it is deliberately taught to branch on the carrier and added here.
   *
   * <p>The set is read from two places, and both must read this one field. {@link #dispatchAll}
   * consults it per step as the fail-closed gate, and {@link #postUnionSuffixTranslatable} consults
   * it as a look-ahead so {@link UnionStepRecogniser} can decline <em>before</em> forking and
   * walking every child — see that method for why the look-ahead exists.
   *
   * <p>Membership is necessary, not sufficient. {@link RangeGlobalStepRecogniser} sits here because
   * a slice <em>can</em> ride the concatenation, but it accepts only when a {@code count()} follows
   * immediately: the concatenation emits child one's rows then child two's while native {@code
   * union(...)} interleaves the arms, so any surviving positional selection would return a different
   * multiset with the translator on than off. That recogniser's class Javadoc carries the full
   * argument.
   */
  private static final Set<StepRecogniser> POST_UNION_RECOGNISERS =
      Set.of(
          CountGlobalStepRecogniser.INSTANCE,
          RangeGlobalStepRecogniser.INSTANCE,
          DedupGlobalStepRecogniser.INSTANCE);

  /**
   * Pre-built production walker. The walker is stateless — only the immutable {@code recognisers}
   * field — so a single shared instance avoids one allocation per Gremlin traversal that reaches the
   * strategy. Mirrors the singleton pattern the strategy itself uses.
   */
  private static final GremlinStepWalker PRODUCTION_INSTANCE =
      new GremlinStepWalker(PRODUCTION_RECOGNISERS);

  /** Stateless builder used to AND-compose same-alias filters at result-build time; construction is
   *  trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private final Map<Class<?>, StepRecogniser> recognisers;

  /**
   * Package-private constructor accepting a curated recogniser registry keyed on step class. Both the
   * production singleton and unit tests use this constructor; production code reaches it via {@link
   * #production()}, tests pass fixture registries directly.
   */
  GremlinStepWalker(Map<Class<?>, StepRecogniser> recognisers) {
    this.recognisers = Map.copyOf(recognisers);
  }

  /** Returns the shared production walker wired with the production recogniser registry. */
  static GremlinStepWalker production() {
    return PRODUCTION_INSTANCE;
  }

  /**
   * Attempts to translate {@code traversal} by walking its steps in order. Returns the {@link
   * GremlinToMatchTranslator.TranslationResult} when every step was recognised, otherwise {@code
   * null}.
   */
  @Nullable GremlinToMatchTranslator.TranslationResult walk(Traversal.Admin<?, ?> traversal) {
    // Empty-traversal gate, before any per-step work. A step-less traversal has nothing to translate
    // and could never pin a boundary, so decline it here rather than let it fall through to the
    // terminator invariant below — an empty traversal is a normal shape, not a recogniser bug.
    var steps = traversal.getSteps();
    if (steps.isEmpty()) {
      return null;
    }

    // Reserved-prefix pre-flight: a user label starting with '$' is prohibited — the namespace is
    // reserved for the minted $g2m_ aliases and YouTrackDB query variables — so reject the whole
    // traversal with a ReservedAliasException. GremlinToMatchStrategy's throw-safety net propagates
    // this one type rather than swallowing it, so the query fails loudly instead of running on native
    // (which accepts the '$' label). Purely lexical (no graph access), so it runs before flag
    // resolution below.
    rejectReservedPrefixLabels(steps);

    // Resolve the polymorphism flag once. isPolymorphic is null-safe: it gates on an attached YTDB
    // graph + transaction before touching tx(), so a detached EmptyGraph or non-YTDB graph yields
    // null rather than throwing. A null result means the traversal has no resolvable polymorphism
    // setting and cannot be translated faithfully — decline the whole walk before building the
    // context. Owning the resolution here keeps every recogniser free of the flag's initialisation.
    Boolean resolved = YTDBStrategyUtil.isPolymorphic(traversal);
    if (resolved == null) {
      return null;
    }
    boolean polymorphic = resolved;

    // Resolve the EdgeLabelVerificationStrategy presence once too, so resolveEdgeLabel reads a
    // boolean instead of scanning the strategy list per hop.
    boolean edgeLabelVerification =
        traversal.getStrategies().getStrategy(EdgeLabelVerificationStrategy.class).isPresent();

    // Resolve the schema snapshot once for the has(...) recogniser's non-String Text type gate. The
    // isPolymorphic resolution above already proved an attached YTDB session (it returns null
    // otherwise, declining the walk), so the session resolves here too; a null schema is a defensive
    // fallback that disables the type gate, translating string predicates best-effort.
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    Schema schema = session != null ? session.getSchema() : null;

    var ctx = new WalkerContext(polymorphic, edgeLabelVerification, schema, recognisers);
    var cursor = new StepStreamCursor(steps, TRANSPARENT_STEPS);
    // Install the union fork host after the cursor exists: the host reads prefix length from the
    // cursor position after UnionStepRecogniser.take(), and keeps the parent Admin private.
    ctx.setUnionForkHost(new UnionForkHostImpl(traversal, cursor, ctx, recognisers));

    // Cursor-driven dispatch. A missing recogniser or a DECLINE declines the whole traversal
    // (all-or-nothing), returning false; the shared driver is reused by the sub-walk below.
    if (!dispatchAll(cursor, ctx, recognisers)) {
      return null;
    }

    // Invariant: a fully-recognised non-empty traversal has its terminator metadata pinned — boundary
    // alias, output type, and return class — by the recogniser that owns its terminator. Empty
    // traversals are gated out above, so reaching here with a null field is not a normal decline: it
    // means a recogniser returned ACCEPTED without pinning the boundary, a recogniser-logic bug.
    //
    // The assert surfaces that bug loudly under -ea (the test/CI default); under -da the decline
    // below is the safety net, so rather than build a null-bearing TranslationResult the walk declines
    // and the traversal stays on the native pipeline unchanged.
    assert ctx.boundaryAlias != null && ctx.outputType != null && ctx.returnClass != null
        : "walk recognised all " + steps.size() + " step(s) but left the boundary unpinned";
    if (ctx.boundaryAlias == null || ctx.outputType == null || ctx.returnClass == null) {
      return null;
    }

    return buildResult(ctx);
  }

  /**
   * Runs the cursor-driven dispatch loop over {@code cursor} against {@code recognisers}, contributing
   * each recognised step to {@code ctx}. Returns {@code true} when every step was recognised, {@code
   * false} on the first step whose exact class has no recogniser or whose recogniser declines
   * (all-or-nothing). This is the loop shared by the top-level {@link #walk} and the {@link #subWalk}
   * sub-walk: the sub-walk needs exactly this loop and none of {@code walk}'s surrounding
   * machinery — the reserved-prefix scan, the once-per-walk flag resolution, the terminator invariant,
   * and {@code buildResult} are top-level-only.
   */
  private static boolean dispatchAll(
      StepStreamCursor cursor, RecognitionContext ctx, Map<Class<?>, StepRecogniser> recognisers) {
    Step<?, ?> head;
    while ((head = cursor.peek()) != null) {
      var recogniser = recognisers.get(head.getClass());
      if (recogniser == null) {
        return false;
      }
      // Post-union suffix gate (see POST_UNION_RECOGNISERS). Only the post-concat-aware recognisers
      // may claim a step once a union carrier is on the context; every other one would write into
      // state buildResult discards for a multi-plan translation, so decline the whole walk and let
      // the traversal run on the native pipeline.
      if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)) {
        return false;
      }
      int positionBefore = cursor.position();
      Outcome outcome = recogniser.recognize(cursor, ctx);
      if (outcome == Outcome.DECLINE) {
        return false;
      }
      // An ACCEPTED must have advanced the cursor. An accept that consumed nothing would re-dispatch
      // the same head forever, so it is a recogniser bug: the assert surfaces it loudly under -ea (an
      // AssertionError, which GremlinToMatchStrategy's RuntimeException-only throw-safety net does not
      // swallow); under -da the defensive decline keeps such a bug from spinning a live query.
      assert cursor.position() > positionBefore
          : "recogniser for "
              + head.getClass().getSimpleName()
              + " returned ACCEPTED without consuming any step";
      if (cursor.position() <= positionBefore) {
        return false;
      }
    }
    return true;
  }

  /**
   * Look-ahead form of {@link #dispatchAll}'s post-union gate: {@code true} when every step still
   * ahead of {@code cursor} would be claimed by a {@link #POST_UNION_RECOGNISERS} member, including
   * the vacuous case of no steps left. Reads the same field the in-loop gate reads, so the two can
   * never disagree about which suffix is translatable.
   *
   * <p>Why look ahead at all: {@link UnionStepRecogniser#recognize} forks the recognised prefix into
   * every child and runs a complete sub-walk per child before returning, and the in-loop gate only
   * fires on the step <em>after</em> the union. A suffix the gate will refuse therefore throws away
   * N full sub-walks on every traversal compilation — {@code applyStrategies()} runs per execution
   * and no walk-level cache exists. Calling this before the fork turns that into an O(suffix) scan
   * over steps already in memory.
   *
   * <p>The scan reads through {@link StepCursor#peek(int)}, which leaves the cursor position
   * untouched and skips transparent steps, so a barrier in the suffix is not mistaken for an
   * unclaimable step. Recogniser lookup is by exact class against the same registry dispatch uses,
   * so a step class that maps onto an allow-listed recogniser through a second key (the {@code
   * RangeGlobalStepPlaceholder} → {@link RangeGlobalStepRecogniser} entry) is accepted here exactly
   * as dispatch would accept it.
   *
   * <p>This is a necessary condition, not a simulation: an allow-listed recogniser may still decline
   * its own step (a second {@code count()}, a {@code dedup(labels)}), in which case the fork is
   * still paid and the in-loop gate plus the recogniser decline the walk. Fail-closed is preserved
   * in both directions — the look-ahead only ever declines shapes the in-loop gate would decline.
   *
   * <p>The one gate mirrored here rather than left to its recogniser is the positional one: a
   * post-union slice needs a {@code count()} immediately after it (see {@link
   * RangeGlobalStepRecogniser}), and {@code union(...).limit(n)} is common enough that paying N
   * discarded sub-walks per compilation for it would give back most of what this look-ahead exists
   * to save. The mirror reads {@link RangeGlobalStepRecogniser#selectsPositionally} rather than
   * re-deriving the normalisation, so a slice that normalises away to nothing still reaches the fork
   * exactly as it did before and the look-ahead stays no stricter than the recogniser.
   */
  static boolean postUnionSuffixTranslatable(
      StepCursor cursor, Map<Class<?>, StepRecogniser> recognisers) {
    for (var ahead = 0;; ahead++) {
      var step = cursor.peek(ahead);
      if (step == null) {
        return true;
      }
      // An unregistered class has no recogniser at all, which dispatch declines before it reaches
      // the gate. Check for it separately — Set.of(...).contains(null) throws.
      var recogniser = recognisers.get(step.getClass());
      if (recogniser == null || !POST_UNION_RECOGNISERS.contains(recogniser)) {
        return false;
      }
      if (recogniser == RangeGlobalStepRecogniser.INSTANCE
          && RangeGlobalStepRecogniser.selectsPositionally(step)) {
        var next = cursor.peek(ahead + 1);
        if (next == null
            || recognisers.get(next.getClass()) != CountGlobalStepRecogniser.INSTANCE) {
          return false;
        }
      }
    }
  }

  /**
   * Drives a sub-walk of {@code child} against {@code recognisers}, capturing the child's
   * contributions into a fresh {@link SubTraversalPredicateAdapter} that wraps {@code parent}. The
   * seam a logical-combinator recogniser reaches through {@link RecognitionContext#walkChild}: it runs
   * the same dispatch loop the top-level walk uses, but over the child's step list and against the
   * delegating capture context, so alias minting bottoms out at the top-level context while every
   * contribution stays buffered in the returned adapter until the combinator commits it.
   *
   * <p>An empty child declines up front, mirroring {@link #walk}'s empty-traversal gate — a combinator
   * child with no steps expresses no filter. Otherwise the adapter's {@link
   * SubTraversalPredicateAdapter#outcome()} is {@link Outcome#ACCEPTED} when every child step was
   * recognised and {@link Outcome#DECLINE} on the first unrecognised one.
   */
  static SubTraversalPredicateAdapter subWalk(
      Traversal.Admin<?, ?> child,
      RecognitionContext parent,
      Map<Class<?>, StepRecogniser> recognisers) {
    var adapter = new SubTraversalPredicateAdapter(parent, recognisers);
    var steps = new ArrayList<>(child.getSteps());
    // Anonymous child traversals inside where/and/or/not sometimes carry a leading GraphStep
    // placeholder; the sub-walk's meaningful steps start after it.
    while (!steps.isEmpty() && steps.getFirst() instanceof GraphStep) {
      steps.removeFirst();
    }
    if (steps.isEmpty()) {
      adapter.markOutcome(Outcome.DECLINE);
      return adapter;
    }
    var cursor = new StepStreamCursor(steps, TRANSPARENT_STEPS);
    adapter.markOutcome(
        dispatchAll(cursor, adapter, recognisers) ? Outcome.ACCEPTED : Outcome.DECLINE);
    return adapter;
  }

  /**
   * Rejects the whole traversal with a {@link ReservedAliasException} if any step carries a user label
   * starting with the reserved {@code $} prefix ({@link WalkerContext#RESERVED_ALIAS_PREFIX}). That
   * namespace is reserved for the translator's minted {@code $g2m_} aliases and YouTrackDB's
   * query-context variables, so a user label there is prohibited rather than translated. Scans every
   * step's {@code getLabels()} once; the scan is purely lexical (no graph access), so the walker runs
   * it before resolving any session-dependent state. The exception is the one failure {@link
   * GremlinToMatchStrategy}'s throw-safety net re-throws rather than degrading to a native decline.
   */
  private static void rejectReservedPrefixLabels(List<?> steps) {
    for (Object raw : steps) {
      // getSteps() is a raw List<Step>; each element is a Step whose labels are user-supplied.
      var step = (Step<?, ?>) raw;
      for (String label : step.getLabels()) {
        // A step's label set can contain a null: as((String) null) reaches AbstractStep.addLabel,
        // which adds the label with no null guard. Skip nulls — a null label is lexical noise that
        // cannot collide with the reserved '$' namespace, so it is never a rejection.
        if (label != null && label.startsWith(WalkerContext.RESERVED_ALIAS_PREFIX)) {
          throw new ReservedAliasException(
              "Gremlin alias '"
                  + label
                  + "' uses the reserved '"
                  + WalkerContext.RESERVED_ALIAS_PREFIX
                  + "' prefix: this namespace is reserved for YouTrackDB internal aliases and query"
                  + " variables. Rename the as(...) label.");
        }
      }
    }
  }

  /**
   * Snapshots the walker context into a {@link GremlinToMatchTranslator.TranslationResult}. When a
   * {@link UnionStepRecogniser} accepted, emits a multi-plan result from the stashed child inputs
   * (the prefix-only pattern on the context is discarded — each child re-walked the prefix).
   * Otherwise locks the pattern, merges alias filters, and packages a single-plan
   * {@link MatchPlanInputs}.
   */
  private static GremlinToMatchTranslator.TranslationResult buildResult(WalkerContext ctx) {
    if (ctx.hasUnionCarrier()) {
      assert ctx.boundaryAlias != null && ctx.outputType != null && ctx.returnClass != null;
      return GremlinToMatchTranslator.TranslationResult.multiPlan(
          zipChildPlans(ctx),
          ctx.postConcatOps(),
          ctx.boundaryAlias,
          ctx.outputType,
          ctx.returnClass,
          ctx.shaping());
    }

    var ir = ctx.patternBuilder.build();

    Map<String, SQLWhereClause> finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());
    // AND-compose recogniser-contributed filters with any builder-supplied filter on the same alias
    // rather than overwriting: a hasLabel(L) @class narrowing and a has(...) predicate can both land
    // on the boundary alias, and dropping either would return a wrong (over-large) multiset.
    for (var entry : ctx.aliasFilters.entrySet()) {
      finalAliasFilters.merge(entry.getKey(), entry.getValue(), GremlinStepWalker::andWhere);
    }

    // The merged map above is what the planner reads for the alias it roots the plan at; every other
    // alias's constraint has to be pushed onto the path item that produced it. This is the first
    // point where both halves exist — the pattern is assembled and the recogniser-contributed
    // filters are merged — so the pass runs here.
    bindPathItemConstraints(ir.pattern(), finalAliasFilters, ir.aliasClasses());

    // Only the fields a single-node g.V() translation actually carries are set; the rest keep their
    // null/false defaults (matchExpressions/notMatchExpressions normalise to empty lists in the
    // compact constructor). The builder names each field so a future track adding one cannot silently
    // transpose a positional argument.
    var inputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(finalAliasFilters)
            .notMatchExpressions(
                ctx.notMatchExpressions.isEmpty() ? null : List.copyOf(ctx.notMatchExpressions))
            .returnItems(ctx.returnItems)
            .returnAliases(ctx.returnAliases)
            .returnNestedProjections(ctx.returnNestedProjections)
            .returnDistinct(ctx.returnDistinct)
            .groupBy(ctx.groupBy)
            .orderBy(ctx.orderBy)
            .limit(ctx.limit)
            .skip(ctx.skip)
            .build();

    Map<Object, Object> inputParameters = new LinkedHashMap<>(ctx.inputParameters.size());
    ctx.inputParameters.forEach(inputParameters::put);
    return GremlinToMatchTranslator.TranslationResult.singlePlan(
        inputs,
        ctx.boundaryAlias,
        ctx.outputType,
        ctx.returnClass,
        Map.copyOf(inputParameters),
        !ctx.ridBearing(),
        ctx.shaping());
  }

  /**
   * Zips the walker context's three parallel union-child lists into the one {@code ChildPlan} list
   * the translation carrier takes. The context keeps them separate because the fork host stashes
   * them as three accumulators; the carrier bundles them so nothing downstream can index them out of
   * step. The parity the zip relies on is asserted where they are stashed.
   */
  private static List<GremlinToMatchTranslator.TranslationResult.ChildPlan> zipChildPlans(
      WalkerContext ctx) {
    var childInputs = ctx.unionChildInputs();
    var childParameters = ctx.unionChildInputParameters();
    var childCacheEligible = ctx.unionChildCacheEligible();
    var childPlans =
        new ArrayList<GremlinToMatchTranslator.TranslationResult.ChildPlan>(childInputs.size());
    for (int i = 0; i < childInputs.size(); i++) {
      childPlans.add(
          new GremlinToMatchTranslator.TranslationResult.ChildPlan(
              childInputs.get(i), childParameters.get(i), childCacheEligible.get(i)));
    }
    return List.copyOf(childPlans);
  }

  /** AND-composes two same-alias {@code WHERE} clauses into one — the merge function used when both
   *  the pattern builder and a recogniser contribute a filter to the same alias. */
  private static SQLWhereClause andWhere(SQLWhereClause a, SQLWhereClause b) {
    return WHERE.wrap(WHERE.and(a.getBaseExpression(), b.getBaseExpression()));
  }

  /**
   * Pushes each alias's class and {@code WHERE} onto the path item that targets it, so a constraint
   * on an alias the planner does not root still reaches the executor.
   *
   * <p>A forward hop's target constraint is read off the path item itself — {@code
   * MatchEdgeTraverser} calls {@code item.getFilter().getFilter()} and {@code .getClassName(…)} — and
   * not off the plan's alias maps, which the planner consults for the root alias and, when it
   * traverses an edge backwards, for the syntactic source. Every positive path item is constructed
   * alias-only, so before this pass a predicate on any other alias had no consumer at all:
   * {@code g.V(a).out().has(k, v)} returned every out-neighbour of {@code a} rather than the matching
   * one, and returned it with no error.
   *
   * <p>The binding merges and never rebinds:
   *
   * <ul>
   *   <li>It AND-composes with the {@code WHERE} the item already carries. An edge path item's own
   *       predicate ({@code outE(L).has(p, v).inV()}) lives nowhere else — the walker's edge-filter
   *       map is observability-only and never reaches the plan inputs — so an overwriting rebind
   *       would drop it silently.
   *   <li>It leaves an item whose alias is in neither map untouched, for the same reason.
   *   <li>It does not overwrite a class the item already carries.
   * </ul>
   *
   * <p>The class is bound as well as the {@code WHERE} because under polymorphic mode a {@code
   * hasLabel(L)} contributes no {@code @class} term to the alias filter (see {@link
   * HasStepRecogniser}), which leaves the item's class slot as the constraint's only carrier — a
   * {@code WHERE}-only binding would lose {@code g.V(a).out().hasLabel(L)} entirely. The generic
   * {@code V} root class the hop recognisers register is skipped: it excludes nothing a vertex hop
   * can reach, and binding it would add a per-candidate class check to every translated hop.
   *
   * <p>The items are mutated in place. {@code Pattern.copy()} shares its path items with the
   * builder's pattern and the planner takes the pattern by reference, so these are the objects the
   * executor reads; the builder is locked by {@code build()} before this runs, so no later
   * construction call can observe a half-bound item. Sharing is at the item level, not the filter
   * level: every construction path attaches a fresh {@code SQLMatchFilter} per item and {@code
   * SQLMatchPathItem.copy()} deep-copies it, so no two items hold one filter. What the {@code
   * existing.copy()} below buys is therefore narrow — the bound filter is a new object, so anything
   * still holding the pre-bind filter does not observe the binding. It buys no isolation between
   * patterns: {@code setFilter} on a shared item is visible through every pattern holding that item,
   * copy or no copy. The bound {@code WHERE} is copied for the same reason the planner copies its
   * own sub-clauses — one {@code SQLWhereClause} instance would otherwise serve both the path item
   * and {@code MatchPlanInputs.aliasFilters}, and an AST rewrite on either side would reach both.
   *
   * <p>Package-private rather than private so the merge rules above can be asserted directly. Only
   * the leave-an-unlisted-alias-alone rule is reachable end-to-end today — {@code
   * outE(L).has(p, v).inV().has(q, w)} binds the target while the edge item stays untouched, and an
   * equivalence case covers it. The other two (an item that already carries a {@code WHERE}, an item
   * that already carries a class) have no traversal shape that reaches them, so a regression would
   * break them silently; unit tests against this method are the only net they have.
   */
  static void bindPathItemConstraints(
      Pattern pattern, Map<String, SQLWhereClause> aliasFilters, Map<String, String> aliasClasses) {
    for (var node : pattern.aliasToNode.values()) {
      for (var edge : node.out) {
        // Pattern.addExpression names each node after its item's own filter alias, so the alias at
        // the head of the edge is the one whose constraints belong on this item.
        var alias = edge.in.alias;
        var where = aliasFilters.get(alias);
        var className = aliasClasses.get(alias);
        if (WalkerContext.VERTEX_ROOT_CLASS.equals(className)) {
          // The generic vertex root the hop recognisers register excludes nothing a vertex hop can
          // reach; binding it would cost a class check per candidate row for no narrowing.
          className = null;
        }
        if (where == null && className == null) {
          continue;
        }
        var item = edge.item;
        var existing = item.getFilter();
        assert existing != null
            : "path item for alias " + alias + " has no filter block to bind onto";
        var bound = existing.copy();
        if (className != null && bound.getClassName(null) == null) {
          bound.setClassName(className);
        }
        if (where != null) {
          var existingWhere = bound.getFilter();
          // A copy rather than the map's own instance: the same map goes to MatchPlanInputs, so
          // binding the clause itself would leave one mutable AST shared by the path item and the
          // planner. andWhere reuses its operands' expression nodes, so the copy is taken on both
          // branches, matching the planner's own copy-before-you-share policy.
          var isolated = where.copy();
          bound.setFilter(existingWhere == null ? isolated : andWhere(existingWhere, isolated));
        }
        item.setFilter(bound);
      }
    }
  }
}
