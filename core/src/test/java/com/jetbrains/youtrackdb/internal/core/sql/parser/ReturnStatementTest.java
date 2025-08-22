package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class ReturnStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("RETURN");
    checkRightSyntax("return");
    checkRightSyntax("RETURN 1");
    checkRightSyntax("RETURN [1, 3]");
    checkRightSyntax("RETURN [$a, $b, $c]");
    checkRightSyntax("RETURN (select from foo)");

    checkWrongSyntax("return foo bar");
  }
}
