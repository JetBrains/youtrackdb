package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;

/**
 * Recogniser for the single {@link HasStep} that {@code has(...)} / {@code hasLabel(...)} / {@code
 * hasId(...)} all produce at translator time. The g2m translator runs before {@code
 * YTDBGraphStepStrategy}, and the plain {@code GraphStep} is not a {@code HasContainerHolder}, so no
 * fold has happened yet: {@code hasLabel} is never on the start step and there is no {@code
 * YTDBHasLabelStep} instance. Every one of the three DSL forms arrives here as a {@link HasStep}
 * distinguished only by its {@link HasContainer} keys, and consecutive {@code has}-family calls fold
 * into one {@code HasStep} (each is a {@code HasContainerHolder}), so a single step can carry a mix of
 * property, {@code ~label}, and {@code ~id} containers.
 *
 * <h2>Container-key branching</h2>
 *
 * <ul>
 *   <li>a {@code ~label} container ({@code T.label} accessor) narrows by <em>re-typing the boundary
 *       node's class</em> to {@code L} so the scan narrows to {@code SELECT FROM L} rather than a full
 *       {@code V} scan that rejects rows in a {@code WHERE}. Non-polymorphic mode re-types and adds an
 *       exact {@code @class = 'L'} filter (leaf-exact, mirroring native non-polymorphic {@code
 *       hasLabel}); polymorphic mode re-types alone (a {@code SELECT FROM L} scan matches subclasses,
 *       mirroring native hierarchy-aware {@code hasLabel} — see {@code YTDBLabelMatcher}). Handled
 *       only for a single {@code eq(L)} container: a multi-label {@code hasLabel(L1, L2)} arrives as
 *       one {@code within(...)} container and declines, and two conflicting {@code ~label} containers
 *       decline (one MATCH node has one class);
 *   <li>a {@code ~id} container ({@code T.id} accessor) contributes an {@code @rid IN [...]} filter
 *       via the record-attribute builder shared with {@link StartStepRecogniser}. {@code hasId} is set
 *       membership, so a repeated id ({@code hasId(a, a)}) does <em>not</em> decline (unlike {@code
 *       g.V(ids)} seek semantics) — it calls {@link StartStepRecogniser#toRecordIds} without the
 *       duplicate decline;
 *   <li>a property key routes through {@link GremlinPredicateAdapter#toFilter(HasContainer,
 *       PropertyTypeGate)}. The {@link GremlinPredicateAdapter.PropertyTypeGate} keys only
 *       {@code startingWith} routing on the step's {@code ~label} class (if any): declared {@code
 *       STRING} uses the index-aware prefix range, every other case uses the strict full-scan node.
 *       All other {@code Text} / {@code TextP} predicates translate in strict mode and throw at
 *       execution on a present non-{@code String} operand, matching native rather than declining.
 * </ul>
 *
 * <h2>Translate-all-then-contribute</h2>
 *
 * The recogniser validates and translates <em>every</em> container before it mutates the context: an
 * untranslatable container (a reserved key, a multi-label {@code ~label}, an unconvertible id, a
 * size-1 collection equality) declines with zero {@code WalkerContext} mutation.
 * The accumulated filters go in through one {@link RecognitionContext#putAliasFilter} on the boundary
 * alias, which AND-composes with any filter an earlier step contributed to the same alias (a {@code
 * g.V(ids)} {@code @rid IN}, or an earlier {@code has}).
 */
final class HasStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final HasStepRecogniser INSTANCE = new HasStepRecogniser();

  /** Stateless builder for the class-narrowing and AND-merge AST; construction is trivial. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  /** TinkerPop hidden key {@code ~label} that {@code hasLabel} / {@code has(label, ...)} produce. */
  private static final String LABEL_KEY = T.label.getAccessor();

  /** TinkerPop hidden key {@code ~id} that {@code hasId} produces. */
  private static final String ID_KEY = T.id.getAccessor();

  private HasStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    // Take the head the walker dispatched by class. Defence in depth: re-assert a HasStep so a direct
    // mis-call declines cleanly rather than throwing.
    var step = cursor.take();
    if (!(step instanceof HasStep<?> hasStep)) {
      return Outcome.DECLINE;
    }
    // A has() with no boundary to filter cannot be translated: it must follow a pinned node. A null
    // boundary means a HasStep reached the walker before any node was pinned — decline.
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var containers = hasStep.getHasContainers();
    if (containers.isEmpty()) {
      // A HasStep with no containers is degenerate — decline rather than contribute nothing and
      // wrongly claim the step.
      return Outcome.DECLINE;
    }

    // First pass: resolve a single-eq ~label class. It drives both the boundary-node re-typing and
    // the startsWith-only type gate for property containers in this same step.
    String labelClass = null;
    for (var container : containers) {
      if (!LABEL_KEY.equals(container.getKey())) {
        continue;
      }
      var name = singleEqLabel(container);
      if (name == null) {
        // Multi-label within(...), a non-eq label predicate, or a non-String label value — none of
        // which a single-class MATCH node can express. Decline the whole traversal.
        return Outcome.DECLINE;
      }
      if (labelClass != null && !labelClass.equals(name)) {
        // Two conflicting ~label containers (hasLabel("A").hasLabel("B")): one MATCH node has one
        // class. Decline to native rather than pick one.
        return Outcome.DECLINE;
      }
      labelClass = name;
    }

    // The re-type target must be a real vertex class: a typo'd / never-used label or an edge class
    // would make SELECT FROM L error or scan the wrong element type, while native hasLabel just
    // matches no vertex and returns empty. Decline so the native pipeline produces that empty result.
    if (labelClass != null && !ctx.isVertexClass(labelClass)) {
      return Outcome.DECLINE;
    }

    // The class context for the startsWith-form type gate is the step's own ~label (if any); a
    // property has() on a generic V boundary has no known leaf class, so its keys resolve as
    // not-a-declared-String and a startingWith there routes to the strict full-scan form.
    var typeClass = labelClass;
    GremlinPredicateAdapter.PropertyTypeGate typeGate =
        key -> ctx.isDeclaredStringProperty(typeClass, key);

    // Second pass: translate every id / property container into a WHERE expression BEFORE any
    // contribution (so an untranslatable container declines with zero context mutation).
    var whereExprs = new ArrayList<SQLBooleanExpression>();
    for (var container : containers) {
      var key = container.getKey();
      if (LABEL_KEY.equals(key)) {
        continue; // handled by the re-typing contribution below
      }
      if (ID_KEY.equals(key)) {
        ctx.markRidBearing();
        var ridExpr = translateHasId(container);
        if (ridExpr == null) {
          return Outcome.DECLINE;
        }
        whereExprs.add(ridExpr);
        continue;
      }
      var filter = GremlinPredicateAdapter.INSTANCE.toFilter(container, typeGate, ctx);
      if (filter == null) {
        return Outcome.DECLINE;
      }
      whereExprs.add(filter);
    }

    // Contribution — reached only after every container validated.
    if (labelClass != null) {
      // Re-type the boundary node's class so the scan narrows to L (addNode overwrites the prior
      // class). Non-polymorphic adds an exact @class = 'L' so the SELECT FROM L polymorphic scan is
      // filtered to the leaf class; polymorphic leaves it at SELECT FROM L (matches subclasses).
      ctx.addNode(boundary, labelClass);
      if (!ctx.polymorphic()) {
        whereExprs.add(WHERE.classEquals(labelClass));
      }
    }
    if (!whereExprs.isEmpty()) {
      var merged = WHERE.and(whereExprs.toArray(new SQLBooleanExpression[0]));
      ctx.putAliasFilter(boundary, WHERE.wrap(merged));
    }
    return Outcome.ACCEPTED;
  }

  /**
   * Extracts a single {@code eq(L)} label name from a {@code ~label} container, or {@code null} to
   * decline. Only a {@link Compare#eq} predicate over a non-blank {@link String} qualifies: a
   * multi-label {@code hasLabel(L1, L2)} arrives as {@link Contains#within} and any non-eq label
   * predicate ({@code hasLabel(P.neq(...))}) is out of scope.
   */
  private static @Nullable String singleEqLabel(HasContainer container) {
    var predicate = container.getPredicate();
    if (predicate == null) {
      return null;
    }
    if (!(predicate.getBiPredicate() instanceof Compare compare) || compare != Compare.eq) {
      return null;
    }
    return predicate.getValue() instanceof String label && !label.isBlank() ? label : null;
  }

  /**
   * Translates a {@code ~id} container ({@code hasId}) into an {@code @rid IN [...]} expression, or
   * {@code null} to decline. {@code hasId(id)} arrives as {@link Compare#eq} over one id, {@code
   * hasId(a, b, …)} as {@link Contains#within} over a collection; any other shape (a range predicate
   * such as {@code hasId(P.gt(x))}) cannot build a membership filter and declines. Ids normalise
   * through {@link StartStepRecogniser#toRecordIds} with no duplicate decline — {@code hasId} is set
   * membership, so {@code hasId(a, a)} maps to the same {@code @rid IN [a]} filter.
   */
  private static @Nullable SQLBooleanExpression translateHasId(HasContainer container) {
    var predicate = container.getPredicate();
    if (predicate == null) {
      return null;
    }
    var biPredicate = predicate.getBiPredicate();
    Object[] rawIds;
    if (biPredicate instanceof Compare compare && compare == Compare.eq) {
      rawIds = new Object[] {predicate.getValue()};
    } else if (biPredicate instanceof Contains contains && contains == Contains.within) {
      if (!(predicate.getValue() instanceof Collection<?> values)) {
        return null;
      }
      rawIds = values.toArray();
    } else {
      return null;
    }
    var rids = StartStepRecogniser.toRecordIds(rawIds);
    // toRecordIds returns null on an unconvertible id and an empty list when there are no ids. An
    // empty @rid IN would match nothing and is degenerate — decline rather than emit it.
    if (rids == null || rids.isEmpty()) {
      return null;
    }
    return StartStepRecogniser.buildRidInExpression(rids);
  }
}
