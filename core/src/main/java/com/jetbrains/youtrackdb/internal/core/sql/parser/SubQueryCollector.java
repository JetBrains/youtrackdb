package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collector used by {@link com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner}
 * to extract inline subqueries from WHERE, projection, ORDER BY, and GROUP BY clauses
 * and rewrite them as LET variables.
 *
 * <p>The actual traversal logic is distributed across AST node classes -- each node
 * that can contain a subquery implements {@code extractSubQueries(SubQueryCollector)}
 * and calls {@link #addStatement(SQLStatement)} when a subquery is found.
 *
 * <h2>Rewriting example</h2>
 * <pre>
 *  Before:
 *    SELECT FROM foo WHERE name IN (SELECT name FROM bar)
 *
 *  After:
 *    LET $$$SUBQUERY$$_0 = (SELECT name FROM bar)
 *    SELECT FROM foo WHERE name IN $$$SUBQUERY$$_0
 * </pre>
 *
 * <p>This rewriting enables the planner to evaluate the subquery once (global LET)
 * or once per record (per-record LET) and reference the result by variable name,
 * rather than re-executing the subquery for each comparison.
 *
 * <p>Synthetic aliases use the prefix {@code $$$SUBQUERY$$_} followed by an
 * incrementing counter. The counter is preserved across {@link #reset()} to avoid
 * alias collisions when multiple extraction passes run in sequence.
 *
 * @see com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner#extractSubQueries
 */
public class SubQueryCollector {

  /** Prefix for generated subquery variable aliases. */
  public static final String GENERATED_ALIAS_PREFIX = "$$$SUBQUERY$$_";

  /** Monotonically increasing counter for unique alias generation. */
  protected int nextAliasId = 0;

  /** Map from generated alias to the extracted subquery statement (preserves insertion order). */
  protected Map<SQLIdentifier, SQLStatement> subQueries = new LinkedHashMap<>();

  /** Generates a fresh unique alias (e.g. {@code $$$SUBQUERY$$_0}, {@code $$$SUBQUERY$$_1}). */
  protected SQLIdentifier getNextAlias() {
    var result = new SQLIdentifier(GENERATED_ALIAS_PREFIX + (nextAliasId++));
    // Mark as internal so this synthetic variable is hidden from user-visible output.
    result.internalAlias = true;
    return result;
  }

  /**
   * Clears collected subqueries but preserves the alias counter ({@link #nextAliasId})
   * so that subsequent calls to {@link #getNextAlias()} produce globally unique aliases
   * within the same planning session.
   */
  public void reset() {
    this.subQueries.clear();
  }

  /**
   * Registers a subquery under the given alias. Returns the alias for use in the rewritten AST.
   */
  public SQLIdentifier addStatement(SQLIdentifier alias, SQLStatement stm) {
    subQueries.put(alias, stm);
    return alias;
  }

  /**
   * Registers a subquery with an auto-generated alias and returns it.
   */
  public SQLIdentifier addStatement(SQLStatement stm) {
    var alias = getNextAlias();
    return addStatement(alias, stm);
  }

  /** Returns all extracted subqueries, keyed by their generated alias (insertion order preserved). */
  public Map<SQLIdentifier, SQLStatement> getSubQueries() {
    return subQueries;
  }
}
