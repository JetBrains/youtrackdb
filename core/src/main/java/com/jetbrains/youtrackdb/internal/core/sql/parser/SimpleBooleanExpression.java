package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.List;

/**
 *
 */
public interface SimpleBooleanExpression {

  /**
   * if the condition involved the current pattern (MATCH statement, eg. $matched.something = foo),
   * returns the name of involved pattern aliases ("something" in this case)
   *
   * @return a list of pattern aliases involved in this condition. Null it does not involve the
   * pattern
   */
  List<String> getMatchPatternInvolvedAliases();
}
