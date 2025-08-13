package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class DropDatabaseStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntaxServer("DROP DATABASE foo");
    checkRightSyntaxServer("DROP DATABASE foo if exists");

    checkWrongSyntax("DROP DATABASE");
    checkWrongSyntax("DROP DATABASE if exists");
  }
}
