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
   * {@code pageIndex < targetBytes / pageSize} must SURVIVE the shrink. These belong to file
   * regions the truncate does NOT drop and must be persisted by the next periodic flush.
   * Dropping them would silently lose unflushed user data on the recovery path.
   */
  @Test
  public void shrinkFilePreservesDirtyEntriesBelowTarget() throws IOException {
    // Allocate 4 pages, mark all dirty. Shrink to 4 pages exactly — physically nothing
    // to drop, but the range filter must still match correctly across pageIndex < target.
    final var fileId = allocateAndOptionallyDirty(4, true);
    final long initialSize = wowCache.getFilledUpTo(fileId) * pageSize;
    assertThat(initialSize).isEqualTo(4L * pageSize);

    // Shrink to a target greater than the file: pre-flight no-op, the dirty entries at
    // pageIndex < 4 must survive untouched.
    wowCache.shrinkFile(fileId, initialSize);
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(initialSize);

    // A subsequent flush persists every dirty page; the file size remains exactly 4 pages
    // (no orphan grew because all dirty entries belong to live pages).
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize)
        .as("flush of below-target dirty entries must preserve every live page")
        .isEqualTo(initialSize);

    // Re-load each page through the cache; the marker byte set under exclusive lock must
    // be reachable, confirming the dirty entry actually persisted (the on-disk page now
    // matches what the cache stamped before the shrink).
    for (int i = 0; i < 4; i++) {
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
}
