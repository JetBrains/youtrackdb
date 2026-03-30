package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * A durable bit set that tracks which data pages in a {@link PaginatedCollectionV2} contain at
 * least one stale record's <em>start chunk</em>. The GC scans only pages whose bit is set,
 * avoiding a full collection scan.
 *
 * <h2>File Layout</h2>
 *
 * <p>The bit set is stored in a single file with extension {@value #DEF_EXTENSION}. Each page
 * in the file is a {@link DirtyPageBitSetPage} that stores {@link DirtyPageBitSetPage#BITS_PER_PAGE}
 * bits. Bit {@code i} corresponds to data page {@code i} in the collection's {@code .cdt} file.
 * Multiple bit-set pages are appended on demand as the collection grows.
 *
 * <h2>Concurrency</h2>
 *
 * <p>All bit set mutations are WAL-logged via the {@link AtomicOperation} passed to each method.
 * The caller is responsible for holding the collection's component lock (via
 * {@link StorageComponent#executeInsideComponentOperation}) to serialize bit set access against
 * concurrent writes and GC.
 *
 * @see DirtyPageBitSetPage
 * @see PaginatedCollectionV2
 */
public final class CollectionDirtyPageBitSet extends StorageComponent {

  /** File extension for dirty page bit set files. */
  public static final String DEF_EXTENSION = ".dpb";

  /** Internal file ID assigned by the disk cache when the .dpb file is opened/created. */
  private long fileId;

  public CollectionDirtyPageBitSet(
      @Nonnull AbstractStorage storage,
      @Nonnull String name,
      String extension,
      String lockName) {
    super(storage, name, extension, lockName);
  }

  /**
   * Returns {@code true} if the backing file already exists on disk.
   */
  public boolean exists(final AtomicOperation atomicOperation) {
    return isFileExists(atomicOperation, getFullName());
  }

  /**
   * Creates a new dirty page bit set file and initializes the first page to all zeros.
   */
  public void create(final AtomicOperation atomicOperation) throws IOException {
    fileId = addFile(atomicOperation, getFullName());

    try (final var cacheEntry = addPage(atomicOperation, fileId)) {
      final var page = new DirtyPageBitSetPage(cacheEntry);
      page.init();
    }
  }

  /**
   * Opens an existing dirty page bit set file.
   */
  public void open(final AtomicOperation atomicOperation) throws IOException {
    fileId = openFile(atomicOperation, getFullName());
  }

  /**
   * Flushes pending writes for this dirty page bit set file to disk.
   */
  public void flush() {
    writeCache.flush(fileId);
  }

  /**
   * Closes the dirty page bit set file.
   *
   * @param flush if {@code true}, pending writes are flushed to disk before closing
   */
  public void close(final boolean flush) {
    readCache.closeFile(fileId, flush, writeCache);
  }

  /**
   * Deletes the dirty page bit set file.
   */
  public void delete(final AtomicOperation atomicOperation) throws IOException {
    deleteFile(atomicOperation, fileId);
  }

  /**
   * Renames the dirty page bit set file to match a new collection name.
   */
  void rename(final String newName) throws IOException {
    writeCache.renameFile(fileId, newName + getExtension());
    setName(newName);
  }

  /**
   * Sets the bit corresponding to the given data page index, indicating that the page contains
   * at least one stale record's start chunk.
   *
   * <p>If the bit set file does not yet have enough pages to cover the given data page index,
   * new pages are appended and initialized automatically.
   *
   * @param dataPageIndex the index of the data page in the collection's {@code .cdt} file
   * @param atomicOperation the current atomic operation context
   */
  public void set(int dataPageIndex, AtomicOperation atomicOperation) throws IOException {
    assert dataPageIndex >= 0;

    long bitSetPageIndex = dataPageIndex / DirtyPageBitSetPage.BITS_PER_PAGE;
    var bitIndex = dataPageIndex % DirtyPageBitSetPage.BITS_PER_PAGE;

    ensureCapacity(bitSetPageIndex, atomicOperation);

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, bitSetPageIndex, true)) {
      final var page = new DirtyPageBitSetPage(cacheEntry);
      page.setBit(bitIndex);
    }
  }

  /**
   * Clears the bit corresponding to the given data page index.
   *
   * @param dataPageIndex the index of the data page in the collection's {@code .cdt} file
   * @param atomicOperation the current atomic operation context
   */
  public void clear(int dataPageIndex, AtomicOperation atomicOperation) throws IOException {
    assert dataPageIndex >= 0;

    long bitSetPageIndex = dataPageIndex / DirtyPageBitSetPage.BITS_PER_PAGE;

    // If the bit set file doesn't cover this page index, the bit is already clear.
    if (bitSetPageIndex >= getFilledUpTo(atomicOperation, fileId)) {
      return;
    }

    var bitIndex = dataPageIndex % DirtyPageBitSetPage.BITS_PER_PAGE;

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, bitSetPageIndex, true)) {
      final var page = new DirtyPageBitSetPage(cacheEntry);
      page.clearBit(bitIndex);
    }
  }

  /**
   * Returns the index of the first set bit at or after {@code fromDataPageIndex}, or {@code -1}
   * if no set bits exist from that point onward.
   *
   * @param fromDataPageIndex the starting data page index (inclusive)
   * @param atomicOperation the current atomic operation context
   * @return the data page index of the next set bit, or {@code -1} if none found
   */
  public int nextSetBit(int fromDataPageIndex, AtomicOperation atomicOperation)
      throws IOException {
    assert fromDataPageIndex >= 0;

    long bitSetPageIndex = fromDataPageIndex / DirtyPageBitSetPage.BITS_PER_PAGE;
    var bitIndex = fromDataPageIndex % DirtyPageBitSetPage.BITS_PER_PAGE;
    var totalPages = getFilledUpTo(atomicOperation, fileId);

    while (bitSetPageIndex < totalPages) {
      try (final var cacheEntry =
          loadPageForRead(atomicOperation, fileId, bitSetPageIndex)) {
        final var page = new DirtyPageBitSetPage(cacheEntry);
        var found = page.nextSetBit(bitIndex);
        if (found >= 0) {
          return (int) (bitSetPageIndex * DirtyPageBitSetPage.BITS_PER_PAGE + found);
        }
      }

      // Move to the start of the next bit set page.
      bitSetPageIndex++;
      bitIndex = 0;
    }

    return -1;
  }

  /**
   * Ensures the file has enough pages to cover bit set page index {@code requiredPageIndex}.
   * Appends and initializes new pages as needed.
   */
  private void ensureCapacity(long requiredPageIndex, AtomicOperation atomicOperation)
      throws IOException {
    var filledUpTo = getFilledUpTo(atomicOperation, fileId);

    for (var i = filledUpTo; i <= requiredPageIndex; i++) {
      try (final var cacheEntry = addPage(atomicOperation, fileId)) {
        final var page = new DirtyPageBitSetPage(cacheEntry);
        page.init();
      }
    }
  }
}
