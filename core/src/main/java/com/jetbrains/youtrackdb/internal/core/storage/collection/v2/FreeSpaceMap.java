package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * A three-level free-space map (FSM) for a {@link PaginatedCollectionV2}.
 * Each page stores a max segment tree with N = {@link FreeSpaceMapPage#CELLS_PER_PAGE} entries.
 * Leaf entries describe data pages. Middle entries hold leaf maxima. Root entries hold middle
 * maxima. Searches choose the lowest qualifying entry at each level.
 *
 * <p>The file uses depth-first order: root, middle 0, its leaves, middle 1, its leaves, and so on.
 * Middle m is at 1 + m(N + 1). Leaf l is at l + l/N + 2. For D tracked data pages, the file
 * contains 1 + ceil(D/N^2) + ceil(D/N) pages, including the root when D is zero.
 *
 * <p>Free space is rounded down by {@link #NORMALIZATION_INTERVAL}. Requests use the same
 * divisor plus one, so a returned page always has enough room. Updates propagate a child maximum
 * only when it changes. The in-page format and logical write-ahead log (WAL) records are identical
 * at all three levels.
 *
 * <p>The collection exclusive lock serializes searches and updates. Optimistic reads validate
 * each page before using its result. The enclosing read also validates all stamps and the
 * component apply epoch. Safety does not depend on page apply order.
 */
public final class FreeSpaceMap extends StorageComponent {

  /** File extension for free-space map files. */
  public static final String DEF_EXTENSION = ".fsm";

  /**
   * Normalization divisor that converts raw free-space bytes to a 0..255 range. Computed as
   * {@code floor(MAX_PAGE_SIZE_BYTES / 256)}. For the default 8 KB page size (8192 bytes),
   * this equals 32, so each unit represents ~32 bytes of free space.
   */
  static final int NORMALIZATION_INTERVAL =
      (int) Math.floor(DurablePage.MAX_PAGE_SIZE_BYTES / 256.0);

  /** Internal file ID assigned by the disk cache when the .fsm file is opened/created. */
  private volatile long fileId;

  public FreeSpaceMap(
      @Nonnull AbstractStorage storage,
      @Nonnull String name,
      String extension,
      String lockName) {
    super(storage, name, extension, lockName, true);
  }

  public boolean exists(final AtomicOperation atomicOperation) {
    return isFileExists(atomicOperation, getFullName());
  }

  public void create(final AtomicOperation atomicOperation) throws IOException {
    fileId = addFile(atomicOperation, getFullName());
    init(atomicOperation);
  }

  public void open(final AtomicOperation atomicOperation) throws IOException {
    fileId = openFile(atomicOperation, getFullName());
  }

  private void init(final AtomicOperation atomicOperation) throws IOException {
    // A fresh file contains only the root at page index 0.
    try (final var firstLevelCacheEntry = allocatePageForWrite(atomicOperation, fileId, 0)) {
      final var page = new FreeSpaceMapPage(firstLevelCacheEntry);
      page.init();
    }
  }

  /**
   * Finds a data page with at least {@code requiredSize} bytes of free space.
   *
   * <p>Descends through root, middle and leaf pages, choosing the lowest qualifying entry.
   *
   * @param requiredSize    minimum free space required (in raw bytes)
   * @param atomicOperation the current atomic operation context
   * @return the data-file page index of a suitable page, or {@code -1} if no page qualifies
   */
  public int findFreePage(final int requiredSize, AtomicOperation atomicOperation)
      throws IOException {
    // Normalize the required size to the same scale used by the segment tree cells.
    // The +1 ensures we round up, so we never return a page with slightly less space than needed.
    final var normalizedSize = requiredSize / NORMALIZATION_INTERVAL + 1;

    return executeOptimisticStorageRead(
        atomicOperation,
        () -> findFreePage(atomicOperation, normalizedSize, true),
        () -> findFreePage(atomicOperation, normalizedSize, false));
  }

  private int findFreePage(
      final AtomicOperation operation, final int normalizedSize, final boolean optimistic)
      throws IOException {
    long filePage = 0;
    long childIndex = 0;
    for (var level = 0; level < 3; level++) {
      final int entryIndex;
      if (optimistic) {
        final var view = loadPageOptimistic(operation, fileId, filePage);
        entryIndex = new FreeSpaceMapPage(view).findPage(normalizedSize);
        // Validate even a negative result before using it or returning no match.
        operation.getOptimisticReadScope().validateLastOrThrow();
      } else {
        try (final var entry = loadPageForRead(operation, fileId, filePage)) {
          entryIndex = new FreeSpaceMapPage(entry).findPage(normalizedSize);
        }
      }
      if (entryIndex < 0) {
        if (level == 0) {
          return -1;
        }
        // A runtime error retries the optimistic attempt with pinned pages.
        throw new IllegalStateException("Qualifying FSM parent has no qualifying child");
      }
      if (level == 2) {
        return dataPageIndex(childIndex, entryIndex);
      }
      childIndex = childIndex * FreeSpaceMapPage.CELLS_PER_PAGE + entryIndex;
      filePage = level == 0 ? middlePageIndex(childIndex) : leafPageIndex(childIndex);
    }
    throw new IllegalStateException("FSM search did not reach a leaf");
  }

  static long middlePageIndex(final long middleIndex) {
    return 1 + middleIndex * (FreeSpaceMapPage.CELLS_PER_PAGE + 1L);
  }

  static long leafPageIndex(final long leafIndex) {
    return leafIndex + leafIndex / FreeSpaceMapPage.CELLS_PER_PAGE + 2;
  }

  static int dataPageIndex(final long leafIndex, final int entryIndex) {
    final long index = leafIndex * FreeSpaceMapPage.CELLS_PER_PAGE + entryIndex;
    return index <= Integer.MAX_VALUE ? (int) index : -1;
  }

  /**
   * Updates the free-space tracking for a specific data page. Called by
   * {@link PaginatedCollectionV2} after writing or deleting a record on the data page.
   *
   * <p>Always updates the leaf entry and its WAL record. Writes a parent only when the child
   * maximum changes. Missing middle and leaf pages are initialized in file order before any
   * parent refers to them, within the same atomic operation.
   *
   * @param atomicOperation the current atomic operation context
   * @param pageIndex       the data-file page index whose free space changed
   * @param freeSpace       the new maximum record size (in raw bytes) that fits on the page
   */
  public void updatePageFreeSpace(
      final AtomicOperation atomicOperation, final int pageIndex, final int freeSpace)
      throws IOException {

    assert pageIndex >= 0;
    assert freeSpace < DurablePage.MAX_PAGE_SIZE_BYTES;

    final var normalizedSpace = freeSpace / NORMALIZATION_INTERVAL;
    final long leafIndex = pageIndex / FreeSpaceMapPage.CELLS_PER_PAGE;
    final var middleIndex = leafIndex / FreeSpaceMapPage.CELLS_PER_PAGE;
    final var leafFilePage = leafPageIndex(leafIndex);

    // Ensure all required pages exist (the FSM file may need to grow). Each
    // loop iteration targets a strictly-new pageIndex (i >= filledUpTo), so
    // allocatePageForWrite's allocator-only contract is trivially satisfied --
    // the FSM has no entry-point page tracking a logical extent separate from
    // the physical one, so physical orphans past filledUpTo are impossible
    // by definition (the filledUpTo read IS the physical extent). Per-component
    // lock (held transitively via PaginatedCollectionV2's component operation)
    // serialises concurrent growers on the same fileId. The pre-read fixes the
    // growth-loop starting point against current physical state.
    // The same growth-loop pattern appears in CollectionDirtyPageBitSet.ensureCapacity;
    // keep both sites in sync.
    final var filledUpTo =
        physicalSize(atomicOperation, fileId, PhysicalReadIntent.GROWTH_LOOP_PRE_READ);
    for (var i = filledUpTo; i <= leafFilePage; i++) {
      try (final var cacheEntry = allocatePageForWrite(atomicOperation, fileId, i)) {
        final var page = new FreeSpaceMapPage(cacheEntry);
        page.init();
      }
    }

    final int oldLeafMax;
    final int newLeafMax;
    try (final var entry = loadPageForWrite(atomicOperation, fileId, leafFilePage, true)) {
      final var page = new FreeSpaceMapPage(entry);
      oldLeafMax = page.getMaxFreeSpace();
      newLeafMax = page.updatePageMaxFreeSpace(
          pageIndex % FreeSpaceMapPage.CELLS_PER_PAGE, normalizedSpace);
    }
    if (oldLeafMax == newLeafMax) {
      return;
    }

    final int oldMiddleMax;
    final int newMiddleMax;
    try (final var entry =
        loadPageForWrite(atomicOperation, fileId, middlePageIndex(middleIndex), true)) {
      final var page = new FreeSpaceMapPage(entry);
      oldMiddleMax = page.getMaxFreeSpace();
      newMiddleMax = page.updatePageMaxFreeSpace(
          (int) (leafIndex % FreeSpaceMapPage.CELLS_PER_PAGE), newLeafMax);
    }
    if (oldMiddleMax == newMiddleMax) {
      return;
    }

    try (final var entry = loadPageForWrite(atomicOperation, fileId, 0, true)) {
      new FreeSpaceMapPage(entry).updatePageMaxFreeSpace((int) middleIndex, newMiddleMax);
    }
  }

  public void delete(AtomicOperation atomicOperation) throws IOException {
    deleteFile(atomicOperation, fileId);
  }

  void rename(final String newName) throws IOException {
    writeCache.renameFile(fileId, newName + getExtension());
    setName(newName);
  }
}
