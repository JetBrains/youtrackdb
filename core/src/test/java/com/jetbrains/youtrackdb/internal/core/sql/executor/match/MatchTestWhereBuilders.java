package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 * Shared WHERE-clause AST builders for MATCH planner unit tests. Tests in this
 * package construct {@link SQLWhereClause} fixtures directly against the
 * planner instead of parsing SQL, to keep the tests co-located with the code
 * under test and to isolate planner behaviour from parser changes.
 */
final class MatchTestWhereBuilders {

  private MatchTestWhereBuilders() {
  }

  /**
   * Builds an AND-wrapped single-condition WHERE clause with the given binary
   * operator and empty-expression operands. Use when the test cares only about
   * the filter's top-level shape (AND with one binary condition of a specific
   * operator), not about the identifier or value on either side.
   */
  static SQLWhereClause makeWhereWithOperator(SQLBinaryCompareOperator op) {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1));
    condition.setOperator(op);
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);
    return where;
  }
}
