package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.Test;

/**
 * Unit tests of the validated-gzip primitive (design CS43), at the stream level and in
 * isolation from its Step-5 importer consumer: single-member decoding with the pinned
 * validation sequence — drain to EOF → trailer verified (CRC32 + ISIZE) → full-consumption
 * checks — against valid, truncated, trailing-garbage, multi-member and corrupt-trailer
 * fixtures.
 */
public class ValidatedGZIPInputStreamTest {

  private static final byte[] PAYLOAD =
      ("the quick brown fox jumps over the lazy dog; "
          + "the quick brown fox jumps over the lazy dog").getBytes(StandardCharsets.UTF_8);

  private static byte[] gzip(byte[] payload) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var gzipOut = new GZIPOutputStream(out)) {
      gzipOut.write(payload);
    }
    return out.toByteArray();
  }

  private static byte[] drain(ValidatedGZIPInputStream in) throws IOException {
    var out = new ByteArrayOutputStream();
    var chunk = new byte[64];
    int n;
    while ((n = in.read(chunk)) != -1) {
      out.write(chunk, 0, n);
    }
    return out.toByteArray();
  }

  /**
   * A valid single-member stream decodes byte-for-byte, passes the full validation sequence,
   * and the compressed-bytes arithmetic spans exactly the physical size.
   */
  @Test
  public void validSingleMemberPassesTheFullSequence() throws Exception {
    var compressed = gzip(PAYLOAD);
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(compressed))) {
      assertArrayEquals(PAYLOAD, drain(in));
      in.verifyFullyConsumed();
      in.verifyPhysicalSize(compressed.length);
      assertEquals(compressed.length, in.getCompressedBytesConsumed());
    }
  }

  /** A truncated DEFLATE stream fails loudly DURING the drain (step 1 of the sequence). */
  @Test
  public void truncatedDeflateStreamFailsDuringDrain() throws Exception {
    var compressed = gzip(PAYLOAD);
    var truncated = Arrays.copyOf(compressed, compressed.length / 2);
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(truncated))) {
      IOException thrown = null;
      try {
        drain(in);
      } catch (IOException e) {
        thrown = e;
      }
      assertNotNull("a truncated deflate stream must fail the drain loudly", thrown);
    }
  }

  /** A stream cut inside the 8-byte trailer fails loudly with the truncation named. */
  @Test
  public void truncatedTrailerFailsDuringDrain() throws Exception {
    var compressed = gzip(PAYLOAD);
    var truncated = Arrays.copyOf(compressed, compressed.length - 3);
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(truncated))) {
      EOFException thrown = null;
      try {
        drain(in);
      } catch (EOFException e) {
        thrown = e;
      }
      assertNotNull("a truncated trailer must fail the drain loudly", thrown);
      assertTrue(thrown.getMessage().contains("Truncated GZIP trailer"));
    }
  }

  /**
   * Trailing garbage INSIDE the decoder's read-ahead window is rejected by step (2)
   * ({@code verifyFullyConsumed}); garbage beyond the window is step (3)'s job, covered below.
   */
  @Test
  public void trailingGarbageIsRejectedByFullConsumptionCheck() throws Exception {
    var compressed = gzip(PAYLOAD);
    var garbage = Arrays.copyOf(compressed, compressed.length + 5);
    garbage[compressed.length] = 42;
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(garbage))) {
      assertArrayEquals("the payload itself still decodes", PAYLOAD, drain(in));
      ZipException thrown = null;
      try {
        in.verifyFullyConsumed();
      } catch (ZipException e) {
        thrown = e;
      }
      assertNotNull("in-window trailing garbage must be rejected", thrown);
      assertTrue(thrown.getMessage().contains("Trailing data after the GZIP trailer"));
    }
  }

  /**
   * A concatenated multi-member stream (valid for the JDK decoder, forbidden for the dump
   * format) is rejected: the drain stops at the FIRST member's verified trailer — never probing
   * for the next member — and the full-consumption check rejects the residue.
   */
  @Test
  public void multiMemberStreamIsRejected() throws Exception {
    var first = gzip(PAYLOAD);
    var second = gzip("second member".getBytes(StandardCharsets.UTF_8));
    var concatenated = new byte[first.length + second.length];
    System.arraycopy(first, 0, concatenated, 0, first.length);
    System.arraycopy(second, 0, concatenated, first.length, second.length);

    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(concatenated))) {
      assertArrayEquals("only the FIRST member decodes — no continuation", PAYLOAD, drain(in));
      try {
        in.verifyFullyConsumed();
        fail("a second member must be rejected as trailing data");
      } catch (ZipException expected) {
        assertTrue(expected.getMessage().contains("Trailing data after the GZIP trailer"));
      }
      // The physical-size arithmetic rejects it too (belt for beyond-window residue).
      try {
        in.verifyPhysicalSize(concatenated.length);
        fail("the physical-size arithmetic must reject the second member");
      } catch (ZipException expected) {
        // either the residue rejection or the size mismatch — both loud
      }
    }
  }

  /**
   * Review TQ20 (the discriminating pin for CS43 step (3) ITSELF): with a one-byte decoder
   * buffer, appended garbage is never read ahead into the decoder window — the drain succeeds
   * and {@code verifyFullyConsumed()} passes — so the PHYSICAL-SIZE ARITHMETIC is the check
   * that must reject the stream. Neutering the {@code consumed != physicalSize} comparison
   * turns exactly this test red (proven during the review-fix iteration by a temporary local
   * neutering).
   */
  @Test
  public void garbageBeyondReadAheadIsRejectedByPhysicalSizeArithmeticAlone() throws Exception {
    var compressed = gzip(PAYLOAD);
    var garbage = Arrays.copyOf(compressed, compressed.length + 64);
    for (var i = compressed.length; i < garbage.length; i++) {
      garbage[i] = (byte) 0x5a;
    }
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(garbage), 1)) {
      assertArrayEquals(PAYLOAD, drain(in));
      // Steps (1)+(2) pass: nothing beyond the trailer was buffered by the tiny read-ahead.
      in.verifyFullyConsumed();
      assertEquals("the member spans only the original bytes",
          compressed.length, in.getCompressedBytesConsumed());
      try {
        in.verifyPhysicalSize(garbage.length);
        fail("the physical-size arithmetic must reject beyond-window trailing garbage");
      } catch (ZipException expected) {
        assertTrue(expected.getMessage().contains("does not span the whole source"));
      }
    }
  }

  /** A flipped CRC32 byte in the trailer fails the drain with the CRC mismatch named. */
  @Test
  public void corruptTrailerCrcFailsDuringDrain() throws Exception {
    var compressed = gzip(PAYLOAD);
    compressed[compressed.length - 8] ^= 0x01; // first CRC byte
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(compressed))) {
      ZipException thrown = null;
      try {
        drain(in);
      } catch (ZipException e) {
        thrown = e;
      }
      assertNotNull("a corrupt trailer CRC must fail the drain loudly", thrown);
      assertTrue(thrown.getMessage().contains("CRC32 mismatch"));
    }
  }

  /** A flipped ISIZE byte in the trailer fails the drain with the size mismatch named. */
  @Test
  public void corruptTrailerSizeFailsDuringDrain() throws Exception {
    var compressed = gzip(PAYLOAD);
    compressed[compressed.length - 4] ^= 0x01; // first ISIZE byte
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(compressed))) {
      ZipException thrown = null;
      try {
        drain(in);
      } catch (ZipException e) {
        thrown = e;
      }
      assertNotNull("a corrupt trailer size must fail the drain loudly", thrown);
      assertTrue(thrown.getMessage().contains("uncompressed-size mismatch"));
    }
  }

  /** Non-gzip input is rejected at construction (the header parse). */
  @Test
  public void nonGzipInputIsRejectedAtConstruction() {
    var plain = "{\"not\":\"gzip\"}".getBytes(StandardCharsets.UTF_8);
    try {
      new ValidatedGZIPInputStream(new ByteArrayInputStream(plain)).close();
      fail("non-gzip input must be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Not in GZIP format"));
    }
  }

  /**
   * The validation sequence's ORDER is enforced: the consumption checks refuse to run before
   * the stream was drained to end of stream (skipping the drain would false-reject valid
   * input, so it is an error, not a pass).
   */
  @Test
  public void verificationBeforeDrainIsRejected() throws Exception {
    var compressed = gzip(PAYLOAD);
    try (var in = new ValidatedGZIPInputStream(new ByteArrayInputStream(compressed))) {
      try {
        in.verifyFullyConsumed();
        fail("verification without the drain must be rejected");
      } catch (ZipException expected) {
        assertTrue(expected.getMessage().contains("drain"));
      }
    }
  }
}
