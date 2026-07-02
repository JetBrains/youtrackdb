package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLevelZeroIdentifier;
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
 * Gating on {@code GraphStep} is also ordering-robust: a {@code YTDBGraphStep} is-a
 * {@code GraphStep}, so the recogniser keeps working even if the ordering ever changes.
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
 *   <li>The empty-ID case ({@code g.V()}) and the single-ID case ({@code g.V(id)}) land
 *       on {@code aliasRids} — the planner's optimised path that resolves to
 *       {@code SELECT FROM #X:Y}.</li>
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
public final class StartStepRecogniser implements StepRecogniser {

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
   * Same convention {@code GqlMatchStatement.effectiveType} uses when no explicit label
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

    var rids = normaliseIds(graphStep.getIds());
    if (rids == null) {
      // At least one ID could not be converted — decline cleanly so the traversal
      // stays on the native pipeline that knows how to resolve every Gremlin ID
      // shape.
      return false;
    }

    // Native g.V(ids) (YTDBGraphImplAbstract.elements) streams the id array one-to-one
    // with no dedup, so a repeated id emits the vertex once per occurrence. A translated
    // @rid IN [...] filter has set semantics: MATCH scans the class once and emits each
    // matching vertex once, regardless of how many times the id appears. MATCH cannot
    // express "emit the same vertex twice" through an IN clause, so a duplicated id is a
    // shape this recogniser cannot match exactly — decline it to native rather than
    // return a smaller multiset than the native pipeline. The single-id path is exact
    // (one native emission, one aliasRids lookup) and is unaffected.
    if (hasDuplicate(rids)) {
      return false;
    }

    // Polymorphism resolution intentionally lives here, not in the walker pre-check:
    // YTDBStrategyUtil.isPolymorphic calls graph.tx() unconditionally, which throws
    // UnsupportedOperationException on graphs that do not support transactions
    // (anonymous traversals attached to TinkerPop's EmptyGraph, non-YTDB graphs).
    // Doing it here means the structural gates above filter those traversals out
    // before we ever reach the unsafe call. A null result (no attached graph) declines
    // the whole traversal cleanly.
    Boolean polymorphic = YTDBStrategyUtil.isPolymorphic(ctx.traversal);
    if (polymorphic == null) {
      return false;
    }

    // Validation done; commit mutations to the context.
    ctx.patternBuilder.addNode(BOUNDARY_ALIAS, DEFAULT_VERTEX_CLASS, null, false);
    ctx.boundaryAlias = BOUNDARY_ALIAS;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;

    // aliasRids carries the single-ID path; multi-ID constraints flow through aliasFilters.
    if (rids.size() == 1) {
      ctx.aliasRids.put(BOUNDARY_ALIAS, toSqlRid(rids.getFirst()));
    }

    // Multi-ID sources need a WHERE @rid IN [...] filter; the single/no-ID paths carry no
    // filter here. No @class narrowing is applied: for a bare g.V() (and g.V(ids) with no
    // folded label) the native pipeline returns the full polymorphic set regardless of the
    // polymorphic flag — the no-id branch always browses the class polymorphically, and the
    // by-id branch applies no class filter (YTDBGraphImplAbstract.elements /
    // YTDBGraphStep.elements). Emitting @class = 'V' would wrongly exclude subclass instances.
    if (rids.size() > 1) {
      ctx.aliasFilters.put(BOUNDARY_ALIAS, wrapWhere(buildRidInExpression(rids)));
    }

    // The boundary step pulls the matched vertex out of each result row by name, so the
    // RETURN projection must keep the alias as the row key. A single SQLProjectionItem
    // expressing `boundaryAlias AS boundaryAlias` survives the planner's projection
    // pipeline with the alias-keyed shape intact.
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(BOUNDARY_ALIAS)));
    ctx.returnAliases.add(new SQLIdentifier(BOUNDARY_ALIAS));
    ctx.returnNestedProjections.add(null);

    return true;
  }

  /**
   * Converts the start step's heterogeneous {@code Object[] ids} into a list of
   * {@link RecordIdInternal}, returning {@code null} as a "decline" sentinel if any
   * element cannot be normalised. {@link Identifiable} (Gremlin {@code Vertex}/
   * {@code Edge}, YTDB record handles) and RID-shaped {@link String}s ({@code "#X:Y"})
   * are accepted; anything else (numbers, arbitrary objects) signals decline.
   *
   * <p>An empty or null input array maps to an empty list, which represents the
   * {@code g.V()} no-ID case — the caller distinguishes "no IDs" from "unconvertible
   * IDs" by the {@code null} return.
   */
  @Nullable private static List<RecordIdInternal> normaliseIds(Object[] ids) {
    if (ids == null || ids.length == 0) {
      return List.of();
    }
    var rids = new ArrayList<RecordIdInternal>(ids.length);
    for (var id : ids) {
      var rid = toRecordId(id);
      if (rid == null) {
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
      case Identifiable identifiable -> (RecordIdInternal) identifiable.getIdentity();
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
   * Translates one {@link RecordIdInternal} into the parser's {@link SQLRid} shape. Sets
   * {@code legacy=true} so the AST renders as {@code #X:Y} (the canonical literal form)
   * rather than the {@code \{"@rid": expression\}} fallback that {@link SQLRid#toString}
   * produces when an expression is attached.
   */
  private static SQLRid toSqlRid(RecordIdInternal rid) {
    return createLegacySqlRid(rid);
  }

  /**
   * Factory method to construct and initialize a basic {@link SQLRid} node in legacy mode
   * from a raw storage-level {@link RecordIdInternal}.
   */
  public static SQLRid createLegacySqlRid(RecordIdInternal rid) {
    java.util.Objects.requireNonNull(rid);

    var sqlRid = new SQLRid(-1);
    var collection = new SQLInteger(-1);
    collection.setValue(rid.getCollectionId());

    var position = new SQLInteger(-1);
    position.setValue(rid.getCollectionPosition());

    sqlRid.setCollection(collection);
    sqlRid.setPosition(position);
    sqlRid.setLegacy(true);

    return sqlRid;
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

    var collection = new SQLCollection(-1);
    for (var rid : rids) {
      // Each RID becomes an SQLExpression-wrapped SQLRid via the literal builder.
      collection.add(MatchLiteralBuilder.toLiteral(rid));
    }
    // The right side mirrors the parser's representation of a literal list: collection
    // wrapped in level-zero identifier wrapped in a base identifier wrapped in a base
    // expression. Without this wrapping, SQLInCondition.evaluateRight cannot reach the
    // collection's element expressions.
    var levelZero = new SQLLevelZeroIdentifier(-1);
    levelZero.setCollection(collection);
    var ident = new SQLBaseIdentifier(-1);
    ident.setLevelZero(levelZero);
    var rightBase = new SQLBaseExpression(-1);
    rightBase.setIdentifier(ident);

    return getSqlInCondition(leftExpr, rightBase);
  }

  private static @NonNull SQLInCondition getSqlInCondition(SQLExpression leftExpr,
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

  /**
   * Returns {@code true} if {@code rids} contains the same {@link RecordIdInternal} more
   * than once. Used to decline a {@code g.V(ids)} source with a repeated id: an
   * {@code @rid IN [...]} filter has set semantics and cannot reproduce the native
   * pipeline's one-emission-per-occurrence multiset.
   */
  private static boolean hasDuplicate(List<RecordIdInternal> rids) {
    // Dedup on the value key (collection id + position) rather than the RecordIdInternal
    // instance: the ids reach this list from two paths — RecordIdInternal.fromString and
    // Identifiable.getIdentity — that can return different concrete permitted subtypes for the
    // same logical rid, so an instance-hashCode-based set is not guaranteed to collide them.
    // The same @rid IN filter is emitted regardless of which subtype carried the value, so the
    // value key is the right identity here.
    var seen = new HashSet<RidKey>(rids.size());
    for (var rid : rids) {
      if (!seen.add(new RidKey(rid.getCollectionId(), rid.getCollectionPosition()))) {
        return true;
      }
    }
    return false;
  }

  private record RidKey(int collectionId, long position) {
  }

  private static SQLWhereClause wrapWhere(SQLBooleanExpression expr) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(expr);
    return clause;
  }
}
