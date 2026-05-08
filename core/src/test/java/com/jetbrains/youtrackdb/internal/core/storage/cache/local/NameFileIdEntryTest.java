package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the package-private {@link NameFileIdEntry} value object — equals/hashCode contract
 * and getter values. WOWCache writes these entries to its name-id map durable side file; a
 * refactor that loosens equals (e.g., ignoring {@code fileSystemName}) would silently allow
 * duplicate entries with different on-disk names to be treated as identical.
 *
 * <p>{@code NameFileIdEntry} is package-private; this test must live in the same package.
 */
public class NameFileIdEntryTest {

  /**
   * The 2-arg constructor sets {@code fileSystemName} to the same value as {@code name} —
   * pin both getters so a refactor that makes them diverge silently is detected.
   */
  @Test
  public void testTwoArgConstructorMirrorsNameToFileSystemName() {
    var entry = new NameFileIdEntry("alpha.tst", 7);
    assertEquals("alpha.tst", entry.getName());
    assertEquals(7, entry.getFileId());
    assertEquals(
        "Two-arg constructor must mirror name into fileSystemName",
        "alpha.tst", entry.getFileSystemName());
  }

  /**
   * The 3-arg constructor accepts a distinct {@code fileSystemName}, used when an entry's
   * on-disk name differs from its logical name (e.g., suffix collisions).
   */
  @Test
  public void testThreeArgConstructorPreservesAllFields() {
    var entry = new NameFileIdEntry("alpha.tst", 7, "alpha_1.tst");
    assertEquals("alpha.tst", entry.getName());
    assertEquals(7, entry.getFileId());
    assertEquals("alpha_1.tst", entry.getFileSystemName());
  }

  /**
   * Two entries with identical name, fileId and fileSystemName are equal and have equal
   * hash codes — required for stable de-duplication in the side-file iterator.
   */
  @Test
  public void testEqualsAndHashCodeForIdenticalEntries() {
    var a = new NameFileIdEntry("name.tst", 5, "name.tst");
    var b = new NameFileIdEntry("name.tst", 5, "name.tst");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /** Reflexivity. */
  @Test
  public void testEqualsReflexive() {
    var a = new NameFileIdEntry("name.tst", 5, "name.tst");
    assertEquals(a, a);
  }

  /** Different name → not equal. */
  @Test
  public void testNotEqualsDifferentName() {
    var a = new NameFileIdEntry("a.tst", 5, "x.tst");
    var b = new NameFileIdEntry("b.tst", 5, "x.tst");
    assertNotEquals(a, b);
  }

  /** Different fileId → not equal. */
  @Test
  public void testNotEqualsDifferentFileId() {
    var a = new NameFileIdEntry("name.tst", 5);
    var b = new NameFileIdEntry("name.tst", 6);
    assertNotEquals(a, b);
  }

  /**
   * Different fileSystemName → not equal even when name and fileId match. Crucial because
   * two entries can share the logical name but live under different on-disk names after a
   * collision-renaming sweep.
   */
  @Test
  public void testNotEqualsDifferentFileSystemName() {
    var a = new NameFileIdEntry("name.tst", 5, "name.tst");
    var b = new NameFileIdEntry("name.tst", 5, "name_renamed.tst");
    assertNotEquals(a, b);
  }

  /** Not equal to null and not equal to a different type. */
  @SuppressWarnings({"ConstantConditions", "EqualsBetweenInconvertibleTypes"})
  @Test
  public void testNotEqualsNullOrDifferentType() {
    var a = new NameFileIdEntry("name.tst", 5);
    assertFalse(a.equals(null));
    assertFalse(a.equals("not an entry"));
  }

  /**
   * Falsifiability: assert hashCode formula by predicting it from the documented mix of
   * {@code name.hashCode()}, {@code fileId}, and {@code fileSystemName.hashCode()}. A refactor
   * that swaps fields in the formula would change the hash but keep equals working — only
   * this assertion would catch the regression.
   */
  @Test
  public void testHashCodeFormulaMatchesDocumentedMix() {
    var name = "alpha.tst";
    var fsName = "alpha_renamed.tst";
    var entry = new NameFileIdEntry(name, 13, fsName);
    int expected = name.hashCode();
    expected = 31 * expected + 13;
    expected = 31 * expected + fsName.hashCode();
    assertEquals(expected, entry.hashCode());
  }

  /**
   * Sanity check that getters return the constructor arguments in a 3-arg construction,
   * pinned in the same way as the round-trip assertions in WAL-record tests — at least one
   * specific getter value must be asserted to satisfy the falsifiability rule.
   */
  @Test
  public void testGettersReturnConstructorArguments() {
    var entry = new NameFileIdEntry("logical", 99, "physical");
    assertTrue("getName must return the logical name", "logical".equals(entry.getName()));
    assertEquals(99, entry.getFileId());
    assertTrue(
        "getFileSystemName must return the physical name",
        "physical".equals(entry.getFileSystemName()));
  }
}
