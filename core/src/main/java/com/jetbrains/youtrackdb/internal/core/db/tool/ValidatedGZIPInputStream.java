package com.jetbrains.youtrackdb.internal.core.db.tool;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * A single-member, fully-validated GZIP decoder (the CS43 primitive of Track 8's migration
 * hardening; consumed by the v15-strict import path). Unlike {@link java.util.zip.GZIPInputStream}
 * it NEVER probes for a concatenated next member — the probe is the forbidden "exhaustion probe"
 * of the design: it consumes trailing residue into a dead decoder buffer, silently accepting a
 * corrupt tail. Disabled continuation is what makes draining this stream safe.
 *
 * <p>The pinned validation sequence (design CS43), in order:
 *
 * <ol>
 *   <li>drain the DECOMPRESSED stream to end of stream — reads return {@code -1} once the single
 *       member's trailer has been read and verified (CRC32 + ISIZE), without probing for a next
 *       member; a truncated deflate stream or a corrupt trailer fails loudly during the drain;
 *   <li>{@link #verifyFullyConsumed()} — asserts the drain reached the verified trailer, the
 *       inflater consumed the whole deflate stream, and no bytes beyond the trailer were read
 *       ahead into the decoder buffer (in-window trailing garbage);
 *   <li>{@link #verifyPhysicalSize(long)} — for seekable sources only: asserts
 *       {@code headerLength + Inflater.getBytesRead() + 8 == physicalSize}, catching trailing
 *       garbage beyond the decoder's read-ahead window. Skipping step (1) would leave
 *       {@code getBytesRead()} legitimately short of the deflate stream and false-reject valid
 *       input, which is why the drain comes first.
 * </ol>
 *
 * <p>Implementation note: this class extends {@link InflaterInputStream} (the superclass of
 * {@code GZIPInputStream}) and parses the GZIP framing itself, because {@code GZIPInputStream}'s
 * member-continuation logic is private and cannot be disabled by a subclass.
 */
public final class ValidatedGZIPInputStream extends InflaterInputStream {

  /** RFC 1952 magic (little-endian). */
  private static final int GZIP_MAGIC = 0x8b1f;

  private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;
  private static final int TRAILER_LENGTH = 8;

  private static final int FHCRC = 2;
  private static final int FEXTRA = 4;
  private static final int FNAME = 8;
  private static final int FCOMMENT = 16;

  /** CRC32 of the decompressed payload, verified against the trailer. */
  private final CRC32 crc = new CRC32();

  private final long headerLength;

  /** The single member's trailer has been read and verified; the stream is at end of stream. */
  private boolean trailerVerified;

  /**
   * Raw bytes the inflater had read ahead BEYOND the 8-byte trailer when the member ended —
   * in-window trailing garbage, rejected by {@link #verifyFullyConsumed()}.
   */
  private int readAheadResidue;

  public ValidatedGZIPInputStream(InputStream in) throws IOException {
    this(in, DEFAULT_BUFFER_SIZE);
  }

  public ValidatedGZIPInputStream(InputStream in, int bufferSize) throws IOException {
    super(in, new Inflater(true), bufferSize);
    try {
      headerLength = readHeader();
    } catch (IOException | RuntimeException e) {
      // The self-allocated inflater must not outlive a failed construction (the close() that
      // would end it is unreachable) — rejection-heavy validation paths would otherwise leak
      // native memory until GC.
      inf.end();
      throw e;
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (trailerVerified) {
      return -1;
    }
    var n = super.read(b, off, len);
    if (n == -1) {
      // The deflate stream ended: read and verify the single member's trailer, then stay at
      // end of stream. NO next-member probe (single-member by construction).
      readTrailer();
      return -1;
    }
    crc.update(b, off, n);
    return n;
  }

  @Override
  public void close() throws IOException {
    try {
      super.close();
    } finally {
      // The inflater was allocated by this class; InflaterInputStream only ends inflaters it
      // allocated itself, so end it here or its native memory outlives the stream.
      inf.end();
    }
  }

  /**
   * CS43 steps (1)+(2): must be called after the decompressed stream was drained to end of
   * stream. Verifies the single member ended with a valid trailer, the inflater consumed the
   * whole deflate stream, and no trailing bytes beyond the trailer were read ahead into the
   * decoder buffer.
   *
   * @throws IOException when the stream was not drained, the deflate stream did not finish, or
   *                     trailing data follows the trailer inside the decoder's read-ahead window
   */
  public void verifyFullyConsumed() throws IOException {
    if (!trailerVerified) {
      throw new ZipException(
          "GZIP stream validation requires draining the decompressed stream to end of stream"
              + " first");
    }
    if (!inf.finished()) {
      // Unreachable when trailerVerified (the trailer is only read after the inflater
      // finishes); kept as a belt for the pinned sequence.
      throw new ZipException("GZIP deflate stream was not fully consumed");
    }
    if (readAheadResidue > 0) {
      throw new ZipException(
          "Trailing data after the GZIP trailer: " + readAheadResidue
              + " byte(s) read ahead past the single member");
    }
  }

  /**
   * CS43 step (3), for seekable sources: verifies the single member spans the WHOLE physical
   * source — {@code headerLength + Inflater.getBytesRead() + 8 == physicalSize}. Catches
   * trailing garbage beyond the decoder's read-ahead window (which steps (1)+(2) cannot see on
   * a pure stream).
   */
  public void verifyPhysicalSize(long physicalSize) throws IOException {
    verifyFullyConsumed();
    final var consumed = getCompressedBytesConsumed();
    if (consumed != physicalSize) {
      throw new ZipException(
          "GZIP stream does not span the whole source: " + consumed + " byte(s) consumed of "
              + physicalSize);
    }
  }

  /**
   * The physical bytes the single member occupies: header + deflate stream + trailer. Valid
   * only after the drain reached the verified trailer.
   */
  public long getCompressedBytesConsumed() throws IOException {
    if (!trailerVerified) {
      throw new ZipException(
          "The compressed size is known only after the stream was drained to end of stream");
    }
    return headerLength + inf.getBytesRead() + TRAILER_LENGTH;
  }

  /** The parsed GZIP header's length in bytes. */
  public long getHeaderLength() {
    return headerLength;
  }

  /**
   * Reads and validates the RFC 1952 header directly from the source; returns its length. The
   * accounting is {@code long}: an adversarial multi-GiB FNAME/FCOMMENT must not wrap the
   * length and corrupt the physical-size arithmetic.
   */
  private long readHeader() throws IOException {
    var headerCrc = new CRC32();
    var count = 0L;

    if (readHeaderUShort(headerCrc) != GZIP_MAGIC) {
      throw new ZipException("Not in GZIP format");
    }
    count += 2;
    if (readHeaderUByte(headerCrc) != 8) {
      throw new ZipException("Unsupported compression method");
    }
    count += 1;
    final var flags = readHeaderUByte(headerCrc);
    count += 1;
    // MTIME (4) + XFL (1) + OS (1)
    for (var i = 0; i < 6; i++) {
      readHeaderUByte(headerCrc);
    }
    count += 6;
    if ((flags & FEXTRA) != 0) {
      final var extraLength = readHeaderUShort(headerCrc);
      count += 2;
      for (var i = 0; i < extraLength; i++) {
        readHeaderUByte(headerCrc);
      }
      count += extraLength;
    }
    if ((flags & FNAME) != 0) {
      count += readHeaderZeroTerminated(headerCrc);
    }
    if ((flags & FCOMMENT) != 0) {
      count += readHeaderZeroTerminated(headerCrc);
    }
    // (the FEXTRA/FNAME/FCOMMENT contributions above are longs — no int wrap on adversarial
    // oversized header fields)
    if ((flags & FHCRC) != 0) {
      final var expected = (int) (headerCrc.getValue() & 0xffff);
      final var declared = readHeaderUShort(null);
      count += 2;
      if (declared != expected) {
        throw new ZipException("Corrupt GZIP header (header CRC mismatch)");
      }
    }
    return count;
  }

  private int readHeaderUByte(CRC32 headerCrc) throws IOException {
    final var b = in.read();
    if (b == -1) {
      throw new EOFException("Truncated GZIP header");
    }
    if (headerCrc != null) {
      headerCrc.update(b);
    }
    return b;
  }

  private int readHeaderUShort(CRC32 headerCrc) throws IOException {
    final var low = readHeaderUByte(headerCrc);
    return low | (readHeaderUByte(headerCrc) << 8);
  }

  private long readHeaderZeroTerminated(CRC32 headerCrc) throws IOException {
    var length = 0L;
    int b;
    do {
      b = readHeaderUByte(headerCrc);
      length++;
    } while (b != 0);
    return length;
  }

  /**
   * Reads the 8-byte trailer — from the inflater's read-ahead first, then the source — and
   * verifies CRC32 and ISIZE. Any raw bytes read ahead BEYOND the trailer are recorded as
   * {@link #readAheadResidue} for {@link #verifyFullyConsumed()}.
   */
  private void readTrailer() throws IOException {
    final var remaining = inf.getRemaining();
    final var trailer = new byte[TRAILER_LENGTH];
    final var fromReadAhead = Math.min(remaining, TRAILER_LENGTH);
    if (fromReadAhead > 0) {
      System.arraycopy(buf, len - remaining, trailer, 0, fromReadAhead);
    }
    var read = fromReadAhead;
    while (read < TRAILER_LENGTH) {
      final var n = in.read(trailer, read, TRAILER_LENGTH - read);
      if (n < 0) {
        throw new EOFException("Truncated GZIP trailer");
      }
      read += n;
    }
    readAheadResidue = remaining - fromReadAhead;

    final var declaredCrc = readUInt(trailer, 0);
    if (declaredCrc != crc.getValue()) {
      throw new ZipException("Corrupt GZIP trailer (CRC32 mismatch)");
    }
    final var declaredSize = readUInt(trailer, 4);
    if (declaredSize != (inf.getBytesWritten() & 0xffffffffL)) {
      throw new ZipException("Corrupt GZIP trailer (uncompressed-size mismatch)");
    }
    trailerVerified = true;
  }

  private static long readUInt(byte[] buffer, int offset) {
    return (buffer[offset] & 0xffL)
        | ((buffer[offset + 1] & 0xffL) << 8)
        | ((buffer[offset + 2] & 0xffL) << 16)
        | ((buffer[offset + 3] & 0xffL) << 24);
  }
}
