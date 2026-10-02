package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.gremlin.BackupTailReadCapability;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

/** Measures bytes read from a real file channel by the local positioned tail reader. */
public class BackupTailReadTest {

  /** Large and short files return their exact tails without reading any earlier file bytes. */
  @Test
  public void positionedReadCountsActualFileBytes() throws Exception {
    var path = Files.createTempFile("backup-tail-read", ".ibu");
    try {
      var bytes = new byte[1024 * 1024];
      Arrays.fill(bytes, (byte) 3);
      Arrays.fill(bytes, bytes.length - 86, bytes.length, (byte) 9);
      Files.write(path, bytes);
      try (var channel = new CountingChannel(FileChannel.open(path, StandardOpenOption.READ))) {
        assertArrayEquals(Arrays.copyOfRange(bytes, bytes.length - 86, bytes.length),
            DiskStorage.readLocalBackupTail(channel, 86));
        assertEquals("the channel, not the array size, measures file input", 86,
            channel.bytesRead);
        assertEquals(bytes.length - 86, channel.firstPosition);
      }
      Files.write(path, new byte[] {1, 2, 3});
      try (var channel = new CountingChannel(FileChannel.open(path, StandardOpenOption.READ))) {
        assertArrayEquals(new byte[] {1, 2, 3}, DiskStorage.readLocalBackupTail(channel, 86));
        assertEquals(3, channel.bytesRead);
        assertEquals(0, channel.firstPosition);
        assertArrayEquals(new byte[0], DiskStorage.readLocalBackupTail(channel, 0));
        assertEquals(3, channel.bytesRead);
        assertThrows(IllegalArgumentException.class,
            () -> DiskStorage.readLocalBackupTail(channel, -1));
      }
    } finally {
      Files.deleteIfExists(path);
    }
  }

  /** The local input supplier exposes the optional tail read contract. */
  @Test
  public void localSupplierReturnsTheCurrentEndOfTheFile() throws Exception {
    var directory = Files.createTempDirectory("local-tail-supplier");
    try {
      Files.write(directory.resolve("unit.ibu"), new byte[] {1, 2, 3, 4});
      var supplierClass = Class.forName(DiskStorage.class.getName()
          + "$IBULocalFileInputStreamSupplier");
      var constructor = supplierClass.getDeclaredConstructor(Path.class, String.class);
      constructor.setAccessible(true);
      var supplier = (BackupTailReadCapability) constructor.newInstance(directory, "source");
      assertArrayEquals(new byte[] {3, 4}, supplier.readBackupTail("unit.ibu", 2));
      assertArrayEquals(new byte[] {1, 2, 3, 4}, supplier.readBackupTail("unit.ibu", 86));
      assertThrows(IOException.class, () -> supplier.readBackupTail("missing.ibu", 86));
    } finally {
      Files.deleteIfExists(directory.resolve("unit.ibu"));
      Files.deleteIfExists(directory);
    }
  }

  /** The production local supplier reads only the requested file bytes, not the whole file. */
  @Test
  public void localSupplierCountsFileBytesThroughItsOwnChannel() throws Exception {
    var directory = Files.createTempDirectory("local-counted-tail");
    var path = directory.resolve("unit.ibu");
    try {
      var bytes = new byte[1024 * 1024];
      Arrays.fill(bytes, (byte) 3);
      Arrays.fill(bytes, bytes.length - 86, bytes.length, (byte) 9);
      Files.write(path, bytes);
      var channels = new ArrayList<CountingChannel>();
      var supplier = new DiskStorage.IBULocalFileInputStreamSupplier(directory, "source",
          file -> {
            var channel = new CountingChannel(FileChannel.open(file, StandardOpenOption.READ));
            channels.add(channel);
            return channel;
          });

      assertArrayEquals(Arrays.copyOfRange(bytes, bytes.length - 86, bytes.length),
          supplier.readBackupTail("unit.ibu", 86));
      assertEquals("the supplier must open exactly one file channel", 1, channels.size());
      assertEquals("the supplier must read only the last 86 file bytes", 86,
          channels.get(0).bytesRead);
      assertEquals(bytes.length - 86, channels.get(0).firstPosition);
      assertEquals(false, channels.get(0).isOpen());

      Files.write(path, new byte[] {1, 2, 3});
      assertArrayEquals(new byte[] {1, 2, 3}, supplier.readBackupTail("unit.ibu", 86));
      assertEquals(2, channels.size());
      assertEquals("a short file needs only its three bytes", 3, channels.get(1).bytesRead);
      assertEquals(0, channels.get(1).firstPosition);
      assertEquals(false, channels.get(1).isOpen());
    } finally {
      Files.deleteIfExists(path);
      Files.deleteIfExists(directory);
    }
  }

  /** Counts bytes returned by positioned file reads, including any partial reads. */
  static final class CountingChannel extends FileChannel {
    private final FileChannel delegate;
    long bytesRead;
    long firstPosition = -1;

    CountingChannel(FileChannel delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
      if (firstPosition < 0) {
        firstPosition = position;
      }
      var count = delegate.read(dst, position);
      if (count > 0) {
        bytesRead += count;
      }
      return count;
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    protected void implCloseChannel() throws IOException {
      delegate.close();
    }

    @Override
    public int read(ByteBuffer dst) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public int write(ByteBuffer src) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public long position() {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public FileChannel position(long newPosition) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public FileChannel truncate(long size) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public void force(boolean metaData) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public int write(ByteBuffer src, long position) {
      throw new AssertionError("read-only channel");
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
      throw new AssertionError("only positioned reads are allowed");
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
      throw new AssertionError("only positioned reads are allowed");
    }
  }
}
