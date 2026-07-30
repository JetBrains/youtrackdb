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
 * Regression tests for {@link WOWCache#shrinkFile(long, long)} and for the exclusive-write page
 * accounting that the file-purge path ({@code doRemoveCachePages}) maintains.
 *
 * <p>The class covers two related areas. The original one is {@code shrinkFile} itself, listed
 * below. The second, added with the accounting-leak fix, is the {@code exclusiveWritePages} /
 * {@code exclusiveWriteCacheSize} bookkeeping that {@code deleteFile}, {@code close(fileId,false)}
 * and {@code shrinkFile} all drive through the same purge: those tests live here because this class
 * already builds a real single-file {@link WOWCache} and has the {@code allocateAndOptionallyDirty}
 * helper needed to put pages into a deterministic writers-only state. They are named
 * {@code ...ExclusiveWritePage...} / {@code ...Sweep...} rather than {@code shrinkFile...} so the
 * split is visible from the test names alone.
 *
 * <p>The recovery-time orphan-truncation pass uses the shrink primitive to repair the
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
 *
 * <p><b>Boolean return contract.</b> {@code shrinkFile} now returns {@code true} iff the file was
 * physically truncated and {@code false} on the pre-flight no-op (a target at or above the current
 * size). The no-op and real-truncate tests below assert the return so the read-cache orchestrator
 * can trust it to gate its purge.
 */
public class WOWCacheShrinkFileTest {

  private static final int pageSize = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long TEST_PAGES_FLUSH_INTERVAL = 10L;
  private static final int TEST_SHUTDOWN_TIMEOUT = 10_000;
  private static final long TEST_EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

  /**
   * Page count used by the exclusive-write-page accounting tests. Comfortably below
   * {@link #TEST_EXCLUSIVE_WRITE_CACHE_MAX_SIZE} so no writer-latching flush is triggered
   * while the test is building its precondition state, and large enough that the shrink test
   * can split it into a purged range and a surviving range.
   */
  private static final int LEAK_TEST_PAGES = 6;

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
    // Recursive best-effort wipe, mirroring WOWCacheLoadOrAddTest.cleanUp(). This used to
    // delete a hand-written list of names (name_id_map.cm, name_id_map_v2.cm) and then call
    // Files.deleteIfExists(storagePath), which throws DirectoryNotEmptyException on anything
    // the list misses — name_id_map_v3.cm (the format actually in use) and the WAL segments
    // among them. On a passing test those are removed by wowCache.delete() /
    // writeAheadLog.delete() above, so the gap was invisible; but if a test aborts partway
    // (for example on the -ea exclusive-write-counter assertion) the leftovers make setUp
    // itself throw for every subsequent run against the same target directory, turning one
    // real failure into a permanently red class until the directory is wiped by hand.
    if (storagePath != null && Files.exists(storagePath)) {
      // Collect per-path failures instead of swallowing them. Silently ignoring an IOException
      // here is what let the original residue problem hide: the wipe appears to succeed, and
      // the next run fails far away in setUp with a DirectoryNotEmptyException that names no
      // cause. Anything left behind is reported with the paths that could not be removed.
      final var undeletable = new java.util.ArrayList<String>();
      try (var stream = Files.walk(storagePath)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    undeletable.add(p + " (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + ")");
                  }
                });
      }
      if (!undeletable.isEmpty()) {
        throw new IOException(
            "Could not fully wipe the test storage directory "
                + storagePath
                + "; leftovers will break every subsequent run of this class: "
                + undeletable);
      }
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
   *
   * <p>Allocates 4 dirty pages so the "dirty-page entries are left intact" branch of the
   * contract is observable. Each page carries a per-page marker byte at
   * {@code DurablePage.NEXT_FREE_POSITION}; after the no-op shrink the pages must still
   * carry the same marker on a re-load. A regression that mutated {@code shrinkFile}'s
   * pre-flight to purge every dirty entry on the no-op branch (for example, lifting the
   * range-purge above the pre-flight) would lose the marker bytes and fail the per-page
   * assertions below. The {@code markDirty=false} variant pinned only the file-size
   * branch and was vacuous against the dirty-entry contract.
   */
  @Test
  public void shrinkFileWithTargetAtOrAboveCurrentSizeIsNoOp() throws IOException {
    final var fileId = allocateAndOptionallyDirty(4, true);
    final long initialSize = wowCache.getFilledUpTo(fileId) * pageSize;
    assertThat(initialSize).isEqualTo(4L * pageSize);

    // Equal-target — must not perturb file state and must report false (nothing truncated).
    assertThat(wowCache.shrinkFile(fileId, initialSize))
        .as("equal-target shrinkFile is a no-op and must return false")
        .isFalse();
    assertThat(wowCache.getFilledUpTo(fileId) * pageSize).isEqualTo(initialSize);

    // Above-target — same no-op contract, same false return.
    assertThat(wowCache.shrinkFile(fileId, initialSize + pageSize * 100L))
        .as("above-target shrinkFile is a no-op and must return false")
        .isFalse();
    assertThat(wowCache.getFilledUpTo(fileId) * pageSize).isEqualTo(initialSize);

    // Flush so the dirty entries are persisted, then verify each page's marker byte
    // survived the no-op shrink branch. The round-trip through load(...) catches the
    // regression class where a future change silently purges dirty entries on the
    // pre-flight branch (e.g., moving the range-purge above the file-size check).
    wowCache.flush();
    assertThat(wowCache.getFilledUpTo(fileId) * pageSize)
        .as("flush after no-op shrinkFile must not perturb file size")
        .isEqualTo(initialSize);

    for (int i = 0; i < 4; i++) {
      final var cachePointer = wowCache.load(fileId, i, new ModifiableBoolean(), false);
      try {
        var buffer = cachePointer.getBuffer();
        assert buffer != null;
        assertThat(buffer.get(DurablePage.NEXT_FREE_POSITION))
            .as("page %d's dirty marker must survive the no-op shrink", i)
            .isEqualTo((byte) (i & 0x7F));
      } finally {
        cachePointer.decrementReadersReferrer();
      }
    }
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
    assertThat(wowCache.shrinkFile(fileId, targetBytes))
        .as("a real truncate must return true")
        .isTrue();

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

    assertThat(wowCache.shrinkFile(fileId, 0L))
        .as("a real zero-target truncate must return true")
        .isTrue();

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
    assertThat(wowCache.shrinkFile(fileId, targetBytes))
        .as("first shrinkFile is a real truncate and must return true")
        .isTrue();
    assertThat(wowCache.getFilledUpTo(fileId) * (long) pageSize).isEqualTo(targetBytes);

    // Second call with the same target — should be a pre-flight no-op (file.getFileSize() <=
    // targetBytes branch); the [0, 3) below-target dirty entries must NOT be perturbed, and the
    // already-shrunk file truncates nothing so the return must be false.
    assertThat(wowCache.shrinkFile(fileId, targetBytes))
        .as("second shrinkFile with the same target is a no-op and must return false")
        .isFalse();
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
   * {@code LockFreeReadCache.clearFile} in the orchestrator). A regression that swapped
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

  /**
   * Contract-violation signal: invoking {@code shrinkFile} against a fileId that has no
   * open entry in {@code files} surfaces a {@link com.jetbrains.youtrackdb.internal.core
   * .exception.StorageException}. The recovery-time orphan-truncation orchestrator
   * iterates already-open components, so a null entry here means the orchestrator
   * dispatched against a stale fileId — a programming error that previously surfaced as
   * a silent no-op (any subsequent partial-flush orphan on that file would persist). The
   * orchestrator wraps each dispatch in a try/catch that absorbs the throw with a WARN
   * log so a single contract violation does not poison recovery for other components.
   */
  @Test
  public void shrinkFileThrowsOnMissingFileEntry() throws IOException {
    // Allocate a file, then delete it from the WOWCache.files map (mirroring a stale
    // fileId reaching the recovery pass) and re-dispatch shrinkFile.
    final var fileId = allocateAndOptionallyDirty(2, false);
    wowCache.deleteFile(fileId);

    assertThatThrownBy(() -> wowCache.shrinkFile(fileId, pageSize))
        .isInstanceOf(
            com.jetbrains.youtrackdb.internal.core.exception.StorageException.class)
        .hasMessageContaining("no file entry is open");
  }

  /**
   * Regression test for the exclusive-write-page accounting leak in
   * {@code WOWCache.doRemoveCachePages} — the <b>deleteFile</b> ordering, asserted strictly.
   *
   * <p><b>Scenario.</b> Allocate {@code LEAK_TEST_PAGES} pages and install a dirty
   * {@code writeCachePages} entry for each, then drop the reader reference on every page. That
   * last step is what publishes each page key into {@code exclusiveWritePages}:
   * {@code CachePointer.decrementReadersReferrer} fires {@code addOnlyWriters} once a page
   * reaches {@code readers == 0 && writers > 0}. It reproduces in-process exactly the state the
   * real orchestration produces on this ordering, where
   * {@code LockFreeReadCache.deleteFile}/{@code closeFile} purge the READ cache first (dropping
   * the reader references) and only then call into the write cache. Deleting the file must then
   * return both the counter and the set to zero.
   *
   * <p><b>The bug.</b> {@code doRemoveCachePages} nulls each page's writers listener before
   * calling {@code decrementWritersReferrer}, deliberately suppressing the
   * {@code removeOnlyWriters} callback. Before the fix nothing else did the bookkeeping, so
   * every deleted file's page keys were orphaned in {@code exclusiveWritePages} and
   * {@code exclusiveWriteCacheSize} never came back down. A permanently non-zero counter pins
   * the periodic flush task at its 1 ms re-arm interval instead of 25 ms for the whole life of
   * the storage, and once the leaked count reaches {@code exclusiveWriteCacheMaxSize} it starts
   * latching writers.
   *
   * <p><b>Why the counter and the set are asserted independently, never against each other.</b>
   * They leak in lockstep — the buggy path skipped both mutations — so
   * {@code assertThat(counter).isEqualTo(setSize)} held true before the fix as well and proves
   * nothing. Each is therefore pinned to an absolute expected value: exactly
   * {@code LEAK_TEST_PAGES} before the delete and exactly zero after it. The set-size assertion
   * is the one that distinguishes "the counter was decremented" from "the key was actually
   * removed", which is why {@code getExclusiveWritePagesCountForTest()} exists.
   *
   * <p><b>Why background flushing is paused.</b> This cache is built with a 10 ms
   * {@code pagesFlushInterval}. A periodic flush would write the dirty pages back and remove
   * their keys through the normal {@code removeOnlyWriters} path, making the non-zero
   * precondition (and hence the whole test) racy. {@code pauseBackgroundFlush()} additionally
   * barriers on the commit executor, so any flush already in flight has fully returned before
   * the test builds its state.
   */
  @Test
  public void deleteFileClearsExclusiveWritePageAccounting() throws IOException {
    wowCache.pauseBackgroundFlush();
    try {
      // Baseline: a fresh cache has no exclusive-write pages at all.
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("fresh cache must report a zero exclusive-write page counter")
          .isZero();
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("fresh cache must hold an empty exclusive-write page set")
          .isZero();

      final var fileId = allocateAndOptionallyDirty(LEAK_TEST_PAGES, true);

      // Non-zero precondition. Without this the post-delete zero assertions would pass
      // vacuously against a cache that never accumulated anything to leak.
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("every dirty reader-free page must be counted in the exclusive-write counter")
          .isEqualTo(LEAK_TEST_PAGES);
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("every dirty reader-free page must be present in the exclusive-write page set")
          .isEqualTo(LEAK_TEST_PAGES);

      // deleteFile submits DeleteFileTask to the single-threaded commit executor and blocks on
      // its future, so doRemoveCachePages has fully run by the time this call returns.
      wowCache.deleteFile(fileId);

      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("deleteFile must return the exclusive-write counter to zero (back-pressure and"
              + " the 25 ms flush re-arm both depend on it reaching zero)")
          .isZero();
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("deleteFile must actually empty the exclusive-write page set, not merely fix up"
              + " the counter")
          .isZero();
    } finally {
      wowCache.resumeBackgroundFlush();
    }
  }

  /**
   * Same accounting invariant as {@link #deleteFileClearsExclusiveWritePageAccounting}, on the
   * <b>closeFile</b> ordering, also asserted strictly.
   *
   * <p>{@code close(fileId, flush = false)} reaches {@code doRemoveCachePages} through
   * {@code removeCachedPages} rather than through {@code DeleteFileTask}, and
   * {@code LockFreeReadCache.closeFile} likewise purges the read cache before delegating. This
   * test exists so a future change that fixes (or breaks) only the delete path cannot pass:
   * both entry points must leave the counter and the set at zero.
   *
   * <p>{@code flush = false} is deliberate — with {@code flush = true} the pages would be
   * written back and unregistered through the ordinary {@code removeOnlyWriters} callback,
   * which is not the path under test.
   */
  @Test
  public void closeFileWithoutFlushClearsExclusiveWritePageAccounting() throws IOException {
    wowCache.pauseBackgroundFlush();
    try {
      final var fileId = allocateAndOptionallyDirty(LEAK_TEST_PAGES, true);

      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("non-zero precondition: counter must reflect the dirty reader-free pages")
          .isEqualTo(LEAK_TEST_PAGES);
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("non-zero precondition: set must hold one key per dirty reader-free page")
          .isEqualTo(LEAK_TEST_PAGES);

      wowCache.close(fileId, false);

      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("close(fileId, false) must return the exclusive-write counter to zero")
          .isZero();
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("close(fileId, false) must actually empty the exclusive-write page set")
          .isZero();
    } finally {
      wowCache.resumeBackgroundFlush();
    }
  }

  /**
   * Accounting behaviour of the range-scoped purge on the <b>shrinkFile</b> ordering —
   * deliberately asserted only as a <i>direction of improvement</i>, never as a strict
   * zero-leak claim.
   *
   * <p><b>Why not strict.</b> The production comment in {@code doRemoveCachePages} documents a
   * known, unclosed window on this ordering: {@code LockFreeReadCache.shrinkFile} (and
   * {@code truncateFile}) purge the write cache FIRST and clear the read cache afterwards, so
   * the read cache is still live while the purge runs and a concurrent release or eviction can
   * call {@code decrementReadersReferrer -> addOnlyWriters} and re-publish a key after the
   * post-loop sweep has already passed it. The in-process shape below happens to be
   * deterministic (there is no read cache and no eviction thread in this harness), but pinning
   * an exact post-shrink value here would assert an invariant the production code explicitly
   * does not promise, and would turn any future eviction-timing change into a spurious
   * failure. Only the two properties the code does guarantee are asserted.
   *
   * <p><b>Property 1 — the purged range is accounted for.</b> The counter and the set must
   * strictly decrease across the shrink. Before the fix they did not move at all, so this is
   * the load-bearing half.
   *
   * <p><b>Property 2 — the surviving range is NOT over-purged.</b> Neither may drop below the
   * number of pages the shrink keeps ({@code minPageIndex}). This is the guard against the
   * whole-file sweep and the unconditional decrement that review rejected: both would run the
   * counter down past the surviving pages and, on the real ordering, straight into negative
   * territory, which silently disables the exclusive-write back-pressure altogether.
   */
  @Test
  public void shrinkFileAccountsForThePurgedRangeWithoutOverPurgingTheSurvivors()
      throws IOException {
    wowCache.pauseBackgroundFlush();
    try {
      final var fileId = allocateAndOptionallyDirty(LEAK_TEST_PAGES, true);

      final long counterBefore = wowCache.getExclusiveWriteCachePagesSize();
      final int setSizeBefore = wowCache.getExclusiveWritePagesCountForTest();
      assertThat(counterBefore)
          .as("non-zero precondition: counter must reflect the dirty reader-free pages")
          .isEqualTo(LEAK_TEST_PAGES);
      assertThat(setSizeBefore)
          .as("non-zero precondition: set must hold one key per dirty reader-free page")
          .isEqualTo(LEAK_TEST_PAGES);

      // Keep the first survivingPages pages, drop the rest.
      final int survivingPages = 2;
      assertThat(wowCache.shrinkFile(fileId, (long) survivingPages * pageSize))
          .as("a real truncate must return true")
          .isTrue();

      // Exact expected value, not a bound. In THIS harness the outcome is fully determined:
      // all LEAK_TEST_PAGES keys are published before the shrink, the purge runs to completion
      // inside shrinkFile (which blocks on the task's future), and the only other thread that
      // could publish a key — a read-cache release or eviction — does not exist here, because
      // the test drives WOWCache directly with no LockFreeReadCache and no evictor. Asserting
      // a range instead would let a mutant that purges the wrong number of keys pass.
      //
      // This is a claim about the harness, NOT a zero-leak promise for the production shrink
      // ordering: there the read cache is still live during the purge and a key can be
      // re-published after the sweep, which is why doRemoveCachePages documents that window as
      // a known limitation.
      final int expectedAfterShrink = survivingPages;
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("shrinkFile must account for exactly the purged page range: %d published pages"
              + " minus the %d dropped at or above the target",
              counterBefore, LEAK_TEST_PAGES - survivingPages)
          .isEqualTo(expectedAfterShrink);
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("shrinkFile must remove exactly the purged page range from the set, leaving the"
              + " %d below-target keys (a whole-file sweep or a removal-independent"
              + " decrement would drop those too and, on the real ordering, drive the"
              + " counter negative)",
              survivingPages)
          .isEqualTo(expectedAfterShrink);
      assertThat(setSizeBefore - wowCache.getExclusiveWritePagesCountForTest())
          .as("exactly the at-or-above-target keys must have been removed")
          .isEqualTo(LEAK_TEST_PAGES - survivingPages);
    } finally {
      wowCache.resumeBackgroundFlush();
    }
  }

  /**
   * The <b>conditional</b> half of the accounting fix: a dirty page that still has a reader
   * must NOT be decremented when its file is purged.
   *
   * <p><b>Why this shape matters.</b> A page only enters {@code exclusiveWritePages} once it
   * becomes writers-only ({@code readers == 0 && writers > 0}). On the
   * {@code truncateFile}/{@code shrinkFile} ordering the write cache is purged BEFORE the read
   * cache is cleared, so the pages still have readers and their keys are NOT in the set when
   * {@code doRemoveCachePages} runs. This test reproduces that in-process by installing a dirty
   * entry via {@code store} and deliberately holding the reader reference across the delete.
   *
   * <p>A removal-independent {@code exclusiveWriteCacheSize.decrementAndGet()} in the purge — the
   * variant review rejected — would take the counter to -1 here. A negative counter is strictly
   * worse than the leak being fixed: {@code checkCacheOverflow} enforces writer back-pressure
   * with {@code while (exclusiveWriteCacheSize.get() > exclusiveWriteCacheMaxSize)} and
   * {@code executePeriodicFlush} only flushes exclusive pages while the counter is {@code >= 0},
   * so one negative value disables both for the rest of the storage's lifetime. The counter must
   * therefore stay at exactly zero and never go below it.
   *
   * <p><b>This test passes against the pre-fix code, by design.</b> Before the fix the purge did
   * no accounting at all, so the counter also stayed at zero here. It is not a regression test
   * for the shipped leak (that is {@link #deleteFileClearsExclusiveWritePageAccounting}); it is a
   * guard against the *rejected alternative implementation* — the removal-independent decrement.
   *
   * <p>Detecting that mutant needs the clamp counter, not the value assertions. The production
   * clamp deliberately prevents the counter going negative, so a purge that decrements without
   * having removed anything now leaves the counter at zero too — indistinguishable from correct
   * behaviour by value alone. {@code getExclusiveWriteAccountingClampsForTest()} is the
   * observable difference: the clamp must never engage on this deterministic single-threaded
   * path, and it engages exactly once per purged page under the mutant. Verified by mutation:
   * with the removal guard removed, this test fails on the clamp assertion below.
   */
  @Test
  public void deleteFileDoesNotDecrementForPagesThatWereNeverPublishedAsWritersOnly()
      throws IOException {
    wowCache.pauseBackgroundFlush();
    try {
      final var fileId = wowCache.addFile(FILE_NAME);
      wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();

      // Install a dirty writeCachePages entry but KEEP the reader reference, so the page stays
      // at readers=1/writers=1 and addOnlyWriters never fires for it.
      final var heldPointer = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
      long exclusiveStamp = heldPointer.acquireExclusiveLock();
      try {
        var buffer = heldPointer.getBuffer();
        assert buffer != null;
        buffer.put(DurablePage.NEXT_FREE_POSITION, (byte) 0x5A);
      } finally {
        heldPointer.releaseExclusiveLock(exclusiveStamp);
      }
      wowCache.store(fileId, 0L, heldPointer);

      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("a page that still has a reader must not be counted as exclusive-write")
          .isZero();
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("a page that still has a reader must not be present in the exclusive-write set")
          .isZero();

      try {
        // The purge walks this dirty entry and must find nothing to remove from the set.
        wowCache.deleteFile(fileId);

        assertThat(wowCache.getExclusiveWriteCachePagesSize())
            .as("purging a reader-held dirty page must leave the counter at zero, never negative"
                + " (a negative counter permanently disables exclusive-write back-pressure)")
            .isZero();
        assertThat(wowCache.getExclusiveWritePagesCountForTest())
            .as("purging a reader-held dirty page must leave the set empty")
            .isZero();
        assertThat(wowCache.getExclusiveWriteAccountingClampsForTest())
            .as("the non-negativity clamp must never engage on this deterministic path: engaging"
                + " means the purge tried to decrement for a key it had not removed, which is"
                + " the rejected removal-independent-decrement implementation")
            .isZero();
      } finally {
        // Release the reference the test deliberately held, on every path. In a try/finally
        // rather than trailing the assertions: a failing assertion above would otherwise skip
        // the release and leak the PageFrame, so the real failure would be followed by an
        // unrelated direct-memory leak report from the shutdown detector and the diagnosis
        // would start from the wrong symptom.
        heldPointer.decrementReadersReferrer();
      }
    } finally {
      wowCache.resumeBackgroundFlush();
    }
  }

  /**
   * The <b>post-loop sweep</b>: page keys that exist ONLY in {@code exclusiveWritePages}, with
   * no {@code writeCachePages} entry backing them, must still be cleaned up when their file is
   * purged.
   *
   * <p><b>Why such keys exist.</b> {@code flushExclusiveWriteCache} documents the two structures
   * as only eventually consistent, with {@code writeCachePages} as the source of truth; it even
   * has a dedicated no-progress break for the case where a key has no entry and the file is too
   * small to extend. That break is the source of the "no progress in flush cycle" warning this
   * track eliminates. The purge loop iterates {@code writeCachePages}, so it structurally cannot
   * see such a key — only the post-loop sweep can.
   *
   * <p><b>How the shape is built.</b> {@code addOnlyWriters} is the public {@code WritersListener}
   * callback the cache installs on every stored page; calling it directly publishes a key into
   * {@code exclusiveWritePages} and bumps the counter with no {@code writeCachePages} entry —
   * exactly the orphan shape, built through production API rather than reflection.
   *
   * <p><b>Two properties, both load-bearing.</b> The delete must clear the orphans (only the
   * sweep can), and the shrink must clear ONLY the keys at or above the truncate target. The
   * second is the regression guard for the range-bound requirement: a whole-file sweep would
   * also drop the three below-target keys, and on the real ordering that over-purge is what
   * drives the counter negative. Exact values are asserted here — unlike the CachePointer-backed
   * shrink test above — because these keys are synthetic: there is no read cache, no
   * {@code CachePointer} and no listener behind them, so the concurrent-eviction re-publication
   * that the production known-limitation comment describes cannot occur.
   */
  @Test
  public void purgeSweepsKeysPresentOnlyInTheExclusiveWritePageSet() throws IOException {
    wowCache.pauseBackgroundFlush();
    try {
      final var fileId = allocateAndOptionallyDirty(LEAK_TEST_PAGES, false);

      // Publish LEAK_TEST_PAGES orphan keys: in the set, absent from writeCachePages.
      for (int i = 0; i < LEAK_TEST_PAGES; i++) {
        wowCache.addOnlyWriters(fileId, i);
      }
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("non-zero precondition: every published orphan key must be counted")
          .isEqualTo(LEAK_TEST_PAGES);
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("non-zero precondition: every published orphan key must be in the set")
          .isEqualTo(LEAK_TEST_PAGES);

      // Range-bound check first: shrink to 3 pages must sweep keys 3..5 and keep keys 0..2.
      final int survivingPages = 3;
      assertThat(wowCache.shrinkFile(fileId, (long) survivingPages * pageSize))
          .as("a real truncate must return true")
          .isTrue();
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("the sweep must be range-bound: only keys at or above the truncate target are"
              + " dropped, so the three below-target keys stay counted")
          .isEqualTo(survivingPages);
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("the sweep must leave the below-target keys in the set")
          .isEqualTo(survivingPages);

      // Now delete the file: minPageIndex is 0, so the sweep must clear the remainder.
      wowCache.deleteFile(fileId);
      assertThat(wowCache.getExclusiveWriteCachePagesSize())
          .as("deleting the file must sweep every remaining orphan key from the counter")
          .isZero();
      assertThat(wowCache.getExclusiveWritePagesCountForTest())
          .as("deleting the file must sweep every remaining orphan key from the set")
          .isZero();
    } finally {
      wowCache.resumeBackgroundFlush();
    }
  }
}
