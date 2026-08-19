package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Shared helpers for post-union reductions: rewrite a child {@link MatchPlanInputs} into a bare
 * {@code RETURN count(*)} plan (push-down for lone {@link PostConcatOp.Count}).
 */
final class PostConcatSupport {

  private PostConcatSupport() {
  }

  /**
   * Same RETURN shape {@link GremlinAggregateAssembler#configureCount} pins on a single-plan walk:
   * one {@code count(*)} column, no GROUP BY / DISTINCT / ORDER / SKIP / LIMIT on the child.
   */
  static @Nonnull MatchPlanInputs rewriteToCountStar(@Nonnull MatchPlanInputs inputs) {
    List<SQLIdentifier> aliases = new ArrayList<>(1);
    aliases.add(null);
    List<SQLNestedProjection> nested = new ArrayList<>(1);
    nested.add(null);
    return MatchPlanInputs.builder(inputs.pattern())
        .aliasClasses(inputs.aliasClasses())
        .aliasFilters(inputs.aliasFilters())
        .matchExpressions(inputs.matchExpressions())
        .notMatchExpressions(inputs.notMatchExpressions())
        .returnItems(List.of(MatchProjectionBuilder.countStar()))
        .returnAliases(aliases)
        .returnNestedProjections(nested)
        .groupBy(null)
        .orderBy(null)
        .unwind(null)
        .limit(null)
        .skip(null)
        .returnDistinct(false)
        .returnElements(false)
        .returnPaths(false)
        .returnPatterns(false)
        .returnPathElements(false)
        .build();
  }

  static boolean isPushDownCountOnly(@Nonnull List<PostConcatOp> ops) {
    return PostConcatOp.isPushDownCountOnly(ops);
  }
}
