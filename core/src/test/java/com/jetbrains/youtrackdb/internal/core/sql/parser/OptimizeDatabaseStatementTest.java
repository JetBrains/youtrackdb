package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class OptimizeDatabaseStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {

    checkRightSyntax("OPTIMIZE DATABASE");
    checkRightSyntax("optimize database");
    checkRightSyntax("OPTIMIZE DATABASE -lwedges -noverbose");
    checkRightSyntax("OPTIMIZE DATABASE -lwedges");
  }
}
