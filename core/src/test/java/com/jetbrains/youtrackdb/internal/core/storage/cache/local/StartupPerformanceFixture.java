package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Streaming, warm-cache fixtures. Only one random 8 KiB page is generated at a time. */
public final class StartupPerformanceFixture {
  public static final int FILE = 123457;
  public static final long MIB = 1024 * 1024;
  public final List<LogSequenceNumber> positions = new ArrayList<>();
  public LogSequenceNumber coverage;
  public long firstPage;
  public long extentBytes;
  public long suffixBytes;
  public List<WriteAheadLog.SegmentExtent> inventory;
  private boolean timingReader;

  public static Path root() throws Exception {
    var root = Files.createDirectories(Path.of("target", "tracker-startup-performance"))
        .toAbsolutePath();
    String filesystem = Files.getFileStore(root).type();
    assertTrue("Startup evidence requires disk, not " + filesystem,
        !filesystem.equals("tmpfs") && !filesystem.equals("ramfs"));
    return root;
  }

  public StartupPerformanceFixture(WriteAheadLog wal, Path directory, int mib, boolean tail)
      throws Exception {
    var random = new Random(1317);
    byte[] bytes = new byte[8192];
    long logical = 0;
    while (logical < mib * MIB) {
      if (logical > 0 && logical % (16 * MIB) == 0) {
        assertTrue(wal.appendNewSegment());
        if (tail) {
          firstPage = positions.size();
        }
      }
      random.nextBytes(bytes);
      var changes = new WALPageChangesPortion();
      changes.setBinaryValue(null, bytes, 0);
      var record = new UpdatePageRecord(positions.size(), FILE, 991, changes,
          new LogSequenceNumber(0, 0));
      assertTrue(record.serializedSize() < 16 * MIB);
      positions.add(wal.log(record));
      logical += bytes.length;
      // Bound the asynchronous writer queue as well as Java fixture memory.
      if (positions.size() % 128 == 0) {
        wal.flush();
      }
    }
    wal.flush();
    coverage = tail ? new LogSequenceNumber(positions.get((int) firstPage).getSegment(),
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage.RECORDS_OFFSET)
        : positions.get(0);
    inventory = inventory(directory);
    extentBytes = inventory.stream().mapToLong(WriteAheadLog.SegmentExtent::bytes).sum();
    assertTrue("Physical WAL must not compress below its size label", extentBytes >= mib * MIB);
    assertTrue("Unexpected fixture inflation", extentBytes < mib * MIB * 1.2 + MIB);
    suffixBytes = inventory.stream().filter(s -> s.segment() >= coverage.getSegment())
        .mapToLong(WriteAheadLog.SegmentExtent::bytes).sum() - coverage.getPosition();
  }

  public static List<WriteAheadLog.SegmentExtent> inventory(Path directory) throws Exception {
    var result = new ArrayList<WriteAheadLog.SegmentExtent>();
    try (var files = Files.list(directory)) {
      for (var file : files.filter(p -> p.getFileName().toString().matches(
          ContextConfiguration.WAL_DEFAULT_NAME + "\\.\\d+\\.wal")).sorted().toList()) {
        var name = file.getFileName().toString().split("\\.");
        result.add(new WriteAheadLog.SegmentExtent(Long.parseLong(name[name.length - 2]),
            Files.size(file)));
      }
    }
    result.sort(java.util.Comparator.comparingLong(WriteAheadLog.SegmentExtent::segment));
    return List.copyOf(result);
  }

  public void save(Path path, boolean trusted, int generations) throws Exception {
    assertTrue(generations == 1 || generations == 2);
    var builder = ChangedPageTracker.restoration(new UUID(1, 2), new UUID(3, 4), trusted,
        generations == 2 ? new UUID(5, 6) : null);
    builder.activeWord(FILE, 0, 1);
    builder.activeWord(FILE, positions.size() / 64L + 1, 1);
    if (generations == 2) {
      builder.sealedWord(FILE, positions.size() / 64L + 1, 2);
    }
    ChangedPageTrackerFile.save(path, builder.build(), coverage);
    var loaded = ChangedPageTrackerFile.load(path);
    assertEquals(generations == 2, capture(loaded.tracker()).sealed() != null);
    assertEquals(coverage, loaded.coverageLsn());
  }

  public static WriteAheadLog countDecoded(WriteAheadLog wal, long[] records) {
    var observed = org.mockito.Mockito.mock(WriteAheadLog.class,
        org.mockito.AdditionalAnswers.delegatesTo(wal));
    org.mockito.Mockito.doAnswer(call -> {
      var reader = wal.openProofReader(call.getArgument(0), call.getArgument(1));
      return (WriteAheadLog.ProofReader) max -> {
        var batch = reader.next(max);
        records[0] += batch.records().size();
        return batch;
      };
    }).when(observed).openProofReader(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt());
    return observed;
  }

  /** Captures a bounded diagnostic sequence for validation outside the timed reader loop. */
  public long[] sequence(WriteAheadLog wal, int batchSize) {
    assertTrue("Sequence capture must stay outside reader timing", !timingReader);
    var sequence = new long[(positions.size() + inventory.size() + 1) * 4];
    int index = 0;
    var reader = wal.openProofReader(coverage, 16 * 1024 * 1024);
    while (true) {
      var batch = reader.next(batchSize);
      for (var record : batch.records()) {
        sequence[index++] = record.getLsn().getSegment();
        sequence[index++] = record.getLsn().getPosition();
        sequence[index++] = record instanceof UpdatePageRecord page ? page.getPageIndex() : -1;
        sequence[index++] = record instanceof UpdatePageRecord page ? page.getFileId() : 0;
      }
      if (batch.status() != ProofStatus.MORE) {
        sequence[index++] = batch.status().ordinal();
        return java.util.Arrays.copyOf(sequence, index);
      }
    }
  }

  /** Only next() and its lightweight counters run inside both measurement windows. */
  public long[] measureReader(CountingReader reader, int batchSize) {
    timingReader = true;
    try {
      long before = TrackerPerformanceProbe.allocated();
      long start = System.nanoTime();
      while (reader.next(batchSize).status() == ProofStatus.MORE) {
        // Drain without capturing sequences or validating individual records.
      }
      long elapsed = System.nanoTime() - start;
      long allocation = TrackerPerformanceProbe.allocated() - before;
      return new long[] {elapsed, allocation};
    } finally {
      timingReader = false;
    }
  }

  /** Independent witness of the batch arguments actually sent to the production reader. */
  public static final class CountingReader implements WriteAheadLog.ProofReader {
    private final WriteAheadLog.ProofReader delegate;
    private int calls;
    private int maxRequested;
    private int minRequested = Integer.MAX_VALUE;
    private long records;
    private ProofStatus terminal;

    public CountingReader(WriteAheadLog.ProofReader delegate) {
      this.delegate = delegate;
    }

    @Override
    public WriteAheadLog.ProofBatch next(int maxRecords) {
      calls++;
      maxRequested = Math.max(maxRequested, maxRecords);
      minRequested = Math.min(minRequested, maxRecords);
      var batch = delegate.next(maxRecords);
      records += batch.records().size();
      terminal = batch.status();
      return batch;
    }

    public void assertBatch(int expectedBatch, long expectedRecords) {
      assertEquals("Maximum requested batch", expectedBatch, maxRequested);
      assertEquals("Minimum requested batch", expectedBatch, minRequested);
      assertEquals(expectedRecords, records);
      assertEquals(ProofStatus.REACHED_END, terminal);
      // A full final batch needs a separate terminal call. A short batch carries REACHED_END.
      assertEquals(expectedRecords / expectedBatch + 1, calls);
    }

    public String label() {
      return " requestedBatch=" + maxRequested + " nextCalls=" + calls;
    }
  }

  public long raw(WriteAheadLog wal, int batchSize) {
    var reader = wal.openProofReader(coverage, 16 * 1024 * 1024);
    int page = (int) firstPage;
    long records = 0;
    while (true) {
      var batch = reader.next(batchSize);
      for (var record : batch.records()) {
        records++;
        if (record instanceof UpdatePageRecord update) {
          assertEquals(page, update.getPageIndex());
          assertEquals(FILE, update.getFileId());
          assertEquals(positions.get(page++), update.getLsn());
        } else {
          assertTrue(record instanceof EmptyWALRecord);
        }
      }
      if (batch.status() != ProofStatus.MORE) {
        assertEquals(ProofStatus.REACHED_END, batch.status());
        assertEquals(positions.size(), page);
        long empties = inventory.stream().filter(s -> s.segment() > coverage.getSegment()
            || (s.segment() == coverage.getSegment() && firstPage > 0)).count();
        assertEquals(positions.size() - firstPage + empties, records);
        return records;
      }
    }
  }

  public void assertMarks(ChangedPageTracker tracker, int generations) {
    var actual = new ArrayList<Long>();
    var state = capture(tracker);
    state.active().forEachCandidate(FILE, actual::add);
    var expected = new java.util.TreeSet<Long>();
    expected.add(0L);
    expected.add((positions.size() / 64L + 1) * 64);
    if (generations == 2) {
      expected.add((positions.size() / 64L + 1) * 64 + 1);
    }
    for (long page = firstPage; page < positions.size(); page++) {
      expected.add(page);
    }
    assertEquals(new ArrayList<>(expected), actual);
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private long bitmapSegments() {
    var segments = new java.util.HashSet<Long>();
    segments.add(0L);
    segments.add(((positions.size() / 64L + 1) * 64) / (ChangedPageTracker.SEGMENT_WORDS * 64L));
    for (long page = firstPage; page < positions.size(); page++) {
      segments.add(page / (ChangedPageTracker.SEGMENT_WORDS * 64L));
    }
    return segments.size();
  }

  public String label(String row, int generations, String shutdown, long records) {
    return row + " savedGenerations=" + generations + " shutdown=" + shutdown
        + " cache=warm compression=LZ4 encryption=none payload=random8KiB"
        + " physicalExtentBytes=" + extentBytes + " retainedBytes=" + extentBytes
        + " suffixBytes=" + suffixBytes + " readBytes=unobserved records=" + records
        + " markCalls=" + (positions.size() - firstPage) + " distinctScanMarks="
        + (positions.size() - firstPage) + " walSegments=" + inventory.size()
        + " bitmapSegments=" + bitmapSegments()
        + " coverage=" + coverage.getSegment() + ":" + coverage.getPosition()
        + " coverageLocation=" + (firstPage == 0 ? "early" : "lastSegment")
        + " outcome=PROVED_HISTORY_TRUSTED terminal=REACHED_END"
        + " childHeapBytes=" + Runtime.getRuntime().maxMemory();
  }
}
