package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class AlterSchemaPropertyStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("ALTER PROPERTY Foo.foo NAME Bar");
    checkRightSyntax("alter property Foo.foo NAME Bar");
    checkRightSyntax("ALTER PROPERTY Foo.foo REGEXP \"[M|F]\"");
    checkRightSyntax("ALTER PROPERTY Foo.foo CUSTOM foo = 'bar'");
    checkRightSyntax("ALTER PROPERTY Foo.foo CUSTOM foo = bar()");
    checkRightSyntax("alter property Foo.foo custom foo = bar()");
  }
}
