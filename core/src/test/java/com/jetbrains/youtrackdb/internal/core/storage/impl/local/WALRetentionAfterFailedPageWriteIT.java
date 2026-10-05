package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Regression tests for YTDB-475: a write ahead log segment must survive while it is the only
 * recovery source of a committed change.
 *
 * <p>The scenario below reproduces one traced failure sequence. A transaction commits, its
 * write ahead log records become durable, and the write cache then copies the changed database
 * page for writing. The copy leaves the dirty page tables, and the data file write fails. No
 * dirty page is left behind, so the write cache reports that nothing waits for a write. The
 * committed change now lives only in the write ahead log, and deleting that segment loses it.
 *
 * <p>The tests exercise both production routes which delete segments. The background cleanup
 * route runs through {@code AbstractStorage.runWALVacuum} while the page remains unwritten.
 * The explicit full synchronization route runs through {@code AbstractStorage.flushAllData},
 * which retries the page successfully and may delete its old segment. Both routes must preserve
 * the committed record in a crash-state copy.
 *
 * <p>The failing write is injected by replacing the data file of one collection with a
 * delegating file which throws on write. Nothing else is weakened. File synchronization stays
 * enabled, and no production behaviour is altered to create the failure.
 *
 * <p>The fixture closes and reopens a persisted schema baseline before the marker transaction.
 * After each deletion attempt, it pins the remaining write ahead log and copies the paused
 * storage without clean shutdown markers. Background cleanup leaves the marker unwritten, so
 * replay must restore it. Full synchronization writes the marker first, so replay need not redo
 * that record.
 *
 * <p>The tests run in the {@link SequentialTest} category, because they change global
 * configuration and copy raw storage files.
 */
@Category(SequentialTest.class)
public class WALRetentionAfterFailedPageWriteIT {

  private static final String DB_NAME = "walRetentionSource";
  private static final String CLASS_NAME = "Retained";
  private static final String PROPERTY_NAME = "value";
  private static final String MARKER = "committed-before-the-failed-page-write";

  /** Time budget for WAL completion to release operation-table ownership before the cut. */
  private static final long PERSIST_WAIT_TIMEOUT_MS = 2_000;

  private Path buildDirectory;
  private Path reopenableBaseline;
  private YouTrackDBImpl youTrackDB;
  private long previousFuzzyCheckpointInterval;

  @Before
  public void before() throws Exception {
    previousFuzzyCheckpointInterval =
        GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getValueAsLong();
    // A background fuzzy checkpoint would delete segments on its own schedule and make the
    // deletion attempt of each test ambiguous.
    GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.setValue(1_000_000);

    var target = System.getProperty("buildDirectory", "./target");
    buildDirectory = Path.of(target, "walRetentionAfterFailedPageWrite").toAbsolutePath();
    FileUtils.deleteDirectory(buildDirectory.toFile());
    Files.createDirectories(buildDirectory);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory.toString());
    youTrackDB.create(DB_NAME, DatabaseType.DISK, YouTrackDBConfig.defaultConfig(),
        "admin", "admin", "admin");
  }

  @After
  public void after() throws Exception {
    try {
      if (youTrackDB != null && youTrackDB.isOpen()) {
        // The injected write failure can make a close-time flush fail. That is expected and
        // must not hide the result of the test itself.
        try {
          youTrackDB.close();
        } catch (RuntimeException ignored) {
          // Reported by the storage, irrelevant for the assertions above.
        }
      }
    } finally {
      GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL
          .setValue(previousFuzzyCheckpointInterval);
      if (buildDirectory != null) {
        FileUtils.deleteDirectory(buildDirectory.toFile());
      }
    }
  }

  /**
   * Background cleanup route. After a failed page write, {@code runWALVacuum} must keep the
   * segment which holds the redo records of the committed record, and a crash snapshot of the
   * storage must still recover that record.
   */
  @Test
  public void backgroundCleanupKeepsSegmentNeededByCommittedChange() throws Exception {
    runFailedPageWriteScenario(DeletionRoute.BACKGROUND_CLEANUP);
  }

  /**
   * Full synchronization retries the failed page, may delete its obsolete segment, and must
   * leave the committed record present in a crash-state reopen.
   */
  @Test
  public void fullSynchronizationPersistsCommittedChangeBeforeSegmentDeletion() throws Exception {
    runFailedPageWriteScenario(DeletionRoute.FULL_SYNCHRONIZATION);
  }

  /**
   * Successful page writes must still allow deletion. Without this test the fix could keep
   * every segment forever and both tests above would still pass.
   */
  @Test
  public void successfulExistingRetryAllowsLaterSegmentDeletion() throws Exception {
    runFailedPageWriteScenario(DeletionRoute.RETRY_AND_DELETE);
  }

  /** Successful page writes must still allow deletion without a preceding failure. */
  @Test
  public void backgroundCleanupDeletesSegmentAfterSuccessfulPageWrites() throws Exception {
    try (var session = openSession()) {
      createSchema(session);

      // AbstractStorage typed reference: the two deletion routes under test are declared
      // there with package visibility, and this test lives in the same package.
      var storage = (AbstractStorage) session.getStorage();
      var diskStorage = (DiskStorage) storage;
      var wal = storage.getWALInstance();

      insertMarkerRecord(session);
      var commitSegment = wal.end().getSegment();

      // Two new segments make the commit segment a non-active one, which is the only kind the
      // background cleanup may delete.
      wal.appendNewSegment();
      wal.appendNewSegment();

      // Successful write of every dirty page, so nothing holds the commit segment any more.
      storage.getWriteCache().flush();
      wal.flush();

      storage.runWALVacuum();

      assertThat(walSegments(diskStorage.getStoragePath()))
          .as("a segment whose database pages were written successfully must be deleted")
          .doesNotContain(commitSegment);
    }
  }

  /** The production route which attempts the segment deletion. */
  private enum DeletionRoute {
    BACKGROUND_CLEANUP, FULL_SYNCHRONIZATION, RETRY_AND_DELETE
  }

  /**
   * Commits one record, lets its page write fail, executes the selected deletion route, and
   * verifies the route-specific durability outcome in a crash-state copy.
   */
  private void runFailedPageWriteScenario(final DeletionRoute route) throws Exception {
    establishPersistedBaseline();

    Path storagePath;
    long commitSegment;
    Path crashSnapshot;

    try (var session = openSession()) {
      var storage = (AbstractStorage) session.getStorage();
      var wal = storage.getWALInstance();
      var writeCache = storage.getWriteCache();
      storagePath = ((DiskStorage) storage).getStoragePath();

      // The periodic flusher would write the page before the failure can be injected.
      writeCache.pauseBackgroundFlush();

      var markerCollectionId = insertMarkerRecord(session);
      commitSegment = wal.end().getSegment();

      // WAL completion may release operation-table ownership. Cache-owned page protection
      // must remain until the corresponding data-file write succeeds.
      wal.flush();

      // Two new segments make the commit segment a non-active one.
      wal.appendNewSegment();
      wal.appendNewSegment();

      var wowCache = (WOWCache) writeCache;
      var markerCollectionName = session.getCollectionNameById(markerCollectionId);
      var collectionFileId = wowCache.fileIdByName(markerCollectionName + ".pcl");
      var failingFile = injectFailingFile(wowCache, collectionFileId);
      try {
        // The copy phase removes the page from the dirty page tables, then this write fails.
        var flushFailed = false;
        try {
          writeCache.flush(collectionFileId);
        } catch (RuntimeException expected) {
          flushFailed = true;
        }
        assertThat(flushFailed)
            .as("the injected data file write must make the page flush fail")
            .isTrue();
        assertThat(failingFile.failedWrites.get())
            .as("the failure must come from the injected data file write")
            .isPositive();
      } finally {
        // Restore the working file, so the deletion route under test is not aborted by a
        // second failure. The committed change stays unwritten, because its dirty page
        // bookkeeping is already gone.
        restoreFile(wowCache, collectionFileId, failingFile.delegate);
      }

      wal.flush();
      awaitOperationsPersisted(storage);

      if (route == DeletionRoute.RETRY_AND_DELETE) {
        // Explicit flush re-enumerates the retained live page. Its successful newer copy
        // satisfies the failed requirement and allows the next vacuum to delete the segment.
        writeCache.flush();
        storage.runWALVacuum();
        assertThat(walSegments(storagePath))
            .as("a successful existing retry must release the failed page requirement")
            .doesNotContain(commitSegment);
        return;
      }

      switch (route) {
        case BACKGROUND_CLEANUP -> {
          storage.runWALVacuum();
          assertThat(walSegments(storagePath))
              .as("background cleanup must retain unwritten segment %d", commitSegment)
              .contains(commitSegment);
        }
        case FULL_SYNCHRONIZATION -> {
          storage.flushAllData();
          assertThat(walSegments(storagePath))
              .as("full synchronization may delete successfully persisted segment %d",
                  commitSegment)
              .doesNotContain(commitSegment);
        }
        default -> throw new IllegalStateException("Unexpected deletion route " + route);
      }

      assertThat(markerRows(session))
          .as("the live source must contain the marker after route %s", route)
          .isEqualTo(1L);

      // This pin starts after the production cut attempt. It stabilizes only the copy and cannot
      // make the retention assertion above pass.
      wal.flush();
      var copyPin = wal.begin();
      wal.addCutTillLimit(copyPin);
      try {
        crashSnapshot = copyAsCrashSnapshot(storagePath, markerCollectionName, route);
      } finally {
        wal.removeCutTillLimit(copyPin);
      }
    }

    assertRecordSurvivesCrashSnapshot(crashSnapshot, route);
  }

  /** Creates and verifies a self-contained persisted baseline before the marker transaction. */
  private void establishPersistedBaseline() throws IOException {
    try (var session = openSession()) {
      createSchema(session);
    }

    youTrackDB.close();

    reopenableBaseline = buildDirectory.resolve("reopenableBaseline");
    var baselineStorage = reopenableBaseline.resolve(DB_NAME);
    FileUtils.copyDirectory(buildDirectory.resolve(DB_NAME).toFile(), baselineStorage.toFile());

    // Verify a disposable clone. The retained baseline must stay on the source WAL lineage.
    var verificationRoot = buildDirectory.resolve("baselineVerification");
    FileUtils.copyDirectory(reopenableBaseline.toFile(), verificationRoot.toFile());
    var baselineDatabase = (YouTrackDBImpl) YourTracks.instance(verificationRoot.toString());
    try (var session = (DatabaseSessionEmbedded) baselineDatabase.open(
        DB_NAME, "admin", "admin")) {
      assertThat(session.getMetadata().getSchema().getClass(CLASS_NAME))
          .as("the schema baseline must survive an independent copied reopen")
          .isNotNull();
      assertThat(markerRows(session))
          .as("the persisted baseline must not contain the later marker")
          .isZero();
    } finally {
      baselineDatabase.close();
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory.toString());
  }

  /** Opens a session on the source database. */
  private DatabaseSessionEmbedded openSession() {
    return (DatabaseSessionEmbedded) youTrackDB.open(DB_NAME, "admin", "admin");
  }

  private static void createSchema(final DatabaseSessionEmbedded session) {
    session.getMetadata().getSchema().createClass(CLASS_NAME);
  }

  /** Commits one marker and returns the collection selected for that concrete record. */
  private static int insertMarkerRecord(final DatabaseSessionEmbedded session) {
    var entity = session.computeInTx(transaction -> {
      var marker = transaction.newEntity(CLASS_NAME);
      marker.setProperty(PROPERTY_NAME, MARKER);
      return marker;
    });
    return entity.getIdentity().getCollectionId();
  }

  /**
   * Replaces the file registered under {@code fileId} with a delegating file which fails every
   * write, and returns the replaced file.
   */
  @SuppressWarnings("unchecked")
  private static FailingWriteFile injectFailingFile(final WOWCache cache, final long fileId)
      throws Exception {
    var files = fileContainer(cache);
    var realFile = files.remove(fileId);
    assertThat(realFile).as("the collection file must be registered in the write cache")
        .isNotNull();
    var failingFile = new FailingWriteFile(realFile);
    files.add(fileId, failingFile);
    return failingFile;
  }

  /** Puts the original file back under {@code fileId}. */
  private static void restoreFile(final WOWCache cache, final long fileId, final File realFile)
      throws Exception {
    var files = fileContainer(cache);
    files.remove(fileId);
    files.add(fileId, realFile);
  }

  /**
   * Reads the write cache file registry. The registry is private, and replacing one of its
   * entries is the least invasive way to fail a single data file write without changing
   * production code.
   */
  @SuppressWarnings("unchecked")
  private static ClosableLinkedContainer<Long, File> fileContainer(final WOWCache cache)
      throws Exception {
    var field = WOWCache.class.getDeclaredField("files");
    field.setAccessible(true);
    return (ClosableLinkedContainer<Long, File>) field.get(cache);
  }

  /**
   * Waits until WAL completion releases operation-table ownership, or the time budget expires.
   * Cache-owned protection remains independently visible after that ownership transfer.
   */
  private static void awaitOperationsPersisted(final AbstractStorage storage)
      throws InterruptedException {
    var deadline = System.currentTimeMillis() + PERSIST_WAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (storage.atomicOperationsTable.getSegmentEarliestNotPersistedOperation() < 0) {
        return;
      }
      Thread.sleep(25);
    }
  }

  /** Returns the segment numbers of the write ahead log files present in the storage. */
  private static List<Long> walSegments(final Path storagePath) throws IOException {
    try (Stream<Path> files = Files.list(storagePath)) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".wal"))
          .map(WALRetentionAfterFailedPageWriteIT::segmentNumber)
          .filter(segment -> segment >= 0)
          .toList();
    }
  }

  /**
   * Extracts the segment number from a write ahead log file name of the shape
   * {@code <base>.<segment>.wal}, or returns {@code -1} for a name of another shape.
   */
  private static long segmentNumber(final String fileName) {
    var parts = fileName.split("\\.");
    if (parts.length < 3) {
      return -1;
    }
    try {
      return Long.parseLong(parts[parts.length - 2]);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Builds a crash-state copy from the verified baseline and current marker collection files.
   * Background cleanup also copies the retained write ahead log needed to restore the marker.
   *
   * <p>Opening a broad live copy after a full synchronization exposes a separate catalog
   * startup problem. Its configuration B-tree can reference an absent system record.
   * The marker transaction does not modify that catalog. Keeping the verified catalog baseline
   * isolates this test to the collection pages and write ahead log affected by that transaction.
   */
  private Path copyAsCrashSnapshot(
      final Path storagePath,
      final String markerCollectionName,
      final DeletionRoute route) throws IOException {
    var snapshotRoot = buildDirectory.resolve("crashSnapshot");
    FileUtils.deleteDirectory(snapshotRoot.toFile());
    var snapshot = snapshotRoot.resolve(DB_NAME);
    FileUtils.copyDirectory(reopenableBaseline.resolve(DB_NAME).toFile(), snapshot.toFile());

    try (Stream<Path> copiedFiles = Files.list(snapshot)) {
      for (var copiedFile : copiedFiles.toList()) {
        var name = copiedFile.getFileName().toString();
        if (name.startsWith("dirty.fl")
            || (route == DeletionRoute.BACKGROUND_CLEANUP && name.endsWith(".wal"))) {
          Files.deleteIfExists(copiedFile);
        }
      }
    }

    try (Stream<Path> sourceFiles = Files.list(storagePath)) {
      for (var sourceFile : sourceFiles.toList()) {
        var name = sourceFile.getFileName().toString();
        if (!isMarkerCollectionFile(name, markerCollectionName)
            && (route != DeletionRoute.BACKGROUND_CLEANUP || !name.endsWith(".wal"))) {
          continue;
        }
        Files.copy(
            sourceFile,
            snapshot.resolve(name),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }

    return snapshotRoot;
  }

  /** Selects every persistent component changed by insertion into the marker collection. */
  private static boolean isMarkerCollectionFile(
      final String fileName, final String markerCollectionName) {
    if (!fileName.startsWith(markerCollectionName + "_")) {
      return false;
    }
    return fileName.endsWith(".pcl")
        || fileName.endsWith(".cpm")
        || fileName.endsWith(".fsm")
        || fileName.endsWith(".dpb");
  }

  /** Returns the number of records carrying the committed marker. */
  private static long markerRows(final DatabaseSessionEmbedded session) {
    try (var rows = session.query(
        "SELECT count(*) as cnt FROM " + CLASS_NAME + " WHERE " + PROPERTY_NAME + " = ?",
        MARKER)) {
      return rows.next().getProperty("cnt");
    }
  }

  /** Opens the crash-state copy and verifies the route-specific data-survival guarantee. */
  private static void assertRecordSurvivesCrashSnapshot(
      final Path snapshotRoot, final DeletionRoute route) {
    var recovered = (YouTrackDBImpl) YourTracks.instance(snapshotRoot.toString());
    try (var session = (DatabaseSessionEmbedded) recovered.open(DB_NAME, "admin", "admin")) {
      var storage = (DiskStorage) session.getStorage();
      if (route == DeletionRoute.BACKGROUND_CLEANUP) {
        assertThat(storage.wereDataRestoredAfterOpen())
            .as("the unwritten marker requires the retained write ahead log recovery path")
            .isTrue();
      }

      assertThat(session.getMetadata().getSchema().getClass(CLASS_NAME))
          .as("the independently persisted schema must survive the crash-state reopen")
          .isNotNull();

      var survivalReason = route == DeletionRoute.BACKGROUND_CLEANUP
          ? "retained write ahead log replay must restore the unwritten marker"
          : "the marker persisted by full synchronization must survive without its old segment";
      assertThat(markerRows(session)).as(survivalReason).isEqualTo(1L);
    } finally {
      recovered.close();
    }
  }

  /**
   * Delegating storage file which fails every write. Everything else is forwarded, so the
   * write cache observes a normal file until it tries to write a page.
   */
  private static final class FailingWriteFile implements File {

    private final File delegate;
    private final AtomicInteger failedWrites = new AtomicInteger();

    private FailingWriteFile(final File delegate) {
      this.delegate = delegate;
    }

    @Override
    public long allocateSpace(final int size) throws IOException {
      return delegate.allocateSpace(size);
    }

    @Override
    public void shrink(final long size) throws IOException {
      delegate.shrink(size);
    }

    @Override
    public long getFileSize() {
      return delegate.getFileSize();
    }

    @Override
    public void read(final long offset, final ByteBuffer buffer, final boolean throwOnEof)
        throws IOException {
      delegate.read(offset, buffer, throwOnEof);
    }

    @Override
    public void write(final long offset, final ByteBuffer buffer) throws IOException {
      failedWrites.incrementAndGet();
      throw new IOException("Injected single page write failure");
    }

    @Override
    public IOResult write(final List<RawPairLongObject<ByteBuffer>> buffers) throws IOException {
      failedWrites.incrementAndGet();
      throw new IOException("Injected page chunk write failure");
    }

    @Override
    public void synch() {
      delegate.synch();
    }

    @Override
    public void create() throws IOException {
      delegate.create();
    }

    @Override
    public void open() {
      delegate.open();
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public void delete() throws IOException, InterruptedException {
      delegate.delete();
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }

    @Override
    public boolean exists() {
      return delegate.exists();
    }

    @Override
    public String getName() {
      return delegate.getName();
    }

    @Override
    public void renameTo(final Path newFile) throws IOException, InterruptedException {
      delegate.renameTo(newFile);
    }

    @Override
    public long getUnderlyingFileSize() throws IOException {
      return delegate.getUnderlyingFileSize();
    }

    @Override
    public void replaceContentWith(final Path newContentFile)
        throws IOException, InterruptedException {
      delegate.replaceContentWith(newContentFile);
    }

    @Override
    public String toString() {
      return "FailingWriteFile[" + delegate + "]";
    }
  }
}
