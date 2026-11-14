package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/** Private preparation seam. No strategy installs or executes these candidate plans. */
final class PrivateCandidatePreparation {

  /** A closed admission contract names every admitted prepared root position. */
  record Policy(Set<Integer> cuts) {
    Policy {
      cuts = Set.copyOf(cuts);
    }

  }

  record CandidateKey(String namespace, Policy policy, boolean oneBulk, boolean sourceState,
      long timeout, GremlinShapeKey shape) {
  }

  record Prepared(PrivatePrefixDiscovery.Reason reason, int extent, boolean cacheHit,
      Map<Object, Object> bindings, @Nullable ResultShaping shaping,
      @Nullable InternalExecutionPlan plan) implements AutoCloseable {
    @Override
    public void close() {
      if (plan != null) {
        plan.close();
      }
    }
  }

  private final PrivatePrefixDiscovery discovery;

  PrivateCandidatePreparation(PrivatePrefixDiscovery discovery) {
    this.discovery = discovery;
  }

  Prepared prepare(Traversal.Admin<?, ?> traversal, Policy policy) {
    var safety = QuerySafetyAdmission.prepare(traversal);
    if (safety == null || safety.reason() != QuerySafetyAdmission.Reason.SAFE) {
      return refusal(PrivatePrefixDiscovery.Reason.SAFETY_VETO, false);
    }
    var session = GremlinToMatchStrategy.resolveSessionIfEnabled(traversal);
    if (session == null) {
      return refusal(PrivatePrefixDiscovery.Reason.DISABLED, false);
    }
    var cache = GremlinPlanCache.instance(session);
    long generation = cache.getGeneration();
    var inputs = GremlinStepWalker.WalkInputs.capture(traversal);
    if (inputs.polymorphic() == null || inputs.nulls() == null) {
      return refusal(PrivatePrefixDiscovery.Reason.COMPLETE_NATIVE, false);
    }
    var extraction = GremlinShapeExtractor.extractPartial(GremlinStepWalker.PRODUCTION_RECOGNISERS,
        GremlinStepWalker.TRANSPARENT_STEPS, traversal, session, inputs);
    var key = new CandidateKey("private-candidate-v1", policy, safety.oneBulk(),
        PartialSourceState.configured(traversal), inputs.commandTimeout(), extraction.key());
    boolean eligible = session.getTxSchemaState() == null && cache.isEnabled()
        && extraction.complete();
    GremlinPlanCache.StampedTranslation rejected = null;
    if (eligible) {
      var resident = cache.getTranslationEntry(key, session);
      if (resident != null && cache.getGeneration() == generation
          && resident.template() instanceof GremlinTranslationTemplate.Candidate candidate) {
        var reused = reuse(traversal, session, extraction, candidate, inputs);
        if (reused != null) {
          if (cache.getGeneration() == generation) {
            return reused;
          }
          reused.close();
        }
        rejected = resident;
      }
    }
    var outcome = discovery.discover(traversal, policy.cuts()::contains, session, inputs, true);
    if (outcome.plan() == null) {
      if (eligible && outcome.reason() != PrivatePrefixDiscovery.Reason.PLAN_BUILD_FAILURE) {
        GremlinPlanCache.putTranslation(key,
            new GremlinTranslationTemplate.Candidate(-1, outcome.reason(), null), session,
            generation, rejected);
      }
      return refusal(outcome.reason(), false);
    }
    try (outcome) {
      var translation = outcome.translation();
      var prefix = prefix(traversal, extraction, outcome.cut(), inputs);
      boolean agrees = prefix.bindings().size() == translation.inputParameters().size()
          && GremlinToMatchStrategy.matchingLayout(prefix.hasContributions(),
              translation.hasContributions())
          && prefix.propertyMapBulkingCapacity()
              == translation.shaping().propertyMapBulkingCapacity()
          && OrderedFilterBinding.fromExtraction(translation.shaping(), prefix) != null;
      if (eligible && agrees && translation.cacheEligible() && outcome.plan().canBeCached()) {
        // Never publish the private prepared plan or its bound planning context.
        var template = outcome.plan().copy(new BasicCommandContext(session));
        template.close();
        if (template.canBeCached()) {
          var translate = new GremlinTranslationTemplate.Translate(template,
              translation.boundaryAlias(), translation.outputType(), translation.returnClass(),
              OrderedFilterBinding.unbound(translation.shaping()),
              translation.inputParameters().size(), translation.hasContributions(),
              PlainBoundaryClassifier.classify(outcome.plan(), translation));
          GremlinPlanCache.putTranslation(key, new GremlinTranslationTemplate.Candidate(
              outcome.cut(), outcome.reason(), translate), session, generation, rejected);
        }
      }
      // A publication loser uses its own complete outcome. It never mixes winner fields.
      var context = new BasicCommandContext(session);
      context.setInputParameters(translation.inputParameters());
      return new Prepared(outcome.reason(), outcome.cut(), false, translation.inputParameters(),
          OrderedFilterBinding.fresh(translation.shaping()), outcome.plan().copy(context));
    }
  }

  @Nullable private static Prepared reuse(Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session, GremlinShapeExtractor.Extraction extraction,
      GremlinTranslationTemplate.Candidate candidate, GremlinStepWalker.WalkInputs inputs) {
    var translate = candidate.translation();
    if (translate == null) {
      return refusal(candidate.reason(), true);
    }
    int extent = candidate.extent();
    if (extent <= 0 || extent > traversal.getSteps().size()) {
      return null;
    }
    var rules = new PartialCutRules(traversal.getSteps());
    if (rules.splitsFold(extent) || rules.splitsOrder(extent) || rules.hasIdentifiers(extent)
        || rules.multiPlan(extent)) {
      return null;
    }
    var prefix = prefix(traversal, extraction, extent, inputs);
    if (prefix.bindings().size() != translate.bindingCount()
        || !GremlinToMatchStrategy.matchingLayout(prefix.hasContributions(),
            translate.hasContributions())
        || prefix.propertyMapBulkingCapacity()
            != translate.shaping().propertyMapBulkingCapacity()) {
      return null;
    }
    var shaping = OrderedFilterBinding.fromExtraction(translate.shaping(), prefix);
    if (shaping == null) {
      return null;
    }
    var context = new BasicCommandContext(session);
    context.setInputParameters(prefix.bindings());
    return new Prepared(candidate.reason(), extent, true, prefix.bindings(), shaping,
        translate.planTemplate().copy(context));
  }

  private static GremlinShapeExtractor.Extraction prefix(Traversal.Admin<?, ?> traversal,
      GremlinShapeExtractor.Extraction extraction, int extent,
      GremlinStepWalker.WalkInputs inputs) {
    var steps = traversal.getSteps().subList(0, extent);
    // These checks only read root status and step structure. No suffix clone is needed.
    var orderedExpand = new GremlinShapeExtractor.OrderedExpandEvidence();
    for (var step : steps) {
      orderedExpand.accept(step);
    }
    return extraction.prefix(extent, !orderedExpand.present(),
        PropertyMapBulkingAdmission.capacity(steps, traversal.isRoot(), inputs.permitsGrouping()));
  }

  private static Prepared refusal(PrivatePrefixDiscovery.Reason reason, boolean cacheHit) {
    return new Prepared(reason, -1, cacheHit, Map.of(), null, null);
  }
}
