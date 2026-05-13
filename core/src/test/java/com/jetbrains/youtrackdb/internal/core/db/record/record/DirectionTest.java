/*
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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Standalone unit tests for the {@link Direction} enum: pins the value set, ordinals, and the
 * {@link Direction#opposite()} mapping. {@code Direction} is consumed everywhere in the graph
 * layer (vertex edge enumeration, edge endpoint resolution, query planning) and reordering or
 * renaming a constant would silently change call-site dispatch — these tests pin the contract so
 * such a change fails loudly.
 */
public class DirectionTest {

  /**
   * Pins the public set of values and their declaration order. The ordinal is used implicitly by
   * any caller that switches on {@code Direction} or persists the enum by ordinal — reordering
   * would silently shift behaviour.
   */
  @Test
  public void valuesAndOrdinalsArePinned() {
    var values = Direction.values();
    assertArrayEquals(new Direction[] {Direction.OUT, Direction.IN, Direction.BOTH}, values);
    assertEquals(0, Direction.OUT.ordinal());
    assertEquals(1, Direction.IN.ordinal());
    assertEquals(2, Direction.BOTH.ordinal());
  }

  /** Pins the canonical names used in vertex field prefix lookup ({@code out_}, {@code in_}). */
  @Test
  public void namesArePinned() {
    assertEquals("OUT", Direction.OUT.name());
    assertEquals("IN", Direction.IN.name());
    assertEquals("BOTH", Direction.BOTH.name());
  }

  /** {@link Direction#valueOf(String)} resolves the three constants by exact uppercase name. */
  @Test
  public void valueOfReturnsMatchingConstant() {
    assertSame(Direction.OUT, Direction.valueOf("OUT"));
    assertSame(Direction.IN, Direction.valueOf("IN"));
    assertSame(Direction.BOTH, Direction.valueOf("BOTH"));
  }

  /** {@link Direction#valueOf(String)} rejects unknown / lowercase names. */
  @Test(expected = IllegalArgumentException.class)
  public void valueOfRejectsLowercase() {
    Direction.valueOf("out");
  }

  /** OUT and IN are mutually opposite: {@code opposite().opposite() == this}. */
  @Test
  public void oppositeOfOutIsIn() {
    assertSame(Direction.IN, Direction.OUT.opposite());
  }

  @Test
  public void oppositeOfInIsOut() {
    assertSame(Direction.OUT, Direction.IN.opposite());
  }

  /**
   * BOTH is its own opposite — falsifies a hypothetical change that mapped BOTH→OUT or BOTH→IN
   * (which would silently break the symmetry contract callers rely on).
   */
  @Test
  public void oppositeOfBothIsBoth() {
    assertSame(Direction.BOTH, Direction.BOTH.opposite());
  }

  /** Double-application is the identity for every value (involution check). */
  @Test
  public void oppositeIsInvolution() {
    for (var d : Direction.values()) {
      assertSame(d, d.opposite().opposite());
    }
  }

  /** {@link Direction#opposite()} never returns null per {@code @Nonnull} contract. */
  @Test
  public void oppositeIsNeverNull() {
    for (var d : Direction.values()) {
      assertNotNull(d.opposite());
    }
  }
}
