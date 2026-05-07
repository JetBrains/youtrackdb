package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link CompositeCollate}: key transformation, name, equality, hashing, toString, and
 * the exception path when a non-list/non-CompositeKey value is supplied.
 */
public class CompositeCollateTest extends DbTestBase {

  // A minimal CompositeIndexDefinition needed as the constructor argument for CompositeCollate.
  private CompositeIndexDefinition ownerDef;
  private CompositeCollate collate;

  @Before
  public void setUp() {
    // No transaction is needed here: every test below operates purely on the CompositeCollate
    // API (transform, equals, addCollate) and SQLEngine.getCollate, which are session-static.
    // Wrapping the test body in a TX would only mask leaks if a future test accidentally
    // started using DB state; if that happens, re-introduce session.begin/rollback at that
    // point.
    ownerDef = new CompositeIndexDefinition("testClass");
    ownerDef.addIndex(
        new PropertyIndexDefinition("testClass", "f1", PropertyTypeInternal.STRING));
    ownerDef.addIndex(
        new PropertyIndexDefinition("testClass", "f2", PropertyTypeInternal.STRING));

    collate = new CompositeCollate(ownerDef);
    // case-insensitive collate at position 0
    collate.addCollate(SQLEngine.getCollate(CaseInsensitiveCollate.NAME));
    // default (identity) collate at position 1
    collate.addCollate(SQLEngine.getCollate(DefaultCollate.NAME));
  }

  // ---- getName ---------------------------------------------------------------

  /**
   * Verifies that the collate name is the fixed string "CompositeCollate".
   */
  @Test
  public void testGetNameIsCompositeCollate() {
    assertEquals("CompositeCollate", collate.getName());
  }

  // ---- addCollate / getCollates ----------------------------------------------

  /**
   * Verifies that addCollate appends entries and getCollates returns them in insertion order.
   */
  @Test
  public void testGetCollatesReturnsAddedCollates() {
    var collates = collate.getCollates();
    assertEquals(2, collates.size());
    assertEquals(CaseInsensitiveCollate.NAME, collates.get(0).getName());
    assertEquals(DefaultCollate.NAME, collates.get(1).getName());
  }

  // ---- transform(CompositeKey) -----------------------------------------------

  /**
   * Verifies that transform(CompositeKey) applies each per-position collate to the
   * corresponding key element, leaving elements beyond the collate list unchanged.
   * The case-insensitive collate at position 0 lowercases the string; the default
   * collate at position 1 is identity.
   */
  @Test
  public void testTransformCompositeKeyAppliesCollates() {
    var input = new CompositeKey();
    input.addKey("HELLO"); // position 0 — CI collate applies → "hello"
    input.addKey("World"); // position 1 — default collate applies → "World"

    var result = collate.transform(input);

    assertTrue(result instanceof CompositeKey);
    var resultKey = (CompositeKey) result;
    assertEquals(2, resultKey.getKeys().size());
    assertEquals("hello", resultKey.getKeys().get(0));
    assertEquals("World", resultKey.getKeys().get(1));
  }

  /**
   * Verifies that keys beyond the collate list are copied verbatim into the result.
   */
  @Test
  public void testTransformCompositeKeyExtraKeysPassedThrough() {
    var input = new CompositeKey();
    input.addKey("HELLO"); // CI collate
    input.addKey("World"); // default collate
    input.addKey("Extra"); // beyond collate list — pass-through

    var result = (CompositeKey) collate.transform(input);
    assertEquals(3, result.getKeys().size());
    assertEquals("hello", result.getKeys().get(0));
    assertEquals("World", result.getKeys().get(1));
    assertEquals("Extra", result.getKeys().get(2));
  }

  // ---- transform(List) -------------------------------------------------------

  /**
   * Verifies that transform(List) is supported as an alternative to CompositeKey input
   * and produces the same per-position collation result.
   */
  @Test
  public void testTransformListAppliesCollates() {
    List<Object> input = Arrays.asList("UPPER", "Mixed");
    var result = collate.transform(input);

    assertTrue(result instanceof CompositeKey);
    var resultKey = (CompositeKey) result;
    assertEquals(2, resultKey.getKeys().size());
    assertEquals("upper", resultKey.getKeys().get(0));
    assertEquals("Mixed", resultKey.getKeys().get(1));
  }

  // ---- transform — invalid type exception ------------------------------------

  /**
   * Verifies that supplying a value that is neither a CompositeKey nor a List throws
   * IndexException, covering the else-branch in transform().
   */
  @Test(expected = IndexException.class)
  public void testTransformUnsupportedTypeThrowsIndexException() {
    // An Integer is neither a CompositeKey nor a List — must trigger IndexException.
    collate.transform(42);
  }

  // ---- equals / hashCode -----------------------------------------------------

  /**
   * Verifies reflexive equality: a CompositeCollate equals itself.
   */
  @Test
  public void testEqualsReflexive() {
    assertEquals(collate, collate);
    assertEquals(collate.hashCode(), collate.hashCode());
  }

  /**
   * Verifies that two CompositeCollate instances with the same collate list are equal
   * and have the same hash code.
   */
  @Test
  public void testEqualsAndHashCodeSameCollates() {
    var other = new CompositeCollate(ownerDef);
    other.addCollate(SQLEngine.getCollate(CaseInsensitiveCollate.NAME));
    other.addCollate(SQLEngine.getCollate(DefaultCollate.NAME));

    assertEquals(collate, other);
    assertEquals(collate.hashCode(), other.hashCode());
  }

  /**
   * Verifies that two CompositeCollate instances with different collate lists are not equal.
   */
  @Test
  public void testNotEqualsDifferentCollates() {
    var other = new CompositeCollate(ownerDef);
    other.addCollate(SQLEngine.getCollate(DefaultCollate.NAME));
    other.addCollate(SQLEngine.getCollate(CaseInsensitiveCollate.NAME));

    assertNotEquals(collate, other);
  }

  /**
   * Verifies that a CompositeCollate does not equal null.
   */
  @Test
  public void testNotEqualsNull() {
    assertNotEquals(null, collate);
  }

  /**
   * Verifies that a CompositeCollate does not equal an unrelated object.
   */
  @Test
  public void testNotEqualsUnrelatedType() {
    assertNotEquals("string", collate);
  }

  // ---- toString --------------------------------------------------------------

  /**
   * Verifies that toString returns a non-null, non-empty string that contains the
   * class name and mentions null-values handling (delegated from ownerDef).
   */
  @Test
  public void testToStringContainsExpectedContent() {
    var str = collate.toString();
    assertNotNull(str);
    assertTrue(str.contains("CompositeCollate"));
    assertTrue(str.contains("null values ignored"));
  }
}
