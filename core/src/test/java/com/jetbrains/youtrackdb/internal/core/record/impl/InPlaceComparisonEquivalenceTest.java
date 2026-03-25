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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.Test;

/**
 * Cross-path equivalence tests verifying that in-place comparison (both the serialized/source path
 * and the deserialized/properties-map path) produces identical results to the standard {@code
 * getProperty()} + Java comparison path for all supported types and edge cases.
 *
 * <p>Pattern: create entity with property, serialize to source-only form, then assert that {@code
 * isPropertyEqualTo} / {@code comparePropertyTo} matches the result of {@code getProperty()} +
 * Java comparison.
 */
public class InPlaceComparisonEquivalenceTest extends DbTestBase {

  /**
   * Creates a new entity with source bytes set but properties NOT deserialized. Exercises the
   * serialized (InPlaceComparator) path.
   */
  private EntityImpl serializeWithSourceOnly(EntityImpl entity) {
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, entity);
    var reloaded = (EntityImpl) session.newEntity();
    reloaded.unsetDirty();
    reloaded.fromStream(bytes);
    return reloaded;
  }

  /**
   * Creates a new entity, serializes it, and fully deserializes it into a fresh entity. Properties
   * are populated in memory; exercises the deserialized (properties map) path.
   */
  private EntityImpl serializeAndReload(EntityImpl entity) {
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, entity);
    var reloaded = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, reloaded, null);
    return reloaded;
  }

  /**
   * Computes the expected equality result by using standard getProperty() + Java comparison. If the
   * property is null or value is null, returns FALLBACK (matching in-place behavior).
   */
  private InPlaceResult expectedEquality(EntityImpl entity, String name, Object value) {
    var propValue = entity.getProperty(name);
    if (propValue == null || value == null) {
      return InPlaceResult.FALLBACK;
    }
    if (propValue.equals(value)) {
      return InPlaceResult.TRUE;
    }
    // For numeric cross-type, standard equals may return false even when values
    // are logically equal (e.g., Integer(42).equals(Long(42)) == false).
    // The in-place path does type conversion, so we need to check numeric equality.
    if (propValue instanceof Number pn && value instanceof Number vn) {
      if (propValue instanceof BigDecimal bd) {
        BigDecimal other;
        if (value instanceof BigDecimal bdv) {
          other = bdv;
        } else if (vn instanceof Double || vn instanceof Float) {
          double dv = vn.doubleValue();
          if (Double.isNaN(dv) || Double.isInfinite(dv)) {
            return InPlaceResult.FALLBACK;
          }
          other = BigDecimal.valueOf(dv);
        } else {
          other = BigDecimal.valueOf(vn.longValue());
        }
        return bd.compareTo(other) == 0 ? InPlaceResult.TRUE : InPlaceResult.FALSE;
      }
      if (propValue instanceof Float f) {
        if (value instanceof Double d) {
          return Double.compare(f, d) == 0 ? InPlaceResult.TRUE : InPlaceResult.FALSE;
        }
        // Guard: integer values beyond float's exact range must fall back
        if (!(vn instanceof Float)) {
          long lv = vn.longValue();
          if (lv > (1 << 24) || lv < -(1 << 24)) {
            return InPlaceResult.FALLBACK;
          }
        }
        return Float.compare(f, vn.floatValue()) == 0
            ? InPlaceResult.TRUE : InPlaceResult.FALSE;
      }
      if (propValue instanceof Double d) {
        // Guard: long values beyond double's exact range must fall back
        if (vn instanceof Long || vn instanceof Integer) {
          long lv = vn.longValue();
          if (lv > (1L << 53) || lv < -(1L << 53)) {
            return InPlaceResult.FALLBACK;
          }
        }
        return Double.compare(d, vn.doubleValue()) == 0
            ? InPlaceResult.TRUE : InPlaceResult.FALSE;
      }
      // Float/Double → integer: fall back to match production behavior
      if (vn instanceof Float || vn instanceof Double) {
        return InPlaceResult.FALLBACK;
      }
      // Integer types: compare via long
      return Long.compare(pn.longValue(), vn.longValue()) == 0
          ? InPlaceResult.TRUE : InPlaceResult.FALSE;
    }
    // For byte arrays, standard equals uses reference equality — use Arrays.equals
    if (propValue instanceof byte[] pb && value instanceof byte[] vb) {
      return Arrays.equals(pb, vb) ? InPlaceResult.TRUE : InPlaceResult.FALSE;
    }
    return InPlaceResult.FALSE;
  }

  /**
   * Computes the expected ordering result using standard getProperty() + Java comparison.
   */
  @SuppressWarnings("unchecked")
  private OptionalInt expectedOrdering(EntityImpl entity, String name, Object value) {
    var propValue = entity.getProperty(name);
    if (propValue == null || value == null) {
      return OptionalInt.empty();
    }
    if (propValue instanceof Float f && value instanceof Number n) {
      if (value instanceof Double d) {
        return OptionalInt.of(Double.compare(f, d));
      }
      // Guard: integer values beyond float's exact range must fall back
      if (!(n instanceof Float)) {
        long lv = n.longValue();
        if (lv > (1 << 24) || lv < -(1 << 24)) {
          return OptionalInt.empty();
        }
      }
      return OptionalInt.of(Float.compare(f, n.floatValue()));
    }
    if (propValue instanceof Double d && value instanceof Number n) {
      // Guard: long values beyond double's exact range must fall back
      if (n instanceof Long || n instanceof Integer) {
        long lv = n.longValue();
        if (lv > (1L << 53) || lv < -(1L << 53)) {
          return OptionalInt.empty();
        }
      }
      return OptionalInt.of(Double.compare(d, n.doubleValue()));
    }
    if (propValue instanceof BigDecimal bd) {
      BigDecimal other;
      if (value instanceof BigDecimal bdv) {
        other = bdv;
      } else if (value instanceof Number n) {
        if (n instanceof Double || n instanceof Float) {
          double dv = n.doubleValue();
          if (Double.isNaN(dv) || Double.isInfinite(dv)) {
            return OptionalInt.empty();
          }
          other = BigDecimal.valueOf(dv);
        } else {
          other = BigDecimal.valueOf(n.longValue());
        }
      } else {
        return OptionalInt.empty();
      }
      return OptionalInt.of(bd.compareTo(other));
    }
    if (propValue instanceof Number pn && value instanceof Number vn) {
      // Float/Double → integer: fall back to match production behavior
      if (vn instanceof Float || vn instanceof Double) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(Long.compare(pn.longValue(), vn.longValue()));
    }
    if (propValue instanceof byte[] pb && value instanceof byte[] vb) {
      return OptionalInt.of(Arrays.compare(pb, vb));
    }
    if (propValue instanceof Comparable c) {
      try {
        return OptionalInt.of(c.compareTo(value));
      } catch (ClassCastException e) {
        return OptionalInt.empty();
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Asserts equivalence of in-place equality check (source path) with the standard Java path.
   */
  private void assertEqualityEquivalence(
      EntityImpl sourceEntity, EntityImpl javaEntity, String name, Object value) {
    var expected = expectedEquality(javaEntity, name, value);
    var actual = sourceEntity.isPropertyEqualTo(name, value);
    assertEquals(
        "isPropertyEqualTo mismatch for property '" + name + "' with value " + value,
        expected, actual);
  }

  /**
   * Asserts equivalence of in-place ordering check (source path) with the standard Java path.
   * Compares sign of ordering result rather than exact value, since different comparison
   * implementations may return different magnitudes.
   */
  private void assertOrderingEquivalence(
      EntityImpl sourceEntity, EntityImpl javaEntity, String name, Object value) {
    var expected = expectedOrdering(javaEntity, name, value);
    var actual = sourceEntity.comparePropertyTo(name, value);
    assertEquals(
        "comparePropertyTo emptiness mismatch for property '" + name + "' with value " + value,
        expected.isEmpty(), actual.isEmpty());
    if (expected.isPresent() && actual.isPresent()) {
      assertEquals(
          "comparePropertyTo sign mismatch for property '" + name + "' with value " + value,
          Integer.signum(expected.getAsInt()), Integer.signum(actual.getAsInt()));
    }
  }

  // ===========================================================================
  // INTEGER
  // ===========================================================================

  /** INTEGER property: matching, greater, less values. */
  @Test
  public void testIntegerEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42, PropertyType.INTEGER);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42);
    assertEqualityEquivalence(source, deserialized, "val", 99);
    assertOrderingEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 10);
    assertOrderingEquivalence(source, deserialized, "val", 100);
    session.rollback();
  }

  /** INTEGER property with Long comparison value — cross-type conversion. */
  @Test
  public void testIntegerWithLongValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42, PropertyType.INTEGER);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42L);
    assertEqualityEquivalence(source, deserialized, "val", 99L);
    assertOrderingEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 10L);
    session.rollback();
  }

  /** INTEGER property with Short comparison value — cross-type conversion. */
  @Test
  public void testIntegerWithShortValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42, PropertyType.INTEGER);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", (short) 42);
    assertEqualityEquivalence(source, deserialized, "val", (short) 10);
    assertOrderingEquivalence(source, deserialized, "val", (short) 42);
    session.rollback();
  }

  // ===========================================================================
  // LONG
  // ===========================================================================

  /** LONG property: matching, greater, less values. */
  @Test
  public void testLongEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 1_000_000_000_000L, PropertyType.LONG);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 1_000_000_000_000L);
    assertEqualityEquivalence(source, deserialized, "val", 999L);
    assertOrderingEquivalence(source, deserialized, "val", 1_000_000_000_000L);
    assertOrderingEquivalence(source, deserialized, "val", 0L);
    assertOrderingEquivalence(source, deserialized, "val", Long.MAX_VALUE);
    session.rollback();
  }

  /** LONG property with Integer comparison value. */
  @Test
  public void testLongWithIntegerValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42L, PropertyType.LONG);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 100);
    session.rollback();
  }

  // ===========================================================================
  // SHORT
  // ===========================================================================

  /** SHORT property equivalence. */
  @Test
  public void testShortEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", (short) 100, PropertyType.SHORT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", (short) 100);
    assertEqualityEquivalence(source, deserialized, "val", (short) 50);
    assertOrderingEquivalence(source, deserialized, "val", (short) 100);
    assertOrderingEquivalence(source, deserialized, "val", (short) 200);
    session.rollback();
  }

  /** SHORT property with Integer comparison value. */
  @Test
  public void testShortWithIntegerValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", (short) 42, PropertyType.SHORT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 42);
    session.rollback();
  }

  // ===========================================================================
  // BYTE
  // ===========================================================================

  /** BYTE property equivalence. */
  @Test
  public void testByteEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", (byte) 7, PropertyType.BYTE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", (byte) 7);
    assertEqualityEquivalence(source, deserialized, "val", (byte) 0);
    assertOrderingEquivalence(source, deserialized, "val", (byte) 7);
    assertOrderingEquivalence(source, deserialized, "val", (byte) 127);
    session.rollback();
  }

  // ===========================================================================
  // FLOAT
  // ===========================================================================

  /** FLOAT property equivalence. */
  @Test
  public void testFloatEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 3.14f, PropertyType.FLOAT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 3.14f);
    assertEqualityEquivalence(source, deserialized, "val", 2.71f);
    assertOrderingEquivalence(source, deserialized, "val", 3.14f);
    assertOrderingEquivalence(source, deserialized, "val", 0.0f);
    assertOrderingEquivalence(source, deserialized, "val", 100.0f);
    session.rollback();
  }

  /** FLOAT edge cases: NaN, -0.0, infinities. */
  @Test
  public void testFloatEdgeCases() {
    session.begin();

    // NaN — NaN != NaN per IEEE 754, but Float.compare(NaN, NaN) == 0
    var nanEntity = (EntityImpl) session.newEntity();
    nanEntity.setProperty("val", Float.NaN, PropertyType.FLOAT);
    var nanSource = serializeWithSourceOnly(nanEntity);
    var nanJava = serializeAndReload(nanEntity);
    assertEqualityEquivalence(nanSource, nanJava, "val", Float.NaN);
    assertOrderingEquivalence(nanSource, nanJava, "val", Float.NaN);
    assertOrderingEquivalence(nanSource, nanJava, "val", 1.0f);

    // -0.0 vs +0.0 — Float.compare distinguishes them
    var negZero = (EntityImpl) session.newEntity();
    negZero.setProperty("val", -0.0f, PropertyType.FLOAT);
    var negZeroSource = serializeWithSourceOnly(negZero);
    var negZeroJava = serializeAndReload(negZero);
    assertEqualityEquivalence(negZeroSource, negZeroJava, "val", 0.0f);
    assertOrderingEquivalence(negZeroSource, negZeroJava, "val", 0.0f);

    // +Infinity
    var posInf = (EntityImpl) session.newEntity();
    posInf.setProperty("val", Float.POSITIVE_INFINITY, PropertyType.FLOAT);
    var posInfSource = serializeWithSourceOnly(posInf);
    var posInfJava = serializeAndReload(posInf);
    assertEqualityEquivalence(posInfSource, posInfJava, "val", Float.POSITIVE_INFINITY);
    assertOrderingEquivalence(posInfSource, posInfJava, "val", Float.MAX_VALUE);

    // -Infinity
    var negInf = (EntityImpl) session.newEntity();
    negInf.setProperty("val", Float.NEGATIVE_INFINITY, PropertyType.FLOAT);
    var negInfSource = serializeWithSourceOnly(negInf);
    var negInfJava = serializeAndReload(negInf);
    assertEqualityEquivalence(negInfSource, negInfJava, "val", Float.NEGATIVE_INFINITY);
    assertOrderingEquivalence(negInfSource, negInfJava, "val", -Float.MAX_VALUE);

    session.rollback();
  }

  /** FLOAT with Double comparison value — widened to double precision. */
  @Test
  public void testFloatWithDoubleValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 3.14f, PropertyType.FLOAT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", (double) 3.14f);
    assertOrderingEquivalence(source, deserialized, "val", (double) 3.14f);
    assertOrderingEquivalence(source, deserialized, "val", 100.0);
    session.rollback();
  }

  // ===========================================================================
  // DOUBLE
  // ===========================================================================

  /** DOUBLE property equivalence. */
  @Test
  public void testDoubleEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 2.718281828, PropertyType.DOUBLE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 2.718281828);
    assertEqualityEquivalence(source, deserialized, "val", 3.14);
    assertOrderingEquivalence(source, deserialized, "val", 2.718281828);
    assertOrderingEquivalence(source, deserialized, "val", 0.0);
    assertOrderingEquivalence(source, deserialized, "val", 1000.0);
    session.rollback();
  }

  /** DOUBLE edge cases: NaN, -0.0, infinities. */
  @Test
  public void testDoubleEdgeCases() {
    session.begin();

    var nanEntity = (EntityImpl) session.newEntity();
    nanEntity.setProperty("val", Double.NaN, PropertyType.DOUBLE);
    var nanSource = serializeWithSourceOnly(nanEntity);
    var nanJava = serializeAndReload(nanEntity);
    assertEqualityEquivalence(nanSource, nanJava, "val", Double.NaN);
    assertOrderingEquivalence(nanSource, nanJava, "val", Double.NaN);
    assertOrderingEquivalence(nanSource, nanJava, "val", 1.0);

    var negZero = (EntityImpl) session.newEntity();
    negZero.setProperty("val", -0.0, PropertyType.DOUBLE);
    var negZeroSource = serializeWithSourceOnly(negZero);
    var negZeroJava = serializeAndReload(negZero);
    assertEqualityEquivalence(negZeroSource, negZeroJava, "val", 0.0);
    assertOrderingEquivalence(negZeroSource, negZeroJava, "val", 0.0);

    var posInf = (EntityImpl) session.newEntity();
    posInf.setProperty("val", Double.POSITIVE_INFINITY, PropertyType.DOUBLE);
    var posInfSource = serializeWithSourceOnly(posInf);
    var posInfJava = serializeAndReload(posInf);
    assertEqualityEquivalence(posInfSource, posInfJava, "val", Double.POSITIVE_INFINITY);
    assertOrderingEquivalence(posInfSource, posInfJava, "val", Double.MAX_VALUE);

    // -Infinity
    var negInf = (EntityImpl) session.newEntity();
    negInf.setProperty("val", Double.NEGATIVE_INFINITY, PropertyType.DOUBLE);
    var negInfSource = serializeWithSourceOnly(negInf);
    var negInfDeserialized = serializeAndReload(negInf);
    assertEqualityEquivalence(negInfSource, negInfDeserialized,
        "val", Double.NEGATIVE_INFINITY);
    assertOrderingEquivalence(negInfSource, negInfDeserialized,
        "val", -Double.MAX_VALUE);

    session.rollback();
  }

  /** DOUBLE with Integer comparison value. */
  @Test
  public void testDoubleWithIntegerValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42.0, PropertyType.DOUBLE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 100);
    session.rollback();
  }

  // ===========================================================================
  // Precision boundaries
  // ===========================================================================

  /** INT→FLOAT precision boundary: 2^24 ± 1. Above 2^24, integer→float loses precision. */
  @Test
  public void testIntToFloatPrecisionBoundary() {
    session.begin();

    // Exactly at boundary — should succeed
    var atBoundary = (EntityImpl) session.newEntity();
    atBoundary.setProperty("val", (1 << 24), PropertyType.INTEGER);
    var atSrc = serializeWithSourceOnly(atBoundary);
    var atJava = serializeAndReload(atBoundary);
    assertEqualityEquivalence(atSrc, atJava, "val", (1 << 24));
    assertOrderingEquivalence(atSrc, atJava, "val", (1 << 24));

    // Below boundary — should succeed
    var belowBoundary = (EntityImpl) session.newEntity();
    belowBoundary.setProperty("val", (1 << 24) - 1, PropertyType.INTEGER);
    var belowSrc = serializeWithSourceOnly(belowBoundary);
    var belowDeserialized = serializeAndReload(belowBoundary);
    assertEqualityEquivalence(belowSrc, belowDeserialized, "val", (1 << 24) - 1);

    // Above boundary — (1 << 24) + 1 cannot be exactly represented as float
    var aboveBoundary = (EntityImpl) session.newEntity();
    aboveBoundary.setProperty("val", (1 << 24) + 1, PropertyType.INTEGER);
    var aboveSrc = serializeWithSourceOnly(aboveBoundary);
    var aboveDeserialized = serializeAndReload(aboveBoundary);
    assertEqualityEquivalence(aboveSrc, aboveDeserialized, "val", (1 << 24) + 1);
    assertOrderingEquivalence(aboveSrc, aboveDeserialized, "val", (1 << 24) + 1);

    session.rollback();
  }

  /** LONG→DOUBLE precision boundary: 2^53 ± 1. Above 2^53, long→double loses precision. */
  @Test
  public void testLongToDoublePrecisionBoundary() {
    session.begin();

    // Exactly at boundary
    var atBoundary = (EntityImpl) session.newEntity();
    atBoundary.setProperty("val", (1L << 53), PropertyType.LONG);
    var atSrc = serializeWithSourceOnly(atBoundary);
    var atDeserialized = serializeAndReload(atBoundary);
    assertEqualityEquivalence(atSrc, atDeserialized, "val", (1L << 53));
    assertOrderingEquivalence(atSrc, atDeserialized, "val", (1L << 53));

    // Below boundary
    var belowBoundary = (EntityImpl) session.newEntity();
    belowBoundary.setProperty("val", (1L << 53) - 1, PropertyType.LONG);
    var belowSrc = serializeWithSourceOnly(belowBoundary);
    var belowDeserialized = serializeAndReload(belowBoundary);
    assertEqualityEquivalence(belowSrc, belowDeserialized, "val", (1L << 53) - 1);

    // Above boundary — (1L << 53) + 1 cannot be exactly represented as double
    var aboveBoundary = (EntityImpl) session.newEntity();
    aboveBoundary.setProperty("val", (1L << 53) + 1, PropertyType.LONG);
    var aboveSrc = serializeWithSourceOnly(aboveBoundary);
    var aboveDeserialized = serializeAndReload(aboveBoundary);
    assertEqualityEquivalence(aboveSrc, aboveDeserialized, "val", (1L << 53) + 1);
    assertOrderingEquivalence(aboveSrc, aboveDeserialized, "val", (1L << 53) + 1);

    session.rollback();
  }

  /**
   * FLOAT property compared with Integer above 2^24 — must fall back because float cannot
   * represent the integer exactly. Both source and deserialized paths must agree.
   */
  @Test
  public void testFloatPropertyWithIntegerAbovePrecisionBoundary() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 16_777_216.0f, PropertyType.FLOAT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // 16_777_217 > 2^24, cannot be exactly represented as float → FALLBACK
    assertEqualityEquivalence(source, deserialized, "val", 16_777_217);
    assertOrderingEquivalence(source, deserialized, "val", 16_777_217);
    // At boundary — should succeed
    assertEqualityEquivalence(source, deserialized, "val", 16_777_216);
    assertOrderingEquivalence(source, deserialized, "val", 16_777_216);
    session.rollback();
  }

  /**
   * DOUBLE property compared with Long above 2^53 — must fall back because double cannot
   * represent the long exactly. Both source and deserialized paths must agree.
   */
  @Test
  public void testDoublePropertyWithLongAbovePrecisionBoundary() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 9_007_199_254_740_992.0, PropertyType.DOUBLE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // 2^53 + 1, cannot be exactly represented as double → FALLBACK
    assertEqualityEquivalence(source, deserialized, "val", 9_007_199_254_740_993L);
    assertOrderingEquivalence(source, deserialized, "val", 9_007_199_254_740_993L);
    // At boundary — should succeed
    assertEqualityEquivalence(source, deserialized, "val", 9_007_199_254_740_992L);
    assertOrderingEquivalence(source, deserialized, "val", 9_007_199_254_740_992L);
    session.rollback();
  }

  // ===========================================================================
  // STRING
  // ===========================================================================

  /** STRING property: matching, non-matching, ordering. */
  @Test
  public void testStringEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", "Hello, World!");

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", "Hello, World!");
    assertEqualityEquivalence(source, deserialized, "val", "Goodbye");
    assertOrderingEquivalence(source, deserialized, "val", "Hello, World!");
    assertOrderingEquivalence(source, deserialized, "val", "AAA");
    assertOrderingEquivalence(source, deserialized, "val", "ZZZ");
    session.rollback();
  }

  /** STRING with unicode characters. */
  @Test
  public void testStringUnicode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", "\u00e9\u00e0\u00fc \u4e16\u754c");

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", "\u00e9\u00e0\u00fc \u4e16\u754c");
    assertEqualityEquivalence(source, deserialized, "val", "plain ascii");
    assertOrderingEquivalence(source, deserialized, "val", "\u00e9\u00e0\u00fc \u4e16\u754c");
    session.rollback();
  }

  /**
   * Empty string — known to fall back via deserializeField returning null for zero-length fields.
   * Both source and Java paths should agree on FALLBACK behavior.
   */
  @Test
  public void testEmptyStringFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", "");

    // Source path: empty string is handled by deserializeField + InPlaceComparator
    var source = serializeWithSourceOnly(entity);
    assertEquals(
        "Empty string via source path should match",
        InPlaceResult.TRUE, source.isPropertyEqualTo("val", ""));

    // Deserialized path: properties map retains the empty string, so equality succeeds
    var deserialized = serializeAndReload(entity);
    assertEquals(
        "Empty string via deserialized path should match",
        InPlaceResult.TRUE, deserialized.isPropertyEqualTo("val", ""));
    session.rollback();
  }

  // ===========================================================================
  // BOOLEAN
  // ===========================================================================

  /** BOOLEAN property equivalence. */
  @Test
  public void testBooleanEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", true, PropertyType.BOOLEAN);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", true);
    assertEqualityEquivalence(source, deserialized, "val", false);
    assertOrderingEquivalence(source, deserialized, "val", true);
    assertOrderingEquivalence(source, deserialized, "val", false);

    // Also test false property
    var falseEntity = (EntityImpl) session.newEntity();
    falseEntity.setProperty("val", false, PropertyType.BOOLEAN);
    var falseSrc = serializeWithSourceOnly(falseEntity);
    var falseJava = serializeAndReload(falseEntity);
    assertEqualityEquivalence(falseSrc, falseJava, "val", false);
    assertEqualityEquivalence(falseSrc, falseJava, "val", true);

    session.rollback();
  }

  // ===========================================================================
  // DATETIME
  // ===========================================================================

  /** DATETIME property equivalence. */
  @Test
  public void testDatetimeEquivalence() {
    session.begin();
    var now = new Date();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", now, PropertyType.DATETIME);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", now);
    assertEqualityEquivalence(source, deserialized, "val", new Date(0));
    assertOrderingEquivalence(source, deserialized, "val", now);
    assertOrderingEquivalence(source, deserialized, "val", new Date(0));
    assertOrderingEquivalence(source, deserialized, "val", new Date(Long.MAX_VALUE));
    session.rollback();
  }

  /** DATETIME with epoch zero. */
  @Test
  public void testDatetimeEpochZero() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", new Date(0), PropertyType.DATETIME);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", new Date(0));
    assertOrderingEquivalence(source, deserialized, "val", new Date(1000));
    session.rollback();
  }

  // ===========================================================================
  // DATE (day-level precision, timezone-sensitive)
  // ===========================================================================

  /** DATE property equivalence — uses convertDayToTimezone internally. */
  @Test
  public void testDateEquivalence() {
    session.begin();
    // Use a date well past epoch to avoid timezone edge cases at day boundary
    var date = new Date(1700000000000L); // 2023-11-14
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", date, PropertyType.DATE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // DATE truncates to day precision; use the reloaded value as the comparison target
    var reloadedDate = (Date) deserialized.getProperty("val");
    assertEqualityEquivalence(source, deserialized, "val", reloadedDate);
    assertOrderingEquivalence(source, deserialized, "val", reloadedDate);
    // Compare with a different date
    assertOrderingEquivalence(source, deserialized, "val", new Date(0));
    session.rollback();
  }

  // ===========================================================================
  // DECIMAL
  // ===========================================================================

  /** DECIMAL property equivalence. */
  @Test
  public void testDecimalEquivalence() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", new BigDecimal("123.456"), PropertyType.DECIMAL);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", new BigDecimal("123.456"));
    assertEqualityEquivalence(source, deserialized, "val", new BigDecimal("999.999"));
    assertOrderingEquivalence(source, deserialized, "val", new BigDecimal("123.456"));
    assertOrderingEquivalence(source, deserialized, "val", BigDecimal.ZERO);
    assertOrderingEquivalence(source, deserialized, "val", new BigDecimal("1000"));
    session.rollback();
  }

  /** DECIMAL with different scales — 1.0 vs 1 should be equal via compareTo (not equals). */
  @Test
  public void testDecimalDifferentScales() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", new BigDecimal("1.0"), PropertyType.DECIMAL);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // BigDecimal("1.0").equals(BigDecimal("1")) is false, but compareTo returns 0
    // The in-place path uses compareTo semantics
    assertEqualityEquivalence(source, deserialized, "val", new BigDecimal("1"));
    assertEqualityEquivalence(source, deserialized, "val", new BigDecimal("1.00"));
    assertOrderingEquivalence(source, deserialized, "val", new BigDecimal("1"));
    session.rollback();
  }

  /** DECIMAL with long comparison value. */
  @Test
  public void testDecimalWithLongValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", new BigDecimal("42"), PropertyType.DECIMAL);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 100L);
    session.rollback();
  }

  // ===========================================================================
  // BINARY
  // ===========================================================================

  /** BINARY property equivalence. */
  @Test
  public void testBinaryEquivalence() {
    session.begin();
    var bytes = new byte[] {1, 2, 3, 4, 5};
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", bytes, PropertyType.BINARY);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", new byte[] {1, 2, 3, 4, 5});
    assertEqualityEquivalence(source, deserialized, "val", new byte[] {9, 8, 7});
    assertOrderingEquivalence(source, deserialized, "val", new byte[] {1, 2, 3, 4, 5});
    assertOrderingEquivalence(source, deserialized, "val", new byte[] {0});
    assertOrderingEquivalence(source, deserialized, "val", new byte[] {9, 9, 9});
    session.rollback();
  }

  // ===========================================================================
  // LINK (equality only — ordering returns empty)
  // ===========================================================================

  /**
   * LINK property equality via source path (InPlaceComparator). The deserialized path falls back
   * for LINK (compareJavaValuesOrdering returns empty), so we test the source path directly
   * rather than using the cross-path equivalence helper.
   */
  @Test
  public void testLinkEqualityFromSource() {
    session.begin();
    var rid = new RecordId(10, 42);
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", rid, PropertyType.LINK);

    var source = serializeWithSourceOnly(entity);

    // Source path supports LINK equality via InPlaceComparator
    assertEquals(InPlaceResult.TRUE, source.isPropertyEqualTo("val", new RecordId(10, 42)));
    assertEquals(InPlaceResult.FALSE, source.isPropertyEqualTo("val", new RecordId(10, 99)));
    // Different cluster ID, same position
    assertEquals(InPlaceResult.FALSE, source.isPropertyEqualTo("val", new RecordId(11, 42)));

    // Ordering should return empty for LINK type
    assertTrue(
        "LINK ordering should return empty",
        source.comparePropertyTo("val", new RecordId(10, 42)).isEmpty());
    session.rollback();
  }

  /**
   * LINK property via deserialized path — falls back because the properties-map comparison
   * does not support LINK ordering (and equality delegates through ordering).
   */
  @Test
  public void testLinkDeserializedPathFallback() {
    session.begin();
    var rid = new RecordId(10, 42);
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", rid, PropertyType.LINK);

    var deserialized = serializeAndReload(entity);

    // Deserialized path returns FALLBACK for LINK (compareJavaValuesOrdering returns empty)
    assertEquals(InPlaceResult.FALLBACK,
        deserialized.isPropertyEqualTo("val", new RecordId(10, 42)));
    assertTrue(deserialized.comparePropertyTo("val", new RecordId(10, 42)).isEmpty());
    session.rollback();
  }

  // ===========================================================================
  // Null handling
  // ===========================================================================

  /** Null comparison value — both paths should return FALLBACK. */
  @Test
  public void testNullComparisonValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", "Alice");

    var source = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.FALLBACK, source.isPropertyEqualTo("val", null));
    assertTrue(source.comparePropertyTo("val", null).isEmpty());

    // Deserialized path too
    var deserialized = serializeAndReload(entity);
    assertEquals(InPlaceResult.FALLBACK, deserialized.isPropertyEqualTo("val", null));
    assertTrue(deserialized.comparePropertyTo("val", null).isEmpty());
    session.rollback();
  }

  /** Non-existent property — both paths should return FALLBACK. */
  @Test
  public void testNonExistentProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", "Alice");

    var source = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.FALLBACK, source.isPropertyEqualTo("missing", "x"));
    assertTrue(source.comparePropertyTo("missing", "x").isEmpty());
    session.rollback();
  }

  // ===========================================================================
  // Partially deserialized entities
  // ===========================================================================

  /**
   * Entity with some properties deserialized (in the map) and others still in source bytes.
   * After accessing one property via getProperty(), the properties map gets populated, but the
   * remaining properties may still use the source path.
   */
  @Test
  public void testPartiallyDeserializedEntity() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30, PropertyType.INTEGER);
    entity.setProperty("score", 99.5, PropertyType.DOUBLE);

    var loaded = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // Access one property to trigger partial deserialization
    loaded.getProperty("name");

    // Now "name" is in the properties map; "age" and "score" may still use source
    assertEqualityEquivalence(loaded, deserialized, "name", "Alice");
    assertEqualityEquivalence(loaded, deserialized, "age", 30);
    assertEqualityEquivalence(loaded, deserialized, "score", 99.5);
    assertOrderingEquivalence(loaded, deserialized, "name", "Alice");
    assertOrderingEquivalence(loaded, deserialized, "age", 30);
    assertOrderingEquivalence(loaded, deserialized, "score", 99.5);
    session.rollback();
  }

  // ===========================================================================
  // Non-comparable types — should fall back
  // ===========================================================================

  /**
   * EMBEDDED property should return FALLBACK since embedded entities are not binary-comparable.
   */
  @Test
  public void testEmbeddedTypeFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var embedded = (EntityImpl) session.newEmbeddedEntity();
    embedded.setProperty("inner", "value");
    entity.setProperty("val", embedded, PropertyType.EMBEDDED);

    var source = serializeWithSourceOnly(entity);
    // EMBEDDED is not binary-comparable — should fall back
    assertEquals(InPlaceResult.FALLBACK, source.isPropertyEqualTo("val", embedded));
    assertTrue(source.comparePropertyTo("val", embedded).isEmpty());
    session.rollback();
  }

  /** EMBEDDEDLIST should fall back. */
  @Test
  public void testEmbeddedListFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.getOrCreateEmbeddedList("val").addAll(List.of("a", "b", "c"));

    var source = serializeWithSourceOnly(entity);
    var list = List.of("a", "b", "c");
    assertEquals(InPlaceResult.FALLBACK, source.isPropertyEqualTo("val", list));
    assertTrue(source.comparePropertyTo("val", list).isEmpty());
    session.rollback();
  }

  /** EMBEDDEDMAP should fall back. */
  @Test
  public void testEmbeddedMapFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.getOrCreateEmbeddedMap("val").put("key", "value");

    var source = serializeWithSourceOnly(entity);
    var map = Map.of("key", "value");
    assertEquals(InPlaceResult.FALLBACK, source.isPropertyEqualTo("val", map));
    assertTrue(source.comparePropertyTo("val", map).isEmpty());
    session.rollback();
  }

  // ===========================================================================
  // Deserialized path equivalence (properties-map path vs Java comparison)
  // ===========================================================================

  /**
   * Verifies that the deserialized (properties-map) path produces the same results as standard
   * Java comparison for all 13 comparable types.
   */
  @Test
  public void testDeserializedPathAllTypes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("int", 42, PropertyType.INTEGER);
    entity.setProperty("long", 999L, PropertyType.LONG);
    entity.setProperty("short", (short) 10, PropertyType.SHORT);
    entity.setProperty("byte", (byte) 5, PropertyType.BYTE);
    entity.setProperty("float", 3.14f, PropertyType.FLOAT);
    entity.setProperty("double", 2.71828, PropertyType.DOUBLE);
    entity.setProperty("string", "hello");
    entity.setProperty("bool", true, PropertyType.BOOLEAN);
    entity.setProperty("datetime", new Date(1700000000000L), PropertyType.DATETIME);
    entity.setProperty("decimal", new BigDecimal("42.5"), PropertyType.DECIMAL);
    entity.setProperty("binary", new byte[] {1, 2, 3}, PropertyType.BINARY);

    // Use serializeAndReload to get a fully deserialized entity
    var deserialized = serializeAndReload(entity);

    // Equality checks — matching values
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("int", 42));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("long", 999L));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("short", (short) 10));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("byte", (byte) 5));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("float", 3.14f));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("double", 2.71828));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("string", "hello"));
    assertEquals(InPlaceResult.TRUE, deserialized.isPropertyEqualTo("bool", true));
    assertEquals(InPlaceResult.TRUE,
        deserialized.isPropertyEqualTo("datetime", new Date(1700000000000L)));
    assertEquals(InPlaceResult.TRUE,
        deserialized.isPropertyEqualTo("decimal", new BigDecimal("42.5")));
    assertEquals(InPlaceResult.TRUE,
        deserialized.isPropertyEqualTo("binary", new byte[] {1, 2, 3}));

    // Equality checks — non-matching values
    assertEquals(InPlaceResult.FALSE, deserialized.isPropertyEqualTo("int", 99));
    assertEquals(InPlaceResult.FALSE, deserialized.isPropertyEqualTo("string", "world"));
    assertEquals(InPlaceResult.FALSE, deserialized.isPropertyEqualTo("bool", false));

    // Ordering checks
    assertEquals(OptionalInt.of(0), deserialized.comparePropertyTo("int", 42));
    assertTrue(deserialized.comparePropertyTo("int", 10).getAsInt() > 0);
    assertTrue(deserialized.comparePropertyTo("string", "aaa").getAsInt() > 0);
    assertTrue(deserialized.comparePropertyTo("string", "zzz").getAsInt() < 0);

    session.rollback();
  }

  // ===========================================================================
  // Cross-type numeric: INTEGER property vs various numeric types
  // ===========================================================================

  /** INTEGER property compared against Byte, Short, Long values — all should work. */
  @Test
  public void testIntegerCrossTypeMatrix() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42, PropertyType.INTEGER);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // Byte
    assertEqualityEquivalence(source, deserialized, "val", (byte) 42);
    assertOrderingEquivalence(source, deserialized, "val", (byte) 42);

    // Short
    assertEqualityEquivalence(source, deserialized, "val", (short) 42);
    assertOrderingEquivalence(source, deserialized, "val", (short) 42);

    // Long
    assertEqualityEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 42L);

    // Long — non-matching
    assertEqualityEquivalence(source, deserialized, "val", 99L);
    assertOrderingEquivalence(source, deserialized, "val", 99L);

    session.rollback();
  }

  /**
   * FLOAT property compared with Integer value — integer→float precision safe within 2^24.
   */
  @Test
  public void testFloatCrossTypeWithInteger() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42.0f, PropertyType.FLOAT);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 42);
    assertOrderingEquivalence(source, deserialized, "val", 100);
    session.rollback();
  }

  /**
   * DOUBLE property compared with Long value — long→double precision safe within 2^53.
   */
  @Test
  public void testDoubleCrossTypeWithLong() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42.0, PropertyType.DOUBLE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 42L);
    assertOrderingEquivalence(source, deserialized, "val", 100L);
    session.rollback();
  }

  // ===========================================================================
  // Float/Double → integer type fallback
  // ===========================================================================

  /** Float/Double values compared against INTEGER property should fall back on both paths. */
  @Test
  public void testIntegerWithFloatAndDoubleValueFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", 42, PropertyType.INTEGER);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // Float → int: both paths should fall back
    assertEqualityEquivalence(source, deserialized, "val", 42.0f);
    assertEqualityEquivalence(source, deserialized, "val", 42.5f);
    assertOrderingEquivalence(source, deserialized, "val", 42.0f);
    // Double → int: both paths should fall back
    assertEqualityEquivalence(source, deserialized, "val", 42.0);
    assertEqualityEquivalence(source, deserialized, "val", 42.5);
    assertOrderingEquivalence(source, deserialized, "val", 42.0);
    session.rollback();
  }

  // ===========================================================================
  // DECIMAL with Double/Float — double conversion artifacts
  // ===========================================================================

  /** DECIMAL compared with Double value — exercises double-to-BigDecimal conversion. */
  @Test
  public void testDecimalWithDoubleValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", new BigDecimal("0.1"), PropertyType.DECIMAL);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    // 0.1d is not exactly 0.1 — exercises the conversion artifact path
    assertEqualityEquivalence(source, deserialized, "val", 0.1);
    assertOrderingEquivalence(source, deserialized, "val", 0.1);
    assertOrderingEquivalence(source, deserialized, "val", 0.2);

    // DECIMAL vs Float
    assertEqualityEquivalence(source, deserialized, "val", 0.1f);
    assertOrderingEquivalence(source, deserialized, "val", 0.1f);
    session.rollback();
  }

  // ===========================================================================
  // Integer boundary values
  // ===========================================================================

  /** INTEGER property with MIN/MAX boundary values and narrowing Long overflow. */
  @Test
  public void testIntegerBoundaryValues() {
    session.begin();

    var maxEntity = (EntityImpl) session.newEntity();
    maxEntity.setProperty("val", Integer.MAX_VALUE, PropertyType.INTEGER);
    var maxSrc = serializeWithSourceOnly(maxEntity);
    var maxDeserialized = serializeAndReload(maxEntity);
    assertEqualityEquivalence(maxSrc, maxDeserialized, "val", Integer.MAX_VALUE);
    assertEqualityEquivalence(maxSrc, maxDeserialized, "val", (long) Integer.MAX_VALUE);
    assertOrderingEquivalence(maxSrc, maxDeserialized, "val", 0);

    var minEntity = (EntityImpl) session.newEntity();
    minEntity.setProperty("val", Integer.MIN_VALUE, PropertyType.INTEGER);
    var minSrc = serializeWithSourceOnly(minEntity);
    var minDeserialized = serializeAndReload(minEntity);
    assertEqualityEquivalence(minSrc, minDeserialized, "val", Integer.MIN_VALUE);
    assertOrderingEquivalence(minSrc, minDeserialized, "val", 0);

    session.rollback();
  }

  // ===========================================================================
  // BYTE with negative and boundary values
  // ===========================================================================

  /** BYTE property with negative values and MIN/MAX boundaries. */
  @Test
  public void testByteNegativeAndBoundary() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("val", Byte.MIN_VALUE, PropertyType.BYTE);

    var source = serializeWithSourceOnly(entity);
    var deserialized = serializeAndReload(entity);

    assertEqualityEquivalence(source, deserialized, "val", Byte.MIN_VALUE);
    assertOrderingEquivalence(source, deserialized, "val", (byte) 0);
    assertOrderingEquivalence(source, deserialized, "val", Byte.MAX_VALUE);
    session.rollback();
  }
}
