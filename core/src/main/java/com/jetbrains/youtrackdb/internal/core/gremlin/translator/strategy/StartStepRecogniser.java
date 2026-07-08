package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.jspecify.annotations.NonNull;

/**
 * Recogniser for the start step of a vertex-rooted traversal — the bare {@code g.V()}
 * or {@code g.V(ids)} shape. Holds all of Phase 1's translation logic for the single-
 * node pattern: ID normalisation (Identifiable / RID strings) and single-vs-multi-ID
 * routing through {@code aliasRids} or a hand-built {@code @rid IN [...]} filter. It
 * emits no {@code @class} narrowing: a bare {@code g.V()} / {@code g.V(ids)} returns the
 * full polymorphic vertex set on the native pipeline regardless of the polymorphic flag,
 * so narrowing to the exact root class would drop subclass instances the native path keeps.
 *
 * <p>The recogniser is the only one registered in Phase 1's
 * {@link GremlinStepWalker} registry. It only accepts a step at index 0 — running it
 * later would imply a non-{@code GraphStep} starting the traversal, which the walker
 * already declines through "no recogniser matched the start step".
 *
 * <h2>Gating on the plain TinkerPop {@code GraphStep}, not the YTDB subclass</h2>
 *
 * The recogniser keys on the plain TinkerPop {@link GraphStep}, <em>not</em> {@code
 * YTDBGraphStep}. The strategy that drives this walker runs <em>before</em> {@code
 * YTDBGraphStepStrategy} — the sole producer of {@code YTDBGraphStep} — so at translator
 * time the start step is still a plain {@code GraphStep}. Keying on {@code YTDBGraphStep}
 * here would decline every recognised shape and the translator would translate nothing.
 * The walker's dispatch is a class-keyed lookup on the step's <em>exact</em> runtime class,
 * so this key is fail-safe under an ordering change rather than merely robust: if the
 * translator ever ran after {@code YTDBGraphStepStrategy} folded the start step into a
 * {@code YTDBGraphStep}, that subclass has no registry entry and the traversal declines
 * cleanly — an {@code instanceof}-based gate would instead accept the subclass and risk
 * mis-recognising a shape the translator never validated for.
 *
 * <h2>Why these checks belong here, not in the walker</h2>
 *
 * Recasting the start-step gates (vertex shape, hasContainers, ID parsing) as a
 * recogniser keeps the walker agnostic of step-specific rules — every gate is local to
 * the recogniser that knows the step it handles. This keeps later tracks' recogniser
 * additions purely additive: each new recogniser brings its own gates.
 *
 * <h2>Single-vs-multi-ID handling</h2>
 *
 * <ul>
 *   <li>The single-ID case ({@code g.V(id)}) lands on {@code aliasRids} — the planner's
 *       optimised path that resolves to {@code SELECT FROM #X:Y}. The empty-ID case
 *       ({@code g.V()}) carries no RID hint and no filter, so the node resolves to a bare
 *       {@code SELECT FROM V} (the full polymorphic class scan).</li>
 *   <li>The multi-ID case ({@code g.V(id1, id2, …)}) lands on {@code aliasFilters} as
 *       {@code WHERE @rid IN [#X1:Y1, #X2:Y2, …]}. The planner's
 *       {@code createSelectStatement} falls through to {@code SELECT FROM Class WHERE …}
 *       when the alias's RID slot is empty, which honours arbitrary-arity IN
 *       constraints.</li>
 * </ul>
 *
 * <h2>Boundary alias</h2>
 *
 * The single matched node uses the alias {@value #BOUNDARY_ALIAS}. The {@code $g2m_}
 * prefix is the translator's private namespace — distinct from GQL's {@code $c} prefix
 * and from {@code MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX} so neither inline-named
 * user aliases nor the planner's internal auto-generation can collide.
 */
final class StartStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final StartStepRecogniser INSTANCE = new StartStepRecogniser();

  /**
   * Alias used by the Phase 1 single-node pattern. The {@code $g2m_} prefix is the
   * Gremlin-to-MATCH translator's reserved namespace; the {@code v0} suffix names this
   * pattern's only vertex node. Later tracks add more aliases under the same prefix.
   */
  private static final String BOUNDARY_ALIAS = "$g2m_v0";

  /**
   * Default schema class for {@code g.V()}-rooted traversals — the abstract vertex root.
   * Same convention {@code GqlMatchPatternAssembler.effectiveType} uses when no explicit label
   * is supplied.
   */
  private static final String DEFAULT_VERTEX_CLASS = "V";

  private StartStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public boolean recognize(Step<?, ?> step, WalkerContext ctx) {
    // The start-step recogniser is only meaningful at the head of a traversal; refusing
    // here keeps a misregistered registry (e.g. a future track placing this recogniser
    // after a step that fell through) from silently accepting a non-start position.
    if (ctx.stepIndex != 0) {
      return false;
    }
    // Gate on the plain TinkerPop GraphStep, not YTDBGraphStep: this walker runs before
    // YTDBGraphStepStrategy produces the YTDB subclass, so at translator time the start
    // step is still a plain GraphStep. See the class Javadoc "Gating on the plain
    // TinkerPop GraphStep" section for why keying on YTDBGraphStep would translate nothing.
    if (!(step instanceof GraphStep<?, ?> graphStep)) {
      return false;
    }
    // returnsVertex() distinguishes g.V() (accept) from g.E() (decline — edge starts are
    // a later milestone). This mirrors the strategy's own vertex-start gate.
    if (!graphStep.returnsVertex()) {
      return false;
    }
    // Defence in depth against a start step that already carries folded predicates. A plain
    // GraphStep has no hasContainers of its own (folding produces YTDBGraphStep), but if a
    // HasContainerHolder start step with non-empty containers ever reaches here — e.g. a
    // unit test driving the walker directly, or a future ordering change — a folded label
    // predicate must still cause a clean decline rather than being silently dropped.
    if (step instanceof HasContainerHolder<?, ?> holder
        && !holder.getHasContainers().isEmpty()) {
      return false;
    }

    // Normalise the start step's ids to distinct RIDs in one pass. A null return declines the
    // whole traversal: either an unconvertible id, or a duplicate the set-semantics @rid IN
    // filter cannot reproduce — both rationales are documented on normaliseIds. The empty-id
    // g.V() case returns an empty list, not null.
    var rids = normaliseIds(graphStep.getIds());
    if (rids == null) {
      return false;
    }

    // Validation done; commit mutations to the context. The polymorphism flag is resolved and
    // pinned once by the walker at context construction (see GremlinStepWalker.walk); no
    // recogniser initialises it.
    ctx.patternBuilder.addNode(BOUNDARY_ALIAS, DEFAULT_VERTEX_CLASS, null, false);
    ctx.boundaryAlias = BOUNDARY_ALIAS;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;

    // All ID sources flow through a WHERE @rid IN [...] filter; the empty-ID g.V() case carries
    // no filter. The planner's promoteStaticRidsFromFilters lifts the IN list into pinned RIDs —
    // a size-1 IN collapses to a single pinned RID and the SELECT FROM #X:Y fast path, the same as
    // a bare @rid equality. No @class narrowing is applied: for a bare g.V() (and g.V(ids) with no
    // folded label) the native pipeline returns the full polymorphic set regardless of the
    // polymorphic flag — the no-id branch always browses the class polymorphically, and the by-id
    // branch applies no class filter (YTDBGraphImplAbstract.elements / YTDBGraphStep.elements).
    // Emitting @class = 'V' would wrongly exclude subclass instances.
    if (!rids.isEmpty()) {
      ctx.aliasFilters.put(BOUNDARY_ALIAS, wrapWhere(buildRidInExpression(rids)));
    }

    // The boundary step pulls the matched vertex out of each result row by name, so the
    // RETURN projection must keep the alias as the row key. A single SQLProjectionItem
    // expressing `boundaryAlias AS boundaryAlias` survives the planner's projection
    // pipeline with the alias-keyed shape intact.
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(BOUNDARY_ALIAS)));
    ctx.returnAliases.add(new SQLIdentifier(BOUNDARY_ALIAS));
    ctx.returnNestedProjections.add(null);

    // Advance the cursor past the one step this recogniser consumed (the start GraphStep). Under
    // the index-driven walker the recogniser owns this advance — the walker no longer does an
    // unconditional ++ — so a single-step claim advances by exactly one. Done last, after every
    // context mutation, so a decline before this point leaves the cursor untouched.
    ctx.stepIndex++;
    return true;
  }

  /**
   * Normalises the start step's heterogeneous {@code Object[] ids} into a list of distinct
   * {@link RecordIdInternal} in a single pass, returning {@code null} as a "decline" sentinel
   * when the shape cannot be translated faithfully. Two conditions decline: an element that does
   * not normalise, or the same RID appearing more than once.
   *
   * <p>{@link Identifiable} (Gremlin {@code Vertex}/{@code Edge}, YTDB record handles) and
   * RID-shaped {@link String}s ({@code "#X:Y"}) normalise; anything else (numbers, arbitrary
   * objects) declines. An empty or null input maps to an empty list — the {@code g.V()} no-ID
   * case — which the caller tells apart from a {@code null} decline.
   *
   * <p>The duplicate check shares this pass. Native {@code g.V(ids)}
   * ({@code YTDBGraphImplAbstract.elements}) streams the id array one-to-one with no dedup, so a
   * repeated id emits its vertex once per occurrence; a translated {@code @rid IN [...]} filter
   * has set semantics and emits each vertex once. MATCH cannot express "emit the same vertex
   * twice", so a duplicated id is a shape this recogniser cannot match exactly — decline rather
   * than return a smaller multiset than the native pipeline. The single-id path (one native
   * emission, one aliasRids lookup) can never trip this.
   *
   * <p>Dedup keys on the RID value ({@link RidKey}: collection id + position), not the
   * {@link RecordIdInternal} instance: ids arrive from two paths — {@link
   * RecordIdInternal#fromString} and {@link Identifiable#getIdentity} — that can return different
   * concrete subtypes for the same logical rid, so an instance-hashCode set is not guaranteed to
   * collide them. The same {@code @rid IN} filter is emitted regardless of which subtype carried
   * the value.
   */
  @Nullable private static List<RecordIdInternal> normaliseIds(Object[] ids) {
    if (ids == null || ids.length == 0) {
      return List.of();
    }
    var rids = new ArrayList<RecordIdInternal>(ids.length);
    var seen = new HashSet<RidKey>(ids.length);
    for (var id : ids) {
      var rid = toRecordId(id);
      if (rid == null) {
        // Unconvertible id — decline so the traversal stays on the native pipeline, which knows
        // how to resolve every Gremlin id shape.
        return null;
      }
      if (!seen.add(new RidKey(rid.getCollectionId(), rid.getCollectionPosition()))) {
        // Repeated id — the set-semantics @rid IN filter cannot reproduce the native pipeline's
        // one-emission-per-occurrence multiset. Decline.
        return null;
      }
      rids.add(rid);
    }
    return rids;
  }

  /**
   * Best-effort conversion of one Gremlin ID into a {@link RecordIdInternal}. Mirrors
   * the subset that {@link SQLRid#toRecordId} accepts so the recogniser's plan-time
   * decision matches the runtime evaluator's semantics.
   *
   * <p>Empty and whitespace-only strings are explicitly declined: {@link
   * RecordIdInternal#fromString} silently treats them as {@code #-1:-1} (the
   * "changeable RID" placeholder used for new records that have not yet been
   * persisted), which would translate {@code g.V("")} into a degenerate lookup whose
   * result diverges from the native Gremlin path's empty result.
   */
  @Nullable private static RecordIdInternal toRecordId(@Nullable Object id) {
    return switch (id) {
      case Identifiable identifiable -> {
        var identity = identifiable.getIdentity();
        yield identity instanceof RecordIdInternal recordIdInternal ? recordIdInternal : null;
      }
      case String s -> {
        if (s.isBlank()) {
          // Empty / whitespace-only strings are not meaningful RIDs — see Javadoc.
          yield null;
        }
        try {
          yield RecordIdInternal.fromString(s, false);
        } catch (RuntimeException ex) {
          // Malformed RID strings are not translatable; decline rather than throw.
          yield null;
        }
      }
      case null, default -> null;
    };
  }

  /**
   * Builds {@code @rid IN [#X1:Y1, #X2:Y2, …]} as a hand-rolled {@link SQLInCondition}.
   * Constructing the AST manually (rather than reusing {@code MatchWhereBuilder.in}) is
   * necessary because the left side must be a {@link SQLRecordAttribute} —
   * {@code @rid} is a record attribute, not a regular property reference, and the
   * runtime evaluator dispatches the two shapes through different code paths in
   * {@code SQLSuffixIdentifier.execute}.
   */
  private static SQLBooleanExpression buildRidInExpression(List<RecordIdInternal> rids) {
    var ridAttr = new SQLRecordAttribute(-1);
    ridAttr.setName("@rid");
    var leftExpr = new SQLExpression(ridAttr, null);

    var values = new ArrayList<SQLExpression>(rids.size());
    for (var rid : rids) {
      // Each RID becomes an SQLExpression-wrapped SQLRid via the literal builder.
      values.add(MatchLiteralBuilder.toLiteral(rid));
    }
    // Wrap the element list in the parser's literal-list AST chain (collection → level-zero →
    // base identifier → base expression) so SQLInCondition.evaluateRight can reach the elements.
    // Shared with MatchWhereBuilder.in via literalCollectionExpression to keep a single copy.
    var rightBase = MatchWhereBuilder.literalCollectionExpression(values);

    return buildRidInCondition(leftExpr, rightBase);
  }

  /**
   * Assembles the {@link SQLInCondition} for {@code @rid IN [...]} from the pre-built left
   * ({@code @rid} attribute) and right (literal-list) expressions, populating the {@code IN}
   * operator so {@code toString} and the plan-time {@code supportsBasicCalculation} path see a
   * well-formed condition. Named with a verb prefix because it constructs a fresh node each call
   * (it is a factory, not an accessor).
   */
  private static @NonNull SQLInCondition buildRidInCondition(SQLExpression leftExpr,
      SQLBaseExpression rightBase) {
    var condition = new SQLInCondition(-1);
    condition.setLeft(leftExpr);
    condition.setRightMathExpression(rightBase);
    // SQLInCondition needs its operator populated so toString and the plan-time
    // supportsBasicCalculation path see a well-formed IN condition. setOperator is public and
    // SQLInOperator implements SQLBinaryCompareOperator, so we set it directly — the same way
    // MatchWhereBuilder.in constructs its inline-list IN condition.
    condition.setOperator(new SQLInOperator(-1));
    return condition;
  }

  /** Dedup key for {@link #normaliseIds}: the RID value (collection id + position), not the
   *  {@link RecordIdInternal} instance. */
  private record RidKey(int collectionId, long position) {
  }

  private static SQLWhereClause wrapWhere(SQLBooleanExpression expr) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(expr);
    return clause;
  }
}
