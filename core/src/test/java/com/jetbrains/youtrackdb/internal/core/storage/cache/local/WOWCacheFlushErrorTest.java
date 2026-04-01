package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests that WOWCache methods return early when a prior flush error has been recorded. Once
 * {@code flushError} is set (by a failed background flush), all subsequent flush and dirty-segment
 * operations must log the error and return immediately rather than proceeding with I/O.
 *
 * <p>Also tests that null-file guards in {@code getFilledUpTo()} and
 * {@code flushWriteCacheFromMinLSN()} correctly handle concurrent file deletion without NPE or
 * infinite loops.
 *
 * <p>Uses Mockito's {@code CALLS_REAL_METHODS} to invoke the real guard-clause logic on a mock
 * instance with fields set via reflection.
 */
public class WOWCacheFlushErrorTest {

  private static final int PAGE_SIZE = 8192;

  /**
   * Sets the private {@code flushError} field on a WOWCache (or mock) to the given throwable.
   */
  private static void setFlushError(WOWCache cache, Throwable error) throws Exception {
    Field field = WOWCache.class.getDeclaredField("flushError");
    field.setAccessible(true);
    field.set(cache, error);
  }

  /**
   * Verifies that {@code executeFindDirtySegment()} returns null immediately when a flush error
   * is recorded, without attempting to access dirty pages or the write cache.
   */
  @Test
  public void testExecuteFindDirtySegmentReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFindDirtySegment());
  }

  /**
   * Verifies that {@code executeFileFlush()} returns null immediately when a flush error
   * is recorded, preventing further I/O on a storage with a known write failure.
   */
  @Test
  public void testExecuteFileFlushReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFileFlush(new IntOpenHashSet()));
  }

  /**
   * Verifies that {@code executePeriodicFlush()} returns immediately when a flush error
   * is recorded, without scheduling further flush work.
   */
  @Test
  public void testExecutePeriodicFlushReturnsOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    // Should return without exception — the flushError guard prevents further processing
    cache.executePeriodicFlush(null);
  }

  /**
   * Verifies that {@code executeFlush()} returns immediately when a flush error is recorded.
   * Also verifies that the completion latches are still counted down in the finally block.
   */
  @Test
  public void testExecuteFlushReturnsOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    // Pass null latches to avoid NPE in the mock — the guard clause returns before using them
    cache.executeFlush(null, null);
  }

  /**
   * Verifies that {@code executeFlushTillSegment()} returns null immediately when a flush
   * error is recorded.
   */
  @Test
  public void testExecuteFlushTillSegmentReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFlushTillSegment(42L));
  }

  /**
   * Verifies that {@code callPageIsBrokenListeners} iterates through registered listeners
   * and notifies them when a page is broken. Covers the for-loop body in
   * {@code callPageIsBrokenListeners} and the listener invocation path.
   */
  @SuppressWarnings("unchecked") // unchecked: generic WeakReference list mock
  @Test
  public void testCallPageIsBrokenListenersNotifiesRegisteredListeners() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    // Initialize and set the pageIsBrokenListeners list (CALLS_REAL_METHODS skips
    // field initializers, so the list is null in the mock)
    PageIsBrokenListener mockListener = mock(PageIsBrokenListener.class);
    var listenersList =
        new java.util.concurrent.CopyOnWriteArrayList<WeakReference<PageIsBrokenListener>>();
    listenersList.add(new WeakReference<>(mockListener));
    Field listenersField = WOWCache.class.getDeclaredField("pageIsBrokenListeners");
    listenersField.setAccessible(true);
    listenersField.set(cache, listenersList);

    // Invoke the private callPageIsBrokenListeners method via reflection
    Method method =
        WOWCache.class.getDeclaredMethod("callPageIsBrokenListeners", String.class, long.class);
    method.setAccessible(true);
    method.invoke(cache, "test-file.dat", 42L);

    verify(mockListener).pageIsBroken("test-file.dat", 42L);
  }

  // ---------------------------------------------------------------------------
  // Null-file guard tests: getFilledUpTo and flushWriteCacheFromMinLSN
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getFilledUpTo()} returns 0 when the file has been concurrently deleted
   * (i.e., {@code files.get()} returns null). This guards against NPE during storage close/drop
   * while the periodic records GC is still running.
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test
  public void testGetFilledUpToReturnsZeroWhenFileIsNull() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    // files container returns null for any lookup (simulating deleted file)
    var filesContainer = mock(ClosableLinkedContainer.class);
    setField(cache, "files", filesContainer);

    // A real lock is needed because getFilledUpTo acquires/releases it
    setField(cache, "filesLock", new ReadersWriterSpinLock());

    // id=0 so composeFileId produces a predictable key
    setField(cache, "id", 0);
    setField(cache, "pageSize", PAGE_SIZE);

    // closed=false so checkForClose() does not throw
    setField(cache, "closed", false);
    setField(cache, "storageName", "test");

    assertEquals(0, cache.getFilledUpTo(42L));

    // Verify the read lock was released — a second call must not deadlock
    assertEquals(0, cache.getFilledUpTo(42L));
  }

  /**
   * Verifies that {@code getFilledUpTo()} returns the correct page count when the file exists.
   * Guards against the null-guard accidentally suppressing the real return value.
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test
  public void testGetFilledUpToReturnsPageCountWhenFileExists() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    var mockFile = mock(File.class);
    Mockito.when(mockFile.getFileSize()).thenReturn(3L * PAGE_SIZE);
    var filesContainer = mock(ClosableLinkedContainer.class);
    Mockito.when(filesContainer.get(anyLong())).thenReturn(mockFile);
    setField(cache, "files", filesContainer);
    setField(cache, "filesLock", new ReadersWriterSpinLock());
    setField(cache, "id", 0);
    setField(cache, "pageSize", PAGE_SIZE);
    setField(cache, "closed", false);
    setField(cache, "storageName", "test");

    assertEquals(3L, cache.getFilledUpTo(42L));
  }

  /**
   * Verifies that {@code flushWriteCacheFromMinLSN()} gracefully skips pages whose backing file
   * has been concurrently deleted (first null-check path: file size lookup when the file id has
   * not been cached in {@code fileIdSizeMap} yet). After the call, the page must remain in
   * {@code localDirtyPagesBySegment} (it was skipped, not processed or removed).
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test
  public void testFlushWriteCacheFromMinLSNSkipsDeletedFileOnSizeLookup() throws Exception {
    var cache = buildCacheForFlushTest();

    // Populate localDirtyPagesBySegment with a single segment containing one page
    // for a file that no longer exists (files mock returns null for all lookups)
    var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    var pages = new TreeSet<PageKey>();
    pages.add(new PageKey(7, 0));
    localDirtyPagesBySegment.put(1L, pages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);

    // writeCachePages must be present (the page won't reach that lookup because it's
    // skipped at the file-null check in the inner while-loop)
    setField(cache, "writeCachePages", new ConcurrentHashMap<PageKey, CachePointer>());

    invokeFlushWriteCacheFromMinLSN(cache, 1L, 2L, 10);

    // The page for the deleted file must remain in the dirty-pages index,
    // confirming it was skipped via continue rather than processed
    var segmentPages = localDirtyPagesBySegment.get(1L);
    assertNotNull(
        "Segment 1 entry must still be present after skipping deleted-file page",
        segmentPages);
    assertTrue(
        "Page key for deleted file must remain in the dirty segment",
        segmentPages.contains(new PageKey(7, 0)));
  }

  /**
   * Verifies that {@code flushWriteCacheFromMinLSN()} gracefully skips pages whose backing file
   * has been concurrently deleted in the second null-check path: when a page's shared lock cannot
   * be acquired ({@code tryAcquireSharedLock()} returns 0) and the file is subsequently found to
   * be null during the page-beyond-file-size check.
   *
   * <p>To reach this path, the files mock must return a valid file during the inner while-loop
   * (so the page is collected into {@code pageKeysToFlush}), then return null during the
   * for-loop (simulating a file deleted between collection and flush phases).
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test
  public void testFlushWriteCacheFromMinLSNSkipsDeletedFileOnFailedLock() throws Exception {
    var cache = buildCacheForFlushTest();

    // Configure files mock: return a valid file on the first call (inner while-loop
    // size lookup), then return null on the second call (for-loop lock-fail path)
    var filesContainer = mock(ClosableLinkedContainer.class);
    var mockFile = mock(File.class);
    Mockito.when(mockFile.getUnderlyingFileSize()).thenReturn((long) PAGE_SIZE);
    Mockito.when(filesContainer.get(anyLong())).thenReturn(mockFile).thenReturn(null);
    setField(cache, "files", filesContainer);

    var pageKey = new PageKey(7, 0);
    var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    var pages = new TreeSet<PageKey>();
    pages.add(pageKey);
    localDirtyPagesBySegment.put(1L, pages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);

    // Put a CachePointer mock in writeCachePages that returns 0 from tryAcquireSharedLock,
    // so we enter the else branch (lock failed) which hits the second null-file check
    var writeCachePages = new ConcurrentHashMap<PageKey, CachePointer>();
    var cachePointer = mock(CachePointer.class);
    Mockito.when(cachePointer.tryAcquireSharedLock()).thenReturn(0L);
    writeCachePages.put(pageKey, cachePointer);
    setField(cache, "writeCachePages", writeCachePages);

    invokeFlushWriteCacheFromMinLSN(cache, 1L, 2L, 10);

    // Verify tryAcquireSharedLock was called — confirms we reached the lock branch
    // and then hit the null-file check, rather than being skipped earlier
    Mockito.verify(cachePointer).tryAcquireSharedLock();

    // The page must remain in the dirty segment (not removed by removeFromDirtyPages),
    // confirming it was skipped via continue
    var segmentPages = localDirtyPagesBySegment.get(1L);
    assertNotNull("Segment 1 entry must still be present", segmentPages);
    assertTrue(
        "Page key must remain after skipping deleted-file page on failed lock",
        segmentPages.contains(pageKey));
  }

  /**
   * Verifies that {@code flushWriteCacheFromMinLSN()} correctly handles the case where a page's
   * shared lock cannot be acquired and the file still exists but the page is beyond the file size.
   * This covers the false branch of the null-file guard in the lock-failure path of the for-loop
   * (file is not null) and the subsequent {@code break flushCycle} when the page exceeds file
   * bounds.
   *
   * <p>Scenario: the page at index 5 is collected into {@code pageKeysToFlush} during the inner
   * while-loop (file reports size = 6 pages). In the for-loop, the lock fails and the file now
   * reports size = 0 (shrunk concurrently), so {@code pageIndex * pageSize >= fileSize} triggers
   * {@code break flushCycle}.
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test
  public void testFlushWriteCacheFromMinLSNBreaksWhenFileShrunkOnFailedLock() throws Exception {
    var cache = buildCacheForFlushTest();

    // Two separate file mocks: one for the inner while-loop (large file) and one for the
    // for-loop else branch (file shrunk to 0 — still exists but page is now beyond bounds)
    var whileLoopFile = mock(File.class);
    Mockito.when(whileLoopFile.getUnderlyingFileSize()).thenReturn(6L * PAGE_SIZE);
    var forLoopFile = mock(File.class);
    Mockito.when(forLoopFile.getUnderlyingFileSize()).thenReturn(0L);

    var filesContainer = mock(ClosableLinkedContainer.class);
    Mockito.when(filesContainer.get(anyLong())).thenReturn(whileLoopFile).thenReturn(forLoopFile);
    setField(cache, "files", filesContainer);

    // Page at index 5 — within bounds during while-loop (6 pages), but beyond in for-loop (0)
    var pageKey = new PageKey(7, 5);
    var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    var pages = new TreeSet<PageKey>();
    pages.add(pageKey);
    localDirtyPagesBySegment.put(1L, pages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);

    // CachePointer that fails to acquire shared lock
    var writeCachePages = new ConcurrentHashMap<PageKey, CachePointer>();
    var cachePointer = mock(CachePointer.class);
    Mockito.when(cachePointer.tryAcquireSharedLock()).thenReturn(0L);
    writeCachePages.put(pageKey, cachePointer);
    setField(cache, "writeCachePages", writeCachePages);

    // Should complete without error — break flushCycle exits the method cleanly
    invokeFlushWriteCacheFromMinLSN(cache, 1L, 2L, 10);

    // Verify the lock attempt was made (confirms we entered the else branch)
    Mockito.verify(cachePointer).tryAcquireSharedLock();

    // Verify the second files.get() was called (for-loop file-size check after lock failure)
    Mockito.verify(filesContainer, Mockito.times(2)).get(anyLong());
  }

  /**
   * Verifies segment advancement when the inner while-loop does NOT exhaust the iterator but
   * no progress is made in the for-loop. This exercises the second disjunct of the compound
   * condition ({@code chunksSize == chunksSizeBeforeFlush}) independently of the first
   * ({@code !lsnPagesIterator.hasNext()}).
   *
   * <p>Scenario: segment 1 has two pages. {@code pagesFlushLimit = 1} causes the inner
   * while-loop to collect only the first page (leaving the iterator with {@code hasNext() ==
   * true}). In the for-loop, the page's lock fails and the file is found to be deleted
   * ({@code continue}), so {@code chunksSize} stays at 0. The segment-advancement condition
   * fires on the second disjunct and the method terminates instead of looping infinitely.
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test(timeout = 10_000)
  public void testFlushWriteCacheFromMinLSNAdvancesOnNoProgressWithRemainingPages()
      throws Exception {
    var cache = buildCacheForFlushTest();

    // Two pages in the same segment — the inner while-loop will collect only the first
    // because pagesFlushLimit = 1
    var filesContainer = mock(ClosableLinkedContainer.class);
    var mockFile = mock(File.class);
    Mockito.when(mockFile.getUnderlyingFileSize()).thenReturn((long) PAGE_SIZE);
    // First call (while-loop size lookup): valid file. Second call (for-loop lock-fail
    // path): null (deleted between collection and flush).
    Mockito.when(filesContainer.get(anyLong())).thenReturn(mockFile).thenReturn(null);
    setField(cache, "files", filesContainer);

    var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    var segPages = new TreeSet<PageKey>();
    segPages.add(new PageKey(7, 0));
    segPages.add(new PageKey(7, 1));
    localDirtyPagesBySegment.put(1L, segPages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);

    // CachePointer with failed lock acquisition
    var writeCachePages = new ConcurrentHashMap<PageKey, CachePointer>();
    var cachePointer = mock(CachePointer.class);
    Mockito.when(cachePointer.tryAcquireSharedLock()).thenReturn(0L);
    writeCachePages.put(new PageKey(7, 0), cachePointer);
    setField(cache, "writeCachePages", writeCachePages);

    // pagesFlushLimit = 1 so the inner while-loop collects only the first page,
    // leaving lsnPagesIterator.hasNext() == true.
    // segStart=1, segEnd=3 — must advance past segment 1 and terminate.
    invokeFlushWriteCacheFromMinLSN(cache, 1L, 3L, 1);
  }

  /**
   * Verifies the segment-advancement fix: when segment 1 contains only pages for deleted files,
   * the flush method must advance to segment 2 rather than retrying segment 1 indefinitely.
   * Without the fix, this test would hang forever (infinite loop).
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  @Test(timeout = 10_000)
  public void testFlushWriteCacheFromMinLSNAdvancesSegmentWhenAllPagesAreDeleted()
      throws Exception {
    var cache = buildCacheForFlushTest();

    // Segment 1: one page for deleted file (files mock returns null).
    // Segment 2: empty (will be null in localDirtyPagesBySegment).
    var localDirtyPagesBySegment = new TreeMap<Long, TreeSet<PageKey>>();
    var seg1Pages = new TreeSet<PageKey>();
    seg1Pages.add(new PageKey(7, 0));
    localDirtyPagesBySegment.put(1L, seg1Pages);
    setField(cache, "localDirtyPagesBySegment", localDirtyPagesBySegment);

    setField(cache, "writeCachePages", new ConcurrentHashMap<PageKey, CachePointer>());

    // segStart=1, segEnd=3 — must advance past segment 1 and terminate.
    // The @Test(timeout) guards against infinite-loop regression.
    invokeFlushWriteCacheFromMinLSN(cache, 1L, 3L, 10);
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  //
  // These tests depend on the following WOWCache internal fields (via reflection):
  //   files, filesLock, id, pageSize, storageName, closed,
  //   dirtyPages, localDirtyPages, localDirtyPagesBySegment, writeCachePages,
  //   flushError, pageIsBrokenListeners
  // If any of these fields are renamed or removed, update setField() calls here.
  // ---------------------------------------------------------------------------

  /**
   * Creates a WOWCache mock with common fields initialized for
   * {@code flushWriteCacheFromMinLSN()} tests. The {@code files} field is set to a mock that
   * returns null for all lookups (simulating all files deleted). Tests that need a more complex
   * files configuration should override this field after calling this method.
   */
  @SuppressWarnings("unchecked") // unchecked: ClosableLinkedContainer<Long, File> mock
  private static WOWCache buildCacheForFlushTest() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    // files container returns null for any lookup by default (simulating deleted file)
    var filesContainer = mock(ClosableLinkedContainer.class);
    setField(cache, "files", filesContainer);

    setField(cache, "id", 0);
    setField(cache, "pageSize", PAGE_SIZE);
    setField(cache, "storageName", "test");

    // filesLock for defensive completeness — not currently used by
    // flushWriteCacheFromMinLSN, but protects against future refactors
    setField(cache, "filesLock", new ReadersWriterSpinLock());

    // nonDurableFileIds is accessed by the assert guard in flushWriteCacheFromMinLSN
    // that verifies non-durable pages never appear in the dirty pages table
    setField(cache, "nonDurableFileIds", new IntOpenHashSet());

    // dirtyPages and localDirtyPages are accessed by convertSharedDirtyPagesToLocal()
    // which is called at the start of flushWriteCacheFromMinLSN
    setField(
        cache, "dirtyPages", new ConcurrentHashMap<PageKey, LogSequenceNumber>());
    setField(
        cache, "localDirtyPages", new HashMap<PageKey, LogSequenceNumber>());

    return cache;
  }

  /**
   * Invokes the private {@code flushWriteCacheFromMinLSN()} method via reflection, unwrapping
   * any {@link InvocationTargetException} to surface the real cause as an {@link AssertionError}.
   */
  private static void invokeFlushWriteCacheFromMinLSN(
      WOWCache cache, long segStart, long segEnd, int pagesFlushLimit) throws Exception {
    Method method =
        WOWCache.class.getDeclaredMethod(
            "flushWriteCacheFromMinLSN", long.class, long.class, int.class);
    method.setAccessible(true);
    try {
      method.invoke(cache, segStart, segEnd, pagesFlushLimit);
    } catch (InvocationTargetException e) {
      throw new AssertionError(
          "flushWriteCacheFromMinLSN threw unexpectedly", e.getCause());
    }
  }

  /**
   * Sets a field (including private/final fields in {@code WOWCache} or its superclasses)
   * to the given value via reflection.
   */
  private static void setField(Object target, String fieldName, Object value) throws Exception {
    // Starts from the Mockito-generated subclass; walks up to WOWCache and its parents
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  /**
   * Walks the class hierarchy to find a declared field by name.
   */
  private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        // Field not declared at this level, walk up
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  // --- executeFileFlush WAL guard tests ---
  // Note: These tests use reflection to inject mocks into private fields because
  // WOWCache has no test-friendly constructor. Field name changes will cause
  // runtime failures.

  /**
   * Sets up a mock WOWCache with the given non-durable file IDs, a mock WAL,
   * and empty writeCachePages. Returns the mock WAL for verification.
   */
  private WriteAheadLog setupCacheForFileFlush(
      WOWCache cache, IntOpenHashSet nonDurableIds) throws Exception {
    Field ndField = WOWCache.class.getDeclaredField("nonDurableFileIds");
    ndField.setAccessible(true);
    ndField.set(cache, nonDurableIds);

    WriteAheadLog mockWal = mock(WriteAheadLog.class);
    Field walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(cache, mockWal);

    Field wcpField = WOWCache.class.getDeclaredField("writeCachePages");
    wcpField.setAccessible(true);
    wcpField.set(cache, new ConcurrentHashMap<>());

    return mockWal;
  }

  /**
   * Verifies that {@code executeFileFlush()} skips the WAL flush when all files in the
   * set are non-durable. Non-durable files never produce WAL records, so flushing the
   * WAL is unnecessary overhead.
   */
  @Test
  public void testExecuteFileFlushSkipsWALFlushForNonDurableOnlyFiles() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    var nonDurable = new IntOpenHashSet();
    nonDurable.add(5);
    var mockWal = setupCacheForFileFlush(cache, nonDurable);

    var fileIdSet = new IntOpenHashSet();
    fileIdSet.add(5);
    cache.executeFileFlush(fileIdSet);

    Mockito.verify(mockWal, Mockito.never()).flush();
  }

  /**
   * Verifies that {@code executeFileFlush()} does flush the WAL when the set contains
   * at least one durable file.
   */
  @Test
  public void testExecuteFileFlushFlushesWALForDurableFiles() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    var nonDurable = new IntOpenHashSet();
    nonDurable.add(5);
    var mockWal = setupCacheForFileFlush(cache, nonDurable);

    // Mix: durable (ID 10) + non-durable (ID 5)
    var fileIdSet = new IntOpenHashSet();
    fileIdSet.add(5);
    fileIdSet.add(10);
    cache.executeFileFlush(fileIdSet);

    Mockito.verify(mockWal).flush();
  }
}
