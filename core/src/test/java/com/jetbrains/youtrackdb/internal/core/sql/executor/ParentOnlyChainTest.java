package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link ParentOnlyChain}, the closed syntactic whitelist that gates the correlated
 * RID fetch in {@link SelectExecutionPlanner}.
 *
 * <p>The predicate answers a single question: can this expression be evaluated once, with a
 * {@code null} current record, without changing the rows that a class scan plus a RID filter would
 * return? It answers yes only for a chain rooted at the {@code $parent} context variable and built
 * from plain identifiers and constant or parent-rooted indexes. Everything else must be rejected,
 * because the caller then falls back to the scan plus filter, which is always correct.
 *
 * <p>Each case here drives the predicate directly on a parsed AST rather than through a query, so a
 * shape that never survives planning — a wildcard link, a bracket range, a RID index — is still
 * exercised. The query-level counterpart lives in {@code SelectExecutionPlannerRidEqualityTest},
 * which pins the plan text and the returned rows for the shapes that reach the planner.
 */
public class ParentOnlyChainTest {

  // ---------------------------------------------------------------------------------------------
  // Accepted shapes
  // ---------------------------------------------------------------------------------------------

  /**
   * The bare parent context variable is a chain with zero links. It resolves to the parent command
   * context and reads nothing from the current record, so the whitelist admits it.
   */
  @Test
  public void bareParentVariable_isAccepted() {
    assertAccepted("$parent");
  }

  /**
   * A dotted chain of plain identifiers under the parent root is the canonical accepted shape.
   * Each link reads only the value the previous link produced, so no link can reach the inner row.
   */
  @Test
  public void plainIdentifierChain_isAccepted() {
    assertAccepted("$parent.$current");
    assertAccepted("$parent.$current.ref");
    assertAccepted("$parent.$current.a.b.c");
    // A second $parent hop walks one more context level up, which is still parent-rooted.
    assertAccepted("$parent.$parent.$current.ref");
  }

  /**
   * The root identifier is matched case insensitively, mirroring
   * {@code SQLSuffixIdentifier.execute}, which compares the variable name with
   * {@code equalsIgnoreCase}. A case variant must not silently lose the fast path.
   */
  @Test
  public void parentRootIsCaseInsensitive_isAccepted() {
    assertAccepted("$PARENT.$current.ref");
  }

  /**
   * A bracket index whose body is a literal number, a literal string, a named bind parameter, or a
   * positional bind parameter is constant for the whole execution, so the indexed chain stays
   * current-record independent and is admitted.
   */
  @Test
  public void constantBracketIndex_isAccepted() {
    assertAccepted("$parent.$current.refs[0]");
    assertAccepted("$parent.$current.refs['key']");
    assertAccepted("$parent.$current.refs[:p]");
    assertAccepted("$parent.$current.refs[?]");
    // Directly on the root, with no dotted link in between.
    assertAccepted("$parent[0]");
  }

  /**
   * A bracket index that is itself a parent-rooted chain is admitted, because the nested chain is
   * checked by the same predicate and therefore cannot read the current record either.
   */
  @Test
  public void parentRootedBracketIndex_isAccepted() {
    assertAccepted("$parent.$r[$parent.$current.idx]");
  }

  /**
   * A chain may continue with plain identifiers after a bracket index. The walk must classify every
   * link, not stop at the first bracket.
   */
  @Test
  public void chainContinuingAfterIndex_isAccepted() {
    assertAccepted("$parent.$current.refs[0].name");
    assertAccepted("$parent.$current.refs[0][1]");
  }

  /**
   * Benchmark guard: the two right-hand expressions that the LDBC benchmark queries actually use
   * must stay inside the whitelist. IC1 correlates on {@code $parent.$current.friendVertex} and
   * IC10 on {@code $parent.$current.fofVertex}. Both are the reason the correlated fetch exists,
   * so a later tightening of the gate that drops either one would silently erase the reported
   * throughput gain while every other test stayed green.
   *
   * <p>This is one half of the pin. It proves the gate accepts these expressions. The other half
   * is {@code LdbcCorrelatedRidShapeTest} in the {@code jmh-ldbc} module, which proves the two
   * benchmark SQL resources still contain exactly these expressions. Neither half needs a schema
   * or a benchmark run, and both must be updated together if a benchmark query is rewritten.
   */
  @Test
  public void ldbcBenchmarkCorrelatedExpressions_areAccepted() {
    assertAccepted("$parent.$current.friendVertex");
    assertAccepted("$parent.$current.fofVertex");
  }

  // ---------------------------------------------------------------------------------------------
  // Rejected: nothing to walk
  // ---------------------------------------------------------------------------------------------

  /**
   * A {@code null} expression reference must be rejected rather than throw. The gate calls the
   * predicate on whatever the RID-equality extractor produced, so a defensive answer is required.
   */
  @Test
  public void nullExpression_isRejected() {
    Assert.assertFalse(
        "a null expression must be rejected, not throw",
        ParentOnlyChain.isParentOnlyChain(null));
  }

  /**
   * An expression node with every payload unset carries no chain at all. It must be rejected by the
   * math-expression check rather than treated as an empty, and therefore harmless, chain.
   */
  @Test
  public void emptyExpression_isRejected() {
    Assert.assertFalse(
        "an expression with no payload must be rejected",
        ParentOnlyChain.isParentOnlyChain(new SQLExpression(-1)));
  }

  // ---------------------------------------------------------------------------------------------
  // Rejected: literals and non-chain payloads
  // ---------------------------------------------------------------------------------------------

  /**
   * The {@code null} literal, a RID literal, a boolean literal, a number, a string and a bind
   * parameter are all values rather than chains. None of them is rooted at {@code $parent}, so the
   * whitelist rejects each one and the planner keeps its existing handling for them.
   */
  @Test
  public void literalPayloads_areRejected() {
    assertRejected("null");
    assertRejected("#12:0");
    assertRejected("true");
    assertRejected("false");
    assertRejected("1");
    assertRejected("'text'");
    assertRejected(":p");
  }

  /**
   * An arithmetic or null-coalescing composition keeps its wrapper math-expression node, so it is
   * not a single base expression. It must be rejected even when both operands are parent-rooted,
   * because the whitelist is defined over one chain, not over an operator tree.
   */
  @Test
  public void arithmeticComposition_isRejected() {
    assertRejected("$parent.$current.ref + 1");
    assertRejected("$parent.$current.missing ?? $parent.$current.ref");
  }

  /**
   * A parenthesised subquery parses into a parenthesis expression, not a base expression, and it is
   * evaluated against the current record. The whitelist must reject it.
   */
  @Test
  public void parenthesisedSubquery_isRejected() {
    assertRejected("(select from V)");
  }

  // ---------------------------------------------------------------------------------------------
  // Rejected: wrong root
  // ---------------------------------------------------------------------------------------------

  /**
   * A root that is not the parent variable reads the inner row by definition. That covers a bare
   * property name, a {@code $current} with no {@code $parent} in front, and any other context
   * variable.
   */
  @Test
  public void nonParentIdentifierRoot_isRejected() {
    assertRejected("foo");
    assertRejected("foo.bar");
    assertRejected("$current.ref");
    assertRejected("$other.ref");
  }

  /**
   * A record-attribute root such as {@code @rid} and a {@code @this} root both read the current
   * record. Neither carries a plain {@code $parent} identifier, so both are rejected.
   */
  @Test
  public void recordAttributeAndThisRoot_areRejected() {
    assertRejected("@rid");
    assertRejected("@this");
    assertRejected("@this.ref");
  }

  /**
   * A collection literal and a function call both parse into the level-zero identifier slot, which
   * the whitelist refuses outright. A function call there is the dangerous case: it falls back to
   * the current-record system variable when the record argument is {@code null}.
   */
  @Test
  public void levelZeroRoot_isRejected() {
    assertRejected("[1,2]");
    assertRejected("first($parent.$current.ref)");
  }

  // ---------------------------------------------------------------------------------------------
  // Rejected: unsafe links
  // ---------------------------------------------------------------------------------------------

  /**
   * A function call anywhere in the expression is rejected with no exception. The verified
   * counterexample is reproduced here: {@code out()} reads the current-record system variable when
   * the record argument is {@code null}, so a fetch that evaluates it once would return nothing
   * where the scan plus filter returns rows.
   */
  @Test
  public void functionCallAnywhere_isRejected() {
    assertRejected("ifnull($parent.$current.missing, first(out('GE')))");
    assertRejected("ifnull(otherRef, $parent.$current.ref)");
    assertRejected("$parent.$current.refs[first(x)]");
  }

  /**
   * A method call is rejected at every chain position: on the root, in the middle of the chain, and
   * at the end. {@code SQLMethodCall} reaches the current record the same way a function call does.
   */
  @Test
  public void methodCallAtEveryChainPosition_isRejected() {
    assertRejected("$parent.asList()");
    assertRejected("$parent.$current.asList()[0]");
    assertRejected("$parent.$current.refs.asList()");
  }

  /**
   * A record attribute used as a chain link is rejected. It is not a plain identifier, and the
   * whitelist admits only node shapes it has proven safe rather than everything that happens to
   * look harmless.
   */
  @Test
  public void recordAttributeInChain_isRejected() {
    assertRejected("$parent.$current.@rid");
    assertRejected("$parent.$current.ref.@class");
  }

  /**
   * A wildcard link expands the whole value rather than naming one property. It is not a plain
   * identifier, so the whitelist rejects it.
   */
  @Test
  public void wildcardInChain_isRejected() {
    assertRejected("$parent.$current.*");
    assertRejected("$parent.*");
  }

  // ---------------------------------------------------------------------------------------------
  // Rejected: unsafe brackets
  // ---------------------------------------------------------------------------------------------

  /**
   * A multi-index bracket builds a list rather than selecting one element. That is not the
   * single-index shape the whitelist describes, so it is rejected.
   */
  @Test
  public void multiIndexBracket_isRejected() {
    assertRejected("$parent.$current.refs[0,1]");
    assertRejected("$parent.$current.refs[0,1,2]");
  }

  /**
   * A bracket carrying a filter condition evaluates a full boolean expression per element, which
   * can read anything including the current record. It is rejected.
   */
  @Test
  public void bracketWithCondition_isRejected() {
    assertRejected("$parent.$current.refs[name = 'x']");
  }

  /**
   * A bracket carrying a range selector is rejected. The whitelist has not proven the range path
   * safe, and rejection only costs the fast path, never correctness.
   */
  @Test
  public void bracketWithRange_isRejected() {
    assertRejected("$parent.$current.refs[0..2]");
    assertRejected("$parent.$current.refs[0...2]");
  }

  /**
   * A bracket carrying a right binary condition is rejected for the same reason as a filter
   * condition: it is an evaluated body, not an index.
   */
  @Test
  public void bracketWithRightBinaryCondition_isRejected() {
    assertRejected("$parent.$current.refs[= 3]");
    assertRejected("$parent.$current.refs[> 3]");
  }

  /**
   * A RID index is rejected. {@code SQLArraySelector.getValue} ignores its {@code rid} field and
   * yields {@code null}, so admitting it would be admitting a shape whose runtime meaning the
   * whitelist has not established.
   */
  @Test
  public void ridBracketIndex_isRejected() {
    assertRejected("$parent.$current.refs[#12:0]");
  }

  /**
   * A bracket index that is neither a constant nor a parent-rooted chain reads the current record.
   * A bare identifier, a dotted inner path, an arithmetic index and the {@code null} literal are
   * all rejected.
   */
  @Test
  public void nonConstantNonParentBracketIndex_isRejected() {
    assertRejected("$parent.$current.refs[foo]");
    assertRejected("$parent.$current.refs[a.b]");
    assertRejected("$parent.$current.refs[1 + 1]");
    assertRejected("$parent.$current.refs[null]");
  }

  /**
   * One rejected link poisons the whole chain, wherever it sits. A safe prefix followed by an
   * unsafe link, and an unsafe link followed by a safe suffix, must both be rejected.
   */
  @Test
  public void oneUnsafeLinkRejectsTheWholeChain() {
    assertRejected("$parent.$current.refs[0].asList()");
    assertRejected("$parent.$current.asList().name");
    assertRejected("$parent.$current.@rid.name");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Asserts that the whitelist admits {@code expressionText}. */
  private static void assertAccepted(String expressionText) {
    Assert.assertTrue(
        "expected the whitelist to accept: " + expressionText,
        ParentOnlyChain.isParentOnlyChain(parse(expressionText)));
  }

  /** Asserts that the whitelist refuses {@code expressionText}. */
  private static void assertRejected(String expressionText) {
    Assert.assertFalse(
        "expected the whitelist to reject: " + expressionText,
        ParentOnlyChain.isParentOnlyChain(parse(expressionText)));
  }

  /**
   * Parses one SQL expression into its AST. Fixtures are written as SQL text so that each case
   * pins the shape a real query produces, rather than a hand-assembled node graph that could drift
   * from the grammar.
   */
  private static SQLExpression parse(String expressionText) {
    var input = new ByteArrayInputStream(expressionText.getBytes(StandardCharsets.UTF_8));
    try {
      return new YouTrackDBSql(input).Expression();
    } catch (ParseException e) {
      throw new AssertionError("fixture must parse: " + expressionText, e);
    }
  }
}
