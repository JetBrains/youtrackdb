package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies {@link NonDeterministicQueryDetector#containsNonDeterministicReference}: a query reading a
 * per-call value (a denylisted function, a per-row context variable) or carrying the {@code NOCACHE}
 * hint is reported non-deterministic and must bypass the cache, while a query that is a pure function
 * of storage + parameters is reported deterministic and is cacheable. The walk must reach every
 * expression position — WHERE, ORDER BY, projection, and nested sub-expressions — so detection cases
 * are placed in several of those positions.
 */
public class NonDeterministicQueryDetectorTest extends DbTestBase {

  private SQLStatement parse(String sql) {
    return YqlStatementCache.get(sql, session);
  }

  private boolean isNonDeterministic(String sql) {
    return NonDeterministicQueryDetector.containsNonDeterministicReference(parse(sql));
  }

  /** A plain deterministic SELECT (storage + parameter only) is cacheable. */
  @Test
  public void plainSelectIsDeterministic() {
    Assert.assertFalse(isNonDeterministic("select from OUser where name = ?"));
  }

  /** {@code sysdate()} in the WHERE clause forces bypass: it returns a different instant per call. */
  @Test
  public void sysdateInWhereIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select from OUser where name = sysdate()"));
  }

  /** {@code uuid()} in the projection forces bypass. */
  @Test
  public void uuidInProjectionIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select uuid() as u from OUser"));
  }

  /**
   * The walk must reach a LET binding: {@code uuid()} bound to a LET alias forces bypass even though
   * the rest of the query is deterministic. (ORDER BY in this dialect accepts only an identifier plus
   * a modifier chain, not a bare function call, so the LET position exercises the same "reach a
   * non-top-level expression" requirement.)
   */
  @Test
  public void uuidInLetClauseIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select $u from OUser let $u = uuid()"));
  }

  /** The zero-argument {@code date()} returns "now" and forces bypass. */
  @Test
  public void zeroArgDateIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select from OUser where name = date()"));
  }

  /**
   * {@code date('<literal>')} parses a fixed instant and is deterministic — only the zero-argument
   * form is non-deterministic, so an argument-carrying date must stay cacheable.
   */
  @Test
  public void dateWithArgumentIsDeterministic() {
    Assert.assertFalse(isNonDeterministic("select from OUser where name = date('2020-01-01')"));
  }

  /** A per-row context variable ({@code $parent}) forces bypass: the delta builder cannot reproduce it. */
  @Test
  public void contextVariableIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select $parent from OUser"));
  }

  /**
   * The {@code NOCACHE} hint is the user-facing opt-out and must be reported non-deterministic so the
   * caller's explicit hint short-circuits caching even when the query body is otherwise deterministic.
   */
  @Test
  public void noCacheHintForcesBypass() {
    Assert.assertTrue(isNonDeterministic("select from OUser NOCACHE"));
  }

  /**
   * A denylisted function nested inside a larger expression must still be found, proving the walk
   * descends into nested sub-expressions rather than only checking the top level.
   */
  @Test
  public void nestedSysdateIsNonDeterministic() {
    Assert
        .assertTrue(isNonDeterministic("select from OUser where age > (sysdate().asLong() - 100)"));
  }

  /**
   * {@code eval(...)} is on the denylist (it evaluates an arbitrary expression that may itself read a
   * per-call value), so a query calling it must bypass the cache. This pins the {@code eval} denylist
   * member directly: the {@code for}-loop in {@code isNonDeterministicFunction} matching only
   * {@code sysdate} would still report this query deterministic, so a typo or case drift in the
   * {@code eval} entry is caught here rather than silently caching a non-deterministic query.
   */
  @Test
  public void evalIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select eval('1+1') as e from OUser"));
  }

  /**
   * {@code math_random()} is on the denylist (it returns a different pseudo-random value per call,
   * resolving through the reflective {@code math_} factory to {@code Math.random()}), so a query
   * calling it must bypass the cache. Asserted directly so the {@code math_random} denylist member is
   * verified independently of the other names sharing the same matching loop.
   */
  @Test
  public void mathRandomIsNonDeterministic() {
    Assert.assertTrue(isNonDeterministic("select from OUser where age > math_random()"));
  }
}
