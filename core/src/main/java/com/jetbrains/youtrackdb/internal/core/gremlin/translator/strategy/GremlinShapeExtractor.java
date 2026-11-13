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

/**
 * Cheap pre-walk extraction of the translation-cache key and this invocation's {@code ?} bindings.
 * Dispatches each step to the same class-keyed recogniser registry the walker uses: the recogniser
 * that would translate the step is the one that lists the tokens it reads. An unregistered step
 * needs an explicit complete native encoding from the closed safety admission list on the private
 * partial policy path. Otherwise that private extraction is incomplete. Full-only extraction keeps
 * class, labels and children for native steps so complete native declines remain cacheable. A recogniser that returns
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

  private final boolean strictNativeInputs;

  private GremlinShapeExtractor(
      Map<Class<?>, StepRecogniser> recognisers,
      Set<Class<?>> transparentSteps,
      GremlinShapeEncoder encoder, boolean strictNativeInputs) {
    this.recognisers = recognisers;
    this.transparentSteps = transparentSteps;
    this.encoder = encoder;
    this.strictNativeInputs = strictNativeInputs;
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
      @Nullable Boolean polymorphic, TranslationEnvironment environment) {
    return extract(recognisers, transparentSteps, traversal, orderIncludesMissingKey,
        orderByNullsPlacements, polymorphic, environment,
        new GremlinShapeEncoder(session.getSchema()));
  }

  // Encoder injection keeps the text-key reference oracle independent of structural storage.
  static Extraction extract(Map<Class<?>, StepRecogniser> recognisers,
      Set<Class<?>> transparentSteps, Traversal.Admin<?, ?> traversal,
      @Nullable Boolean orderIncludesMissingKey,
      ResolvedOrderByNullsPlacement orderByNullsPlacements,
      @Nullable Boolean polymorphic, TranslationEnvironment environment,
      GremlinShapeEncoder encoder) {
    return extractInternal(recognisers, transparentSteps, traversal, orderIncludesMissingKey,
        orderByNullsPlacements, polymorphic, environment, encoder, null);
  }

  static Extraction extractPartial(Map<Class<?>, StepRecogniser> recognisers,
      Set<Class<?>> transparentSteps, Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session, Boolean includesMissing,
      ResolvedOrderByNullsPlacement nulls, Boolean polymorphic,
      GremlinStepWalker.WalkInputs inputs) {
    return extractInternal(recognisers, transparentSteps, traversal, inputs.includesMissing(),
        inputs.nulls(), inputs.polymorphic(),
        new TranslationEnvironment(session, inputs.edgeVerification(), inputs.productiveKeys()),
        new GremlinShapeEncoder(inputs.schema()), inputs);
  }

  private static Extraction extractInternal(Map<Class<?>, StepRecogniser> recognisers,
      Set<Class<?>> transparentSteps, Traversal.Admin<?, ?> traversal, Boolean includesMissing,
      ResolvedOrderByNullsPlacement orderByNullsPlacements, Boolean polymorphic,
      TranslationEnvironment environment, GremlinShapeEncoder encoder,
      GremlinStepWalker.WalkInputs inputs) {
    var extractor =
        new GremlinShapeExtractor(recognisers, transparentSteps, encoder, inputs != null);
    extractor.appendStrategyFlags(environment, includesMissing, polymorphic);
    extractor.visit(traversal, null);
    int bulkingCapacity = inputs == null ? PropertyMapBulkingAdmission.capacity(traversal)
        : PropertyMapBulkingAdmission.capacity(traversal, inputs.permitsGrouping());
    if (bulkingCapacity > 0) {
      extractor.encoder.appendToken("pmb", Integer.toString(bulkingCapacity));
    }
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
    return new Extraction(extractor.encoder.compactKey(), Map.of(),
        extractor.encoder.complete(), List.of(),
        extractor.nativeOperands, !extractor.possibleOrderedExpand, bulkingCapacity,
        extractor.encoder, extractor.rootEnds);
  }

  record Extraction(@Nonnull GremlinShapeKey key, @Nonnull Map<Object, Object> bindings,
      boolean complete, @Nonnull List<HasBindingContext.Contribution> hasContributions,
      @Nonnull List<NativeHasOperands> nativeOperands, boolean orderedExpandAbsent,
      int propertyMapBulkingCapacity, @Nullable GremlinShapeEncoder encoder,
      List<BindingEnd> rootEnds) {

    Extraction(GremlinShapeKey key, Map<Object, Object> bindings, boolean complete,
        List<HasBindingContext.Contribution> hasContributions,
        List<NativeHasOperands> nativeOperands, boolean orderedExpandAbsent,
        int propertyMapBulkingCapacity) {
      this(key, bindings, complete, hasContributions, nativeOperands, orderedExpandAbsent,
          propertyMapBulkingCapacity, null, List.of());
    }

    /** Freeze the full harvest before making independent immutable prefix slices. */
    Extraction prefix(int extent, boolean orderedExpandAbsent, int bulkingCapacity) {
      var values = bindings();
      var contributions = hasContributions();
      var operands = nativeOperands();
      var end = rootEnds.get(extent - 1);
      var slice = new java.util.LinkedHashMap<Object, Object>();
      for (int slot = 0; slot < end.bindings(); slot++) {
        slice.put(slot, values.get(slot));
      }
      return new Extraction(key, java.util.Collections.unmodifiableMap(slice), complete,
          List.copyOf(contributions.subList(0, end.contributions())),
          List.copyOf(operands.subList(0, end.nativeOperands())), orderedExpandAbsent,
          bulkingCapacity, null, List.copyOf(rootEnds.subList(0, extent)));
    }

    @Override
    public Map<Object, Object> bindings() {
      if (encoder == null) {
        return bindings;
      }
      encoder.harvest();
      return encoder.bindings();
    }

    @Override
    public List<HasBindingContext.Contribution> hasContributions() {
      if (encoder == null) {
        return hasContributions;
      }
      encoder.harvest();
      return encoder.hasContributions();
    }

    @Override
    public List<NativeHasOperands> nativeOperands() {
      return encoder == null ? nativeOperands : encoder.nativeOperands(nativeOperands);
    }
  }

  private final List<BindingEnd> rootEnds = new java.util.ArrayList<>();

  record BindingEnd(int bindings, int contributions, int nativeOperands) {
  }

  private void recordRootEnd() {
    if (strictNativeInputs) {
      // Replay the checkpoint after the slots belonging to this root step and its children.
      encoder.deferHarvest(() -> rootEnds.add(new BindingEnd(encoder.bindingCount(),
          encoder.contributionCount(), nativeOperands.size())));
    }
  }

  static final class OrderedExpandEvidence {
    private boolean order;
    private boolean hop;
    private boolean slice;

    void accept(Step<?, ?> step) {
      if (step instanceof OrderGlobalStep) {
        order = true;
      } else if (order && step instanceof VertexStepContract<?> vertex && !vertex.returnsEdge()) {
        hop = true;
      } else if (order && step instanceof RangeGlobalStepContract<?>) {
        slice = true;
      }
    }

    boolean present() {
      return hop && slice;
    }
  }

  private boolean possibleOrderedExpand;
  private boolean globalOrder;

  /** Replay records hold only structural facts until bindings are actually requested. */
  private static final class HarvestScope implements Runnable {
    private final HarvestScope parent;
    private String boundaryClass;
    private String[] edgeClasses;
    private boolean inheritedVertexRoot;

    private HarvestScope(HarvestScope parent) {
      this.parent = parent;
    }

    @Override
    public void run() {
      boundaryClass = parent == null ? WalkerContext.VERTEX_ROOT_CLASS : parent.boundaryClass;
      inheritedVertexRoot = WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass);
    }
  }

  private record ResetBoundary(HarvestScope scope, String boundary, String[] edgeClasses)
      implements Runnable {
    @Override
    public void run() {
      scope.boundaryClass = boundary;
      scope.edgeClasses = edgeClasses;
    }
  }

  private record HasHarvest(HarvestScope scope, HasStep<?> step, boolean initialVertexStart,
      boolean folded, boolean pendingDeferred, boolean orderedFilter, GremlinShapeEncoder encoder,
      List<NativeHasOperands> nativeOperands) implements Runnable {
    @Override
    public void run() {
      var containers = step.getHasContainers();
      boolean edgeOpen = scope.edgeClasses != null;
      var provisionalLabel = initialVertexStart && scope.inheritedVertexRoot && folded
          && !edgeOpen && !pendingDeferred
          && !orderedFilter && WalkerContext.VERTEX_ROOT_CLASS.equals(scope.boundaryClass)
              ? HasStepRecogniser.singleEqualityLabel(containers) : null;
      var names = provisionalLabel == null
          ? HasStepRecogniser.labelNames(containers, pendingDeferred) : List.of(provisionalLabel);
      var context = edgeOpen ? HasBindingContext.forEdge(scope.edgeClasses)
          : HasBindingContext.forVertex(names, scope.boundaryClass, folded,
              orderedFilter ? HasBindingContext.Destination.ORDERED_FILTER
                  : HasBindingContext.Destination.MATCH_VERTEX);
      // Contribution and native operands must describe the very same HasStep enumeration.
      HasStepRecogniser.harvest(containers, context, encoder);
      if (orderedFilter) {
        nativeOperands.add(NativeHasOperands.capture(containers));
      }
      if (provisionalLabel != null) {
        scope.boundaryClass = provisionalLabel;
      } else if (!edgeOpen && !names.isEmpty()) {
        var candidate = HasStepRecogniser.narrowedClass(
            HasBindingContext.schemaFacts(encoder.schema()), names, scope.boundaryClass,
            !pendingDeferred && names.size() == 1, pendingDeferred);
        if (candidate != null) {
          scope.boundaryClass = candidate;
        }
      }
    }
  }

  private final java.util.List<NativeHasOperands> nativeOperands = new java.util.ArrayList<>();

  private void appendStrategyFlags(
      TranslationEnvironment environment,
      @Nullable Boolean orderIncludesMissingKey,
      @Nullable Boolean polymorphic) {
    encoder.appendToken("poly", polymorphic == null ? "n" : (polymorphic ? "1" : "0"));
    encoder.appendToken(
        "elv",
        environment.edgeLabelVerification() ? "1" : "0");
    var productiveKeys = environment.productiveKeys();
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

  private void visit(Traversal.Admin<?, ?> traversal, HarvestScope parentScope) {
    if (encodeLambda(traversal)) {
      return;
    }
    boolean root = parentScope == null;
    int counted = 0;
    var orderedExpand = new OrderedExpandEvidence();
    // Reuse the existing count pass. This conservative check needs only three local bits and
    // covers both slice placements, including projections and transparent barriers between them.
    for (Step<?, ?> step : traversal.getSteps()) {
      if (!isTransparent(step)) {
        counted++;
      }
      if (step instanceof OrderGlobalStep) {
        globalOrder = true;
      }
      orderedExpand.accept(step);
    }
    boolean scopeOrderedExpand = orderedExpand.present();
    possibleOrderedExpand |= scopeOrderedExpand;
    encoder.appendToken("T", Integer.toString(counted));
    // Track only the facts needed to bind HasSteps. This does not dispatch or build a second
    // MATCH walk. A GraphStep opens the native fold; every other non-HasStep closes it.
    var scope = new HarvestScope(parentScope);
    encoder.deferHarvest(scope);
    boolean folded = false;
    boolean ordered = false;
    boolean sourceSliced = false;
    boolean deferredHop = false;
    boolean pendingDeferred = false;
    var steps = traversal.getSteps();
    boolean initialVertexStart =
        !steps.isEmpty() && steps.getFirst() instanceof GraphStep<?, ?> start
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
        if (root) {
          recordRootEnd();
        }
        continue;
      }
      encoder.appendToken("S", step.getClass().getName());
      if (step instanceof HasStep<?> hasStep) {
        boolean orderedFilter = scopeOrderedExpand && deferredHop && (sourceSliced
            || followedByOrderedSlice(steps, stepIndex));
        encoder.deferHarvest(new HasHarvest(scope, hasStep, initialVertexStart, folded,
            pendingDeferred, orderedFilter, encoder, nativeOperands));
        if (orderedFilter && hasStepHasUnrebuildableOperands(hasStep)) {
          encoder.markIncomplete();
        }
      }
      var labels = GremlinStepLabels.userLabels(step);
      encoder.appendStringSeq("L", labels);
      var recogniser = recognisers.get(step.getClass());
      if (recogniser != null ? !recogniser.contributeShape(step, encoder)
          : strictNativeInputs && !QuerySafetyAdmission.nativeShapeComplete(step, encoder)) {
        encoder.markIncomplete();
      }
      if (!(step instanceof HasStep<?>) && (!pendingDeferred || !isNonSelectingRange(step))) {
        pendingDeferred = false;
      }
      if (step instanceof GraphStep<?, ?>) {
        encoder.deferHarvest(new ResetBoundary(scope, WalkerContext.VERTEX_ROOT_CLASS, null));
        ordered = false;
        sourceSliced = false;
        deferredHop = false;
        folded = true;
      } else if (step instanceof VertexStepContract<?> hop) {
        encoder.deferHarvest(new ResetBoundary(scope, HasBindingContext.afterHopBoundary(),
            hop.returnsEdge() ? hop.getEdgeLabels() : null));
        deferredHop = ordered && !hop.returnsEdge();
        pendingDeferred = deferredHop;
        folded = false;
      } else if (step instanceof EdgeVertexStep || step instanceof EdgeOtherVertexStep) {
        encoder.deferHarvest(new ResetBoundary(scope, HasBindingContext.afterHopBoundary(), null));
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
        // Delimit every child and encode its role. T counts only nontransparent steps, so it
        // cannot locate trailing barriers or their labels relative to the enclosing traversal.
        // This also covers lambda modulators and unregistered TraversalParent implementations.
        for (var child : parent.getLocalChildren()) {
          encoder.appendToken("localChild");
          visit(child.asAdmin(), scope);
          encoder.appendToken("childEnd");
          if (step instanceof org.apache.tinkerpop.gremlin.process.traversal.step.filter.ConnectiveStep<
              ?>) {
            // Arity alone does not locate a boundary between multi-step sibling operands.
            encoder.appendToken("armEnd");
          }
        }
        for (var child : parent.getGlobalChildren()) {
          encoder.appendToken("globalChild");
          visit(child.asAdmin(), scope);
          encoder.appendToken("childEnd");
        }
      }
      if (root) {
        recordRootEnd();
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
