package com.jetbrains.youtrack.db.internal.core.sql.parser;

import org.junit.Test;

public class AlterDatabaseStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("ALTER DATABASE COLLECTION_SELECTION 'default'");
    checkRightSyntax("alter database COLLECTION_SELECTION 'default'");

    checkWrongSyntax("alter database ");
    checkWrongSyntax("alter database bar baz zz");
  }
}
