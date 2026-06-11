package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Assert;
import org.junit.Test;

/**
 * Enumeration-completeness guard for cache-determinism classification (I5). The tx-result cache trusts
 * {@link NonDeterministicQueryDetector}'s denylist to catch every non-deterministic builtin: a function
 * whose result varies per call but is missing from the denylist would be silently cached, returning a
 * stale value on the second {@code query()}. Because the denylist is fail-open, that gap cannot be
 * detected by the detector itself — only by walking every registered function and asserting each one
 * has been deliberately classified.
 *
 * <p>This test walks all four registered {@link SQLFunctionFactory} implementations
 * ({@code DefaultSQLFunctionFactory}, {@code DynamicSQLElementFactory}, {@code CustomSQLFunctionFactory}
 * reflective {@code math_*}, {@code DatabaseFunctionFactory} stored) and requires every advertised
 * function name to fall into exactly one of two test-maintained classifications: the
 * {@link #KNOWN_NON_DETERMINISTIC} set (which must equal the detector's denylist) or the
 * {@link #KNOWN_DETERMINISTIC} set. A newly registered function in neither set fails the build, forcing
 * its author to classify it — and, when non-deterministic, to add a denylist entry — before it can ship
 * cacheable. The reflective {@code math_*} family is handled by a prefix rule because the exact member
 * set is JDK-version dependent: {@code math_random} is non-deterministic, every other {@code math_*} is
 * a pure numeric function.
 */
public class FunctionDeterminismEnumerationTest extends DbTestBase {

  /**
   * The function names the cache treats as non-deterministic. This MUST mirror
   * {@link NonDeterministicQueryDetector}'s denylist (plus the special zero-argument {@code date()}
   * form, whose name is {@code date} but which is deterministic with an argument — see the detector).
   * Keeping the set here lets the test fail if a registered non-deterministic function lacks coverage,
   * and the {@link #denylistEntriesAreAllRegistered()} case fails if a denylist name drifts away from
   * any real registered function (a typo that would silently never match).
   */
  private static final Set<String> KNOWN_NON_DETERMINISTIC =
      Set.of("sysdate", "uuid", "eval", "math_random", "date");

  /**
   * Registered builtin functions whose result is a pure function of storage + arguments, so caching a
   * query that calls them is safe. Maintained by hand: a new builtin must be added here (deterministic)
   * or to {@link #KNOWN_NON_DETERMINISTIC} (and the detector denylist) before it ships. The reflective
   * {@code math_*} family is intentionally absent — it is classified by the {@code math_} prefix rule in
   * {@link #classify} because its members are JDK-version dependent.
   */
  private static final Set<String> KNOWN_DETERMINISTIC =
      Set.of(
          "avg", "coalesce", "count", "decode", "difference", "symmetricdifference", "distance",
          "distinct", "encode", "first", "format", "traversededge", "traversedelement",
          "traversedvertex", "if", "assert", "ifnull", "intersect", "last", "list", "map", "max",
          "min", "interval", "set", "sum", "unionall", "mode", "percentile", "median", "variance",
          "stddev", "concat", "decimal", "sequence", "abs", "indexkeysize", "strcmpci", "throwcme",
          "out", "in", "both", "oute", "outv", "ine", "inv", "bothe", "bothv", "shortestpath",
          "dijkstra", "astar", "detach");

  /**
   * Classifies a single registered function name as deterministic-or-not for the completeness check.
   * Returns {@code true} when the name is accounted for (in either curated set, or covered by the
   * {@code math_} prefix rule); a {@code false} return is a name the author has not yet classified.
   */
  private static boolean isClassified(String name) {
    var lower = name.toLowerCase(Locale.ROOT);
    if (KNOWN_NON_DETERMINISTIC.contains(lower) || KNOWN_DETERMINISTIC.contains(lower)) {
      return true;
    }
    // Reflective Math.* family: every member except math_random is a pure numeric function. The exact
    // member set varies by JDK, so it is covered by a prefix rule rather than an enumerated list.
    return lower.startsWith("math_");
  }

  private List<SQLFunctionFactory> allFactories() {
    var factories = new ArrayList<SQLFunctionFactory>();
    var it = SQLEngine.getFunctionFactories(session);
    while (it.hasNext()) {
      factories.add(it.next());
    }
    return factories;
  }

  private Set<String> allRegisteredFunctionNames() {
    var names = new TreeSet<String>();
    for (var factory : allFactories()) {
      for (var name : factory.getFunctionNames(session)) {
        names.add(name.toLowerCase(Locale.ROOT));
      }
    }
    return names;
  }

  /**
   * The ServiceLoader manifest registers exactly four function factories. Pinning the count guards the
   * enumeration surface: a fifth factory added without updating this test (and the curated sets) would
   * widen the function set silently, and a removed factory would shrink the determinism guard's reach.
   */
  @Test
  public void exactlyFourFunctionFactoriesAreRegistered() {
    Assert.assertEquals(
        "The I5 enumeration must walk all four SQLFunctionFactory implementations; a change to the"
            + " ServiceLoader manifest must be reflected here",
        4,
        allFactories().size());
  }

  /**
   * The completeness guard: every registered function across all four factories must be classified. A
   * new non-deterministic builtin added without a denylist entry — the exact gap the fail-open detector
   * cannot self-detect — lands here as an unclassified name and fails the build.
   */
  @Test
  public void everyRegisteredFunctionIsClassifiedDeterministicOrNot() {
    var unclassified = new TreeSet<String>();
    for (var name : allRegisteredFunctionNames()) {
      if (!isClassified(name)) {
        unclassified.add(name);
      }
    }
    Assert.assertTrue(
        "Unclassified function(s) found: "
            + unclassified
            + ". Each must be added to FunctionDeterminismEnumerationTest.KNOWN_DETERMINISTIC, or — if"
            + " its result varies per call — to KNOWN_NON_DETERMINISTIC AND the"
            + " NonDeterministicQueryDetector denylist, before it can ship cacheable.",
        unclassified.isEmpty());
  }

  /**
   * Guards the denylist against drift the other direction: every name the cache treats as
   * non-deterministic must correspond to a function that is actually registered (the {@code math_random}
   * member is covered by the reflective family). A denylist entry naming no real function is a dead
   * guard — a typo or a renamed function — that would silently never match and let a non-deterministic
   * query through.
   */
  @Test
  public void denylistEntriesAreAllRegistered() {
    var registered = allRegisteredFunctionNames();
    for (var nonDeterministic : KNOWN_NON_DETERMINISTIC) {
      Assert.assertTrue(
          "Denylisted non-deterministic name '"
              + nonDeterministic
              + "' matches no registered function; the denylist entry is dead (typo or rename) and"
              + " would silently never bypass the cache",
          registered.contains(nonDeterministic));
    }
  }
}
