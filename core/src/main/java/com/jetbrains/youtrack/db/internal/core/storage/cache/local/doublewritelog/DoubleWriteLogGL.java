package com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrack.db.internal.common.util.RawPairIntegerInteger;
import com.jetbrains.youtrack.db.internal.common.util.RawPairLongLong;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

public class DoubleWriteLogGL implements DoubleWriteLog {

  /**
   * Stands for "double write log"
   */
  public static final String EXTENSION = ".dwl";

  private static final DirectMemoryAllocator ALLOCATOR = DirectMemoryAllocator.instance();
  static final int DEFAULT_BLOCK_SIZE = 4 << 10;

  private static final byte DATA_RECORD = 0;

  private static final byte ONE_BYTE_FILLER_RECORD = 1;
  private static final byte TWO_BYTE_FILLER_RECORD = 2;
  private static final byte THREE_BYTE_FILLER_RECORD = 3;
  private static final byte FOUR_BYTE_FILLER_RECORD = 4;
  private static final byte FULL_FILLER_RECORD = 5;

  private static final int RECORD_TYPE_FLAG_LEN = 1;

  private static final int XX_HASH_LEN = 8;
  private static final int FILE_ID_LEN = 4;
  private static final int START_PAGE_INDEX_LEN = 4;
  private static final int CHUNK_SIZE_LEN = 4;
  private static final int COMPRESSED_SIZE_LEN = 4;

  private static final int RECORD_METADATA_SIZE =
      XX_HASH_LEN
          + FILE_ID_LEN
          + START_PAGE_INDEX_LEN
          + CHUNK_SIZE_LEN
          + COMPRESSED_SIZE_LEN
          + RECORD_TYPE_FLAG_LEN;

  private static final long XX_HASH_SEED = 0x3242AEDF76L;
  private static final XXHash64 XX_HASH;

  static {
    final var factory = XXHashFactory.fastestInstance();
    XX_HASH = factory.hash64();
  }

  private Path storagePath;
  private String storageName;
  private String baseFileName;

  private TimeRate diskWriteMeter = TimeRate.NOOP;

  private int pageSize;

  private FileChannel currentFile;
  private long currentSegment;

  private final long maxSegSize;

  private static final LZ4Compressor LZ_4_COMPRESSOR;
  private static final LZ4FastDecompressor LZ_4_DECOMPRESSOR;

  private LongArrayList tailSegments;

  private volatile boolean restoreMode;

  private int checkpointCounter;

  private volatile int blockMask;
  private volatile int blockSize;

  private long segmentPosition;

  private Map<RawPairIntegerInteger, RawPairLongLong> pageMap;

  static {
    final var factory = LZ4Factory.fastestInstance();
    LZ_4_COMPRESSOR = factory.fastCompressor();
    LZ_4_DECOMPRESSOR = factory.fastDecompressor();
  }

  private final Object mutex = new Object();

  public DoubleWriteLogGL(final long maxSegSize) {
    this.maxSegSize = maxSegSize;
  }

  public DoubleWriteLogGL(final long maxSegSize, int blockSize) {
    this.maxSegSize = maxSegSize;
    this.blockSize = blockSize;
  }

  @Override
  public void open(final String storageName, String baseFileName, final Path storagePath,
      int pageSize)
      throws IOException {
    synchronized (mutex) {
      this.pageSize = pageSize;
      this.storagePath = storagePath;
      this.storageName = storageName;
      this.baseFileName = baseFileName;
      this.diskWriteMeter = YouTrackDBEnginesManager.instance()
          .getMetricsRegistry()
          .databaseMetric(CoreMetrics.DISK_WRITE_RATE, storageName);

      this.tailSegments = new LongArrayList();
      this.pageMap = new HashMap<>();

      final Optional<Path> latestPath;
      try (final var stream = Files.list(storagePath)) {
        latestPath =
            stream
                .filter(DoubleWriteLogGL::fileFilter)
                .peek((path) -> tailSegments.add(extractSegmentId(path.getFileName().toString())))
                .min(
                    (pathOne, pathTwo) -> {
                      final var indexOne = extractSegmentId(pathOne.getFileName().toString());
                      final var indexTwo = extractSegmentId(pathTwo.getFileName().toString());
                      return -Long.compare(indexOne, indexTwo);
                    });
      }

      this.currentSegment =
          latestPath.map(path -> extractSegmentId(path.getFileName().toString()) + 1).orElse(0L);

      var path = createLogFilePath();
      this.currentFile = createLogFile(path);

      if (blockSize <= 0) {
        var supposedBlockSize = IOUtils.calculateBlockSize(path);
        if (supposedBlockSize > 0) {
          blockSize = supposedBlockSize;
        } else {
          blockSize = DEFAULT_BLOCK_SIZE;
        }
      }

      blockMask = blockSize - 1;

      LogManager.instance()
          .info(
              this,
              "DWL:%s: block size = %d bytes, maximum segment size = %d MB, base file name: %s",
              storageName,
              blockSize,
              maxSegSize / 1024 / 1024,
              baseFileName);
    }
  }

  private long extractSegmentId(final String segmentName) {
    final var len = baseFileName.length();

    var index = segmentName.substring(len + 1);
    index = index.substring(0, index.length() - EXTENSION.length());

    return Long.parseLong(index);
  }

  private static FileChannel createLogFile(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
  }

  private Path createLogFilePath() {
    return storagePath.resolve(generateSegmentsName(currentSegment));
  }

  private FileChannel createLogFile() throws IOException {
    return createLogFile(createLogFilePath());
  }

  private String generateSegmentsName(long id) {
    return baseFileName + "_" + id + EXTENSION;
  }

  private static boolean fileFilter(final Path path) {
    return path.toString().endsWith(EXTENSION);
  }

  @Override
  public boolean write(
      final ArrayList<ByteBuffer> buffers,
      final IntArrayList fileIds,
      final IntArrayList pageIndexes)
      throws IOException {
    synchronized (mutex) {
      assert checkpointCounter >= 0;

      assert currentFile.size() == segmentPosition;
      if (checkpointCounter == 0 && segmentPosition >= maxSegSize) {
        addNewSegment();
      }

      var sizeToAllocate = 0;
      for (final var byteBuffer : buffers) {
        sizeToAllocate += LZ_4_COMPRESSOR.maxCompressedLength(byteBuffer.limit());
      }

      sizeToAllocate += buffers.size() * RECORD_METADATA_SIZE;
      sizeToAllocate = (sizeToAllocate + blockMask) & ~blockMask;
      final var pageContainer =
          ALLOCATOR.allocate(sizeToAllocate, false, Intention.DWL_ALLOCATE_CHUNK);

      try {
        final ByteBuffer containerBuffer;

        containerBuffer = pageContainer.getNativeByteBuffer();
        assert containerBuffer.position() == 0;

        for (var i = 0; i < buffers.size(); i++) {
          final var buffer = buffers.get(i);
          buffer.rewind();

          final var maxCompressedLength = LZ_4_COMPRESSOR.maxCompressedLength(buffer.limit());
          final var compressedPointer =
              ALLOCATOR.allocate(
                  maxCompressedLength, false, Intention.DWL_ALLOCATE_COMPRESSED_CHUNK);
          try {
            final var compressedBuffer = compressedPointer.getNativeByteBuffer();
            LZ_4_COMPRESSOR.compress(buffer, compressedBuffer);

            final var compressedSize = compressedBuffer.position();
            compressedBuffer.rewind();
            compressedBuffer.limit(compressedSize);

            containerBuffer.put(DATA_RECORD);

            final var xxHashPosition = containerBuffer.position();
            containerBuffer.position(xxHashPosition + XX_HASH_LEN);
            containerBuffer.putInt(fileIds.getInt(i));
            containerBuffer.putInt(pageIndexes.getInt(i));
            containerBuffer.putInt(buffer.limit() / pageSize);
            containerBuffer.putInt(compressedSize);

            containerBuffer.put(compressedBuffer);
            containerBuffer.putLong(
                xxHashPosition,
                XX_HASH.hash(
                    containerBuffer,
                    xxHashPosition + XX_HASH_LEN,
                    containerBuffer.position() - xxHashPosition - XX_HASH_LEN,
                    XX_HASH_SEED));
          } finally {
            ALLOCATOR.deallocate(compressedPointer);
          }
        }

        var currentPosition = containerBuffer.position();
        var bytesToFill = blockSize - (currentPosition & blockMask);

        var fillerRecordId =
            switch (bytesToFill) {
              case 1 -> ONE_BYTE_FILLER_RECORD;
              case 2 -> TWO_BYTE_FILLER_RECORD;
              case 3 -> THREE_BYTE_FILLER_RECORD;
              case 4 -> FOUR_BYTE_FILLER_RECORD;
              default -> FULL_FILLER_RECORD;
            };
        containerBuffer.put(fillerRecordId);

        if (fillerRecordId == FULL_FILLER_RECORD) {
          containerBuffer.putInt(bytesToFill);
        }

        containerBuffer.limit(currentPosition + bytesToFill);
        assert (currentPosition + bytesToFill) % blockSize == 0;

        containerBuffer.rewind();
        assert currentFile.size() == segmentPosition;
        var written = IOUtils.writeByteBuffer(containerBuffer, currentFile, segmentPosition);
        currentFile.force(true);
        diskWriteMeter.record(written);
        segmentPosition += written;
        assert currentFile.size() == segmentPosition;
      } finally {
        ALLOCATOR.deallocate(pageContainer);
      }

      // we can not truncate log in restore mode because we remove all restore information
      return !restoreMode && !tailSegments.isEmpty() && checkpointCounter == 0;
    }
  }

  private void addNewSegment() throws IOException {
    if (currentFile.size() > 0) {
      currentFile.close();

      tailSegments.add(currentSegment);

      this.currentSegment++;
      this.currentFile = createLogFile();
      this.segmentPosition = 0;
    }
  }

  @Override
  public void truncate() throws IOException {
    synchronized (mutex) {
      if (restoreMode) {
        return;
      }

      tailSegments
          .longStream()
          .boxed()
          .map(this::generateSegmentsName)
          .forEach(
              (segment) -> {
                try {
                  final var segmentPath = storagePath.resolve(segment);
                  Files.delete(segmentPath);
                } catch (final IOException e) {
                  LogManager.instance()
                      .error(
                          this,
                          "Can not delete segment of double write log - %s in storage %s",
                          e,
                          segment,
                          storageName);
                }
              });

      tailSegments.clear();
    }
  }

  @Nullable
  @Override
  public Pointer loadPage(int fileId, int pageIndex, ByteBufferPool bufferPool)
      throws IOException {
    if (!restoreMode) {
      return null;
    }

    synchronized (mutex) {
      if (!restoreMode) {
        return null;
      }

      final var segmentPosition =
          pageMap.get(new RawPairIntegerInteger(fileId, pageIndex));
      if (segmentPosition == null) {
        return null;
      }

      final var segmentName = generateSegmentsName(segmentPosition.first);
      final var segmentPath = storagePath.resolve(segmentName);

      // bellow set of precautions to prevent errors during page restore
      if (Files.exists(segmentPath)) {
        try (final var channel = FileChannel.open(segmentPath, StandardOpenOption.READ)) {
          final var channelSize = channel.size();
          if (channelSize - segmentPosition.second > RECORD_METADATA_SIZE) {
            final var metadataBuffer =
                ByteBuffer.allocate(RECORD_METADATA_SIZE).order(ByteOrder.nativeOrder());

            IOUtils.readByteBuffer(metadataBuffer, channel, segmentPosition.second, true);
            metadataBuffer.rewind();

            final var recordType = metadataBuffer.get();
            final var xxHash = metadataBuffer.getLong();
            if (recordType != DATA_RECORD) {
              throwSegmentIsBroken(segmentPath);
            }
            final var storedFileId = metadataBuffer.getInt();
            final var storedPageIndex = metadataBuffer.getInt();
            final var pages = metadataBuffer.getInt();
            final var compressedLen = metadataBuffer.getInt();

            if (pages < 0 || storedPageIndex < 0 || storedFileId < 0 || compressedLen < 0) {
              throwSegmentIsBroken(segmentPath);
            }

            if (storedFileId == fileId
                && pageIndex >= storedPageIndex
                && pageIndex < storedPageIndex + pages) {
              if (channelSize - segmentPosition.second - RECORD_METADATA_SIZE >= compressedLen) {
                final var buffer =
                    ByteBuffer.allocate(compressedLen + RECORD_METADATA_SIZE)
                        .order(ByteOrder.nativeOrder());
                IOUtils.readByteBuffer(buffer, channel, segmentPosition.second, true);

                buffer.rewind();

                if (XX_HASH.hash(
                    buffer,
                    XX_HASH_LEN + RECORD_TYPE_FLAG_LEN,
                    buffer.capacity() - XX_HASH_LEN - RECORD_TYPE_FLAG_LEN,
                    XX_HASH_SEED)
                    != xxHash) {
                  throwSegmentIsBroken(segmentPath);
                }

                final var pagesBuffer =
                    ByteBuffer.allocate(pages * pageSize).order(ByteOrder.nativeOrder());
                LZ_4_DECOMPRESSOR.decompress(
                    buffer, RECORD_METADATA_SIZE, pagesBuffer, 0, pagesBuffer.capacity());

                final var pagePosition = (pageIndex - storedPageIndex) * pageSize;

                pagesBuffer.position(pagePosition);
                pagesBuffer.limit(pagePosition + pageSize);

                final var pointer = bufferPool.acquireDirect(false, Intention.LOAD_WAL_PAGE);
                final var pageBuffer = pointer.getNativeByteBuffer();
                assert pageBuffer.position() == 0;
                pageBuffer.put(pagesBuffer);

                pageBuffer.rewind();

                return pointer;
              }
            }
          }
        }
      }

      return null;
    }
  }

  private static void throwSegmentIsBroken(Path segmentPath) {
    throw new IllegalStateException(
        "DWL Segment " + segmentPath + " is broken and can not be used during restore");
  }

  @Override
  public void restoreModeOn() throws IOException {
    synchronized (mutex) {
      if (restoreMode) {
        return;
      }

      pageMap.clear();

      // sort to fetch the
      final Path[] segments;
      try (final var stream = Files.list(storagePath)) {
        segments =
            stream
                .filter(DoubleWriteLogGL::fileFilter)
                .sorted(
                    (pathOne, pathTwo) -> {
                      final var indexOne = extractSegmentId(pathOne.getFileName().toString());
                      final var indexTwo = extractSegmentId((pathTwo.getFileName().toString()));
                      return Long.compare(indexOne, indexTwo);
                    })
                .toArray(Path[]::new);
      }

      segmentLoop:
      for (final var segment : segments) {
        try (final var channel = FileChannel.open(segment, StandardOpenOption.READ)) {
          long position = 0;
          var fileSize = channel.size();

          while (fileSize - position > RECORD_METADATA_SIZE) {
            final var metadataBuffer =
                ByteBuffer.allocate(RECORD_METADATA_SIZE).order(ByteOrder.nativeOrder());
            IOUtils.readByteBuffer(metadataBuffer, channel, position, true);
            metadataBuffer.rewind();

            final var recordType = metadataBuffer.get();
            if (recordType < 0 || recordType > FULL_FILLER_RECORD) {
              printSegmentIsBroken(segment);
              continue segmentLoop;
            }

            if (recordType != DATA_RECORD) {
              final var bytesToFill =
                  switch (recordType) {
                    case ONE_BYTE_FILLER_RECORD -> 1;
                    case TWO_BYTE_FILLER_RECORD -> 2;
                    case THREE_BYTE_FILLER_RECORD -> 3;
                    case FOUR_BYTE_FILLER_RECORD -> 4;
                    case FULL_FILLER_RECORD -> metadataBuffer.getInt();
                    default ->
                        throw new IllegalStateException("Unexpected record type : " + recordType);
                  };

              position += bytesToFill;
              continue;
            }

            final var xxHash = metadataBuffer.getLong();
            final var fileId = metadataBuffer.getInt();
            final var pageIndex = metadataBuffer.getInt();
            final var pages = metadataBuffer.getInt();
            final var compressedLen = metadataBuffer.getInt();

            if (fileId >= 0
                && pages >= 0
                && pageIndex >= 0
                && compressedLen >= 0
                && position + RECORD_METADATA_SIZE + compressedLen <= fileSize) {
              final var buffer =
                  ByteBuffer.allocate(RECORD_METADATA_SIZE + compressedLen)
                      .order(ByteOrder.nativeOrder());
              IOUtils.readByteBuffer(buffer, channel, position, true);
              buffer.rewind();

              if (XX_HASH.hash(
                  buffer,
                  XX_HASH_LEN + RECORD_TYPE_FLAG_LEN,
                  buffer.capacity() - XX_HASH_LEN - RECORD_TYPE_FLAG_LEN,
                  XX_HASH_SEED)
                  != xxHash) {
                printSegmentIsBroken(segment);
                continue segmentLoop;
              }
            } else {
              printSegmentIsBroken(segment);
              continue segmentLoop;
            }

            final long segmentId;
            try {
              segmentId = extractSegmentId(segment.getFileName().toString());
            } catch (NumberFormatException e) {
              printSegmentIsBroken(segment);
              continue segmentLoop;
            }

            for (var i = 0; i < pages; i++) {
              pageMap.put(
                  new RawPairIntegerInteger(fileId, pageIndex + i),
                  new RawPairLongLong(segmentId, position));
            }

            position += RECORD_METADATA_SIZE + compressedLen;
          }
        }
      }

      restoreMode = true;
    }
  }

  private void printSegmentIsBroken(Path segment) {
    LogManager.instance()
        .warn(
            this,
            "DWL Segment "
                + segment
                + " is broken and only part of its content will be used during data restore");
  }

  @Override
  public void restoreModeOff() {
    synchronized (mutex) {
      pageMap = new HashMap<>();
      restoreMode = false;
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (mutex) {
      currentFile.close();

      try (final var stream = Files.list(storagePath)) {
        stream
            .filter(DoubleWriteLogGL::fileFilter)
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException e) {
                    throw new StorageException(storageName,
                        "Can not delete file " + path + " in storage " + storageName);
                  }
                });
      }
    }
  }

  @Override
  public void startCheckpoint() throws IOException {
    synchronized (mutex) {
      addNewSegment();

      checkpointCounter++;
    }
  }

  @Override
  public void endCheckpoint() {
    synchronized (mutex) {
      checkpointCounter--;
    }
  }
}
