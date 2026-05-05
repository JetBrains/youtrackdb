/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Standalone shape pin for the {@link InPlaceResult} tri-state enum used by
 * {@link EntityImpl}'s in-place comparison fast-path. The enum encodes the
 * comparison outcome as one of {@code TRUE}, {@code FALSE}, or
 * {@code FALLBACK} — the third value telling the caller to abandon the
 * fast-path and run the standard deserialize-then-compare pipeline.
 *
 * <p>Pinning the constant set, ordinal order, and {@code valueOf} round-trip
 * makes a reorder or rename a build-time failure rather than a silent
 * behaviour change at the comparison call sites.
 */
public class InPlaceResultTest {

  /**
   * The enum exposes exactly three constants in the documented order.
   * A reorder or addition would change the {@code ordinal()} mapping
   * downstream call sites might (incorrectly) rely on.
   */
  @Test
  public void testEnumExposesThreeConstantsInDocumentedOrder() {
    var values = InPlaceResult.values();
    assertEquals("InPlaceResult must have exactly 3 constants",
        3, values.length);
    assertSame(InPlaceResult.TRUE, values[0]);
    assertSame(InPlaceResult.FALSE, values[1]);
    assertSame(InPlaceResult.FALLBACK, values[2]);
  }

  /**
   * {@code ordinal()} stability — the enum is referenced as an enum (no
   * persisted serialised form), but a comparator could plausibly rely on
   * the ordinal order. Pinning it makes a reorder fire here first.
   */
  @Test
  public void testOrdinalsAreStable() {
    assertEquals(0, InPlaceResult.TRUE.ordinal());
    assertEquals(1, InPlaceResult.FALSE.ordinal());
    assertEquals(2, InPlaceResult.FALLBACK.ordinal());
  }

  /**
   * {@link Enum#name()} matches the constant identifier. Round-trip via
   * {@link InPlaceResult#valueOf(String)}.
   */
  @Test
  public void testNameAndValueOfRoundTrip() {
    assertEquals("TRUE", InPlaceResult.TRUE.name());
    assertEquals("FALSE", InPlaceResult.FALSE.name());
    assertEquals("FALLBACK", InPlaceResult.FALLBACK.name());

    assertSame(InPlaceResult.TRUE, InPlaceResult.valueOf("TRUE"));
    assertSame(InPlaceResult.FALSE, InPlaceResult.valueOf("FALSE"));
    assertSame(InPlaceResult.FALLBACK, InPlaceResult.valueOf("FALLBACK"));
  }
}
