package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
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
 * <p>After the union the walk may continue for the post-concatenation barriers only — {@code
 * count()}, {@code limit()} / {@code range()} / {@code skip()}, and {@code dedup()} — each of which
 * becomes a {@link com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp}
 * applied by {@code MultiPlanMatchStep} over the concatenation. {@code order()} after a union
 * declines (no post-concat sort in this cut), the list-shaping terminators ({@code fold} and
 * friends) are not translated yet, and every other step class declines too.
 *
 * <p>That suffix check runs twice, against one allow-list. {@link
 * UnionForkHost#postUnionSuffixTranslatable} scans the suffix here, before any fork, so an
 * untranslatable suffix costs a look-ahead instead of N discarded child sub-walks per compilation;
 * {@link GremlinStepWalker}'s per-step gate then refuses anything the scan let through and any
 * allow-listed recogniser that declines on its own terms.
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
        rewritten.add(new SQLIdentifier(toAlias));
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
