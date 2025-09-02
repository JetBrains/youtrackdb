package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class CreateClassStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("CREATE CLASS Foo");
    checkRightSyntax("create class Foo");
    checkRightSyntax("create class Foo extends bar, baz abstract");
    checkRightSyntax("CREATE CLASS Foo EXTENDS bar, baz ABSTRACT");

    checkWrongSyntax("CREATE CLASS Foo EXTENDS ");
  }

  @Test
  public void testIfNotExists() {
    checkRightSyntax("CREATE CLASS Foo if not exists");
    checkRightSyntax("CREATE CLASS Foo IF NOT EXISTS");
    checkRightSyntax("CREATE CLASS Foo if not exists extends V");

    checkWrongSyntax("CREATE CLASS Foo if");
    checkWrongSyntax("CREATE CLASS Foo if not");
  }
}
