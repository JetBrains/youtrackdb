package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Test;

public class MatchStatementTest {

  protected SimpleNode checkRightSyntax(String query) {
    var result = checkSyntax(query, true);
    var builder = new StringBuilder();
    result.toString(null, builder);
    return checkSyntax(builder.toString(), true);
  }

  protected SimpleNode checkWrongSyntax(String query) {
    return checkSyntax(query, false);
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
        e.printStackTrace();
        fail();
      }
    }
    return null;
  }

  @Test
  public void testWrongFilterKey() {
    checkWrongSyntax("MATCH {clasx: 'V'} RETURN foo");
  }

  @Test
  public void testBasicMatch() {
    checkRightSyntax("MATCH {class: 'V', as: foo} RETURN foo");
  }

  @Test
  public void testNoReturn() {
    checkWrongSyntax("MATCH {class: 'V', as: foo}");
  }

  @Test
  public void testSingleMethod() {
    checkRightSyntax("MATCH {class: 'V', as: foo}.out() RETURN foo");
  }

  @Test
  public void testArrowsNoBrackets() {
    checkWrongSyntax("MATCH {}-->-->{as:foo} RETURN foo");
  }

  @Test
  public void testSingleMethodAndFilter() {
    checkRightSyntax("MATCH {class: 'V', as: foo}.out(){class: 'V', as: bar} RETURN foo");
    checkRightSyntax("MATCH {class: 'V', as: foo}-E->{class: 'V', as: bar} RETURN foo");
    checkRightSyntax("MATCH {class: 'V', as: foo}-->{class: 'V', as: bar} RETURN foo");
  }

  @Test
  public void testLongPath() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.out().in('foo').both('bar').out(){as: bar} RETURN foo");

    checkRightSyntax("MATCH {class: 'V', as: foo}-->{}<-foo-{}-bar-{}-->{as: bar} RETURN foo");
  }

  @Test
  public void testLongPath2() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.out().in('foo'){}.both('bar'){CLASS: 'bar'}.out(){as: bar}"
            + " RETURN foo");
  }

  @Test
  public void testFilterTypes() {
    var query =
        "MATCH {"
            + "   class: 'v', "
            + "   as: foo, "
            + "   where: (name = 'foo' and surname = 'bar' or aaa in [1,2,3]), "
            + "   maxDepth: 10 "
            + "} return foo";
    checkRightSyntax(query);
  }

  @Test
  public void testFilterTypes2() {
    var query =
        "MATCH {"
            + "   classes: ['V', 'E'], "
            + "   as: foo, "
            + "   where: (name = 'foo' and surname = 'bar' or aaa in [1,2,3]), "
            + "   maxDepth: 10 "
            + "} return foo";
    checkRightSyntax(query);
  }

  @Test
  public void testMultiPath() {
    var query =
        "MATCH {}" + "  .(out().in(){class:'v'}.both('Foo')){maxDepth: 3}.out() return foo";
    checkRightSyntax(query);
  }

  @Test
  public void testMultiPathArrows() {
    var query = "MATCH {}" + "  .(-->{}<--{class:'v'}--){maxDepth: 3}-->{} return foo";
    checkRightSyntax(query);
  }

  @Test
  public void testMultipleMatches() {
    var query = "MATCH {class: 'V', as: foo}.out(){class: 'V', as: bar}, ";
    query += " {class: 'V', as: foo}.out(){class: 'V', as: bar},";
    query += " {class: 'V', as: foo}.out(){class: 'V', as: bar} RETURN foo";
    checkRightSyntax(query);
  }

  @Test
  public void testMultipleMatchesArrow() {
    var query = "MATCH {class: 'V', as: foo}-->{class: 'V', as: bar}, ";
    query += " {class: 'V', as: foo}-->{class: 'V', as: bar},";
    query += " {class: 'V', as: foo}-->{class: 'V', as: bar} RETURN foo";
    checkRightSyntax(query);
  }

  @Test
  public void testWhile() {
    checkRightSyntax("MATCH {class: 'V', as: foo}.out(){while:($depth<4), as:bar} RETURN bar ");
  }

  @Test
  public void testWhileArrow() {
    checkRightSyntax("MATCH {class: 'V', as: foo}-->{while:($depth<4), as:bar} RETURN bar ");
  }

  @Test
  public void testLimit() {
    checkRightSyntax("MATCH {class: 'V'} RETURN foo limit 10");
  }

  @Test
  public void testReturnJson() {
    checkRightSyntax("MATCH {class: 'V'} RETURN {'name':'foo', 'value': bar}");
  }

  @Test
  public void testOptional() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{}<-foo-{}-bar-{}-->{as: bar, optional:true} RETURN foo");
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{}<-foo-{}-bar-{}-->{as: bar, optional:false} RETURN foo");
  }

  @Test
  public void testOrderBy() {
    checkRightSyntax("MATCH {class: 'V', as: foo}-->{} RETURN foo ORDER BY foo");
    checkRightSyntax("MATCH {class: 'V', as: foo}-->{} RETURN foo ORDER BY foo limit 10");
  }

  @Test
  public void testNestedProjections() {
    checkRightSyntax("MATCH {class: 'V', as: foo}-->{} RETURN foo:{name, surname}");
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar} RETURN foo:{name, surname} as bloo, bar:{*}");
  }

  @Test
  public void testUnwind() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar} RETURN foo.name, bar.name as x unwind x");
  }

  @Test
  public void testDepthAlias() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar, while:($depth < 2), depthAlias: depth} RETURN"
            + " depth");
  }

  @Test
  public void testPathAlias() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar, while:($depth < 2), pathAlias: barPath} RETURN"
            + " barPath");
  }

  @Test
  public void testClassTarget() {
    checkRightSyntax("MATCH {class:v, as: foo} RETURN $elements");
    checkRightSyntax("MATCH {class:12, as: foo} RETURN $elements");
    checkRightSyntax("MATCH {class: v, as: foo} RETURN $elements");
    checkRightSyntax("MATCH {class: `v`, as: foo} RETURN $elements");
    checkRightSyntax("MATCH {class:`v`, as: foo} RETURN $elements");
    checkRightSyntax("MATCH {class: 12, as: foo} RETURN $elements");
  }

  @Test
  public void testNot() {
    checkRightSyntax("MATCH {class:v, as: foo}, NOT {as:foo}-->{as:bar} RETURN $elements");
  }

  @Test
  public void testSkip() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar} RETURN foo.name, bar.name skip 10 limit 10");
  }

  @Test
  public void testFieldTraversal() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.toBar{as:bar} RETURN foo.name, bar.name skip 10 limit 10");
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.toBar{as:bar}.out(){as:c} RETURN foo.name, bar.name skip 10"
            + " limit 10");
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.toBar.baz{as:bar} RETURN foo.name, bar.name skip 10 limit 10");
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}.toBar.out(){as:bar} RETURN foo.name, bar.name skip 10 limit"
            + " 10");
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
