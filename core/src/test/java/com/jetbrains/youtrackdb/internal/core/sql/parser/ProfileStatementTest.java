package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class ProfileStatementTest extends ParserTestAbstract {

  @Test
  public void test() {
    checkRightSyntax("profile select from V");
    checkRightSyntax("profile MATCH {as:v, class:V} RETURN $elements");
    checkRightSyntax("profile UPDATE V SET name = 'foo'");
    checkRightSyntax("profile INSERT INTO V SET name = 'foo'");
    checkRightSyntax("profile DELETE FROM Foo");
  }
}
