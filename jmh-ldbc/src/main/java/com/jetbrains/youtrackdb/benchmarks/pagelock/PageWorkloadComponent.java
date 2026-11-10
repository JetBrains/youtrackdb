package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EntryPoint;
import java.io.IOException;

/**
 * Fixed existing pages. Give each independent writer a distinct component name.
 * Each page uses the EntryPoint value field and its production WAL operations.
 * These pages are not a tree. No tree traversal or structural change runs here.
 */
public final class PageWorkloadComponent extends StorageComponent {

  public record Sample(long fileId, int pageIndex, long value) {
  }

  public record Read(Sample[] pages, boolean optimistic) {
  }

  private final int pageCount;
  private long fileId;

  public PageWorkloadComponent(AbstractStorage storage, String name, int pageCount,
      boolean durable) {
    super(storage, name, ".bench", name, durable);
    if (pageCount <= 0) {
      throw new IllegalArgumentException("pageCount must be positive");
    }
    this.pageCount = pageCount;
  }

  /** Seed generation only. Run commits never allocate or extend the file. */
  public void create() throws IOException {
    atomicOperationsManager
        .executeInsideAtomicOperation(op -> executeInsideComponentOperation(op, operation -> {
          fileId = addFile(operation, getFullName());
          for (int i = 0; i < pageCount; i++) {
            var entry = allocatePageForWrite(operation, fileId, i);
            try {
              new EntryPoint(entry).init();
            } finally {
              releasePageFromWrite(operation, entry);
            }
          }
        }));
  }

  public void open() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      fileId = openFile(op, getFullName());
      if (op.filledUpTo(fileId) != pageCount) {
        throw new IOException("Seed page count differs for " + getFullName());
      }
    });
  }

  public Read read(int[] pages) throws IOException {
    checkPages(pages);
    // Read-only operations capture a snapshot without registering a write or committing.
    var op = atomicOperationsManager.startAtomicOperation();
    try {
      return executeOptimisticStorageRead(op, () -> {
        Sample[] result = new Sample[pages.length];
        for (int i = 0; i < pages.length; i++) {
          var view = loadPageOptimistic(op, fileId, pages[i]);
          result[i] = new Sample(view.pageFrame().getFileId(),
              view.pageFrame().getPageIndex(), new EntryPoint(view).getTreeSize());
        }
        return new Read(result, true);
      }, () -> {
        Sample[] result = new Sample[pages.length];
        for (int i = 0; i < pages.length; i++) {
          try (var entry = loadPageForRead(op, fileId, pages[i])) {
            result[i] = sample(entry);
          }
        }
        return new Read(result, false);
      });
    } finally {
      op.deactivate();
    }
  }

  /** Returns the actual written coordinates only after the atomic operation commits. */
  public Sample[] commit(int[] pages, long value) throws IOException {
    return commit(pages, value, null);
  }

  Sample[] commit(int[] pages, long value, java.util.function.Consumer<Object> observer)
      throws IOException {
    return commit(pages, value, null, observer);
  }

  // Setup validation can enable page tracking before the first write on either core version.
  Sample[] commit(int[] pages, long value, java.util.function.Consumer<Object> before,
      java.util.function.Consumer<Object> observer) throws IOException {
    checkPages(pages);
    return atomicOperationsManager.calculateInsideAtomicOperation(op -> {
      if (before != null) {
        before.accept(op);
      }
      Sample[] result = new Sample[pages.length];
      executeInsideComponentOperation(op, operation -> {
        for (int i = 0; i < pages.length; i++) {
          var entry = loadPageForWrite(operation, fileId, pages[i], true);
          try {
            new EntryPoint(entry).setTreeSize(value);
            result[i] = sample(entry);
          } finally {
            releasePageFromWrite(operation, entry);
          }
        }
      });
      if (observer != null) {
        observer.accept(op);
      }
      return result;
    });
  }

  /** Writes a prevalidated fixed page set without coordinate samples or value read-back. */
  void commitMeasured(int[] pages, long value) throws IOException {
    atomicOperationsManager
        .executeInsideAtomicOperation(op -> executeInsideComponentOperation(op, operation -> {
          for (int page : pages) {
            var entry = loadPageForWrite(operation, fileId, page, true);
            try {
              new EntryPoint(entry).setTreeSize(value);
            } finally {
              releasePageFromWrite(operation, entry);
            }
          }
        }));
  }

  private Sample sample(CacheEntry entry) {
    return new Sample(entry.getFileId(), entry.getPageIndex(), new EntryPoint(entry).getTreeSize());
  }

  private void checkPages(int[] pages) {
    for (int page : pages) {
      if (page < 0 || page >= pageCount) {
        throw new IllegalArgumentException("Page outside fixed workload: " + page);
      }
    }
  }

}
