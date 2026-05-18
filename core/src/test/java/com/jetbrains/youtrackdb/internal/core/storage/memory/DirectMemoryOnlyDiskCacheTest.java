package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link DirectMemoryOnlyDiskCache}.
 *
 * <p>{@link DirectMemoryOnlyDiskCache} has a package-private constructor, so these tests live in
 * the same package ({@code com.jetbrains.youtrackdb.internal.core.storage.memory}).
 *
 * <p>The page size used across tests is 4 KiB — large enough to hold real page content but
 * small enough to keep direct-memory pressure minimal during the test run.
 */
public class DirectMemoryOnlyDiskCacheTest {

  private static final int PAGE_SIZE = 4 * 1024;
  private static final int STORAGE_ID = 1;
  private static final String STORAGE_NAME = "testStorage";

  private DirectMemoryOnlyDiskCache cache;

  @Before
  public void setUp() {
    cache = new DirectMemoryOnlyDiskCache(PAGE_SIZE, STORAGE_ID, STORAGE_NAME);
  }

  @After
  public void tearDown() {
    // delete() clears all MemoryFile instances and releases their direct-memory pages
    cache.delete();
  }

  // ---------------------------------------------------------------------------
  // Identity / metadata
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getStorageName()} returns the name supplied to the constructor.
   */
  @Test
  public void testGetStorageName() {
    assertEquals(STORAGE_NAME, cache.getStorageName());
  }

  /**
   * Verifies that {@code getRootDirectory()} always returns {@code null} — the in-memory cache
   * has no filesystem presence.
   */
  @Test
  public void testGetRootDirectoryIsNull() {
    assertNull(cache.getRootDirectory());
  }

  /**
   * Verifies that {@code getId()} returns the storage-ID supplied to the constructor.
   */
  @Test
  public void testGetId() {
    assertEquals(STORAGE_ID, cache.getId());
  }

  /**
   * Verifies that {@code pageSize()} returns the page size supplied to the constructor.
   */
  @Test
  public void testPageSize() {
    assertEquals(PAGE_SIZE, cache.pageSize());
  }

  // ---------------------------------------------------------------------------
  // Lifecycle no-ops
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code create()} and {@code open()} complete without throwing.
   *
   * <p>Both are no-ops in the in-memory implementation; exercising them provides line coverage.
   */
  @Test
  public void testCreateAndOpenAreNoOps() {
    cache.create();
    cache.open();
    // No exception expected — these are intentional no-ops.
  }

  /**
   * Verifies that {@code restoreModeOn()} and {@code restoreModeOff()} complete without throwing.
   */
  @Test
  public void testRestoreModeNoOps() {
    cache.restoreModeOn();
    cache.restoreModeOff();
  }

  /**
   * Verifies that {@code addBackgroundExceptionListener()} and
   * {@code removeBackgroundExceptionListener()} complete without throwing.
   */
  @Test
  public void testBackgroundExceptionListenerNoOps() {
    cache.addBackgroundExceptionListener(e -> {
    });
    cache.removeBackgroundExceptionListener(e -> {
    });
  }

  /**
   * Verifies that {@code checkCacheOverflow()} completes without throwing.
   */
  @Test
  public void testCheckCacheOverflowNoOp() {
    cache.checkCacheOverflow();
  }

  /**
   * Verifies that {@code flush(fileId)}, {@code close(fileId, flush)},
   * {@code flush()}, {@code syncDataFiles()}, {@code flushTillSegment()},
   * {@code changeMaximumAmountOfMemory()}, and {@code recordOptimisticAccess()} are
   * no-ops that complete without throwing.
   */
  @Test
  public void testFlushAndCloseNoOps() {
    var fileId = cache.addFile("noopFile.cf");

    cache.flush(fileId);
    cache.close(fileId, true);
    cache.flush();
    cache.syncDataFiles(0L);
    cache.flushTillSegment(0L);
    cache.changeMaximumAmountOfMemory(Long.MAX_VALUE);
    cache.recordOptimisticAccess(fileId, 0L);
  }

  // ---------------------------------------------------------------------------
  // File management — addFile / exists / fileIdByName / fileNameById
  // ---------------------------------------------------------------------------

  /**
   * Verifies that adding a file and then querying it by name returns a positive file-ID.
   */
  @Test
  public void testAddFileAndLookupByName() {
    var externalId = cache.addFile("data.cf");
    assertTrue("External file ID must be positive", externalId > 0);
    // fileIdByName returns the internal (lower 32-bit) file ID, not the composed external ID.
    var internalId = cache.internalFileId(externalId);
    assertEquals(internalId, cache.fileIdByName("data.cf"));
  }

  /**
   * Verifies that {@code fileIdByName()} returns -1 for a file name that was never added.
   */
  @Test
  public void testFileIdByNameReturnsMinus1ForUnknownFile() {
    assertEquals(-1L, cache.fileIdByName("nonexistent.cf"));
  }

  /**
   * Verifies that adding a file with a name that already exists throws {@link StorageException}.
   */
  @Test(expected = StorageException.class)
  public void testAddFileDuplicateNameThrows() {
    cache.addFile("dup.cf");
    cache.addFile("dup.cf"); // second add must throw
  }

  /**
   * Verifies that adding a file with a pre-assigned external file ID works and the ID is honoured.
   */
  @Test
  public void testAddFileWithExplicitId() {
    // Book an ID first, then register the file under that ID.
    var bookedId = cache.bookFileId("explicit.cf");
    var registeredId = cache.addFile("explicit.cf", bookedId);
    assertEquals("Returned file ID must match the booked ID", bookedId, registeredId);
    assertTrue(cache.exists("explicit.cf"));
  }

  /**
   * Verifies that adding a file with an explicit ID that is already in use throws
   * {@link StorageException}.
   */
  @Test(expected = StorageException.class)
  public void testAddFileWithDuplicateExplicitIdThrows() {
    var firstId = cache.addFile("first.cf");
    // Attempt to add a second file using the same internal (extracted) file ID.
    cache.addFile("second.cf", firstId);
  }

  /**
   * Verifies that adding a file with an explicit ID when the name already exists throws
   * {@link StorageException}.
   */
  @Test(expected = StorageException.class)
  public void testAddFileWithDuplicateNameAndExplicitIdThrows() {
    cache.addFile("named.cf");
    // Use a different internal ID so the ID check passes but the name check fires.
    var otherId = AbstractWriteCache.composeFileId(STORAGE_ID, 99);
    cache.addFile("named.cf", otherId);
  }

  /**
   * Verifies that {@code exists(name)} returns {@code false} for an unknown file name and
   * {@code true} after the file is added.
   */
  @Test
  public void testExistsByName() {
    assertFalse(cache.exists("missing.cf"));
    cache.addFile("missing.cf");
    assertTrue(cache.exists("missing.cf"));
  }

  /**
   * Verifies that {@code exists(fileId)} returns {@code false} for an unknown file ID and
   * {@code true} after the file is added.
   */
  @Test
  public void testExistsByFileId() {
    // Compose an ID for a file that hasn't been added yet.
    var unknownId = AbstractWriteCache.composeFileId(STORAGE_ID, 999);
    assertFalse(cache.exists(unknownId));

    var fileId = cache.addFile("exists.cf");
    assertTrue(cache.exists(fileId));
  }

  /**
   * Verifies that {@code fileNameById()} returns the correct name for a known file and
   * {@code null} for an unknown ID.
   *
   * <p>{@code fileNameById()} uses the internal (lower 32-bit) ID to look up the name, so the
   * external composed ID is passed as-is and the internal extraction happens inside the method.
   */
  @Test
  public void testFileNameById() {
    var fileId = cache.addFile("named.cf");
    assertEquals("named.cf", cache.fileNameById(fileId));
    // An unknown internal ID returns null.
    assertNull(cache.fileNameById(AbstractWriteCache.composeFileId(STORAGE_ID, 9999)));
  }

  /**
   * Verifies that {@code nativeFileNameById()} delegates to {@code fileNameById()} and returns the
   * same result.
   */
  @Test
  public void testNativeFileNameById() {
    var fileId = cache.addFile("native.cf");
    assertEquals("native.cf", cache.nativeFileNameById(fileId));
  }

  /**
   * Verifies that {@code loadFile()} returns the external file ID for a file that exists.
   */
  @Test
  public void testLoadFileExistingFile() {
    var fileId = cache.addFile("loadable.cf");
    assertEquals(fileId, cache.loadFile("loadable.cf"));
  }

  /**
   * Verifies that {@code loadFile()} throws {@link StorageException} for a file that does not
   * exist.
   */
  @Test(expected = StorageException.class)
  public void testLoadFileNotExistingThrows() {
    cache.loadFile("ghost.cf");
  }

  /**
   * Verifies that {@code bookFileId()} returns a unique external ID on each call.
   */
  @Test
  public void testBookFileIdReturnsUniqueIds() {
    var id1 = cache.bookFileId("a.cf");
    var id2 = cache.bookFileId("b.cf");
    assertNotEquals("Each bookFileId call must return a distinct ID", id1, id2);
  }

  /**
   * Verifies that {@code internalFileId()} strips the storage-ID component from the external ID.
   */
  @Test
  public void testInternalFileId() {
    var fileId = cache.addFile("internal.cf");
    var internalId = cache.internalFileId(fileId);
    assertTrue("Internal file ID must be positive", internalId > 0);
    // Round-trip: externalFileId(internalFileId(extId)) == extId
    assertEquals(fileId, cache.externalFileId(internalId));
  }

  /**
   * Verifies that {@code files()} returns a map that contains all files added to the cache.
   */
  @Test
  public void testFilesMapContainsAddedFiles() {
    var id1 = cache.addFile("f1.cf");
    var id2 = cache.addFile("f2.cf");

    var filesMap = cache.files();
    assertEquals(2, filesMap.size());
    assertEquals(id1, (long) filesMap.get("f1.cf"));
    assertEquals(id2, (long) filesMap.get("f2.cf"));
  }

  // ---------------------------------------------------------------------------
  // Page operations
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the total {@code loadOrAdd()} primitive installs a fresh page in
   * {@link MemoryFile} and returns a non-null {@link CachePointer} that the caller can
   * release via {@code decrementReadersReferrer()}. This is the in-memory engine's
   * allocator path on the new write-side API. The test pins three observable contract
   * facets: (a) the install advances the per-file filled-up-to horizon to 1 page, (b)
   * the returned pointer's {@code (fileId, pageIndex)} coordinates match the requested
   * target, and (c) the publication-time readers-referrer bump is exactly-once — a
   * second decrement after the balancing one fails fast, proving the bump is not
   * silently doubled.
   */
  @Test
  public void testLoadOrAddInstallsAndReleases() {
    var fileId = cache.addFile("pages.cf");
    var pointer = cache.loadOrAdd(fileId, 0, false);

    assertNotNull("loadOrAdd must return a non-null pointer", pointer);
    // (a) Install observable: the per-file horizon advanced by exactly one page.
    assertEquals(
        "loadOrAdd must install the page (filledUpTo bumps to 1)",
        1L,
        cache.getFilledUpTo(fileId));
    // (b) Pointer coordinates match the request. The pointer carries the internal
    // file id (extracted via externalFileId) and the requested page index.
    assertEquals(
        "pointer must carry the requested fileId",
        fileId,
        cache.externalFileId((int) pointer.getFileId()));
    assertEquals(
        "pointer must carry the requested pageIndex", 0, pointer.getPageIndex());
    // (c) Exactly-once referrer bump: balance it, then a second decrement must fail.
    // Core tests run with assertions enabled (the module argLine adds -ea), so the
    // over-decrement trips CachePointer.decrementReadersReferrer's "assert readers >= 0"
    // before the IllegalStateException retry path; AssertionError is the expected
    // exception under -ea. The assertion still proves the bump is not silently doubled.
    pointer.decrementReadersReferrer();
    assertThrows(
        "second decrement must fail — the publication-time bump is exactly-once",
        AssertionError.class,
        pointer::decrementReadersReferrer);
  }

  /**
   * Verifies that {@code getFilledUpTo()} returns 0 for a file with no pages and increments
   * after each page is installed via {@code loadOrAdd()}.
   */
  @Test
  public void testGetFilledUpTo() {
    var fileId = cache.addFile("filled.cf");
    assertEquals(0L, cache.getFilledUpTo(fileId));

    var pointer1 = cache.loadOrAdd(fileId, 0, false);
    pointer1.decrementReadersReferrer();
    assertEquals(1L, cache.getFilledUpTo(fileId));

    var pointer2 = cache.loadOrAdd(fileId, 1, false);
    pointer2.decrementReadersReferrer();
    assertEquals(2L, cache.getFilledUpTo(fileId));
  }

  /**
   * Verifies that {@code loadOrAddForWrite()} returns {@code null} when the page has not been
   * installed (the in-memory engine's deliberate read-cache divergence: read-cache wrappers
   * keep null-on-miss semantics while the {@code loadOrAdd} write-cache primitive is total),
   * and returns the page entry with the exclusive lock after the page is installed. The test
   * exercises the lock contract under {@code -ea}: it explicitly calls
   * {@code releaseExclusiveLock()} before {@code releaseFromWrite}, which trips
   * {@code CacheEntryImpl.releaseExclusiveLock}'s {@code assert stamp != 0} if
   * {@code loadOrAddForWrite} ever stops acquiring the exclusive lock — a one-line
   * smoke gate that costs nothing under {@code -ea} but pins the distinguishing
   * contract that separates {@code loadOrAddForWrite} from {@code loadForRead}.
   */
  @Test
  public void testLoadOrAddForWriteMissAndHit() {
    var fileId = cache.addFile("rw.cf");

    // Miss — page 0 not yet installed. loadOrAddForWrite is null-on-miss on this engine
    // (the documented divergence from the total WriteCache.loadOrAdd primitive below).
    assertNull(cache.loadOrAddForWrite(fileId, 0, cache, false, null));

    // Install the page via the total write-side primitive — proves the divergence
    // explicitly: the same fileId + pageIndex returns non-null here.
    var allocated = cache.loadOrAdd(fileId, 0, false);
    assertNotNull("loadOrAdd is total and must return a non-null pointer", allocated);
    allocated.decrementReadersReferrer();

    // Hit — page 0 now exists and loadOrAddForWrite must return it with the exclusive lock.
    var loaded = cache.loadOrAddForWrite(fileId, 0, cache, false, null);
    assertNotNull("loadOrAddForWrite must find the installed page", loaded);
    // Exercise the exclusive-lock contract under -ea: an explicit releaseExclusiveLock()
    // here trips CacheEntryImpl's "assert stamp != 0" if loadOrAddForWrite ever stops
    // acquiring the lock. After the explicit release, balance the cache bookkeeping via
    // releaseFromRead (releaseFromWrite would attempt a second releaseExclusiveLock and
    // trip the same assert on the now-zero stamp).
    loaded.releaseExclusiveLock();
    cache.releaseFromRead(loaded);
  }

  /**
   * Verifies that {@code loadForRead()} returns {@code null} when the page has not been
   * installed, and returns the entry (without exclusive lock) after installation.
   */
  @Test
  public void testLoadForReadMissAndHit() {
    var fileId = cache.addFile("ro.cf");

    assertNull(cache.loadForRead(fileId, 0, cache, false));

    var allocated = cache.loadOrAdd(fileId, 0, false);
    allocated.decrementReadersReferrer();

    var loaded = cache.loadForRead(fileId, 0, cache, false);
    assertNotNull("loadForRead must find the installed page", loaded);
    cache.releaseFromRead(loaded);
  }

  /**
   * Verifies that {@code silentLoadForRead()} behaves identically to {@code loadForRead()} —
   * returns {@code null} on a miss and the entry on a hit after page installation.
   */
  @Test
  public void testSilentLoadForRead() {
    var fileId = cache.addFile("silent.cf");

    assertNull(cache.silentLoadForRead(fileId, 0, cache, false));

    var allocated = cache.loadOrAdd(fileId, 0, false);
    allocated.decrementReadersReferrer();

    var loaded = cache.silentLoadForRead(fileId, 0, cache, false);
    assertNotNull(loaded);
    cache.releaseFromRead(loaded);
  }

  // ---------------------------------------------------------------------------
  // File operations — rename / truncate / delete
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code renameFile()} updates both the name-to-ID and ID-to-name maps so that
   * the file is findable under its new name but not its old name.
   */
  @Test
  public void testRenameFile() {
    var fileId = cache.addFile("old.cf");
    cache.renameFile(fileId, "new.cf");

    assertTrue("File must exist under new name", cache.exists("new.cf"));
    assertFalse("File must not exist under old name", cache.exists("old.cf"));
    // fileIdByName returns the internal (lower 32-bit) ID.
    var internalId = cache.internalFileId(fileId);
    assertEquals(internalId, cache.fileIdByName("new.cf"));
  }

  /**
   * Verifies that {@code renameFile()} is a no-op when the file ID is unknown — it must not throw
   * and the maps must remain unchanged.
   */
  @Test
  public void testRenameNonExistentFileIsNoOp() {
    var unknownId = AbstractWriteCache.composeFileId(STORAGE_ID, 9999);
    cache.renameFile(unknownId, "irrelevant.cf"); // must not throw
    assertFalse(cache.exists("irrelevant.cf"));
  }

  /**
   * Verifies that {@code truncateFile(fileId)} clears all pages from the file, so
   * {@code getFilledUpTo()} returns 0 after the truncation.
   */
  @Test
  public void testTruncateFile() {
    var fileId = cache.addFile("trunc.cf");
    var pointer = cache.loadOrAdd(fileId, 0, false);
    pointer.decrementReadersReferrer();
    assertEquals(1L, cache.getFilledUpTo(fileId));

    cache.truncateFile(fileId);
    assertEquals(0L, cache.getFilledUpTo(fileId));
  }

  /**
   * Verifies that {@code truncateFile(fileId, writeCache)} delegates to {@code truncateFile(fileId)}
   * — it must behave identically.
   */
  @Test
  public void testTruncateFileWithWriteCache() {
    var fileId = cache.addFile("trunc2.cf");
    var pointer = cache.loadOrAdd(fileId, 0, false);
    pointer.decrementReadersReferrer();

    cache.truncateFile(fileId, cache);
    assertEquals(0L, cache.getFilledUpTo(fileId));
  }

  /**
   * Verifies that {@code shrinkFile(fileId, targetBytes)} is a no-op for the in-memory engine —
   * the on-disk physical state the recovery-time orphan-truncation pass repairs does not exist
   * for {@link DirectMemoryOnlyDiskCache}, so the WriteCache half of the layered shrink must
   * leave the file unchanged regardless of the supplied target. The orchestrator calls this
   * polymorphically through the WriteCache interface, so a regression to UOE or to a real
   * truncate would break the in-memory startup path.
   */
  @Test
  public void testShrinkFileWriteCacheVariantIsNoOp() {
    var fileId = cache.addFile("shrink.cf");
    for (int i = 0; i < 4; i++) {
      cache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }
    assertEquals(4L, cache.getFilledUpTo(fileId));

    // Target below current size — must NOT shrink (in-memory engine has no on-disk state)
    cache.shrinkFile(fileId, PAGE_SIZE);
    assertEquals(
        "shrinkFile(WriteCache variant) must be a no-op on the in-memory engine",
        4L, cache.getFilledUpTo(fileId));

    // Target above current size — also a no-op, no growth
    cache.shrinkFile(fileId, 16L * PAGE_SIZE);
    assertEquals(4L, cache.getFilledUpTo(fileId));

    // Zero target — still a no-op
    cache.shrinkFile(fileId, 0L);
    assertEquals(4L, cache.getFilledUpTo(fileId));
  }

  /**
   * Verifies that {@code shrinkFile(fileId, targetBytes, writeCache)} — the {@code ReadCache}
   * orchestrator variant — is a no-op for the in-memory engine. The polymorphic dispatch in the
   * recovery pass routes through this entry point; it must behave identically to the
   * {@link DirectMemoryOnlyDiskCache#shrinkFile(long, long)} sibling regardless of the
   * supplied {@code WriteCache}.
   */
  @Test
  public void testShrinkFileReadCacheVariantIsNoOp() throws java.io.IOException {
    var fileId = cache.addFile("shrink2.cf");
    for (int i = 0; i < 3; i++) {
      cache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }
    assertEquals(3L, cache.getFilledUpTo(fileId));

    cache.shrinkFile(fileId, PAGE_SIZE, cache);
    assertEquals(
        "shrinkFile(ReadCache variant) must be a no-op on the in-memory engine",
        3L, cache.getFilledUpTo(fileId));
  }

  /**
   * Verifies that {@code deleteFile(fileId)} removes the file so that {@code exists()} returns
   * {@code false} afterwards.
   */
  @Test
  public void testDeleteFile() {
    var fileId = cache.addFile("del.cf");
    assertTrue(cache.exists(fileId));
    cache.deleteFile(fileId);
    assertFalse("File must not exist after deletion", cache.exists(fileId));
  }

  /**
   * Verifies that {@code deleteFile(fileId)} is a no-op when the file ID is unknown —
   * it must not throw.
   */
  @Test
  public void testDeleteNonExistentFileIsNoOp() {
    var unknownId = AbstractWriteCache.composeFileId(STORAGE_ID, 9999);
    cache.deleteFile(unknownId); // must not throw
  }

  /**
   * Verifies that {@code deleteFile(fileId, writeCache)} delegates to {@code deleteFile(fileId)}.
   */
  @Test
  public void testDeleteFileWithWriteCache() {
    var fileId = cache.addFile("del2.cf");
    cache.deleteFile(fileId, cache);
    assertFalse(cache.exists(fileId));
  }

  /**
   * Verifies that {@code closeFile(fileId, flush, writeCache)} completes without throwing —
   * close is a no-op for the in-memory implementation.
   */
  @Test
  public void testCloseFileNoOp() {
    var fileId = cache.addFile("close.cf");
    cache.closeFile(fileId, true, cache);
  }

  // ---------------------------------------------------------------------------
  // Bulk operations — close / clear / delete / deleteStorage / closeStorage
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code close()} returns an empty {@code long[]} array.
   */
  @Test
  public void testCloseReturnsEmptyArray() {
    cache.addFile("a.cf");
    var result = cache.close();
    assertArrayEquals(new long[0], result);
  }

  /**
   * Verifies that {@code delete()} removes all files and returns an empty array.
   */
  @Test
  public void testDeleteRemovesAllFiles() {
    cache.addFile("x.cf");
    cache.addFile("y.cf");
    var result = cache.delete();
    assertArrayEquals(new long[0], result);
    assertFalse(cache.exists("x.cf"));
    assertFalse(cache.exists("y.cf"));
  }

  /**
   * Verifies that {@code clear()} delegates to {@code delete()} and removes all files.
   */
  @Test
  public void testClearDelegatesToDelete() {
    cache.addFile("clear.cf");
    cache.clear();
    assertFalse(cache.exists("clear.cf"));
  }

  /**
   * Verifies that {@code deleteStorage(writeCache)} delegates to {@code delete()}.
   */
  @Test
  public void testDeleteStorage() {
    cache.addFile("ds.cf");
    cache.deleteStorage(cache);
    assertFalse(cache.exists("ds.cf"));
  }

  /**
   * Verifies that {@code closeStorage(writeCache)} completes without throwing.
   */
  @Test
  public void testCloseStorageNoOp() {
    cache.addFile("cs.cf");
    cache.closeStorage(cache);
  }

  // ---------------------------------------------------------------------------
  // Memory metrics
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getUsedMemory()} returns 0 when no pages have been installed, and
   * that it grows by exactly {@code pageSize} after each page installation via the total
   * {@code loadOrAdd()} primitive.
   */
  @Test
  public void testGetUsedMemory() {
    var fileId = cache.addFile("mem.cf");
    assertEquals(0L, cache.getUsedMemory());

    var pointer = cache.loadOrAdd(fileId, 0, false);
    pointer.decrementReadersReferrer();
    assertEquals((long) PAGE_SIZE, cache.getUsedMemory());
  }

  /**
   * Verifies that {@code checkLowDiskSpace()} always returns {@code true} — in-memory storage
   * is never "low on disk".
   */
  @Test
  public void testCheckLowDiskSpace() {
    assertTrue(cache.checkLowDiskSpace());
  }

  // ---------------------------------------------------------------------------
  // Optimistic-read and page-verification stubs
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getPageFrameOptimistic()} always returns {@code null} — the in-memory
   * cache does not support lock-free optimistic reads.
   */
  @Test
  public void testGetPageFrameOptimisticAlwaysNull() {
    var fileId = cache.addFile("opt.cf");
    assertNull(cache.getPageFrameOptimistic(fileId, 0));
  }

  /**
   * Verifies that {@code checkStoredPages()} returns an empty array — no pages need
   * checksum/verification in the in-memory implementation.
   */
  @Test
  public void testCheckStoredPagesReturnsEmpty() {
    var errors = cache.checkStoredPages(msg -> {
    });
    assertNotNull(errors);
    assertEquals(0, errors.length);
  }

  // ---------------------------------------------------------------------------
  // Unsupported-operation stubs
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code replaceFileId()} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testReplaceFileIdThrows() {
    cache.replaceFileId(1L, 2L);
  }

  /**
   * Verifies that {@code store()} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testStoreThrows() {
    cache.store(1L, 0L, null);
  }

  /**
   * Verifies that {@code getMinimalNotFlushedSegment()} throws
   * {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testGetMinimalNotFlushedSegmentThrows() {
    cache.getMinimalNotFlushedSegment();
  }

  /**
   * Verifies that {@code load()} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testLoadThrows() {
    cache.load(1L, 0L, null, false);
  }

  // ---------------------------------------------------------------------------
  // Exclusive-write-cache / WAL stubs
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getExclusiveWriteCachePagesSize()} always returns 0.
   */
  @Test
  public void testGetExclusiveWriteCachePagesSize() {
    assertEquals(0L, cache.getExclusiveWriteCachePagesSize());
  }

  /**
   * Verifies that {@code updateDirtyPagesTable()} completes without throwing — it is a no-op
   * in the in-memory implementation.
   */
  @Test
  public void testUpdateDirtyPagesTableNoOp() {
    cache.updateDirtyPagesTable(null, null);
  }

  // ---------------------------------------------------------------------------
  // fileIdsAreEqual / restoreFileById
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code fileIdsAreEqual()} compares the internal (lower 32-bit) file ID only,
   * ignoring the storage-ID component in the upper 32 bits.
   */
  @Test
  public void testFileIdsAreEqual() {
    var fileId = cache.addFile("eq.cf");
    assertTrue("Same file ID must be equal to itself", cache.fileIdsAreEqual(fileId, fileId));

    // Two different external IDs derived from different internal IDs must not be equal.
    var fileId2 = cache.addFile("eq2.cf");
    assertFalse("Different file IDs must not be equal", cache.fileIdsAreEqual(fileId, fileId2));
  }

  /**
   * Verifies that {@code restoreFileById()} always returns {@code null} — the in-memory cache
   * does not persist files and therefore cannot restore them.
   */
  @Test
  public void testRestoreFileByIdAlwaysNull() {
    assertNull(cache.restoreFileById(1L));
    assertNull(cache.restoreFileById(AbstractWriteCache.composeFileId(STORAGE_ID, 42)));
  }

  // ---------------------------------------------------------------------------
  // addFile(name) and addFile(name, fileId) convenience overloads
  // ---------------------------------------------------------------------------

  /**
   * Verifies that the single-argument {@code addFile(name)} overload delegates correctly and
   * that the file is subsequently findable.
   */
  @Test
  public void testAddFileSingleArgOverload() {
    var fileId = cache.addFile("single.cf");
    assertTrue(cache.exists("single.cf"));
    // fileIdByName returns the internal (lower 32-bit) ID.
    var internalId = cache.internalFileId(fileId);
    assertEquals(internalId, cache.fileIdByName("single.cf"));
  }

  /**
   * Verifies that the two-argument {@code addFile(name, fileId)} overload (without a WriteCache
   * parameter) delegates correctly.
   */
  @Test
  public void testAddFileTwoArgOverload() {
    var bookedId = cache.bookFileId("two.cf");
    var registeredId = cache.addFile("two.cf", bookedId);
    assertEquals(bookedId, registeredId);
    assertTrue(cache.exists("two.cf"));
  }
}
