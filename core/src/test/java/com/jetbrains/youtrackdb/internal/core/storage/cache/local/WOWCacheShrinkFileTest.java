package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Focused regression tests for {@link WOWCache#shrinkFile(long, long)}.
 *
 * <p>The recovery-time orphan-truncation pass uses this primitive to repair the
 * {@code logical &lt;= physical} invariant on entry-point-equipped storage components after a
 * partial-flush crash. Three load-bearing semantics under test:
 *
 * <ol>
 *   <li><b>Pre-flight no-op.</b> Calling {@code shrinkFile} with a target greater than or equal
 *       to the current physical size must return without invoking the underlying truncate or
 *       perturbing the write-back layer. This is the clean-shutdown path.
 *   <li><b>Above-target dirty entries are discarded.</b> A dirty {@code writeCachePages} entry
 *       at {@code pageIndex >= targetBytes / pageSize} is dropped before the AsyncFile shrink
 *       runs, so a subsequent periodic flush cannot re-extend the file past the target.
 *   <li><b>Below-target dirty entries are preserved.</b> A dirty {@code writeCachePages} entry
 *       at {@code pageIndex < targetBytes / pageSize} survives the shrink and is persisted by
 *       the next flush — these belong to file regions the truncate does NOT drop.
 * </ol>
 *
 * <p>Together (2) and (3) form the symmetry pair the step plan calls out. The bookkeeping (real
 * {@link WOWCache} setup, page allocation, dirty-page installation via {@code store}, flush
 * verification via {@code getFileSize}) mirrors the existing {@code WOWCacheTestIT} pattern so a
 * later reviewer can trace the shape across the existing test surface.
 */
public class WOWCacheShrinkFileTest {

  private static final int pageSize = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long TEST_PAGES_FLUSH_INTERVAL = 10L;
  private static final int TEST_SHUTDOWN_TIMEOUT = 10_000;
  private static final long TEST_EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

  private static final String FILE_NAME = "wowCacheShrinkFileTest.tst";
  private static final String STORAGE_NAME = "WOWCacheShrinkFileTest";

  private Path storagePath;
  private ByteBufferPool bufferPool;
  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private final ClosableLinkedContainer<Long, File> files = new ClosableLinkedContainer<>(1024);

  @BeforeClass
  public static void disableLockingForTest() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
  }

  @AfterClass
  public static void restoreLocking() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(true);
    GlobalConfiguration.FILE_LOCK.setValue(true);
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storagePath = Paths.get(buildDirectory).resolve(STORAGE_NAME);
    deleteCacheAndDeleteFile();
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

    wowCache =
        new WOWCache(
            pageSize,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            TEST_PAGES_FLUSH_INTERVAL,
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
            Executors.newCachedThreadPool());
    wowCache.loadRegisteredFiles();
  }

  @After
  public void tearDown() throws IOException {
    deleteCacheAndDeleteFile();
    if (bufferPool != null) {
      bufferPool.clear();
      bufferPool = null;
    }
  }

  private void deleteCacheAndDeleteFile() throws IOException {
    String nativeFileName = null;
    if (wowCache != null) {
      var fileId = wowCache.fileIdByName(FILE_NAME);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      wowCache.delete();
      wowCache = null;
    }
    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }
    if (nativeFileName != null) {
      var testFile = storagePath.resolve(nativeFileName).toFile();
      if (testFile.exists()) {
        // Best-effort cleanup; harness will fail later if anything sticks.
        //noinspection ResultOfMethodCallIgnored
        testFile.delete();
      }
    }
    if (storagePath != null && Files.exists(storagePath)) {
      var nameIdMapFile = storagePath.resolve("name_id_map.cm").toFile();
      if (nameIdMapFile.exists()) {
        //noinspection ResultOfMethodCallIgnored
        nameIdMapFile.delete();
      }
      nameIdMapFile = storagePath.resolve("name_id_map_v2.cm").toFile();
      if (nameIdMapFile.exists()) {
        //noinspection ResultOfMethodCallIgnored
        nameIdMapFile.delete();
      }
      Files.deleteIfExists(storagePath);
    }
  }

  /**
   * Allocate {@code pageCount} pages in the test file, optionally pinning each one's dirty
   * state in {@code writeCachePages} via {@code store(...)}. Returns the external fileId.
   */
  private long allocateAndOptionallyDirty(final int pageCount, final boolean markDirty)
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    for (int i = 0; i < pageCount; i++) {
      final var allocPointer = wowCache.loadOrAdd(fileId, i, false);
      allocPointer.decrementReadersReferrer();

      if (markDirty) {
        // Stamp a marker byte under the page's exclusive lock so the cache records a dirty
        // entry in writeCachePages. Without store(), the page is allocated but the dirty
        // map stays empty and the range-purge has nothing observable to drop.
        final var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
        long exclusiveStamp = cachePointer.acquireExclusiveLock();
        try {
          var buffer = cachePointer.getBuffer();
          assert buffer != null;
          buffer.put(DurablePage.NEXT_FREE_POSITION, (byte) (i & 0x7F));
        } finally {
          cachePointer.releaseExclusiveLock(exclusiveStamp);
        }
        wowCache.store(fileId, i, cachePointer);
        cachePointer.decrementReadersReferrer();
      }
    }
    return fileId;
  }

  /**
   * Clean-shutdown pre-flight: a target greater than or equal to the current logical size is
   * a no-op — the AsyncFile is not touched and any dirty-page entries are left intact. This
   * is the entry-point check the orchestrator relies on so {@code shrinkFile} can be called
   * unconditionally per component without a per-call physical-size probe.
   */
  @Test
  public void shrinkFileWithTargetAtOrAboveCurrentSizeIsNoOp() throws IOException {
    final var fileId = allocateAndOptionallyDirty(4, false);
    final long initialSize = wowCache.getFilledUpTo(fileId) * pageSize;
    assertThat(initialSize).isEqualTo(4L * pageSize);

    // Equal-target — must not perturb file state
    wowCache.shrinkFile(fileId, initialSize);
    assertThat(wowCache.getFilledUpTo(fileId) * pageSize).isEqualTo(initialSize);

    // Above-target — same no-op contract
    wowCache.shrinkFile(fileId, initialSize + pageSize * 100L);
    assertThat(wowCache.getFilledUpTo(fileId) * pageSize).isEqualTo(initialSize);
  }

  /**
   * Symmetry-pair half (a): a dirty {@code writeCachePages} entry at
   * {@code pageIndex >= targetBytes / pageSize} must be dropped BEFORE the AsyncFile shrink
   * runs, so a subsequent flush cannot re-extend the file past the target. Without the
   * range-scoped purge the orphan dirty entry would be flushed by the next periodic flush
   * and silently re-create the orphan the recovery pass just truncated.
   */
  @Test
  public void shrinkFileDropsDirtyEntriesAtOrAboveTargetBeforeTruncate() throws IOException {
    // Allocate 6 pages and mark them all dirty; the file is logically + physically 6 pages.
    final var fileId = allocateAndOptionallyDirty(6, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(6);

    // Shrink to the first 3 pages — pages [3, 6) become physical orphans.
    final long targetBytes = 3L * pageSize;
    wowCache.shrinkFile(fileId, targetBytes);

    // Physical size matches the target immediately after the shrink — the dirty entries at
    // pageIndex >= 3 were dropped before AsyncFile.shrink, so the truncate took effect.
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    // A subsequent flush must NOT re-extend the file. The dirty entries at pageIndex >= 3
    // were purged from writeCachePages; flushAllData has nothing to write past targetBytes.
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("flush after shrinkFile must not re-extend the file past targetBytes")
        .isEqualTo(targetBytes);
  }

  /**
   * Symmetry-pair half (b): a dirty {@code writeCachePages} entry at
   * {@code pageIndex < targetBytes / pageSize} must SURVIVE a real shrink. These belong to
   * file regions the truncate does NOT drop and must be persisted by the next periodic flush.
   * Dropping them would silently lose unflushed user data on the recovery path.
   *
   * <p>Allocates K=8 dirty pages, calls {@code shrinkFile(fileId, 5 * pageSize)} so the shrink
   * actually fires (not the pre-flight no-op path covered separately by
   * {@code shrinkFileWithTargetAtOrAboveCurrentSizeIsNoOp}), and verifies the 5 below-target
   * pages still carry their pre-shrink dirty markers after the post-shrink flush + reload.
   */
  @Test
  public void shrinkFilePreservesDirtyEntriesBelowTarget() throws IOException {
    // Allocate K=8 pages, mark all dirty. The file is logically + physically 8 pages.
    final var fileId = allocateAndOptionallyDirty(8, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(8);

    // Real shrink to 5 pages — the [5, 8) range is dropped at the WriteCache layer + truncated
    // on disk; the [0, 5) range must survive both the in-memory dirty map and the on-disk
    // post-flush bytes.
    final long targetBytes = 5L * pageSize;
    wowCache.shrinkFile(fileId, targetBytes);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    // Post-shrink flush must persist the surviving dirty entries and NOT re-extend the file.
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("flush after shrinkFile must not re-extend the file past targetBytes")
        .isEqualTo(targetBytes);

    // Re-load each below-target page through the cache; the marker byte set under exclusive
    // lock must be reachable, confirming the dirty entry actually persisted (the on-disk page
    // now matches what the cache stamped before the shrink).
    for (int i = 0; i < 5; i++) {
      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      try {
        var buffer = cachePointer.getBuffer();
        assert buffer != null;
        byte stamped = buffer.get(DurablePage.NEXT_FREE_POSITION);
        assertThat(stamped)
            .as("page %d's pre-shrink dirty marker must persist", i)
            .isEqualTo((byte) (i & 0x7F));
      } finally {
        cachePointer.decrementReadersReferrer();
      }
    }
  }

  /**
   * Mixed range — pages [0, 6) dirty, shrink to 4 pages. Pages [0, 4) must remain dirty and
   * survive the next flush; pages [4, 6) are discarded and the file ends at exactly 4 pages.
   * Catches a regression where the range filter is loose (drops below-target entries) or
   * tight (skips above-target entries).
   */
  @Test
  public void shrinkFilePreservesBelowAndDropsAboveTargetInSamePass() throws IOException {
    final var fileId = allocateAndOptionallyDirty(6, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(6);

    final long targetBytes = 4L * pageSize;
    wowCache.shrinkFile(fileId, targetBytes);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("file must not grow past targetBytes during the post-shrink flush")
        .isEqualTo(targetBytes);

    // Pages [0, 4) keep their dirty stamps after the shrink + flush.
    for (int i = 0; i < 4; i++) {
      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      try {
        var buffer = cachePointer.getBuffer();
        assert buffer != null;
        assertThat(buffer.get(DurablePage.NEXT_FREE_POSITION))
            .as("page %d below targetBytes must keep its dirty marker", i)
            .isEqualTo((byte) (i & 0x7F));
      } finally {
        cachePointer.decrementReadersReferrer();
      }
    }
  }

  /** Negative target — rejected up front. Guards against arithmetic underflow at the call site. */
  @Test
  public void shrinkFileRejectsNegativeTarget() throws IOException {
    final var fileId = allocateAndOptionallyDirty(2, false);
    assertThatThrownBy(() -> wowCache.shrinkFile(fileId, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Target shrink size must be non-negative");
  }

  /**
   * Non-page-aligned target — rejected before any I/O. The orchestrator computes
   * {@code minPageIndex = targetBytes / pageSize} via integer division; a mis-aligned target
   * would silently truncate to the floored page boundary on disk while keeping the half-page
   * region cached, producing a torn read on the very next reload. The production guard fires
   * before either {@code removeCachedPagesAtLeast} or {@code AsyncFile.shrink} runs, so the
   * cache and file state are unperturbed when the exception lands.
   */
  @Test
  public void shrinkFileRejectsNonPageAlignedTarget() throws IOException {
    final var fileId = allocateAndOptionallyDirty(4, false);
    final long unaligned = 3L * pageSize + 1L;
    assertThatThrownBy(() -> wowCache.shrinkFile(fileId, unaligned))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("multiple of pageSize");

    // File state must be untouched — the guard runs before any AsyncFile or
    // writeCachePages mutation.
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(4);
  }

  /**
   * Overflow target — a target whose {@code targetBytes / pageSize} exceeds
   * {@link Integer#MAX_VALUE} would wrap the {@code (int)} cast for {@code minPageIndex} to a
   * negative value; the downstream {@code pageIndex >= minPageIndex} filter would then match
   * every cached entry, purging unrelated dirty regions of the file. The production guard
   * fires before any I/O so no actual file is allocated to the hypothetical 17 TB shape the
   * input describes.
   */
  @Test
  public void shrinkFileRejectsOverflowTarget() throws IOException {
    final var fileId = allocateAndOptionallyDirty(2, false);
    final long overflow = ((long) Integer.MAX_VALUE + 1L) * pageSize;
    assertThatThrownBy(() -> wowCache.shrinkFile(fileId, overflow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overflow");

    // File state must be untouched — the guard runs before any AsyncFile or
    // writeCachePages mutation.
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(2);
  }

  /**
   * Zero-target shrink — every dirty entry at the WriteCache layer must be dropped (the range
   * filter at {@code pageIndex >= 0} matches everything) and the on-disk file must truncate
   * to zero bytes. The LFRC-level zero-target case is covered by
   * {@code testShrinkFileToZeroDropsEverything} in {@code LockFreeReadCacheFileOpsTest}, but
   * that exercise routes through {@code TrackingWriteCache} (a counter-only mock). This test
   * pins the real {@link WOWCache} zero-target path end-to-end including the AsyncFile
   * truncate and the post-flush no-extend property.
   */
  @Test
  public void shrinkFileToZeroDropsEveryDirtyEntryAndTruncates() throws IOException {
    final var fileId = allocateAndOptionallyDirty(5, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(5);

    wowCache.shrinkFile(fileId, 0L);

    // Logical size — getFilledUpTo divides AsyncFile.getFileSize() by pageSize.
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(0);

    // Post-shrink flush must NOT re-extend the file. Every dirty entry was dropped at
    // pageIndex >= 0 before AsyncFile.shrink(0), so flushAllData has nothing to write.
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(0);

    // Physical on-disk size — verifies the underlying file actually shrank, not just that
    // the in-memory AsyncFile counter dropped to zero. AsyncFile prefixes every file with a
    // {@link File#HEADER_SIZE}-byte header that {@link AsyncFile#shrink} never removes; after
    // shrinkFile(0) the file is expected to be exactly the header.
    final var nativeFileName = wowCache.nativeFileNameById(fileId);
    final var underlyingPath = storagePath.resolve(nativeFileName);
    assertThat(Files.size(underlyingPath))
        .as("on-disk file must shrink to exactly HEADER_SIZE bytes after shrinkFile(0)")
        .isEqualTo((long) File.HEADER_SIZE);
  }

  /**
   * Idempotence: the recovery pass re-runs after every partial crash, so a second invocation
   * of {@code shrinkFile(fileId, sameTarget)} must observe an already-shrunk file and hit the
   * pre-flight no-op cleanly. A regression that double-drops below-target dirty entries on
   * the second call would silently lose user data on the recovery path.
   */
  @Test
  public void shrinkFileIsIdempotentOnRepeatedInvocation() throws IOException {
    // 6 dirty pages, shrink to 3 pages, then shrink again with the same target.
    final var fileId = allocateAndOptionallyDirty(6, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(6);

    final long targetBytes = 3L * pageSize;
    wowCache.shrinkFile(fileId, targetBytes);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    // Second call with the same target — should be a pre-flight no-op (file.getFileSize() <=
    // targetBytes branch); the [0, 3) below-target dirty entries must NOT be perturbed.
    wowCache.shrinkFile(fileId, targetBytes);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("second shrinkFile call with the same target must leave file size unchanged")
        .isEqualTo(targetBytes);

    // Post-second-call flush must NOT re-extend the file.
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("flush after idempotent shrinkFile must not re-extend the file")
        .isEqualTo(targetBytes);

    // The 3 below-target pages still flush-persist their dirty markers — confirms the second
    // shrinkFile invocation did NOT clobber the surviving dirty entries.
    for (int i = 0; i < 3; i++) {
      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      try {
        var buffer = cachePointer.getBuffer();
        assert buffer != null;
        assertThat(buffer.get(DurablePage.NEXT_FREE_POSITION))
            .as("page %d's dirty marker must survive the idempotent second shrink", i)
            .isEqualTo((byte) (i & 0x7F));
      } finally {
        cachePointer.decrementReadersReferrer();
      }
    }
  }

  /**
   * End-to-end ordering check: a flush immediately after a real shrink must NOT re-extend the
   * file. This pins the load-bearing invariant that
   * {@code removeCachedPagesAtLeast} runs BEFORE {@code AsyncFile.shrink} inside
   * {@code WOWCache.shrinkFile} (and that {@code WriteCache.shrinkFile} runs BEFORE
   * {@code LockFreeReadCache.clearFileRange} in the orchestrator). A regression that swapped
   * either ordering would let a periodic flush rewrite the dirty above-target entries past
   * the truncate and silently re-create the orphan the recovery pass just removed.
   *
   * <p>The bigger page span (12 pages, shrink to 4) widens the race window the production code
   * forecloses: 8 pages worth of above-target dirty entries must all be dropped before the
   * AsyncFile.shrink fires, and the post-shrink flush must observe an empty above-target
   * dirty set.
   */
  @Test
  public void shrinkFileFlushAfterShrinkDoesNotReExtendFile() throws IOException {
    final var fileId = allocateAndOptionallyDirty(12, true);
    assertThat(wowCache.getFilledUpTo(fileId)).isEqualTo(12);

    final long targetBytes = 4L * pageSize;
    wowCache.shrinkFile(fileId, targetBytes);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    // Trigger a flush of every still-dirty entry. The 8 above-target pages were dropped from
    // writeCachePages BEFORE AsyncFile.shrink, so flushAllData has nothing past targetBytes to
    // write back. If the ordering were reversed, the dirty entries would survive the shrink
    // and the flush would re-extend the file to its original 12-page size.
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("post-shrink flush must not re-extend the file past targetBytes")
        .isEqualTo(targetBytes);

    // The 4 below-target pages survive the shrink + flush — confirms the range filter did not
    // drop them by mistake.
    for (int i = 0; i < 4; i++) {
      var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      try {
        var buffer = cachePointer.getBuffer();
        assert buffer != null;
        assertThat(buffer.get(DurablePage.NEXT_FREE_POSITION))
            .as("page %d below targetBytes must keep its dirty marker", i)
            .isEqualTo((byte) (i & 0x7F));
      } finally {
        cachePointer.decrementReadersReferrer();
      }
    }
  }
}
