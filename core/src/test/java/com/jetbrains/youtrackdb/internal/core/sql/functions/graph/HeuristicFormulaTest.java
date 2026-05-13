package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Covers the {@link HeuristicFormula} enum surface plus every protected heuristic-math helper in
 * {@link SQLFunctionHeuristicPathFinderAbstract}. Uses a test-only subclass to expose the
 * protected helpers so we can validate their arithmetic against hand-calculated expected values
 * — independent of the A* search loop that orchestrates them.
 *
 * <p>These helpers form the "dispatch table" behind {@code SQLFunctionAstar.getHeuristicCost}; if
 * any formula drifts (e.g. Euclidean without sqrt, Manhattan swapped for Max-axis), the A*
 * algorithm silently produces wrong paths. Direct arithmetic assertions catch that.
 */
public class HeuristicFormulaTest {

  /** Epsilon for float comparisons — the helpers use double arithmetic. */
  private static final double EPS = 1e-9;

  /**
   * Exposes protected helpers. Uses default values for all params since we only invoke the helpers
   * directly — we never call {@link #execute} or {@link #getNeighbors}, so no CommandContext or
   * source/destination vertex is needed.
   */
  private static final class TestHeuristic extends SQLFunctionHeuristicPathFinderAbstract {

    TestHeuristic() {
      super("testHeuristic", 0, 0);
    }

    @Override
    protected double getDistance(Vertex node, Vertex parent, Vertex target) {
      return 0.0;
    }

    @Override
    protected double getHeuristicCost(
        Vertex node, Vertex parent, Vertex target, CommandContext iContext) {
      return 0.0;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return "testHeuristic()";
    }

    @Override
    public Object execute(
        Object iThis, Result iCurrentRecord, Object iCurrentResult, Object[] iParams,
        CommandContext iContext) {
      return null; // Not used — tests invoke helpers directly.
    }

    // Expose each protected overload for the test.
    double simple(double x, double g, double d) {
      return getSimpleHeuristicCost(x, g, d);
    }

    double manhattan(double x, double y, double gx, double gy, double d) {
      return getManhatanHeuristicCost(x, y, gx, gy, d);
    }

    double maxAxis(double x, double y, double gx, double gy, double d) {
      return getMaxAxisHeuristicCost(x, y, gx, gy, d);
    }

    double diagonal(double x, double y, double gx, double gy, double d) {
      return getDiagonalHeuristicCost(x, y, gx, gy, d);
    }

    double euclidean(double x, double y, double gx, double gy, double d) {
      return getEuclideanHeuristicCost(x, y, gx, gy, d);
    }

    double euclideanNoSqr(double x, double y, double gx, double gy, double d) {
      return getEuclideanNoSQRHeuristicCost(x, y, gx, gy, d);
    }

    double tieBreak(
        double x, double y, double sx, double sy, double gx, double gy, double heuristic) {
      return getTieBreakingHeuristicCost(x, y, sx, sy, gx, gy, heuristic);
    }

    double tieBreakRandom(
        double x, double y, double sx, double sy, double gx, double gy, double heuristic) {
      return getTieBreakingRandomHeuristicCost(x, y, sx, sy, gx, gy, heuristic);
    }

    // N-axis variants.
    double manhattanN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double d) {
      return getManhatanHeuristicCost(axis, s, c, p, g, depth, d);
    }

    double maxAxisN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double d) {
      return getMaxAxisHeuristicCost(axis, s, c, p, g, depth, d);
    }

    double diagonalN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double d) {
      return getDiagonalHeuristicCost(axis, s, c, p, g, depth, d);
    }

    double euclideanN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double d) {
      return getEuclideanHeuristicCost(axis, s, c, p, g, depth, d);
    }

    double euclideanNoSqrN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double d) {
      return getEuclideanNoSQRHeuristicCost(axis, s, c, p, g, depth, d);
    }

    double tieBreakN(
        String[] axis,
        Map<String, Double> s,
        Map<String, Double> c,
        Map<String, Double> p,
        Map<String, Double> g,
        long depth,
        double heuristic) {
      return getTieBreakingHeuristicCost(axis, s, c, p, g, depth, heuristic);
    }

    String[] exposedStringArray(Object o) {
      return stringArray(o);
    }

    Boolean exposedBool(Object o, boolean def) {
      return booleanOrDefault(o, def);
    }

    String exposedString(Object o, String def) {
      return stringOrDefault(o, def);
    }

    Integer exposedInt(Object o, int def) {
      return integerOrDefault(o, def);
    }

    Long exposedLong(Object o, long def) {
      return longOrDefault(o, def);
    }

    Double exposedDouble(Object o, double def) {
      return doubleOrDefault(o, def);
    }
  }

  // --- HeuristicFormula enum identity -----------------------------------------------------

  /**
   * Values list is frozen by production code (Astar's switch dispatch); any reordering /
   * addition changes heuristic selection behavior and must be caught here.
   */
  @Test
  public void heuristicFormulaEnumValuesMatchProductionOrder() {
    assertArrayEquals(
        new HeuristicFormula[] {
            HeuristicFormula.MANHATAN,
            HeuristicFormula.MAXAXIS,
            HeuristicFormula.DIAGONAL,
            HeuristicFormula.EUCLIDEAN,
            HeuristicFormula.EUCLIDEANNOSQR,
            HeuristicFormula.CUSTOM,
        },
        HeuristicFormula.values());
  }

  /** valueOf must match exact-case enum names — Astar uppercases the param before lookup. */
  @Test
  public void heuristicFormulaValueOfExactCase() {
    assertSame(HeuristicFormula.MANHATAN, HeuristicFormula.valueOf("MANHATAN"));
    assertSame(HeuristicFormula.EUCLIDEAN, HeuristicFormula.valueOf("EUCLIDEAN"));
    assertSame(HeuristicFormula.CUSTOM, HeuristicFormula.valueOf("CUSTOM"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void heuristicFormulaValueOfLowerCaseRejected() {
    HeuristicFormula.valueOf("manhatan");
  }

  @Test(expected = IllegalArgumentException.class)
  public void heuristicFormulaValueOfUnknownNameRejected() {
    HeuristicFormula.valueOf("CHEBYSHEV");
  }

  // --- 2-axis helpers ---------------------------------------------------------------------

  @Test
  public void simpleHeuristicCostIsAbsDifferenceScaledByDFactor() {
    var h = new TestHeuristic();
    assertEquals(5.0, h.simple(3.0, 8.0, 1.0), EPS); // |3-8| * 1 = 5
    assertEquals(10.0, h.simple(3.0, 8.0, 2.0), EPS); // factor applies
    assertEquals(5.0, h.simple(8.0, 3.0, 1.0), EPS); // symmetric
    assertEquals(0.0, h.simple(4.0, 4.0, 3.0), EPS); // zero distance
  }

  @Test
  public void manhattanHeuristicCostIsSumOfAxisAbsDifferences() {
    var h = new TestHeuristic();
    // |1-4| + |2-6| = 3+4 = 7
    assertEquals(7.0, h.manhattan(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
    assertEquals(14.0, h.manhattan(1.0, 2.0, 4.0, 6.0, 2.0), EPS);
    assertEquals(0.0, h.manhattan(5.0, 5.0, 5.0, 5.0, 1.0), EPS);
  }

  @Test
  public void maxAxisHeuristicCostReturnsLargestAxisDifference() {
    var h = new TestHeuristic();
    // max(|1-4|, |2-6|) = max(3,4) = 4
    assertEquals(4.0, h.maxAxis(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
    // and distinct from Manhattan (7) — catches formula swap
    assertNotEquals(h.manhattan(1.0, 2.0, 4.0, 6.0, 1.0), h.maxAxis(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
  }

  @Test
  public void diagonalHeuristicCostUsesMinPlusStraight() {
    var h = new TestHeuristic();
    // dx=3, dy=4; hDiagonal=min=3; hStraight=7; result = 2*3 + 1*(7-6) = 6 + 1 = 7
    assertEquals(7.0, h.diagonal(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
    // dFactor=2 → 4*3 + 2*(7-6) = 12 + 2 = 14
    assertEquals(14.0, h.diagonal(1.0, 2.0, 4.0, 6.0, 2.0), EPS);
  }

  @Test
  public void euclideanHeuristicCostIsDistanceFormula() {
    var h = new TestHeuristic();
    // dx=3, dy=4 → sqrt(9+16) = 5
    assertEquals(5.0, h.euclidean(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
    assertEquals(10.0, h.euclidean(1.0, 2.0, 4.0, 6.0, 2.0), EPS);
  }

  @Test
  public void euclideanNoSqrHeuristicCostOmitsSquareRoot() {
    var h = new TestHeuristic();
    // dx=3, dy=4 → (9+16) = 25 (no sqrt)
    assertEquals(25.0, h.euclideanNoSqr(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
    // Must differ from Euclidean — the two formulas are easy to swap by mistake.
    assertNotEquals(h.euclidean(1.0, 2.0, 4.0, 6.0, 1.0),
        h.euclideanNoSqr(1.0, 2.0, 4.0, 6.0, 1.0), EPS);
  }

  @Test
  public void tieBreakingAddsSmallCrossProductBias() {
    var h = new TestHeuristic();
    // cross = |(x-gx)*(sy-gy) - (sx-gx)*(y-gy)|
    // x=2 y=3 sx=0 sy=0 gx=4 gy=5: dx1=-2 dy1=-2 dx2=-4 dy2=-5
    // cross = |(-2)*(-5) - (-4)*(-2)| = |10 - 8| = 2
    // heuristic 1.0 + 2 * 0.0001 = 1.0002
    assertEquals(1.0002, h.tieBreak(2.0, 3.0, 0.0, 0.0, 4.0, 5.0, 1.0), EPS);
    // Same-line points (cross = 0) leave heuristic unchanged.
    assertEquals(1.0, h.tieBreak(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), EPS);
  }

  @Test
  public void tieBreakingRandomReturnsFiniteValueAndUsesInput() {
    var h = new TestHeuristic();
    // Random implementation: heuristic += (|cross| + rnd.nextFloat()) * heuristic. For
    // heuristic=1.0 and cross=2 (computed from the input coordinates) the result is
    // 1 + (2 + rnd) with rnd ∈ [0,1), so result ∈ [3, 4).
    double result = h.tieBreakRandom(2.0, 3.0, 0.0, 0.0, 4.0, 5.0, 1.0);
    assertTrue(
        "tieBreakRandom produced out-of-expected-range value: " + result,
        result >= 3.0 && result < 4.0);

    // Repeated calls with identical inputs must produce more than one distinct value when
    // rnd.nextFloat() is actually drawn on each invocation. The previous form compared just
    // two consecutive draws and had a 1-in-2^24 flake risk under sustained CI. Drawing 10
    // samples drives the false-fail probability to (1/2^24)^9 ≈ 10^-65 while still catching a
    // regression where the random term is cached or stubbed out (BC3).
    var samples = new java.util.HashSet<Double>();
    for (int i = 0; i < 10; i++) {
      samples.add(h.tieBreakRandom(2.0, 3.0, 0.0, 0.0, 4.0, 5.0, 1.0));
    }
    assertTrue(
        "tieBreakRandom must draw a new random sample per call; saw only " + samples.size()
            + " distinct values across 10 calls",
        samples.size() >= 2);

    // With heuristic=0.0 the multiplicative term is zero regardless of randomness.
    assertEquals(0.0, h.tieBreakRandom(2.0, 3.0, 0.0, 0.0, 4.0, 5.0, 0.0), EPS);
  }

  // --- N-axis helpers --------------------------------------------------------------------

  private static Map<String, Double> mapOf3(double x, double y, double z) {
    Map<String, Double> m = new HashMap<>();
    m.put("x", x);
    m.put("y", y);
    m.put("z", z);
    return m;
  }

  @Test
  public void manhattanNAxisSumsAbsDifferencesAcrossAllAxes() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y", "z"};
    // current (1,2,3) → goal (4,6,9): |1-4|+|2-6|+|3-9| = 3+4+6 = 13
    double r = h.manhattanN(axes, mapOf3(0, 0, 0), mapOf3(1, 2, 3), mapOf3(0, 0, 0),
        mapOf3(4, 6, 9), 0L, 1.0);
    assertEquals(13.0, r, EPS);
    // dFactor applies
    double r2 = h.manhattanN(axes, mapOf3(0, 0, 0), mapOf3(1, 2, 3), mapOf3(0, 0, 0),
        mapOf3(4, 6, 9), 0L, 2.0);
    assertEquals(26.0, r2, EPS);
  }

  @Test
  public void manhattanNAxisTreatsMissingMapEntriesAsZero() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y", "z"};
    Map<String, Double> goal = new HashMap<>();
    goal.put("x", 5.0); // y and z missing
    // current (1,2,3) → goal (x=5,y=0,z=0): |1-5|+|2-0|+|3-0| = 4+2+3 = 9
    assertEquals(9.0, h.manhattanN(axes, new HashMap<>(), mapOf3(1, 2, 3), new HashMap<>(), goal,
        0L, 1.0), EPS);
  }

  @Test
  public void maxAxisNTracksLargestAxisSpan() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y", "z"};
    // Max axis-difference is z: 6
    double r = h.maxAxisN(axes, mapOf3(0, 0, 0), mapOf3(1, 2, 3), mapOf3(0, 0, 0),
        mapOf3(4, 6, 9), 0L, 1.0);
    assertEquals(6.0, r, EPS);
  }

  @Test
  public void diagonalNAxisSumsStraightMinusTwoDiagonalPlusDiagonal() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y"};
    // diagonal-N formula: hDiagonal starts at 0 and uses min with each abs-diff (0 from init
    // makes hDiagonal stay at 0); hStraight = sum of abs-diffs.
    // current (1,2) → goal (4,6): hStraight = 3+4 = 7; hDiagonal = 0 → 2*0 + 1*(7-0) = 7.
    double r = h.diagonalN(axes, new HashMap<>(), Map.of("x", 1.0, "y", 2.0), new HashMap<>(),
        Map.of("x", 4.0, "y", 6.0), 0L, 1.0);
    assertEquals(7.0, r, EPS);
  }

  @Test
  public void euclideanNAxisReturnsSqrtOfSumOfSquaredDifferences() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y"};
    // current (1,2) → goal (4,6): sqrt(9+16) = 5. dFactor not applied by impl (matches source).
    double r = h.euclideanN(axes, new HashMap<>(), Map.of("x", 1.0, "y", 2.0), new HashMap<>(),
        Map.of("x", 4.0, "y", 6.0), 0L, 1.0);
    assertEquals(5.0, r, EPS);
  }

  @Test
  public void euclideanNoSqrNAxisOmitsSqrtAndAppliesDFactor() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y"};
    // current (1,2) → goal (4,6): (9+16) = 25
    double r = h.euclideanNoSqrN(axes, new HashMap<>(), Map.of("x", 1.0, "y", 2.0),
        new HashMap<>(), Map.of("x", 4.0, "y", 6.0), 0L, 1.0);
    assertEquals(25.0, r, EPS);
    // dFactor=2 → 50
    double r2 = h.euclideanNoSqrN(axes, new HashMap<>(), Map.of("x", 1.0, "y", 2.0),
        new HashMap<>(), Map.of("x", 4.0, "y", 6.0), 0L, 2.0);
    assertEquals(50.0, r2, EPS);
  }

  @Test
  public void tieBreakNAxisAddsCrossProductScaledByTenThousandth() {
    var h = new TestHeuristic();
    String[] axes = {"x", "y"};
    // cross = |1-4| + |2-6| = 7 → heuristic 10 + 7*0.0001 = 10.0007
    double r = h.tieBreakN(axes, new HashMap<>(), Map.of("x", 1.0, "y", 2.0), new HashMap<>(),
        Map.of("x", 4.0, "y", 6.0), 0L, 10.0);
    assertEquals(10.0007, r, EPS);
  }

  // --- type-coercion helpers -------------------------------------------------------------

  @Test
  public void stringArrayFromNullReturnsEmptyArray() {
    var h = new TestHeuristic();
    assertArrayEquals(new String[0], h.exposedStringArray(null));
  }

  @Test
  public void stringArrayFromStringSplitsOnComma() {
    var h = new TestHeuristic();
    assertArrayEquals(new String[] {"a", "b", "c"}, h.exposedStringArray("a,b,c"));
  }

  /** Legacy behavior: "},{" is replaced by " ," which makes the separator ambiguous. */
  @Test
  public void stringArrayFromStringHandlesObjectJoinSeparator() {
    var h = new TestHeuristic();
    String[] r = h.exposedStringArray("x},{y");
    // Impl replaces "},{"→" ," then splits on "," — so result is ["x ", "y"].
    assertArrayEquals(new String[] {"x ", "y"}, r);
  }

  @Test
  public void stringArrayFromStringArrayReturnsReference() {
    var h = new TestHeuristic();
    String[] src = {"a", "b"};
    // Production returns the array as-is (cast path).
    assertSame(src, h.exposedStringArray(src));
  }

  /**
   * Non-String, non-array Object hits the {@code instanceof Object} branch which forces a cast
   * to {@code String[]} — ClassCastException is the observed behaviour. Pinning the quirk so
   * future refactors don't silently change it (the trailing {@code return new String[]{}} is
   * dead code since {@code instanceof Object} is always true for non-null).
   */
  @Test(expected = ClassCastException.class)
  public void stringArrayFromNonStringNonArrayObjectThrowsClassCastException() {
    new TestHeuristic().exposedStringArray(Integer.valueOf(5));
  }

  @Test
  public void booleanOrDefaultNullUsesDefault() {
    var h = new TestHeuristic();
    assertEquals(Boolean.TRUE, h.exposedBool(null, true));
    assertEquals(Boolean.FALSE, h.exposedBool(null, false));
  }

  @Test
  public void booleanOrDefaultBooleanIdentity() {
    var h = new TestHeuristic();
    assertEquals(Boolean.TRUE, h.exposedBool(true, false));
    assertEquals(Boolean.FALSE, h.exposedBool(false, true));
  }

  @Test
  public void booleanOrDefaultParsesStringIgnoringCase() {
    var h = new TestHeuristic();
    assertEquals(Boolean.TRUE, h.exposedBool("true", false));
    assertEquals(Boolean.FALSE, h.exposedBool("nope", true)); // Boolean.parseBoolean returns false
  }

  @Test
  public void booleanOrDefaultUnrecognizedTypeReturnsDefault() {
    var h = new TestHeuristic();
    // Non-String, non-Boolean → default path.
    assertEquals(Boolean.TRUE, h.exposedBool(42, true));
  }

  @Test
  public void stringOrDefaultNullReturnsDefault() {
    var h = new TestHeuristic();
    assertEquals("fallback", h.exposedString(null, "fallback"));
    assertEquals("value", h.exposedString("value", "fallback"));
  }

  @Test
  public void integerOrDefaultCoercesNumbersAndStrings() {
    var h = new TestHeuristic();
    assertEquals(Integer.valueOf(5), h.exposedInt(null, 5));
    assertEquals(Integer.valueOf(7), h.exposedInt(7L, -1));
    assertEquals(Integer.valueOf(3), h.exposedInt(3.9, -1)); // Number.intValue() truncates
    assertEquals(Integer.valueOf(42), h.exposedInt("42", -1));
    assertEquals(Integer.valueOf(-1), h.exposedInt("not-a-number", -1));
    assertEquals(Integer.valueOf(-1), h.exposedInt(new Object(), -1));
  }

  @Test
  public void longOrDefaultCoercesNumbersAndStrings() {
    var h = new TestHeuristic();
    assertEquals(Long.valueOf(99), h.exposedLong(null, 99));
    assertEquals(Long.valueOf(5), h.exposedLong((short) 5, -1));
    assertEquals(Long.valueOf(12345), h.exposedLong("12345", -1));
    assertEquals(Long.valueOf(-1), h.exposedLong("oops", -1));
    assertEquals(Long.valueOf(-1), h.exposedLong(new Object(), -1));
  }

  @Test
  public void doubleOrDefaultCoercesNumbersAndStrings() {
    var h = new TestHeuristic();
    assertEquals(Double.valueOf(1.5), h.exposedDouble(null, 1.5));
    assertEquals(Double.valueOf(3.0), h.exposedDouble(3, -1.0));
    assertEquals(Double.valueOf(2.5), h.exposedDouble("2.5", -1.0));
    assertEquals(Double.valueOf(-1.0), h.exposedDouble("oops", -1.0));
    assertEquals(Double.valueOf(-1.0), h.exposedDouble(new Object(), -1.0));
  }

  // --- class-level sanity ---------------------------------------------------------------

  /**
   * The test subclass itself is instantiable — guards against a future abstract method being
   * added to the superclass without forcing test-side updates silently.
   */
  @Test
  public void testSubclassIsInstantiable() {
    var h1 = new TestHeuristic();
    var h2 = new TestHeuristic();
    assertNotNull(h1);
    assertNotNull(h2);
    assertNotSame(h1, h2);
  }
}
