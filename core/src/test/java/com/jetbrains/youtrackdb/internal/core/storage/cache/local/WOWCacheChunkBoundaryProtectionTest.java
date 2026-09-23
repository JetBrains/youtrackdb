package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Regression test for the chunk-boundary case of write ahead log protection (YTDB-475, review
 * finding BG-1).
 *
 * <p>A flush round can write several chunk batches. The production loop copies a page, removes it
 * from dirty tracking, then writes the already collected batch, and only afterwards places that
 * copy into its own later batch. An implementation which confirms every copy of the round on the
 * first successful batch loses the protection of the later copy. A failure in that later batch
 * then releases a committed operation whose page never reached the data file.
 *
 * <p>This test exercises the real {@code WOWCache} chunk assembly. It builds a two-page flush
 * round with a reduced chunk limit, lets the first batch write successfully, and fails the write
 * of the second batch. The reported earliest not-written position must still name the page of the
 * failed batch, because the operations manager uses exactly that position to keep write ahead log
 * segments alive.
 */
@Category(SequentialTest.class)
public class WOWCacheChunkBoundaryProtectionTest {

  private static final int pageSize = DurablePage.NEXT_FREE_POSITION + 8;
  private static final int TEST_SHUTDOWN_TIMEOUT = 10_000;
  private static final long TEST_EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

  private static final String FILE_NAME = "wowCacheChunkBoundaryTest.tst";
  private static final String SECOND_FILE_NAME = "wowCacheChunkBoundaryTestSecond.tst";
  private static final String STORAGE_NAME = "WOWCacheChunkBoundaryProtectionTest";

  /** Dirty position of the page written by the first, successful chunk batch. */
  private static final LogSequenceNumber FIRST_PAGE_LSN = new LogSequenceNumber(3, 10);

  /** Dirty position of the page whose own chunk batch fails. */
  private static final LogSequenceNumber SECOND_PAGE_LSN = new LogSequenceNumber(3, 20);

  private static Object savedExclusiveFileAccess;
  private static Object savedFileLock;

  private Path storagePath;
  private ByteBufferPool bufferPool;
  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ExecutorService asyncFileExecutor;
  private final ClosableLinkedContainer<Long, File> files = new ClosableLinkedContainer<>(1024);

  @BeforeClass
  public static void disableLockingForTest() {
    savedExclusiveFileAccess = GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.getValue();
    savedFileLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
  }

  @AfterClass
  public static void restoreLocking() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(savedExclusiveFileAccess);
    GlobalConfiguration.FILE_LOCK.setValue(savedFileLock);
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    // Calling instance can expose an initialization in progress. Calling startup waits for it.
    YouTrackDBEnginesManager.instance().startup();

    var buildDirectory = System.getProperty("buildDirectory", ".");
    storagePath = Paths.get(buildDirectory).resolve(STORAGE_NAME);
    wipeStorageDirectory();
    Files.createDirectories(storagePath);

    bufferPool = new ByteBufferPool(pageSize);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            STORAGE_NAME,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);

    asyncFileExecutor = Executors.newCachedThreadPool();
    wowCache =
        new WOWCache(
            pageSize,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            // No periodic flush interval: the test drives every flush round itself, so no
            // background round can race the assertions.
            0,
            TEST_SHUTDOWN_TIMEOUT,
            TEST_EXCLUSIVE_WRITE_CACHE_MAX_SIZE,
            storagePath,
            STORAGE_NAME,
            files,
            1,
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify,
            null,
            null,
            false,
            asyncFileExecutor);
    wowCache.loadRegisteredFiles();
  }

  @After
  public void tearDown() throws IOException {
    try {
      if (wowCache != null) {
        wowCache.delete();
        wowCache = null;
      }
      if (writeAheadLog != null) {
        writeAheadLog.delete();
        writeAheadLog = null;
      }
    } finally {
      if (asyncFileExecutor != null) {
        asyncFileExecutor.shutdownNow();
        try {
          asyncFileExecutor.awaitTermination(TEST_SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        asyncFileExecutor = null;
      }
      if (bufferPool != null) {
        bufferPool.clear();
        bufferPool = null;
      }
      wipeStorageDirectory();
    }
  }

  private void wipeStorageDirectory() throws IOException {
    if (storagePath == null || !Files.exists(storagePath)) {
      return;
    }
    try (var stream = Files.walk(storagePath)) {
      stream.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best effort: a residue failure surfaces in the next setUp.
                }
              });
    }
  }

  /**
   * One flush round writes the first chunk batch successfully and fails the second batch. The
   * page of the failed batch must remain protected, so the reported earliest not-written
   * position must name it.
   */
  @Test(timeout = 10_000)
  public void failedLaterChunkKeepsProtectionAfterSuccessfulEarlierChunk() throws Exception {
    // One page per chunk batch, so the round crosses a batch boundary with two pages. The
    // production limit is derived from a fixed byte size, so the test lowers it directly.
    setChunkSize(1);

    final var fileId = wowCache.addFile(FILE_NAME);
    dirtyPage(fileId, 0, FIRST_PAGE_LSN);
    dirtyPage(fileId, 1, SECOND_PAGE_LSN);

    final var failingFile = replaceWithSecondPageFailingFile(fileId);

    wowCache.executePeriodicFlush(new PeriodicFlushTask(wowCache));

    assertThat(failingFile.successfulWrites.get())
        .as("the earlier chunk batch must have been written successfully")
        .isEqualTo(1);
    assertThat(failingFile.failedWrites.get())
        .as("the later chunk batch must have failed")
        .isEqualTo(1);
    assertThat(wowCache.getMinimalNotFlushedSegment())
        .as("the page of the failed later batch must keep its WAL segment protected")
        .isEqualTo(SECOND_PAGE_LSN.getSegment());

    assertThatThrownBy(() -> wowCache.flushTillSegment(SECOND_PAGE_LSN.getSegment() + 1))
        .as("vacuum flushing must stop when the periodic write failure prevents progress")
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Error during data flush");
    assertThat(wowCache.getMinimalNotFlushedSegment())
        .as("stopping the flush must preserve the failed page retention boundary")
        .isEqualTo(SECOND_PAGE_LSN.getSegment());
  }

  /**
   * Two file writes share one drain. A failure from the first result must not release either file
   * handle or source buffer before the second result finishes.
   */
  @Test(timeout = 10_000)
  public void failedResultStillDrainsPendingResultBeforeReleasingResources() throws Exception {
    final var firstFileId = wowCache.addFile(FILE_NAME);
    final var secondFileId = wowCache.addFile(SECOND_FILE_NAME);
    dirtyPage(firstFileId, 0, FIRST_PAGE_LSN);
    dirtyPage(secondFileId, 0, SECOND_PAGE_LSN);

    final var coordinator = new MultipleResultCoordinator();
    final var firstFile = replaceWithCoordinatedFile(firstFileId, coordinator);
    final var secondFile = replaceWithCoordinatedFile(secondFileId, coordinator);
    final var fileIds = new IntOpenHashSet();
    fileIds.add(WOWCache.extractFileId(firstFileId));
    fileIds.add(WOWCache.extractFileId(secondFileId));

    final var flushExecutor = Executors.newSingleThreadExecutor();
    try {
      final var flushFuture = flushExecutor.submit(() -> wowCache.executeFileFlush(fileIds));

      assertThat(coordinator.secondAwaitEntered.await(5, TimeUnit.SECONDS))
          .as("the drain must reach the pending result after the first result fails")
          .isTrue();
      assertThat(flushFuture.isDone())
          .as("the flush must remain blocked until the pending result completes")
          .isFalse();
      assertThat(coordinator.firstAwaitCalls.get()).isEqualTo(1);
      assertThat(coordinator.secondAwaitCalls.get()).isEqualTo(1);
      assertThat(coordinator.pendingBufferChecks.get())
          .as("the pending result must retain its direct source buffer")
          .isEqualTo(1);
      assertThat(files.close(firstFileId))
          .as("the first file handle must remain acquired while another result is pending")
          .isFalse();
      assertThat(files.close(secondFileId))
          .as("the pending file handle must remain acquired until its result completes")
          .isFalse();

      coordinator.allowSecondCompletion.countDown();

      assertThatThrownBy(() -> flushFuture.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .satisfies(failure -> assertThat(failure.getCause()).isSameAs(coordinator.firstFailure));
      assertThat(coordinator.pendingBufferChecks.get())
          .as("the pending result must retain its source bytes through completion")
          .isEqualTo(2);
      assertThat(files.close(firstFileId)).isTrue();
      assertThat(files.close(secondFileId)).isTrue();
      assertThat(firstFile.closeCalls.get()).isEqualTo(1);
      assertThat(secondFile.closeCalls.get()).isEqualTo(1);
    } finally {
      coordinator.allowSecondCompletion.countDown();
      flushExecutor.shutdownNow();
      assertThat(flushExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** A later explicit flush satisfies and releases a requirement retained after failure. */
  @Test
  public void successfulExistingRetryReleasesFailedRequirement() throws Exception {
    final var fileId = wowCache.addFile(FILE_NAME);
    dirtyPage(fileId, 0, FIRST_PAGE_LSN);
    final var failOnceFile = replaceWithFailOnceFile(fileId);
    final var fileIds = new IntOpenHashSet();
    fileIds.add(WOWCache.extractFileId(fileId));

    assertThatThrownBy(() -> wowCache.executeFileFlush(fileIds)).isInstanceOf(IOException.class);
    assertThat(wowCache.getMinimalNotFlushedSegment()).isEqualTo(FIRST_PAGE_LSN.getSegment());

    wowCache.executeFileFlush(fileIds);

    assertThat(failOnceFile.writeAttempts.get()).isEqualTo(2);
    assertThat(wowCache.getMinimalNotFlushedSegment()).isNull();
  }

  /** A fixture rebuilt after global engine shutdown receives initialized metrics services. */
  @Test
  public void rebuildAfterEngineShutdownInitializesRequiredServices() throws Exception {
    tearDown();
    YouTrackDBEnginesManager.instance().shutdown();

    setUp();

    assertThat(YouTrackDBEnginesManager.instance().getMetricsRegistry()).isNotNull();
    assertThat(wowCache.getMinimalNotFlushedSegment()).isNull();
  }

  /** Allocates a page, records its dirty position, and publishes it to the write cache. */
  private void dirtyPage(final long fileId, final int pageIndex, final LogSequenceNumber lsn)
      throws IOException {
    final var allocated = wowCache.loadOrAdd(fileId, pageIndex, false);
    allocated.decrementReadersReferrer();

    final var cachePointer = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), false);
    final var exclusiveStamp = cachePointer.acquireExclusiveLock();
    try {
      final var buffer = cachePointer.getBuffer();
      assert buffer != null;
      buffer.put(DurablePage.NEXT_FREE_POSITION, (byte) (pageIndex + 1));
      // Production records the dirty position under the page's exclusive lock, exactly like
      // the read cache does during a commit.
      wowCache.updateDirtyPagesTable(cachePointer, lsn);
    } finally {
      cachePointer.releaseExclusiveLock(exclusiveStamp);
    }
    wowCache.store(fileId, pageIndex, cachePointer);
    cachePointer.decrementReadersReferrer();
  }

  /** Lowers the chunk page limit, which production derives from a fixed byte size. */
  private void setChunkSize(final int chunkSize) throws Exception {
    final var field = WOWCache.class.getDeclaredField("chunkSize");
    field.setAccessible(true);
    field.setInt(wowCache, chunkSize);
  }

  /** Replaces the data file with one that fails the write of page index one. */
  private ControlledFailingFile replaceWithSecondPageFailingFile(final long fileId)
      throws Exception {
    final var field = WOWCache.class.getDeclaredField("files");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var container = (ClosableLinkedContainer<Long, File>) field.get(wowCache);

    final var realFile = container.remove(fileId);
    assertThat(realFile).as("the test file must be registered").isNotNull();
    final var failingFile = new ControlledFailingFile(realFile, false);
    container.add(fileId, failingFile);
    return failingFile;
  }

  /** Replaces a data file with one that returns a coordinated asynchronous result. */
  private CoordinatedFile replaceWithCoordinatedFile(
      final long fileId, final MultipleResultCoordinator coordinator) throws Exception {
    final var realFile = files.remove(fileId);
    assertThat(realFile).as("the test file must be registered").isNotNull();
    final var coordinatedFile = new CoordinatedFile(realFile, coordinator);
    files.add(fileId, coordinatedFile);
    return coordinatedFile;
  }

  /** Replaces the data file with one that fails its first write attempt only. */
  private ControlledFailingFile replaceWithFailOnceFile(final long fileId) throws Exception {
    final var field = WOWCache.class.getDeclaredField("files");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var container = (ClosableLinkedContainer<Long, File>) field.get(wowCache);

    final var realFile = container.remove(fileId);
    assertThat(realFile).as("the test file must be registered").isNotNull();
    final var failingFile = new ControlledFailingFile(realFile, true);
    container.add(fileId, failingFile);
    return failingFile;
  }

  /** Assigns deterministic failure and blocking behavior to two submitted file results. */
  private static final class MultipleResultCoordinator {

    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger firstAwaitCalls = new AtomicInteger();
    private final AtomicInteger secondAwaitCalls = new AtomicInteger();
    private final AtomicInteger pendingBufferChecks = new AtomicInteger();
    private final CountDownLatch secondAwaitEntered = new CountDownLatch(1);
    private final CountDownLatch allowSecondCompletion = new CountDownLatch(1);
    private final StorageException firstFailure =
        new StorageException(STORAGE_NAME, "Injected first result failure");

    private IOResult submit(final List<RawPairLongObject<ByteBuffer>> buffers) {
      final var submission = submissions.getAndIncrement();
      assertThat(buffers).hasSize(1);
      final var sourceBuffer = buffers.getFirst().second;
      assertThat(sourceBuffer.isDirect()).isTrue();

      if (submission == 0) {
        return () -> {
          firstAwaitCalls.incrementAndGet();
          throw firstFailure;
        };
      }
      if (submission == 1) {
        return () -> {
          secondAwaitCalls.incrementAndGet();
          final var expectedSourceByte = sourceBuffer.get(DurablePage.NEXT_FREE_POSITION);
          assertThat(expectedSourceByte).isNotZero();
          pendingBufferChecks.incrementAndGet();
          secondAwaitEntered.countDown();
          try {
            allowSecondCompletion.await();
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting controlled completion", e);
          }
          assertThat(sourceBuffer.get(DurablePage.NEXT_FREE_POSITION))
              .isEqualTo(expectedSourceByte);
          pendingBufferChecks.incrementAndGet();
        };
      }
      throw new AssertionError("Expected exactly two submitted file results");
    }
  }

  /** Delegating file which exposes coordinated asynchronous write results. */
  private static final class CoordinatedFile implements File {

    private final File delegate;
    private final MultipleResultCoordinator coordinator;
    private final AtomicInteger closeCalls = new AtomicInteger();

    private CoordinatedFile(final File delegate, final MultipleResultCoordinator coordinator) {
      this.delegate = delegate;
      this.coordinator = coordinator;
    }

    @Override
    public IOResult write(final List<RawPairLongObject<ByteBuffer>> buffers) {
      return coordinator.submit(buffers);
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
      delegate.write(offset, buffer);
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
      closeCalls.incrementAndGet();
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
  }

  /** Delegating file with deterministic second-page or first-attempt failure modes. */
  private static final class ControlledFailingFile implements File {

    private final File delegate;
    private final boolean failFirstAttempt;
    private final AtomicInteger writeAttempts = new AtomicInteger();
    private final AtomicInteger successfulWrites = new AtomicInteger();
    private final AtomicInteger failedWrites = new AtomicInteger();

    private ControlledFailingFile(final File delegate, final boolean failFirstAttempt) {
      this.delegate = delegate;
      this.failFirstAttempt = failFirstAttempt;
    }

    @Override
    public IOResult write(final List<RawPairLongObject<ByteBuffer>> buffers) throws IOException {
      final var attempt = writeAttempts.incrementAndGet();
      if (failFirstAttempt && attempt == 1) {
        failedWrites.incrementAndGet();
        throw new IOException("Injected first-attempt write failure");
      }
      if (!failFirstAttempt) {
        for (final var buffer : buffers) {
          if (buffer.first == (long) pageSize) {
            failedWrites.incrementAndGet();
            throw new IOException("Injected write failure for the second chunk batch");
          }
        }
      }
      final var result = delegate.write(buffers);
      successfulWrites.incrementAndGet();
      return result;
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
      delegate.write(offset, buffer);
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
      return "ControlledFailingFile[" + delegate + "]";
    }
  }
}
