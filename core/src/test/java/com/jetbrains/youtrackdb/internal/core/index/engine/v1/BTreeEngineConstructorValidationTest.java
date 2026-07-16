package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import org.junit.Test;

/**
 * Tests for constructor validation and basic SPI accessors on the B-tree index engines.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link BTreeSingleValueIndexEngine} constructor: rejects version != 3/4 with
 *       {@link IllegalStateException}.</li>
 *   <li>{@link BTreeMultiValueIndexEngine} constructor: rejects versions 1/2/3 with
 *       {@link IllegalArgumentException}, rejects any other unsupported version with
 *       {@link IllegalStateException}.</li>
 *   <li>{@link SingleValueIndexEngine#isMultiValue()} default: returns {@code false}.</li>
 *   <li>{@link MultiValueIndexEngine#isMultiValue()} default: returns {@code true}.</li>
 *   <li>{@link V1IndexEngine#getEngineAPIVersion()} default: returns {@code V1IndexEngine.API_VERSION}.</li>
 *   <li>{@link BTreeSingleValueIndexEngine#getId()} / {@link BTreeSingleValueIndexEngine#getName()}</li>
 * </ul>
 */
public class BTreeEngineConstructorValidationTest {

  // ═══════════════════════════════════════════════════════════════════════
  // BTreeSingleValueIndexEngine — constructor version guard
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Version 3 is accepted; the engine is constructed without error.
   */
  @Test
  public void singleValue_version3_constructsSuccessfully() {
    var engine = new BTreeSingleValueIndexEngine(
        7, 7, "sv-v3", BTreeEngineTestFixtures.createMockStorage(), 3);
    // getId and getName are covered by this construction path
    assertEquals("Engine id must match constructor arg", 7, engine.getId());
    assertEquals("Engine name must match constructor arg", "sv-v3", engine.getName());
  }

  /**
   * Version 4 is accepted; the engine is constructed without error.
   */
  @Test
  public void singleValue_version4_constructsSuccessfully() {
    var engine = new BTreeSingleValueIndexEngine(
        0, 0, "sv-v4", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertEquals(0, engine.getId());
    assertEquals("sv-v4", engine.getName());
  }

  /**
   * Any version other than 3 or 4 must throw {@link IllegalStateException} with
   * a message that identifies the invalid version number.
   */
  @Test(expected = IllegalStateException.class)
  public void singleValue_version2_throwsIllegalStateException() {
    new BTreeSingleValueIndexEngine(
        0, 0, "sv", BTreeEngineTestFixtures.createMockStorage(), 2);
  }

  /**
   * Version 1 (legacy) is also rejected with {@link IllegalStateException}.
   */
  @Test(expected = IllegalStateException.class)
  public void singleValue_version1_throwsIllegalStateException() {
    new BTreeSingleValueIndexEngine(
        0, 0, "sv", BTreeEngineTestFixtures.createMockStorage(), 1);
  }

  /**
   * Negative version is rejected with {@link IllegalStateException}.
   */
  @Test(expected = IllegalStateException.class)
  public void singleValue_negativeVersion_throwsIllegalStateException() {
    new BTreeSingleValueIndexEngine(
        0, 0, "sv", BTreeEngineTestFixtures.createMockStorage(), -1);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // BTreeMultiValueIndexEngine — constructor version guard
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Version 4 is the only accepted version for multi-value; constructs without error.
   */
  @Test
  public void multiValue_version4_constructsSuccessfully() {
    var engine = new BTreeMultiValueIndexEngine(
        5, 5, "mv-v4", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertEquals("Engine id must match constructor arg", 5, engine.getId());
    assertEquals("Engine name must match constructor arg", "mv-v4", engine.getName());
  }

  /**
   * Version 1 is rejected with {@link IllegalArgumentException} ("Unsupported version of
   * index: 1") — legacy format that was removed.
   */
  @Test(expected = IllegalArgumentException.class)
  public void multiValue_version1_throwsIllegalArgumentException() {
    new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 1);
  }

  /**
   * Version 2 is rejected with {@link IllegalArgumentException}.
   */
  @Test(expected = IllegalArgumentException.class)
  public void multiValue_version2_throwsIllegalArgumentException() {
    new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 2);
  }

  /**
   * Version 3 is rejected with {@link IllegalArgumentException}.
   */
  @Test(expected = IllegalArgumentException.class)
  public void multiValue_version3_throwsIllegalArgumentException() {
    new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 3);
  }

  /**
   * A completely unknown version (e.g. 99) falls through to the {@code else} branch
   * and throws {@link IllegalStateException}.
   */
  @Test(expected = IllegalStateException.class)
  public void multiValue_version99_throwsIllegalStateException() {
    new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 99);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // isMultiValue() default interface implementations
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * {@link BTreeSingleValueIndexEngine} inherits {@code isMultiValue()} from
   * {@link SingleValueIndexEngine}, which returns {@code false}.
   */
  @Test
  public void singleValueEngine_isMultiValue_returnsFalse() {
    var engine = new BTreeSingleValueIndexEngine(
        0, 0, "sv", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertFalse("Single-value engine must report isMultiValue() = false",
        engine.isMultiValue());
  }

  /**
   * {@link BTreeMultiValueIndexEngine} inherits {@code isMultiValue()} from
   * {@link MultiValueIndexEngine}, which returns {@code true}.
   */
  @Test
  public void multiValueEngine_isMultiValue_returnsTrue() {
    var engine = new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertTrue("Multi-value engine must report isMultiValue() = true",
        engine.isMultiValue());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // getEngineAPIVersion() default
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Both engine types inherit {@code getEngineAPIVersion()} from {@link V1IndexEngine},
   * which returns {@link V1IndexEngine#API_VERSION} (currently 1).
   */
  @Test
  public void singleValueEngine_getEngineAPIVersion_returnsV1ApiVersion() {
    var engine = new BTreeSingleValueIndexEngine(
        0, 0, "sv", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertEquals("getEngineAPIVersion must return V1IndexEngine.API_VERSION",
        V1IndexEngine.API_VERSION, engine.getEngineAPIVersion());
  }

  @Test
  public void multiValueEngine_getEngineAPIVersion_returnsV1ApiVersion() {
    var engine = new BTreeMultiValueIndexEngine(
        0, 0, "mv", BTreeEngineTestFixtures.createMockStorage(), 4);
    assertEquals("getEngineAPIVersion must return V1IndexEngine.API_VERSION",
        V1IndexEngine.API_VERSION, engine.getEngineAPIVersion());
  }

  /**
   * The {@link CellBTreeSingleValue} identity-seam defaults ({@code setEngineId},
   * {@code setNullTree}, {@code setDisplayName}) are no-ops by contract: implementations that do
   * not participate in GC or user-facing diagnostics may ignore them, and the owning engine's
   * construction-time calls must be safe against such implementations. Pinned by invoking the
   * real default bodies on an implementation that overrides none of them.
   */
  @Test
  public void cellBTreeIdentitySeamDefaultsAreNoOps() {
    var tree = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
    // Must not throw — the defaults are deliberate no-ops.
    tree.setEngineId(7);
    tree.setNullTree(true);
    tree.setDisplayName("logical-name");
  }
}
