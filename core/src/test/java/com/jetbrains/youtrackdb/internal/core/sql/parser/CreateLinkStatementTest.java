package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class CreateLinkStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("CREATE LINK comments TYPE LINKSET FROM comment.postId TO post.id");
    checkRightSyntax("CREATE LINK comments TYPE LINKSET FROM comment.postId TO post.id INVERSE");
    checkRightSyntax("CREATE LINK comments TYPE LINKSET FROM comment.postId TO post.@rid");
    checkWrongSyntax("CREATE LINK comments FROM comment.postId TO post.id");
    checkWrongSyntax("CREATE LINK comments TYPE LINKSET FROM comment.postId ");
    checkWrongSyntax("CREATE LINK comments TYPE LINKSET TO post.id");
  }
}
