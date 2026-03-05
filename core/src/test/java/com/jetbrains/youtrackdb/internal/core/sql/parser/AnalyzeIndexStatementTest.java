package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class AnalyzeIndexStatementTest extends ParserTestAbstract {

  @Test
  public void testAnalyzeIndexStar() {
    checkRightSyntax("ANALYZE INDEX *");
  }

  @Test
  public void testAnalyzeIndexNamed() {
    checkRightSyntax("ANALYZE INDEX Foo");
  }

  @Test
  public void testAnalyzeIndexCaseInsensitive() {
    checkRightSyntax("analyze index Foo");
  }

  @Test
  public void testAnalyzeIndexDottedName() {
    checkRightSyntax("ANALYZE INDEX Foo.bar");
  }

  @Test
  public void testAnalyzeIndexMultiDottedName() {
    checkRightSyntax("ANALYZE INDEX Foo.bar.baz");
  }

  @Test
  public void testAnalyzeIndexTrailingTokenError() {
    checkWrongSyntax("ANALYZE INDEX Foo.bar foo");
  }

  @Test
  public void testAnalyzeIndexMissingNameError() {
    checkWrongSyntax("ANALYZE INDEX");
  }
}
