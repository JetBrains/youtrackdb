package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

/**
 * Central registry of test-only WAL record IDs reserved by tests added under the storage
 * cache &amp; WAL coverage suite.
 *
 * <p>Using a single constants class makes collisions a compile error rather than a
 * surefire-parallel-fork race: every test that registers a record via
 * {@link WALRecordsFactory#registerNewRecord(int, Class)} must reference a constant
 * from this class.
 *
 * <p><b>Allocation policy.</b> {@code WALRecordsFactory} only accepts IDs in
 * {@code [PAGE_OPERATION_ID_BASE, ID_TABLE_SIZE)} = {@code [200, 512)} — a
 * {@code registerNewRecord} call with an ID &ge; 512 throws {@link IllegalArgumentException}.
 * Tests under this coverage suite that need a new test-only record type allocate
 * IDs in <b>[460, 510]</b> (a sub-range of the legal table window, kept clear of
 * existing test ID 500 used by {@code CASDiskWriteAheadLogCloseTest.SmallTestRecord}
 * and the {@code TestPageOperation.TEST_RECORD_ID} used by
 * {@code WALRecordsFactoryPageOperationTest}).
 *
 * <p>Existing surefire registrations that pre-date this suite:
 * <ul>
 *   <li>ID 200 ({@code PAGE_OPERATION_ID_BASE}) and adjacent IDs — production
 *       {@code PageOperation} subclasses registered via {@code PageOperationRegistry}.
 *   <li>ID 250 — {@code WOWCacheTestIT} (failsafe).
 *   <li>ID 500 — {@code SmallTestRecord} ({@code CASDiskWriteAheadLogCloseTest}).
 *   <li>ID {@code TestPageOperation.TEST_RECORD_ID} — {@code WALRecordsFactoryPageOperationTest}.
 * </ul>
 *
 * <p>Registration semantics: {@code WALRecordsFactory.INSTANCE} is a process-singleton
 * mutable registry; {@code registerNewRecord} does last-write-wins via
 * {@code AtomicReferenceArray.set}. Registering the same ID from multiple test classes
 * with the same class is safe; registering conflicting classes for the same ID produces
 * silent overwrites in surefire forks. The compile-time guarantee here prevents the
 * conflicting-class case.
 *
 * <p>Subsequent additions that need a test-only record type allocate a constant here so
 * the compile-time check covers them.
 */
public final class CoverageTestWALRecordIds {

  /**
   * Test-only record ID used by {@code CASDiskWriteAheadLogLifecycleTest}'s
   * {@code LifecycleTestRecord}. Inside the {@code [460, 510]} reserved window; kept clear
   * of ID 500 ({@code SmallTestRecord} in {@code CASDiskWriteAheadLogCloseTest}) and ID 511
   * ({@code CASDiskWriteAheadLogIT}).
   */
  public static final int CAS_LIFECYCLE_TEST_RECORD_ID = 460;

  private CoverageTestWALRecordIds() {
    // constants only
  }
}
