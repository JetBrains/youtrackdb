package com.jetbrains.youtrackdb.internal.core.storage.disk;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.Assert;
import org.junit.Test;

public class DiskStorageValidateFileAndFetchBackupMetadataTest {

  @Test
  public void testValidateFileAndFetchBackupMetadata() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataWithContent() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance()
            .newStreamingHash64(DiskStorage.XX_HASH_SEED)) {

      final var content = new byte[1024];
      for (var i = 0; i < content.length; i++) {
        content[i] = (byte) i;
      }

      outputStream.write(content);
      xxHash64.update(content, 0, content.length);

      try (var dataOutputStream = new DataOutputStream(outputStream)) {
        dataOutputStream.writeShort(backupFormatVersion);
        dataOutputStream.writeLong(uuid.getLeastSignificantBits());
        dataOutputStream.writeLong(uuid.getMostSignificantBits());
        dataOutputStream.writeInt(sequenceNumber);
        dataOutputStream.writeLong(startLsn.getSegment());
        dataOutputStream.writeInt(startLsn.getPosition());
        dataOutputStream.writeLong(endLsn.getSegment());
        dataOutputStream.writeInt(endLsn.getPosition());

        dataOutputStream.flush();

        final var metadata = outputStream.toByteArray();
        xxHash64.update(metadata, content.length, metadata.length - content.length);

        final var hashCode = xxHash64.getValue();
        dataOutputStream.writeLong(hashCode);
        dataOutputStream.flush();

        try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            var copyStream = new ByteArrayOutputStream()) {
          final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

          final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
              inputStream, copyStream);

          Assert.assertNotNull(result);
          Assert.assertEquals(backupMetadata, result);
          Assert.assertArrayEquals(outputStream.toByteArray(), copyStream.toByteArray());
        }
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataBrokenHash() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      dataOutputStream.writeLong(123);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataBrokenUUID() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits() + 1);
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataBrokenSequenceNumber() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber + 1);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataFileTooShort() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

    try (var outputStream = new ByteArrayOutputStream()) {
      outputStream.write(new byte[10]);

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataVersionMismatch() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 2;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataFileNameTooShort() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = "short.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataInvalidFileNameUUID() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = "invalid-uuid-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataInvalidLastLsn() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(-1, -1);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataInvalidSequenceNumberInFileName()
      throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-invalid-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataEmptyInputStream() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

    try (var inputStream = new ByteArrayInputStream(new byte[0])) {
      final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
          inputStream, null);

      Assert.assertNull(result);
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataWithNullDbUUID() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        // Pass null for dbUUID - should skip UUID validation
        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", null,
            inputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataWithNullStartLsn() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    // When startLsn is (-1, -1), the returned metadata should have null startLsn
    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, null, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(-1); // startLsn segment
      dataOutputStream.writeInt(-1);  // startLsn position
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
        Assert.assertNull(result.startLsn());
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataWithLargeContent() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance()
            .newStreamingHash64(DiskStorage.XX_HASH_SEED)) {

      // Content larger than 64KB buffer to exercise multiple read iterations
      // and the shifting metadata logic
      final var content = new byte[(100 << 10)]; // 100KB
      for (var i = 0; i < content.length; i++) {
        content[i] = (byte) i;
      }

      outputStream.write(content);
      xxHash64.update(content, 0, content.length);

      try (var dataOutputStream = new DataOutputStream(outputStream)) {
        dataOutputStream.writeShort(backupFormatVersion);
        dataOutputStream.writeLong(uuid.getLeastSignificantBits());
        dataOutputStream.writeLong(uuid.getMostSignificantBits());
        dataOutputStream.writeInt(sequenceNumber);
        dataOutputStream.writeLong(startLsn.getSegment());
        dataOutputStream.writeInt(startLsn.getPosition());
        dataOutputStream.writeLong(endLsn.getSegment());
        dataOutputStream.writeInt(endLsn.getPosition());

        dataOutputStream.flush();

        final var metadataBytes = outputStream.toByteArray();
        xxHash64.update(metadataBytes, content.length, metadataBytes.length - content.length);

        final var hashCode = xxHash64.getValue();
        dataOutputStream.writeLong(hashCode);
        dataOutputStream.flush();

        try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            var copyStream = new ByteArrayOutputStream()) {
          final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

          final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
              inputStream, copyStream);

          Assert.assertNotNull(result);
          Assert.assertEquals(backupMetadata, result);
          Assert.assertArrayEquals(outputStream.toByteArray(), copyStream.toByteArray());
        }
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataInvalidLastLsnSegmentOnly() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(-1); // Invalid segment
      dataOutputStream.writeInt(2);   // Valid position

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataInvalidLastLsnPositionOnly()
      throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(2);  // Valid segment
      dataOutputStream.writeInt(-1);  // Invalid position

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataFileNameUUIDMismatch() throws IOException {
    final var metadataUuid = UUID.randomUUID();
    final var fileNameUuid = UUID.randomUUID(); // Different UUID in filename
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    // The result should still be returned (with warning logged) using the filename UUID
    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, fileNameUuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(metadataUuid.getLeastSignificantBits());
      dataOutputStream.writeLong(metadataUuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        // Use fileNameUuid in filename but metadataUuid in content
        final var fileName = fileNameUuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        // Pass null dbUUID to skip the first UUID check, testing only filename vs metadata UUID mismatch
        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", null,
            inputStream, null);

        // Should return metadata with filename UUID (warning only, no rejection)
        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataBrokenUUIDHigherBits() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits() + 1); // Different higher bits
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNull(result);
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataPartialStartLsnSegmentOnly()
      throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    // When only segment is -1, startLsn should be null
    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, null, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(-1); // startLsn segment is -1
      dataOutputStream.writeInt(1);   // startLsn position is valid
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
        Assert.assertNull(result.startLsn());
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataPartialStartLsnPositionOnly()
      throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    // When only position is -1, startLsn should be null
    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, null, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(1);  // startLsn segment is valid
      dataOutputStream.writeInt(-1);  // startLsn position is -1
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            inputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
        Assert.assertNull(result.startLsn());
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataMissingDashAfterSequenceNumber()
      throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        // Filename without dash after sequence number (missing -db part)
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + ".ibu";

        // Should throw StringIndexOutOfBoundsException since afterSequenceDashIndex == -1
        // and substring(start, -1) throws this exception
        Assert.assertThrows(StringIndexOutOfBoundsException.class, () ->
            DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
                inputStream, null));
      }
    }
  }

  @Test
  public void testValidateFileAndFetchBackupMetadataWithSmallChunkReading() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance()
            .newStreamingHash64(DiskStorage.XX_HASH_SEED)) {

      // Content larger than metadata size to establish metadataCandidate
      final var content = new byte[100];
      for (var i = 0; i < content.length; i++) {
        content[i] = (byte) i;
      }

      outputStream.write(content);
      xxHash64.update(content, 0, content.length);

      try (var dataOutputStream = new DataOutputStream(outputStream)) {
        dataOutputStream.writeShort(backupFormatVersion);
        dataOutputStream.writeLong(uuid.getLeastSignificantBits());
        dataOutputStream.writeLong(uuid.getMostSignificantBits());
        dataOutputStream.writeInt(sequenceNumber);
        dataOutputStream.writeLong(startLsn.getSegment());
        dataOutputStream.writeInt(startLsn.getPosition());
        dataOutputStream.writeLong(endLsn.getSegment());
        dataOutputStream.writeInt(endLsn.getPosition());

        dataOutputStream.flush();

        final var metadataBytes = outputStream.toByteArray();
        xxHash64.update(metadataBytes, content.length, metadataBytes.length - content.length);

        final var hashCode = xxHash64.getValue();
        dataOutputStream.writeLong(hashCode);
        dataOutputStream.flush();

        final var fullData = outputStream.toByteArray();

        // Custom InputStream that returns data in small chunks
        try (var smallChunkInputStream = new InputStream() {
          private int position = 0;
          private static final int chunkSize = 10; // Small chunk size to exercise shifting logic

          @Override
          public int read() throws IOException {
            if (position >= fullData.length) {
              return -1;
            }
            return fullData[position++] & 0xFF;
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            if (position >= fullData.length) {
              return -1;
            }
            var bytesToRead = Math.min(chunkSize, Math.min(len, fullData.length - position));
            System.arraycopy(fullData, position, b, off, bytesToRead);
            position += bytesToRead;
            return bytesToRead;
          }
        }) {
          final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

          final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
              smallChunkInputStream, null);

          Assert.assertNotNull(result);
          Assert.assertEquals(backupMetadata, result);
        }
      }
    }
  }

  /**
   * Test to cover lines 1037-1038: the case when inputStream.read() returns 0. This is a defensive
   * code path that handles streams that may return 0 bytes read. The test returns 0 on the first
   * read, then returns actual data on subsequent reads.
   */
  @Test
  public void testValidateFileAndFetchBackupMetadataWithZeroReadInputStream() throws IOException {
    final var uuid = UUID.randomUUID();
    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, uuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      dataOutputStream.writeLong(uuid.getLeastSignificantBits());
      dataOutputStream.writeLong(uuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      final var fullData = outputStream.toByteArray();

      // Custom InputStream that returns 0 on the first read to trigger the continue branch
      // at lines 1037-1038, then returns actual data on subsequent reads
      try (var zeroReadInputStream = new InputStream() {
        private int position = 0;
        private boolean returnedZero = false;

        @Override
        public int read() throws IOException {
          if (position >= fullData.length) {
            return -1;
          }
          return fullData[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          // Return 0 on the first read to exercise the continue branch (lines 1037-1038)
          if (!returnedZero) {
            returnedZero = true;
            return 0;
          }
          if (position >= fullData.length) {
            return -1;
          }
          var bytesToRead = Math.min(len, fullData.length - position);
          System.arraycopy(fullData, position, b, off, bytesToRead);
          position += bytesToRead;
          return bytesToRead;
        }
      }) {
        final var fileName = uuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", uuid,
            zeroReadInputStream, null);

        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
      }
    }
  }

  /**
   * Test to cover line 1167: the most significant bits comparison in the filename UUID vs metadata
   * UUID check. This test creates UUIDs where least significant bits match but most significant
   * bits differ, forcing evaluation of the second part of the OR condition at line 1167.
   */
  @Test
  public void testValidateFileAndFetchBackupMetadataFileNameUUIDMostSignificantBitsMismatch()
      throws IOException {
    // Create two UUIDs with same least significant bits but different most significant bits
    final var leastSignificantBits = 0x123456789ABCDEF0L;
    final var metadataMostSignificantBits = 0xFEDCBA9876543210L;
    final var fileNameMostSignificantBits = 0xFEDCBA9876543211L; // Different by 1

    final var metadataUuid = new UUID(metadataMostSignificantBits, leastSignificantBits);
    final var fileNameUuid = new UUID(fileNameMostSignificantBits, leastSignificantBits);

    final var sequenceNumber = 1;
    final var startLsn = new LogSequenceNumber(1, 1);
    final var endLsn = new LogSequenceNumber(2, 2);
    final var backupFormatVersion = 1;

    // The result should still be returned (with warning logged) using the filename UUID
    final var backupMetadata = new DiskStorage.BackupMetadata(backupFormatVersion, fileNameUuid,
        sequenceNumber, startLsn, endLsn);

    try (var outputStream = new ByteArrayOutputStream();
        var xxHash64 = XXHashFactory.fastestInstance().newStreamingHash64(DiskStorage.XX_HASH_SEED);
        var dataOutputStream = new DataOutputStream(outputStream)) {

      dataOutputStream.writeShort(backupFormatVersion);
      // Write metadata UUID (different most significant bits than filename)
      dataOutputStream.writeLong(metadataUuid.getLeastSignificantBits());
      dataOutputStream.writeLong(metadataUuid.getMostSignificantBits());
      dataOutputStream.writeInt(sequenceNumber);
      dataOutputStream.writeLong(startLsn.getSegment());
      dataOutputStream.writeInt(startLsn.getPosition());
      dataOutputStream.writeLong(endLsn.getSegment());
      dataOutputStream.writeInt(endLsn.getPosition());

      dataOutputStream.flush();

      final var metadata = outputStream.toByteArray();
      xxHash64.update(metadata, 0, metadata.length);

      final var hashCode = xxHash64.getValue();
      dataOutputStream.writeLong(hashCode);
      dataOutputStream.flush();

      try (var inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
        // Use fileNameUuid in filename (same least significant bits, different most significant bits)
        final var fileName = fileNameUuid + "-2021-01-01-00-00-00-" + sequenceNumber + "-db.ibu";

        // Pass null dbUUID to skip the first UUID check at lines 1131-1140
        // This test specifically covers line 1167 where:
        // - fileNameUUID.getLeastSignificantBits() == metadataUUIDLowerBits (first condition is FALSE)
        // - fileNameUUID.getMostSignificantBits() != metadataUUIDHigherBits (second condition at line 1167 is TRUE)
        final var result = DiskStorage.validateFileAndFetchBackupMetadata(fileName, "db", null,
            inputStream, null);

        // Should return metadata with filename UUID (warning only, no rejection)
        Assert.assertNotNull(result);
        Assert.assertEquals(backupMetadata, result);
      }
    }
  }
}
