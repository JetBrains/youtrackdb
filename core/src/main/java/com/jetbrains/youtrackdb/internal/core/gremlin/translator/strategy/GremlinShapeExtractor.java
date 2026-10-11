package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;

/**
 * Cheap pre-walk extraction of the translation-cache key and this invocation's {@code ?} bindings.
 * Dispatches each step to the same class-keyed recogniser registry the walker uses: the recogniser
 * that would translate the step is the one that lists the tokens it reads. An unregistered step is
 * encoded by class name and labels only (the walker declines it). A recogniser that returns
 * {@code false} from {@link StepRecogniser#contributeShape}, or a lambda modulator the extractor
 * cannot name, marks the extraction incomplete so {@code apply} will not cache a {@code Translate}.
 *
 * <p>The key opens with the strategy-flag section. It carries every resolved setting that changes
 * the emitted plan for one step sequence. The section includes polymorphism ({@code poly}), edge
 * label verification ({@code elv}), and the productive-order setting ({@code oim}). It includes
 * resolved null placements ({@code onp}) only when a global order step can embed them. It also
 * includes upstream {@code ProductiveByStrategy}'s productive keys ({@code pb}).
 *
 * <p>Lambda {@code by()} modulators ({@link ValueTraversal}, {@link TokenTraversal}, {@link
 * IdentityTraversal}, {@link RecordIdSortKeyTraversal}) have an empty step list; their property key
 * / token lives on the traversal itself. Encoding them here, once, covers {@code order}/{@code group}/{@code project}/{@code
 * select} without each recogniser re-implementing the split.
 */
final class GremlinShapeExtractor {

  private final Map<Class<?>, StepRecogniser> recognisers;

  private final Set<Class<?>> transparentSteps;

  private final GremlinShapeEncoder encoder;

  private GremlinShapeExtractor(
      Map<Class<?>, StepRecogniser> recognisers,
      Set<Class<?>> transparentSteps,
      GremlinShapeEncoder encoder) {
    this.recognisers = recognisers;
    this.transparentSteps = transparentSteps;
    this.encoder = encoder;
  }

  /**
   * @param orderIncludesMissingKey the productive-order setting ALREADY RESOLVED for this
   *     compilation, or {@code null} when the caller has none. The value is passed in rather than
   *     resolved here so the key and the plan built beside it read one and the same answer. A
   *     second read could see a runtime flip and file the plan under the other setting's key, in
   *     a cache that is storage-wide and outlives the session.
   */
  static Extraction extract(
      @Nonnull Map<Class<?>, StepRecogniser> recognisers,
      @Nonnull Set<Class<?>> transparentSteps,
      @Nonnull Traversal.Admin<?, ?> traversal,
      @Nonnull DatabaseSessionEmbedded session,
      @Nullable Boolean orderIncludesMissingKey,
      @Nonnull ResolvedOrderByNullsPlacement orderByNullsPlacements,
      @Nullable Boolean polymorphic) {
    var extractor =
        new GremlinShapeExtractor(
            recognisers, transparentSteps, new GremlinShapeEncoder(session.getSchema()));
    extractor.appendStrategyFlags(
        traversal, orderIncludesMissingKey, polymorphic);
    extractor.visit(traversal, WalkerContext.VERTEX_ROOT_CLASS);
    // The verdict reads only encoded step structure. Absent tokens cannot equal real facts.
    if (extractor.possibleOrderedExpand) {
      var facts = TraverserMergeFacts.from(traversal);
      extractor.encoder.appendToken("sk", facts.sackToken());
      extractor.encoder.appendToken("lp", "real:" + facts.pathToken());
    }
    if (extractor.globalOrder) {
      extractor.encoder.appendToken("onp", orderByNullsPlacements.ascending().name()
          + "/" + orderByNullsPlacements.descending().name());
    }
    return new Extraction(extractor.encoder.key(), extractor.encoder.bindings(),
        extractor.encoder.complete(), extractor.encoder.hasContributions(),
        List.copyOf(extractor.nativeOperands), !extractor.possibleOrderedExpand);
  }

  record Extraction(@Nonnull String key, @Nonnull Map<Object, Object> bindings, boolean complete,
      @Nonnull java.util.List<HasBindingContext.Contribution> hasContributions,
      @Nonnull java.util.List<NativeHasOperands> nativeOperands, boolean orderedExpandAbsent) {
  }

  private boolean possibleOrderedExpand;
  private boolean globalOrder;
  private HasBindingContext.VertexClassFacts schemaFacts;

  private final java.util.List<NativeHasOperands> nativeOperands = new java.util.ArrayList<>();

  private void appendStrategyFlags(
      Traversal.Admin<?, ?> traversal,
      @Nullable Boolean orderIncludesMissingKey,
      @Nullable Boolean polymorphic) {
    encoder.appendToken("poly", polymorphic == null ? "n" : (polymorphic ? "1" : "0"));
    encoder.appendToken(
        "elv",
        traversal.getStrategies().getStrategy(EdgeLabelVerificationStrategy.class).isPresent()
            ? "1"
            : "0");
    var productiveKeys =
        traversal
            .getStrategies()
            .getStrategy(ProductiveByStrategy.class)
            .map(ProductiveByStrategy::getProductiveKeys)
            .orElse(null);
    // The resolved productive-order setting changes the emitted pattern: under the shipped default
    // the order-key IS DEFINED conjunct is omitted, under the opt-out it is emitted. The cache is
    // storage-wide, so without this token a plan built under one setting would be spliced verbatim
    // into a traversal running under the other, in another session.
    encoder.appendToken(
        "oim",
        orderIncludesMissingKey == null ? "n" : (orderIncludesMissingKey ? "1" : "0"));
    if (productiveKeys == null) {
      encoder.appendToken("pb", "-");
    } else {
      encoder.appendToken("pb", Integer.toString(productiveKeys.size()));
      for (String key : new TreeSet<>(productiveKeys)) {
        encoder.appendToken(key);
      }
    }
  }

  private void visit(Traversal.Admin<?, ?> traversal, String inheritedBoundaryClass) {
    if (encodeLambda(traversal)) {
      return;
    }
    int counted = 0;
    boolean sawOrder = false;
    boolean sawHop = false;
    boolean sawSlice = false;
    boolean scopeOrderedExpand = false;
    // Reuse the existing count pass. This conservative check needs only three local bits and
    // covers both slice placements, including projections and transparent barriers between them.
    for (Step<?, ?> step : traversal.getSteps()) {
      if (!isTransparent(step)) {
        counted++;
      }
      if (step instanceof OrderGlobalStep) {
        globalOrder = true;
        sawOrder = true;
      } else if (sawOrder && step instanceof VertexStepContract<?> hop && !hop.returnsEdge()) {
        sawHop = true;
      } else if (sawOrder && step instanceof RangeGlobalStepContract<?>) {
        sawSlice = true;
      }
      scopeOrderedExpand |= sawHop && sawSlice;
    }
    possibleOrderedExpand |= scopeOrderedExpand;
    encoder.appendToken("T", Integer.toString(counted));
    // Track only the facts needed to bind HasSteps. This does not dispatch or build a second
    // MATCH walk. A GraphStep opens the native fold; every other non-HasStep closes it.
    String boundaryClass = inheritedBoundaryClass;
    String[] edgeClasses = null;
    boolean edgeOpen = false;
    boolean folded = false;
    boolean ordered = false;
    boolean sourceSliced = false;
    boolean deferredHop = false;
    boolean pendingDeferred = false;
    var steps = traversal.getSteps();
    boolean initialVertexStart = WalkerContext.VERTEX_ROOT_CLASS.equals(inheritedBoundaryClass)
        && !steps.isEmpty() && steps.getFirst() instanceof GraphStep<?, ?> start
        && start.returnsVertex();
    for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
      Step<?, ?> step = steps.get(stepIndex);
      if (isTransparent(step)) {
        folded = false;
        // Positive-capacity barriers stay out of the counted step list, but must still
        // discriminate the shape key — labelled or not. barrier(0) encodes as a normal step. An
        // unlabelled barrier that closes the fold changes comparison semantics vs the folded
        // spelling.
        encoder.appendToken("TB", step.getClass().getName());
        if (step instanceof org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep<
            ?> barrier) {
          encoder.appendToken("TW", Integer.toString(barrier.getMaxBarrierSize()));
        }
        encoder.appendStringSeq("L", GremlinStepLabels.userLabels(step));
        continue;
      }
      encoder.appendToken("S", step.getClass().getName());
      HasBindingContext hasContext = null;
      java.util.List<String> hasLabels = java.util.List.of();
      String provisionalLabel = null;
      int slotStart = encoder.hasSlotCount();
      if (step instanceof HasStep<?> hasStep) {
        // hasId RIDs stay structural in the shape key (appendPredicate). Walk-time markRidBearing
        // is what bypasses GremlinPlanCache reuse — do not mark the extraction incomplete here.
        boolean orderedFilter = scopeOrderedExpand && deferredHop && (sourceSliced
            || followedByOrderedSlice(steps, stepIndex));
        if (initialVertexStart && folded && !edgeOpen && !pendingDeferred && !orderedFilter
            && WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass)) {
          provisionalLabel = HasStepRecogniser.singleEqualityLabel(hasStep.getHasContainers());
        }
        hasLabels = provisionalLabel == null
            ? HasStepRecogniser.labelNames(hasStep.getHasContainers(), pendingDeferred)
            : List.of(provisionalLabel);
        hasContext = edgeOpen ? HasBindingContext.forEdge(edgeClasses)
            : HasBindingContext.forVertex(hasLabels, boundaryClass, folded,
                orderedFilter ? HasBindingContext.Destination.ORDERED_FILTER
                    : HasBindingContext.Destination.MATCH_VERTEX);
        encoder.setHasBindingContext(hasContext);
        if (orderedFilter) {
          nativeOperands.add(NativeHasOperands.capture(hasStep.getHasContainers()));
        }
      }
      var labels = GremlinStepLabels.userLabels(step);
      encoder.appendStringSeq("L", labels);
      var recogniser = recognisers.get(step.getClass());
      if (recogniser != null && !recogniser.contributeShape(step, encoder)) {
        encoder.markIncomplete();
      }
      if (hasContext != null) {
        encoder.recordHasContribution(hasContext, slotStart);
        if (hasContext.destination() == HasBindingContext.Destination.ORDERED_FILTER
            && hasStepHasUnrebuildableOperands((HasStep<?>) step)) {
          // A cold walk can use the original native predicate, but must not splice a template.
          encoder.markIncomplete();
        }
        if (provisionalLabel != null) {
          // This is query context, not schema validation. The full walker validates existence,
          // vertex status and narrowing before a template can pass binding/layout agreement.
          // Later property HasSteps still resolve this class through their real type gates.
          boundaryClass = provisionalLabel;
        } else if (!edgeOpen && !hasLabels.isEmpty()) {
          // Captured children and complex constraints retain schema-based narrowing. Initialize
          // these facts only on the fallback, never for an ordinary provisional equality label.
          if (schemaFacts == null) {
            schemaFacts = HasBindingContext.schemaFacts(encoder.schema());
          }
          var candidate = HasStepRecogniser.narrowedClass(schemaFacts, hasLabels,
              boundaryClass, !pendingDeferred && hasLabels.size() == 1, pendingDeferred);
          if (candidate != null) {
            boundaryClass = candidate;
          }
        }
      }
      if (!(step instanceof HasStep<?>) && (!pendingDeferred || !isNonSelectingRange(step))) {
        pendingDeferred = false;
      }
      if (step instanceof GraphStep<?, ?>) {
        boundaryClass = WalkerContext.VERTEX_ROOT_CLASS;
        edgeClasses = null;
        edgeOpen = false;
        ordered = false;
        sourceSliced = false;
        deferredHop = false;
        folded = true;
      } else if (step instanceof VertexStepContract<?> hop) {
        boundaryClass = HasBindingContext.afterHopBoundary();
        edgeClasses = hop.returnsEdge() ? hop.getEdgeLabels() : null;
        edgeOpen = hop.returnsEdge();
        deferredHop = ordered && !hop.returnsEdge();
        pendingDeferred = deferredHop;
        folded = false;
      } else if (step instanceof EdgeVertexStep || step instanceof EdgeOtherVertexStep) {
        edgeClasses = null;
        edgeOpen = false;
        boundaryClass = HasBindingContext.afterHopBoundary();
        folded = false;
      } else if (!(step instanceof HasStep<?>)) {
        folded = false;
      }
      if (step instanceof OrderGlobalStep) {
        ordered = true;
        sourceSliced = false;
      } else if (step instanceof RangeGlobalStepContract<?> && ordered && !deferredHop
          && !isNonSelectingRange(step)) {
        sourceSliced = true;
      }
      if (step instanceof TraversalParent parent) {
        // All children inherit the current boundary class, including union arms.
        // A child-local hasLabel can narrow it. A hop resets it to V.
        for (var child : parent.getLocalChildren()) {
          visit(child.asAdmin(), boundaryClass);
        }
        for (var child : parent.getGlobalChildren()) {
          visit(child.asAdmin(), boundaryClass);
        }
      }
    }
  }

  private static boolean hasStepHasUnrebuildableOperands(HasStep<?> step) {
    return step.getHasContainers().stream()
        .filter(c -> !org.apache.tinkerpop.gremlin.structure.T.label.getAccessor()
            .equals(c.getKey()))
        .anyMatch(c -> !NativeHasOperands.cacheable(c.getPredicate()));
  }

  private boolean isTransparent(Step<?, ?> step) {
    return transparentSteps.contains(step.getClass()) && StepStreamCursor.hasCapacity(step);
  }

  /** A deferred hop's filters enter the op only when a slice follows the filter run. */
  private boolean followedByOrderedSlice(java.util.List<? extends Step> steps, int index) {
    for (int next = index + 1; next < steps.size(); next++) {
      Step<?, ?> step = steps.get(next);
      // Identity ranges leave the hop pending, so only an effective later slice owns its filters.
      if (step instanceof HasStep<?> || isTransparent(step) || isNonSelectingRange(step)) {
        continue;
      }
      return step instanceof RangeGlobalStepContract<?>;
    }
    return false;
  }

  private static boolean isNonSelectingRange(Step<?, ?> step) {
    // Use the walker's normalization. Invalid ranges also select nothing here and decline in the walk.
    return step instanceof RangeGlobalStepContract<?>
        && !RangeGlobalStepRecogniser.INSTANCE.selectsPositionally(step);
  }

  /**
   * {@code true} when {@code traversal} is a lambda (no step list) and has been encoded as such.
   * Unknown lambdas mark the extraction incomplete.
   */
  private boolean encodeLambda(Traversal.Admin<?, ?> traversal) {
    if (traversal instanceof ValueTraversal<?, ?> valueTraversal) {
      encoder.appendToken("vt", String.valueOf(valueTraversal.getPropertyKey()));
      return true;
    }
    if (traversal instanceof TokenTraversal<?, ?> tokenTraversal) {
      var token = tokenTraversal.getToken();
      encoder.appendToken("tt", token == null ? "-" : token.getAccessor());
      return true;
    }
    if (traversal instanceof IdentityTraversal) {
      encoder.appendToken("idtr", "1");
      return true;
    }
    // The appended record identifier sort key. Carrying its own token keeps a translated order()
    // shape cacheable; without it the lambda fallback below would mark every one incomplete.
    if (traversal instanceof RecordIdSortKeyTraversal) {
      encoder.appendToken("ridsk", "1");
      return true;
    }
    if (traversal.getSteps().isEmpty()) {
      encoder.appendToken("lambda", traversal.getClass().getName());
      encoder.markIncomplete();
      return true;
    }
    return false;
  }
}
