package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for the start step of a vertex-rooted traversal — the bare {@code g.V()} or {@code
 * g.V(ids)} shape. Holds all of Phase 1's translation logic for the single-node pattern: ID
 * normalisation (Identifiable / RID strings) and single-vs-multi-ID routing through {@code aliasRids}
 * or a hand-built {@code @rid IN [...]} filter. It emits no {@code @class} narrowing: a bare {@code
 * g.V()} / {@code g.V(ids)} returns the full polymorphic vertex set on the native pipeline regardless
 * of the polymorphic flag, so narrowing to the exact root class would drop subclass instances the
 * native path keeps.
 *
 * <p>The recogniser is the only one registered under {@link GraphStep} in Phase 1's {@link
 * GremlinStepWalker} registry. Its "I am the start" guard is a state check — a non-null {@link
 * RecognitionContext#boundaryAlias()} declines — not an absolute-index check: a pinned boundary means
 * a start step already ran, which is the same condition without the walker exposing an index.
 *
 * <h2>Gating on the plain TinkerPop {@code GraphStep}, not the YTDB subclass</h2>
 *
 * The recogniser keys on the plain TinkerPop {@link GraphStep}, <em>not</em> {@code YTDBGraphStep}.
 * The strategy that drives this walker runs <em>before</em> {@code YTDBGraphStepStrategy} — the sole
 * producer of {@code YTDBGraphStep} — so at translator time the start step is still a plain {@code
 * GraphStep}. Keying on {@code YTDBGraphStep} here would decline every recognised shape and the
 * translator would translate nothing. The walker's dispatch is a class-keyed lookup on the step's
 * <em>exact</em> runtime class, so this key is fail-safe under an ordering change: if the translator
 * ever ran after {@code YTDBGraphStepStrategy} folded the start step into a {@code YTDBGraphStep},
 * that subclass has no registry entry and the traversal declines cleanly.
 *
 * <h2>Single-vs-multi-ID handling</h2>
 *
 * <ul>
 *   <li>The single-ID case ({@code g.V(id)}) lands on {@code aliasRids} — the planner's optimised
 *       path that resolves to {@code SELECT FROM #X:Y}. The empty-ID case ({@code g.V()}) carries no
 *       RID hint and no filter, so the node resolves to a bare {@code SELECT FROM V} (the full
 *       polymorphic class scan).</li>
 *   <li>The multi-ID case ({@code g.V(id1, id2, …)}) lands on {@code aliasFilters} as {@code WHERE
 *       @rid IN [#X1:Y1, #X2:Y2, …]}. The planner's {@code createSelectStatement} falls through to
 *       {@code SELECT FROM Class WHERE …} when the alias's RID slot is empty, which honours
 *       arbitrary-arity IN constraints.</li>
 * </ul>
 *
 * <h2>Boundary alias</h2>
 *
 * The single matched node uses the alias {@value #BOUNDARY_ALIAS}. The {@code $g2m_} prefix is the
 * translator's private namespace — distinct from GQL's {@code $c} prefix and from {@code
 * MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX} so neither inline-named user aliases nor the planner's
 * internal auto-generation can collide.
 */
final class StartStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final StartStepRecogniser INSTANCE = new StartStepRecogniser();

  /**
   * Alias used by the Phase 1 single-node pattern. The {@code $g2m_} prefix is the Gremlin-to-MATCH
   * translator's reserved namespace; the {@code v0} suffix names this pattern's only vertex node.
   * Later tracks add more aliases under the same prefix.
   */
  private static final String BOUNDARY_ALIAS = "$g2m_v0";

  private StartStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    // Consume the head the walker dispatched by class. The start-step recogniser is meaningful only at
    // the traversal's head: a non-null boundary means a start step already pinned the boundary, so a
    // second start-shaped step declines. This state check replaces the old absolute-index check.
    var step = cursor.take();
    if (ctx.boundaryAlias() != null) {
      return Outcome.DECLINE;
    }
    // Gate on the plain TinkerPop GraphStep, not YTDBGraphStep: this walker runs before
    // YTDBGraphStepStrategy produces the YTDB subclass, so at translator time the start step is still
    // a plain GraphStep. See the class Javadoc "Gating on the plain TinkerPop GraphStep".
    if (!(step instanceof GraphStep<?, ?> graphStep)) {
      return Outcome.DECLINE;
    }
    // returnsVertex() distinguishes g.V() (accept) from g.E() (decline — edge starts are a later
    // milestone). This mirrors the strategy's own vertex-start gate.
    if (!graphStep.returnsVertex()) {
      return Outcome.DECLINE;
    }
    // Defence in depth against a start step that already carries folded predicates. A plain GraphStep
    // has no hasContainers of its own (folding produces YTDBGraphStep), but a HasContainerHolder start
    // step with non-empty containers must still decline rather than silently drop the predicate.
    if (step instanceof HasContainerHolder<?, ?> holder && !holder.getHasContainers().isEmpty()) {
      return Outcome.DECLINE;
    }

    // Normalise the start step's ids to distinct RIDs in one pass. A null return declines the whole
    // traversal (an unconvertible id, or a duplicate the set-semantics @rid IN filter cannot
    // reproduce). The empty-id g.V() case returns an empty list, not null.
    var rids = normaliseIds(graphStep.getIds());
    if (rids == null) {
      return Outcome.DECLINE;
    }

    // Contribute. A later DECLINE would discard the whole walk, so contribution order is free.
    ctx.addNode(BOUNDARY_ALIAS, WalkerContext.VERTEX_ROOT_CLASS);
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);

    // All ID sources flow through a WHERE @rid IN [...] filter; the empty-ID g.V() case carries no
    // filter. The planner's promoteStaticRidsFromFilters lifts the IN list into pinned RIDs — a size-1
    // IN collapses to a single pinned RID and the SELECT FROM #X:Y fast path. No @class narrowing is
    // applied: for a bare g.V() (and g.V(ids) with no folded label) the native pipeline returns the
    // full polymorphic set regardless of the polymorphic flag, so @class = 'V' would wrongly exclude
    // subclass instances.
    if (!rids.isEmpty()) {
      ctx.markRidBearing();
      ctx.putAliasFilter(BOUNDARY_ALIAS, wrapWhere(buildRidInExpression(rids)));
    }

    // The boundary step pulls the matched vertex out of each row by name, so the RETURN projection
    // keeps the alias as the row key: one column boundaryAlias AS boundaryAlias.
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return Outcome.ACCEPTED;
  }

  /**
   * Normalises the start step's heterogeneous {@code Object[] ids} into a list of distinct {@link
   * RecordIdInternal}, returning {@code null} as a "decline" sentinel when the shape cannot be
   * translated faithfully. Two conditions decline: an element that does not normalise (delegated to
   * {@link #toRecordIds}), or the same RID appearing more than once.
   *
   * <p>The duplicate decline is specific to {@code g.V(ids)} seek semantics. Native {@code g.V(ids)}
   * ({@code YTDBGraphImplAbstract.elements}) streams the id array one-to-one with no dedup, so a
   * repeated id emits its vertex once per occurrence; a translated {@code @rid IN [...]} filter has
   * set semantics and emits each vertex once. MATCH cannot express "emit the same vertex twice", so a
   * duplicated id is a shape the start step cannot match exactly — decline rather than return a
   * smaller multiset than the native pipeline. The single-id path (one native emission, one aliasRids
   * lookup) can never trip this. The set-membership {@code hasId(...)} branch does NOT inherit this
   * decline — it calls {@link #toRecordIds} directly, because {@code hasId(a, a)} is membership and
   * maps to the same {@code @rid IN [a]} filter.
   *
   * <p>Dedup keys on the RID value ({@link RidKey}: collection id + position), not the {@link
   * RecordIdInternal} instance: ids arrive from two paths — {@link RecordIdInternal#fromString} and
   * {@link Identifiable#getIdentity} — that can return different concrete subtypes for the same
   * logical rid, so an instance-hashCode set is not guaranteed to collide them. The same {@code @rid
   * IN} filter is emitted regardless of which subtype carried the value.
   */
  @Nullable private static List<RecordIdInternal> normaliseIds(Object[] ids) {
    var rids = toRecordIds(ids);
    if (rids == null) {
      return null;
    }
    var seen = new HashSet<RidKey>(rids.size());
    for (var rid : rids) {
      if (!seen.add(new RidKey(rid.getCollectionId(), rid.getCollectionPosition()))) {
        // Repeated id — the set-semantics @rid IN filter cannot reproduce the native pipeline's
        // one-emission-per-occurrence multiset. Decline.
        return null;
      }
    }
    return rids;
  }

  /**
   * Normalises a heterogeneous {@code Object[] ids} into a list of {@link RecordIdInternal} in one
   * pass, returning {@code null} as a decline sentinel when any element does not normalise. Shared
   * by the {@code g.V(ids)} start-step path (through {@link #normaliseIds}, which layers a
   * seek-semantics duplicate decline on top) and the set-membership {@code hasId(...)} branch of
   * {@code HasStepRecogniser}, which calls this directly with no duplicate decline.
   *
   * <p>{@link Identifiable} (Gremlin {@code Vertex}/{@code Edge}, YTDB record handles) and RID-shaped
   * {@link String}s ({@code "#X:Y"}) normalise; anything else (numbers, arbitrary objects) declines.
   * An empty or null input maps to an empty list — the {@code g.V()} no-ID case — which the caller
   * tells apart from a {@code null} decline.
   */
  @Nullable static List<RecordIdInternal> toRecordIds(Object[] ids) {
    if (ids == null || ids.length == 0) {
      return List.of();
    }
    var rids = new ArrayList<RecordIdInternal>(ids.length);
    for (var id : ids) {
      var rid = toRecordId(id);
      if (rid == null) {
        // Unconvertible id — decline so the traversal stays on the native pipeline, which knows how
        // to resolve every Gremlin id shape.
        return null;
      }
      rids.add(rid);
    }
    return rids;
  }

  /**
   * Best-effort conversion of one Gremlin ID into a {@link RecordIdInternal}. Mirrors the subset that
   * {@link SQLRid#toRecordId} accepts so the recogniser's plan-time decision matches the runtime
   * evaluator's semantics.
   *
   * <p>Empty and whitespace-only strings are explicitly declined: {@link RecordIdInternal#fromString}
   * silently treats them as {@code #-1:-1} (the "changeable RID" placeholder used for new records that
   * have not yet been persisted), which would translate {@code g.V("")} into a degenerate lookup whose
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
   * Builds {@code @rid IN [#X1:Y1, #X2:Y2, …]} as a hand-rolled {@link SQLInCondition}. Constructing
   * the AST manually (rather than reusing {@code MatchWhereBuilder.in}) is necessary because the left
   * side must be a {@link SQLRecordAttribute} — {@code @rid} is a record attribute, not a regular
   * property reference, and the runtime evaluator dispatches the two shapes through different code
   * paths in {@code SQLSuffixIdentifier.execute}.
   *
   * <p>Shared as-is with the {@code hasId(...)} branch of {@code HasStepRecogniser}: {@code hasId} is
   * set membership over RIDs, the same {@code @rid IN [...]} shape, and needs the same
   * record-attribute left side so {@code promoteStaticRidsFromFilters} can lift it to pinned RIDs.
   */
  static SQLBooleanExpression buildRidInExpression(List<RecordIdInternal> rids) {
    var ridAttr = new SQLRecordAttribute(-1);
    ridAttr.setName("@rid");
    var leftExpr = new SQLExpression(ridAttr, null);

    var values = new ArrayList<SQLExpression>(rids.size());
    for (var rid : rids) {
      // Each RID becomes an SQLExpression-wrapped SQLRid via the literal builder.
      values.add(MatchLiteralBuilder.toLiteral(rid));
    }
    // Wrap the element list in the parser's literal-list AST chain (collection → level-zero → base
    // identifier → base expression) so SQLInCondition.evaluateRight can reach the elements. Shared
    // with MatchWhereBuilder.in via literalCollectionExpression to keep a single copy.
    var rightBase = MatchWhereBuilder.literalCollectionExpression(values);

    return buildRidInCondition(leftExpr, rightBase);
  }

  /**
   * Assembles the {@link SQLInCondition} for {@code @rid IN [...]} from the pre-built left ({@code
   * @rid} attribute) and right (literal-list) expressions, populating the {@code IN} operator so
   * {@code toString} and the plan-time {@code supportsBasicCalculation} path see a well-formed
   * condition. Named with a verb prefix because it constructs a fresh node each call (it is a factory,
   * not an accessor).
   */
  private static @Nonnull SQLInCondition buildRidInCondition(
      SQLExpression leftExpr, SQLBaseExpression rightBase) {
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

  /** Dedup key for {@link #normaliseIds}: the RID value (collection id + position), not the {@link
   *  RecordIdInternal} instance. */
  private record RidKey(int collectionId, long position) {
  }

  private static SQLWhereClause wrapWhere(SQLBooleanExpression expr) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(expr);
    return clause;
  }
}
