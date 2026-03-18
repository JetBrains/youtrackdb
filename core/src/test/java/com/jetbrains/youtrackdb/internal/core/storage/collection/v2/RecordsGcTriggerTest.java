package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the records GC trigger condition ({@link PaginatedCollectionV2#isGcTriggered})
 * and dead record counter behavior.
 *
 * <p>The trigger formula is:
 * <pre>{@code
 *   deadRecords > minThreshold + (long)(scaleFactor * approximateRecordsCount)
 * }</pre>
 *
 * <p>Tests use an in-memory database because {@code PaginatedCollectionV2} is {@code final}
 * and requires storage infrastructure to instantiate.
 *
 * <p>Pure unit tests for the static {@code evictStaleSnapshotEntries} method (including
 * null/out-of-bounds/negative componentId guards) are in
 * {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.SnapshotIndexCleanupTest},
 * which is in the same package as {@code AbstractStorage} and can access the package-private
 * overloads.
 */
public class RecordsGcTriggerTest {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void setUp() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
  }

  @After
  public void tearDown() {
    youTrackDB.close();
  }

  // ---------------------------------------------------------------------------
  // GC trigger condition: boundary tests
  // ---------------------------------------------------------------------------

  // Verifies that the trigger condition uses strict greater-than: when deadRecords
  // equals the threshold exactly, the trigger does not fire.
  @Test
  public void gcTriggerNotFiredAtExactThreshold() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      // Force cleanup on every tx close to populate dead record counters
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

      session.command("CREATE CLASS TriggerBoundary");

      // Insert records, then update them to create dead versions
      for (int i = 0; i < 20; i++) {
        session.begin();
        session.command("INSERT INTO TriggerBoundary SET idx = " + i);
        session.commit();
      }
      for (int i = 0; i < 5; i++) {
        session.begin();
        session.command("UPDATE TriggerBoundary SET ver = " + (i + 1));
        session.commit();
      }

      // Find a collection with dead records. With cleanup threshold=0 and
      // multiple update rounds, dead records must always be present.
      var pc = findCollectionWithDeadRecords(session, storage, "TriggerBoundary");
      assertThat(pc)
          .as("expected dead records after updates with cleanup threshold=0")
          .isNotNull();

      long deadCount = pc.getDeadRecordCount();

      // When minThreshold == deadCount and scaleFactor == 0, the formula is:
      //   deadRecords > deadCount + 0 → false (strict greater-than)
      assertThat(pc.isGcTriggered((int) deadCount, 0.0f))
          .as("trigger should NOT fire when deadRecords == threshold")
          .isFalse();

      // When minThreshold == deadCount - 1, it should fire
      assertThat(pc.isGcTriggered((int) (deadCount - 1), 0.0f))
          .as("trigger should fire when deadRecords > threshold")
          .isTrue();
    }
  }

  // Verifies that the scaleFactor contributes to the threshold by scaling with
  // the approximate collection size. A large scaleFactor can prevent triggering
  // even with many dead records.
  @Test
  public void gcTriggerScaleFactorRaisesThreshold() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

      session.command("CREATE CLASS TriggerScale");
      for (int i = 0; i < 50; i++) {
        session.begin();
        session.command("INSERT INTO TriggerScale SET idx = " + i);
        session.commit();
      }
      // Update to create dead records
      for (int i = 0; i < 3; i++) {
        session.begin();
        session.command("UPDATE TriggerScale SET ver = " + (i + 1));
        session.commit();
      }

      var pc = findCollectionWithDeadRecords(session, storage, "TriggerScale");
      assertThat(pc)
          .as("expected dead records after updates with cleanup threshold=0")
          .isNotNull();

      // With a very large scaleFactor (e.g., 100.0), the threshold becomes huge:
      //   threshold = 0 + 100.0 * ~50 = ~5000, which is much larger than deadCount
      assertThat(pc.isGcTriggered(0, 100.0f))
          .as("large scaleFactor should prevent triggering")
          .isFalse();

      // With scaleFactor=0 and minThreshold=0, any dead record triggers
      assertThat(pc.isGcTriggered(0, 0.0f))
          .as("zero threshold and scaleFactor should trigger with any dead records")
          .isTrue();
    }
  }

  // Verifies that a fresh collection with zero dead records never triggers GC,
  // regardless of how low the threshold and scaleFactor are set.
  @Test
  public void gcTriggerNeverFiresWithZeroDeadRecords() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      session.command("CREATE CLASS TriggerZero");
      session.begin();
      session.command("INSERT INTO TriggerZero SET name = 'only'");
      session.commit();

      boolean found = false;
      var collectionIds = session.getClass("TriggerZero").getCollectionIds();
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            found = true;
            // Even with the most permissive thresholds (0, 0.0), a collection
            // with 0 dead records should not trigger because 0 > 0 is false.
            assertThat(pc.isGcTriggered(0, 0.0f))
                .as("0 dead records should never trigger GC")
                .isFalse();
          }
        }
      }
      assertThat(found)
          .as("should find at least one PaginatedCollectionV2 for TriggerZero")
          .isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Dead record counter
  // ---------------------------------------------------------------------------

  // Verifies that the dead record counter is positive after updates that create
  // stale snapshot entries (which are then evicted by cleanup).
  @Test
  public void deadRecordCounterIncrementedByEviction() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

      session.command("CREATE CLASS CounterTest");
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO CounterTest SET idx = " + i);
        session.commit();
      }

      // Multiple rounds of updates to create stale versions
      for (int round = 0; round < 5; round++) {
        session.begin();
        session.command("UPDATE CounterTest SET ver = " + (round + 1));
        session.commit();
      }

      // Sum dead records across all collections of this class
      long totalDead = 0;
      var collectionIds = session.getClass("CounterTest").getCollectionIds();
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            totalDead += pc.getDeadRecordCount();
          }
        }
      }

      // After 5 rounds of updating 10 records, at least some stale entries
      // should have been evicted (lwm advances past earlier commit timestamps).
      assertThat(totalDead)
          .as("dead record counter should reflect evicted snapshot entries")
          .isGreaterThan(0);
    }
  }

  // Verifies that the dead record counter starts at 0 for a newly created collection.
  @Test
  public void deadRecordCounterStartsAtZero() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      session.command("CREATE CLASS FreshCollection");
      session.begin();
      session.command("INSERT INTO FreshCollection SET name = 'first'");
      session.commit();

      boolean found = false;
      var collectionIds = session.getClass("FreshCollection").getCollectionIds();
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            found = true;
            // No updates or deletes → no snapshot entries evicted → counter is 0.
            assertThat(pc.getDeadRecordCount())
                .as("fresh collection should have zero dead records")
                .isEqualTo(0);
          }
        }
      }
      assertThat(found)
          .as("should find at least one PaginatedCollectionV2 for FreshCollection")
          .isTrue();
    }
  }

  // ---------------------------------------------------------------------------
  // Configuration parameters
  // ---------------------------------------------------------------------------

  // Verifies that storage-level GC config overrides are read correctly.
  @Test
  public void gcConfigOverrideAtStorageLevel() {
    try (var session = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();

      // Override GC parameters at the storage level
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 500);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.25f);

      assertThat(storage.getContextConfiguration()
          .getValueAsInteger(
              GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD))
          .isEqualTo(500);
      assertThat(storage.getContextConfiguration()
          .getValueAsFloat(
              GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR))
          .isEqualTo(0.25f);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Finds a {@link PaginatedCollectionV2} instance for the given class that has at least
   * one dead record, or {@code null} if none has accumulated dead records yet.
   */
  private static PaginatedCollectionV2 findCollectionWithDeadRecords(
      DatabaseSessionEmbedded session,
      AbstractStorage storage,
      String className) {
    var collectionIds = session.getClass(className).getCollectionIds();
    for (int cid : collectionIds) {
      for (var coll : storage.getCollectionInstances()) {
        if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
          if (pc.getDeadRecordCount() > 0) {
            return pc;
          }
        }
      }
    }
    return null;
  }
}
