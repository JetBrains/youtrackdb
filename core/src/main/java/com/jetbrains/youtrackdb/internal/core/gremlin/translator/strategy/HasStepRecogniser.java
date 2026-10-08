package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
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
 *       {@code startingWith} routing on the step's {@code ~label} class or the effective boundary
 *       class: declared {@code STRING} uses the index-aware prefix range, every other case uses the
 *       strict full-scan node.
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
 * The recogniser validates and translates <em>every</em> container before writing the boundary
 * class and alias filter. An untranslatable container (a reserved key, a conflicting {@code
 * ~label}, an unconvertible id) discards the entire walk. The label bind opens the contribution
 * block: a colliding label declines before the re-type and filter land, and {@code bindStepLabels}
 * itself checks every label before it writes any of them.
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

    var contribution = prepare(ctx, containers, labelConstraint, List.of(),
        ctx.boundaryClassName(), ctx::bindParam, false);
    if (contribution == null || !contribution.matchCapable()) {
      return Outcome.DECLINE;
    }
    // A filter does not transform its labelled element. Validate the label bind before writing
    // the prepared class and WHERE contributions onto the boundary alias.
    if (!ctx.bindStepLabels(hasStep, boundary)) {
      return Outcome.DECLINE;
    }
    contribution.applyToMatch(ctx, boundary);
    return Outcome.ACCEPTED;
  }

  /**
   * One HasStep's validated contribution. A non-MATCH-capable predicate stays available to the
   * native ordered-expand op. A null expression with MATCH capability is a polymorphic single-label
   * re-type that needs no additional class filter. Provisional bindings commit only on flush.
   */
  record HasContribution(
      List<HasContainer> nativeContainers,
      List<String> labelAlternatives,
      @Nullable SQLBooleanExpression matchExpression,
      boolean matchCapable,
      @Nullable String targetClass,
      @Nullable String effectiveClass,
      List<Object> deferredBindings,
      int firstBindingSlot,
      boolean cacheSafe,
      HasBindingContext bindingContext,
      List<HasBindingContext.Slot> slots,
      NativeHasOperands nativeOperands) {

    HasContribution {
      nativeContainers = List.copyOf(nativeContainers);
      labelAlternatives = List.copyOf(labelAlternatives);
      deferredBindings = java.util.Collections.unmodifiableList(new ArrayList<>(deferredBindings));
      slots = List.copyOf(slots);
    }

    boolean applyToMatch(RecognitionContext ctx, String alias) {
      if (!matchCapable) {
        return false;
      }
      // An ordered-expand route never commits these values. On flush the slots reserved at
      // recognition must still be the next ones available, or the prepared AST would bind wrongly.
      if (!deferredBindings.isEmpty()) {
        if (!(ctx instanceof WalkerContext walker)
            || walker.inputParameters.size() != firstBindingSlot) {
          return false;
        }
        for (var value : deferredBindings) {
          ctx.bindParam(value);
        }
      }
      if (labelAlternatives.size() > 1
          && ctx instanceof SubTraversalPredicateAdapter adapter
          && adapter.concreteCapturedClassForAlias(alias) != null) {
        // Positive commits retain the scan class and intersect explicit label filters.
        // Captured refinements remain outside the supported boolean fold.
        adapter.markCapturedClassChange();
      }
      if (targetClass != null) {
        ctx.addNode(alias, targetClass);
        if (ctx.polymorphic()
            && labelAlternatives.size() == 1
            && ctx instanceof SubTraversalPredicateAdapter adapter) {
          // The scan class is not a boolean label test. Keep the named label for the OR/NOT fold.
          adapter.capturePolymorphicLabel(alias, labelAlternatives.getFirst());
        }
      }
      if (matchExpression != null) {
        ctx.putAliasFilter(alias, WHERE.wrap(matchExpression));
      }
      ctx.recordHasBinding(new HasBindingContext(HasBindingContext.Destination.MATCH_VERTEX,
          bindingContext.gateClasses(), bindingContext.folded()), slots);
      return true;
    }
  }

  /**
   * Prepare the MATCH and native-op views together. Deferred label alternatives OR within this
   * HasStep; the caller applies each prepared step separately, so steps AND at the MATCH alias.
   * Missing deferred alternatives remain in the class predicate, never in a MATCH source class.
   */
  private static @Nullable HasContribution prepare(
      RecognitionContext ctx, List<HasContainer> containers,
      @Nullable ParsedLabelConstraint mainLabel, List<String> deferredLabels,
      @Nullable String boundaryClass, ParamSink sink, boolean deferred) {
    var names = new ArrayList<String>();
    if (deferred) {
      names.addAll(deferredLabels);
    } else if (mainLabel instanceof ParsedLabelConstraint.Single single) {
      names.add(single.name());
    } else if (mainLabel instanceof ParsedLabelConstraint.Multi multi) {
      names.addAll(multi.names());
    }
    // A step-local label group overrides the boundary class; otherwise the current boundary
    // supplies the type gate. Both extraction and the walk read this same context description.
    var bindingContext = HasBindingContext.forVertex(names, boundaryClass,
        ctx.atTraversalStart(), deferred ? HasBindingContext.Destination.ORDERED_FILTER
            : HasBindingContext.Destination.MATCH_VERTEX);
    var typeGate = bindingContext.gate(ctx);
    var slots = new ArrayList<HasBindingContext.Slot>();
    // A range comparison needs the per-record type guard exactly when this HasStep will NOT be
    // folded into YTDBGraphStep — folded, the native fallback runs the same SQL-style comparison the
    // translation emits; unfolded, it runs TinkerPop's comparability rule instead. See
    // RecognitionContext.atTraversalStart(). The adapter still drops the guard when the schema
    // declares the property in the literal's comparability block (schemaGate).
    var rangeTypeGuard = !ctx.atTraversalStart();

    // Translate each id/property once. The deferred op keeps the original containers even when
    // the adapter cannot build a MATCH predicate, while the ordinary MATCH route then declines.
    var whereExprs = new ArrayList<SQLBooleanExpression>();
    boolean matchCapable = true;
    boolean cacheSafe = true;
    for (int index = 0; index < containers.size(); index++) {
      var container = containers.get(index);
      var key = container.getKey();
      if (LABEL_KEY.equals(key)) {
        continue;
      }
      if (ID_KEY.equals(key)) {
        ctx.markRidBearing();
        cacheSafe = false;
        var ridExpr = translateHasId(container);
        if (ridExpr == null) {
          matchCapable = false;
        } else {
          whereExprs.add(ridExpr);
        }
        continue;
      }
      int containerIndex = index;
      var recordedSink = GremlinPredicateAdapter.withRoles(sink,
          role -> slots.add(new HasBindingContext.Slot(containerIndex, role)));
      var filter = GremlinPredicateAdapter.INSTANCE.toFilter(
          container, typeGate, recordedSink, rangeTypeGuard);
      if (filter == null) {
        matchCapable = false;
      } else {
        whereExprs.add(filter);
      }
      // A slotless singleton collection cannot be replayed safely. Regex is different: its
      // compiled native pattern is captured explicitly and reconstructed at splice.
      if (deferred && (!NativeHasOperands.cacheable(container.getPredicate())
          || (slots.stream().noneMatch(s -> s.containerIndex() == containerIndex)
              && !NativeHasOperands.regexOnly(container.getPredicate())))) {
        cacheSafe = false;
      }
    }
    String targetClass = null;
    if (!names.isEmpty()) {
      // Do not re-type to a known alternative if another alternative is missing: a MATCH class
      // source cannot represent the missing name, whereas the native label predicate can.
      targetClass = narrowedClass(ctx, names, boundaryClass,
          !deferred && mainLabel instanceof ParsedLabelConstraint.Single, deferred);
      if (!deferred && mainLabel instanceof ParsedLabelConstraint.Single single) {
        if (!ctx.polymorphic()) {
          whereExprs.add(WHERE.classEquals(single.name()));
        }
      } else {
        whereExprs.add(WHERE.classIn(ctx.polymorphic()
            ? ctx.expandPolymorphicClassClosure(names) : names));
      }
    }
    // A native-only predicate has no SQL representation, but retains its original containers.
    var expression = matchCapable && !whereExprs.isEmpty()
        ? WHERE.and(whereExprs.toArray(new SQLBooleanExpression[0])) : null;
    return new HasContribution(containers, names, expression, matchCapable, targetClass,
        targetClass == null ? boundaryClass : targetClass, List.of(), 0, cacheSafe,
        bindingContext, slots, NativeHasOperands.capture(containers));
  }

  /**
   * MATCH re-types a single label even across an incompatible boundary. A multi-label MATCH
   * contribution re-types to its LCA only at the generic vertex root. Deferred contributions
   * accumulate on one alias and may narrow that alias but never broaden it.
   */
  static @Nullable String narrowedClass(RecognitionContext ctx, List<String> names,
      @Nullable String boundaryClass, boolean singleMatchLabel, boolean deferredRoute) {
    if (names.isEmpty() || !names.stream().allMatch(ctx::isVertexClass)) {
      return null;
    }
    if (!deferredRoute) {
      if (singleMatchLabel) {
        return names.getFirst();
      }
      if (!WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass)) {
        return null;
      }
      var lca = ctx.leastCommonVertexAncestor(names);
      return lca == null || WalkerContext.VERTEX_ROOT_CLASS.equals(lca) ? null : lca;
    }
    var candidate = names.size() == 1 ? names.getFirst()
        : ctx.leastCommonVertexAncestor(names);
    if (candidate == null || WalkerContext.VERTEX_ROOT_CLASS.equals(candidate)) {
      return null;
    }
    // A later HasStep may narrow an earlier source, but must not broaden it.
    if (singleMatchLabel || boundaryClass == null
        || WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass)
        || candidate.equals(boundaryClass)
        || boundaryClass.equals(ctx.leastCommonVertexAncestor(
            List.of(boundaryClass, candidate)))) {
      return candidate;
    }
    return null;
  }

  /**
   * The deferred route prepares its MATCH expression before deciding whether a slice takes it into
   * the native op. Its provisional slots reserve positions but do not bind anything on the op route.
   */
  static @Nullable HasContribution prepareDeferred(
      RecognitionContext ctx, List<HasContainer> containers, String targetClass, int firstSlot) {
    var names = new ArrayList<String>();
    for (var container : containers) {
      if (LABEL_KEY.equals(container.getKey())) {
        var parsed = parseLabelContainer(container);
        if (parsed instanceof ParsedLabelConstraint.Single single) {
          names.add(single.name());
        } else if (parsed instanceof ParsedLabelConstraint.Multi multi) {
          names.addAll(multi.names());
        } else {
          return null;
        }
      }
    }
    var bindings = new ArrayList<Object>();
    ParamSink sink = value -> {
      var slot = SQLPositionalParameter.forSlot(firstSlot + bindings.size());
      bindings.add(value);
      return slot;
    };
    var prepared = prepare(ctx, containers, null, names, targetClass, sink, true);
    if (prepared == null) {
      return null;
    }
    return new HasContribution(prepared.nativeContainers(), prepared.labelAlternatives(),
        prepared.matchExpression(), prepared.matchCapable(), prepared.targetClass(),
        prepared.effectiveClass(), bindings, firstSlot, prepared.cacheSafe(),
        prepared.bindingContext(), prepared.slots(), prepared.nativeOperands());
  }

  /** Flush each prepared HasStep in order, retaining the separate label group's class condition. */
  static boolean contributeToAlias(
      RecognitionContext ctx, String alias, List<HasContribution> contributions) {
    for (var contribution : contributions) {
      if (!contribution.applyToMatch(ctx, alias)) {
        return false;
      }
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
    // TinkerPop folds chained hasLabel() into one HasStep. MATCH declines that shape; the deferred
    // hop must too — merging the containers into one NeighbourFilter would OR the conditions.
    if (conflictingLabelContainers(containers)) {
      return Outcome.DECLINE;
    }
    if (!ctx.bindStepLabels(hasStep, pending.targetAlias())) {
      return Outcome.DECLINE;
    }
    int firstSlot = ctx instanceof WalkerContext walker ? walker.inputParameters.size() : 0;
    for (var prior : pending.contributions()) {
      firstSlot += prior.deferredBindings().size();
    }
    var contribution = prepareDeferred(ctx, containers, pending.effectiveTargetClass(), firstSlot);
    if (contribution == null) {
      return Outcome.DECLINE;
    }
    if (!contribution.cacheSafe()) {
      ctx.markRidBearing();
    }
    ctx.setPendingOrderedHop(pending.appendHasStep(contribution, ctx.polymorphic()));
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
   * Whether one HasStep holds label containers that cannot share a single OR group (YTDB-1369).
   * Chained {@code hasLabel} calls merge into one step; AND across those containers is not a
   * NeighbourFilter / MATCH classIn disjunction.
   */
  static boolean conflictingLabelContainers(List<HasContainer> containers) {
    ParsedLabelConstraint labelConstraint = null;
    for (var container : containers) {
      if (!LABEL_KEY.equals(container.getKey())) {
        continue;
      }
      var parsed = parseLabelContainer(container);
      if (parsed == null) {
        return true;
      }
      if (labelConstraint == null) {
        labelConstraint = parsed;
      } else if (labelConstraint.conflictsWith(parsed)) {
        return true;
      }
    }
    return false;
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
    var context = encoder.hasBindingContext();
    if (context == null) {
      context = HasBindingContext.forVertex(labelNames(containers), null, false,
          HasBindingContext.Destination.MATCH_VERTEX);
    }
    var typeGate = context.gate(encoder.schema());
    for (int index = 0; index < containers.size(); index++) {
      HasContainer container = containers.get(index);
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
      int containerIndex = index;
      // Ordered-filter slots describe the native predicate's layout, but never enter MATCH's
      // positional map. Otherwise a later MATCH HasStep would be bound at the wrong SQL slot.
      int[] localSlot = {0};
      ParamSink sink = context.destination() == HasBindingContext.Destination.ORDERED_FILTER
          ? value -> SQLPositionalParameter.forSlot(localSlot[0]++) : encoder.paramSink();
      GremlinPredicateAdapter.INSTANCE.bindParams(container, typeGate,
          GremlinPredicateAdapter.withRoles(sink,
              role -> encoder.recordHasSlot(new HasBindingContext.Slot(containerIndex, role))),
          !context.folded());
    }
    return true;
  }

  /** The label group of one step, independent of the prior boundary's class. */
  static List<String> labelNames(List<HasContainer> containers) {
    var names = new ArrayList<String>();
    for (var container : containers) {
      if (LABEL_KEY.equals(container.getKey())) {
        var parsed = parseLabelContainer(container);
        if (parsed instanceof ParsedLabelConstraint.Single single) {
          names.add(single.name());
        } else if (parsed instanceof ParsedLabelConstraint.Multi multi) {
          names.addAll(multi.names());
        }
      }
    }
    return List.copyOf(names);
  }

}
