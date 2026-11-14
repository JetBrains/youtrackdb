package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
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
    SELECTED, DISABLED, MISSING_SAFETY, SAFETY_VETO, FULL_SUPPORTED, COMPLETE_NATIVE, CAPABILITY_DECLINE, REPEAT, SOURCE_STATE, CUT_ADMISSION, FOLD_CUT, ORDER_CUT, MULTI_PLAN, RECORD_IDENTIFIERS, PLAIN, PLAN_BUILD_FAILURE
  }

  record Outcome(Reason reason, int cut,
      @Nullable GremlinToMatchTranslator.TranslationResult translation,
      @Nullable InternalExecutionPlan plan) implements AutoCloseable {
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
    this(GremlinStepWalker.production()::walkObserved, GremlinToMatchStrategy::buildPlan);
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
    // Resolve deliberate option errors and all strategy-derived walk inputs once, on the root.
    return discover(traversal, admittedCut, session,
        GremlinStepWalker.WalkInputs.capture(traversal), false);
  }

  Outcome discover(Traversal.Admin<?, ?> traversal, @Nullable IntPredicate admittedCut,
      DatabaseSessionEmbedded session, GremlinStepWalker.WalkInputs inputs, boolean prepareFull) {
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

  private Outcome build(DatabaseSessionEmbedded session,
      GremlinToMatchTranslator.TranslationResult candidate, int cut, Reason selected,
      GremlinStepWalker.WalkInputs inputs) {
    InternalExecutionPlan plan;
    try {
      plan = builder.buildPlan(session, candidate,
          new GremlinToMatchStrategy.CacheScope(0, false, inputs.commandTimeout()));
    } catch (RuntimeException failure) {
      return refused(Reason.PLAN_BUILD_FAILURE);
    }
    boolean plain;
    try {
      plain = PlainBoundaryClassifier.classify(plan, candidate).plain();
    } catch (RuntimeException | Error failure) {
      try {
        plan.close();
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    if (plain) {
      plan.close();
      return refused(Reason.PLAIN);
    }
    return new Outcome(selected, cut, candidate, plan);
  }

  private static Outcome refused(Reason reason) {
    return new Outcome(reason, -1, null, null);
  }
}
