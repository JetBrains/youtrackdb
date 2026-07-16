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
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
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
   * Dropping and recreating a same-named index allocates a fresh file base id, so the recreated
   * engine's file family lives under a different {@code ie_<n>} stem and can never collide with
   * any residue of the dropped one — the core D16 regression the stable key exists to prevent.
   */
  @Test
  public void dropAndRecreateSameNamedIndexGetsFreshFileStems() throws Exception {
    final var dbName = "fbiDropRecreate";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;

      final var cls = session.getMetadata().getSchema().createClass("FbiDrop");
      cls.createProperty("val", PropertyType.STRING);
      cls.createIndex("FbiDropIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");

      final int firstFileBaseId =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiDropIdx", -1, op)).getFileBaseId();
      final var firstStem = AbstractStorage.indexEngineFileStem(firstFileBaseId);
      assertTrue("the created engine's data file must carry the ie_ stem",
          storage.getWriteCache().exists(firstStem + ".cbt"));

      session.execute("DROP INDEX FbiDropIdx").close();
      assertTrue("the dropped engine's files must be gone",
          collectEngineFiles(storage, firstStem).isEmpty());

      // Recreate under the SAME index name: a fresh, never-reused stem — no possible collision
      // with anything the dropped incarnation might have left behind.
      cls.createIndex("FbiDropIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");
      final int secondFileBaseId =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiDropIdx", -1, op)).getFileBaseId();
      assertTrue("the recreated same-named index must get a strictly newer file base id",
          secondFileBaseId > firstFileBaseId);
      final var secondStem = AbstractStorage.indexEngineFileStem(secondFileBaseId);
      assertTrue("the recreated engine's data file must live under the new stem",
          storage.getWriteCache().exists(secondStem + ".cbt"));
      assertTrue("no file of the dropped incarnation may reappear",
          collectEngineFiles(storage, firstStem).isEmpty());
    }
  }

  /**
   * AD-10: a schema-carrying commit that fails AFTER the commit window built the engine (all
   * family files booked as WAL-reverted intent in the commit's atomic operation) must revert
   * cleanly: no engine file survives on the disk profile, the burned file base id is never
   * reissued, and a retry of the same-named create succeeds under a fresh stem.
   */
  @Test
  public void failedCommitWindowIndexCreateRevertsBookedFamilyFiles() throws Exception {
    final var dbName = "fbiFailedCommit";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;
      final var hwmBefore = storage.indexEngineFileBaseIdHwmForTesting();
      final var engineFilesBefore = allEngineFiles(storage);

      // The class and property are committed up front (property creation is not yet
      // transactional); only the index create runs inside the transaction, which is exactly the
      // commit-window engine build AD-10 targets.
      final var cls = session.getMetadata().getSchema().createClass("FbiCrash");
      cls.createProperty("val", PropertyType.STRING);

      // Fault after the commit window BUILT the engines (family files booked in the commit's
      // atomic operation, registries published) and before the record apply — the post-build
      // hook, not the pre-build commitWindowTestHook, which fires before any engine exists. The
      // NeedRetryException family keeps the storage OPEN (moveToErrorStateIfNeeded skips it),
      // so the retry below runs against the same storage instance.
      storage.setPostEngineBuildTestHook(() -> {
        throw new CommandInterruptedException(dbName, "injected post-engine-build commit fault");
      });
      try {
        session.begin();
        session.getMetadata().getSchema().getClass("FbiCrash")
            .createIndex("FbiCrashIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");
        try {
          session.commit();
          fail("the schema commit must fail when the in-window fault hook throws");
        } catch (final RuntimeException expected) {
          // Routed through rollback + the engine-create undo arms.
        }
      } finally {
        storage.setPostEngineBuildTestHook(null);
      }

      // Disk profile: the booked family files were never physically created, so the write cache
      // is byte-for-byte back at the baseline — no orphan of the failed create.
      assertEquals("a failed commit must leave no engine file behind",
          engineFilesBefore, allEngineFiles(storage));
      // The failed allocation burned its value: the mark advanced and never reverts.
      assertTrue("the failed create's file base id must be burned, not reverted",
          storage.indexEngineFileBaseIdHwmForTesting() > hwmBefore);

      // The retry succeeds under a fresh stem strictly past the burned value.
      session.executeInTx(tx -> session.getMetadata().getSchema().getClass("FbiCrash")
          .createIndex("FbiCrashIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val"));
      final var retriedData =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiCrashIdx", -1, op));
      assertTrue("the retried create must allocate past the burned id",
          retriedData.getFileBaseId() > hwmBefore);
      assertTrue("the retried engine's data file must exist under its fresh stem",
          storage.getWriteCache().exists(
              AbstractStorage.indexEngineFileStem(retriedData.getFileBaseId()) + ".cbt"));
    }
  }

  /**
   * D16 end-goal pin: a class rename touches no engine storage file. The engine's whole file
   * family is keyed by the stable file base id — not by the index or class name — so the
   * rename leaves the write cache's engine-file set and the engine entry byte-identical.
   * Complements the collection-side rename pin (D11) in {@code ClassTest.testRename}.
   */
  @Test
  public void classRenameLeavesEngineFilesUntouched() throws Exception {
    final var dbName = "fbiRename";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;

      final var cls = session.getMetadata().getSchema().createClass("FbiRenameOld");
      cls.createProperty("val", PropertyType.STRING);
      cls.createIndex("FbiRenameIdx", SchemaClass.INDEX_TYPE.UNIQUE, "val");
      session.executeInTx(tx -> tx.newEntity("FbiRenameOld").setProperty("val", "pinned"));

      final int fileBaseIdBefore =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiRenameIdx", -1, op)).getFileBaseId();
      final var engineFilesBefore = allEngineFiles(storage);
      assertTrue("precondition: the index's engine files exist",
          engineFilesBefore.stream().anyMatch(
              f -> f.startsWith(AbstractStorage.indexEngineFileStem(fileBaseIdBefore))));

      cls.setName("FbiRenameNew");

      assertEquals("a class rename must leave the engine-file set byte-identical",
          engineFilesBefore, allEngineFiles(storage));
      assertEquals("a class rename must not touch the engine entry's file base id",
          fileBaseIdBefore,
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiRenameIdx", -1, op)).getFileBaseId());
    }
  }

  /**
   * AD-6: dropping the database removes the engine's whole five-file family from disk —
   * {@code .cbt}+{@code .nbt} for the main tree, the {@code $null} variants of both for the
   * multi-value null tree, and the {@code .ixs} histogram. The {@code .nbt} extension was
   * missing from {@code DiskStorage.ALL_FILE_EXTENSIONS}, so null-bucket files used to leak and
   * kept the database directory from being deleted.
   */
  @Test
  public void databaseDropRemovesWholeEngineFileFamily() throws Exception {
    final var dbName = "fbiDropDb";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, PWD, "admin");

    final int fileBaseId;
    try (var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, PWD)) {
      final var storage = (AbstractStorage) session.getStorage();
      final var config = (CollectionBasedStorageConfiguration) storage.configuration;
      final var cls = session.getMetadata().getSchema().createClass("FbiDropDb");
      cls.createProperty("val", PropertyType.STRING);
      // NOTUNIQUE → multi-value engine → the full five-file family.
      cls.createIndex("FbiDropDbIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");
      fileBaseId =
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> config.getIndexEngine("FbiDropDbIdx", -1, op)).getFileBaseId();
    }

    final var dbDir = new File(testDir, dbName);
    assertTrue("precondition: the database directory exists", dbDir.isDirectory());
    final var onDisk = dbDir.list();
    assertTrue("precondition: the database directory lists files", onDisk != null);
    final var stem = AbstractStorage.indexEngineFileStem(fileBaseId);
    final var nullStem = stem + AbstractStorage.NULL_TREE_SUFFIX;
    // The write cache stores files on disk under an internal name that inserts the numeric file
    // id before the extension (<stem>_<fileId>.<ext>), so the presence check matches
    // stem-underscore prefix + extension instead of exact names.
    for (final var expected : new String[][] {
        {stem, ".cbt"}, {stem, ".nbt"}, {nullStem, ".cbt"}, {nullStem, ".nbt"},
        {stem, ".ixs"}}) {
      final var memberStem = expected[0];
      final var extension = expected[1];
      assertTrue(
          "precondition: family file " + memberStem + "*" + extension + " must exist on disk",
          java.util.Arrays.stream(onDisk).anyMatch(
              f -> f.startsWith(memberStem + "_") && f.endsWith(extension)));
    }

    ytdb.drop(dbName);

    assertTrue("the drop must remove every family file and the (then-empty) directory",
        !dbDir.exists());
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
   * The write cache's engine-family file names carrying the given {@code ie_<n>} stem (any
   * extension, including the {@code $null} variants).
   */
  private static java.util.Set<String> collectEngineFiles(final AbstractStorage storage,
      final String stem) {
    final var result = new java.util.HashSet<String>();
    for (final var fileName : storage.getWriteCache().files().keySet()) {
      if (fileName.startsWith(stem + ".")
          || fileName.startsWith(stem + AbstractStorage.NULL_TREE_SUFFIX + ".")) {
        result.add(fileName);
      }
    }
    return result;
  }

  /**
   * Every engine-family file name in the write cache (any {@code ie_} stem).
   */
  private static java.util.Set<String> allEngineFiles(final AbstractStorage storage) {
    final var result = new java.util.HashSet<String>();
    for (final var fileName : storage.getWriteCache().files().keySet()) {
      if (fileName.startsWith(AbstractStorage.INDEX_ENGINE_FILE_STEM_PREFIX)) {
        result.add(fileName);
      }
    }
    return result;
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
