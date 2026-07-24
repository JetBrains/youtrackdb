package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests of the per-record spill buffer (design M2.a-3, ruling Q-M1): the threshold
 * boundary (exactly-at-threshold stays in memory, one byte more spills), content fidelity in
 * both modes, and the pinned spill-file lifecycle (deleted on every path).
 */
public class SpillableRecordBufferTest {

  private Path spillDirectory;

  @Before
  public void setUp() throws IOException {
    spillDirectory = Files.createTempDirectory("spill-buffer-test");
  }

  @After
  public void tearDown() throws IOException {
    try (var files = Files.list(spillDirectory)) {
      for (var file : files.toList()) {
        Files.deleteIfExists(file);
      }
    }
    Files.deleteIfExists(spillDirectory);
  }

  private long spillFileCount() throws IOException {
    try (var files = Files.list(spillDirectory)) {
      return files.count();
    }
  }

  private static byte[] randomBytes(int length) {
    var bytes = new byte[length];
    new Random(42).nextBytes(bytes);
    return bytes;
  }

  /** A record of EXACTLY the threshold size stays in memory (the boundary case). */
  @Test
  public void contentAtThresholdStaysInMemory() throws IOException {
    var content = randomBytes(128);
    try (var buffer = new SpillableRecordBuffer(128, spillDirectory)) {
      buffer.write(content);
      assertFalse("exactly-at-threshold content must stay in memory", buffer.spilled());
      assertEquals(128, buffer.size());
      try (var in = buffer.openContent()) {
        assertArrayEquals(content, in.readAllBytes());
      }
      assertEquals("no spill file must have been created", 0, spillFileCount());
    }
  }

  /** One byte beyond the threshold spills; the content round-trips byte-for-byte. */
  @Test
  public void contentBeyondThresholdSpillsAndRoundTrips() throws IOException {
    var content = randomBytes(129);
    try (var buffer = new SpillableRecordBuffer(128, spillDirectory)) {
      buffer.write(content);
      assertTrue("beyond-threshold content must spill", buffer.spilled());
      assertEquals(1, spillFileCount());
      try (var in = buffer.openContent()) {
        assertArrayEquals(content, in.readAllBytes());
      }
    }
    assertEquals("the spill file must be deleted on close", 0, spillFileCount());
  }

  /** The single-byte write path crosses the threshold correctly too. */
  @Test
  public void singleByteWritesCrossThreshold() throws IOException {
    try (var buffer = new SpillableRecordBuffer(4, spillDirectory)) {
      for (var i = 0; i < 6; i++) {
        buffer.write(i);
      }
      assertTrue(buffer.spilled());
      try (var in = buffer.openContent()) {
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5}, in.readAllBytes());
      }
    }
    assertEquals(0, spillFileCount());
  }

  /**
   * The discard path (a render failure closes the buffer WITHOUT copying it out) deletes the
   * spill file too — deletion on EVERY path is the pinned lifecycle.
   */
  @Test
  public void discardWithoutCopyOutDeletesSpillFile() throws IOException {
    try (var buffer = new SpillableRecordBuffer(8, spillDirectory)) {
      buffer.write(randomBytes(64));
      assertTrue(buffer.spilled());
      assertEquals(1, spillFileCount());
      // no openContent(): the record is discarded whole
    }
    assertEquals("the spill file must be deleted on the discard path", 0, spillFileCount());
  }
}
