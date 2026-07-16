package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import java.io.File;
import java.util.ArrayList;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the D16 wave-A substrate: the persisted, never-reused index-engine file base id
 * ({@code IndexEngineData.fileBaseId}), its high-water-mark allocator with the persisted floor,
 * the open-time seeding inputs, and the storage-format version gate (v24 reject-and-redirect).
 *
 * <p>Uses DISK databases with a full {@code YourTracks}-instance close between phases, because
 * the seeding and the format gate both run only on a real storage open
 * ({@code CollectionBasedStorageConfiguration.load} / {@code AbstractStorage.openIndexes}) —
 * closing a session alone leaves the storage open and would exercise nothing.
 */
public class IndexEngineFileBaseIdTest {

  private static final String ADMIN = "admin";
  private static final String PWD = "adminpwd";

  private String testDir;
  private YouTrackDBImpl ytdb;

  @Before
  public void setUp() {
    testDir = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
  }

  @After
  public void tearDown() throws Exception {
    if (ytdb != null) {
      ytdb.close();
    }
    FileUtils.deleteDirectory(new File(testDir));
  }

  /**
   * Every engine create allocates a distinct, positive file base id; the persisted floor and the
   * in-process high-water mark both track the maximum allocated value. Covers both the genesis
   * (security-bootstrap) engines and user-created indexes in one storage.
   */
  @Test
  public void allocationIsMonotonicPersistedAndFloorTracked() throws Exception {
    final var dbName = "fbiAllocation";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      createIndexedClass(session, "FbiAllocA");
      createIndexedClass(session, "FbiAllocB");

      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;

      final var fileBaseIds = new ArrayList<Integer>();
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        for (final var engineName : config.indexEngines(op)) {
          final var engineData = config.getIndexEngine(engineName, -1, op);
          fileBaseIds.add(engineData.getFileBaseId());
        }
      });

      assertTrue("the storage must own engines (genesis + the two test indexes)",
          fileBaseIds.size() >= 2);
      var max = 0;
      for (final var id : fileBaseIds) {
        assertTrue("every allocated file base id must be positive, got " + id, id > 0);
        max = Math.max(max, id);
      }
      assertEquals("no two engines may share a file base id",
          fileBaseIds.size(), fileBaseIds.stream().distinct().count());

      assertEquals("the persisted floor must track the maximum allocated file base id",
          max, floorOf(storage));
      assertEquals("the in-process high-water mark must equal the persisted floor at rest",
          max, storage.indexEngineFileBaseIdHwmForTesting());
    }
  }

  /**
   * A real close-and-reopen seeds the high-water mark from the persisted floor and the persisted
   * engine entries, so the first allocation after the reopen continues the sequence instead of
   * reusing an id.
   */
  @Test
  public void reopenSeedsHwmFromFloorAndEngineEntries() throws Exception {
    final var dbName = "fbiReopen";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    final long floorBeforeClose;
    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      createIndexedClass(session, "FbiReopenA");
      floorBeforeClose = floorOf((AbstractStorage) session.getStorage());
      assertTrue("at least the genesis engines must have allocated", floorBeforeClose > 0);
    }

    // Full instance close: the storage really closes, so the next open re-runs load() (the
    // format gate) and openIndexes (the seeding).
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      assertEquals("the reopened storage must seed the high-water mark from the persisted floor",
          floorBeforeClose, storage.indexEngineFileBaseIdHwmForTesting());

      createIndexedClass(session, "FbiReopenB");
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;
      final var newEngineData =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiReopenB.val", -1, op));
      assertEquals("the first post-reopen allocation must continue the sequence",
          floorBeforeClose + 1, newEngineData.getFileBaseId());
    }
  }

  /**
   * The open-time seeding sweeps the write cache for {@code ie_<n>} file stems, so an orphaned
   * engine file (the in-memory profile's rolled-back eager install, simulated here by planting a
   * file directly in the write cache) pushes the high-water mark past its id and the next
   * allocation can never collide with the orphan.
   */
  @Test
  public void reopenSeedsHwmFromOrphanedEngineFileSweep() throws Exception {
    final var dbName = "fbiSweep";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      // Plant an orphaned engine-shaped file (no component references it), plus a decoy whose
      // stem is not numeric and must be ignored by the sweep's parse.
      storage.getWriteCache().addFile(
          AbstractStorage.INDEX_ENGINE_FILE_STEM_PREFIX + "9999.cbt");
      storage.getWriteCache().addFile(
          AbstractStorage.INDEX_ENGINE_FILE_STEM_PREFIX + "decoy.cbt");
    }

    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      assertEquals("the sweep must push the high-water mark past the orphaned ie_ stem",
          9999, storage.indexEngineFileBaseIdHwmForTesting());

      createIndexedClass(session, "FbiSweepA");
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;
      final var newEngineData =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiSweepA.val", -1, op));
      assertEquals("the next allocation must skip past the orphan's id",
          10000, newEngineData.getFileBaseId());
    }
  }

  /**
   * A rolled-back allocation reverts the persisted floor (it rides the allocating atomic
   * operation) but burns the in-process high-water-mark value, so the next allocation is still
   * unique — the AD-1 invariant that keeps the in-memory profile's surviving orphan files from
   * ever colliding with a re-issued id.
   */
  @Test
  public void rolledBackAllocationBurnsValueAndRevertsFloor() throws Exception {
    final var dbName = "fbiRollback";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var floorBefore = floorOf(storage);
      final var hwmBefore = storage.indexEngineFileBaseIdHwmForTesting();
      assertEquals("precondition: floor and mark agree at rest", floorBefore, hwmBefore);

      // The allocator asserts the exclusive-lock window (AD-9), so the test takes it the same
      // way the engine-create paths do.
      storage.stateLock.writeLock().lock();
      try {
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            final var allocated = storage.allocateIndexEngineFileBaseId(op);
            assertEquals("the allocation must advance the mark by one",
                hwmBefore + 1, allocated);
            // A HighLevelException subtype: it rolls the operation back without flipping the
            // storage into the restart-requiring error state, so the assertions below can keep
            // using the same storage instance.
            throw new ConfigurationException("forced rollback");
          });
          fail("the forced failure must propagate out of the atomic operation");
        } catch (final Exception expected) {
          // The atomic operation rolled back; the thrown exception is the forced one (possibly
          // wrapped by the operation machinery).
        }

        assertEquals("the rolled-back floor write must revert with the atomic operation",
            floorBefore, floorOf(storage));
        assertEquals("the high-water mark must burn the rolled-back value, never revert",
            hwmBefore + 1, storage.indexEngineFileBaseIdHwmForTesting());

        // The next (committed) allocation continues past the burned value, so it can never
        // collide with files a rolled-back create might have left behind.
        final int reallocated =
            storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
                storage::allocateIndexEngineFileBaseId);
        assertEquals("the next allocation must skip the burned value",
            hwmBefore + 2, reallocated);
        assertEquals("the committed allocation must persist the new floor",
            hwmBefore + 2, floorOf(storage));
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  /**
   * The engine-entry deserializer hard-fails on an entry stored under a pre-2 property version
   * tag: such an entry carries no file base id and there is no computable default for one, so a
   * loud rejection (with the export/import redirect) must beat silently mis-keying engine files.
   * Also pins the delete+add-only discipline: the stale tag can only exist because a version bump
   * tried to ride an in-place update, which storeProperty's update branch asserts against.
   */
  @Test
  public void staleEngineEntryVersionTagHardFailsOnRead() throws Exception {
    final var dbName = "fbiStaleTag";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;

      final var staleData = new IndexEngineData(
          7, 7, "staleprobe", "CELL_BTREE", "UNIQUE", true, 4, 1, false,
          (byte) 0, (byte) 0, false,
          new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
          true, 1, null, null, null);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config.storeIndexEngineEntryForTesting(op, "staleprobe", staleData, 1));

      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> config.getIndexEngine("staleprobe", -1, op));
        fail("reading an engine entry with a pre-2 version tag must hard-fail");
      } catch (final Exception e) {
        assertMessageChainContains(e, "predates the engine-file-id format");
      }

      // Remove the poisoned entry so the storage close/teardown does not trip over it.
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config.deleteIndexEngine(op, "staleprobe"));
    }
  }

  /**
   * The storage-format gate rejects a pre-24 database at open — before any engine or collection
   * component touches its files — with the export/import redirect message, mirroring the schema
   * record's reject-and-redirect policy.
   */
  @Test
  public void preCurrentFormatIsRejectedAtOpenWithExportRedirect() throws Exception {
    final var dbName = "fbiGateOld";
    createDbWithTamperedVersion(dbName, 23);

    try {
      ytdb.open(dbName, ADMIN, PWD);
      fail("opening a version-23 database must be rejected by the storage-format gate");
    } catch (final Exception e) {
      assertMessageChainContains(e, "predates the current format");
      assertMessageChainContains(e, "please export your old database");
    }
  }

  /**
   * The gate also enforces a forward ceiling: a database written by a newer format is rejected
   * with a distinct message directing the user to a matching YouTrackDB version, instead of
   * misparsing entries whose layout these binaries cannot know.
   */
  @Test
  public void newerFormatIsRejectedAtOpenWithCeilingMessage() throws Exception {
    final var dbName = "fbiGateNew";
    createDbWithTamperedVersion(dbName, 25);

    try {
      ytdb.open(dbName, ADMIN, PWD);
      fail("opening a version-25 database must be rejected by the storage-format gate");
    } catch (final Exception e) {
      assertMessageChainContains(e, "is newer than the format this version of YouTrackDB"
          + " supports");
    }
  }

  /**
   * Creates a disk database, rewrites its persisted storage-format version to
   * {@code tamperedVersion}, and fully closes the {@code YourTracks} instance so the next open
   * re-runs the gate.
   */
  private void createDbWithTamperedVersion(final String dbName, final int tamperedVersion)
      throws Exception {
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");
    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config.updateVersionForTesting(op, tamperedVersion));
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
  }

  /**
   * Creates a class with one string property and a unique automatic index on it — one engine
   * create through the production path.
   */
  private static void createIndexedClass(final DatabaseSessionEmbedded session,
      final String className) {
    final var cls = session.getMetadata().getSchema().createClass(className);
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex(className + ".val", SchemaClass.INDEX_TYPE.UNIQUE, "val");
  }

  /**
   * Reads the persisted file-base-id floor through the direct (non-cache) accessor.
   */
  private static int floorOf(final AbstractStorage storage) throws Exception {
    return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> ((CollectionBasedStorageConfiguration) storage.configuration)
            .getIndexEngineFileBaseIdFloor(op));
  }

  /**
   * Asserts some throwable in the cause chain carries {@code marker} in its message; open-time
   * failures may be wrapped by the session/storage machinery, so the assertion walks the chain.
   */
  private static void assertMessageChainContains(final Throwable failure, final String marker) {
    var current = failure;
    while (current != null) {
      if (current.getMessage() != null && current.getMessage().contains(marker)) {
        return;
      }
      current = current.getCause();
    }
    throw new AssertionError(
        "expected a failure whose message chain contains '" + marker + "'", failure);
  }
}
