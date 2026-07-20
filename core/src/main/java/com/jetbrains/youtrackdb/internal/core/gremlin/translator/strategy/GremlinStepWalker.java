package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
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
   * Today only {@link NoOpBarrierStep}, the barrier {@code LazyBarrierStrategy} wedges between chained
   * hops. Adding a transparent type is a one-line change here that touches no recogniser.
   */
  private static final Set<Class<?>> TRANSPARENT_STEPS = Set.of(NoOpBarrierStep.class);

  /**
   * Production recogniser registry, keyed on the exact step class. {@link StartStepRecogniser} claims
   * the vertex source under {@link GraphStep}; {@link VertexStepRecogniser} owns {@link VertexStep}
   * and {@link VertexStepPlaceholder} (the latter appears on combinator child sub-traversals after
   * {@code AdjacentToIncidentStrategy} runs recursively during {@code applyStrategies()}) and routes
   * on {@code returnsEdge()} — a folded bare hop to {@link VertexHopRecogniser}, an edge-returning
   * {@code outE(L).has(...).inV()} chain to {@link EdgeHopRecogniser}. {@link HasStepRecogniser}
   */
  private static final Map<Class<?>, StepRecogniser> PRODUCTION_RECOGNISERS =
      Map.of(
          GraphStep.class, StartStepRecogniser.INSTANCE,
          VertexStep.class, VertexStepRecogniser.INSTANCE,
          VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
          HasStep.class, HasStepRecogniser.INSTANCE,
          TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE,
          AndStep.class, AndStepRecogniser.INSTANCE,
          OrStep.class, OrStepRecogniser.INSTANCE);

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
    var steps = child.getSteps();
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
   * Snapshots the walker context into a {@link GremlinToMatchTranslator.TranslationResult}. Locks the
   * pattern (one-shot {@code build()}), merges builder-supplied alias filters with recogniser-supplied
   * ones (AND-composing on the same alias), and packages the {@link MatchPlanInputs}.
   */
  private static GremlinToMatchTranslator.TranslationResult buildResult(WalkerContext ctx) {
    var ir = ctx.patternBuilder.build();

    Map<String, SQLWhereClause> finalAliasFilters = new LinkedHashMap<>(ir.aliasFilters());
    // AND-compose recogniser-contributed filters with any builder-supplied filter on the same alias
    // rather than overwriting: a hasLabel(L) @class narrowing and a has(...) predicate can both land
    // on the boundary alias, and dropping either would return a wrong (over-large) multiset.
    for (var entry : ctx.aliasFilters.entrySet()) {
      finalAliasFilters.merge(entry.getKey(), entry.getValue(), GremlinStepWalker::andWhere);
    }

    // Only the fields a single-node g.V() translation actually carries are set; the rest keep their
    // null/false defaults (matchExpressions/notMatchExpressions normalise to empty lists in the
    // compact constructor). The builder names each field so a future track adding one cannot silently
    // transpose a positional argument.
    var inputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(finalAliasFilters)
            .returnItems(ctx.returnItems)
            .returnAliases(ctx.returnAliases)
            .returnNestedProjections(ctx.returnNestedProjections)
            .build();

    return new GremlinToMatchTranslator.TranslationResult(
        inputs, ctx.boundaryAlias, ctx.outputType, ctx.returnClass);
  }

  /** AND-composes two same-alias {@code WHERE} clauses into one — the merge function used when both
   *  the pattern builder and a recogniser contribute a filter to the same alias. */
  private static SQLWhereClause andWhere(SQLWhereClause a, SQLWhereClause b) {
    return WHERE.wrap(WHERE.and(a.getBaseExpression(), b.getBaseExpression()));
  }
}
