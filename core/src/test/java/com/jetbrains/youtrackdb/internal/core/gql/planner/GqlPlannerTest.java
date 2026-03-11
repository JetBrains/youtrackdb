package com.jetbrains.youtrackdb.internal.core.gql.planner;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for GqlPlanner: parse valid MATCH, unsupported query type throws, null query throws,
 * getStatement with null session uses parse path.
 */
public class GqlPlannerTest {

  @Test
  public void parse_validMatch_returnsStatement() {
    var statement = GqlPlanner.parse("MATCH (a:V)");
    Assert.assertNotNull(statement);
  }

  @Test
  public void parse_matchWithEmptyPattern_returnsStatement() {
    var statement = GqlPlanner.parse("MATCH ()");
    Assert.assertNotNull(statement);
  }

  @Test
  public void parse_matchWithMultiplePatterns_returnsStatement() {
    var statement = GqlPlanner.parse("MATCH (a:V), (b:V)");
    Assert.assertNotNull(statement);
  }

  @Test(expected = IllegalArgumentException.class)
  public void parse_unsupportedQueryType_throws() {
    GqlPlanner.parse("LIMIT 1");
  }

  @SuppressWarnings("DataFlowIssue")
  @Test(expected = NullPointerException.class)
  public void parse_nullQuery_throws() {
    GqlPlanner.parse(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void parse_syntaxError_throwsWithMessage() {
    try {
      GqlPlanner.parse("MATCH (a:V WHERE");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().contains("GQL syntax error"));
      throw e;
    }
  }

  @Test
  public void getStatement_withNullSession_callsParse() {
    var statement = GqlPlanner.getStatement("MATCH (n:V)", null);
    Assert.assertNotNull(statement);
  }

  @Test(expected = IllegalArgumentException.class)
  public void parse_syntaxError_includesLineAndPosition() {
    try {
      GqlPlanner.parse("MATCH !!!");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("Should contain 'line'", e.getMessage().contains("line "));
      throw e;
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void parse_emptyQuery_throws() {
    GqlPlanner.parse("");
  }

  @Test
  public void parse_matchOnlyLabel_returnsStatement() {
    var statement = GqlPlanner.parse("MATCH (:Person)");
    Assert.assertNotNull(statement);
  }

  @Test
  public void parse_matchAliasOnly_returnsStatement() {
    var statement = GqlPlanner.parse("MATCH (x)");
    Assert.assertNotNull(statement);
  }
}
