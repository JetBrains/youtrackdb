package com.jetbrains.youtrackdb.internal.common.serialization;

import org.junit.Before;
import org.junit.Test;

/** Verifies {@link SafeBinaryConverter} byte-order conversion correctness. */
public class SafeConverterTest extends AbstractConverterTest {

  @Before
  public void setUp() {
    converter = new SafeBinaryConverter();
  }

  @Test
  public void putIntBigEndianRoundTrips() {
    assertPutIntBigEndianRoundTrips();
  }

  @Test
  public void putIntLittleEndianRoundTrips() {
    assertPutIntLittleEndianRoundTrips();
  }

  @Test
  public void putLongBigEndianRoundTrips() {
    assertPutLongBigEndianRoundTrips();
  }

  @Test
  public void putLongLittleEndianRoundTrips() {
    assertPutLongLittleEndianRoundTrips();
  }

  @Test
  public void putShortBigEndianRoundTrips() {
    assertPutShortBigEndianRoundTrips();
  }

  @Test
  public void putShortLittleEndianRoundTrips() {
    assertPutShortLittleEndianRoundTrips();
  }

  @Test
  public void putCharBigEndianRoundTrips() {
    assertPutCharBigEndianRoundTrips();
  }

  @Test
  public void putCharLittleEndianRoundTrips() {
    assertPutCharLittleEndianRoundTrips();
  }
}
