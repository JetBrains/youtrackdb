package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.junit.Test;

/**
 * Standalone tests for {@link LogSequenceNumber}: getters, equals/hashCode contract,
 * natural ordering ({@link Comparable}), DataOutput/DataInput round-trip, and the
 * {@link LogSequenceNumber#NOT_TRACKED} sentinel.
 *
 * <p>LSN is the central addressing primitive of the WAL — every WAL record carries
 * one, recovery uses LSN ordering to replay updates, and the page cache uses the
 * NOT_TRACKED sentinel to short-circuit allocation on the read-only path. Coverage
 * here is a hard prerequisite for every WAL record test in this track.
 */
public class LogSequenceNumberTest {

  /**
   * Constructor + getters: assigning explicit segment / position values exposes them
   * unchanged via {@code getSegment()} / {@code getPosition()}.
   */
  @Test
  public void gettersExposeConstructorArguments() {
    var lsn = new LogSequenceNumber(7L, 42);

    assertEquals(7L, lsn.getSegment());
    assertEquals(42, lsn.getPosition());
  }

  /**
   * The {@link LogSequenceNumber#NOT_TRACKED} sentinel uses {@code (-1, -1)} so that
   * any real LSN compares strictly greater. Pin both fields so a future refactor that
   * silently changes the sentinel value fails fast.
   */
  @Test
  public void notTrackedSentinelHasNegativeOneSegmentAndPosition() {
    assertEquals(-1L, LogSequenceNumber.NOT_TRACKED.getSegment());
    assertEquals(-1, LogSequenceNumber.NOT_TRACKED.getPosition());
  }

  /**
   * Equals/hashCode contract: two LSNs with identical segment+position are equal and
   * share a hash code; differing in either segment or position produces inequality.
   */
  @Test
  public void equalsAndHashCodeReflectSegmentAndPosition() {
    var a = new LogSequenceNumber(3L, 17);
    var b = new LogSequenceNumber(3L, 17);
    var differentSegment = new LogSequenceNumber(4L, 17);
    var differentPosition = new LogSequenceNumber(3L, 18);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, differentSegment);
    assertNotEquals(a, differentPosition);
  }

  /**
   * Equals must reject {@code null} and unrelated types and accept reflexive identity,
   * covering the early-return branches in {@link LogSequenceNumber#equals(Object)}.
   */
  @Test
  public void equalsRejectsNullAndForeignTypeAndAcceptsSelf() {
    var lsn = new LogSequenceNumber(1L, 1);

    assertEquals(lsn, lsn);
    assertNotEquals(lsn, null);
    assertNotEquals(lsn, "not an LSN");
  }

  /**
   * compareTo is segment-major, position-minor: ordering is decided first by segment,
   * then by position when segments tie. Cover both segment-greater and segment-less
   * branches plus the position-greater / position-less / equal branches inside the
   * segment tie.
   */
  @Test
  public void compareToOrdersBySegmentThenPosition() {
    var base = new LogSequenceNumber(5L, 100);

    var laterSegment = new LogSequenceNumber(6L, 0);
    var earlierSegment = new LogSequenceNumber(4L, 999);
    var sameSegmentLaterPosition = new LogSequenceNumber(5L, 101);
    var sameSegmentEarlierPosition = new LogSequenceNumber(5L, 99);
    var equal = new LogSequenceNumber(5L, 100);

    assertTrue(base.compareTo(laterSegment) < 0);
    assertTrue(base.compareTo(earlierSegment) > 0);
    assertTrue(base.compareTo(sameSegmentLaterPosition) < 0);
    assertTrue(base.compareTo(sameSegmentEarlierPosition) > 0);
    assertEquals(0, base.compareTo(equal));
  }

  /**
   * DataOutput/DataInput round-trip: serializing an LSN via {@code toStream} and then
   * reading it back through the {@code DataInput} constructor reproduces the exact
   * segment+position. Falsifiability — both fields are pinned individually so a bug
   * that swaps them, drops a byte, or zeroes a field cannot pass.
   */
  @Test
  public void toStreamAndDataInputConstructorRoundTrip() throws Exception {
    var original = new LogSequenceNumber(123_456_789L, 0xCAFEBABE);

    var sink = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(sink)) {
      original.toStream(out);
    }

    LogSequenceNumber restored;
    try (var in =
        new DataInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
      restored = new LogSequenceNumber(in);
    }

    assertEquals(original, restored);
    assertEquals(123_456_789L, restored.getSegment());
    assertEquals(0xCAFEBABE, restored.getPosition());
  }

  /**
   * The serialized form is exactly 12 bytes (8-byte segment + 4-byte position). Pinning
   * the size ensures format compatibility — any future change that silently grows the
   * record breaks the WAL on-disk layout, and this assertion will catch it.
   */
  @Test
  public void serializedFormIsTwelveBytes() throws Exception {
    var sink = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(sink)) {
      new LogSequenceNumber(0L, 0).toStream(out);
    }
    assertEquals(12, sink.toByteArray().length);
  }

  /**
   * Default {@code toString()} returns the canonical {@code LogSequenceNumber{segment=N,
   * position=M}} form. Pinning the full output (rather than substring-contains) catches
   * any refactor that reorders the fields, drops one, or renames the brace style — the
   * full-string equality is the load-bearing falsifier here.
   */
  @Test
  public void toStringHasCanonicalSegmentPositionFormat() {
    var lsn = new LogSequenceNumber(11L, 22);

    assertEquals("LogSequenceNumber{segment=11, position=22}", lsn.toString());
  }
}
