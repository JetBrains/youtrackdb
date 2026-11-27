package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.mockito.Mockito;

public class BackupAllocatedBlankPageTest {

  // A silent miss copies the canonical blank under its shared lock, then releases the reference.
  @Test
  public void copiesAllocatedBlankRepresentation() throws Exception {
    final var storage = mock(DiskStorage.class, Mockito.CALLS_REAL_METHODS);
    final var read = mock(ReadCache.class);
    final var write = mock(WriteCache.class);
    setField(storage, "readCache", read);
    setField(storage, "writeCache", write);
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(write.pageSize()).thenReturn(128);
    when(write.physicalSizeForBackupSnapshot(1)).thenReturn(3L);
    final var pointer = mock(CachePointer.class);
    final var blank = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
    DurablePage.setLogSequenceNumberForPage(blank, new LogSequenceNumber(-1, -1));
    when(pointer.acquireSharedLock()).thenReturn(17L);
    when(pointer.getBuffer()).thenReturn(blank);
    when(write.loadOrAdd(1, 2, true)).thenReturn(pointer);
    final var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("page"));
      storage.copyBackupPage(1, 2, null, zip, null, null, new byte[136],
          new LogSequenceNumber[1]);
      zip.closeEntry();
    }
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      zip.getNextEntry();
      final var data = zip.readAllBytes();
      assertEquals(136, data.length);
      assertEquals(2, ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).getLong());
      assertArrayEquals(blank.array(), java.util.Arrays.copyOfRange(data, 8, data.length));
    }
    final var order = inOrder(pointer);
    order.verify(pointer).acquireSharedLock();
    order.verify(pointer).getBuffer();
    order.verify(pointer).releaseSharedLock(17L);
    order.verify(pointer).decrementReadersReferrer();
  }

  // A failed fallback copy releases the shared lock before releasing the reader reference.
  @Test
  public void releasesFallbackLockWhenCopyThrows() throws Exception {
    final var storage = mock(DiskStorage.class, Mockito.CALLS_REAL_METHODS);
    final var write = mock(WriteCache.class);
    setField(storage, "readCache", mock(ReadCache.class));
    setField(storage, "writeCache", write);
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(write.pageSize()).thenReturn(128);
    when(write.physicalSizeForBackupSnapshot(1)).thenReturn(3L);
    final var pointer = mock(CachePointer.class);
    when(write.loadOrAdd(1, 2, true)).thenReturn(pointer);
    when(pointer.acquireSharedLock()).thenReturn(23L);
    when(pointer.getBuffer()).thenReturn(ByteBuffer.allocate(128).order(ByteOrder.nativeOrder()));
    final var stream = mock(ZipOutputStream.class);
    final var data = new byte[136];
    final var failure = new IOException("copy failed");
    doThrow(failure).when(stream).write(data);

    assertSame(failure, assertThrows(IOException.class, () -> storage.copyBackupPage(
        1, 2, null, stream, null, null, data, new LogSequenceNumber[1])));

    final var order = inOrder(pointer, stream);
    order.verify(pointer).acquireSharedLock();
    order.verify(pointer).getBuffer();
    order.verify(stream).write(data);
    order.verify(pointer).releaseSharedLock(23L);
    order.verify(pointer).decrementReadersReferrer();
  }

  // A missing written page remains an EOF error, even if a silent-load wrapper returns absence.
  @Test
  public void doesNotMaskMissingWrittenPage() throws Exception {
    final var storage = mock(DiskStorage.class, Mockito.CALLS_REAL_METHODS);
    final var write = mock(WriteCache.class);
    setField(storage, "readCache", mock(ReadCache.class));
    setField(storage, "writeCache", write);
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(write.physicalSizeForBackupSnapshot(1)).thenReturn(3L);
    when(write.loadOrAdd(1, 2, true)).thenThrow(new EOFException("missing written page"));
    try (var zip = new ZipOutputStream(new ByteArrayOutputStream())) {
      assertThrows(EOFException.class, () -> storage.copyBackupPage(1, 2, null, zip,
          null, null, new byte[136], new LogSequenceNumber[1]));
    }
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Class<?> type = DiskStorage.class;
    while (type != null) {
      try {
        final var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (NoSuchFieldException ignored) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }
}
