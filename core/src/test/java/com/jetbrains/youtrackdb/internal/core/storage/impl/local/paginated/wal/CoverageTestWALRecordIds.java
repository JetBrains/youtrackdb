package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

/**
 * Central registry of test-only WAL record IDs reserved by tests added under the storage
 * cache &amp; WAL coverage suite.
 *
 * <p>Named constants document allocations but do not enforce numeric uniqueness at compile time.
 * Check production and test registrations before allocating an ID. The isolated registry
 * coexistence test covers its explicit inventory, not every possible future registration.
 *
 * <p><b>Allocation policy.</b> Tests use IDs at or above {@code PAGE_OPERATION_ID_BASE}
 * (200). The factory unconditionally rejects IDs outside {@code [0, 512)} and asserts
 * the lower bound of 200 when assertions are enabled.
 * Tests under this coverage suite that need a new test-only record type allocate
 * IDs in <b>[460, 510]</b> (a sub-range of the legal table window, kept clear of
 * existing test ID 500 used by {@code CASDiskWriteAheadLogCloseTest.SmallTestRecord}
 * and ID 511 used by {@code CASDiskWriteAheadLogIT}).
 *
 * <p>Selected production and test registrations:
 * <ul>
 *   <li>ID 200 — {@code TestPageOperation} in {@code WALRecordsFactoryPageOperationTest}.
 *   <li>IDs 201–297 — production operations, including production ID 250.
 *   <li>ID 461 — {@code WOWCacheTestIT.TestRecord} (failsafe).
 *   <li>ID 500 — {@code SmallTestRecord} ({@code CASDiskWriteAheadLogCloseTest}).
 *   <li>ID 511 — {@code CASDiskWriteAheadLogIT}.
 * </ul>
 *
 * <p>Registration semantics: {@code WALRecordsFactory.INSTANCE} is a process-singleton
 * mutable registry; {@code registerNewRecord} does last-write-wins via
 * {@code AtomicReferenceArray.set}. Registering the same ID from multiple test classes
 * with the same class is safe; registering conflicting classes for the same ID produces
 * silent overwrites in test JVMs. Constants alone do not prevent conflicting values.
 *
 * <p>Subsequent additions that need a test-only record type allocate a constant here
 * and check coexistence with production registrations.
 */
public final class CoverageTestWALRecordIds {

  /** Test-only WOW cache record, separate from the production operation at ID 250. */
  public static final int WOW_CACHE_TEST_RECORD_ID = 461;

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
