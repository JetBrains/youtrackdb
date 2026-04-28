package com.jetbrains.youtrackdb.internal.common.serialization;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link UnsafeBinaryConverter} byte-order conversion correctness.
 *
 * <p>The {@code @Test} annotations on the overrides are required for JUnit 4 to discover the
 * tests; without them, the parent's identically-named methods were silently never executed.
 *
 * @since 21.05.13
 */
public class UnsafeConverterTest extends AbstractConverterTest {

  @Before
  public void beforeClass() {
    converter = new UnsafeBinaryConverter();
  }

  @Test
  @Override
  public void testPutIntBigEndian() {
    super.testPutIntBigEndian();
  }

  @Test
  @Override
  public void testPutIntLittleEndian() {
    super.testPutIntLittleEndian();
  }

  @Test
  @Override
  public void testPutLongBigEndian() {
    super.testPutLongBigEndian();
  }

  @Test
  @Override
  public void testPutLongLittleEndian() {
    super.testPutLongLittleEndian();
  }

  @Test
  @Override
  public void testPutShortBigEndian() {
    super.testPutShortBigEndian();
  }

  @Test
  @Override
  public void testPutShortLittleEndian() {
    super.testPutShortLittleEndian();
  }

  @Test
  @Override
  public void testPutCharBigEndian() {
    super.testPutCharBigEndian();
  }

  @Test
  @Override
  public void testPutCharLittleEndian() {
    super.testPutCharLittleEndian();
  }
}
