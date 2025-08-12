package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class ExistsSystemUserStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntaxServer("EXISTS SYSTEM USER test ");
    checkRightSyntaxServer("EXISTS SYSTEM USER ?");
    checkRightSyntaxServer("EXISTS SYSTEM USER :foo");
    checkWrongSyntaxServer("EXISTS SYSTEM USER");
  }
}
