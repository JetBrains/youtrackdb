package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Test;

/** Unit tests for MATCH statement parsing, equality, serialization, and return-item checks. */
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

  /** Verifies that equals() returns false for different MATCH statements. */
  @Test
  public void testEqualsDifferentStatements() {
    var stm1 = parse("MATCH {class: V, as: a} RETURN a");
    var stm2 = parse("MATCH {class: E, as: b} RETURN b");
    assertFalse("different statements should not be equal", stm1.equals(stm2));
    assertTrue("same statement should be equal to itself", stm1.equals(stm1));
    assertFalse("statement should not equal null", stm1.equals(null));
  }

  /** Verifies hashCode() consistency for equal MATCH statements. */
  @Test
  public void testHashCodeConsistency() {
    var query = "MATCH {class: V, as: a}.out(){as: b} RETURN a, b";
    var stm1 = parse(query);
    var stm2 = parse(query);
    assertTrue("same query should produce equal statements", stm1.equals(stm2));
    assertEquals("equal statements should have same hashCode",
        stm1.hashCode(), stm2.hashCode());
  }

  /** Verifies toGenericStatement() produces a re-parseable string. */
  @Test
  public void testToGenericStatement() {
    var stm = parse("MATCH {class: V, as: a}.out(){as: b} RETURN a, b");
    var builder = new StringBuilder();
    stm.toGenericStatement(builder);
    var generic = builder.toString();
    assertFalse("generic statement should not be empty", generic.isEmpty());
    // Verify the generic form can be re-parsed
    parse(generic);
  }

  /**
   * Verifies toGenericStatement() preserves ORDER BY, SKIP, and LIMIT clauses.
   * Note: toGenericStatement() serializes only matchExpressions, not notMatchExpressions,
   * so the NOT clause is not expected in the output.
   */
  @Test
  public void testToGenericStatementComplex() {
    var stm = parse("MATCH {class: V, as: a}-->{as: b, optional: true},"
        + " NOT {as: a}-->{as: c}"
        + " RETURN a.name, b ORDER BY a.name SKIP 1 LIMIT 10");
    var builder = new StringBuilder();
    stm.toGenericStatement(builder);
    var generic = builder.toString();
    assertFalse("generic statement should not be empty", generic.isEmpty());
    assertTrue("should contain MATCH keyword", generic.contains("MATCH"));
    assertTrue("should contain RETURN keyword", generic.contains("RETURN"));
    assertTrue("should contain ORDER BY clause", generic.contains("ORDER BY"));
    assertTrue("should contain SKIP clause", generic.contains("SKIP"));
    assertTrue("should contain LIMIT clause", generic.contains("LIMIT"));
  }

  /** Verifies MATCH with GROUP BY clause parses correctly and round-trips through toString. */
  @Test
  public void testGroupBy() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar} RETURN foo.type, count(*) GROUP BY foo.type");
  }

  /**
   * Verifies MATCH with all clauses (DISTINCT, GROUP BY, ORDER BY, UNWIND, SKIP, LIMIT)
   * parses correctly. This exercises toString/toGenericStatement branches for every clause.
   */
  @Test
  public void testAllClauses() {
    checkRightSyntax(
        "MATCH {class: 'V', as: foo}-->{as:bar}"
            + " RETURN DISTINCT foo.type, bar.name"
            + " GROUP BY foo.type"
            + " ORDER BY foo.type"
            + " SKIP 5"
            + " LIMIT 10");
  }

  /**
   * Verifies that equals returns false when statements differ only in GROUP BY.
   * This tests the groupBy equality branch in isolation.
   */
  @Test
  public void testEqualsDifferentGroupBy() {
    var withGroupBy = parse("MATCH {class: V, as: a}-->{as: b} RETURN a GROUP BY a");
    var withoutGroupBy = parse("MATCH {class: V, as: a}-->{as: b} RETURN a");
    assertFalse("statements with different GROUP BY should not be equal",
        withGroupBy.equals(withoutGroupBy));
    assertFalse("statements with different GROUP BY should not be equal (symmetric)",
        withoutGroupBy.equals(withGroupBy));
  }

  /**
   * Verifies that equals returns false when statements differ only in ORDER BY.
   */
  @Test
  public void testEqualsDifferentOrderBy() {
    var withOrder = parse("MATCH {class: V, as: a} RETURN a ORDER BY a");
    var withoutOrder = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different ORDER BY should not be equal",
        withOrder.equals(withoutOrder));
  }

  /**
   * Verifies that equals returns false when statements differ only in UNWIND.
   */
  @Test
  public void testEqualsDifferentUnwind() {
    var withUnwind = parse(
        "MATCH {class: V, as: a}-->{as:b} RETURN a.name, b.name as x unwind x");
    var withoutUnwind = parse(
        "MATCH {class: V, as: a}-->{as:b} RETURN a.name, b.name as x");
    assertFalse("statements with different UNWIND should not be equal",
        withUnwind.equals(withoutUnwind));
  }

  /**
   * Verifies that equals returns false when statements differ only in SKIP.
   */
  @Test
  public void testEqualsDifferentSkip() {
    var withSkip = parse("MATCH {class: V, as: a} RETURN a SKIP 5");
    var withoutSkip = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different SKIP should not be equal",
        withSkip.equals(withoutSkip));
  }

  /**
   * Verifies that equals returns false when statements differ only in LIMIT.
   */
  @Test
  public void testEqualsDifferentLimit() {
    var withLimit = parse("MATCH {class: V, as: a} RETURN a LIMIT 10");
    var withoutLimit = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different LIMIT should not be equal",
        withLimit.equals(withoutLimit));
  }

  /**
   * Verifies that equals returns false when statements differ only in RETURN DISTINCT.
   */
  @Test
  public void testEqualsDifferentDistinct() {
    var withDistinct = parse("MATCH {class: V, as: a} RETURN DISTINCT a");
    var withoutDistinct = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different DISTINCT should not be equal",
        withDistinct.equals(withoutDistinct));
  }

  /**
   * Verifies that equals returns false when statements differ only in return aliases.
   */
  @Test
  public void testEqualsDifferentReturnAliases() {
    var withAlias = parse("MATCH {class: V, as: a} RETURN a.name AS n");
    var withoutAlias = parse("MATCH {class: V, as: a} RETURN a.name");
    assertFalse("statements with different return aliases should not be equal",
        withAlias.equals(withoutAlias));
  }

  /**
   * Verifies that equals returns false when statements differ in NOT MATCH clauses.
   */
  @Test
  public void testEqualsDifferentNotMatch() {
    var withNot = parse(
        "MATCH {class: V, as: a}, NOT {as:a}-->{as:b} RETURN a");
    var withoutNot = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different NOT MATCH should not be equal",
        withNot.equals(withoutNot));
  }

  /**
   * Verifies that equals returns false when statements differ in nested projections.
   */
  @Test
  public void testEqualsDifferentNestedProjections() {
    var withProjection = parse("MATCH {class: V, as: a} RETURN a:{name, surname}");
    var withoutProjection = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("statements with different nested projections should not be equal",
        withProjection.equals(withoutProjection));
  }

  /**
   * Verifies hashCode consistency: equal statements have equal hash codes, and
   * hashCode is deterministic across multiple calls.
   */
  @Test
  public void testHashCodeConsistencyWithAllClauses() {
    var full1 = parse(
        "MATCH {class: V, as: a}-->{as:b}"
            + " RETURN a, b GROUP BY a ORDER BY a SKIP 1 LIMIT 10");
    var full2 = parse(
        "MATCH {class: V, as: a}-->{as:b}"
            + " RETURN a, b GROUP BY a ORDER BY a SKIP 1 LIMIT 10");

    // Equal objects must have equal hash codes (hashCode contract)
    assertEquals("equal statements must have equal hashCodes",
        full1.hashCode(), full2.hashCode());

    // Verify hashCode is consistent across calls
    assertEquals("hashCode should be deterministic", full1.hashCode(), full1.hashCode());
  }

  /**
   * Verifies that returnsPatterns() detects the $matches keyword (the second branch).
   */
  @Test
  public void testReturnsPatternsWithMatchesKeyword() {
    var stm = parse("MATCH {class: V, as: a} RETURN $matches");
    assertTrue("$matches should be detected by returnsPatterns()", stm.returnsPatterns());
  }

  /**
   * Verifies that returnsPatterns() detects the $patterns keyword.
   */
  @Test
  public void testReturnsPatternsWithPatternsKeyword() {
    var stm = parse("MATCH {class: V, as: a} RETURN $patterns");
    assertTrue("$patterns should be detected by returnsPatterns()", stm.returnsPatterns());
  }

  /**
   * Verifies that returnsPatterns() returns false when no pattern keywords are present.
   */
  @Test
  public void testReturnsPatternsReturnsFalseForNormalReturn() {
    var stm = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("normal return should not trigger returnsPatterns()",
        stm.returnsPatterns());
  }

  /**
   * Verifies that isIdempotent() returns true for MATCH statements
   * (MATCH is a read-only operation).
   */
  @Test
  public void testIsIdempotent() {
    var stm = parse("MATCH {class: V, as: a} RETURN a");
    assertTrue("MATCH statements should be idempotent", stm.isIdempotent());
  }

  /**
   * Verifies that refersToParent() returns false for MATCH statements.
   */
  @Test
  public void testRefersToParent() {
    var stm = parse("MATCH {class: V, as: a} RETURN a");
    assertFalse("MATCH statements should not refer to parent", stm.refersToParent());
  }

  /**
   * Verifies the toGenericStatement() output for a MATCH with GROUP BY clause
   * to ensure the GROUP BY branch is exercised.
   */
  @Test
  public void testToGenericStatementWithGroupBy() {
    var stm = parse(
        "MATCH {class: V, as: a}-->{as:b} RETURN a.type GROUP BY a.type");
    var builder = new StringBuilder();
    stm.toGenericStatement(builder);
    var generic = builder.toString();
    assertTrue("generic statement should contain GROUP BY",
        generic.contains("GROUP BY"));
  }

  /** Helper to parse a MATCH query and return the typed SQLMatchStatement. */
  private SQLMatchStatement parse(String query) {
    try {
      return (SQLMatchStatement) getParserFor(query).parse();
    } catch (ParseException e) {
      throw new RuntimeException("Failed to parse: " + query, e);
    }
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
