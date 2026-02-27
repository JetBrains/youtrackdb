package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * A two-level free-space map that tracks the maximum free space available on each data page of a
 * {@link PaginatedCollectionV2}, enabling efficient O(log N) lookup for a page with enough room
 * to store a record chunk.
 *
 * <h2>Architecture</h2>
 *
 * <p>The free-space map is stored in a single file with extension {@value #DEF_EXTENSION}. The
 * file contains a hierarchy of {@link FreeSpaceMapPage} pages organized in a two-level structure:
 *
 * <pre>{@code
 *  .fsm file layout:
 *
 *  Page 0 (first level):
 *  +-----------------------------------------------------+
 *  |  Segment tree over second-level pages                |
 *  |  Each cell = max(free space across all data pages    |
 *  |              tracked by the corresponding            |
 *  |              second-level page)                      |
 *  +-----------------------------------------------------+
 *
 *  Pages 1..K (second level):
 *  +-----------------------------------------------------+
 *  |  Segment tree over individual data pages             |
 *  |  Each leaf cell = normalized free space of one       |
 *  |                   data page                          |
 *  +-----------------------------------------------------+
 *
 *  Lookup flow for findFreePage(requiredSize):
 *
 *  1. Normalize requiredSize:
 *        normalizedSize = requiredSize / NORMALIZATION_INTERVAL + 1
 *
 *  2. Search first-level page (page 0) for a second-level page
 *     whose max free space >= normalizedSize
 *        -> returns secondLevelPageIndex (local index within page 0's tree)
 *
 *  3. Search the second-level page for a data page whose
 *     normalized free space >= normalizedSize
 *        -> returns localDataPageIndex
 *
 *  4. Global data page index =
 *        localDataPageIndex + secondLevelPageIndex * CELLS_PER_PAGE
 * }</pre>
 *
 * <h2>Normalization</h2>
 *
 * <p>Free space values are <em>normalized</em> to fit in a single byte (0..255) by dividing
 * the actual free space in bytes by {@link #NORMALIZATION_INTERVAL}. This quantization trades
 * precision for compact storage: each cell in the segment tree occupies only 1 byte. The
 * normalization interval is {@code floor(MAX_PAGE_SIZE_BYTES / 256)}, ensuring that a full
 * page maps to a value of ~255.
 *
 * <h2>Update Flow</h2>
 *
 * <p>When a record is written to or deleted from a data page, the collection calls
 * {@link #updatePageFreeSpace(AtomicOperation, int, int)} with the page's new maximum record
 * size. The normalized value is propagated through the second-level segment tree (updating the
 * leaf and its ancestors), and the new maximum for that second-level page is then propagated
 * up to the first-level tree.
 *
 * @see FreeSpaceMapPage
 * @see PaginatedCollectionV2
 */
public final class FreeSpaceMap extends DurableComponent {

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
  private long fileId;

  public FreeSpaceMap(
      @Nonnull AbstractStorage storage,
      @Nonnull String name,
      String extension,
      String lockName) {
    super(storage, name, extension, lockName);
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
    try (final var firstLevelCacheEntry = addPage(atomicOperation, fileId)) {
      final var page = new FreeSpaceMapPage(firstLevelCacheEntry);
      page.init();
    }
  }

  /**
   * Finds a data page with at least {@code requiredSize} bytes of free space.
   *
   * <p>The search is a two-step process:
   * <ol>
   *   <li>Search the <b>first-level</b> page (page 0) to find which second-level page
   *       contains a data page with enough free space.</li>
   *   <li>Search the identified <b>second-level</b> page to find the specific data page.</li>
   * </ol>
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

    final int localSecondLevelPageIndex;

    // Step 1: search the first-level page for a second-level page with enough max free space.
    try (final var firstLevelEntry = loadPageForRead(atomicOperation, fileId, 0)) {
      final var page = new FreeSpaceMapPage(firstLevelEntry);
      localSecondLevelPageIndex = page.findPage(normalizedSize);
      if (localSecondLevelPageIndex < 0) {
        return -1;
      }
    }

    // Step 2: search within the identified second-level page for the actual data page.
    // Second-level pages start at file page index 1 (page 0 is the first level).
    final var secondLevelPageIndex = localSecondLevelPageIndex + 1;
    try (final var leafEntry =
        loadPageForRead(atomicOperation, fileId, secondLevelPageIndex)) {
      final var page = new FreeSpaceMapPage(leafEntry);
      // Convert the local leaf index to a global data-page index.
      return page.findPage(normalizedSize)
          + localSecondLevelPageIndex * FreeSpaceMapPage.CELLS_PER_PAGE;
    }
  }

  /**
   * Updates the free-space tracking for a specific data page. Called by
   * {@link PaginatedCollectionV2} after writing or deleting a record on the data page.
   *
   * <p>The update propagates through both levels of the tree:
   * <ol>
   *   <li>Normalize {@code freeSpace} to a 0..255 byte value.</li>
   *   <li>Update the leaf in the second-level page, which returns the new max for that
   *       second-level page.</li>
   *   <li>Propagate the new max up to the corresponding cell in the first-level page.</li>
   * </ol>
   *
   * <p>If the second-level page does not yet exist (the data file grew beyond what the FSM
   * currently tracks), new pages are appended and initialized before the update.
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
    // +1 because page 0 of the FSM file is the first-level page.
    final var secondLevelPageIndex = 1 + pageIndex / FreeSpaceMapPage.CELLS_PER_PAGE;

    // Ensure all required pages exist (the FSM file may need to grow).
    final var filledUpTo = getFilledUpTo(atomicOperation, fileId);
    for (var i = 0; i < secondLevelPageIndex - filledUpTo + 1; i++) {
      try (final var cacheEntry = addPage(atomicOperation, fileId)) {
        final var page = new FreeSpaceMapPage(cacheEntry);
        page.init();
      }
    }

    // Update the second-level page's segment tree and get the new page-wide maximum.
    final int maxFreeSpaceSecondLevel;
    final var localSecondLevelPageIndex = pageIndex % FreeSpaceMapPage.CELLS_PER_PAGE;
    try (final var leafEntry =
        loadPageForWrite(atomicOperation, fileId, secondLevelPageIndex, true)) {

      final var page = new FreeSpaceMapPage(leafEntry);
      maxFreeSpaceSecondLevel =
          page.updatePageMaxFreeSpace(localSecondLevelPageIndex, normalizedSpace);
    }

    // Propagate the new second-level maximum up to the first-level page.
    try (final var firstLevelCacheEntry =
        loadPageForWrite(atomicOperation, fileId, 0, true)) {
      final var page = new FreeSpaceMapPage(firstLevelCacheEntry);
      page.updatePageMaxFreeSpace(secondLevelPageIndex - 1, maxFreeSpaceSecondLevel);
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
