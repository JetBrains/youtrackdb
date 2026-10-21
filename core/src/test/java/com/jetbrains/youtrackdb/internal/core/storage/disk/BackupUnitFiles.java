package com.jetbrains.youtrackdb.internal.core.storage.disk;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.jpountz.xxhash.XXHashFactory;

/**
 * Writes backup unit files with a chosen header for tests of the backup admission.
 *
 * <p>A backup header is the metadata record at the tail of one backup unit file. Every supported
 * header carries the semantic database format of the backed-up database and the creation
 * completion evidence of that database.
 *
 * <p>This helper writes such a header, an old header, and a header of another build. It also
 * writes a header without creation completion evidence, authentic version 1 and 2 headers,
 * and output without any readable header.
 *
 * <p>The written content is arbitrary. Every case of this helper serves an admission decision,
 * which runs before any replay of the content.
 */
public final class BackupUnitFiles {

  /** The unsupported version with a shorter tail and no semantic identity. */
  public static final int VERSION_2 = 2;

  /** The supported version with a 74-byte tail. */
  public static final int VERSION_3 = 3;

  /** The version with a metadata checksum and barrier flag. */
  public static final int VERSION_4 = 4;

  /** The allocation-WAL format with the same header layout as version 4. */
  public static final int VERSION_5 = 5;
  public static final int PREVIOUS_BACKUP_FORMAT_VERSION = VERSION_3;

  /** Version 2 fixture name used by existing restore admission tests. */
  public static final int OLD_BACKUP_FORMAT_VERSION = VERSION_2;
  public static final int LEGACY_BACKUP_FORMAT_VERSION = VERSION_2;
  public static final int FIRST_BACKUP_FORMAT_VERSION = 1;
  public static final int VERSION_6 = 6;
  public static final int CURRENT_BACKUP_FORMAT_VERSION = VERSION_6;
  public static final int FUTURE_BACKUP_FORMAT_VERSION = 7;

  /** The accepted creation completion evidence of this build. */
  public static final int COMPLETED_CREATION_EVIDENCE = DiskStorage.CREATION_COMPLETED_EVIDENCE;

  /** The value of a header without any creation completion evidence. */
  public static final int ABSENT_CREATION_EVIDENCE = DiskStorage.CREATION_EVIDENCE_ABSENT;

  private BackupUnitFiles() {
  }

  /** Returns the database feature format that this build accepts. */
  public static int supportedFeatureFormat() {
    return DiskStorage.supportedBackupSemanticIdentity().featureFormatVersion();
  }

  /** Returns the storage layout version that this build accepts. */
  public static int supportedLayoutVersion() {
    return DiskStorage.supportedBackupSemanticIdentity().storageLayoutVersion();
  }

  /**
   * A date stamp that sorts after every unit of a real backup of this build.
   *
   * <p>The order of one chain comes from the file name, which carries the date stamp before the
   * sequence number. A trailing unit of a test therefore needs a date stamp of the far future.
   */
  public static final String FUTURE_DATE_STAMP = "2099-01-01-00-00-00";

  /** Returns the file name of one backup unit of one database. */
  public static String unitFileName(UUID databaseId, String databaseName, int sequenceNumber) {
    return unitFileName(databaseId, databaseName, sequenceNumber,
        "2021-01-01-00-00-" + String.format("%02d", sequenceNumber));
  }

  /** Returns the file name of one backup unit with a chosen date stamp. */
  public static String unitFileName(UUID databaseId, String databaseName, int sequenceNumber,
      String dateStamp) {
    return databaseId
        + "-"
        + dateStamp
        + "-"
        + sequenceNumber
        + "-"
        + databaseName
        + ".ibu";
  }

  /**
   * Writes one backup unit with the complete accepted header of this build.
   *
   * @param directory the backup directory that receives the unit
   * @param databaseId the database identifier of the unit
   * @param databaseName the database name inside the file name
   * @param sequenceNumber the position of the unit inside its chain
   * @param fullBackup true for the full backup that opens one chain
   * @return the file name of the written unit
   */
  public static String writeSupportedUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup) throws IOException {
    return writeUnit(directory, databaseId, databaseName, sequenceNumber, fullBackup,
        CURRENT_BACKUP_FORMAT_VERSION, supportedFeatureFormat(), supportedLayoutVersion(),
        COMPLETED_CREATION_EVIDENCE, true);
  }

  /**
   * Writes one backup unit with a chosen header.
   *
   * @param backupFormatVersion the backup metadata format version of the header
   * @param featureFormat the database feature format of the header
   * @param layoutVersion the storage layout version of the header
   * @param creationEvidence the creation completion evidence of the header
   * @param validHash true for a hash code that matches the written bytes
   * @return the file name of the written unit
   */
  public static String writeUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup, int backupFormatVersion, int featureFormat,
      int layoutVersion, int creationEvidence, boolean validHash) throws IOException {
    return writeUnit(directory, databaseId, databaseName, sequenceNumber, fullBackup,
        backupFormatVersion, featureFormat, layoutVersion, creationEvidence, validHash,
        unitFileName(databaseId, databaseName, sequenceNumber));
  }

  /**
   * Writes one backup unit with a chosen header under a chosen file name.
   *
   * @param fileName the file name of the unit, which carries the sequence number of the header
   * @return the file name of the written unit
   */
  public static String writeUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup, int backupFormatVersion, int featureFormat,
      int layoutVersion, int creationEvidence, boolean validHash, String fileName)
      throws IOException {
    var startLsn = fullBackup ? null : new LogSequenceNumber(1, 10 * sequenceNumber);
    var endLsn = new LogSequenceNumber(1, 10 * (sequenceNumber + 1));
    return writeUnit(directory, databaseId, databaseName, sequenceNumber, backupFormatVersion,
        featureFormat, layoutVersion, creationEvidence, validHash, fileName, startLsn, endLsn);
  }

  /** Writes one supported incremental unit that carries no change in its LSN interval. */
  public static String writeNoOpIncrementUnit(Path directory, UUID databaseId,
      String databaseName, int sequenceNumber) throws IOException {
    var lsn = new LogSequenceNumber(1, 10 * sequenceNumber);
    return writeUnit(directory, databaseId, databaseName, sequenceNumber,
        CURRENT_BACKUP_FORMAT_VERSION, supportedFeatureFormat(), supportedLayoutVersion(),
        COMPLETED_CREATION_EVIDENCE, true,
        unitFileName(databaseId, databaseName, sequenceNumber), lsn, lsn);
  }

  /** Writes one unit whose start and end LSN values are supplied by an admission test. */
  private static String writeUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber, int backupFormatVersion, int featureFormat, int layoutVersion,
      int creationEvidence, boolean validHash, String fileName,
      LogSequenceNumber startLsn, LogSequenceNumber endLsn) throws IOException {
    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {
      // The content of the unit is arbitrary, because every test case of this helper decides
      // before any replay of that content.
      var content = ("backup unit " + sequenceNumber + " of " + databaseName).getBytes("UTF-8");
      dataOutputStream.write(content);
      if (backupFormatVersion == VERSION_4 || backupFormatVersion == VERSION_5) {
        var offset = outputStream.size() % DiskStorage.IBU_SECTOR_SIZE;
        var pad = offset + DiskStorage.IBU_V4_METADATA_SIZE <= DiskStorage.IBU_SECTOR_SIZE
            ? 0 : DiskStorage.IBU_SECTOR_SIZE - offset;
        dataOutputStream.write(new byte[pad]);
        dataOutputStream.writeLong(0);
        dataOutputStream.writeInt(DiskStorage.BARRIER_ABSENT);
      }

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(databaseId.getLeastSignificantBits());
      dataOutputStream.writeLong(databaseId.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn == null ? -1 : startLsn.getSegment());
      dataOutputStream.writeInt(startLsn == null ? -1 : startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());
      dataOutputStream.writeLong(42L);
      dataOutputStream.writeInt(featureFormat);
      dataOutputStream.writeInt(layoutVersion);
      dataOutputStream.writeInt(creationEvidence);
      dataOutputStream.flush();

      var written = outputStream.toByteArray();
      if (backupFormatVersion == VERSION_4 || backupFormatVersion == VERSION_5) {
        var tailOffset = written.length - (DiskStorage.IBU_V4_METADATA_SIZE - Long.BYTES);
        ByteBuffer.wrap(written, tailOffset, Long.BYTES).putLong(
            DiskStorage.XX_HASH_64.hash(written, tailOffset + Long.BYTES,
                DiskStorage.IBU_V4_METADATA_SIZE - 2 * Long.BYTES,
                DiskStorage.METADATA_HASH_SEED));
      }
      xxHash64.update(written, 0, written.length);
      outputStream.reset();
      outputStream.write(written);
      dataOutputStream.writeLong(validHash ? xxHash64.getValue() : xxHash64.getValue() + 1);
      dataOutputStream.flush();

      var unit = outputStream.toByteArray();
      if (backupFormatVersion == VERSION_6) {
        unit = version6Bytes(content, java.util.Arrays.copyOfRange(unit, content.length,
            unit.length), DiskStorage.BARRIER_ABSENT, null);
        if (!validHash) {
          unit[unit.length - 1] ^= 1;
        }
      }
      Files.write(directory.resolve(fileName), unit);
      return fileName;
    }
  }

  /** Builds a literal v6 fixture from payload and the unchanged 74-byte shared suffix. */
  static byte[] version6Bytes(byte[] payload, byte[] shared, int barrier,
      DiskStorage.BackupContinuity continuity) {
    var offset = payload.length % 512;
    var padding = offset + 122 <= 512 ? 0 : 512 - offset;
    var result = java.util.Arrays.copyOf(payload, payload.length + padding + 122);
    var tail = result.length - 122;
    var buffer = ByteBuffer.wrap(result);
    buffer.position(tail + 8);
    buffer.putInt(barrier).putInt(continuity == null ? 0 : 0x434F4E54);
    if (continuity != null) {
      buffer.putLong(continuity.trackerId().getMostSignificantBits());
      buffer.putLong(continuity.trackerId().getLeastSignificantBits());
      buffer.putLong(continuity.generationId().getMostSignificantBits());
      buffer.putLong(continuity.generationId().getLeastSignificantBits());
    }
    System.arraycopy(shared, 0, result, tail + 48, 74);
    buffer.putShort(tail + 48, (short) 6);
    repairVersion6Hashes(result, true, true);
    return result;
  }

  /** Repairs hashes independently so corruption tests do not mask the other check. */
  static void repairVersion6Hashes(byte[] unit, boolean metadata, boolean content) {
    var tail = unit.length - 122;
    var buffer = ByteBuffer.wrap(unit);
    if (metadata) {
      buffer.putLong(tail, DiskStorage.XX_HASH_64.hash(unit, tail + 8, 106,
          DiskStorage.METADATA_HASH_SEED));
    }
    if (content) {
      buffer.putLong(unit.length - 8, DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - 8,
          DiskStorage.XX_HASH_SEED));
    }
  }

  /** Converts real supported ZIP output without changing payload or shared metadata fields. */
  static void rewriteAsVersion6(Path path, int barrier,
      DiskStorage.BackupContinuity continuity) throws IOException {
    var unit = Files.readAllBytes(path);
    Files.write(path, version6Bytes(zipPayload(unit),
        java.util.Arrays.copyOfRange(unit, unit.length - 74, unit.length), barrier, continuity));
  }

  /** Finds the ZIP end using the explicit source version rather than the current writer size. */
  private static byte[] zipPayload(byte[] unit) throws IOException {
    var version = ByteBuffer.wrap(unit).getShort(unit.length - 74);
    var size = switch (version) {
      case 3 -> 74;
      case 4, 5 -> 86;
      case 6 -> 122;
      default -> throw new IOException("Unsupported fixture source version " + version);
    };
    var zipEnd = -1;
    for (var i = unit.length - size - 22; i >= 0; i--) {
      if (unit[i] == 0x50 && unit[i + 1] == 0x4b
          && unit[i + 2] == 0x05 && unit[i + 3] == 0x06) {
        zipEnd = i + 22 + ((unit[i + 20] & 0xff) | (unit[i + 21] & 0xff) << 8);
        break;
      }
    }
    if (zipEnd < 0) {
      throw new IOException("Missing ZIP end in fixture");
    }
    return java.util.Arrays.copyOf(unit, zipEnd);
  }

  /** Rewrites real supported ZIP output in an explicit historical layout. */
  public static void rewriteAsVersion(Path path, int version) throws IOException {
    if (version != 3 && version != 4 && version != 5) {
      throw new IllegalArgumentException("Expected historical version 3, 4 or 5");
    }
    var unit = Files.readAllBytes(path);
    var payload = zipPayload(unit);
    var size = version == 3 ? 74 : 86;
    var offset = payload.length % 512;
    var padding = version == 3 || offset + size <= 512 ? 0 : 512 - offset;
    var result = java.util.Arrays.copyOf(payload, payload.length + padding + size);
    var tail = result.length - size;
    System.arraycopy(unit, unit.length - 74, result, result.length - 74, 74);
    var buffer = ByteBuffer.wrap(result);
    buffer.putShort(result.length - 74, (short) version);
    if (version != 3) {
      // These fixtures model local forced output.
      buffer.putInt(tail + 8, 0x42415252);
      buffer.putLong(tail, DiskStorage.XX_HASH_64.hash(result, tail + 8, 70,
          DiskStorage.METADATA_HASH_SEED));
    }
    buffer.putLong(result.length - 8, DiskStorage.XX_HASH_64.hash(result, 0,
        result.length - 8, DiskStorage.XX_HASH_SEED));
    Files.write(path, result);
  }

  /**
   * Writes one backup unit without any readable header.
   *
   * <p>A crash inside one backup write leaves such output. This build cannot classify that output,
   * so the output stays in place and blocks the extension of its chain.
   *
   * @return the file name of the written unit
   */
  public static String writeUnreadableUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber) throws IOException {
    return writeUnreadableUnit(directory, databaseId, databaseName, sequenceNumber,
        unitFileName(databaseId, databaseName, sequenceNumber));
  }

  /** Writes one backup unit without any readable header under a chosen file name. */
  public static String writeUnreadableUnit(Path directory, UUID databaseId, String databaseName,
      int sequenceNumber, String fileName) throws IOException {
    var residue = new byte[512];
    for (var index = 0; index < residue.length; index++) {
      residue[index] = (byte) (index % 251);
    }
    Files.write(directory.resolve(fileName), residue);
    return fileName;
  }

  /**
   * Truncates one existing unit to the given length, which breaks its header.
   *
   * <p>The truncation must really shorten the unit, because every caller needs broken output. A
   * length that keeps the whole unit therefore fails instead of writing the same bytes again.
   *
   * @param keptBytes the new length of the unit, which stays below the current length
   */
  public static void truncateUnit(Path unitPath, int keptBytes) throws IOException {
    var content = Files.readAllBytes(unitPath);
    if (keptBytes < 0 || keptBytes >= content.length) {
      throw new IllegalArgumentException(
          "The truncation of " + unitPath + " must shorten the unit of " + content.length
              + " bytes, and the requested length is " + keptBytes);
    }
    Files.write(unitPath, java.util.Arrays.copyOf(content, keptBytes));
  }

  /**
   * Writes one backup unit with an authentic header of the earlier backup format version 2.
   *
   * <p>Version 2 holds no database feature format, no storage layout version, and no creation
   * completion evidence. The tail of such a unit is therefore shorter than the tail of this
   * build. This helper writes that shorter tail, so the fixture matches a real backup chain of an
   * earlier release.
   *
   * <p>The stored hash code follows the version 2 rule, which covers every byte before that hash
   * code. A real legacy unit therefore reaches the header checks of this build with a matching
   * content hash.
   *
   * @return the file name of the written unit
   */
  public static String writeLegacyVersion2Unit(Path directory, UUID databaseId,
      String databaseName, int sequenceNumber, boolean fullBackup) throws IOException {
    return writeLegacyVersion2Unit(directory, databaseId, databaseName, sequenceNumber, fullBackup,
        unitFileName(databaseId, databaseName, sequenceNumber));
  }

  /** Writes one authentic version 2 unit under a chosen file name. */
  public static String writeLegacyVersion2Unit(Path directory, UUID databaseId,
      String databaseName, int sequenceNumber, boolean fullBackup, String fileName)
      throws IOException {
    Files.write(directory.resolve(fileName),
        legacyVersion2UnitBytes(databaseId, databaseName, sequenceNumber, fullBackup));
    return fileName;
  }

  /**
   * Builds the bytes of one authentic version 2 unit.
   *
   * <p>The tail of this unit holds no database feature format, no storage layout version, and no
   * creation completion evidence. The tail is therefore twelve bytes shorter than the tail of
   * this build.
   */
  public static byte[] legacyVersion2UnitBytes(UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup) throws IOException {
    return legacyUnitBytes(databaseId, databaseName, sequenceNumber, fullBackup, VERSION_2);
  }

  /** Writes an authentic version 1 backup with its original 54-byte tail. */
  public static String writeLegacyVersion1Unit(Path directory, UUID databaseId,
      String databaseName, int sequenceNumber, boolean fullBackup) throws IOException {
    return writeLegacyVersion1Unit(directory, databaseId, databaseName, sequenceNumber, fullBackup,
        unitFileName(databaseId, databaseName, sequenceNumber));
  }

  /** Writes an authentic version 1 backup under a chosen file name. */
  public static String writeLegacyVersion1Unit(Path directory, UUID databaseId,
      String databaseName, int sequenceNumber, boolean fullBackup, String fileName)
      throws IOException {
    Files.write(directory.resolve(fileName),
        legacyVersion1UnitBytes(databaseId, databaseName, sequenceNumber, fullBackup));
    return fileName;
  }

  /** Builds the bytes of one authentic version 1 unit. */
  public static byte[] legacyVersion1UnitBytes(UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup) throws IOException {
    return legacyUnitBytes(databaseId, databaseName, sequenceNumber, fullBackup,
        FIRST_BACKUP_FORMAT_VERSION);
  }

  private static byte[] legacyUnitBytes(UUID databaseId, String databaseName,
      int sequenceNumber, boolean fullBackup, int version) throws IOException {
    var startLsn = fullBackup ? null : new LogSequenceNumber(1, 10 * sequenceNumber);
    var endLsn = new LogSequenceNumber(1, 10 * (sequenceNumber + 1));

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {
      // The content is arbitrary and long enough to carry one complete tail of this build in
      // front of the shorter legacy tail.
      var content = ("legacy backup unit " + sequenceNumber + " of " + databaseName
          + " written by an earlier release").getBytes("UTF-8");
      dataOutputStream.write(content);

      dataOutputStream.writeShort(version);
      dataOutputStream.writeLong(databaseId.getLeastSignificantBits());
      dataOutputStream.writeLong(databaseId.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn == null ? -1 : startLsn.getSegment());
      dataOutputStream.writeInt(startLsn == null ? -1 : startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());
      if (version == VERSION_2) {
        dataOutputStream.writeLong(42L);
      }
      dataOutputStream.flush();

      var written = outputStream.toByteArray();
      xxHash64.update(written, 0, written.length);
      dataOutputStream.writeLong(xxHash64.getValue());
      dataOutputStream.flush();

      return outputStream.toByteArray();
    }
  }
}
