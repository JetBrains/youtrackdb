package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class AlterDBSequenceStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {

    checkRightSyntax("ALTER SEQUENCE Foo START 100 ");
    checkRightSyntax("alter sequence Foo start 100 ");
    checkRightSyntax("alter sequence Foo increment 4");
    checkRightSyntax("alter sequence Foo cache 5");
    checkRightSyntax("alter sequence Foo start 100 increment 4 cache 5");
    checkRightSyntax("alter sequence Foo START 100 INCREMENT 4 CACHE 5");
    checkRightSyntax("alter sequence Foo START :a INCREMENT :b CACHE :c");

    checkWrongSyntax("alter SEQUENCE Foo TYPE cached");
  }
}
