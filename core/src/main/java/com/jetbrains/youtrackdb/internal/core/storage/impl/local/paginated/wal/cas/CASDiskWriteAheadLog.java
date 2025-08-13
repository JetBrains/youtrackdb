package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.thread.ThreadPoolExecutors;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableLong;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.EncryptionKeyAbsentException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidStorageEncryptionKeyException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CheckpointRequestListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.MilestoneWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.StartWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque.MPSCFAAArrayDequeue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jpountz.xxhash.XXHashFactory;

public final class CASDiskWriteAheadLog implements WriteAheadLog {

  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";

  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(CASDiskWriteAheadLog::getCipherInstance);

  private static final XXHashFactory xxHashFactory = XXHashFactory.fastestJavaInstance();
  private static final long XX_SEED = 0x9747b28cL;

  private static final int BATCH_READ_SIZE = 4 * 1024;

  static final int DEFAULT_MAX_CACHE_SIZE = Integer.MAX_VALUE;

  private static final ScheduledExecutorService commitExecutor;
  private static final ExecutorService writeExecutor;

  static {
    commitExecutor =
        ThreadPoolExecutors.newSingleThreadScheduledPool(
            "YouTrackDB WAL Flush Task", AbstractStorage.storageThreadGroup);

    writeExecutor =
        ThreadPoolExecutors.newSingleThreadPool(
            "YouTrackDB WAL Write Task Thread", AbstractStorage.storageThreadGroup);
  }

  private final boolean keepSingleWALSegment;

  private final List<CheckpointRequestListener> checkpointRequestListeners =
      new CopyOnWriteArrayList<>();

  private final long walSizeLimit;

  private final long segmentsInterval;

  private final long maxSegmentSize;

  private final MPSCFAAArrayDequeue<WALRecord> records = new MPSCFAAArrayDequeue<>();

  private volatile long currentSegment;

  private final AtomicLong segmentSize = new AtomicLong();
  private final AtomicLong logSize = new AtomicLong();
  private final AtomicLong queueSize = new AtomicLong();

  private final int maxCacheSize;

  private final AtomicReference<LogSequenceNumber> end = new AtomicReference<>();
  private final ConcurrentSkipListSet<Long> segments = new ConcurrentSkipListSet<>();

  private final Path walLocation;
  private final String storageName;
  private final String walBaseName;

  private final TimeRate diskWriteMeter;

  private final DirectMemoryAllocator allocator = DirectMemoryAllocator.instance();

  private final int pageSize;
  private final int maxRecordSize;

  private volatile WALFile walFile = null;

  private volatile LogSequenceNumber flushedLSN = null;

  private final AtomicReference<WrittenUpTo> writtenUpTo = new AtomicReference<>();
  private long segmentId = -1;

  private final ScheduledFuture<?> recordsWriterFuture;
  private final ReentrantLock recordsWriterLock = new ReentrantLock();
  private volatile boolean cancelRecordsWriting = false;

  private final ConcurrentNavigableMap<LogSequenceNumber, EventWrapper> events =
      new ConcurrentSkipListMap<>();

  private final ScalableRWLock segmentLock = new ScalableRWLock();

  private final ConcurrentNavigableMap<LogSequenceNumber, Integer> cutTillLimits =
      new ConcurrentSkipListMap<>();
  private final ScalableRWLock cuttingLock = new ScalableRWLock();

  private final ConcurrentLinkedQueue<RawPairLongObject<WALFile>> fileCloseQueue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger fileCloseQueueSize = new AtomicInteger();

  private final AtomicReference<CountDownLatch> flushLatch =
      new AtomicReference<>(new CountDownLatch(0));
  private volatile Future<?> writeFuture = null;

  private long lastFSyncTs = -1;
  private final int fsyncInterval;
  private volatile long segmentAdditionTs;

  private long currentPosition = 0;

  private boolean useFirstBuffer = true;

  private ByteBuffer writeBuffer = null;

  private Pointer writeBufferPointer = null;
  private int writeBufferPageIndex = -1;

  private final ByteBuffer writeBufferOne;
  private final Pointer writeBufferPointerOne;

  private final ByteBuffer writeBufferTwo;
  private final Pointer writeBufferPointerTwo;

  private LogSequenceNumber lastLSN = null;

  private final byte[] aesKey;
  private final byte[] iv;

  private final boolean callFsync;

  private final boolean printPerformanceStatistic;
  private final int statisticPrintInterval;

  private volatile long bytesWrittenSum = 0;
  private volatile long bytesWrittenTime = 0;

  private volatile long fsyncTime = 0;
  private volatile long fsyncCount = 0;

  private final LongAdder threadsWaitingSum = new LongAdder();
  private final LongAdder threadsWaitingCount = new LongAdder();

  private long reportTs = -1;

  public CASDiskWriteAheadLog(
      final String storageName,
      final Path storagePath,
      final Path walPath,
      final String walBaseName,
      final int maxPagesCacheSize,
      final int bufferSize,
      byte[] aesKey,
      byte[] iv,
      long segmentsInterval,
      final long maxSegmentSize,
      final int commitDelay,
      final boolean filterWALFiles,
      final Locale locale,
      final long walSizeHardLimit,
      final int fsyncInterval,
      boolean keepSingleWALSegment,
      boolean callFsync,
      boolean printPerformanceStatistic,
      int statisticPrintInterval)
      throws IOException {

    if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
      throw new InvalidStorageEncryptionKeyException(storageName,
          "Invalid length of the encryption key, provided size is " + aesKey.length);
    }

    if (aesKey != null && iv == null) {
      throw new InvalidStorageEncryptionKeyException(storageName, "IV can not be null");
    }

    this.keepSingleWALSegment = keepSingleWALSegment;
    this.aesKey = aesKey;
    this.iv = iv;

    var bufferSize1 = bufferSize * 1024 * 1024;

    this.segmentsInterval = segmentsInterval;
    this.callFsync = callFsync;
    this.printPerformanceStatistic = printPerformanceStatistic;
    this.statisticPrintInterval = statisticPrintInterval;

    this.fsyncInterval = fsyncInterval;

    walSizeLimit = walSizeHardLimit;

    this.walLocation = calculateWalPath(storagePath, walPath);

    if (!Files.exists(walLocation)) {
      Files.createDirectories(walLocation);
    }

    this.storageName = storageName;
    this.walBaseName = walBaseName;

    pageSize = CASWALPage.DEFAULT_PAGE_SIZE;
    maxRecordSize = CASWALPage.DEFAULT_MAX_RECORD_SIZE;
    this.diskWriteMeter = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .databaseMetric(CoreMetrics.DISK_WRITE_RATE, storageName);

    LogManager.instance()
        .info(
            this,
            "Page size for WAL located in %s is set to %d bytes. Base file name is %s",
            walLocation.toString(),
            pageSize, walBaseName);

    this.maxCacheSize =
        multiplyIntsWithOverflowDefault(maxPagesCacheSize, pageSize, DEFAULT_MAX_CACHE_SIZE);

    logSize.set(initSegmentSet(filterWALFiles, locale));

    final long nextSegmentId;

    if (segments.isEmpty()) {
      nextSegmentId = 1;
    } else {
      nextSegmentId = segments.last() + 1;
    }

    currentSegment = nextSegmentId;
    this.maxSegmentSize = Math.min(Integer.MAX_VALUE / 4, maxSegmentSize);
    this.segmentAdditionTs = System.nanoTime();

    // we log empty record on open so end of WAL will always contain valid value
    final var startRecord = new StartWALRecord();

    startRecord.setLsn(new LogSequenceNumber(currentSegment, CASWALPage.RECORDS_OFFSET));
    startRecord.setDistance(0);
    startRecord.setDiskSize(CASWALPage.RECORDS_OFFSET);

    records.offer(startRecord);

    writtenUpTo.set(new WrittenUpTo(new LogSequenceNumber(currentSegment, 0), 0));

    writeBufferPointerOne =
        allocator.allocate(bufferSize1, false, Intention.ALLOCATE_FIRST_WAL_BUFFER);
    writeBufferOne = writeBufferPointerOne.getNativeByteBuffer().order(ByteOrder.nativeOrder());
    assert writeBufferOne.position() == 0;

    writeBufferPointerTwo =
        allocator.allocate(bufferSize1, false, Intention.ALLOCATE_SECOND_WAL_BUFFER);
    writeBufferTwo = writeBufferPointerTwo.getNativeByteBuffer().order(ByteOrder.nativeOrder());
    assert writeBufferTwo.position() == 0;

    this.recordsWriterFuture =
        commitExecutor.scheduleWithFixedDelay(
            new RecordsWriter(this, false, false), commitDelay, commitDelay, TimeUnit.MILLISECONDS);

    log(new EmptyWALRecord());

    flush();
  }

  public int pageSize() {
    return pageSize;
  }

  int maxCacheSize() {
    return maxCacheSize;
  }

  private static int multiplyIntsWithOverflowDefault(
      final int maxPagesCacheSize,
      final int pageSize,
      @SuppressWarnings("SameParameterValue") final int defaultValue) {
    var maxCacheSize = (long) maxPagesCacheSize * (long) pageSize;
    if ((int) maxCacheSize != maxCacheSize) {
      return defaultValue;
    }
    return (int) maxCacheSize;
  }

  private long initSegmentSet(final boolean filterWALFiles, final Locale locale)
      throws IOException {
    final Stream<Path> walFiles;

    final var walSize = new ModifiableLong();
    if (filterWALFiles) {
      walFiles =
          Files.find(
              walLocation,
              1,
              (Path path, BasicFileAttributes attributes) ->
                  validateName(path.getFileName().toString(), walBaseName, locale));
    } else {
      walFiles =
          Files.find(
              walLocation,
              1,
              (Path path, BasicFileAttributes attrs) ->
                  validateSimpleName(path.getFileName().toString(), locale));
    }
    try {
      walFiles.forEach(
          (Path path) -> {
            segments.add(extractSegmentId(path.getFileName().toString()));
            walSize.increment(path.toFile().length());
          });
    } finally {
      walFiles.close();
    }

    return walSize.value;
  }

  private static long extractSegmentId(final String name) {
    final var matcher = Pattern.compile("^.*\\.(\\d+)\\.wal$").matcher(name);

    final var matches = matcher.find();
    assert matches;

    final var order = matcher.group(1);
    try {
      return Long.parseLong(order);
    } catch (final NumberFormatException e) {
      // never happen
      throw new IllegalStateException(e);
    }
  }

  private static boolean validateName(String name, String storageName, final Locale locale) {
    name = name.toLowerCase(locale);
    storageName = storageName.toLowerCase(locale);

    if (!name.endsWith(".wal")) {
      return false;
    }

    final var walOrderStartIndex = name.indexOf('.');
    if (walOrderStartIndex == name.length() - 4) {
      return false;
    }

    final var walStorageName = name.substring(0, walOrderStartIndex);
    if (!storageName.equals(walStorageName)) {
      return false;
    }

    final var walOrderEndIndex = name.indexOf('.', walOrderStartIndex + 1);

    final var walOrder = name.substring(walOrderStartIndex + 1, walOrderEndIndex);
    try {
      Integer.parseInt(walOrder);
    } catch (final NumberFormatException ignore) {
      return false;
    }

    return true;
  }

  private static boolean validateSimpleName(String name, final Locale locale) {
    name = name.toLowerCase(locale);

    if (!name.endsWith(".wal")) {
      return false;
    }

    final var walOrderStartIndex = name.indexOf('.');
    if (walOrderStartIndex == name.length() - 4) {
      return false;
    }

    final var walOrderEndIndex = name.indexOf('.', walOrderStartIndex + 1);

    final var walOrder = name.substring(walOrderStartIndex + 1, walOrderEndIndex);
    try {
      Integer.parseInt(walOrder);
    } catch (final NumberFormatException ignore) {
      return false;
    }

    return true;
  }

  private static Path calculateWalPath(final Path storagePath, final Path walPath) {
    if (walPath == null) {
      return storagePath;
    }

    return walPath;
  }

  @Override
  public List<WriteableWALRecord> read(final LogSequenceNumber lsn, final int limit)
      throws IOException {
    addCutTillLimit(lsn);
    try {
      final var begin = begin();
      final var endLSN = end.get();

      if (begin.compareTo(lsn) > 0) {
        return Collections.emptyList();
      }

      if (lsn.compareTo(endLSN) > 0) {
        return Collections.emptyList();
      }

      var recordCursor = records.peekFirst();
      assert recordCursor != null;
      var record = recordCursor.getItem();
      var logRecordLSN = record.getLsn();

      while (logRecordLSN.getPosition() > 0 && logRecordLSN.compareTo(lsn) <= 0) {
        while (true) {
          final var compare = logRecordLSN.compareTo(lsn);

          if (compare == 0 && record instanceof WriteableWALRecord) {
            return Collections.singletonList((WriteableWALRecord) record);
          }

          if (compare > 0) {
            return Collections.emptyList();
          }

          recordCursor = MPSCFAAArrayDequeue.next(recordCursor);
          if (recordCursor != null) {
            record = recordCursor.getItem();
            logRecordLSN = record.getLsn();

            if (logRecordLSN.getPosition() < 0) {
              return Collections.emptyList();
            }
          } else {
            recordCursor = records.peekFirst();
            assert recordCursor != null;
            record = recordCursor.getItem();
            logRecordLSN = record.getLsn();
            break;
          }
        }
      }

      // ensure that next record is written on disk
      var writtenLSN = this.writtenUpTo.get().getLsn();
      while (writtenLSN == null || writtenLSN.compareTo(lsn) < 0) {
        try {
          flushLatch.get().await();
        } catch (final InterruptedException e) {
          LogManager.instance().error(this, "WAL write was interrupted", e);
        }

        writtenLSN = this.writtenUpTo.get().getLsn();
        assert writtenLSN != null;

        if (writtenLSN.compareTo(lsn) < 0) {
          doFlush(false);
          waitTillWriteWillBeFinished();
        }

        writtenLSN = this.writtenUpTo.get().getLsn();
      }

      return readFromDisk(lsn, limit);
    } finally {
      removeCutTillLimit(lsn);
    }
  }

  private void waitTillWriteWillBeFinished() {
    final var wf = writeFuture;
    if (wf != null) {
      try {
        wf.get();
      } catch (final InterruptedException e) {
        throw BaseException.wrapException(
            new StorageException(storageName,
                "WAL write for storage " + storageName + " was interrupted"),
            e, storageName);
      } catch (final ExecutionException e) {
        throw BaseException.wrapException(
            new StorageException(storageName, "Error during WAL write for storage " + storageName),
            e, storageName);
      }
    }
  }

  long segSize() {
    return segmentSize.get();
  }

  long size() {
    return logSize.get();
  }

  private List<WriteableWALRecord> readFromDisk(final LogSequenceNumber lsn, final int limit)
      throws IOException {
    final List<WriteableWALRecord> result = new ArrayList<>();
    long position = lsn.getPosition();
    var pageIndex = position / pageSize;
    var segment = lsn.getSegment();

    var pagesRead = 0;

    final var segs = segments.tailSet(segment);
    if (segs.isEmpty() || segs.first() > segment) {
      return Collections.emptyList();
    }
    final var segmentsIterator = segs.iterator();

    while (pagesRead < BATCH_READ_SIZE) {
      if (segmentsIterator.hasNext()) {
        byte[] recordContent = null;
        var recordLen = -1;

        byte[] recordLenBytes = null;
        var recordLenRead = -1;

        var bytesRead = 0;

        var lsnPos = -1;

        segment = segmentsIterator.next();

        final var segmentName = getSegmentName(segment);
        final var segmentPath = walLocation.resolve(segmentName);

        if (Files.exists(segmentPath)) {
          try (final var file = WALFile.createReadWALFile(segmentPath, segmentId)) {
            var chSize = Files.size(segmentPath);
            final var written = this.writtenUpTo.get();

            if (segment == written.getLsn().getSegment()) {
              chSize = Math.min(chSize, written.getPosition());
            }

            var filePosition = file.position();

            while (pageIndex * pageSize < chSize) {
              var expectedFilePosition = pageIndex * pageSize;
              if (filePosition != expectedFilePosition) {
                file.position(expectedFilePosition);
                filePosition = expectedFilePosition;
              }

              final ByteBuffer buffer;
              buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());

              assert buffer.position() == 0;
              file.readBuffer(buffer);
              filePosition += buffer.position();

              pagesRead++;

              if (checkPageIsBrokenAndDecrypt(buffer, segment, pageIndex, pageSize)) {
                LogManager.instance()
                    .error(
                        this,
                        "WAL page %d of segment %s is broken, read of records will be stopped",
                        null,
                        pageIndex,
                        segmentName);
                return result;
              }

              buffer.position((int) (position - pageIndex * pageSize));
              while (buffer.remaining() > 0) {
                if (recordLen == -1) {
                  if (recordLenBytes == null) {
                    lsnPos = (int) (pageIndex * pageSize + buffer.position());

                    if (buffer.remaining() >= IntegerSerializer.INT_SIZE) {
                      recordLen = buffer.getInt();
                    } else {
                      recordLenBytes = new byte[IntegerSerializer.INT_SIZE];
                      recordLenRead = buffer.remaining();

                      buffer.get(recordLenBytes, 0, recordLenRead);
                      continue;
                    }
                  } else {
                    buffer.get(
                        recordLenBytes, recordLenRead, IntegerSerializer.INT_SIZE - recordLenRead);
                    recordLen = IntegerSerializer.deserializeNative(recordLenBytes, 0);
                  }

                  if (recordLen == 0) {
                    // end of page is reached
                    recordLen = -1;
                    recordLenBytes = null;
                    recordLenRead = -1;

                    break;
                  }

                  recordContent = new byte[recordLen];
                }

                final var bytesToRead = Math.min(recordLen - bytesRead, buffer.remaining());
                buffer.get(recordContent, bytesRead, bytesToRead);
                bytesRead += bytesToRead;

                if (bytesRead == recordLen) {
                  final var walRecord =
                      WALRecordsFactory.INSTANCE.fromStream(recordContent);

                  walRecord.setLsn(new LogSequenceNumber(segment, lsnPos));

                  recordContent = null;
                  bytesRead = 0;
                  recordLen = -1;

                  recordLenBytes = null;
                  recordLenRead = -1;

                  result.add(walRecord);

                  if (result.size() == limit) {
                    return result;
                  }
                }
              }

              pageIndex++;
              position = pageIndex * pageSize + CASWALPage.RECORDS_OFFSET;
            }

            // we can jump to a new segment and skip and of the current file because of thread
            // racing
            // so we stop here to start to read from next batch
            if (segment == written.getLsn().getSegment()) {
              break;
            }
          }
        } else {
          break;
        }

        pageIndex = 0;
        position = CASWALPage.RECORDS_OFFSET;
      } else {
        break;
      }
    }

    return result;
  }

  @Override
  public List<WriteableWALRecord> next(final LogSequenceNumber lsn, final int limit)
      throws IOException {
    addCutTillLimit(lsn);
    try {
      final var begin = begin();

      if (begin.compareTo(lsn) > 0) {
        return Collections.emptyList();
      }

      final var end = this.end.get();
      if (lsn.compareTo(end) >= 0) {
        return Collections.emptyList();
      }

      var recordCursor = records.peekFirst();

      assert recordCursor != null;
      var logRecord = recordCursor.getItem();
      var logRecordLSN = logRecord.getLsn();

      while (logRecordLSN.getPosition() >= 0 && logRecordLSN.compareTo(lsn) <= 0) {
        while (true) {
          final var compare = logRecordLSN.compareTo(lsn);

          if (compare == 0) {
            recordCursor = MPSCFAAArrayDequeue.next(recordCursor);

            while (recordCursor != null) {
              final var nextRecord = recordCursor.getItem();

              if (nextRecord instanceof WriteableWALRecord) {
                final var nextLSN = nextRecord.getLsn();

                if (nextLSN.getPosition() < 0) {
                  return Collections.emptyList();
                }

                if (nextLSN.compareTo(lsn) > 0) {
                  return Collections.singletonList((WriteableWALRecord) nextRecord);
                } else {
                  assert nextLSN.compareTo(lsn) == 0;
                }
              }

              recordCursor = MPSCFAAArrayDequeue.next(recordCursor);
            }

            recordCursor = records.peekFirst();
            assert recordCursor != null;
            logRecord = recordCursor.getItem();
            logRecordLSN = logRecord.getLsn();
            break;
          } else if (compare < 0) {
            recordCursor = MPSCFAAArrayDequeue.next(recordCursor);

            if (recordCursor != null) {
              logRecord = recordCursor.getItem();
              logRecordLSN = logRecord.getLsn();

              assert logRecordLSN.getPosition() >= 0;
            } else {
              recordCursor = records.peekFirst();
              assert recordCursor != null;
              logRecord = recordCursor.getItem();
              logRecordLSN = logRecord.getLsn();

              break;
            }
          } else {
            throw new IllegalArgumentException("Invalid LSN was passed " + lsn);
          }
        }
      }

      // ensure that next record is written on disk
      var writtenLSN = this.writtenUpTo.get().getLsn();
      while (writtenLSN == null || writtenLSN.compareTo(lsn) <= 0) {
        try {
          flushLatch.get().await();
        } catch (final InterruptedException e) {
          LogManager.instance().error(this, "WAL write was interrupted", e);
        }

        writtenLSN = this.writtenUpTo.get().getLsn();
        assert writtenLSN != null;

        if (writtenLSN.compareTo(lsn) <= 0) {
          doFlush(false);

          waitTillWriteWillBeFinished();
        }
        writtenLSN = this.writtenUpTo.get().getLsn();
      }

      final List<WriteableWALRecord> result;
      if (limit <= 0) {
        result = readFromDisk(lsn, 0);
      } else {
        result = readFromDisk(lsn, limit + 1);
      }
      if (result.isEmpty()) {
        return result;
      }
      return result.subList(1, result.size());
    } finally {
      removeCutTillLimit(lsn);
    }
  }

  @Override
  public void addEventAt(final LogSequenceNumber lsn, final Runnable event) {
    // may be executed by multiple threads simultaneously

    final var localFlushedLsn = flushedLSN;

    final var wrapper = new EventWrapper(event);

    if (localFlushedLsn != null && lsn.compareTo(localFlushedLsn) <= 0) {
      event.run();
    } else {
      final var eventWrapper = events.put(lsn, wrapper);
      if (eventWrapper != null) {
        throw new IllegalStateException(
            "It is impossible to have several wrappers bound to the same LSN - lsn = " + lsn);
      }

      final var potentiallyUpdatedLocalFlushedLsn = flushedLSN;
      if (potentiallyUpdatedLocalFlushedLsn != null
          && lsn.compareTo(potentiallyUpdatedLocalFlushedLsn) <= 0) {
        commitExecutor.execute(() -> fireEventsFor(potentiallyUpdatedLocalFlushedLsn));
      }
    }
  }

  @Override
  public void delete() throws IOException {
    final var segmentsToDelete = new LongArrayList(this.segments.size());
    segmentsToDelete.addAll(segments);

    close(false);

    for (var i = 0; i < segmentsToDelete.size(); i++) {
      final var segment = segmentsToDelete.getLong(i);
      final var segmentName = getSegmentName(segment);
      final var segmentPath = walLocation.resolve(segmentName);
      Files.deleteIfExists(segmentPath);
    }
  }

  private boolean checkPageIsBrokenAndDecrypt(
      final ByteBuffer buffer, final long segmentId, final long pageIndex, final int walPageSize) {
    if (buffer.position() < CASWALPage.RECORDS_OFFSET) {
      return true;
    }

    final var magicNumber = buffer.getLong(CASWALPage.MAGIC_NUMBER_OFFSET);

    if (magicNumber != CASWALPage.MAGIC_NUMBER
        && magicNumber != CASWALPage.MAGIC_NUMBER_WITH_ENCRYPTION) {
      return true;
    }

    if (magicNumber == CASWALPage.MAGIC_NUMBER_WITH_ENCRYPTION) {
      if (aesKey == null) {
        throw new EncryptionKeyAbsentException(storageName,
            "Can not decrypt WAL page because decryption key is absent.");
      }

      doEncryptionDecryption(segmentId, pageIndex, Cipher.DECRYPT_MODE, 0, this.pageSize, buffer);
    }

    final int pageSize = buffer.getShort(CASWALPage.PAGE_SIZE_OFFSET);
    if (pageSize <= 0 || pageSize > walPageSize) {
      return true;
    }

    buffer.limit(pageSize);

    buffer.position(CASWALPage.RECORDS_OFFSET);
    final var hash64 = xxHashFactory.hash64();

    final var hash = hash64.hash(buffer, XX_SEED);

    return hash != buffer.getLong(CASWALPage.XX_OFFSET);
  }

  @Override
  public void addCutTillLimit(final LogSequenceNumber lsn) {
    if (lsn == null) {
      throw new NullPointerException();
    }

    cuttingLock.sharedLock();
    try {
      cutTillLimits.merge(lsn, 1, Integer::sum);
    } finally {
      cuttingLock.sharedUnlock();
    }
  }

  @Override
  public void removeCutTillLimit(final LogSequenceNumber lsn) {
    if (lsn == null) {
      throw new NullPointerException();
    }

    cuttingLock.sharedLock();
    try {
      cutTillLimits.compute(
          lsn,
          (key, oldCounter) -> {
            if (oldCounter == null) {
              throw new IllegalArgumentException(
                  String.format("Limit %s is going to be removed but it was not added", lsn));
            }

            final var newCounter = oldCounter - 1;
            if (newCounter == 0) {
              return null;
            }

            return newCounter;
          });
    } finally {
      cuttingLock.sharedUnlock();
    }
  }

  @Override
  public LogSequenceNumber logAtomicOperationStartRecord(
      final boolean isRollbackSupported, final long unitId) {
    final var record = new AtomicUnitStartRecord(isRollbackSupported, unitId);
    return log(record);
  }

  @Override
  public LogSequenceNumber log(final WriteableWALRecord writeableRecord) {
    if (recordsWriterFuture.isDone()) {
      try {
        recordsWriterFuture.get();
      } catch (final InterruptedException interruptedException) {
        throw BaseException.wrapException(
            new StorageException(storageName,
                "WAL records write task for storage '" + storageName + "'  was interrupted"),
            interruptedException, storageName);
      } catch (ExecutionException executionException) {
        throw BaseException.wrapException(
            new StorageException(storageName,
                "WAL records write task for storage '" + storageName + "' was finished with error"),
            executionException, storageName);
      }

      throw new StorageException(storageName,
          "WAL records write task for storage '" + storageName + "' was unexpectedly finished");
    }

    final long segSize;
    final long size;
    final LogSequenceNumber recordLSN;

    long logSegment;
    segmentLock.sharedLock();
    try {
      logSegment = currentSegment;
      recordLSN = doLogRecord(writeableRecord);

      final var diskSize = writeableRecord.getDiskSize();
      segSize = segmentSize.addAndGet(diskSize);
      size = logSize.addAndGet(diskSize);

      if (segSize == diskSize) {
        segments.add(currentSegment);
      }
    } finally {
      segmentLock.sharedUnlock();
    }

    var qsize = queueSize.addAndGet(writeableRecord.getDiskSize());
    if (qsize >= maxCacheSize) {
      threadsWaitingCount.increment();
      try {
        long startTs = 0;
        if (printPerformanceStatistic) {
          startTs = System.nanoTime();
        }
        flushLatch.get().await();
        if (printPerformanceStatistic) {
          final var endTs = System.nanoTime();
          threadsWaitingSum.add(endTs - startTs);
        }
      } catch (final InterruptedException e) {
        LogManager.instance().error(this, "WAL write was interrupted", e);
      }

      qsize = queueSize.get();

      if (qsize >= maxCacheSize) {
        long startTs = 0;
        if (printPerformanceStatistic) {
          startTs = System.nanoTime();
        }
        doFlush(false);
        if (printPerformanceStatistic) {
          final var endTs = System.nanoTime();
          threadsWaitingSum.add(endTs - startTs);
        }
      }
    }

    if (keepSingleWALSegment && segments.size() > 1) {
      for (final var listener : checkpointRequestListeners) {
        listener.requestCheckpoint();
      }
    } else if (walSizeLimit > -1 && size > walSizeLimit && segments.size() > 1) {
      for (final var listener : checkpointRequestListeners) {
        listener.requestCheckpoint();
      }
    }

    if (segSize > maxSegmentSize) {
      appendSegment(logSegment + 1);
    }

    return recordLSN;
  }

  @Override
  public LogSequenceNumber begin() {
    final long first = segments.first();
    return new LogSequenceNumber(first, CASWALPage.RECORDS_OFFSET);
  }

  @Override
  @Nullable
  public LogSequenceNumber begin(final long segmentId) {
    if (segments.contains(segmentId)) {
      return new LogSequenceNumber(segmentId, CASWALPage.RECORDS_OFFSET);
    }

    return null;
  }

  @Override
  public boolean cutAllSegmentsSmallerThan(long segmentId) throws IOException {
    cuttingLock.exclusiveLock();
    try {
      segmentLock.sharedLock();
      try {
        if (segmentId > currentSegment) {
          segmentId = currentSegment;
        }

        final var firsEntry = cutTillLimits.firstEntry();

        if (firsEntry != null) {
          if (segmentId > firsEntry.getKey().getSegment()) {
            segmentId = firsEntry.getKey().getSegment();
          }
        }

        final var written = writtenUpTo.get().getLsn();
        if (segmentId > written.getSegment()) {
          segmentId = written.getSegment();
        }

        if (segmentId <= segments.first()) {
          return false;
        }

        var pair = fileCloseQueue.poll();
        while (pair != null) {
          final var file = pair.second;

          fileCloseQueueSize.decrementAndGet();
          if (pair.first >= segmentId) {
            if (callFsync) {
              file.force(true);
            }

            file.close();
            break;
          } else {
            file.close();
          }
          pair = fileCloseQueue.poll();
        }

        var removed = false;

        final var segmentIterator = segments.iterator();
        while (segmentIterator.hasNext()) {
          final long segment = segmentIterator.next();
          if (segment < segmentId) {
            segmentIterator.remove();

            final var segmentName = getSegmentName(segment);
            final var segmentPath = walLocation.resolve(segmentName);
            if (Files.exists(segmentPath)) {
              final var length = Files.size(segmentPath);
              Files.delete(segmentPath);
              logSize.addAndGet(-length);
              removed = true;
            }
          } else {
            break;
          }
        }

        return removed;
      } finally {
        segmentLock.sharedUnlock();
      }
    } finally {
      cuttingLock.exclusiveUnlock();
    }
  }

  @Override
  public boolean cutTill(final LogSequenceNumber lsn) throws IOException {
    final var segmentId = lsn.getSegment();
    return cutAllSegmentsSmallerThan(segmentId);
  }

  @Override
  public long activeSegment() {
    return currentSegment;
  }

  @Override
  public boolean appendNewSegment() {
    segmentLock.exclusiveLock();
    try {
      //noinspection NonAtomicOperationOnVolatileField
      currentSegment++;
      segmentSize.set(0);

      logMilestoneRecord();

      segmentAdditionTs = System.nanoTime();
    } finally {
      segmentLock.exclusiveUnlock();
    }

    // we need to have at least one record in a segment to preserve operation id
    log(new EmptyWALRecord());

    return true;
  }

  public void appendSegment(final long segmentIndex) {
    if (segmentIndex <= currentSegment) {
      return;
    }

    segmentLock.exclusiveLock();
    try {
      if (segmentIndex <= currentSegment) {
        return;
      }

      currentSegment = segmentIndex;
      segmentSize.set(0);

      logMilestoneRecord();

      segmentAdditionTs = System.nanoTime();
    } finally {
      segmentLock.exclusiveUnlock();
    }
  }

  @Override
  public void moveLsnAfter(final LogSequenceNumber lsn) {
    final var segment = lsn.getSegment() + 1;
    appendSegment(segment);
  }

  @Override
  public long[] nonActiveSegments() {
    final var writtenUpTo = this.writtenUpTo.get().getLsn();

    var maxSegment = currentSegment;

    if (writtenUpTo.getSegment() < maxSegment) {
      maxSegment = writtenUpTo.getSegment();
    }

    final var result = new LongArrayList();
    for (final long segment : segments) {
      if (segment < maxSegment) {
        result.add(segment);
      } else {
        break;
      }
    }

    return result.toLongArray();
  }

  @Override
  public File[] nonActiveSegments(final long fromSegment) {
    final var maxSegment = currentSegment;
    final List<File> result = new ArrayList<>(8);

    for (final long segment : segments.tailSet(fromSegment)) {
      if (segment < maxSegment) {
        final var segmentName = getSegmentName(segment);
        final var segmentPath = walLocation.resolve(segmentName);

        final var segFile = segmentPath.toFile();
        if (segFile.exists()) {
          result.add(segFile);
        }
      } else {
        break;
      }
    }

    return result.toArray(new File[0]);
  }

  private LogSequenceNumber doLogRecord(final WriteableWALRecord writeableRecord) {
    ByteBuffer serializedRecord;
    if (writeableRecord.getBinaryContentLen() < 0) {
      serializedRecord = WALRecordsFactory.toStream(writeableRecord);
      writeableRecord.setBinaryContent(serializedRecord);
    }

    writeableRecord.setLsn(new LogSequenceNumber(currentSegment, -1));

    records.offer(writeableRecord);

    calculateRecordsLSNs();

    final var recordLSN = writeableRecord.getLsn();

    var endLsn = end.get();
    while (endLsn == null || recordLSN.compareTo(endLsn) > 0) {
      if (end.compareAndSet(endLsn, recordLSN)) {
        break;
      }

      endLsn = end.get();
    }

    return recordLSN;
  }

  @Override
  public void flush() {
    doFlush(true);
    waitTillWriteWillBeFinished();
  }

  @Override
  public void close() throws IOException {
    close(true);
  }

  @Override
  public void close(final boolean flush) throws IOException {
    if (flush) {
      doFlush(true);
    }

    if (!recordsWriterFuture.cancel(false) && !recordsWriterFuture.isDone()) {
      throw new StorageException(storageName, "Can not cancel background write thread in WAL");
    }

    cancelRecordsWriting = true;
    try {
      recordsWriterFuture.get();
    } catch (CancellationException e) {
      // ignore, we canceled scheduled execution
    } catch (InterruptedException | ExecutionException e) {
      throw BaseException.wrapException(
          new StorageException(storageName,
              "Error during writing of WAL records in storage " + storageName),
          e, storageName);
    }

    recordsWriterLock.lock();
    try {
      final var future = writeFuture;
      if (future != null) {
        try {
          future.get();
        } catch (InterruptedException | ExecutionException e) {
          throw BaseException.wrapException(
              new StorageException(storageName,
                  "Error during writing of WAL records in storage " + storageName),
              e, storageName);
        }
      }

      var record = records.poll();
      while (record != null) {
        if (record instanceof WriteableWALRecord) {
          ((WriteableWALRecord) record).freeBinaryContent();
        }

        record = records.poll();
      }

      for (var pair : fileCloseQueue) {
        final var file = pair.second;

        if (callFsync) {
          file.force(true);
        }

        file.close();
      }

      fileCloseQueueSize.set(0);

      if (walFile != null) {
        if (callFsync) {
          walFile.force(true);
        }

        walFile.close();
      }

      segments.clear();
      fileCloseQueue.clear();

      allocator.deallocate(writeBufferPointerOne);
      allocator.deallocate(writeBufferPointerTwo);

      if (writeBufferPointer != null) {
        writeBufferPointer = null;
        writeBuffer = null;
        writeBufferPageIndex = -1;
      }
    } finally {
      recordsWriterLock.unlock();
    }
  }

  @Override
  public void addCheckpointListener(final CheckpointRequestListener listener) {
    checkpointRequestListeners.add(listener);
  }

  @Override
  public void removeCheckpointListener(final CheckpointRequestListener listener) {
    final List<CheckpointRequestListener> itemsToRemove = new ArrayList<>();

    for (final var fullCheckpointRequestListener :
        checkpointRequestListeners) {
      if (fullCheckpointRequestListener.equals(listener)) {
        itemsToRemove.add(fullCheckpointRequestListener);
      }
    }

    checkpointRequestListeners.removeAll(itemsToRemove);
  }

  private void doFlush(final boolean forceSync) {
    final var future = commitExecutor.submit(new RecordsWriter(this, forceSync, true));
    try {
      future.get();
    } catch (final Exception e) {
      LogManager.instance().error(this, "Exception during WAL flush", e);
      throw new IllegalStateException(e);
    }
  }

  @Override
  public LogSequenceNumber getFlushedLsn() {
    return flushedLSN;
  }

  private void doEncryptionDecryption(
      final long segmentId,
      final long pageIndex,
      final int mode,
      final int start,
      final int pageSize,
      final ByteBuffer buffer) {
    try {
      final var cipher = CIPHER.get();
      final SecretKey aesKey = new SecretKeySpec(this.aesKey, ALGORITHM_NAME);

      final var updatedIv = new byte[iv.length];

      for (var i = 0; i < LongSerializer.LONG_SIZE; i++) {
        updatedIv[i] = (byte) (iv[i] ^ ((pageIndex >>> i) & 0xFF));
      }

      for (var i = 0; i < LongSerializer.LONG_SIZE; i++) {
        updatedIv[i + LongSerializer.LONG_SIZE] =
            (byte) (iv[i + LongSerializer.LONG_SIZE] ^ ((segmentId >>> i) & 0xFF));
      }

      cipher.init(mode, aesKey, new IvParameterSpec(updatedIv));

      final var outBuffer =
          ByteBuffer.allocate(pageSize - CASWALPage.XX_OFFSET).order(ByteOrder.nativeOrder());

      buffer.position(start + CASWALPage.XX_OFFSET);
      cipher.doFinal(buffer, outBuffer);

      buffer.position(start + CASWALPage.XX_OFFSET);
      outBuffer.position(0);
      buffer.put(outBuffer);

    } catch (InvalidKeyException e) {
      throw BaseException.wrapException(
          new InvalidStorageEncryptionKeyException(storageName, e.getMessage()),
          e, storageName);
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalArgumentException("Invalid IV.", e);
    } catch (IllegalBlockSizeException | BadPaddingException | ShortBufferException e) {
      throw new IllegalStateException("Unexpected exception during CRT encryption.", e);
    }
  }

  private void calculateRecordsLSNs() {
    final List<WALRecord> unassignedList = new ArrayList<>();

    var cursor = records.peekLast();
    while (cursor != null) {
      final var record = cursor.getItem();

      final var lsn = record.getLsn();

      if (lsn.getPosition() == -1) {
        unassignedList.add(record);
      } else {
        unassignedList.add(record);
        break;
      }

      final var nextCursor = MPSCFAAArrayDequeue.prev(cursor);
      if (nextCursor == null && record.getLsn().getPosition() < 0) {
        LogManager.instance().warn(this, cursor.toString());
        throw new IllegalStateException("Invalid last record");
      }

      cursor = nextCursor;
    }

    if (!unassignedList.isEmpty()) {
      final var unassignedRecordsIterator =
          unassignedList.listIterator(unassignedList.size());

      var prevRecord = unassignedRecordsIterator.previous();
      final var prevLSN = prevRecord.getLsn();

      if (prevLSN.getPosition() < 0) {
        throw new IllegalStateException(
            "There should be at least one record in the queue which has valid position");
      }

      while (unassignedRecordsIterator.hasPrevious()) {
        final var record = unassignedRecordsIterator.previous();
        var lsn = record.getLsn();

        if (lsn.getPosition() < 0) {
          final var position = calculatePosition(record, prevRecord, pageSize, maxRecordSize);
          final var newLSN = new LogSequenceNumber(lsn.getSegment(), position);

          lsn = record.getLsn();
          if (lsn.getPosition() < 0) {
            record.setLsn(newLSN);
          }
        }

        prevRecord = record;
      }
    }
  }

  private MilestoneWALRecord logMilestoneRecord() {
    final var milestoneRecord = new MilestoneWALRecord();
    milestoneRecord.setLsn(new LogSequenceNumber(currentSegment, -1));

    records.offer(milestoneRecord);

    calculateRecordsLSNs();

    return milestoneRecord;
  }

  @Override
  public LogSequenceNumber end() {
    return end.get();
  }

  private static int calculatePosition(
      final WALRecord record, final WALRecord prevRecord, int pageSize, int maxRecordSize) {
    assert prevRecord.getLsn().getSegment() <= record.getLsn().getSegment()
        : "prev segment "
        + prevRecord.getLsn().getSegment()
        + " segment "
        + record.getLsn().getSegment();

    if (prevRecord instanceof StartWALRecord) {
      assert prevRecord.getLsn().getSegment() == record.getLsn().getSegment();

      if (record instanceof MilestoneWALRecord) {
        record.setDistance(0);
        record.setDiskSize(prevRecord.getDiskSize());
      } else {
        final var recordLength = ((WriteableWALRecord) record).getBinaryContentLen();
        final var length = CASWALPage.calculateSerializedSize(recordLength);

        final var pages = length / maxRecordSize;
        final var offset = length - pages * maxRecordSize;

        final int distance;
        if (pages == 0) {
          distance = length;
        } else {
          distance = (pages - 1) * pageSize + offset + maxRecordSize + CASWALPage.RECORDS_OFFSET;
        }

        record.setDistance(distance);
        record.setDiskSize(distance + prevRecord.getDiskSize());
      }

      return prevRecord.getLsn().getPosition();
    }

    if (prevRecord instanceof MilestoneWALRecord) {
      if (record instanceof MilestoneWALRecord) {
        record.setDistance(0);
        // repeat previous record disk size so it will be used in first writable record
        if (prevRecord.getLsn().getSegment() == record.getLsn().getSegment()) {
          record.setDiskSize(prevRecord.getDiskSize());
          return prevRecord.getLsn().getPosition();
        }

        record.setDiskSize(prevRecord.getDiskSize());
        return CASWALPage.RECORDS_OFFSET;
      } else {
        // we always start from the begging of the page so no need to calculate page offset
        // record is written from the begging of page
        final var recordLength = ((WriteableWALRecord) record).getBinaryContentLen();
        final var length = CASWALPage.calculateSerializedSize(recordLength);

        final var pages = length / maxRecordSize;
        final var offset = length - pages * maxRecordSize;

        final int distance;
        if (pages == 0) {
          distance = length;
        } else {
          distance = (pages - 1) * pageSize + offset + maxRecordSize + CASWALPage.RECORDS_OFFSET;
        }

        record.setDistance(distance);

        final int disksize;

        if (offset == 0) {
          disksize = distance - CASWALPage.RECORDS_OFFSET;
        } else {
          disksize = distance;
        }

        assert prevRecord.getLsn().getSegment() == record.getLsn().getSegment();
        record.setDiskSize(disksize + prevRecord.getDiskSize());
      }

      return prevRecord.getLsn().getPosition();
    }

    if (record instanceof MilestoneWALRecord) {
      if (prevRecord.getLsn().getSegment() == record.getLsn().getSegment()) {
        final long end = prevRecord.getLsn().getPosition() + prevRecord.getDistance();
        final var pageIndex = end / pageSize;

        final long newPosition;
        final var pageOffset = (int) (end - pageIndex * pageSize);

        if (pageOffset > CASWALPage.RECORDS_OFFSET) {
          newPosition = (pageIndex + 1) * pageSize + CASWALPage.RECORDS_OFFSET;
          record.setDiskSize((int) ((pageIndex + 1) * pageSize - end) + CASWALPage.RECORDS_OFFSET);
        } else {
          newPosition = end;
          record.setDiskSize(CASWALPage.RECORDS_OFFSET);
        }

        record.setDistance(0);

        return (int) newPosition;
      } else {
        final long prevPosition = prevRecord.getLsn().getPosition();
        final var end = prevPosition + prevRecord.getDistance();
        final var pageIndex = end / pageSize;
        final var pageOffset = (int) (end - pageIndex * pageSize);

        if (pageOffset == CASWALPage.RECORDS_OFFSET) {
          record.setDiskSize(CASWALPage.RECORDS_OFFSET);
        } else {
          final var pageFreeSpace = pageSize - pageOffset;
          record.setDiskSize(pageFreeSpace + CASWALPage.RECORDS_OFFSET);
        }

        record.setDistance(0);

        return CASWALPage.RECORDS_OFFSET;
      }
    }

    assert prevRecord.getLsn().getSegment() == record.getLsn().getSegment();
    final long start = prevRecord.getDistance() + prevRecord.getLsn().getPosition();
    final var freeSpace = pageSize - (int) (start % pageSize);
    final var startOffset = pageSize - freeSpace;

    final var recordLength = ((WriteableWALRecord) record).getBinaryContentLen();
    var length = CASWALPage.calculateSerializedSize(recordLength);

    if (length < freeSpace) {
      record.setDistance(length);

      if (startOffset == CASWALPage.RECORDS_OFFSET) {
        record.setDiskSize(length + CASWALPage.RECORDS_OFFSET);
      } else {
        record.setDiskSize(length);
      }

    } else {
      length -= freeSpace;

      @SuppressWarnings("UnnecessaryLocalVariable") final var firstChunk = freeSpace;
      final var pages = length / maxRecordSize;
      final var offset = length - pages * maxRecordSize;

      final var distance = firstChunk + pages * pageSize + offset + CASWALPage.RECORDS_OFFSET;
      record.setDistance(distance);

      var diskSize = distance;

      if (offset == 0) {
        diskSize -= CASWALPage.RECORDS_OFFSET;
      }

      if (startOffset == CASWALPage.RECORDS_OFFSET) {
        diskSize += CASWALPage.RECORDS_OFFSET;
      }

      record.setDiskSize(diskSize);
    }

    return (int) start;
  }

  private void fireEventsFor(final LogSequenceNumber lsn) {
    // may be executed by only one thread at every instant of time

    final var eventsToFire = events.headMap(lsn, true).values().iterator();
    while (eventsToFire.hasNext()) {
      eventsToFire.next().fire();
      eventsToFire.remove();
    }
  }

  private String getSegmentName(final long segment) {
    return walBaseName + "." + segment + WAL_SEGMENT_EXTENSION;
  }

  public void executeWriteRecords(boolean forceSync, boolean fullWrite) {
    recordsWriterLock.lock();
    try {
      if (cancelRecordsWriting) {
        return;
      }

      if (printPerformanceStatistic) {
        printReport();
      }

      final var ts = System.nanoTime();
      final var makeFSync = forceSync || ts - lastFSyncTs > fsyncInterval * 1_000_000L;
      final var qSize = queueSize.get();

      // even if queue is empty we need to write buffer content to the disk if needed
      if (qSize > 0 || fullWrite || makeFSync) {
        final var fl = new CountDownLatch(1);
        flushLatch.lazySet(fl);
        try {
          // in case of "full write" mode, we log milestone record and iterate over the queue till
          // we find it
          final MilestoneWALRecord milestoneRecord;
          // in case of "full cache" mode we chose last record in the queue, iterate till this
          // record and write it if needed
          // but do not remove this record from the queue, so we will always have queue with
          // record with valid LSN
          // if we write last record, we mark it as written, so we do not repeat that again
          final WALRecord lastRecord;

          // we jump to new page if we need to make fsync or we need to be sure that records are
          // written in file system
          if (makeFSync || fullWrite) {
            segmentLock.sharedLock();
            try {
              milestoneRecord = logMilestoneRecord();
            } finally {
              segmentLock.sharedUnlock();
            }

            lastRecord = null;
          } else {

            final var cursor = records.peekLast();
            assert cursor != null;

            lastRecord = cursor.getItem();
            assert lastRecord != null;

            if (lastRecord.getLsn().getPosition() == -1) {
              calculateRecordsLSNs();
            }

            assert lastRecord.getLsn().getPosition() >= 0;
            milestoneRecord = null;
          }

          while (true) {
            final var record = records.peek();

            if (record == milestoneRecord) {
              break;
            }

            assert record != null;
            final var lsn = record.getLsn();

            assert lsn.getSegment() >= segmentId;

            if (!(record instanceof MilestoneWALRecord) && !(record instanceof StartWALRecord)) {
              if (segmentId != lsn.getSegment()) {
                if (walFile != null) {
                  if (writeBufferPointer != null) {
                    writeBuffer(walFile, segmentId, writeBuffer, lastLSN);
                  }

                  writeBufferPointer = null;
                  writeBuffer = null;
                  writeBufferPageIndex = -1;

                  lastLSN = null;

                  try {
                    if (writeFuture != null) {
                      writeFuture.get();
                    }
                  } catch (final InterruptedException e) {
                    LogManager.instance().error(this, "WAL write was interrupted", e);
                  }

                  assert walFile.position() == currentPosition;

                  fileCloseQueueSize.incrementAndGet();
                  fileCloseQueue.offer(new RawPairLongObject<>(segmentId, walFile));
                }

                segmentId = lsn.getSegment();

                walFile =
                    WALFile.createWriteWALFile(
                        walLocation.resolve(getSegmentName(segmentId)), segmentId);
                assert lsn.getPosition() == CASWALPage.RECORDS_OFFSET;
                currentPosition = 0;
              }

              final var writeableRecord = (WriteableWALRecord) record;

              if (!writeableRecord.isWritten()) {
                var written = 0;
                final var recordContentBinarySize = writeableRecord.getBinaryContentLen();
                final var bytesToWrite = IntegerSerializer.INT_SIZE + recordContentBinarySize;

                final var recordContent = writeableRecord.getBinaryContent();
                recordContent.position(0);

                byte[] recordSize = null;
                var recordSizeWritten = -1;

                var recordSizeIsWritten = false;

                while (written < bytesToWrite) {
                  if (writeBuffer == null || writeBuffer.remaining() == 0) {
                    if (writeBufferPointer != null) {
                      assert writeBuffer != null;
                      writeBuffer(walFile, segmentId, writeBuffer, lastLSN);
                    }

                    if (useFirstBuffer) {
                      writeBufferPointer = writeBufferPointerOne;
                      writeBuffer = writeBufferOne;
                    } else {
                      writeBufferPointer = writeBufferPointerTwo;
                      writeBuffer = writeBufferTwo;
                    }

                    writeBuffer.limit(writeBuffer.capacity());
                    writeBuffer.rewind();
                    useFirstBuffer = !useFirstBuffer;

                    writeBufferPageIndex = -1;

                    lastLSN = null;
                  }

                  if (writeBuffer.position() % pageSize == 0) {
                    writeBufferPageIndex++;
                    writeBuffer.position(writeBuffer.position() + CASWALPage.RECORDS_OFFSET);
                  }

                  assert written != 0
                      || currentPosition + writeBuffer.position() == lsn.getPosition()
                      : (currentPosition + writeBuffer.position()) + " vs " + lsn.getPosition();
                  final var chunkSize =
                      Math.min(
                          bytesToWrite - written,
                          (writeBufferPageIndex + 1) * pageSize - writeBuffer.position());
                  assert chunkSize <= maxRecordSize;
                  assert chunkSize + writeBuffer.position()
                      <= (writeBufferPageIndex + 1) * pageSize;
                  assert writeBuffer.position() > writeBufferPageIndex * pageSize;

                  if (!recordSizeIsWritten) {
                    if (recordSizeWritten > 0) {
                      writeBuffer.put(
                          recordSize,
                          recordSizeWritten,
                          IntegerSerializer.INT_SIZE - recordSizeWritten);
                      written += IntegerSerializer.INT_SIZE - recordSizeWritten;

                      recordSize = null;
                      recordSizeWritten = -1;
                      recordSizeIsWritten = true;
                      continue;
                    } else if (IntegerSerializer.INT_SIZE <= chunkSize) {
                      writeBuffer.putInt(recordContentBinarySize);
                      written += IntegerSerializer.INT_SIZE;

                      recordSize = null;
                      recordSizeWritten = -1;
                      recordSizeIsWritten = true;
                      continue;
                    } else {
                      recordSize = new byte[IntegerSerializer.INT_SIZE];
                      IntegerSerializer.serializeNative(
                          recordContentBinarySize, recordSize, 0);

                      recordSizeWritten =
                          (writeBufferPageIndex + 1) * pageSize - writeBuffer.position();
                      written += recordSizeWritten;

                      writeBuffer.put(recordSize, 0, recordSizeWritten);
                      continue;
                    }
                  }

                  recordContent.limit(recordContent.position() + chunkSize);
                  writeBuffer.put(recordContent);
                  written += chunkSize;
                }

                lastLSN = lsn;

                queueSize.addAndGet(-writeableRecord.getDiskSize());
                writeableRecord.written();
                writeableRecord.freeBinaryContent();
              }
            }
            if (lastRecord != record) {
              records.poll();
            } else {
              break;
            }
          }

          if ((makeFSync || fullWrite) && writeBufferPointer != null) {
            writeBuffer(walFile, segmentId, writeBuffer, lastLSN);

            writeBufferPointer = null;
            writeBuffer = null;
            writeBufferPageIndex = -1;

            lastLSN = null;
          }
        } finally {
          fl.countDown();
        }

        if (qSize > 0 && ts - segmentAdditionTs >= segmentsInterval) {
          appendSegment(currentSegment);
        }
      }

      if (makeFSync) {
        try {
          try {
            if (writeFuture != null) {
              writeFuture.get();
            }
          } catch (final InterruptedException e) {
            LogManager.instance().error(this, "WAL write was interrupted", e);
          }

          assert walFile == null || walFile.position() == currentPosition;

          writeFuture =
              writeExecutor.submit(
                  (Callable<?>)
                      () -> {
                        executeSyncAndCloseFile();
                        //noinspection ReturnOfNull
                        return null;
                      });
        } finally {
          lastFSyncTs = ts;
        }
      }
    } catch (final IOException | ExecutionException e) {
      LogManager.instance().error(this, "Error during WAL writing", e);
      throw new IllegalStateException(e);
    } catch (final RuntimeException | Error e) {
      LogManager.instance().error(this, "Error during WAL writing", e);
      throw e;
    } finally {
      recordsWriterLock.unlock();
    }
  }

  private void executeSyncAndCloseFile() throws IOException {
    try {
      long startTs = 0;
      if (printPerformanceStatistic) {
        startTs = System.nanoTime();
      }

      final var cqSize = fileCloseQueueSize.get();
      if (cqSize > 0) {
        var counter = 0;

        while (counter < cqSize) {
          final var pair = fileCloseQueue.poll();
          if (pair != null) {
            final var file = pair.second;

            assert file.position() % pageSize == 0;

            if (callFsync) {
              file.force(true);
            }

            file.close();

            fileCloseQueueSize.decrementAndGet();
          } else {
            break;
          }

          counter++;
        }
      }

      if (callFsync && walFile != null) {
        walFile.force(true);
      }

      flushedLSN = writtenUpTo.get().getLsn();

      fireEventsFor(flushedLSN);

      if (printPerformanceStatistic) {
        final var endTs = System.nanoTime();
        //noinspection NonAtomicOperationOnVolatileField
        fsyncTime += (endTs - startTs);
        //noinspection NonAtomicOperationOnVolatileField
        fsyncCount++;
      }
    } catch (final IOException e) {
      LogManager.instance().error(this, "Error during FSync of WAL data", e);
      throw e;
    }
  }

  private void writeBuffer(
      final WALFile file,
      final long segmentId,
      final ByteBuffer buffer,
      final LogSequenceNumber lastLSN)
      throws IOException {

    if (buffer.position() <= CASWALPage.RECORDS_OFFSET) {
      return;
    }

    var maxPage = (buffer.position() + pageSize - 1) / pageSize;
    var lastPageSize = buffer.position() - (maxPage - 1) * pageSize;

    if (lastPageSize <= CASWALPage.RECORDS_OFFSET) {
      maxPage--;
      lastPageSize = pageSize;
    }

    for (int start = 0, page = 0; start < maxPage * pageSize; start += pageSize, page++) {
      final int pageSize;
      if (page < maxPage - 1) {
        pageSize = CASDiskWriteAheadLog.this.pageSize;
      } else {
        pageSize = lastPageSize;
      }

      buffer.limit(start + pageSize);

      buffer.putLong(
          start + CASWALPage.MAGIC_NUMBER_OFFSET,
          aesKey == null ? CASWALPage.MAGIC_NUMBER : CASWALPage.MAGIC_NUMBER_WITH_ENCRYPTION);

      buffer.putShort(start + CASWALPage.PAGE_SIZE_OFFSET, (short) pageSize);

      buffer.position(start + CASWALPage.RECORDS_OFFSET);
      final var xxHash64 = xxHashFactory.hash64();
      final var hash = xxHash64.hash(buffer, XX_SEED);

      buffer.putLong(start + CASWALPage.XX_OFFSET, hash);

      if (aesKey != null) {
        final var pageIndex = (currentPosition + start) / CASDiskWriteAheadLog.this.pageSize;
        doEncryptionDecryption(segmentId, pageIndex, Cipher.ENCRYPT_MODE, start, pageSize, buffer);
      }
    }

    buffer.position(0);
    final var limit = maxPage * pageSize;
    buffer.limit(limit);

    try {
      if (writeFuture != null) {
        writeFuture.get();
      }
    } catch (final InterruptedException e) {
      LogManager.instance().error(this, "WAL write was interrupted", e);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error during WAL write", e);
      throw BaseException.wrapException(
          new StorageException(storageName, "Error during WAL data write"), e, storageName);
    }

    assert file.position() == currentPosition;
    currentPosition += buffer.limit();

    final var expectedPosition = currentPosition;

    writeFuture =
        writeExecutor.submit(
            (Callable<?>)
                () -> {
                  executeWriteBuffer(file, buffer, lastLSN, limit, expectedPosition);
                  //noinspection ReturnOfNull
                  return null;
                });
  }

  private void executeWriteBuffer(
      final WALFile file,
      final ByteBuffer buffer,
      final LogSequenceNumber lastLSN,
      final int limit,
      final long expectedPosition)
      throws IOException {
    try {
      long startTs = 0;
      if (printPerformanceStatistic) {
        startTs = System.nanoTime();
      }

      assert buffer.position() == 0;
      assert file.position() % pageSize == 0;
      assert buffer.limit() == limit;
      assert file.position() == expectedPosition - buffer.limit();

      long totalWritten = 0;
      while (buffer.remaining() > 0) {
        final int initialPos = buffer.position();
        final int written = file.write(buffer);
        totalWritten += written;
        assert buffer.position() == initialPos + written;
        assert file.position() == expectedPosition - buffer.limit() + initialPos + written
            : "File position "
            + file.position()
            + " buffer limit "
            + buffer.limit()
            + " initial pos "
            + initialPos
            + " written "
            + written;
      }
      diskWriteMeter.record(totalWritten);

      assert file.position() == expectedPosition;

      if (lastLSN != null) {
        final var written = writtenUpTo.get();

        assert written == null || written.getLsn().compareTo(lastLSN) < 0;

        if (written == null) {
          writtenUpTo.lazySet(new WrittenUpTo(lastLSN, buffer.limit()));
        } else {
          if (written.getLsn().getSegment() == lastLSN.getSegment()) {
            writtenUpTo.lazySet(new WrittenUpTo(lastLSN, written.getPosition() + buffer.limit()));
          } else {
            writtenUpTo.lazySet(new WrittenUpTo(lastLSN, buffer.limit()));
          }
        }
      }

      if (printPerformanceStatistic) {
        final var endTs = System.nanoTime();

        //noinspection NonAtomicOperationOnVolatileField
        bytesWrittenSum += buffer.limit();
        //noinspection NonAtomicOperationOnVolatileField
        bytesWrittenTime += (endTs - startTs);
      }
    } catch (final IOException e) {
      LogManager.instance().error(this, "Error during WAL data write", e);
      throw e;
    }
  }

  private void printReport() {
    final var ts = System.nanoTime();
    final long reportInterval;

    if (reportTs == -1) {
      reportTs = ts;
      reportInterval = 0;
    } else {
      reportInterval = ts - reportTs;
    }

    if (reportInterval >= statisticPrintInterval * 1_000_000_000L) {
      final var bytesWritten = CASDiskWriteAheadLog.this.bytesWrittenSum;
      final var writtenTime = CASDiskWriteAheadLog.this.bytesWrittenTime;

      final var fsyncTime = CASDiskWriteAheadLog.this.fsyncTime;
      final var fsyncCount = CASDiskWriteAheadLog.this.fsyncCount;

      final var threadsWaitingCount = CASDiskWriteAheadLog.this.threadsWaitingCount.sum();
      final var threadsWaitingSum = CASDiskWriteAheadLog.this.threadsWaitingSum.sum();

      final var additionalArgs =
          new Object[]{
              storageName,
              bytesWritten / 1024,
              writtenTime > 0 ? 1_000_000_000L * bytesWritten / writtenTime / 1024 : -1,
              fsyncCount,
              fsyncCount > 0 ? fsyncTime / fsyncCount / 1_000_000 : -1,
              threadsWaitingCount,
              threadsWaitingCount > 0 ? threadsWaitingSum / threadsWaitingCount / 1_000_000 : -1
          };
      LogManager.instance()
          .info(
              this,
              "WAL stat:%s: %d KB was written, write speed is %d KB/s. FSync count %d. Avg. fsync"
                  + " time %d ms. %d times threads were waiting for WAL. Avg wait interval %d ms.",
              additionalArgs);

      //noinspection NonAtomicOperationOnVolatileField
      CASDiskWriteAheadLog.this.bytesWrittenSum -= bytesWritten;
      //noinspection NonAtomicOperationOnVolatileField
      CASDiskWriteAheadLog.this.bytesWrittenTime -= writtenTime;

      //noinspection NonAtomicOperationOnVolatileField
      CASDiskWriteAheadLog.this.fsyncTime -= fsyncTime;
      //noinspection NonAtomicOperationOnVolatileField
      CASDiskWriteAheadLog.this.fsyncCount -= fsyncCount;

      CASDiskWriteAheadLog.this.threadsWaitingSum.add(-threadsWaitingSum);
      CASDiskWriteAheadLog.this.threadsWaitingCount.add(-threadsWaitingCount);

      reportTs = ts;
    }
  }

  private static Cipher getCipherInstance() {
    try {
      return Cipher.getInstance(TRANSFORMATION);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw BaseException.wrapException(
          new SecurityException((String) null,
              "Implementation of encryption " + TRANSFORMATION + " is absent"),
          e, (String) null);
    }
  }
}
