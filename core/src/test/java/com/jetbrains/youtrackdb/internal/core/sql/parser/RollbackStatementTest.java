package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class RollbackStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("ROLLBACK");
    checkRightSyntax("rollback");

    checkWrongSyntax("ROLLBACK RETRY 10");
  }
}
