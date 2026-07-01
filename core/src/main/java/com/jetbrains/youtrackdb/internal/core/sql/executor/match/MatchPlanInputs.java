package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-built post-parse inputs for {@link MatchExecutionPlanner}, used by non-SQL front-ends
 * that produce a complete MATCH input set without going through the SQL parser.
 *
 * <p>The fields mirror the post-parse state extracted by
 * {@link MatchExecutionPlanner#MatchExecutionPlanner(SQLMatchStatement)} from a parsed AST.
 * The planner's existing planning pipeline then runs unchanged, including its internal
 * call to {@code SelectExecutionPlanner.handleProjectionsBlock} for projection / order /
 * limit / skip / group-by handling.
 *
 * <p>The compact constructor performs <b>null normalisation only</b> &mdash; null collections
 * become {@link Map#of() empty maps} / {@link List#of() empty lists}, and {@code pattern}
 * is required non-null. It does <b>not</b> defensive-copy: the planner constructor that
 * consumes this record performs its own defensive copies on the mutable working maps so
 * the planner can mutate them during planning without affecting the caller's record.
 *
 * <p>{@code returnAliases} and {@code returnNestedProjections} may legitimately contain
 * {@code null} entries (one per return item; null = no alias / no nested projection),
 * so they are stored as-is rather than rejected.
 */
public record MatchPlanInputs(
    Pattern pattern,
    Map<String, String> aliasClasses,
    Map<String, SQLWhereClause> aliasFilters,
    Map<String, SQLRid> aliasRids,
    List<SQLMatchExpression> matchExpressions,
    List<SQLMatchExpression> notMatchExpressions,
    List<SQLExpression> returnItems,
    List<SQLIdentifier> returnAliases,
    List<SQLNestedProjection> returnNestedProjections,
    SQLGroupBy groupBy,
    SQLOrderBy orderBy,
    SQLUnwind unwind,
    SQLLimit limit,
    SQLSkip skip,
    boolean returnDistinct,
    boolean returnElements,
    boolean returnPaths,
    boolean returnPatterns,
    boolean returnPathElements) {

  /** Compact constructor: validates {@code pattern} non-null and normalises null collections. */
  public MatchPlanInputs {
    Objects.requireNonNull(pattern, "pattern must not be null");
    aliasClasses = aliasClasses == null ? Map.of() : aliasClasses;
    aliasFilters = aliasFilters == null ? Map.of() : aliasFilters;
    aliasRids = aliasRids == null ? Map.of() : aliasRids;
    matchExpressions = matchExpressions == null ? List.of() : matchExpressions;
    notMatchExpressions = notMatchExpressions == null ? List.of() : notMatchExpressions;
    returnItems = returnItems == null ? List.of() : returnItems;
    returnAliases = returnAliases == null ? List.of() : returnAliases;
    returnNestedProjections =
        returnNestedProjections == null ? List.of() : returnNestedProjections;
  }
}
