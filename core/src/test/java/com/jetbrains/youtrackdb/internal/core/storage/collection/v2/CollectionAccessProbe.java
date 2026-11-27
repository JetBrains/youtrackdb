package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.util.ArrayList;
import java.util.List;
import org.mockito.invocation.InvocationOnMock;

/** Records requests before they can be served from a cache hit or an operation overlay. */
@SuppressWarnings("deprecation")
final class CollectionAccessProbe {

  record PageRequest(String kind, long file, long page) {
  }

  final List<PageRequest> pages = new ArrayList<>();
  final List<Long> extents = new ArrayList<>();
  private final WriteCache writeCache;

  CollectionAccessProbe(AbstractStorage storage, PaginatedCollectionV2 collection) {
    var reads = storage.getReadCache();
    var observed = mock(ReadCache.class, delegatesTo(reads));
    doAnswer(call -> {
      record("optimistic", call);
      return reads.getCacheEntryOptimistic(call.getArgument(0), call.getArgument(1));
    }).when(observed).getCacheEntryOptimistic(anyLong(), anyLong());

    for (var component : List.of(collection, field(PaginatedCollectionV2.class, collection,
        "collectionPositionMap"), field(PaginatedCollectionV2.class, collection, "freeSpaceMap"),
        field(PaginatedCollectionV2.class, collection, "dirtyPageBitSet"))) {
      setField(StorageComponent.class, component, "readCache", observed);
    }

    var writes = storage.getWriteCache();
    writeCache = mock(WriteCache.class, delegatesTo(writes));
    doAnswer(call -> {
      long id = call.getArgument(0);
      extents.add(id);
      return writes.getFilledUpTo(id);
    }).when(writeCache).getFilledUpTo(anyLong());
  }

  AtomicOperation observe(AtomicOperation operation) {
    setField(operation.getClass(), operation, "writeCache", writeCache);
    var delegate = delegatesTo(operation);
    return mock(AtomicOperation.class, call -> {
      switch (call.getMethod().getName()) {
        case "loadPageForRead" -> record("pinned", call);
        case "loadPageForWrite" -> record("write", call);
        case "allocatePageForWrite" -> record("allocate", call);
        default -> {
        }
      }
      return delegate.answer(call);
    });
  }

  void clear() {
    pages.clear();
    extents.clear();
  }

  private void record(String kind, InvocationOnMock call) {
    pages.add(new PageRequest(kind, call.getArgument(0), call.getArgument(1)));
  }

  private static Object field(Class<?> type, Object target, String name) {
    try {
      var member = type.getDeclaredField(name);
      member.setAccessible(true);
      return member.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void setField(Class<?> type, Object target, String name, Object value) {
    try {
      var member = type.getDeclaredField(name);
      member.setAccessible(true);
      member.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
