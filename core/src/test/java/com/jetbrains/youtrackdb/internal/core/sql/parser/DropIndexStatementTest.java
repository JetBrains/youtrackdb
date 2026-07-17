package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DropIndexStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("DROP INDEX *");
    checkRightSyntax("DROP INDEX Foo");
    checkRightSyntax("drop index Foo");
    checkRightSyntax("DROP INDEX Foo.bar");
    checkRightSyntax("DROP INDEX Foo.bar.baz");
    checkRightSyntax("DROP INDEX Foo.bar.baz if exists");
    checkRightSyntax("DROP INDEX Foo.bar.baz IF EXISTS");
    checkWrongSyntax("DROP INDEX Foo.bar foo");
  }

  /**
   * CQ-113: {@code copy()}, {@code equals()}, and {@code hashCode()} carry the {@code ifExists}
   * flag — a copy of an IF EXISTS statement equals its original (and prints IF EXISTS), and
   * differs from the plain variant, so a statement cache can never conflate the two.
   */
  @Test
  public void testCopyCarriesIfExists() {
    var withIfExists = (SQLDropIndexStatement) checkRightSyntax("DROP INDEX Foo IF EXISTS");
    var plain = (SQLDropIndexStatement) checkRightSyntax("DROP INDEX Foo");

    var copy = withIfExists.copy();
    assertEquals("the copy must equal its original", withIfExists, copy);
    assertEquals("equal statements must share a hash code",
        withIfExists.hashCode(), copy.hashCode());
    var printed = new StringBuilder();
    copy.toString(null, printed);
    assertTrue("the copy must keep printing IF EXISTS",
        printed.toString().contains("IF EXISTS"));

    assertNotEquals("the IF EXISTS variant must differ from the plain one",
        plain, withIfExists);
  }
}
