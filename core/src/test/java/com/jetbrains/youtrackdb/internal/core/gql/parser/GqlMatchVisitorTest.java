package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLLexer;
import com.jetbrains.youtrackdb.internal.core.gql.parser.gen.GQLParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.Assert;
import org.junit.Test;

public class GqlMatchVisitorTest {

  private GqlMatchVisitor visitMatch(String gql) {
    var lexer = new GQLLexer(CharStreams.fromString(gql));
    var tokens = new CommonTokenStream(lexer);
    var parser = new GQLParser(tokens);

    var routerVisitor = new GqlQueryVisitor();
    routerVisitor.visit(parser.graph_query());
    Assert.assertTrue("Should be a MATCH query", routerVisitor.hasMatch());

    var matchVisitor = new GqlMatchVisitor();
    matchVisitor.visit(routerVisitor.getMatchContext());
    return matchVisitor;
  }

  @Test
  public void visitNodePattern_aliasAndLabel_extractsBoth() {
    var visitor = visitMatch("MATCH (a:Person)");
    var filters = visitor.getMatchFilters();
    Assert.assertEquals(1, filters.size());
    Assert.assertEquals("a", filters.getFirst().getAlias());
    Assert.assertEquals("Person", filters.getFirst().getClassName(null));
  }

  @Test
  public void visitNodePattern_labelOnly_aliasIsNull() {
    var visitor = visitMatch("MATCH (:Person)");
    var filters = visitor.getMatchFilters();
    Assert.assertEquals(1, filters.size());
    Assert.assertNull(filters.getFirst().getAlias());
    Assert.assertEquals("Person", filters.getFirst().getClassName(null));
  }

  @Test
  public void visitNodePattern_aliasOnly_labelIsNull() {
    var visitor = visitMatch("MATCH (x)");
    var filters = visitor.getMatchFilters();
    Assert.assertEquals(1, filters.size());
    Assert.assertEquals("x", filters.getFirst().getAlias());
    Assert.assertNull(filters.getFirst().getClassName(null));
  }

  @Test
  public void visitNodePattern_emptyParens_bothNull() {
    var visitor = visitMatch("MATCH ()");
    var filters = visitor.getMatchFilters();
    Assert.assertEquals(1, filters.size());
    Assert.assertNull(filters.getFirst().getAlias());
    Assert.assertNull(filters.getFirst().getClassName(null));
  }

  @Test
  public void visitNodePattern_multiplePatterns_allExtracted() {
    var visitor = visitMatch("MATCH (a:Person), (b:City)");
    var filters = visitor.getMatchFilters();
    Assert.assertEquals(2, filters.size());
    Assert.assertEquals("a", filters.get(0).getAlias());
    Assert.assertEquals("Person", filters.get(0).getClassName(null));
    Assert.assertEquals("b", filters.get(1).getAlias());
    Assert.assertEquals("City", filters.get(1).getClassName(null));
  }

  @Test
  public void getMatchFilters_initiallyEmpty() {
    var visitor = new GqlMatchVisitor();
    Assert.assertTrue(visitor.getMatchFilters().isEmpty());
  }
}
