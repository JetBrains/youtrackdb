package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the v4 tail independently of database ZIP replay. */
public class BackupFormatVersion4Test {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Boundary positions need either zero padding or exactly the rest of one sector. */
  @Test
  public void paddingNeverLetsTailCrossASector() {
    for (var length : new int[] {0, 1, 425, 426, 427, 511, 512, 513, 938, 939, 1023}) {
      var padding = DiskStorage.backupPadding(length);
      var start = length + padding;
      assertTrue(padding >= 0 && padding < DiskStorage.IBU_SECTOR_SIZE);
      assertTrue((start % DiskStorage.IBU_SECTOR_SIZE) + DiskStorage.IBU_V4_METADATA_SIZE
          <= DiskStorage.IBU_SECTOR_SIZE);
      if (padding > 0) {
        assertTrue((length % DiskStorage.IBU_SECTOR_SIZE) + DiskStorage.IBU_V4_METADATA_SIZE
            > DiskStorage.IBU_SECTOR_SIZE);
      }
    }
    assertEquals(0, DiskStorage.backupPadding(426));
    assertEquals(85, DiskStorage.backupPadding(427));
    assertTrue(DiskStorage.IBU_V4_METADATA_SIZE <= DiskStorage.IBU_SECTOR_SIZE);
  }

  /** Every writer overload counts its bytes before real padding, including sector boundaries. */
  @Test
  public void hashingStreamCountsAllWritePathsForSectorPadding() throws Exception {
    var type = Class.forName(DiskStorage.class.getName() + "$XXHashOutputStream");
    var constructor = type.getDeclaredConstructor(OutputStream.class);
    constructor.setAccessible(true);
    var count = type.getDeclaredField("byteCount");
    count.setAccessible(true);
    var pad = DiskStorage.class.getDeclaredMethod("writeBackupPadding", type);
    pad.setAccessible(true);

    // The 426-byte prefix needs no padding, while 427 bytes need 85 zero bytes.
    // The remaining cases cover both sides of a sector boundary.
    for (var length : new int[] {426, 427, 511, 512, 513}) {
      for (var path = 0; path < 3; path++) {
        var destination = new ByteArrayOutputStream();
        try (var writer = (OutputStream) constructor.newInstance(destination)) {
          var payload = new byte[length];
          Arrays.fill(payload, (byte) 0x5a);
          if (path == 0) {
            for (var value : payload) {
              writer.write(value);
            }
          } else if (path == 1) {
            writer.write(payload);
          } else {
            var withOffset = new byte[length + 2];
            Arrays.fill(withOffset, 1, length + 1, (byte) 0x5a);
            writer.write(withOffset, 1, length);
          }
          assertEquals("every write path must count the entire payload", length,
              count.getLong(writer));
          pad.invoke(null, writer);
          var expectedPadding = length % DiskStorage.IBU_SECTOR_SIZE <= 426
              ? 0 : DiskStorage.IBU_SECTOR_SIZE - length % DiskStorage.IBU_SECTOR_SIZE;
          assertEquals("padding must use the counted payload length", length + expectedPadding,
              count.getLong(writer));
          var written = destination.toByteArray();
          assertEquals(length + expectedPadding, written.length);
          for (var index = 0; index < length; index++) {
            assertEquals("padding must not replace payload bytes", 0x5a, written[index] & 0xff);
          }
          for (var index = length; index < written.length; index++) {
            assertEquals("the written padding must contain zeros", 0, written[index]);
          }
          assertTrue("the tail must fit inside one sector after the padding",
              written.length % DiskStorage.IBU_SECTOR_SIZE + DiskStorage.IBU_V4_METADATA_SIZE
                  <= DiskStorage.IBU_SECTOR_SIZE);
        }
      }
    }
  }

  /** The v3 rule checks the complete v4-layout prefix and reads version 5 at EOF-74. */
  @Test
  public void olderReaderSeesVersionFiveAfterAValidFullHash() throws Exception {
    var id = UUID.randomUUID();
    var folder = temporaryFolder.newFolder().toPath();
    var name = BackupUnitFiles.writeSupportedUnit(folder, id, "db", 0, true);
    var unit = Files.readAllBytes(folder.resolve(name));
    var sharedOffset = unit.length - 74;
    assertEquals(BackupUnitFiles.VERSION_5,
        ByteBuffer.wrap(unit, sharedOffset, Short.BYTES).getShort());
    assertEquals(DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES,
        DiskStorage.XX_HASH_SEED),
        ByteBuffer.wrap(unit, unit.length - Long.BYTES,
            Long.BYTES).getLong());
    var tail =
        Arrays.copyOfRange(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE, unit.length);
    assertFalse(DiskStorage.hasBackupBarrier(tail));
    assertTrue((unit.length - tail.length) % DiskStorage.IBU_SECTOR_SIZE + tail.length
        <= DiskStorage.IBU_SECTOR_SIZE);
    assertNotNull(DiskStorage.inspectBackupUnit(name, "db", id,
        new ByteArrayInputStream(unit), null).metadata());
  }

  /** Only the exact present marker counts. Absent and damaged values count as absent. */
  @Test
  public void barrierFlagRequiresItsExactMarker() throws Exception {
    var id = UUID.randomUUID();
    var folder = temporaryFolder.newFolder().toPath();
    var name = BackupUnitFiles.writeSupportedUnit(folder, id, "db", 0, true);
    var unit = Files.readAllBytes(folder.resolve(name));
    for (var marker : new int[] {DiskStorage.BARRIER_PRESENT, DiskStorage.BARRIER_ABSENT,
        DiskStorage.BARRIER_PRESENT ^ 1}) {
      var tail = Arrays.copyOfRange(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE,
          unit.length);
      ByteBuffer.wrap(tail, Long.BYTES, Integer.BYTES).putInt(marker);
      assertEquals(marker == DiskStorage.BARRIER_PRESENT, DiskStorage.hasBackupBarrier(tail));
      ByteBuffer.wrap(tail).putLong(DiskStorage.XX_HASH_64.hash(tail, Long.BYTES,
          tail.length - 2 * Long.BYTES, DiskStorage.METADATA_HASH_SEED));
      System.arraycopy(tail, 0, unit, unit.length - tail.length, tail.length);
      ByteBuffer.wrap(unit, unit.length - Long.BYTES, Long.BYTES).putLong(
          DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES,
              DiskStorage.XX_HASH_SEED));
      assertNotNull(DiskStorage.inspectBackupUnit(name, "db", id,
          new ByteArrayInputStream(unit), null).metadata());
    }
  }

  /** A broken metadata checksum rejects a unit even if the outer full hash is correct. */
  @Test
  public void metadataChecksumMismatchRejectsFullInspection() throws Exception {
    var id = UUID.randomUUID();
    var folder = temporaryFolder.newFolder().toPath();
    var name = BackupUnitFiles.writeSupportedUnit(folder, id, "db", 0, true);
    var unit = Files.readAllBytes(folder.resolve(name));
    ByteBuffer.wrap(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE, Long.BYTES)
        .putLong(0);
    ByteBuffer.wrap(unit, unit.length - Long.BYTES, Long.BYTES).putLong(
        DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES, DiskStorage.XX_HASH_SEED));
    var inspected = DiskStorage.inspectBackupUnit(name, "db", id,
        new ByteArrayInputStream(unit), null);
    assertNull(inspected.metadata());
    assertFalse("a failed metadata check with a valid full hash is not removable output",
        inspected.contentCheckFailed());
  }

  /** An intermediate image with a valid header and zero final hash is never admitted. */
  @Test
  public void intermediateTailWithZeroFullHashIsRejected() throws Exception {
    var id = UUID.randomUUID();
    var folder = temporaryFolder.newFolder().toPath();
    var name = BackupUnitFiles.writeSupportedUnit(folder, id, "db", 0, true);
    var unit = Files.readAllBytes(folder.resolve(name));
    Arrays.fill(unit, unit.length - Long.BYTES, unit.length, (byte) 0);
    var inspected = DiskStorage.inspectBackupUnit(name, "db", id,
        new ByteArrayInputStream(unit), null);
    assertNull(inspected.metadata());
    assertTrue(inspected.contentCheckFailed());
  }
}
