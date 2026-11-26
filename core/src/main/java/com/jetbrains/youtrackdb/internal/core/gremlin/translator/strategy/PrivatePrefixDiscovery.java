package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.PrivatePreparationSchemaScope;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/**
 * Private preparation only. No production strategy calls this entry. Admission is an argument,
 * never an option or traversal marker. A selected plan is private and belongs to the caller.
 */
final class PrivatePrefixDiscovery {

  enum Reason {
    DECLARED_SHAPE, DECLARED_DOMAIN, DECLARED_COMPLETION, SELECTED, DISABLED, MISSING_SAFETY, SAFETY_VETO, FULL_SUPPORTED, COMPLETE_NATIVE, CAPABILITY_DECLINE, REPEAT, SOURCE_STATE, CUT_ADMISSION, FOLD_CUT, ORDER_CUT, MULTI_PLAN, RECORD_IDENTIFIERS, PLAIN, PLAN_BUILD_FAILURE
  }

  record Outcome(Reason reason, int cut,
      @Nullable GremlinToMatchTranslator.TranslationResult translation,
      @Nullable InternalExecutionPlan plan,
      @Nullable PlainPlanDecision decision) implements AutoCloseable {
    Outcome(Reason reason, int cut,
        @Nullable GremlinToMatchTranslator.TranslationResult translation,
        @Nullable InternalExecutionPlan plan) {
      this(reason, cut, translation, plan, null);
    }

    @Override
    public void close() {
      if (plan != null) {
        plan.close();
      }
    }
  }

  @FunctionalInterface
  interface Recognition {
    @Nullable GremlinToMatchTranslator.TranslationResult walk(Traversal.Admin<?, ?> traversal,
        GremlinStepWalker.WalkInputs inputs, @Nullable java.util.function.IntConsumer completion);
  }

  private final Recognition recognition;
  private final GremlinToMatchStrategy.MatchPlanBuilder builder;

  PrivatePrefixDiscovery() {
    this(GremlinStepWalker.production()::walkObserved, GremlinToMatchStrategy::buildPlanEntry);
  }

  PrivatePrefixDiscovery(Recognition recognition, GremlinToMatchStrategy.MatchPlanBuilder builder) {
    this.recognition = recognition;
    this.builder = builder;
  }

  Outcome discover(Traversal.Admin<?, ?> traversal, @Nullable QuerySafetyAdmission.Result safety,
      @Nullable IntPredicate admittedCut) {
    if (safety == null || safety != QuerySafetyAdmission.stored(traversal)) {
      return refused(Reason.MISSING_SAFETY);
    }
    if (safety.reason() != QuerySafetyAdmission.Reason.SAFE) {
      return refused(Reason.SAFETY_VETO);
    }
    DatabaseSessionEmbedded session = GremlinToMatchStrategy.resolveSessionIfEnabled(traversal);
    if (session == null) {
      return refused(Reason.DISABLED);
    }
    // Pin before capture and keep the view through recognition, planning and classification.
    try (var scope = PrivatePreparationSchemaScope.open(session)) {
      var outcome = discover(traversal, admittedCut, session,
          GremlinStepWalker.WalkInputs.capture(traversal), false);
      scope.onReleaseFailure(outcome::close);
      return outcome;
    }
  }

  Outcome discover(Traversal.Admin<?, ?> traversal, @Nullable IntPredicate admittedCut,
      DatabaseSessionEmbedded session, GremlinStepWalker.WalkInputs inputs, boolean prepareFull) {
    return discover(traversal, admittedCut, session, inputs, prepareFull, null);
  }

  Outcome discover(Traversal.Admin<?, ?> traversal, @Nullable IntPredicate admittedCut,
      DatabaseSessionEmbedded session, GremlinStepWalker.WalkInputs inputs, boolean prepareFull,
      @Nullable Integer declaredCut) {
    if (PrivatePreparationSchemaScope.schemaFor(session) != inputs.schema()
        || !PrivatePreparationSchemaScope.isActive(session)) {
      throw new IllegalStateException("Captured inputs require their private schema scope");
    }
    GremlinStepWalker.rejectReservedPrefixLabels(traversal.getSteps());
    if (inputs.polymorphic() == null || inputs.nulls() == null || !traversal.isRoot()) {
      return refused(Reason.COMPLETE_NATIVE);
    }
    var completions = new TreeSet<Integer>();
    // Keep the refused step visible. Truncating here would incorrectly settle a deferred hop.
    var full = recognition.walk(traversal, inputs, completions::add);
    if (PartialSourceState.repeat(traversal)) {
      return refused(Reason.REPEAT);
    }
    if (declaredCut != null) {
      return exact(traversal, session, inputs, completions, declaredCut);
    }
    if (full != null && traversal.getSteps().stream().allMatch(RootStepAdmission::admits)) {
      if (!prepareFull) {
        return refused(Reason.FULL_SUPPORTED);
      }
      if (full.isMultiPlan()) {
        return refused(Reason.MULTI_PLAN);
      }
      if (new PartialCutRules(traversal.getSteps()).hasIdentifiers(traversal.getSteps().size())) {
        return refused(Reason.RECORD_IDENTIFIERS);
      }
      return build(session, full, traversal.getSteps().size(), Reason.FULL_SUPPORTED, inputs);
    }
    if (QuerySafetyAdmission.stored(traversal).oneBulk()
        || PartialSourceState.configured(traversal)) {
      return refused(Reason.SOURCE_STATE);
    }
    var rules = new PartialCutRules(traversal.getSteps());
    // Identifier evidence is cumulative. If even the earliest completion carries it, no
    // shorter completed candidate can avoid the native-only refusal. Do not recognise any.
    var earliest = completions.ceiling(1);
    boolean identifiersAtEveryCompletion = earliest != null
        && earliest < traversal.getSteps().size() && rules.hasIdentifiers(earliest);
    Reason reason = Reason.CAPABILITY_DECLINE;
    for (int cut : completions.descendingSet()) {
      if (cut <= 0 || cut >= traversal.getSteps().size()) {
        continue;
      }
      if (rules.splitsFold(cut)) {
        reason = Reason.FOLD_CUT;
        continue;
      }
      if (rules.splitsOrder(cut)) {
        reason = Reason.ORDER_CUT;
        continue;
      }
      if (rules.multiPlan(cut)) {
        reason = Reason.MULTI_PLAN;
        continue;
      }
      if (admittedCut == null || !admittedCut.test(cut)) {
        reason = Reason.CUT_ADMISSION;
        continue;
      }
      if (identifiersAtEveryCompletion) {
        return refused(Reason.RECORD_IDENTIFIERS);
      }
      var prefix = traversal.clone();
      while (prefix.getSteps().size() > cut) {
        prefix.removeStep(prefix.getSteps().size() - 1);
      }
      // No safety preparation on a prefix. It receives fresh walker state and the root's inputs.
      var candidate = recognition.walk(prefix, inputs, null);
      if (candidate == null) {
        reason = Reason.CAPABILITY_DECLINE;
        continue;
      }
      if (rules.hasIdentifiers(cut)) {
        return refused(Reason.RECORD_IDENTIFIERS);
      }
      if (candidate.isMultiPlan()) {
        return refused(Reason.MULTI_PLAN);
      }
      return build(session, candidate, cut, Reason.SELECTED, inputs);
    }
    return refused(reason);
  }

  /**
   * Exact selection uses the applicable declaration as cut-admission evidence, not admittedCut.
   * Only unforced set membership and the plain choice are overridden. All independent protective
   * checks remain mandatory. A refusal is terminal, with no alternate cut or full substitution.
   */
  private Outcome exact(Traversal.Admin<?, ?> traversal, DatabaseSessionEmbedded session,
      GremlinStepWalker.WalkInputs inputs, TreeSet<Integer> completions, int cut) {
    if (cut < 1 || cut >= traversal.getSteps().size()) {
      return refused(Reason.DECLARED_DOMAIN);
    }
    if (QuerySafetyAdmission.stored(traversal).oneBulk()
        || PartialSourceState.configured(traversal)) {
      return refused(Reason.SOURCE_STATE);
    }
    var rules = new PartialCutRules(traversal.getSteps());
    if (rules.splitsFold(cut)) {
      return refused(Reason.FOLD_CUT);
    }
    if (rules.splitsOrder(cut)) {
      return refused(Reason.ORDER_CUT);
    }
    if (rules.multiPlan(cut)) {
      return refused(Reason.MULTI_PLAN);
    }
    if (rules.hasIdentifiers(cut)) {
      return refused(Reason.RECORD_IDENTIFIERS);
    }
    if (!completions.contains(cut)) {
      return refused(Reason.DECLARED_COMPLETION);
    }
    var prefix = traversal.clone();
    while (prefix.getSteps().size() > cut) {
      prefix.removeStep(prefix.getSteps().size() - 1);
    }
    var candidate = recognition.walk(prefix, inputs, null);
    if (candidate == null) {
      return refused(Reason.CAPABILITY_DECLINE);
    }
    if (candidate.isMultiPlan()) {
      return refused(Reason.MULTI_PLAN);
    }
    return build(session, candidate, cut, Reason.SELECTED, inputs, true);
  }

  private Outcome build(DatabaseSessionEmbedded session,
      GremlinToMatchTranslator.TranslationResult candidate, int cut, Reason selected,
      GremlinStepWalker.WalkInputs inputs) {
    return build(session, candidate, cut, selected, inputs, false);
  }

  private Outcome build(DatabaseSessionEmbedded session,
      GremlinToMatchTranslator.TranslationResult candidate, int cut, Reason selected,
      GremlinStepWalker.WalkInputs inputs, boolean overridePlain) {
    InternalExecutionPlan plan;
    try {
      var planned = builder.buildPlanEntry(session, candidate,
          new GremlinToMatchStrategy.CacheScope(0, false, inputs.commandTimeout()));
      // An uncached scope yields an owned plan. A shared template belongs to the cache, so the
      // caller could neither execute nor close it.
      if (planned.sharedPlanTemplate) {
        return refused(Reason.PLAN_BUILD_FAILURE);
      }
      plan = planned.value;
    } catch (RuntimeException failure) {
      return refused(Reason.PLAN_BUILD_FAILURE);
    }
    PlainPlanDecision decision;
    try {
      decision = PlainBoundaryClassifier.classify(plan, candidate);
    } catch (RuntimeException | Error failure) {
      try {
        plan.close();
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    if (decision.plain() && !overridePlain) {
      plan.close();
      return refused(Reason.PLAIN);
    }
    return new Outcome(selected, cut, candidate, plan, decision);
  }

  private static Outcome refused(Reason reason) {
    return new Outcome(reason, -1, null, null);
  }
}
