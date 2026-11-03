package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorageMaintenanceFloorTest;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NameIdMapFileTest {

  @Rule
  public TemporaryFolder directory = new TemporaryFolder();

  private static final NameFileIdEntry FIRST = new NameFileIdEntry("a", 7, "A_7");
  private static final NameFileIdEntry SECOND =
      new NameFileIdEntry("\u03bb\uD83D\uDE00", -9, "disk");

  /** Wire bytes match the V3 layout independently of the extracted serializer. */
  @Test
  public void recordEncodingKeepsV3WireFormat() throws Exception {
    final var expected = ByteBuffer.allocate(32);
    expected.position(8);
    expected.putInt(20).putInt(7).putInt(1).put((byte) 'a').put((byte) 0);
    expected.putInt(3).put(new byte[] {'A', 0, '_', 0, '7', 0});
    rehash(expected);
    assertArrayEquals(expected.array(), NameIdMapFile.encode(FIRST, "test").array());
    final var path = write(image(FIRST, SECOND));
    try (final var channel = FileChannel.open(path)) {
      final var scan = NameIdMapFile.scan(channel);
      assertTrue(scan.intact());
      assertEquals(channel.size(), scan.validEnd());
      assertEquals(List.of(FIRST, SECOND), entries(scan));
      assertEquals(entries(scan), entries(scan));
      assertTrue(scan.bytes().isReadOnly());
      final var iterator = scan.iterator();
      iterator.next();
      iterator.next();
      assertThrows(NoSuchElementException.class, iterator::next);
    }
  }

  /** Empty files are intact. Record limits accept the maximum and reject larger payloads. */
  @Test
  public void emptyAndMaximumLengthRecordsAreBounded() throws Exception {
    final var empty = scan(write(ByteBuffer.allocate(0)));
    assertTrue(empty.intact());
    assertFalse(empty.iterator().hasNext());
    final var maximum = new NameFileIdEntry("x".repeat((16384 - 12) / 2), 0, "");
    assertEquals(List.of(maximum), entries(scan(write(image(maximum)))));
    assertThrows(StorageException.class,
        () -> NameIdMapFile.encode(new NameFileIdEntry(maximum.getName() + "x", 1, ""), "test"));
  }

  /** Every truncation inside the last record keeps the complete prefix and drops the tail. */
  @Test
  public void truncationRetainsOnlyCompletePrefix() throws Exception {
    final var prefix = image(FIRST);
    final var tail = image(SECOND);
    for (var cut = 1; cut < tail.limit(); cut++) {
      final var bytes = ByteBuffer.allocate(prefix.limit() + cut);
      bytes.put(prefix.duplicate()).put(tail.duplicate().limit(cut)).flip();
      final var path = write(bytes);
      final var scan = scan(path);
      assertFalse("cut " + cut, scan.intact());
      assertEquals(prefix.limit(), scan.validEnd());
      assertEquals(List.of(FIRST), entries(scan));
      final var dropped = new ArrayList<NameIdMapFile.DroppedRange>();
      assertEquals(List.of(FIRST), entries(scan.salvage(dropped::add)));
      assertEquals(1, dropped.size());
      assertEquals(prefix.limit(), dropped.getFirst().start());
      assertEquals(bytes.limit(), dropped.getFirst().end());
    }
  }

  /**
   * Scan classifies negative lengths, invalid strings, unused payloads and bad hashes as damage.
   * No payload is allocated or decoded until its complete record has passed validation.
   */
  @Test
  public void malformedRecordsNeverAllocateFromUncheckedLengths() throws Exception {
    for (final var length : new int[] {
        NameIdMapFile.INCOMPLETE_REWRITE_LENGTH, Integer.MIN_VALUE, 0xFFFFFFF0}) {
      final var record = image(FIRST);
      record.putInt(8, length);
      assertDamagedRecord(record);
    }
    for (final var length : new int[] {0, 16385, Integer.MAX_VALUE, 1, 4}) {
      final var record = image(FIRST);
      record.putInt(8, length);
      assertDamagedRecord(record);
    }
    for (final var offset : new int[] {16, 22}) {
      for (final var length : new int[] {-1, Integer.MAX_VALUE, 100}) {
        final var record = image(FIRST);
        record.putInt(offset, length);
        rehash(record);
        assertDamagedRecord(record);
      }
    }
    for (final var length : new int[] {0, 1, 4}) {
      final var record = image(FIRST);
      record.putInt(8, length);
      rehash(record);
      assertDamagedRecord(record);
    }
    final var trailing = image(FIRST);
    trailing.putInt(22, 0);
    rehash(trailing);
    assertDamagedRecord(trailing);
    final var badHash = image(FIRST);
    badHash.putLong(0, badHash.getLong(0) ^ 1);
    assertDamagedRecord(badHash);
  }

  private void assertDamagedRecord(ByteBuffer record) throws Exception {
    final var prefix = image(FIRST);
    final var bytes = ByteBuffer.allocate(prefix.limit() + record.limit() + image(SECOND).limit());
    bytes.put(prefix).put(record).put(image(SECOND)).flip();
    final var path = write(bytes);
    final var scan = scan(path);
    assertFalse(scan.intact());
    assertEquals(prefix.limit(), scan.validEnd());
    final var dropped = new ArrayList<NameIdMapFile.DroppedRange>();
    final var kept = scan.salvage(dropped::add);
    assertEquals(List.of(FIRST, SECOND), entries(kept));
    assertEquals(image(FIRST, SECOND), kept.bytes());
    assertTrue(kept.intact());
    assertEquals(1, dropped.size());
    assertEquals(prefix.limit(), dropped.getFirst().start());
    assertEquals(prefix.limit() + record.limit(), dropped.getFirst().end());
  }

  /** Torn lengths, torn payloads and header fragments resynchronize at the later real record. */
  @Test
  public void salvageFindsRecordsAfterTornStructures() throws Exception {
    final var tornLength = image(FIRST);
    tornLength.putInt(8, Integer.MAX_VALUE);
    assertDamagedRecord(tornLength);
    final var tornPayload = ByteBuffer.wrap(java.util.Arrays.copyOf(image(FIRST).array(), 21));
    assertDamagedRecord(tornPayload);
    assertDamagedRecord(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}));
  }

  /** Each separated damaged range is reported once and surviving records retain file order. */
  @Test
  public void salvageReportsMultipleRangesAndKeepsIntactScanUnchanged() throws Exception {
    final var record = image(FIRST);
    record.putLong(0, record.getLong(0) ^ 1);
    final var tail = new byte[] {1, 2, 3};
    final var bytes = ByteBuffer.allocate(record.limit() + image(SECOND, FIRST).limit() + 6);
    bytes.put(record).put(image(SECOND)).put(tail).put(image(FIRST)).put(tail).flip();
    final var source = scan(write(bytes));
    final var dropped = new ArrayList<NameIdMapFile.DroppedRange>();
    final var kept = source.salvage(dropped::add);
    assertEquals(0, source.validEnd());
    assertFalse(source.intact());
    assertEquals(List.of(SECOND, FIRST), entries(kept));
    assertEquals(3, dropped.size());
    assertEquals(FIRST, dropped.getFirst().unverifiedEntry());
    assertEquals(null, dropped.get(1).unverifiedEntry());
    assertEquals(null, dropped.get(2).unverifiedEntry());
    assertEquals(bytes.limit(), dropped.getLast().end());
    assertTrue(kept == kept.salvage(range -> {
      throw new AssertionError("intact scan must not report damage");
    }));
  }

  /**
   * A real process halt after a durable normal commit and torn map leaves recoverable suffix
   * registrations. Open repairs both copies, preserves committed data, and a second open is clean.
   */
  @Test
  public void abruptStopSalvagesTornMapAfterDurableCommit() throws Exception {
    final var root = directory.newFolder().toPath();
    final var offset = DiskStorageMaintenanceFloorTest.crashChild(root, "salvage");
    final var main = root.resolve("maintenanceFloor/name_id_map_v3.cm");
    final var backup = main.resolveSibling("name_id_map_v3_backup.cm");
    assertFalse(scan(main).intact());
    assertFalse(scan(backup).intact());
    assertEquals(offset, scan(backup).validEnd());
    for (var reopen = 0; reopen < 2; reopen++) {
      try (final var logs = LogRecordCollector.attachTo(WOWCache.class);
          final var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
          final var session = manager.open("maintenanceFloor", "admin", "admin")) {
        final var cache = ((AbstractStorage) session.getStorage()).getWriteCache();
        assertTrue("the suffix registration must survive without WAL recreation",
            cache.fileIdByName("salvaged.tst") >= 0);
        assertEquals(-1, cache.fileIdByName("torn.tst"));
        try (final var records = session.query("select count(*) as total from Work")) {
          assertEquals(300L, ((Number) records.next().getProperty("total")).longValue());
        }
        assertTrue(scan(main).intact());
        assertTrue(scan(backup).intact());
        assertArrayEquals(Files.readAllBytes(main), Files.readAllBytes(backup));
        assertEquals(reopen == 0, logs.warnedWithAll("Damaged name-id map", backup.toString(),
            "[" + offset + ",", "name cannot be read"));
        if (reopen == 1) {
          assertFalse(logs.warnedWithAll("Damaged name-id map"));
        }
      }
    }
  }

  /** Oversized copies fail before reading or allocating their contents. */
  @Test
  public void scanRejectsCopiesAboveIntLimit() throws Exception {
    final var channel = mock(FileChannel.class);
    when(channel.size()).thenReturn(1L + Integer.MAX_VALUE);
    final var failure = assertThrows(IOException.class, () -> NameIdMapFile.scan(channel));
    assertTrue(failure.getMessage().contains("exceeds the scan limit"));
    verify(channel, never()).read(any(ByteBuffer.class), anyLong());
  }

  /** Every write, truncate and force can fail before or after its effect without hiding a guard. */
  @Test
  public void rewriteFaultsClassifyEveryGuardedStep() throws Exception {
    for (final var after : new boolean[] {false, true}) {
      for (var step = 1; step <= 9; step++) {
        final var path = write(image(SECOND, FIRST, SECOND));
        try (final var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
          final var faulty = failAt(channel, step, after);
          assertThrows(IOException.class,
              () -> NameIdMapFile.rewrite(faulty, image(FIRST, SECOND), true));
        }
        final var result = scan(path);
        final var completed = step > 8 || (after && step == 8);
        if (step == 1 && !after) {
          assertTrue(result.intact());
          assertEquals(List.of(SECOND, FIRST, SECOND), entries(result));
        } else if (completed) {
          assertTrue(result.intact());
          assertEquals(List.of(FIRST, SECOND), entries(result));
        } else {
          assertFalse(result.intact());
          assertEquals(0, result.validEnd());
        }
      }
    }
  }

  /** Empty rewrites keep the guard until truncation, then expose a valid empty copy. */
  @Test
  public void emptyRewriteFaultsKeepGuardUntilTruncation() throws Exception {
    for (var step = 1; step <= 4; step++) {
      final var path = write(image(FIRST));
      try (final var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        final var faulty = failAt(channel, step, false);
        assertThrows(IOException.class,
            () -> NameIdMapFile.rewrite(faulty, ByteBuffer.allocate(0), true));
      }
      final var result = scan(path);
      assertEquals(step == 1 || step == 4, result.intact());
      assertEquals(step == 1 ? List.of(FIRST) : List.of(), entries(result));
    }
  }

  /** Successful rewrites truncate old tails, preserve input bytes and honor disabled fsync. */
  @Test
  public void rewriteSupportsEmptySingleAndMultipleRecords() throws Exception {
    for (final var image : List.of(ByteBuffer.allocate(0), image(FIRST), image(FIRST, SECOND))) {
      for (final var fsync : new boolean[] {false, true}) {
        final var path = write(image(SECOND, FIRST, SECOND));
        try (final var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
          final var delegate = mock(FileChannel.class, delegatesTo(channel));
          NameIdMapFile.rewrite(delegate, image, fsync);
          if (!fsync) {
            verify(delegate, never()).force(anyBoolean());
          }
        }
        assertTrue(scan(path).intact());
        assertArrayEquals(image.array(), Files.readAllBytes(path));
      }
    }
  }

  /** Creation publishes the full image, including an empty image, for both fsync modes. */
  @Test
  public void creationPublishesOnlyCompleteImages() throws Exception {
    for (final var fsync : new boolean[] {false, true}) {
      for (final var image : List.of(ByteBuffer.allocate(0), image(FIRST, SECOND))) {
        final var path = directory.getRoot().toPath().resolve("new-map");
        NameIdMapFile.create(path, image, fsync, this);
        assertTrue(scan(path).intact());
        assertArrayEquals(image.array(), Files.readAllBytes(path));
        Files.delete(path);
      }
    }
    assertEquals(0, directory.getRoot().list().length);
  }

  /** Write and force failures leave no target or temporary file. Move failure does not publish. */
  @Test
  public void creationFaultsDoNotExposePartialCopy() throws Exception {
    for (var step = 1; step <= 3; step++) {
      final var failStep = step;
      final var path = directory.getRoot().toPath().resolve("new-map");
      try (final var channels = mockStatic(FileChannel.class);
          final var moves = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
        channels.when(() -> FileChannel.open(any(Path.class), any(StandardOpenOption.class)))
            .thenAnswer(invocation -> {
              final Path temporary = invocation.getArgument(0);
              final var channel = temporary.getFileSystem().provider().newFileChannel(temporary,
                  Set.of(StandardOpenOption.WRITE));
              return failAt(channel, failStep, false);
            });
        if (step == 3) {
          moves.when(() -> FileUtils.durableAtomicMove(any(), any(), any()))
              .thenThrow(new IOException("injected move failure"));
        }
        assertThrows(IOException.class, () -> NameIdMapFile.create(path, image(FIRST), true, this));
      }
      assertFalse(Files.exists(path));
      assertEquals(0, directory.getRoot().list().length);
    }
  }

  /** An error after the durable move can leave a target, but that target is already intact. */
  @Test
  public void failureAfterMoveLeavesIntactCopy() throws Exception {
    final var path = directory.getRoot().toPath().resolve("new-map");
    try (final var moves = mockStatic(FileUtils.class)) {
      moves.when(() -> FileUtils.durableAtomicMove(any(), any(), any())).thenAnswer(invocation -> {
        Files.move(invocation.getArgument(0), invocation.getArgument(1),
            StandardCopyOption.ATOMIC_MOVE);
        throw new IOException("injected failure after move");
      });
      assertThrows(IOException.class, () -> NameIdMapFile.create(path, image(FIRST), true, this));
    }
    assertEquals(List.of(FIRST), entries(scan(path)));
    assertTrue(scan(path).intact());
    assertEquals(1, directory.getRoot().list().length);
  }

  private static FileChannel failAt(FileChannel channel, int step, boolean after) throws Exception {
    final var delegate = mock(FileChannel.class, delegatesTo(channel));
    final var count = new AtomicInteger();
    final org.mockito.stubbing.Answer<Object> answer = invocation -> {
      final var fail = count.incrementAndGet() == step;
      if (fail && !after) {
        throw new IOException("injected before step " + step);
      }
      final Object result;
      switch (invocation.getMethod().getName()) {
        case "write" ->
            result = channel.write(invocation.getArgument(0), invocation.getArgument(1));
        case "truncate" -> result = channel.truncate(invocation.getArgument(0));
        case "force" -> {
          channel.force(invocation.getArgument(0));
          result = null;
        }
        default -> throw new AssertionError(invocation.getMethod());
      }
      if (fail && after) {
        throw new IOException("injected after step " + step);
      }
      return result;
    };
    doAnswer(answer).when(delegate).write(any(ByteBuffer.class), anyLong());
    doAnswer(answer).when(delegate).truncate(anyLong());
    doAnswer(answer).when(delegate).force(anyBoolean());
    return delegate;
  }

  private Path write(ByteBuffer bytes) throws IOException {
    final var path = directory.newFile().toPath();
    Files.write(path, bytes.array());
    return path;
  }

  private static NameIdMapFile.Scan scan(Path path) throws IOException {
    try (final var channel = FileChannel.open(path)) {
      return NameIdMapFile.scan(channel);
    }
  }

  private static List<NameFileIdEntry> entries(NameIdMapFile.Scan scan) {
    final var result = new ArrayList<NameFileIdEntry>();
    scan.forEach(result::add);
    return result;
  }

  private static ByteBuffer image(NameFileIdEntry... entries) throws IOException {
    final var records = new ArrayList<ByteBuffer>();
    for (final var entry : entries) {
      records.add(NameIdMapFile.encode(entry, "test"));
    }
    final var image = ByteBuffer.allocate(records.stream().mapToInt(ByteBuffer::remaining).sum());
    records.forEach(image::put);
    return image.flip();
  }

  private static void rehash(ByteBuffer record) {
    record.putLong(0,
        DiskStorage.XX_HASH_64.hash(record, 12, record.getInt(8), DiskStorage.XX_HASH_SEED));
  }
}
