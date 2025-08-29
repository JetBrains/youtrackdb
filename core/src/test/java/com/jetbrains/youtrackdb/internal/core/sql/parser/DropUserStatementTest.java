package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class DropUserStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("DROP USER test");

    checkWrongSyntax("DROP USER test IDENTIFIED BY 'foo'");
    checkWrongSyntax("DROP USER");
  }
}
