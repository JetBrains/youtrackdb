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
import javax.annotation.Nonnull;

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
 * <p>{@code returnItems}, {@code returnAliases}, and {@code returnNestedProjections} are
 * <b>parallel</b> lists: entry {@code i} of each describes return item {@code i}, and the planner
 * reads them positionally in lockstep. They must have equal length &mdash; the compact constructor
 * rejects unequal lengths. {@code returnAliases} and {@code returnNestedProjections} may
 * legitimately contain {@code null} entries (null = no alias / no nested projection), so those are
 * stored as-is rather than rejected.
 */
public record MatchPlanInputs(
    @Nonnull Pattern pattern,
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

  /**
   * Compact constructor: validates {@code pattern} non-null, normalises null collections to empty,
   * and requires the three return lists to be parallel (equal length).
   */
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
    // returnItems[i], returnAliases[i], and returnNestedProjections[i] together describe return
    // item i, and the planner reads them positionally in lockstep — one branch iterates
    // returnItems.size(), another iterates returnAliases.size(), and both read .get(i) from all
    // three (MatchExecutionPlanner). Unequal lengths would throw IndexOutOfBoundsException deep in
    // planning, so reject them here with a clear message instead.
    if (returnItems.size() != returnAliases.size()
        || returnItems.size() != returnNestedProjections.size()) {
      throw new IllegalArgumentException(
          "returnItems, returnAliases, and returnNestedProjections must be parallel lists of equal"
              + " length (one entry per return item; use a null entry for a missing alias or nested"
              + " projection), but got sizes "
              + returnItems.size()
              + ", "
              + returnAliases.size()
              + ", "
              + returnNestedProjections.size());
    }
  }

  /**
   * Starts a {@link Builder} for the given (required) {@code pattern}. Every other field
   * defaults to {@code null} / {@code false}; the compact constructor normalises null
   * collections to empty. A front-end sets only the fields its shape actually carries — e.g. a
   * single-node Gremlin {@code g.V()} translation sets the pattern, alias classes/filters/rids,
   * and the return items, leaving the projection, ordering, and return-mode fields at defaults.
   */
  public static Builder builder(Pattern pattern) {
    return new Builder(pattern);
  }

  /**
   * Fluent builder for {@link MatchPlanInputs}. Exists so callers name each field they set
   * instead of threading nineteen positional arguments: a long run of bare {@code null} /
   * {@code false} literals is unreadable, and a transposed value would compile silently and
   * misplan. Unset fields keep their {@code null} / {@code false} defaults, which the compact
   * constructor normalises.
   */
  public static final class Builder {

    private final Pattern pattern;
    private Map<String, String> aliasClasses;
    private Map<String, SQLWhereClause> aliasFilters;
    private Map<String, SQLRid> aliasRids;
    private List<SQLMatchExpression> matchExpressions;
    private List<SQLMatchExpression> notMatchExpressions;
    private List<SQLExpression> returnItems;
    private List<SQLIdentifier> returnAliases;
    private List<SQLNestedProjection> returnNestedProjections;
    private SQLGroupBy groupBy;
    private SQLOrderBy orderBy;
    private SQLUnwind unwind;
    private SQLLimit limit;
    private SQLSkip skip;
    private boolean returnDistinct;
    private boolean returnElements;
    private boolean returnPaths;
    private boolean returnPatterns;
    private boolean returnPathElements;

    private Builder(Pattern pattern) {
      this.pattern = pattern;
    }

    public Builder aliasClasses(Map<String, String> aliasClasses) {
      this.aliasClasses = aliasClasses;
      return this;
    }

    public Builder aliasFilters(Map<String, SQLWhereClause> aliasFilters) {
      this.aliasFilters = aliasFilters;
      return this;
    }

    public Builder aliasRids(Map<String, SQLRid> aliasRids) {
      this.aliasRids = aliasRids;
      return this;
    }

    public Builder matchExpressions(List<SQLMatchExpression> matchExpressions) {
      this.matchExpressions = matchExpressions;
      return this;
    }

    public Builder notMatchExpressions(List<SQLMatchExpression> notMatchExpressions) {
      this.notMatchExpressions = notMatchExpressions;
      return this;
    }

    public Builder returnItems(List<SQLExpression> returnItems) {
      this.returnItems = returnItems;
      return this;
    }

    public Builder returnAliases(List<SQLIdentifier> returnAliases) {
      this.returnAliases = returnAliases;
      return this;
    }

    public Builder returnNestedProjections(List<SQLNestedProjection> returnNestedProjections) {
      this.returnNestedProjections = returnNestedProjections;
      return this;
    }

    public Builder groupBy(SQLGroupBy groupBy) {
      this.groupBy = groupBy;
      return this;
    }

    public Builder orderBy(SQLOrderBy orderBy) {
      this.orderBy = orderBy;
      return this;
    }

    public Builder unwind(SQLUnwind unwind) {
      this.unwind = unwind;
      return this;
    }

    public Builder limit(SQLLimit limit) {
      this.limit = limit;
      return this;
    }

    public Builder skip(SQLSkip skip) {
      this.skip = skip;
      return this;
    }

    public Builder returnDistinct(boolean returnDistinct) {
      this.returnDistinct = returnDistinct;
      return this;
    }

    public Builder returnElements(boolean returnElements) {
      this.returnElements = returnElements;
      return this;
    }

    public Builder returnPaths(boolean returnPaths) {
      this.returnPaths = returnPaths;
      return this;
    }

    public Builder returnPatterns(boolean returnPatterns) {
      this.returnPatterns = returnPatterns;
      return this;
    }

    public Builder returnPathElements(boolean returnPathElements) {
      this.returnPathElements = returnPathElements;
      return this;
    }

    /** Builds the record; the compact constructor validates {@code pattern} and normalises. */
    public MatchPlanInputs build() {
      return new MatchPlanInputs(
          pattern,
          aliasClasses,
          aliasFilters,
          aliasRids,
          matchExpressions,
          notMatchExpressions,
          returnItems,
          returnAliases,
          returnNestedProjections,
          groupBy,
          orderBy,
          unwind,
          limit,
          skip,
          returnDistinct,
          returnElements,
          returnPaths,
          returnPatterns,
          returnPathElements);
    }
  }
}
