package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class CreateUserStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("CREATE USER test IDENTIFIED BY foo");
    checkRightSyntax("CREATE USER test IDENTIFIED BY 'foo'");
    checkRightSyntax("CREATE USER test IDENTIFIED BY 'foo' ROLE admin");
    checkRightSyntax("CREATE USER test IDENTIFIED BY 'foo' ROLE [admin, reader]");
    checkRightSyntax("create user test identified by 'foo' role [admin, reader]");
    checkWrongSyntax("CREATE USER test IDENTIFIED BY 'foo' role admin, reader");
    checkWrongSyntax("CREATE USER test");
  }
}
