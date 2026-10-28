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
record DetachedCheckReadSet(List<String> aliases, boolean independent, boolean rememberable,
    boolean correlated) {

  DetachedCheckReadSet(List<String> aliases, boolean independent, boolean rememberable) {
    this(aliases, independent, rememberable, false);
  }

  DetachedCheckReadSet {
    aliases = List.copyOf(aliases);
  }

  static DetachedCheckReadSet describe(SQLMatchExpression expression, Pattern positive) {
    var aliases =
        new LinkedHashSet<>(MatchExecutionPlanner.findSharedAliases(expression, positive));
    boolean independent = collect(expression.getOrigin(), aliases);
    boolean correlatedParameters = false;
    for (var item : expression.getItems()) {
      independent &= collect(item.getFilter(), aliases);
      if (item.getMethod() == null) {
        // Composite paths and field paths carry additional syntax outside this read set.
        independent = false;
      } else {
        independent &= Set.of("out", "in", "both", "outE", "inE", "bothE", "outV", "inV", "bothV")
            .contains(item.getMethod().getMethodNameString());
        for (var parameter : item.getMethod().getParams()) {
          var reads = parameter.getMatchPatternInvolvedAliases();
          if (reads != null) {
            aliases.addAll(reads);
            correlatedParameters |= !reads.isEmpty();
          }
          independent &= recognised(parameter) && !parameter.isBaseIdentifier();
        }
      }
    }
    // An unrecognised alias cannot be supplied by the positive row. Preserve conservative
    // eligibility instead of treating an incomplete read set as an independent build.
    boolean rememberable = independent && positive.aliasToNode.keySet().containsAll(aliases);
    boolean correlated = correlatedParameters || (expression.getOrigin() != null
        && (hasAliasReads(expression.getOrigin().getFilter())
            || hasAliasReads(expression.getOrigin().getWhileCondition())));
    for (var item : expression.getItems()) {
      if (item.getFilter() != null) {
        correlated |= hasAliasReads(item.getFilter().getFilter())
            || hasAliasReads(item.getFilter().getWhileCondition());
      }
    }
    return new DetachedCheckReadSet(List.copyOf(aliases), rememberable && !correlated,
        rememberable, correlated);
  }

  private static boolean hasAliasReads(SQLWhereClause where) {
    if (where == null || where.getBaseExpression() == null) {
      return false;
    }
    var reads = where.getBaseExpression().getMatchPatternInvolvedAliases();
    return reads != null && !reads.isEmpty();
  }

  private static boolean collect(SQLMatchFilter filter, Set<String> aliases) {
    if (filter == null) {
      return true;
    }
    var rid = filter.getRid(null);
    // Expression-backed RID selectors can read hidden context. Only literal pairs are known.
    boolean literalRid = rid == null || rid.toString().matches("#[0-9]+:[0-9]+");
    return literalRid && (collect(filter.getFilter(), aliases)
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
    return !where.refersToParent() && recognised(where.getBaseExpression());
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
    if (base.getModifier() != null && !base.getModifier().isPlainPropertyChain()) {
      return false;
    }
    if (base.getIdentifier() == null) {
      // A base without an identifier or modifier is a literal or a query parameter.
      return true;
    }
    if (!base.getIdentifier().isBaseIdentifier()) {
      var suffix = base.getIdentifier().getSuffix();
      return suffix != null && suffix.getRecordAttribute() != null
          && base.getModifier() == null;
    }
    var name = base.getIdentifier().getSuffix().getIdentifier().getStringValue();
    return !name.startsWith("$") || (name.equals("$matched")
        && base.getModifier() != null && base.getMatchPatternInvolvedAliases() != null
        && !base.getMatchPatternInvolvedAliases().isEmpty());
  }
}
