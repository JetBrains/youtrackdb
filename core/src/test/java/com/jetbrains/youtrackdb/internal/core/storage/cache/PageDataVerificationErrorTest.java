package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link PageDataVerificationError} — equals/hashCode contract and falsifiable
 * pinning of every field.
 *
 * <p>{@code PageDataVerificationError} is the value object WOWCache produces for the page
 * checker's diagnostic report. Tests are deduplicated by an {@code equals}-based set in the
 * checker; a refactor that loosened equals would silently merge distinct errors and hide
 * corrupted pages, so equals on every component is the load-bearing invariant.
 */
public class PageDataVerificationErrorTest {

  /** Reflexivity. */
  @Test
  public void testEqualsReflexive() {
    var err = new PageDataVerificationError(true, true, 42L, "data.tst");
    assertEquals(err, err);
  }

  /**
   * Two errors with identical fields are equal and produce equal hash codes — the
   * precondition for set-based deduplication.
   */
  @Test
  public void testEqualsSymmetricAndHashCodeMatches() {
    var a = new PageDataVerificationError(true, true, 42L, "data.tst");
    var b = new PageDataVerificationError(true, true, 42L, "data.tst");
    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /** Different magic-number flag → not equal. */
  @Test
  public void testNotEqualsDifferentMagicNumberFlag() {
    var a = new PageDataVerificationError(true, true, 42L, "data.tst");
    var b = new PageDataVerificationError(false, true, 42L, "data.tst");
    assertNotEquals(a, b);
  }

  /** Different checksum flag → not equal. */
  @Test
  public void testNotEqualsDifferentChecksumFlag() {
    var a = new PageDataVerificationError(true, true, 42L, "data.tst");
    var b = new PageDataVerificationError(true, false, 42L, "data.tst");
    assertNotEquals(a, b);
  }

  /** Different pageIndex → not equal. */
  @Test
  public void testNotEqualsDifferentPageIndex() {
    var a = new PageDataVerificationError(true, true, 42L, "data.tst");
    var b = new PageDataVerificationError(true, true, 43L, "data.tst");
    assertNotEquals(a, b);
  }

  /** Different fileName → not equal. */
  @Test
  public void testNotEqualsDifferentFileName() {
    var a = new PageDataVerificationError(true, true, 42L, "data.tst");
    var b = new PageDataVerificationError(true, true, 42L, "other.tst");
    assertNotEquals(a, b);
  }

  /** Not equal to null and not equal to a different type — pattern-matched instanceof check. */
  @SuppressWarnings({"ConstantConditions", "EqualsBetweenInconvertibleTypes"})
  @Test
  public void testNotEqualsNullOrDifferentType() {
    var err = new PageDataVerificationError(true, true, 42L, "data.tst");
    assertFalse(err.equals(null));
    assertFalse(err.equals("not an error"));
  }

  /**
   * hashCode formula matches the documented mix:
   * {@code (magic ? 1 : 0) → *31 + (checksum ? 1 : 0) → *31 + (pageIndex high/low XOR) → *31 +
   * fileName.hashCode()}. Pinning the formula catches a refactor that swaps fields silently
   * (which would still satisfy the equals→hashCode contract via the trivial implication, but
   * change collision behaviour in real-world sets).
   */
  @Test
  public void testHashCodeFormulaMatchesDocumentedMix() {
    var fileName = "data.tst";
    long pageIndex = 42L;
    var err = new PageDataVerificationError(true, true, pageIndex, fileName);

    int expected = 1; // magic=true
    expected = 31 * expected + 1; // checksum=true
    expected = 31 * expected + (int) (pageIndex ^ (pageIndex >>> 32));
    expected = 31 * expected + fileName.hashCode();

    assertEquals(expected, err.hashCode());
  }

  /**
   * Falsifiability: pin the boolean flags to confirm they survive into equals (a refactor that
   * dropped them would still let two errors with different flags compare equal). Two errors
   * differ in every field except magic-number flag — the assertNotEquals here only fires if
   * the magic-number flag actually participates in equals.
   */
  @Test
  public void testEachBooleanFlagParticipatesInEquals() {
    var trueMagic = new PageDataVerificationError(true, true, 42L, "data.tst");
    var falseMagic = new PageDataVerificationError(false, true, 42L, "data.tst");
    assertTrue(
        "Magic-number flag must participate in equals — distinct flags must produce !equal",
        !trueMagic.equals(falseMagic));

    var trueChecksum = new PageDataVerificationError(true, true, 42L, "data.tst");
    var falseChecksum = new PageDataVerificationError(true, false, 42L, "data.tst");
    assertTrue(
        "Checksum flag must participate in equals", !trueChecksum.equals(falseChecksum));
  }
}
