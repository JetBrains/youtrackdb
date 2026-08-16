package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.UnionStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ComputerAwareStep;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Recogniser for mid-traversal {@code union(c1, …, cN)}. Uses {@link UnionForkHost} to fork the
 * already-recognised prefix into each global child (stripping the child's trailing {@link
 * ComputerAwareStep.EndStep}), recursively walks each fork to a full single-plan translation, and
 * accepts only when every child agrees on the full projection contract under one canonical boundary
 * alias. A declining child, a projection-contract disagreement, a start-position union, or a nested
 * union inside a child declines the whole walk.
 *
 * <p>After the union the walk may continue for two kinds of suffix step. {@code count()}, {@code
 * limit()} / {@code range()} / {@code skip()} and {@code dedup()} each become a {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp} applied by {@code
 * MultiPlanMatchStep} over the concatenation; {@code unfold()} and {@code reverse()} each append a
 * {@link com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp} the boundary
 * base applies once over that same concatenation, which is sound because both treat each payload
 * alone. {@code order()} after a union declines (no post-concat sort in this cut). The two other
 * terminators decline through different conditions and the difference is worth keeping straight:
 * {@code tail(n)} is on the allow-list and declines through the positional gate, while {@code
 * fold()} is off the allow-list and so declines on membership, before the positional question is
 * asked of it at all. Every other step class declines too. {@code
 * GremlinStepWalker.POST_UNION_RECOGNISERS} argues each membership and the one recorded exclusion.
 *
 * <p>That suffix check runs twice, against one allow-list. {@link
 * UnionForkHost#postUnionSuffixTranslatable} scans the suffix here, before any fork, so an
 * untranslatable suffix costs a look-ahead instead of N discarded child sub-walks per compilation;
 * {@link GremlinStepWalker}'s per-step gate then refuses anything the scan let through and any
 * allow-listed recogniser that declines on its own terms.
 *
 * <h2>A child that shapes a list declines the union</h2>
 *
 * A stage a <em>child</em> registers is a different thing from a stage the suffix registers, and the
 * difference is where the stage ends up running. Both land in one {@link ResultShaping} that the
 * multi-plan boundary applies once over the whole concatenation, so {@code union(__.out().fold(),
 * __.in().fold())} would return one list over both arms where native returns one list per arm — a
 * wrong answer rather than a missing translation. Any child carrying a non-empty {@code
 * listShapingOps()} therefore declines the union.
 *
 * <p>The check is deliberately blanket over all four terminators, although only {@code fold} and
 * {@code tail} actually diverge: {@code unfold} and {@code reverse} treat each payload alone, so
 * once-over-the-concatenation and once-per-arm agree. Telling them apart would need an op-type
 * discriminator on {@code ListShapingOp}, which the carrier does not have, and {@code
 * union(__.unfold(), __.unfold())} is coverage lost rather than a wrong answer shipped.
 *
 * <p>The gate reads {@code listShapingOps()} and nothing else, which is narrower than the property
 * it rests on. {@code accumulateMap} — the {@link ResultShaping} component a {@code group()} or
 * {@code groupCount()} arm sets — has that same property: the boundary base drains the whole
 * concatenation into one map before any list-shaping op runs, so {@code
 * union(__.out(k).groupCount(), __.in(k).groupCount())} still merges both arms into one map where
 * native returns one per arm. That shape predates this gate and is not closed by it; widening the
 * condition to the stream-level components of {@code ResultShaping} is the fix when it is taken up.
 * The projection-contract comparison below does not catch it either — two {@code
 * withAccumulateMap(true)} records compare equal, unlike the per-recognition op instances the next
 * paragraph turns on.
 *
 * <p>It is also deliberately separate from, and ahead of, the projection-contract comparison below,
 * which would decline these shapes today only by accident. That comparison asks whether the children
 * <em>agree</em>, and {@link ResultShaping} is a record whose {@code equals} compares {@code
 * listShapingOps} element-wise: the recognisers each append a fresh op instance per recognition, so
 * two arms folding identically compare unequal and decline. Rewrite any one of those recognisers to
 * append a shared singleton — this codebase's usual style for a stateless op, and what {@code
 * PostConcatOp.Count.INSTANCE} already is — and the arms would agree, the comparison would pass, and
 * the wrong answer would ship. The explicit gate is what makes the decline independent of that.
 *
 * <p>The recogniser never sees the parent {@code Traversal.Admin} — only the narrow {@link
 * UnionForkHost} seam. On accept it stashes the ordered child {@link MatchPlanInputs} through that
 * host; {@link GremlinStepWalker} then emits a multi-plan {@link
 * GremlinToMatchTranslator.TranslationResult} so the strategy splices a {@code MultiPlanMatchStep}.
 */
final class UnionStepRecogniser implements StepRecogniser {

  static final UnionStepRecogniser INSTANCE = new UnionStepRecogniser();

  private UnionStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var head = cursor.take();
    if (!(head instanceof UnionStep<?, ?> union)) {
      return Outcome.DECLINE;
    }
    // Union is only recognised after a vertex prefix has pinned the boundary. Start-position
    // g.union(...) never pins one, so decline here as well as at the strategy's vertex-start gate.
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var host = ctx.unionForkHost();
    if (host == null) {
      return Outcome.DECLINE;
    }
    // Cheapest gate first. Everything below forks the prefix into each arm and runs a complete
    // sub-walk per arm, and a suffix the walker will not claim post-union declines the traversal
    // regardless — so pay the O(suffix) look-ahead rather than N discarded sub-walks per
    // compilation. The look-ahead reads the walker's own post-union allow-list, so it declines
    // exactly the suffixes the walker's per-step gate declines.
    if (!host.postUnionSuffixTranslatable()) {
      return Outcome.DECLINE;
    }

    var prefix = host.recognisedPrefixSteps();
    if (prefix.isEmpty()) {
      return Outcome.DECLINE;
    }

    var globalChildren = union.getGlobalChildren();
    if (globalChildren.isEmpty()) {
      return Outcome.DECLINE;
    }

    var childInputs = new ArrayList<MatchPlanInputs>(globalChildren.size());
    var childParams = new ArrayList<Map<Object, Object>>(globalChildren.size());
    var childCacheEligible = new ArrayList<Boolean>(globalChildren.size());
    String canonicalAlias = null;
    BoundaryOutputType agreedOutputType = null;
    Class<? extends Element> agreedReturnClass = null;
    ResultShaping agreedShaping = null;

    for (Traversal.Admin<?, ?> child : globalChildren) {
      if (containsNestedUnion(child)) {
        return Outcome.DECLINE;
      }
      var childResult = host.walkFork(childSuffixWithoutEnd(child));
      if (childResult == null || childResult.isMultiPlan()) {
        return Outcome.DECLINE;
      }
      assert childResult.inputs() != null;
      // A child that registered a list-shaping stage declines the union outright, before and
      // independently of the contract comparison below — see the class javadoc's "A child that
      // shapes a list declines the union" for why agreement is not the question here.
      //
      // Scope: this reads listShapingOps only. accumulateMap has the same property — one shaping
      // applied once over the concatenation — so a group / groupCount arm still merges both arms
      // into one map where native returns one per arm. That predates this gate; the class javadoc's
      // same section records it and names widening this condition as the fix.
      if (!childResult.shaping().listShapingOps().isEmpty()) {
        return Outcome.DECLINE;
      }

      if (canonicalAlias == null) {
        canonicalAlias = childResult.boundaryAlias();
        agreedOutputType = childResult.outputType();
        agreedReturnClass = childResult.returnClass();
        agreedShaping = childResult.shaping();
      } else if (!agreedOutputType.equals(childResult.outputType())
          || !agreedReturnClass.equals(childResult.returnClass())
          || !agreedShaping.equals(childResult.shaping())) {
        // Full projection-contract disagreement — enum-only agreement would mistranslate rows.
        return Outcome.DECLINE;
      }

      childInputs.add(
          rewriteReturnAlias(childResult.inputs(), childResult.boundaryAlias(), canonicalAlias));
      childParams.add(childResult.inputParameters());
      // Preserve each fork's RID-bearing / cache decision; children cache under their own
      // fingerprints, the multi-plan carrier does not.
      childCacheEligible.add(childResult.cacheEligible());
    }

    // Re-pin the parent walk to the children's agreed contract. The prefix-only pattern on this
    // context is discarded by buildResult when the union carrier is present.
    ctx.pinBoundary(canonicalAlias, agreedOutputType, agreedReturnClass);
    ctx.setResultShaping(agreedShaping);
    host.stashAcceptedChildren(childInputs, childParams, childCacheEligible);
    // Post-concat barriers (count / limit / dedup) may follow; every other suffix step declines
    // through the walker's post-union suffix gate.
    return Outcome.ACCEPTED;
  }

  /** True when any step of {@code child} is a {@link UnionStep}. */
  private static boolean containsNestedUnion(Traversal.Admin<?, ?> child) {
    for (Object raw : child.getSteps()) {
      if (raw instanceof UnionStep<?, ?>) {
        return true;
      }
    }
    return false;
  }

  /** Child steps with trailing {@link ComputerAwareStep.EndStep} instances stripped. */
  private static List<Step<?, ?>> childSuffixWithoutEnd(Traversal.Admin<?, ?> child) {
    var suffix = new ArrayList<Step<?, ?>>();
    for (Object raw : child.getSteps()) {
      var step = (Step<?, ?>) raw;
      if (step instanceof ComputerAwareStep.EndStep) {
        continue;
      }
      suffix.add(step);
    }
    return List.copyOf(suffix);
  }

  /**
   * Rewrites RETURN {@code AS} aliases that equal {@code fromAlias} to {@code toAlias}, so every
   * child plan emits the canonical boundary column the multi-plan boundary step projects. Pattern
   * node identifiers in {@code returnItems} stay on the child's own aliases — only the Result column
   * name must agree.
   */
  static MatchPlanInputs rewriteReturnAlias(
      MatchPlanInputs inputs, String fromAlias, String toAlias) {
    if (fromAlias.equals(toAlias)) {
      return inputs;
    }
    var rewritten = new ArrayList<SQLIdentifier>(inputs.returnAliases().size());
    boolean changed = false;
    for (SQLIdentifier alias : inputs.returnAliases()) {
      if (alias != null && fromAlias.equals(alias.getStringValue())) {
        rewritten.add(MatchProjectionBuilder.columnAlias(toAlias));
        changed = true;
      } else {
        rewritten.add(alias);
      }
    }
    if (!changed) {
      return inputs;
    }
    return MatchPlanInputs.builder(inputs.pattern())
        .aliasClasses(inputs.aliasClasses())
        .aliasFilters(inputs.aliasFilters())
        .matchExpressions(inputs.matchExpressions())
        .notMatchExpressions(inputs.notMatchExpressions())
        .returnItems(inputs.returnItems())
        .returnAliases(rewritten)
        .returnNestedProjections(inputs.returnNestedProjections())
        .groupBy(inputs.groupBy())
        .orderBy(inputs.orderBy())
        .unwind(inputs.unwind())
        .limit(inputs.limit())
        .skip(inputs.skip())
        .returnDistinct(inputs.returnDistinct())
        .returnElements(inputs.returnElements())
        .returnPaths(inputs.returnPaths())
        .returnPatterns(inputs.returnPatterns())
        .returnPathElements(inputs.returnPathElements())
        .build();
  }
}
