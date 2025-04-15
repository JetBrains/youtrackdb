package com.jetbrains.youtrack.db.internal.core.sql.parser;

import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public abstract class ParserTestAbstract extends DbTestBase {

  protected SimpleNode checkRightSyntax(String query) {
    var result = checkSyntax(query, true);
    var builder = new StringBuilder();
    result.toString(null, builder);
    return checkSyntax(builder.toString(), true);
  }

  protected SimpleNode checkRightSyntaxServer(String query) {
    var result = checkSyntaxServer(query, true);
    var builder = new StringBuilder();
    result.toString(null, builder);
    return checkSyntaxServer(builder.toString(), true);
  }

  protected SimpleNode checkWrongSyntax(String query) {
    return checkSyntax(query, false);
  }

  protected SimpleNode checkWrongSyntaxServer(String query) {
    return checkSyntaxServer(query, false);
  }

  protected SimpleNode checkSyntax(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      SimpleNode result = osql.parse();
      if (!isCorrect) {
        fail();
      }

      return result;
    } catch (Exception e) {
      if (isCorrect) {
        fail();
      }
    }
    return null;
  }

  protected SimpleNode checkSyntaxServer(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      SimpleNode result = osql.parseServerStatement();
      if (!isCorrect) {
        fail();
      }

      return result;
    } catch (Exception e) {
      if (isCorrect) {
        fail();
      }
    }
    return null;
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
