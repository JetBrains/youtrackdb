package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared binding dependencies of a detached check, derived from its condition syntax. */
record DetachedCheckReadSet(List<String> aliases, boolean independent) {

  DetachedCheckReadSet {
    aliases = List.copyOf(aliases);
  }

  static DetachedCheckReadSet describe(SQLMatchExpression expression, Pattern positive) {
    var aliases =
        new LinkedHashSet<>(MatchExecutionPlanner.findSharedAliases(expression, positive));
    boolean independent = collect(expression.getOrigin(), aliases);
    for (var item : expression.getItems()) {
      independent &= collect(item.getFilter(), aliases);
      if (item.getMethod() != null) {
        independent &= item.getMethod().getParams().stream()
            .allMatch(parameter -> recognised(parameter) && !parameter.isBaseIdentifier());
      }
    }
    // An unrecognised alias cannot be supplied by the positive row. Preserve conservative
    // eligibility instead of treating an incomplete read set as an independent build.
    independent &= positive.aliasToNode.keySet().containsAll(aliases);
    return new DetachedCheckReadSet(List.copyOf(aliases), independent);
  }

  private static boolean collect(SQLMatchFilter filter, Set<String> aliases) {
    return filter == null || (collect(filter.getFilter(), aliases)
        & collect(filter.getWhileCondition(), aliases));
  }

  private static boolean collect(SQLWhereClause where, Set<String> aliases) {
    if (where == null || where.getBaseExpression() == null) {
      return true;
    }
    var reads = where.getBaseExpression().getMatchPatternInvolvedAliases();
    if (reads != null) {
      aliases.addAll(reads);
    }
    return !where.refersToParent() && (reads == null || reads.isEmpty())
        && recognised(where.getBaseExpression());
  }

  // Whitelist syntax, not merely the aliases reported by the parser. An empty alias list
  // says nothing about arbitrary context variables or functions that read hidden state.
  private static boolean recognised(SQLBooleanExpression expression) {
    if (expression == SQLBooleanExpression.TRUE || expression == SQLBooleanExpression.FALSE) {
      return true;
    }
    return switch (expression) {
      case SQLAndBlock and ->
          and.getSubBlocks().stream().allMatch(DetachedCheckReadSet::recognised);
      case SQLOrBlock or -> or.getSubBlocks().stream().allMatch(DetachedCheckReadSet::recognised);
      case SQLNotBlock not -> not.getSub() != null && recognised(not.getSub());
      case SQLBinaryCondition binary ->
          recognised(binary.getLeft()) && recognised(binary.getRight());
      default -> false;
    };
  }

  private static boolean recognised(SQLExpression expression) {
    var math = expression.getMathExpression();
    if (math == null) {
      return expression.getArrayConcatExpression() == null && expression.isEarlyCalculated(null);
    }
    if (math.getClass() != SQLBaseExpression.class) {
      return false;
    }
    var base = (SQLBaseExpression) math;
    if (base.getModifier() != null) {
      return false;
    }
    if (base.getIdentifier() == null) {
      // A base without an identifier or modifier is a literal or a query parameter.
      return true;
    }
    if (!base.isBaseIdentifier()) {
      return false;
    }
    return !base.getIdentifier().getSuffix().getIdentifier().getStringValue().startsWith("$");
  }
}
