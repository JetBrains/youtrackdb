package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import org.junit.Test;

/**
 * Unit tests for {@link MemoryFile} covering paths not exercised by {@link MemoryFileClearTest}.
 *
 * <p>Tests run in the same package so the package-private {@code MemoryFile} constructor is
 * accessible without reflection.
 */
public class MemoryFileOpsTest {

  // ---------------------------------------------------------------------------
  // getUsedMemory
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code getUsedMemory()} returns 0 on a freshly created file.
   *
   * <p>{@code getUsedMemory()} returns the number of pages currently stored in the file's
   * content map. A new file has no pages, so the result must be 0.
   */
  @Test
  public void testGetUsedMemoryEmptyFile() {
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 1);
    assertEquals(0, file.getUsedMemory());
  }

  /**
   * Verifies that {@code getUsedMemory()} returns the number of pages after pages are added.
   *
   * <p>Each call to {@code addNewPage()} inserts one entry into the content map; the page count
   * must reflect the number of added pages.
   */
  @Test
  public void testGetUsedMemoryAfterAddingPages() {
    var readCache = mock(ReadCache.class);
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 2);

    try {
      // Add three pages and verify the count tracks each addition.
      for (int i = 1; i <= 3; i++) {
        file.addNewPage(readCache);
        assertEquals("Expected " + i + " page(s) in file", i, file.getUsedMemory());
      }
    } finally {
      // clear() calls decrementReferrer() for each entry, releasing direct memory correctly.
      // Run in finally so an assertion failure between pages does not leak the referrers
      // — under -Dyoutrackdb.memory.directMemory.trackMode=true (set in core/pom.xml) the
      // page tracker calls System.exit(1) at JVM shutdown if any non-zero referrer survives,
      // aborting the surefire JVM and masking the real failure as "Tests run: 0".
      file.clear();
    }
  }

  // ---------------------------------------------------------------------------
  // size — empty file and NoSuchElementException branch
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code size()} returns 0 for an empty file.
   *
   * <p>When the content map is empty, {@code size()} must return 0 without any exception,
   * exercising the {@code content.isEmpty()} early-return branch.
   */
  @Test
  public void testSizeOfEmptyFile() {
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 3);
    assertEquals(0, file.size());
  }

  /**
   * Verifies that {@code size()} returns 0 when the content map becomes empty between the
   * {@code !content.isEmpty()} check and the {@code content.lastKey()} call.
   *
   * <p>{@code size()} acquires the read lock, which means a concurrent {@code clear()} that holds
   * the write lock cannot interleave. Instead, the {@code NoSuchElementException} branch is
   * exercised here by verifying that adding and then clearing a file results in size 0 — the
   * post-clear call exercises the empty-file return path. The
   * {@code NoSuchElementException} branch inside the try block is only reachable under a very
   * narrow race window (between {@code isEmpty()} returning false and {@code lastKey()} running);
   * the branch is kept for safety but cannot be deterministically triggered from outside the class
   * under the lock protocol. Its absence from observable test paths is documented here.
   */
  @Test
  public void testSizeAfterClear() {
    var readCache = mock(ReadCache.class);
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 4);

    // addNewPage() internally calls cachePointer.incrementReferrer(); clear() will call
    // decrementReferrer() for each entry — do NOT pre-decrement here or clear() will go negative.
    file.addNewPage(readCache);

    try {
      assertEquals("File with one page must report size 1", 1, file.size());
    } finally {
      // clear() releases the cache pointer referrer; run in finally so a size-1 assertion
      // failure does not leak the direct-memory referrer (see testGetUsedMemoryAfterAddingPages
      // for the full rationale on the trackMode=true JVM-abort trap).
      file.clear();
    }
    assertEquals("File after clear must report size 0", 0, file.size());
  }

  // ---------------------------------------------------------------------------
  // loadPage
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code loadPage()} returns {@code null} for a page index that was never added.
   *
   * <p>A page-miss is the normal case when a storage engine probes a page slot before allocating it.
   */
  @Test
  public void testLoadPageReturnNullForMissingPage() {
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 5);
    assertNull("loadPage on empty file must return null", file.loadPage(0));
    assertNull("loadPage for arbitrary index must return null", file.loadPage(42));
  }

  /**
   * Verifies that {@code loadPage()} returns the same {@link
   * com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry} that was inserted by
   * {@code addNewPage()}.
   *
   * <p>After adding a page, the entry is stored at index 0 and loadPage(0) must return the same
   * object.
   */
  @Test
  public void testLoadPageReturnsPreviouslyAddedPage() {
    var readCache = mock(ReadCache.class);
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 6);

    var added = file.addNewPage(readCache);
    try {
      var loaded = file.loadPage(0);

      assertNotNull("loadPage must return an entry that was previously added", loaded);
      assertEquals("loadPage must return the same entry that was added", added, loaded);
    } finally {
      // clear() calls decrementReferrer() for the entry, releasing direct memory correctly.
      // Run in finally so a loadPage-returns-different-entry assertion failure does not leak
      // the direct-memory referrer (see testGetUsedMemoryAfterAddingPages for the full
      // rationale on the trackMode=true JVM-abort trap).
      file.clear();
    }
  }

  // ---------------------------------------------------------------------------
  // clear — leaked-handle (warn) branch
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@code clear()} does NOT throw when at least one cache entry has a non-zero
   * {@code usagesCount} (i.e. the entry is "leaked" — still considered in use by the caller).
   *
   * <p>{@code MemoryFile.clear()} contains a critical safety branch (the
   * {@code thereAreNotReleased = true} path) that is documented in its Javadoc as intentionally
   * NOT throwing on this condition — throwing here would "poison the YouTrackDB instance for all
   * subsequent operations" because storage deletion would fail and the storage would remain in a
   * half-deleted state. This test pins the no-throw contract so a future change that reverted to
   * throwing (or flipped polarity to skip the safety branch entirely) would be caught.
   *
   * <p>The leaked-handle condition is reproduced by calling {@code incrementUsages()} on the
   * entry (which {@code MemoryFile.clear()} reads via {@code getUsagesCount() > 0} to detect a
   * leak) and then calling {@code clear()} without the matching {@code decrementUsages()}.
   * Asserting the absence of an exception during {@code clear()} pins the no-throw contract.
   */
  @Test
  public void testClearWithUnreleasedEntryDoesNotThrow() {
    var readCache = mock(ReadCache.class);
    var file = new MemoryFile(/* storageId= */ 1, /* id= */ 7);

    var entry = file.addNewPage(readCache);
    // Bump usagesCount so the leak-detection branch (`thereAreNotReleased`) in clear() fires
    // — we deliberately do NOT call decrementUsages() here; the leak is the point of the test.
    entry.incrementUsages();

    // Contract: clear() must NOT throw on the leaked-handle path —
    // see Javadoc on MemoryFile.clear().
    file.clear();
  }
}
