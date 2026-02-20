package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Data collector populated by AST nodes during recursive aggregate splitting. Used by
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner}
 * to split aggregate projection expressions into three phases (pre-aggregate,
 * aggregate, post-aggregate) so that each can be executed as a separate step.
 *
 * <h2>Splitting example</h2>
 * <pre>
 *  Original:
 *    SELECT max(a + b) + (max(b + c * 2) + 1 + 2) * 3 AS foo, max(d) + max(e), f
 *
 *  Phase 1 - preAggregate (per-row expressions that feed into aggregates):
 *    a + b        AS _$$$OALIAS$$_1
 *    b + c * 2    AS _$$$OALIAS$$_3
 *    d            AS _$$$OALIAS$$_5
 *    e            AS _$$$OALIAS$$_7
 *    f
 *
 *  Phase 2 - aggregate (aggregate accumulators):
 *    max(_$$$OALIAS$$_1) AS _$$$OALIAS$$_0
 *    max(_$$$OALIAS$$_3) AS _$$$OALIAS$$_2
 *    max(_$$$OALIAS$$_5) AS _$$$OALIAS$$_4
 *    max(_$$$OALIAS$$_7) AS _$$$OALIAS$$_6
 *    f
 *
 *  Phase 3 - postAggregate (final expression combining aggregated values):
 *    _$$$OALIAS$$_0 + (_$$$OALIAS$$_2 + 1 + 2) * 3  AS `foo`
 *    _$$$OALIAS$$_4 + _$$$OALIAS$$_6                 AS `max(d) + max(e)`
 *    f
 * </pre>
 *
 * <p>Synthetic aliases use the prefix {@code _$$$OALIAS$$_} followed by an incrementing
 * integer. The counter ({@link #nextAliasId}) is preserved across calls to
 * {@link #reset()} to ensure global uniqueness within a single planning session.
 *
 * @see com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner#splitProjectionsForGroupBy
 */
public class AggregateProjectionSplit {

  /** Prefix for generated internal aliases. */
  protected static final String GENERATED_ALIAS_PREFIX = "_$$$OALIAS$$_";

  /** Monotonically increasing counter for unique alias generation. */
  protected int nextAliasId = 0;

  /** Phase 1 projections: per-row expressions that are inputs to aggregate functions. */
  protected List<SQLProjectionItem> preAggregate = new ArrayList<>();

  /** Phase 2 projections: aggregate function calls over pre-aggregated values. */
  protected List<SQLProjectionItem> aggregate = new ArrayList<>();

  /** Generates a fresh unique alias (e.g. {@code _$$$OALIAS$$_0}, {@code _$$$OALIAS$$_1}). */
  public SQLIdentifier getNextAlias() {
    var result = new SQLIdentifier(GENERATED_ALIAS_PREFIX + (nextAliasId++));
    // Mark as internal so the alias is hidden from user-visible output.
    result.internalAlias = true;
    return result;
  }

  public List<SQLProjectionItem> getPreAggregate() {
    return preAggregate;
  }

  public void setPreAggregate(List<SQLProjectionItem> preAggregate) {
    this.preAggregate = preAggregate;
  }

  public List<SQLProjectionItem> getAggregate() {
    return aggregate;
  }

  public void setAggregate(List<SQLProjectionItem> aggregate) {
    this.aggregate = aggregate;
  }

  /**
   * Clears the pre-aggregate and aggregate lists but preserves the alias counter
   * ({@link #nextAliasId}) so that subsequent calls to {@link #getNextAlias()} produce
   * globally unique aliases within the same planning session.
   */
  public void reset() {
    this.preAggregate.clear();
    this.aggregate.clear();
  }
}
