package com.jetbrains.youtrack.db.internal.core.storage.fs;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrack.db.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncFile implements File {

  private final boolean logFileDeletion;
  private final ScalableRWLock lock = new ScalableRWLock();
  private volatile Path osFile;

  private final AtomicLong dirtyCounter = new AtomicLong();
  private final Object flushSemaphore = new Object();

  private final AtomicLong size = new AtomicLong(-1);
  private AsynchronousFileChannel fileChannel;

  private final int pageSize;
  private final ExecutorService executor;
  private final String dbName;

  private final TimeRate diskReadMeter;
  private final TimeRate diskWriteMeter;

  private final Semaphore syncSemaphore = new Semaphore(Integer.MAX_VALUE);
  private static final Set<OpenOption> options;

  static {
    options = new HashSet<>();
    options.add(StandardOpenOption.READ);
    options.add(StandardOpenOption.WRITE);
  }

  public AsyncFile(
      final Path osFile, final int pageSize, boolean logFileDeletion, ExecutorService executor,
      String dbName) {
    this.osFile = osFile;
    this.pageSize = pageSize;
    this.executor = executor;
    this.logFileDeletion = logFileDeletion;
    this.dbName = dbName;

    this.diskReadMeter = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DISK_READ_RATE, dbName);
    this.diskWriteMeter = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DISK_WRITE_RATE, dbName);
  }

  @Override
  public void create() throws IOException {
    lock.exclusiveLock();
    try {
      if (fileChannel != null) {
        throw new StorageException(dbName, "File " + osFile + " is already opened.");
      }

      Files.createFile(osFile);

      doOpen();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void initSize() throws IOException {
    if (fileChannel.size() < HEADER_SIZE) {
      final var buffer = ByteBuffer.allocate(HEADER_SIZE);

      var written = 0;
      do {
        buffer.position(written);
        final var writeFuture = fileChannel.write(buffer, written);
        try {
          written += writeFuture.get();
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("File write was interrupted"), e, dbName);
        } catch (ExecutionException e) {
          throw BaseException.wrapException(
              new StorageException(dbName, "Error during write operation to the file " + osFile), e,
              dbName);
        }
      } while (written < HEADER_SIZE);

      dirtyCounter.incrementAndGet();
    }

    var currentSize = fileChannel.size() - HEADER_SIZE;

    if (currentSize % pageSize != 0) {
      final var initialSize = currentSize;

      currentSize = (currentSize / pageSize) * pageSize;
      fileChannel.truncate(currentSize + HEADER_SIZE);

      LogManager.instance()
          .warn(
              this,
              "Data page in file {} was partially written and will be truncated, "
                  + "initial size {}, truncated size {}",
              osFile,
              initialSize,
              currentSize);
    }

    if (size.get() < 0) {
      size.set(currentSize);
    } else {
      if (fileChannel.size() - HEADER_SIZE > size.get()) {
        throw new IllegalStateException(
            "Physical size of the file "
                + (fileChannel.size() - HEADER_SIZE)
                + " but logical size is "
                + size.get());
      }
    }
  }

  @Override
  public void open() {
    lock.exclusiveLock();
    try {
      doOpen();
    } catch (IOException e) {
      throw BaseException.wrapException(new StorageException(dbName, "Can not open file " + osFile),
          e, dbName);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void doOpen() throws IOException {
    if (fileChannel != null) {
      throw new StorageException(dbName, "File " + osFile + " is already opened.");
    }
    fileChannel = AsynchronousFileChannel.open(osFile, options, executor);

    initSize();
  }

  @Override
  public long getFileSize() {
    return size.get();
  }

  @Override
  public long getUnderlyingFileSize() throws IOException {
    return Files.size(osFile) - HEADER_SIZE;
  }

  @Override
  public String getName() {
    return osFile.getFileName().toString();
  }

  @Override
  public boolean isOpen() {
    lock.sharedLock();
    try {
      return fileChannel != null;
    } finally {
      lock.sharedUnlock();
    }
  }

  @Override
  public boolean exists() {
    return Files.exists(osFile);
  }

  @Override
  public void write(long offset, ByteBuffer buffer) {
    syncSemaphore.acquireUninterruptibly();
    lock.sharedLock();
    try {
      buffer.rewind();

      checkForClose();
      checkPosition(offset);
      checkPosition(offset + buffer.limit() - 1);

      var written = 0;
      do {
        buffer.position(written);
        final var writeFuture =
            fileChannel.write(buffer, offset + HEADER_SIZE + written);
        try {
          written += writeFuture.get();
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("File write was interrupted"), e, dbName);
        } catch (ExecutionException e) {
          throw BaseException.wrapException(
              new StorageException(dbName, "Error during write operation to the file " + osFile), e,
              dbName);
        }
      } while (written < buffer.limit());

      dirtyCounter.incrementAndGet();
      assert written == buffer.limit();
    } finally {
      lock.sharedUnlock();
      syncSemaphore.release();
    }
  }

  @Override
  public IOResult write(List<RawPairLongObject<ByteBuffer>> buffers) {
    final var latch = new CountDownLatch(buffers.size());
    final var asyncIOResult = new AsyncIOResult(latch, dbName);

    syncSemaphore.acquireUninterruptibly(buffers.size());
    for (final var pair : buffers) {
      final var byteBuffer = pair.second;
      byteBuffer.rewind();
      lock.sharedLock();
      try {
        checkForClose();
        checkPosition(pair.first);
        checkPosition(pair.first + pair.second.limit() - 1);

        final var position = pair.first + HEADER_SIZE;
        fileChannel.write(
            byteBuffer,
            position,
            latch,
            new WriteHandler(byteBuffer, asyncIOResult, position, syncSemaphore));
      } finally {
        lock.sharedUnlock();
      }
    }

    return asyncIOResult;
  }

  @Override
  public void read(long offset, ByteBuffer buffer, boolean throwOnEof) throws IOException {
    lock.sharedLock();
    var read = 0;
    try {
      checkForClose();
      checkPosition(offset);

      do {
        buffer.position(read);
        final var readFuture = fileChannel.read(buffer, offset + HEADER_SIZE + read);
        final int bytesRead;
        try {
          bytesRead = readFuture.get();
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new ThreadInterruptedException("File write was interrupted"), e, dbName);
        } catch (ExecutionException e) {
          throw BaseException.wrapException(
              new StorageException(dbName, "Error during read operation from the file " + osFile),
              e, dbName);
        }

        if (bytesRead == -1) {
          if (throwOnEof) {
            throw new EOFException("End of file " + osFile + " is reached.");
          }

          break;
        }

        read += bytesRead;
      } while (read < buffer.limit());
    } finally {
      lock.sharedUnlock();
      diskReadMeter.record(read);
    }
  }

  @Override
  public long allocateSpace(int size) {
    return this.size.getAndAdd(size);
  }

  @Override
  public void shrink(long size) throws IOException {
    lock.exclusiveLock();
    try {
      checkForClose();

      this.size.set(0);
      fileChannel.truncate(size + HEADER_SIZE);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void synch() {
    lock.sharedLock();
    try {
      doSynch();
    } finally {
      lock.sharedUnlock();
    }
  }

  private void doSynch() {
    syncSemaphore.acquireUninterruptibly(Integer.MAX_VALUE);
    try {
      synchronized (flushSemaphore) {
        var dirtyCounterValue = dirtyCounter.get();
        if (dirtyCounterValue > 0) {
          try {
            fileChannel.force(true);
          } catch (final IOException e) {
            LogManager.instance()
                .warn(
                    this,
                    "Error during flush of file %s. Data may be lost in case of power failure",
                    e,
                    getName());
          }

          dirtyCounter.addAndGet(-dirtyCounterValue);
        }
      }
    } finally {
      syncSemaphore.release(Integer.MAX_VALUE);
    }
  }

  @Override
  public void close() {
    lock.exclusiveLock();
    try {
      doSynch();
      doClose();
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(dbName, "Error during closing the file " + osFile), e, dbName);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void doClose() throws IOException {
    // ignore if closed
    if (fileChannel != null) {
      fileChannel.close();
      fileChannel = null;
    }
  }

  @Override
  public void delete() throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      if (logFileDeletion) {
        LogManager.instance().info(this, "File " + osFile + " has been deleted.");
      }
      Files.delete(osFile);
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void renameTo(Path newFile) throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      //noinspection NonAtomicOperationOnVolatileField
      osFile = Files.move(osFile, newFile);

      doOpen();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  @Override
  public void replaceContentWith(final Path newContentFile) throws IOException {
    lock.exclusiveLock();
    try {
      doClose();

      Files.copy(newContentFile, osFile, StandardCopyOption.REPLACE_EXISTING);

      doOpen();
    } finally {
      lock.exclusiveUnlock();
    }
  }

  private void checkPosition(long offset) {
    final var fileSize = size.get();
    if (offset < 0 || offset >= fileSize) {
      throw new StorageException(dbName,
          "You are going to access region outside of allocated file position. File size = "
              + fileSize
              + ", requested position "
              + offset);
    }
  }

  private void checkForClose() {
    if (fileChannel == null) {
      throw new StorageException(dbName, "File " + osFile + " is closed");
    }
  }

  private final class WriteHandler implements CompletionHandler<Integer, CountDownLatch> {

    private final ByteBuffer byteBuffer;
    private final AsyncIOResult ioResult;
    private final long position;

    private final Semaphore syncSemaphore;

    private WriteHandler(
        ByteBuffer byteBuffer, AsyncIOResult ioResult, long position, Semaphore syncSemaphore) {
      this.byteBuffer = byteBuffer;
      this.ioResult = ioResult;
      this.position = position;
      this.syncSemaphore = syncSemaphore;
    }

    @Override
    public void completed(Integer bytesWritten, CountDownLatch attachment) {
      diskWriteMeter.record(bytesWritten);

      if (byteBuffer.remaining() > 0) {
        lock.sharedLock();
        try {
          checkForClose();

          fileChannel.write(byteBuffer, position + byteBuffer.position(), attachment, this);
        } finally {
          lock.sharedUnlock();
        }
      } else {
        dirtyCounter.incrementAndGet();
        attachment.countDown();
        syncSemaphore.release();
      }
    }

    @Override
    public void failed(Throwable exc, CountDownLatch attachment) {
      ioResult.exc = exc;
      LogManager.instance().error(this, "Error during write operation to the file " + osFile, exc);

      dirtyCounter.incrementAndGet();
      attachment.countDown();
      syncSemaphore.release();
    }
  }

  private static final class AsyncIOResult implements IOResult {

    private final CountDownLatch latch;
    private Throwable exc;
    private final String dbName;

    private AsyncIOResult(CountDownLatch latch, String dbName) {
      this.latch = latch;
      this.dbName = dbName;
    }

    @Override
    public void await() {
      try {
        latch.await();
      } catch (java.lang.InterruptedException e) {
        throw BaseException.wrapException(
            new ThreadInterruptedException("File write was interrupted"),
            e, dbName);
      }
      if (exc != null) {
        throw BaseException.wrapException(new StorageException(dbName, "Error during IO operation"),
            exc, dbName);
      }
    }
  }
}
