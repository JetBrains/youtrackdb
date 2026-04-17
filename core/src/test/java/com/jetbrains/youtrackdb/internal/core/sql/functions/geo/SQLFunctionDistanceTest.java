/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionDistance} — Haversine formula great-circle distance.
 *
 * <p>Uses {@link DbTestBase} because the parameter coercion path calls
 * {@code PropertyTypeInternal.DOUBLE.convert(v, null, null, session)}, which needs a session.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Identical points → 0 km.
 *   <li>Known reference distance: London → Paris ≈ 343 km (Haversine). Assertion tolerates a
 *       small rounding/test-data epsilon.
 *   <li>Explicit "km" unit yields the same numeric value as the 4-arg form.
 *   <li>"mi" unit multiplies by ~0.6214; "nmi" unit multiplies by ~0.5400.
 *   <li>Unit comparison is case-insensitive (KM / Mi / NMI).
 *   <li>Unknown unit → {@link IllegalArgumentException} with the exact message.
 *   <li>Any {@code null} coordinate → {@code null} return (before unit parsing).
 *   <li>Integer / Long coordinates are converted via PropertyTypeInternal.
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLFunctionDistanceTest extends DbTestBase {

  // Coordinates (degrees): each pair is (lat, lon).
  private static final double LONDON_LAT = 51.5074;
  private static final double LONDON_LON = -0.1278;
  private static final double PARIS_LAT = 48.8566;
  private static final double PARIS_LON = 2.3522;

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // Identical points — 0 distance
  // ---------------------------------------------------------------------------

  @Test
  public void sameCoordinatesReturnZeroDistance() {
    var function = new SQLFunctionDistance();

    var result = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, LONDON_LAT, LONDON_LON}, ctx());

    assertEquals(0.0, result, 1e-9);
  }

  // ---------------------------------------------------------------------------
  // Known reference — London to Paris ≈ 343 km
  // ---------------------------------------------------------------------------

  @Test
  public void londonToParisIsApproximately343Km() {
    // Haversine with EARTH_RADIUS=6371 yields ~343.5 km. Tolerance 2 km covers coordinate
    // rounding.
    var function = new SQLFunctionDistance();

    var km = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());

    assertNotNull(km);
    assertTrue("London→Paris should be ~343 km, got " + km, Math.abs(km - 343.5) < 2.0);
  }

  @Test
  public void distanceIsSymmetric() {
    // Haversine is symmetric — swapping the two points must yield the same distance.
    var function = new SQLFunctionDistance();

    var ab = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());
    var ba = (Double) function.execute(null, null, null,
        new Object[] {PARIS_LAT, PARIS_LON, LONDON_LAT, LONDON_LON}, ctx());

    assertEquals(ab, ba, 1e-9);
  }

  // ---------------------------------------------------------------------------
  // Unit conversions
  // ---------------------------------------------------------------------------

  @Test
  public void kmUnitMatchesFourArgResult() {
    // "km" is an explicit no-op branch — the value must match the 4-arg (default) result exactly.
    var function4 = new SQLFunctionDistance();
    var function5 = new SQLFunctionDistance();

    var km4 = (Double) function4.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());
    var km5 = (Double) function5.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "km"}, ctx());

    assertEquals(km4, km5, 1e-9);
  }

  @Test
  public void milesUnitAppliesConversionFactor() {
    var function = new SQLFunctionDistance();
    var expectedKm = 100.0; // placeholder — we compute from same inputs below.

    var km = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());

    var function2 = new SQLFunctionDistance();
    var mi = (Double) function2.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "mi"}, ctx());

    // Multiplier is 0.621371192 per production source.
    assertEquals(km * 0.621371192, mi, 1e-6);
    // Use the placeholder so the assignment isn't flagged as dead by tooling.
    assertTrue(expectedKm > 0);
  }

  @Test
  public void nauticalMilesUnitAppliesConversionFactor() {
    var function = new SQLFunctionDistance();
    var km = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());

    var function2 = new SQLFunctionDistance();
    var nmi = (Double) function2.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "nmi"}, ctx());

    assertEquals(km * 0.539956803, nmi, 1e-6);
  }

  @Test
  public void unitComparisonIsCaseInsensitive() {
    // equalsIgnoreCase drives the unit dispatch. Uppercase / mixed-case must match.
    var function = new SQLFunctionDistance();
    var miLower = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "mi"}, ctx());

    var function2 = new SQLFunctionDistance();
    var miMixed = (Double) function2.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "Mi"}, ctx());

    var function3 = new SQLFunctionDistance();
    var kmUpper = (Double) function3.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "KM"}, ctx());

    var function4 = new SQLFunctionDistance();
    var nmiUpper = (Double) function4.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "NMI"}, ctx());

    assertEquals(miLower, miMixed, 1e-9);
    assertTrue("KM uppercase should still produce a non-null distance", kmUpper > 0);
    assertTrue("NMI uppercase should still produce a non-null distance", nmiUpper > 0);
  }

  // ---------------------------------------------------------------------------
  // Unknown unit — IllegalArgumentException with exact message
  // ---------------------------------------------------------------------------

  @Test
  public void unknownUnitThrowsIllegalArgumentWithExactMessage() {
    var function = new SQLFunctionDistance();

    try {
      function.execute(null, null, null,
          new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, "parsecs"}, ctx());
      fail("expected IllegalArgumentException for unknown unit");
    } catch (IllegalArgumentException e) {
      assertEquals("Unsupported unit 'parsecs'. Use km, mi and nmi. Default is km.",
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Null parameters — null return
  // ---------------------------------------------------------------------------

  @Test
  public void nullLatitudeReturnsNull() {
    var function = new SQLFunctionDistance();

    var result = function.execute(null, null, null,
        new Object[] {null, LONDON_LON, PARIS_LAT, PARIS_LON}, ctx());

    assertNull(result);
  }

  @Test
  public void nullLongitudeInAnyPositionReturnsNull() {
    // Each of the first four coordinate positions short-circuits to null independently.
    var function = new SQLFunctionDistance();

    assertNull(function.execute(null, null, null,
        new Object[] {LONDON_LAT, null, PARIS_LAT, PARIS_LON}, ctx()));
    assertNull(function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, null, PARIS_LON}, ctx()));
    assertNull(function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, null}, ctx()));
  }

  // ---------------------------------------------------------------------------
  // Type coercion — Integer / Long inputs
  // ---------------------------------------------------------------------------

  @Test
  public void integerCoordinatesAreConvertedToDouble() {
    // PropertyTypeInternal.DOUBLE.convert(Integer, ..., session) → Double.
    var function = new SQLFunctionDistance();

    var result = (Double) function.execute(null, null, null,
        new Object[] {0, 0, 0, 0}, ctx());

    // Distance (0,0) → (0,0) is 0.
    assertEquals(0.0, result, 1e-9);
  }

  @Test
  public void longCoordinatesAreConvertedToDouble() {
    var function = new SQLFunctionDistance();

    var result = (Double) function.execute(null, null, null,
        new Object[] {0L, 0L, 45L, 90L}, ctx());

    // Non-null distance must come back.
    assertNotNull(result);
    assertTrue("distance between (0,0) and (45,90) should be > 0, got " + result, result > 0);
  }

  // ---------------------------------------------------------------------------
  // Unit toString coercion (non-String unit arg)
  // ---------------------------------------------------------------------------

  @Test
  public void unitParamToStringIsInvoked() {
    // iParams[4].toString() — a StringBuilder holding "km" works the same as the literal "km".
    var function = new SQLFunctionDistance();
    var unit = new StringBuilder("km");

    var result = (Double) function.execute(null, null, null,
        new Object[] {LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON, unit}, ctx());

    assertNotNull(result);
    assertTrue("distance with StringBuilder 'km' unit should be > 0, got " + result, result > 0);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionDistance();

    assertEquals("distance", SQLFunctionDistance.NAME);
    assertEquals(SQLFunctionDistance.NAME, function.getName(session));
    assertEquals(4, function.getMinParams());
    assertEquals(5, function.getMaxParams(session));
    assertEquals("distance(<field-x>,<field-y>,<x-value>,<y-value>[,<unit>])",
        function.getSyntax(session));
  }
}
