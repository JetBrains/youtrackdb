package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.AbstractLambdaTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ColumnTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.LoopTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.PredicateTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TrueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.ComparatorHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.LambdaHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.Mutating;
import org.apache.tinkerpop.gremlin.process.traversal.step.SideEffectCapable;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.Writing;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.ChooseStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.LocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.OptionalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.IsStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CallStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.RequirementsStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/** One eager safety walk per root. Capability and repeat admission keep their own scopes. */
final class QuerySafetyAdmission {

  enum Reason {
    SAFE, WRITE, SIDE_EFFECT, USER_FUNCTION, UNKNOWN_BEHAVIOR, DUPLICATE_LABEL, SIDE_EFFECT_NAME, ONE_BULK_CHILD, MISSING_ROOT_RESULT, ADMISSION_FAILURE
  }

  record Result(Reason reason, boolean oneBulk, int visitedSteps) {
    Reason forChild() {
      return reason != Reason.SAFE ? reason : oneBulk ? Reason.ONE_BULK_CHILD : Reason.SAFE;
    }
  }

  /** A traversal-local view leaves the captured strategy iteration and global list unchanged. */
  static final class Evidence
      implements org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies {
    final org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies delegate;
    final Result result;

    Evidence(org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies delegate,
        Result result) {
      this.delegate = delegate;
      this.result = result;
    }

    @Override
    public java.util.Iterator<TraversalStrategy<?>> iterator() {
      return delegate.iterator();
    }

    @Override
    public List<TraversalStrategy<?>> toList() {
      return delegate.toList();
    }

    @Override
    public <T extends TraversalStrategy> java.util.Optional<T> getStrategy(Class<T> type) {
      return delegate.getStrategy(type);
    }

    @Override
    public org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies addStrategies(
        TraversalStrategy<?>... strategies) {
      delegate.addStrategies(strategies);
      return this;
    }

    @SafeVarargs
    @Override
    public final org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
        removeStrategies(
            Class<? extends TraversalStrategy>... types) {
      delegate.removeStrategies(types);
      return this;
    }

    @Override
    public Evidence clone() {
      return new Evidence(delegate.clone(), result);
    }
  }

  static Result stored(Traversal.Admin<?, ?> traversal) {
    return YTDBStrategyUtil.rootTraversal(traversal).getStrategies() instanceof Evidence evidence
        ? evidence.result : null;
  }

  static Result prepare(Traversal.Admin<?, ?> traversal) {
    var existing = stored(traversal);
    if (existing != null || !traversal.isRoot()) {
      return existing;
    }
    Result result = new Result(Reason.ADMISSION_FAILURE, false, 0);
    try {
      var scan = new Scan(traversal.getSideEffects().keys());
      scan.visit(traversal);
      result = new Result(scan.reason, scan.oneBulk, scan.visited);
      return result;
    } finally {
      traversal.setStrategies(new Evidence(traversal.getStrategies(), result));
    }
  }

  private static final Set<Class<?>> PURE_EXTRA = Set.of(InjectStep.class, IdentityStep.class,
      RepeatStep.class, RepeatStep.RepeatEndStep.class, LocalStep.class, OptionalStep.class,
      ChooseStep.class, RequirementsStep.class, IsStep.class,
      org.apache.tinkerpop.gremlin.process.traversal.step.map.TraversalMapStep.class,
      org.apache.tinkerpop.gremlin.process.traversal.step.map.TraversalFlatMapStep.class,
      org.apache.tinkerpop.gremlin.process.traversal.step.util.ComputerAwareStep.EndStep.class);
  private static final Set<Class<?>> PURE_LAMBDA = Set.of(ValueTraversal.class,
      TokenTraversal.class,
      IdentityTraversal.class, ColumnTraversal.class, ConstantTraversal.class, TrueTraversal.class,
      LoopTraversal.class, RecordIdSortKeyTraversal.class);

  private static final class Scan {
    final Set<String> sideEffects;
    final Set<String> labels = new HashSet<>();
    Reason reason = Reason.SAFE;
    boolean oneBulk;
    int visited;

    Scan(Set<String> sideEffects) {
      this.sideEffects = sideEffects;
    }

    void refuse(Reason candidate) {
      if (reason == Reason.SAFE) {
        reason = candidate;
      }
    }

    void visit(Traversal.Admin<?, ?> traversal) {
      if (traversal instanceof AbstractLambdaTraversal<?, ?> lambda) {
        if (lambda instanceof PredicateTraversal<?> predicate) {
          if (!(predicate.getPredicate() instanceof P<?> p) || !purePredicate(p)) {
            refuse(Reason.USER_FUNCTION);
          }
        } else if (!PURE_LAMBDA.contains(lambda.getClass())) {
          refuse(Reason.UNKNOWN_BEHAVIOR);
        }
        // ValueTraversal may hide a productive-by traversal, including executable steps.
        if (lambda.getBypassTraversal() != null) {
          visit(lambda.getBypassTraversal());
        }
        return;
      }
      for (Step<?, ?> step : traversal.getSteps()) {
        visited++;
        // Keep lexical errors observable even if an earlier step already vetoed the query.
        GremlinStepWalker.rejectReservedPrefixLabels(List.of(step));
        for (String label : GremlinStepLabels.userLabels(step)) {
          if (!labels.add(label)) {
            refuse(Reason.DUPLICATE_LABEL);
          }
        }
        if ((step instanceof SelectOneStep<?, ?> select
            && select.getScopeKeys().stream().anyMatch(sideEffects::contains))
            || (step instanceof SelectStep<?, ?> selectMany
                && selectMany.getSelectKeys().stream().anyMatch(sideEffects::contains))) {
          refuse(Reason.SIDE_EFFECT_NAME);
        }
        if (step instanceof RequirementsStep<?> requirements) {
          oneBulk |= requirements.getRequirements().contains(TraverserRequirement.ONE_BULK);
        }
        inspect(step);
        if (step instanceof TraversalParent parent) {
          parent.getLocalChildren().forEach(this::visit);
          parent.getGlobalChildren().forEach(this::visit);
        }
      }
    }

    void inspect(Step<?, ?> step) {
      if (step instanceof Mutating<?> || step instanceof Writing<?>) {
        refuse(Reason.WRITE);
      } else if (step instanceof CallStepContract<?, ?>
          || step instanceof SideEffectCapable
          || step instanceof SideEffectBarrierStep<?>
          || (step instanceof SideEffectStep<?> && step.getClass() != IdentityStep.class)) {
        // Step kind only. In particular, do not resolve a CallStep service.
        refuse(Reason.SIDE_EFFECT);
      } else if (step instanceof LambdaHolder) {
        refuse(Reason.USER_FUNCTION);
      } else if (!GremlinStepWalker.PRODUCTION_RECOGNISERS.containsKey(step.getClass())
          && !GremlinStepWalker.TRANSPARENT_STEPS.contains(step.getClass())
          && step.getClass() != EdgeHopRecogniser.CONSUMED_CLOSING_STEP
          && !PURE_EXTRA.contains(step.getClass())) {
        refuse(Reason.UNKNOWN_BEHAVIOR);
      }
      if (step instanceof FoldStep<?, ?> fold && !fold.isDefaultFold()) {
        refuse(Reason.USER_FUNCTION);
      }
      if (step instanceof HasContainerHolder<?, ?> holder) {
        holder.getHasContainers().forEach(c -> checkPredicate(c.getPredicate()));
      }
      if (step instanceof IsStep<?> is) {
        checkPredicate(is.getPredicate());
      }
      if (step instanceof WherePredicateStep<?> where) {
        where.getPredicate().ifPresent(this::checkPredicate);
      }
      if (step instanceof ComparatorHolder<?, ?> holder) {
        for (var pair : holder.getComparators()) {
          if (!((Object) pair.getValue1() instanceof Order)) {
            refuse(Reason.USER_FUNCTION);
          }
        }
      }
    }

    void checkPredicate(P<?> predicate) {
      if (!purePredicate(predicate)) {
        refuse(Reason.USER_FUNCTION);
      }
    }
  }

  private static boolean purePredicate(P<?> predicate) {
    if (predicate.getClass() == NotP.class) {
      return purePredicate(predicate.negate());
    }
    if (predicate.getClass() == AndP.class) {
      return ((AndP<?>) predicate).getPredicates().stream()
          .allMatch(QuerySafetyAdmission::purePredicate);
    }
    if (predicate.getClass() == OrP.class) {
      return ((OrP<?>) predicate).getPredicates().stream()
          .allMatch(QuerySafetyAdmission::purePredicate);
    }
    if (predicate.getClass() != P.class
        && predicate.getClass() != org.apache.tinkerpop.gremlin.process.traversal.TextP.class) {
      return false;
    }
    var operator = predicate.getBiPredicate();
    return operator instanceof Compare || operator instanceof Contains || operator instanceof Text
        || (operator != null && operator.getClass() == Text.RegexPredicate.class);
  }

  private QuerySafetyAdmission() {
  }
}
