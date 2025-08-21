package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class ExplainStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax(
        "EXPLAIN SELECT FROM Foo WHERE name = 'bar' and surname in (select surname from Baz)");
    checkRightSyntax(
        "explain SELECT FROM Foo WHERE name = 'bar' and surname in (select surname from Baz)");
  }
}
