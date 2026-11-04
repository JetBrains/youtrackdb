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

/** Exercises historical layouts and current writer padding without database ZIP replay. */
public class BackupFormatVersion4Test {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** The frozen HEAD projection accepts 3/4/5 and refuses sound or damaged v6 without removal. */
  @Test
  public void frozenHeadReaderRefusesVersionSixBeforeApplyingQueuedRemovals() throws Exception {
    var id = UUID.randomUUID();
    var directory = temporaryFolder.newFolder().toPath();
    var names = new java.util.ArrayList<String>();
    for (int version : new int[] {3, 4, 5}) {
      var name = BackupUnitFiles.writeUnit(directory, id, "db", version, false, version,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE, true);
      names.add(name);
      assertEquals("SUPPORTED",
          frozenHeadClassification(Files.readAllBytes(directory.resolve(name)),
              name, id, true));
    }
    var sharedSource = Files.readAllBytes(directory.resolve(names.getLast()));
    var v6 = BackupUnitFiles.version6Bytes(new byte[900],
        Arrays.copyOfRange(sharedSource, sharedSource.length - 74, sharedSource.length),
        DiskStorage.BARRIER_PRESENT, null);
    var future = directory.resolve("v6.ibu");
    Files.write(future, v6);
    for (boolean damaged : new boolean[] {false, true}) {
      var candidate = v6.clone();
      if (damaged) {
        candidate[0] ^= 1;
      }
      assertEquals(6, ByteBuffer.wrap(candidate).getShort(candidate.length - 74));
      for (boolean full : new boolean[] {false, true}) {
        assertEquals("UNCLASSIFIABLE", frozenHeadClassification(candidate, names.getLast(), id,
            full));
        var queued = new java.util.ArrayList<String>();
        var incomplete = sharedSource.clone();
        incomplete[0] ^= 1;
        assertEquals("RECOGNIZED_INCOMPLETE",
            frozenHeadClassification(incomplete, names.getLast(), id, true));
        queued.add(names.getLast());
        var removals = 0;
        if (!frozenHeadClassification(candidate, names.getLast(), id, full)
            .equals("UNCLASSIFIABLE")) {
          for (var ignored : queued) {
            removals++;
          }
        }
        assertEquals(0, removals);
        assertTrue(Files.exists(directory.resolve(names.getLast())));
        org.junit.Assert.assertArrayEquals(v6, Files.readAllBytes(future));
      }
    }
  }

  // Frozen 3850a8f46c reader projection. It deliberately uses no production classifier or layout.
  static String frozenHeadClassification(byte[] unit, String name, UUID expected,
      boolean full) {
    var window = Arrays.copyOfRange(unit, Math.max(0, unit.length - 86), unit.length);
    var version = ByteBuffer.wrap(window).getShort(window.length - 74);
    var size = version == 4 || version == 5 ? 86 : 74;
    var tail = Arrays.copyOfRange(window, window.length - size, window.length);
    var shared = ByteBuffer.wrap(tail, tail.length - 74, 74).slice();
    var supported = version == 3 || version == 4 || version == 5;
    var database = new UUID(shared.getLong(10), shared.getLong(2));
    var recognized = supported && expected.equals(database)
        && shared.getInt(54) == BackupUnitFiles.supportedFeatureFormat()
        && shared.getInt(58) == BackupUnitFiles.supportedLayoutVersion()
        && shared.getInt(62) == BackupUnitFiles.COMPLETED_CREATION_EVIDENCE;
    // Authentic shorter v1/v2 diagnostics precede the full-hash decision at HEAD.
    if (!supported) {
      for (int legacy : new int[] {2, 1}) {
        var offset = legacy == 2 ? 12 : 20;
        var bytes = ByteBuffer.wrap(tail);
        if (bytes.getShort(offset) == legacy
            && name.startsWith(new UUID(bytes.getLong(offset + 10), bytes.getLong(offset + 2))
                + "-")) {
          return "UNCLASSIFIABLE";
        }
      }
    }
    if (full && DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - 8,
        DiskStorage.XX_HASH_SEED) != shared.getLong(66)) {
      return recognized ? "RECOGNIZED_INCOMPLETE" : "UNCLASSIFIABLE";
    }
    if (version == 4 || version == 5) {
      var bytes = ByteBuffer.wrap(tail);
      if (bytes.getLong(0) != DiskStorage.XX_HASH_64.hash(tail, 8, 70,
          DiskStorage.METADATA_HASH_SEED)) {
        return "UNCLASSIFIABLE";
      }
    }
    return supported && recognized ? "SUPPORTED" : "UNCLASSIFIABLE";
  }

  /** Boundary positions need either zero padding or exactly the rest of one sector. */
  @Test
  public void paddingNeverLetsTailCrossASector() {
    for (var length : new int[] {0, 1, 389, 390, 391, 511, 512, 513, 902, 903, 1023}) {
      var padding = DiskStorage.backupPadding(length);
      var start = length + padding;
      assertTrue(padding >= 0 && padding < DiskStorage.IBU_SECTOR_SIZE);
      assertTrue((start % DiskStorage.IBU_SECTOR_SIZE) + 122
          <= DiskStorage.IBU_SECTOR_SIZE);
      if (padding > 0) {
        assertTrue((length % DiskStorage.IBU_SECTOR_SIZE) + 122
            > DiskStorage.IBU_SECTOR_SIZE);
      }
    }
    assertEquals(0, DiskStorage.backupPadding(390));
    assertEquals(121, DiskStorage.backupPadding(391));
    assertEquals(1, DiskStorage.backupPadding(511));
    assertEquals(0, DiskStorage.backupPadding(512));
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

    // A 390-byte prefix needs no padding. A 391-byte prefix needs 121 zero bytes.
    // The remaining cases cover sector boundaries and repeated sectors.
    for (var length : new int[] {390, 391, 511, 512, 513, 902, 903}) {
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
          var expectedPadding = length % DiskStorage.IBU_SECTOR_SIZE <= 390
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
              written.length % DiskStorage.IBU_SECTOR_SIZE + 122
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
    var name = historicalUnit(folder, id);
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
    var name = historicalUnit(folder, id);
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
    var name = historicalUnit(folder, id);
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

  private static String historicalUnit(java.nio.file.Path folder, UUID id) throws Exception {
    return BackupUnitFiles.writeUnit(folder, id, "db", 0, true, 5,
        BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
        BackupUnitFiles.COMPLETED_CREATION_EVIDENCE, true);
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
