package com.jetbrains.youtrack.db.internal.core.sql.parser;

import org.junit.Test;

public class CreateDatabaseStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntaxServer("CREATE DATABASE foo disk");
    checkRightSyntaxServer("CREATE DATABASE ? disk");
    checkRightSyntaxServer(
        "CREATE DATABASE foo disk {\"config\":{\"security.createDefaultUsers\": true}}");

    checkRightSyntaxServer(
        "CREATE DATABASE foo disk users (foo identified by 'pippo' role admin)");
    checkRightSyntaxServer(
        "CREATE DATABASE foo disk users (foo identified by 'pippo' role admin, reader identified"
            + " by ? role [reader, writer])");

    checkRightSyntaxServer(
        "CREATE DATABASE foo disk users (foo identified by 'pippo' role admin)"
            + " {\"config\":{\"security.createDefaultUsers\": true}}");

    checkWrongSyntax("CREATE DATABASE foo");
    checkWrongSyntax("CREATE DATABASE");
  }
}
