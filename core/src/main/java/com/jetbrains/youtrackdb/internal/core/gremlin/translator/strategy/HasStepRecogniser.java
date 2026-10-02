package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
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
 *       one {@code within(...)} container. When the boundary is still generic {@code V}, the
 *       recogniser re-types to the labels' least common vertex ancestor (when one exists below
 *       {@code V}) and adds {@code @class IN [L1, L2, …]} (leaf-exact under non-polymorphic mode;
 *       subclass-expanded under polymorphic mode). Disjoint trees under {@code V} keep the {@code V}
 *       root and the IN filter alone. Two conflicting {@code ~label} containers decline (one MATCH
 *       node has one class);
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
 * <h2>User {@code as(...)} labels land here more often than they are written here</h2>
 *
 * TinkerPop's {@code FilterRankingStrategy} moves a user label forward off the step it was written on
 * and onto the following filter, on the grounds that a filter does not transform the traverser. It is
 * an {@code OptimizationStrategy}, so it has already run when the translator walks the list: {@code
 * g.V().as("a").has("name", "Alice")} reaches this recogniser as {@code GraphStep -> HasStep[a]}.
 * The step therefore calls {@link RecognitionContext#bindStepLabels} on the boundary alias — the same
 * element the label named before the move — and <b>declines when the label is already bound to a
 * different alias</b>. That decline is not defensive tidiness: {@code
 * g.V().as("a").out(L).has(k, v).as("a").select("a")} binds {@code a} to the origin at the start step
 * and to the hop target here, and before the bind existed the second {@code as("a")} was dropped, so
 * {@code select("a")} resolved to the origin and the translated arm answered the origin where native
 * answers the hop target ({@code Pop.last}).
 *
 * <h2>Translate-all-then-contribute</h2>
 *
 * The recogniser validates and translates <em>every</em> container before it mutates the context: an
 * untranslatable container (a reserved key, a conflicting {@code ~label}, an unconvertible id)
 * declines with zero {@code WalkerContext} mutation. The label bind opens
 * the contribution block for the same reason — a colliding label declines before the re-type and the
 * filter land, and {@code bindStepLabels} itself checks every label before it writes any of them.
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
    // Deferred hop after order(): stash has(...) containers on the pending hop so a following
    // slice filters neighbours with native label/collation semantics before the cut.
    if (ctx.pendingOrderedHop() != null) {
      return recognizePendingOrderedHopHas(hasStep, ctx);
    }
    var containers = hasStep.getHasContainers();
    if (containers.isEmpty()) {
      // A HasStep with no containers is degenerate — decline rather than contribute nothing and
      // wrongly claim the step.
      return Outcome.DECLINE;
    }

    // First pass: resolve ~label containers — a single eq(L), a multi-label within(...), or a
    // conflicting pair of eq containers. Drives re-typing / @class filters and the startsWith gate.
    ParsedLabelConstraint labelConstraint = null;
    for (var container : containers) {
      if (!LABEL_KEY.equals(container.getKey())) {
        continue;
      }
      var parsed = parseLabelContainer(container);
      if (parsed == null) {
        return Outcome.DECLINE;
      }
      if (labelConstraint == null) {
        labelConstraint = parsed;
      } else if (labelConstraint.conflictsWith(parsed)) {
        return Outcome.DECLINE;
      }
    }

    // A hasLabel on a class that does not exist in the schema at translation time declines to
    // native. Native resolves the class at execution time, so if the class is created later in the
    // same open transaction (DDL-in-tx — e.g. g.addV("Person") in one project() by() and
    // g.V().hasLabel("Person").count() in a sibling), native sees the new rows. A translated plan is
    // compiled now, against a schema without the class, and bakes an empty/stale source — so it
    // would return 0 rows where native returns the freshly-created ones (translator-ON != OFF; see
    // GraphApiTest.testComputeInTxAndCommit*). Declining preserves on==off: a class that never
    // exists yields empty on native too, which is exactly what a decline-to-native produces.
    if (labelConstraint instanceof ParsedLabelConstraint.Single single
        && !ctx.isVertexClass(single.name())) {
      return Outcome.DECLINE;
    }
    if (labelConstraint instanceof ParsedLabelConstraint.Multi multi) {
      for (var name : multi.names()) {
        if (!ctx.isVertexClass(name)) {
          return Outcome.DECLINE;
        }
      }
    }

    // A label condition on an edge-bound position must decline, for example after select() of an
    // edge alias bound by outE().as(...). Otherwise the translator could assign a vertex class to a
    // MATCH node that holds edges.
    if (labelConstraint != null && ctx.isEdgeAlias(boundary)) {
      return Outcome.DECLINE;
    }
    // A single label replaces the class of the current MATCH node. Only the same class or a vertex
    // subclass of the current class keeps every earlier label condition. Any other class declines
    // so the native planner runs the query.
    if (labelConstraint instanceof ParsedLabelConstraint.Single single
        && !ctx.canNarrowClass(ctx.boundaryClassName(), single.name())) {
      return Outcome.DECLINE;
    }

    // Type gate: single ~label uses that class; multi-label requires the property type on every
    // named class (allMatch — a schemaless sibling must not drop type guards). No ~label → unknown.
    GremlinPredicateAdapter.PropertyTypeGate typeGate;
    if (labelConstraint instanceof ParsedLabelConstraint.Single single) {
      typeGate = GremlinPredicateAdapter.schemaGate(ctx, single.name());
    } else if (labelConstraint instanceof ParsedLabelConstraint.Multi multi) {
      typeGate = GremlinPredicateAdapter.schemaGate(ctx, multi.names().toArray(String[]::new));
      // No ~label on this step: still gate against the current boundary class when it is a concrete
      // vertex class (after hasLabel / a typed hop). Generic V stays unknown so schemaless list cells
      // do not get the always-false singleton rewrite.
    } else {
      var boundaryClass = ctx.boundaryClassName();
      typeGate = GremlinPredicateAdapter.schemaGate(
          ctx,
          WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass) ? null : boundaryClass);
    }
    ParamSink paramSink = ctx::bindParam;
    // A range comparison needs the per-record type guard exactly when this HasStep will NOT be
    // folded into YTDBGraphStep — folded, the native fallback runs the same SQL-style comparison the
    // translation emits; unfolded, it runs TinkerPop's comparability rule instead. See
    // RecognitionContext.atTraversalStart(). The adapter still drops the guard when the schema
    // declares the property in the literal's comparability block (schemaGate).
    var rangeTypeGuard = !ctx.atTraversalStart();

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
      var filter =
          GremlinPredicateAdapter.INSTANCE.toFilter(container, typeGate, paramSink, rangeTypeGuard);
      if (filter == null) {
        return Outcome.DECLINE;
      }
      whereExprs.add(filter);
    }

    // Contribution — reached only after every container validated.
    // Bind first, so a colliding label declines before anything is contributed. A has() step is a
    // routine parking spot for a user as(...) label: FilterRankingStrategy relocates labels forward
    // onto the following filter, and it runs before every provider strategy, so a label the user
    // wrote on the start step arrives here instead. Binding it to the boundary alias is exact rather
    // than approximate — a filter does not transform the traverser, so the labelled element is the
    // boundary node either side of the move.
    if (!ctx.bindStepLabels(hasStep, boundary)) {
      return Outcome.DECLINE;
    }
    if (labelConstraint instanceof ParsedLabelConstraint.Single single) {
      // The class is known to exist here — a missing class declined above, before any mutation.
      var name = single.name();
      ctx.addNode(boundary, name);
      if (ctx.polymorphic() && ctx instanceof SubTraversalPredicateAdapter adapter) {
        // The scan class is not a boolean label test. Keep the named label for the OR/NOT fold.
        adapter.capturePolymorphicLabel(boundary, name);
      }
      if (!ctx.polymorphic()) {
        whereExprs.add(WHERE.classEquals(name));
      }
    } else if (labelConstraint instanceof ParsedLabelConstraint.Multi multi) {
      // Positive commits retain the scan class and intersect explicit label filters.
      // Captured refinements remain outside the supported boolean fold.
      if (ctx instanceof SubTraversalPredicateAdapter adapter
          && adapter.concreteCapturedClassForAlias(boundary) != null) {
        adapter.markCapturedClassChange();
      }
      var classNames =
          ctx.polymorphic()
              ? ctx.expandPolymorphicClassClosure(multi.names())
              : multi.names();
      // MATCH nodes carry one class. While the boundary is still V, re-type to the least common
      // vertex ancestor of the labels so the scan starts on that class collection; @class IN still
      // restricts to the named (or polymorphically expanded) set. Disjoint trees under V keep the
      // V root and rely on the IN filter alone — still translates, no decline.
      if (WalkerContext.VERTEX_ROOT_CLASS.equals(ctx.boundaryClassName())) {
        var lca = ctx.leastCommonVertexAncestor(multi.names());
        if (lca != null && !WalkerContext.VERTEX_ROOT_CLASS.equals(lca)) {
          ctx.addNode(boundary, lca);
        }
      }
      whereExprs.add(WHERE.classIn(classNames));
    }
    if (!whereExprs.isEmpty()) {
      var merged = WHERE.and(whereExprs.toArray(new SQLBooleanExpression[0]));
      ctx.putAliasFilter(boundary, WHERE.wrap(merged));
    }
    return Outcome.ACCEPTED;
  }

  /**
   * Writes deferred neighbour {@code has} containers onto {@code alias} as MATCH filters (and
   * optional class re-type for {@code hasLabel}). Each native HasStep contributes its own class
   * condition: labels within one step OR, separate steps AND.
   */
  static boolean contributeContainersToAlias(
      RecognitionContext ctx, String alias, List<HasContainer> containers) {
    return contributeContainersToAlias(ctx, alias, containers,
        containers.isEmpty() ? List.of() : List.of(containers.size()));
  }

  static boolean contributeContainersToAlias(
      RecognitionContext ctx, String alias, List<HasContainer> containers,
      List<Integer> stepSizes) {
    int offset = 0;
    for (int size : stepSizes) {
      if (!contributeOneHasStep(ctx, alias, containers.subList(offset, offset + size),
          stepSizes.size() == 1)) {
        return false;
      }
      offset += size;
    }
    return offset == containers.size();
  }

  private static boolean contributeOneHasStep(
      RecognitionContext ctx, String alias, List<HasContainer> containers, boolean mayRetype) {
    // YTDBHasLabelStep ORs all label predicates of one HasStep, including mixed eq/within.
    var names = new ArrayList<String>();
    for (var container : containers) {
      if (LABEL_KEY.equals(container.getKey())) {
        var parsed = parseLabelContainer(container);
        if (parsed instanceof ParsedLabelConstraint.Single single) {
          names.add(single.name());
        } else if (parsed instanceof ParsedLabelConstraint.Multi multi) {
          names.addAll(multi.names());
        } else {
          return false;
        }
      }
    }
    GremlinPredicateAdapter.PropertyTypeGate typeGate;
    if (names.size() == 1) {
      typeGate = GremlinPredicateAdapter.schemaGate(ctx, names.getFirst());
    } else if (!names.isEmpty()) {
      typeGate = GremlinPredicateAdapter.schemaGate(ctx, names.toArray(String[]::new));
    } else {
      typeGate = GremlinPredicateAdapter.schemaGate(ctx, (String) null);
    }
    ParamSink paramSink = ctx::bindParam;
    var rangeTypeGuard = !ctx.atTraversalStart();
    var whereExprs = new ArrayList<SQLBooleanExpression>();
    for (var container : containers) {
      var key = container.getKey();
      if (LABEL_KEY.equals(key)) {
        continue;
      }
      if (ID_KEY.equals(key)) {
        ctx.markRidBearing();
        var ridExpr = translateHasId(container);
        if (ridExpr == null) {
          return false;
        }
        whereExprs.add(ridExpr);
        continue;
      }
      var filter =
          GremlinPredicateAdapter.INSTANCE.toFilter(container, typeGate, paramSink, rangeTypeGuard);
      if (filter == null) {
        return false;
      }
      whereExprs.add(filter);
    }
    if (!names.isEmpty()) {
      // Only narrow a single label group with a verified class. Missing alternatives remain in
      // the class predicate but never become a MATCH source, even when every name is absent.
      if (mayRetype && names.stream().allMatch(ctx::isVertexClass)) {
        if (names.size() == 1) {
          ctx.addNode(alias, names.getFirst());
        } else if (WalkerContext.VERTEX_ROOT_CLASS.equals(ctx.boundaryClassName())) {
          var lca = ctx.leastCommonVertexAncestor(names);
          if (lca != null && !WalkerContext.VERTEX_ROOT_CLASS.equals(lca)) {
            ctx.addNode(alias, lca);
          }
        }
      }
      whereExprs.add(WHERE.classIn(ctx.polymorphic()
          ? ctx.expandPolymorphicClassClosure(names) : names));
    }
    if (!whereExprs.isEmpty()) {
      var merged = WHERE.and(whereExprs.toArray(new SQLBooleanExpression[0]));
      ctx.putAliasFilter(alias, WHERE.wrap(merged));
    }
    return true;
  }

  /**
   * Validates neighbour {@code has(...)} for ordered-expand list-shaping (native label and
   * collation-aware evaluation at expand time). Returns the containers, or {@code null} to decline —
   * traversal-bearing predicates, bad keys, and malformed label predicates.
   */
  static @Nullable List<HasContainer> collectDeferredHasContainers(
      HasStep<?> hasStep, RecognitionContext ctx) {
    var containers = hasStep.getHasContainers();
    if (containers.isEmpty()) {
      return null;
    }
    for (var container : containers) {
      if (embedsTraversal(container.getPredicate())) {
        return null;
      }
      var key = container.getKey();
      if (key == null || key.isBlank()) {
        return null;
      }
      // Allow ~label / ~id; decline other reserved namespaces ($ / @).
      if (WalkerContext.isReservedHasKey(key)
          && !LABEL_KEY.equals(key)
          && !ID_KEY.equals(key)) {
        return null;
      }
      if (LABEL_KEY.equals(key) && parseLabelContainer(container) == null) {
        return null;
      }
    }
    for (var container : containers) {
      if (!LABEL_KEY.equals(container.getKey())) {
        // Deferred IDs and property literals are not rebound inside cached shaping ops.
        // Until op parameters are supported, keep each invocation's filter in its own plan.
        ctx.markRidBearing();
      }
    }
    return List.copyOf(containers);
  }

  /**
   * Stashes neighbour {@code has(...)} containers onto a deferred ordered hop. Every container shape
   * already handled on the MATCH path is accepted and later evaluated with native
   * neighbour filters; traversal-bearing {@code has} declines.
   */
  private static Outcome recognizePendingOrderedHopHas(
      HasStep<?> hasStep, RecognitionContext ctx) {
    var pending = ctx.pendingOrderedHop();
    if (pending == null) {
      return Outcome.DECLINE;
    }
    var containers = collectDeferredHasContainers(hasStep, ctx);
    if (containers == null) {
      return Outcome.DECLINE;
    }
    if (!ctx.bindStepLabels(hasStep, pending.targetAlias())) {
      return Outcome.DECLINE;
    }
    ctx.setPendingOrderedHop(pending.appendHasStep(containers));
    return Outcome.ACCEPTED;
  }

  /**
   * Whether a {@code has} predicate embeds a sub-traversal ({@code has(key, traversal)}). Connectives
   * are walked so {@code P.and(eq(x), traversalP)} still declines.
   */
  private static boolean embedsTraversal(@Nullable P<?> predicate) {
    if (predicate == null) {
      return false;
    }
    if (predicate.getValue() instanceof Traversal) {
      return true;
    }
    if (predicate instanceof NotP<?> notP) {
      return embedsTraversal(notP.negate());
    }
    if (predicate instanceof AndP<?> andP) {
      for (var child : andP.getPredicates()) {
        if (embedsTraversal(child)) {
          return true;
        }
      }
      return false;
    }
    if (predicate instanceof OrP<?> orP) {
      for (var child : orP.getPredicates()) {
        if (embedsTraversal(child)) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  /**
   * Parsed {@code ~label} constraint from one {@link HasContainer}: either one {@code eq(L)} name or
   * a multi-label {@code within(...)} list.
   */
  private sealed interface ParsedLabelConstraint {
    record Single(String name) implements ParsedLabelConstraint {
      @Override
      public boolean conflictsWith(ParsedLabelConstraint other) {
        return !(other instanceof Single s) || !name.equals(s.name);
      }
    }

    record Multi(java.util.List<String> names) implements ParsedLabelConstraint {
      @Override
      public boolean conflictsWith(ParsedLabelConstraint other) {
        return other instanceof Single || other instanceof Multi;
      }
    }

    boolean conflictsWith(ParsedLabelConstraint other);
  }

  /**
   * Extracts a label constraint from a {@code ~label} container, or {@code null} to decline.
   */
  private static @Nullable ParsedLabelConstraint parseLabelContainer(HasContainer container) {
    var predicate = container.getPredicate();
    if (predicate == null) {
      return null;
    }
    if (predicate.getBiPredicate() instanceof Compare compare && compare == Compare.eq) {
      if (predicate.getValue() instanceof String label && !label.isBlank()) {
        return new ParsedLabelConstraint.Single(label);
      }
      return null;
    }
    if (predicate.getBiPredicate() instanceof Contains contains && contains == Contains.within) {
      if (!(predicate.getValue() instanceof Collection<?> values)) {
        return null;
      }
      var names = new ArrayList<String>();
      for (var value : values) {
        if (!(value instanceof String label) || label.isBlank()) {
          return null;
        }
        names.add(label);
      }
      if (names.isEmpty()) {
        return null;
      }
      return new ParsedLabelConstraint.Multi(names);
    }
    return null;
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

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof HasStep<?> hasStep)) {
      return false;
    }
    var containers = hasStep.getHasContainers();
    encoder.appendToken("H", Integer.toString(containers.size()));
    String typeClass = null;
    for (HasContainer container : containers) {
      if (LABEL_KEY.equals(container.getKey()) && container.getValue() instanceof String name) {
        typeClass = name;
      }
    }
    final var labelClass = typeClass;
    GremlinPredicateAdapter.PropertyTypeGate typeGate =
        new GremlinPredicateAdapter.PropertyTypeGate() {
          @Override
          public boolean isDeclaredString(String key) {
            return declaredStringOn(encoder.schema(), labelClass, key);
          }

          @Override
          public boolean declaredTypeIn(String key, List<String> typeNames) {
            return declaredTypeOn(encoder.schema(), labelClass, key, typeNames);
          }
        };
    for (HasContainer container : containers) {
      var key = container.getKey();
      if (LABEL_KEY.equals(key)) {
        encoder.appendToken("lab");
        encoder.appendPredicate(container.getPredicate(), true);
        continue;
      }
      if (ID_KEY.equals(key)) {
        encoder.appendToken("id");
        encoder.appendPredicate(container.getPredicate(), true);
        continue;
      }
      encoder.appendToken(key == null ? "" : key);
      encoder.appendPredicate(container.getPredicate(), false);
      GremlinPredicateAdapter.INSTANCE.bindParams(container, typeGate, encoder.paramSink());
    }
    return true;
  }

  private static boolean declaredStringOn(
      @Nullable Schema schema, @Nullable String className, String propertyKey) {
    if (schema == null || className == null || propertyKey == null) {
      return false;
    }
    var clazz = schema.getClass(className);
    if (clazz == null) {
      return false;
    }
    var property = clazz.getProperty(propertyKey);
    return property != null && property.getType() == PropertyType.STRING;
  }

  private static boolean declaredTypeOn(
      @Nullable Schema schema,
      @Nullable String className,
      String propertyKey,
      Collection<String> typeNames) {
    if (schema == null || className == null || propertyKey == null || typeNames == null
        || typeNames.isEmpty()) {
      return false;
    }
    var clazz = schema.getClass(className);
    if (clazz == null) {
      return false;
    }
    var property = clazz.getProperty(propertyKey);
    return property != null
        && property.getType() != null
        && typeNames.contains(property.getType().name());
  }
}
