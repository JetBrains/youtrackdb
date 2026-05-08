package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import org.junit.Test;

/**
 * Tests the small {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord}
 * implementations in {@code wal.common}: {@link MilestoneWALRecord} and
 * {@link StartWALRecord}. These are simple sentinels used by the WAL writer / reader to
 * mark page boundaries and the start-of-log; they have no payload but they do have
 * non-trivial branch coverage in their getters / setters / state-not-set guards.
 */
public class CommonWALRecordsTest {

  /**
   * {@link MilestoneWALRecord} carries an LSN, distance, and disk size. The unset
   * branches in {@code getDistance()} and {@code getDiskSize()} both throw
   * {@link IllegalStateException} — pin both via {@link Test#expected()} below; here
   * verify the happy-path setters / getters round-trip.
   */
  @Test
  public void milestoneWalRecordRoundTripsLsnDistanceAndDiskSize() {
    var rec = new MilestoneWALRecord();
    var lsn = new LogSequenceNumber(5L, 17);

    rec.setLsn(lsn);
    rec.setDistance(42);
    rec.setDiskSize(128);

    assertSame(lsn, rec.getLsn());
    assertEquals(42, rec.getDistance());
    assertEquals(128, rec.getDiskSize());
  }

  /**
   * Pin: {@link MilestoneWALRecord#getDistance()} throws when distance was never set.
   * The default {@code -1} is not a real distance — recovery code would mis-attribute
   * the slot if the guard were silently dropped.
   */
  @Test(expected = IllegalStateException.class)
  public void milestoneWalRecordGetDistanceThrowsWhenUnset() {
    new MilestoneWALRecord().getDistance();
  }

  /**
   * Pin: {@link MilestoneWALRecord#getDiskSize()} throws when disk size was never set.
   */
  @Test(expected = IllegalStateException.class)
  public void milestoneWalRecordGetDiskSizeThrowsWhenUnset() {
    new MilestoneWALRecord().getDiskSize();
  }

  /**
   * {@link MilestoneWALRecord#toString()} returns the canonical
   * {@code MilestoneWALRecord{ operation_id_lsn = LogSequenceNumber{...}}} form. Recovery
   * diagnostics scrape this string — pin the full output (not a substring) so any refactor
   * that adds, removes, or reorders the field surfaces immediately.
   */
  @Test
  public void milestoneWalRecordToStringHasCanonicalForm() {
    var rec = new MilestoneWALRecord();
    rec.setLsn(new LogSequenceNumber(9L, 13));

    assertEquals(
        "MilestoneWALRecord{ operation_id_lsn = LogSequenceNumber{segment=9, position=13}}",
        rec.toString());
  }

  /**
   * {@link StartWALRecord} — the sentinel marking the beginning of the log. Distance
   * and disk size are fixed: distance is always 0, disk size always
   * {@code CASWALPage.RECORDS_OFFSET}. The setters are no-ops on this class. Pin the
   * fixed values and the no-op semantics.
   */
  @Test
  public void startWalRecordHasFixedDistanceAndDiskSize() {
    var rec = new StartWALRecord();

    assertEquals(0, rec.getDistance());
    assertEquals(CASWALPage.RECORDS_OFFSET, rec.getDiskSize());

    // Setters are no-ops — calling them must not change the fixed values.
    rec.setDistance(999);
    rec.setDiskSize(999);
    assertEquals(0, rec.getDistance());
    assertEquals(CASWALPage.RECORDS_OFFSET, rec.getDiskSize());
  }

  /**
   * {@link StartWALRecord#setLsn(LogSequenceNumber)} stores the LSN like a normal
   * record; pin the round-trip so a regression that drops the field surfaces.
   */
  @Test
  public void startWalRecordRoundTripsLsn() {
    var rec = new StartWALRecord();
    var lsn = new LogSequenceNumber(1L, 1);

    rec.setLsn(lsn);
    assertSame(lsn, rec.getLsn());
  }

  /**
   * {@link CASWALPage} is a constants-only helper. Pin the layout offsets so a
   * refactor that silently shifts them surfaces — the on-disk WAL format depends on
   * these values exactly.
   */
  @Test
  public void casWalPageLayoutOffsetsAreStable() {
    assertEquals(0, CASWALPage.MAGIC_NUMBER_OFFSET);
    assertEquals(8, CASWALPage.XX_OFFSET);
    assertEquals(16, CASWALPage.LAST_OPERATION_ID_OFFSET);
    assertEquals(20, CASWALPage.PAGE_SIZE_OFFSET);
    assertEquals(22, CASWALPage.RECORDS_OFFSET);
    assertEquals(4 * 1024, CASWALPage.DEFAULT_PAGE_SIZE);
    assertEquals(CASWALPage.DEFAULT_PAGE_SIZE - CASWALPage.RECORDS_OFFSET,
        CASWALPage.DEFAULT_MAX_RECORD_SIZE);
  }

  /**
   * {@link CASWALPage#calculateSerializedSize(int)} adds the 4-byte length prefix to
   * the record size. Pin the formula — recovery decoders rely on it byte-for-byte.
   */
  @Test
  public void casWalPageCalculateSerializedSizeAddsLengthPrefix() {
    assertEquals(4, CASWALPage.calculateSerializedSize(0));
    assertEquals(104, CASWALPage.calculateSerializedSize(100));
  }
}
