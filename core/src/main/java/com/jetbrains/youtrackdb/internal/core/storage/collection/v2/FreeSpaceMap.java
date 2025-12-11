package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.IOException;
import javax.annotation.Nonnull;

public final class FreeSpaceMap extends DurableComponent {

  public static final String DEF_EXTENSION = ".fsm";

  static final int NORMALIZATION_INTERVAL =
      (int) Math.floor(DurablePage.MAX_PAGE_SIZE_BYTES / 256.0);

  private FileHandler fileHandler;

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
    fileHandler = addFile(atomicOperation, getFullName());
    init(atomicOperation);
  }

  public void open(final AtomicOperation atomicOperation) throws IOException {
    fileHandler = openFile(atomicOperation, getFullName());
  }

  private void init(final AtomicOperation atomicOperation) throws IOException {
    try (final var firstLevelCacheEntry = addPage(atomicOperation, fileHandler)) {
      final var page = new FreeSpaceMapPage(firstLevelCacheEntry);
      page.init();
    }
  }

  public int findFreePage(final int requiredSize) throws IOException {
    final var normalizedSize = requiredSize / NORMALIZATION_INTERVAL + 1;

    final var atomicOperation = atomicOperationsManager.getCurrentOperation();
    final int localSecondLevelPageIndex;

    try (final var firstLevelEntry = loadPageForRead(atomicOperation, fileHandler, 0)) {
      final var page = new FreeSpaceMapPage(firstLevelEntry);
      localSecondLevelPageIndex = page.findPage(normalizedSize);
      if (localSecondLevelPageIndex < 0) {
        return -1;
      }
    }

    final var secondLevelPageIndex = localSecondLevelPageIndex + 1;
    try (final var leafEntry =
        loadPageForRead(atomicOperation, fileHandler, secondLevelPageIndex)) {
      final var page = new FreeSpaceMapPage(leafEntry);
      return page.findPage(normalizedSize)
          + localSecondLevelPageIndex * FreeSpaceMapPage.CELLS_PER_PAGE;
    }
  }

  public void updatePageFreeSpace(
      final AtomicOperation atomicOperation, final int pageIndex, final int freeSpace)
      throws IOException {

    assert pageIndex >= 0;
    assert freeSpace < DurablePage.MAX_PAGE_SIZE_BYTES;

    final var normalizedSpace = freeSpace / NORMALIZATION_INTERVAL;
    final var secondLevelPageIndex = 1 + pageIndex / FreeSpaceMapPage.CELLS_PER_PAGE;

    final var filledUpTo = getFilledUpTo(atomicOperation, fileHandler);

    for (var i = 0; i < secondLevelPageIndex - filledUpTo + 1; i++) {
      try (final var cacheEntry = addPage(atomicOperation, fileHandler)) {
        final var page = new FreeSpaceMapPage(cacheEntry);
        page.init();
      }
    }

    final int maxFreeSpaceSecondLevel;
    final var localSecondLevelPageIndex = pageIndex % FreeSpaceMapPage.CELLS_PER_PAGE;
    try (final var leafEntry =
        loadPageForWrite(atomicOperation, fileHandler, secondLevelPageIndex, true)) {

      final var page = new FreeSpaceMapPage(leafEntry);
      maxFreeSpaceSecondLevel =
          page.updatePageMaxFreeSpace(localSecondLevelPageIndex, normalizedSpace);
    }

    try (final var firstLevelCacheEntry =
        loadPageForWrite(atomicOperation, fileHandler, 0, true)) {
      final var page = new FreeSpaceMapPage(firstLevelCacheEntry);
      page.updatePageMaxFreeSpace(secondLevelPageIndex - 1, maxFreeSpaceSecondLevel);
    }
  }

  public void delete(AtomicOperation atomicOperation) throws IOException {
    deleteFile(atomicOperation, fileHandler.fileId());
  }

  void rename(final String newName) throws IOException {
    writeCache.renameFile(fileHandler.fileId(), newName + getExtension());
    setName(newName);
  }
}
