package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.UnsupportedBackupException;
import com.jetbrains.youtrackdb.internal.core.gremlin.BackupForceCapability;
import com.jetbrains.youtrackdb.internal.core.gremlin.BackupTailReadCapability;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the admission of an existing backup chain before one incremental backup extends it.
 *
 * <p>A backup header is the metadata record at the tail of one backup unit file. Every supported
 * header carries the semantic database format of the backed-up database and evidence of a finished
 * creation of that database.
 *
 * <p>The extension inspects the existing chain without any change of that chain. Automatic removal
 * covers recognized incomplete output of this build alone. Every other unreadable or
 * unclassifiable unit stays in place and refuses the extension, because such a unit can hold a
 * valuable backup of another build.
 *
 * <p>Each test of this class states one scenario and one expected outcome in its own comment.
 */
public class IncrementalBackupExtensionTest {

  private static final String ADMIN = "admin";
  private static final String PASSWORD = "adminpwd";
  private static final String RECORD_CLASS = "BackedUpClass";
  private static final String SOURCE = "backupExtensionSource";

  private Path root;
  private Path databasesPath;
  private Path backupPath;

  @Before
  public void createDirectories() throws Exception {
    root = Files.createTempDirectory("backup-extension-");
    databasesPath = Files.createDirectories(root.resolve("databases"));
    backupPath = Files.createDirectories(root.resolve("backup"));
  }

  @After
  public void deleteDirectories() {
    FileUtils.deleteRecursively(root.toFile());
  }

  /**
   * One incremental backup extends a chain of supported units.
   *
   * <p>The scenario takes one full backup and then two incremental backups of the same database.
   * The second increment admits the unit below the head of the chain from its header alone. The
   * expected outcome has two parts. The backup directory holds three units. Every unit carries
   * the accepted header of this build, so every unit passes the admission of a later restore.
   */
  @Test
  public void incrementalBackupExtendsAChainOfSupportedUnits() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      assertNotNull(storage.fullBackup(backupPath));
      addOneRecord(youTrackDB);
      assertNotNull(storage.backup(backupPath));
      addOneRecord(youTrackDB);
      // The third unit proves that the header-only admission of the units below the head
      // accepts a supported chain.
      assertNotNull(storage.backup(backupPath));

      var units = unitNames();
      assertEquals("the chain must hold the full backup and two increments", 3, units.size());
      for (var unit : units) {
        assertNotNull(
            "every written unit must carry the accepted header of this build",
            inspectUnit(unit, storage.getUuid()).metadata());
      }
    }
  }

  /**
   * Extending a version 3 chain writes version 5 without rewriting any older unit.
   *
   * <p>The full backup has real content, but its header identifies the previous release. The
   * extension must admit it and write a current-version increment while keeping its bytes.
   */
  @Test
  public void incrementalBackupExtendsPreviousVersionChainWithCurrentVersion() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullUnit = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteVersion4AsVersion3(backupPath.resolve(fullUnit));
      var originalBytes = Files.readAllBytes(backupPath.resolve(fullUnit));

      addOneRecord(youTrackDB);
      var newUnit = storage.backup(backupPath);

      assertTrue(Files.exists(backupPath.resolve(newUnit)));
      assertEquals(2, unitNames().size());
      org.junit.Assert.assertArrayEquals("the original unit must stay unchanged", originalBytes,
          Files.readAllBytes(backupPath.resolve(fullUnit)));
      assertEquals(BackupUnitFiles.PREVIOUS_BACKUP_FORMAT_VERSION,
          inspectUnit(fullUnit, storage.getUuid()).metadata().backupFormatVersion());
      assertEquals(BackupUnitFiles.CURRENT_BACKUP_FORMAT_VERSION,
          inspectUnit(newUnit, storage.getUuid()).metadata().backupFormatVersion());
    }
  }

  /**
   * A future-version unit at the head refuses extension without removing or replacing any file.
   */
  @Test
  public void incrementalBackupRefusesFutureVersionHeadAndKeepsEveryFile() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.FUTURE_BACKUP_FORMAT_VERSION,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var contentBeforeBackup = unitContent();

      var refusal =
          assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertTrue(refusal.getMessage(), refusal.getMessage().contains("version 6"));
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, and 5"));
      assertEquals("no existing file may change", contentBeforeBackup, unitContent());
    }
  }

  /** A broken version 4 trailing unit remains removable after version 5 is introduced. */
  @Test
  public void incrementalBackupRemovesIncompleteVersion4TrailingUnit() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullUnit = storage.fullBackup(backupPath);
      var damagedUnit = BackupUnitFiles.writeUnit(backupPath, storage.getUuid(), SOURCE, 1, false,
          BackupUnitFiles.VERSION_4,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE, false,
          BackupUnitFiles.unitFileName(storage.getUuid(), SOURCE, 1,
              BackupUnitFiles.FUTURE_DATE_STAMP));

      var newUnit = storage.backup(backupPath);

      assertFalse("the incomplete version 4 unit must be removed",
          Files.exists(backupPath.resolve(damagedUnit)));
      assertTrue(Files.exists(backupPath.resolve(fullUnit)));
      assertTrue(Files.exists(backupPath.resolve(newUnit)));
    }
  }

  /**
   * One incremental backup removes recognized incomplete trailing output.
   *
   * <p>A version 3 unit can have a complete header over broken content. The scenario appends
   * such a unit after one full backup. The expected outcome
   * has three parts. The backup removes that trailing unit. The backup writes one new unit. The
   * full backup of the chain survives.
   */
  @Test
  public void incrementalBackupRemovesRecognizedIncompleteTrailingUnit() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullBackupUnit = storage.fullBackup(backupPath);
      var incompleteUnit = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_3,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var damaged = Files.readAllBytes(backupPath.resolve(incompleteUnit));
      damaged[0] ^= 1;
      Files.write(backupPath.resolve(incompleteUnit), damaged);

      addOneRecord(youTrackDB);
      var newUnit = storage.backup(backupPath);

      assertFalse(
          "the backup must remove the recognized incomplete trailing unit",
          Files.exists(backupPath.resolve(incompleteUnit)));
      assertTrue("the backup must write one new unit", Files.exists(backupPath.resolve(newUnit)));
      assertTrue(
          "the full backup of the chain must survive",
          Files.exists(backupPath.resolve(fullBackupUnit)));
    }
  }

  /**
   * One incremental backup refuses an old trailing unit and changes no file.
   *
   * <p>The synthetic unit uses the unsupported version 2 number in the version 3 header layout.
   * The scenario appends it after one full backup. The expected outcome has three parts. The
   * backup reports the unsupported chain. Every existing unit keeps its bytes. The backup writes no
   * new unit.
   */
  @Test
  public void incrementalBackupRefusesAnOldTrailingUnitAndKeepsEveryFile() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.OLD_BACKUP_FORMAT_VERSION,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var contentBeforeBackup = unitContent();

      var refusal =
          assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertTrue(
          "the refusal must name the unsupported header, saw: " + refusal.getMessage(),
          refusal.getMessage().contains("carries no supported backup header"));
      assertEquals(
          "the refused backup must keep every existing unit unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * One incremental backup refuses unreadable trailing residue and changes no file.
   *
   * <p>A crash inside one backup write can leave output without any readable header. This build
   * cannot classify such output, so the output stays in place and needs an operator decision. The
   * scenario appends such residue after one full backup. The expected outcome has two parts. The
   * backup reports the unsupported chain. Every existing file keeps its bytes.
   */
  @Test
  public void incrementalBackupRefusesUnreadableTrailingResidueAndKeepsEveryFile()
      throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      BackupUnitFiles.writeUnreadableUnit(backupPath, storage.getUuid(), SOURCE, 1,
          BackupUnitFiles.unitFileName(storage.getUuid(), SOURCE, 1,
              BackupUnitFiles.FUTURE_DATE_STAMP));
      var contentBeforeBackup = unitContent();

      assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertEquals(
          "the refused backup must keep every existing file unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * One incremental backup refuses a trailing unit of another database feature format.
   *
   * <p>A database feature format names the storage features of one database. A unit of another
   * feature format can hold a backup of a newer build, so this build never removes that unit. The
   * scenario appends such a unit after one full backup. The expected outcome has two parts. The
   * backup reports the unsupported chain. Every existing file keeps its bytes.
   */
  @Test
  public void incrementalBackupRefusesATrailingUnitOfAnotherFeatureFormat() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.CURRENT_BACKUP_FORMAT_VERSION,
          BackupUnitFiles.supportedFeatureFormat() + 1, BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var contentBeforeBackup = unitContent();

      assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertEquals(
          "the refused backup must keep every existing file unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * One incremental backup refuses a trailing unit without creation completion evidence.
   *
   * <p>Such a unit is complete output of a database without a finished creation. The unit is no
   * incomplete output of this build, so no automatic removal covers it. The scenario appends such
   * a unit after one full backup. The expected outcome has two parts. The backup reports the
   * unsupported chain. Every existing file keeps its bytes.
   */
  @Test
  public void incrementalBackupRefusesATrailingUnitWithoutCreationEvidence() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.CURRENT_BACKUP_FORMAT_VERSION,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.ABSENT_CREATION_EVIDENCE);
      var contentBeforeBackup = unitContent();

      assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertEquals(
          "the refused backup must keep every existing file unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * One full backup still replaces every existing unit of its database.
   *
   * <p>The overwrite behavior of the full backup stays unchanged. An operator therefore migrates
   * by writing one full backup into a new empty location. The scenario writes one old unit and
   * then takes one full backup into the same directory. The expected outcome has two parts. The
   * old unit leaves. The new full backup unit is the only unit of the directory.
   */
  @Test
  public void fullBackupStillReplacesEveryExistingUnitOfItsDatabase() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var oldUnit =
          writeTrailingUnitOfFormat(storage.getUuid(),
              BackupUnitFiles.OLD_BACKUP_FORMAT_VERSION,
              BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
              BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);

      var newUnit = storage.fullBackup(backupPath);

      assertFalse(
          "the full backup must replace the existing unit",
          Files.exists(backupPath.resolve(oldUnit)));
      assertEquals("the directory must hold the new full backup alone", List.of(newUnit),
          unitNames());
    }
  }

  /**
   * One incremental backup refuses an unsupported unit below a supported chain head.
   *
   * <p>The inspection covers every existing unit, not the trailing units alone. An old unit below
   * one supported increment therefore refuses the extension. The scenario replaces the full
   * backup of one two-unit chain by an old unit and keeps the supported increment as the newest
   * unit.
   *
   * <p>The expected outcome has three parts. The backup reports the unsupported chain. Every
   * existing unit keeps its bytes. The backup writes no new unit.
   */
  @Test
  public void incrementalBackupRefusesAnUnsupportedUnitBelowASupportedHead() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullBackupUnit = storage.fullBackup(backupPath);
      addOneRecord(youTrackDB);
      var incrementUnit = storage.backup(backupPath);
      // The old unit takes the name and the position of the full backup, so the newest unit of
      // the chain stays supported.
      BackupUnitFiles.writeUnit(backupPath, storage.getUuid(), SOURCE, 0, true,
          BackupUnitFiles.OLD_BACKUP_FORMAT_VERSION, BackupUnitFiles.supportedFeatureFormat(),
          BackupUnitFiles.supportedLayoutVersion(), BackupUnitFiles.COMPLETED_CREATION_EVIDENCE,
          true, fullBackupUnit);
      var contentBeforeBackup = unitContent();

      var refusal =
          assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertTrue(
          "the refusal must name the unsupported unit " + fullBackupUnit + ", saw: "
              + refusal.getMessage(),
          refusal.getMessage().contains(fullBackupUnit));
      assertEquals(
          "the refused backup must keep every existing unit unchanged",
          contentBeforeBackup,
          unitContent());
      assertEquals(
          "the refused backup must write no new unit",
          List.of(fullBackupUnit, incrementUnit).stream().sorted().toList(),
          unitNames());
    }
  }

  /**
   * One incremental backup refuses an authentic version 2 unit below a supported chain head.
   *
   * <p>A unit of the earlier release carries a shorter header tail, and its stored hash code
   * still matches its content. Such a unit can hold a valuable backup, so no automatic removal
   * covers it. The scenario replaces the full backup of one two-unit chain by an authentic
   * version 2 unit.
   *
   * <p>The expected outcome has two parts. The refusal names version 2 and all three accepted
   * versions. Every existing unit keeps its bytes.
   */
  @Test
  public void incrementalBackupRefusesAnAuthenticVersion2UnitBelowASupportedHead()
      throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullBackupUnit = storage.fullBackup(backupPath);
      addOneRecord(youTrackDB);
      storage.backup(backupPath);
      BackupUnitFiles.writeLegacyVersion2Unit(backupPath, storage.getUuid(), SOURCE, 0, true,
          fullBackupUnit);
      var contentBeforeBackup = unitContent();

      var refusal = assertThrows(UnsupportedBackupException.class,
          () -> storage.backup(backupPath));

      assertTrue(refusal.getMessage(), refusal.getMessage().contains("version 2"));
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, and 5"));
      assertEquals(
          "the refused backup must keep every existing unit unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * A real version 1 unit below a supported head refuses extension without changing files.
   * The refusal names version 1 and all three accepted versions.
   */
  @Test
  public void incrementalBackupRefusesAnAuthenticVersion1UnitBelowASupportedHead()
      throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullUnit = storage.fullBackup(backupPath);
      addOneRecord(youTrackDB);
      storage.backup(backupPath);
      BackupUnitFiles.writeLegacyVersion1Unit(backupPath, storage.getUuid(), SOURCE, 0, true,
          fullUnit);
      var original = unitContent();

      var refusal = assertThrows(UnsupportedBackupException.class,
          () -> storage.backup(backupPath));

      assertTrue(refusal.getMessage(), refusal.getMessage().contains("version 1"));
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, and 5"));
      assertEquals("the refused backup keeps every unit", original, unitContent());
    }
  }

  /**
   * One incremental backup inspects the whole chain before it removes any trailing output.
   *
   * <p>The removal of recognized incomplete output follows the complete inspection. The scenario
   * combines one unclassifiable full backup with one recognized incomplete trailing unit. The
   * expected outcome has three parts. The backup reports the unsupported chain. The trailing unit
   * survives, which proves that no removal precedes the refusal. Every existing unit keeps its
   * bytes.
   */
  @Test
  public void incrementalBackupKeepsRecognizedIncompleteOutputOfARefusedChain() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullBackupUnit = storage.fullBackup(backupPath);
      // The full backup becomes unreadable residue, which this build cannot classify.
      BackupUnitFiles.writeUnreadableUnit(backupPath, storage.getUuid(), SOURCE, 0,
          fullBackupUnit);
      var incompleteUnit = writeTrailingUnit(storage.getUuid(), false);
      var contentBeforeBackup = unitContent();

      assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));

      assertTrue(
          "the refused backup must keep the recognized incomplete trailing unit",
          Files.exists(backupPath.resolve(incompleteUnit)));
      assertEquals(
          "the refused backup must keep every existing unit unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /** A v3 full unit followed by real v5 increments extends and replays as one mixed chain. */
  @Test
  public void mixedVersionChainExtendsAndRestoresPastPaddingAndTail() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteVersion4AsVersion3(backupPath.resolve(full));
      addOneRecord(youTrackDB);
      var first = storage.backup(backupPath);
      assertEquals(BackupUnitFiles.VERSION_3,
          inspectUnit(full, storage.getUuid()).metadata().backupFormatVersion());
      assertEquals(BackupUnitFiles.VERSION_5,
          inspectUnit(first, storage.getUuid()).metadata().backupFormatVersion());
      addOneRecord(youTrackDB);
      storage.backup(backupPath);
      youTrackDB.internal.restore("mixedVersionRestore", backupPath.toString(), null, null);
      try (var restored = youTrackDB.open("mixedVersionRestore", ADMIN, PASSWORD)) {
        assertEquals(3, restored.query("select from " + RECORD_CLASS).stream().count());
      }
    }
  }

  /** A plain output stream records no barrier and emits one information-level signal. */
  @Test
  public void plainOutputStreamWritesAnAbsentBarrier() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new ByteArrayOutputStream();
      var logger = Logger.getLogger(DiskStorage.class.getName());
      var previousLevel = logger.getLevel();
      var records = new CopyOnWriteArrayList<LogRecord>();
      Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      };
      handler.setLevel(Level.ALL);
      logger.addHandler(handler);
      logger.setLevel(Level.ALL);
      String name;
      try {
        name = storage.backup(() -> List.<String>of().iterator(),
            file -> InputStream.nullInputStream(), file -> output, file -> {
            });
      } finally {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
      }
      assertEquals("the missing force capability must be reported once at INFO", 1,
          records.stream().filter(record -> record.getLevel() == Level.INFO
              && record.getMessage().contains("has no force capability")).count());
      var unit = output.toByteArray();
      assertFalse(DiskStorage.hasBackupBarrier(
          java.util.Arrays.copyOfRange(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE,
              unit.length)));
      assertEquals("the full hash must cover every preceding byte",
          DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES,
              DiskStorage.XX_HASH_SEED),
          ByteBuffer.wrap(unit, unit.length - Long.BYTES, Long.BYTES).getLong());
      assertNotNull(DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
          new ByteArrayInputStream(unit), null).metadata());
    }
  }

  /** A custom stream can publish only flushed bytes and discard pending bytes on close. */
  @Test
  public void backupFlushesTheFinalTailBeforeClosingCustomOutput() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new FlushPublishedOutput();
      var name = storage.backup(() -> List.<String>of().iterator(),
          file -> InputStream.nullInputStream(), file -> output, file -> {
          });
      var published = output.published.toByteArray();
      assertTrue("close must have discarded no tail bytes", output.closed);
      assertNotNull("the published bytes must form a complete v5 unit",
          DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
              new ByteArrayInputStream(published), null).metadata());
      assertEquals(BackupUnitFiles.VERSION_5,
          DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
              new ByteArrayInputStream(published), null).metadata().backupFormatVersion());
    }
  }

  /** Real full and incremental ZIP writes leave a zero-filled gap before a sector-local v5 tail. */
  @Test
  public void realBackupTailsStayInsideOneSectorWithZeroPadding() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      for (var index = 0; index < 2; index++) {
        var name = index == 0 ? storage.fullBackup(backupPath) : storage.backup(backupPath);
        var unit = Files.readAllBytes(backupPath.resolve(name));
        var tailStart = unit.length - DiskStorage.IBU_V4_METADATA_SIZE;
        assertTrue("the real tail must fit inside one sector",
            tailStart % DiskStorage.IBU_SECTOR_SIZE + DiskStorage.IBU_V4_METADATA_SIZE
                <= DiskStorage.IBU_SECTOR_SIZE);
        var zipEnd = zipEndBeforeTail(unit, tailStart);
        assertTrue("the ZIP end marker must precede the tail", zipEnd <= tailStart);
        for (var byteIndex = zipEnd; byteIndex < tailStart; byteIndex++) {
          assertEquals("the gap after the ZIP must contain zero padding", 0, unit[byteIndex]);
        }
        assertNotNull(inspectUnit(name, storage.getUuid()).metadata());
        addOneRecord(youTrackDB);
      }
    }
  }

  /** The ZIP end record is the final 22 bytes of an archive without a comment. */
  private static int zipEndBeforeTail(byte[] unit, int tailStart) {
    for (var index = tailStart - 22; index >= 0; index--) {
      if ((unit[index] & 0xff) == 0x50 && (unit[index + 1] & 0xff) == 0x4b
          && (unit[index + 2] & 0xff) == 0x05 && (unit[index + 3] & 0xff) == 0x06
          && unit[index + 20] == 0 && unit[index + 21] == 0) {
        return index + 22;
      }
    }
    throw new AssertionError("no ZIP end record before the backup tail");
  }

  /** A force error fails backup before the one-request tail publication. */
  @Test
  public void failedForceWritesNoTail() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      output.failForce = true;
      assertThrows(RuntimeException.class, () -> storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> output, file -> {
          }));
      assertEquals(0, output.tailRequests);
      assertTrue(output.forceCalls > 0);
    }
  }

  /** A one-shot ZIP finalization error fails backup even if later writes and force work. */
  @Test
  public void failedZipFinalizationWritesNoTail() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      output.failCentralDirectory = true;
      assertThrows(RuntimeException.class, () -> storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> output, file -> {
          }));
      assertTrue("the central directory must hit the injected write error", output.failedWrite);
      assertEquals(0, output.tailRequests);
      // The one-shot error does not poison later writes or force calls.
      output.write(new byte[] {1, 2, 3});
      output.forceBackupData();
      assertEquals(1, output.forceCalls);
    }
  }

  /** Body and finalization write failures keep the body error and report the second error. */
  @Test
  public void failedBodyWriteKeepsFinalizationFailureSuppressed() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      output.failFirstWrites = 2;
      var failure = assertThrows(RuntimeException.class, () -> storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> output, file -> {
          }));
      var bodyFailure = failure.getCause();
      assertNotNull("the backup must retain the body IOException", bodyFailure);
      assertEquals("injected write 1", bodyFailure.getMessage());
      assertEquals("the later ZIP failure must be suppressed on the body failure", 1,
          bodyFailure.getSuppressed().length);
      assertEquals("injected write 2", bodyFailure.getSuppressed()[0].getMessage());
      assertEquals(0, output.tailRequests);
    }
  }

  /** A close error cannot mask a failed local durability force. */
  @Test
  public void localOutputCloseKeepsForceFailurePrimary() throws Exception {
    var path = backupPath.resolve("closed-channel");
    var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    channel.close();
    var type = Class.forName(DiskStorage.class.getName() + "$LocalBackupOutputStream");
    var constructor = type.getDeclaredConstructor(OutputStream.class, FileChannel.class,
        Path.class);
    constructor.setAccessible(true);
    OutputStream delegate = new OutputStream() {
      @Override
      public void write(int value) {
      }

      @Override
      public void close() throws IOException {
        throw new IOException("injected close error");
      }
    };
    var output = (OutputStream) constructor.newInstance(delegate, channel, backupPath);
    var forceFailure = assertThrows(IOException.class, output::close);
    assertEquals("the closed file channel must cause the force failure",
        java.nio.channels.ClosedChannelException.class, forceFailure.getClass());
    assertEquals(1, forceFailure.getSuppressed().length);
    assertEquals("injected close error", forceFailure.getSuppressed()[0].getMessage());
  }

  /** A forced output publishes a present barrier after a complete, flushed ZIP payload. */
  @Test
  public void forcedOutputPublishesOneTailRequest() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      var name = storage.backup(() -> List.<String>of().iterator(),
          file -> InputStream.nullInputStream(), file -> output, file -> {
          });
      assertEquals(1, output.forceCalls);
      assertEquals(1, output.tailRequests);
      var forceIndex = output.events.indexOf("force");
      assertTrue("the ZIP must finish before the force", output.events.contains("zipComplete"));
      assertTrue(output.events.indexOf("zipComplete") < forceIndex - 1);
      assertEquals("flush", output.events.get(forceIndex - 1));
      assertEquals("only the tail and its flush may follow the force",
          List.of("force", "tail", "flush"),
          output.events.subList(forceIndex, output.events.size()));
      assertEquals("nothing may be written after the tail", 0, output.writesAfterTail);
      var unit = output.bytes.toByteArray();
      assertTrue(DiskStorage.hasBackupBarrier(
          java.util.Arrays.copyOfRange(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE,
              unit.length)));
      assertNotNull(DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
          new ByteArrayInputStream(unit), null).metadata());
    }
  }

  /** Restore refuses a bad metadata checksum even when the full hash still agrees. */
  @Test
  public void restoreRejectsVersionFourMetadataChecksumFailure() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var name = storage.fullBackup(backupPath);
      rewriteVersionFiveAsVersionFour(name, storage.getUuid());
      var path = backupPath.resolve(name);
      var unit = Files.readAllBytes(path);
      ByteBuffer.wrap(unit, unit.length - DiskStorage.IBU_V4_METADATA_SIZE, Long.BYTES)
          .putLong(0);
      ByteBuffer.wrap(unit, unit.length - Long.BYTES, Long.BYTES).putLong(
          DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES,
              DiskStorage.XX_HASH_SEED));
      Files.write(path, unit);
      var before = unitContent();
      assertThrows(UnsupportedBackupException.class, () -> youTrackDB.internal.restore(
          "badMetadataChecksum", backupPath.toString(), null, null));
      assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));
      assertTrue("the damaged head must survive the refused extension", Files.exists(path));
      assertEquals("the metadata check alone must never cause removal", before, unitContent());
    }
  }

  /** Restore refuses a durable intermediate tail image with a zero full hash. */
  @Test
  public void restoreRejectsIntermediateTailImage() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var name = storage.fullBackup(backupPath);
      var path = backupPath.resolve(name);
      var unit = Files.readAllBytes(path);
      java.util.Arrays.fill(unit, unit.length - Long.BYTES, unit.length, (byte) 0);
      Files.write(path, unit);
      assertThrows(UnsupportedBackupException.class, () -> youTrackDB.internal.restore(
          "zeroFinalHash", backupPath.toString(), null, null));
    }
  }

  /** A v3 incomplete head stays removable after a v4 full backup. */
  @Test
  public void versionThreeIncompleteHeadIsRemoved() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      rewriteVersionFiveAsVersionFour(full, storage.getUuid());
      var incomplete = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_3,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var bytes = Files.readAllBytes(backupPath.resolve(incomplete));
      bytes[0] ^= 1;
      Files.write(backupPath.resolve(incomplete), bytes);
      assertNotNull(storage.backup(backupPath));
      assertFalse(Files.exists(backupPath.resolve(incomplete)));
    }
  }

  /** Version 1, version 2 and an unknown version refuse extension without any removal. */
  @Test
  public void unsupportedVersionsNeverRemoveExistingUnits() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      for (var version : new int[] {1, BackupUnitFiles.VERSION_2, 99}) {
        writeTrailingUnitOfFormat(storage.getUuid(), version,
            BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
            BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
        var before = unitContent();
        assertThrows(UnsupportedBackupException.class, () -> storage.backup(backupPath));
        assertEquals(before, unitContent());
      }
    }
  }

  /** A v4 chain with a forced head reads only 86 bytes per unit from the file itself. */
  @Test
  public void extensionReadsOnlyFileTailsForVersionFourChain() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      rewriteVersionFiveAsVersionFour(full, storage.getUuid());
      addOneRecord(youTrackDB);
      var first = storage.backup(backupPath);
      rewriteVersionFiveAsVersionFour(first, storage.getUuid());
      addOneRecord(youTrackDB);
      var second = storage.backup(backupPath);
      rewriteVersionFiveAsVersionFour(second, storage.getUuid());
      var before = unitContent();
      var reader = new CountingTailSupplier();
      var extension = storage.backup(this::unitIterator, reader, this::openOutput,
          name -> {
            throw new AssertionError("no file should be removed");
          });
      assertEquals(BackupUnitFiles.VERSION_5,
          inspectUnit(extension, storage.getUuid()).metadata().backupFormatVersion());
      var after = unitContent();
      after.remove(extension);
      assertEquals("extension must not rewrite any version 4 unit", before, after);
      assertEquals("all three units must be read through the positioned file channel",
          3 * DiskStorage.IBU_V4_METADATA_SIZE, reader.fileBytesRead);
      assertEquals("a tail-only unit must not open its full input stream", 0,
          reader.fullStreamsOpened);
    }
  }

  /** A mixed v3/v4 chain admits the older v3 unit from its tail below the v4 head. */
  @Test
  public void mixedChainReadsOnlyTailsBelowVersionFourHead() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteVersion4AsVersion3(backupPath.resolve(full));
      addOneRecord(youTrackDB);
      var head = storage.backup(backupPath);
      rewriteVersionFiveAsVersionFour(head, storage.getUuid());
      var reader = new CountingTailSupplier();
      storage.backup(this::unitIterator, reader, this::openOutput,
          name -> {
            throw new AssertionError("no file should be removed");
          });
      assertEquals(2 * DiskStorage.IBU_V4_METADATA_SIZE, reader.fileBytesRead);
      assertEquals("version 3 below the head must not open a full stream", 0,
          reader.fullStreamsOpened);
    }
  }

  /** A corrupt payload of a forced v4 head passes extension, but restore rejects that chain. */
  @Test
  public void forcedVersionFourHeadSkipsPayloadAndRestoreRejectsDamage() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var head = storage.fullBackup(backupPath);
      rewriteVersionFiveAsVersionFour(head, storage.getUuid());
      var path = backupPath.resolve(head);
      var damaged = Files.readAllBytes(path);
      damaged[0] ^= 1;
      Files.write(path, damaged);
      addOneRecord(youTrackDB);
      var extension = storage.backup(backupPath);
      assertTrue(Files.exists(path));
      assertTrue(Files.exists(backupPath.resolve(extension)));
      assertThrows(UnsupportedBackupException.class, () -> youTrackDB.internal.restore(
          "damagedHeadRestore", backupPath.toString(), null, null));
    }
  }

  /** A plain input supplier fully inspects a broken v4 head and signals one fallback. */
  @Test
  public void missingTailCapabilityChecksWholeHeadAndSignalsOnce() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var broken = writeTrailingUnit(storage.getUuid(), false, BackupUnitFiles.VERSION_4);
      var records = new CopyOnWriteArrayList<LogRecord>();
      withDiskStorageLogs(records, () -> storage.backup(this::unitIterator,
          this::openInput, this::openOutput, this::removeUnit));
      assertFalse(Files.exists(backupPath.resolve(broken)));
      assertEquals("one signal names the supplier, even when several units need inspection", 1,
          records.stream().filter(r -> r.getLevel() == Level.INFO
              && r.getMessage().contains("has no tail read capability")
              && r.getMessage().contains("IncrementalBackupExtensionTest")).count());
    }
  }

  /** Without tail reads, a damaged v3 head is removed after a full hash check and a signal. */
  @Test
  public void plainInputSupplierRemovesDamagedVersionThreeHeadAndSignals() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      var head = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_3,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var path = backupPath.resolve(head);
      var bytes = Files.readAllBytes(path);
      bytes[0] ^= 1;
      Files.write(path, bytes);
      var removed = new ArrayList<String>();
      var records = new CopyOnWriteArrayList<LogRecord>();

      withDiskStorageLogs(records, () -> storage.backup(this::unitIterator,
          this::openInput, this::openOutput, name -> {
            removed.add(name);
            removeUnit(name);
          }));

      assertEquals("the damaged v3 head must be the only removed unit", List.of(head), removed);
      assertTrue("the full backup must remain", Files.exists(backupPath.resolve(full)));
      assertTrue("the missing tail capability must be signaled at INFO",
          records.stream().anyMatch(r -> r.getLevel() == Level.INFO
              && r.getMessage().contains("has no tail read capability")
              && r.getMessage().contains("IncrementalBackupExtensionTest")));
    }
  }

  /** Without tail reads, a forced v4 head with damaged payload remains removable. */
  @Test
  public void plainInputSupplierFullyInspectsForcedVersionFourHead() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var head = storage.fullBackup(backupPath);
      rewriteVersionFiveAsVersionFour(head, storage.getUuid());
      var path = backupPath.resolve(head);
      var bytes = Files.readAllBytes(path);
      bytes[0] ^= 1;
      Files.write(path, bytes);
      var removed = new ArrayList<String>();
      var replacement = storage.backup(this::unitIterator, this::openInput,
          this::openOutput, name -> {
            removed.add(name);
            removeUnit(name);
          });
      // A replacement may reuse the name of a removed unit within the same clock second.
      assertEquals("only the failed full hash allows head removal", List.of(head), removed);
      assertTrue(Files.exists(backupPath.resolve(replacement)));
    }
  }

  /** Without tail reads, Case A residue blocks extension and leaves all files unchanged. */
  @Test
  public void plainInputSupplierRefusesMissingTailWithoutDeletion() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var name = BackupUnitFiles.unitFileName(storage.getUuid(), SOURCE, 1,
          BackupUnitFiles.FUTURE_DATE_STAMP);
      Files.write(backupPath.resolve(name), new byte[12]);
      var before = unitContent();
      assertThrows(UnsupportedBackupException.class, () -> storage.backup(this::unitIterator,
          this::openInput, this::openOutput, this::removeUnit));
      assertEquals(before, unitContent());
    }
  }

  /** An absent barrier makes a v4 head use its full hash and signals that fallback. */
  @Test
  public void absentBarrierChecksWholeVersionFourHeadAndSignals() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var broken = writeTrailingUnit(storage.getUuid(), false, BackupUnitFiles.VERSION_4);
      var reader = new CountingTailSupplier();
      var records = new CopyOnWriteArrayList<LogRecord>();
      withDiskStorageLogs(records, () -> storage.backup(this::unitIterator, reader,
          this::openOutput, this::removeUnit));
      assertFalse(Files.exists(backupPath.resolve(broken)));
      assertEquals(1, reader.fullStreamsOpened);
      assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.INFO
          && r.getMessage().contains(broken) && r.getMessage().contains("no force barrier")));
    }
  }

  /** A valid v4 head without a barrier gets a full inspection before admission. */
  @Test
  public void absentBarrierOnValidHeadStillRequiresFullInspection() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var head = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_4,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var reader = new CountingTailSupplier();
      storage.backup(this::unitIterator, reader, this::openOutput, this::removeUnit);
      assertTrue(Files.exists(backupPath.resolve(head)));
      assertEquals(1, reader.fullStreamsOpened);
    }
  }

  /** A failed metadata checksum below a valid head needs full inspection and blocks extension. */
  @Test
  public void badChecksumBelowHeadUsesFullInspectionAndDeletesNothing() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      addOneRecord(youTrackDB);
      storage.backup(backupPath);
      var path = backupPath.resolve(full);
      var bytes = Files.readAllBytes(path);
      bytes[bytes.length - DiskStorage.IBU_V4_METADATA_SIZE] ^= 1;
      ByteBuffer.wrap(bytes, bytes.length - Long.BYTES, Long.BYTES).putLong(
          DiskStorage.XX_HASH_64.hash(bytes, 0, bytes.length - Long.BYTES,
              DiskStorage.XX_HASH_SEED));
      Files.write(path, bytes);
      var before = unitContent();
      var reader = new CountingTailSupplier();
      assertThrows(UnsupportedBackupException.class, () -> storage.backup(this::unitIterator,
          reader, this::openOutput, this::removeUnit));
      assertEquals(before, unitContent());
      assertEquals("the bad older unit must receive a full hash check", 1,
          reader.fullStreamsOpened);
    }
  }

  /** A v3 head needs a full hash and an information-level explanation. */
  @Test
  public void versionThreeHeadChecksWholeUnitAndSignalsReason() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var head = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_3,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var reader = new CountingTailSupplier();
      var records = new CopyOnWriteArrayList<LogRecord>();
      withDiskStorageLogs(records, () -> storage.backup(this::unitIterator, reader,
          this::openOutput, name -> {
            throw new AssertionError("valid head must remain");
          }));
      assertEquals(1, reader.fullStreamsOpened);
      assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.INFO
          && r.getMessage().contains(head) && r.getMessage().contains("version 3")
          && r.getMessage().contains("no metadata checksum")));
    }
  }

  /** A failed metadata checksum requires full inspection before an incomplete unit can leave. */
  @Test
  public void failedMetadataChecksumSignalsAndNeedsFullInspectionForRemoval() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var broken = writeTrailingUnit(storage.getUuid(), false);
      var path = backupPath.resolve(broken);
      var bytes = Files.readAllBytes(path);
      bytes[bytes.length - DiskStorage.IBU_V4_METADATA_SIZE] ^= 1;
      Files.write(path, bytes);
      var reader = new CountingTailSupplier();
      var records = new CopyOnWriteArrayList<LogRecord>();
      withDiskStorageLogs(records, () -> storage.backup(this::unitIterator, reader,
          this::openOutput, this::removeUnit));
      assertFalse(Files.exists(path));
      assertEquals(1, reader.fullStreamsOpened);
      assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
          && r.getMessage().contains(broken) && r.getMessage().contains("Metadata checksum")));
    }
  }

  /** A short or zero tail cannot classify interrupted output, so no file is removed. */
  @Test
  public void missingAndZeroTailsRefuseExtensionWithoutDeletion() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      storage.fullBackup(backupPath);
      var name = BackupUnitFiles.unitFileName(storage.getUuid(), SOURCE, 1,
          BackupUnitFiles.FUTURE_DATE_STAMP);
      for (var count : new int[] {12, DiskStorage.IBU_V4_METADATA_SIZE}) {
        Files.write(backupPath.resolve(name), new byte[count]);
        var before = unitContent();
        var reader = new CountingTailSupplier();
        assertThrows(UnsupportedBackupException.class, () -> storage.backup(
            this::unitIterator, reader, file -> new ByteArrayOutputStream(),
            this::removeUnit));
        assertEquals(before, unitContent());
        assertEquals(1, reader.fullStreamsOpened);
      }
    }
  }

  /** A tail read error after finding removable output fails before removing any unit. */
  @Test
  public void tailReadErrorKeepsEveryUnitEvenAfterFindingIncompleteHead() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      var broken = writeTrailingUnitOfFormat(storage.getUuid(), BackupUnitFiles.VERSION_3,
          BackupUnitFiles.supportedFeatureFormat(), BackupUnitFiles.supportedLayoutVersion(),
          BackupUnitFiles.COMPLETED_CREATION_EVIDENCE);
      var path = backupPath.resolve(broken);
      var bytes = Files.readAllBytes(path);
      bytes[0] ^= 1;
      Files.write(path, bytes);
      var before = unitContent();
      var reader = new CountingTailSupplier();
      reader.failUnit = full;
      assertThrows(RuntimeException.class, () -> storage.backup(this::unitIterator,
          reader, name -> new ByteArrayOutputStream(), this::removeUnit));
      assertEquals(before, unitContent());
      assertEquals(1, reader.fullStreamsOpened);
    }
  }

  private java.util.Iterator<String> unitIterator() {
    try {
      return unitNames().iterator();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private InputStream openInput(String name) {
    try {
      return Files.newInputStream(backupPath.resolve(name));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private OutputStream openOutput(String name) {
    try {
      return Files.newOutputStream(backupPath.resolve(name), StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void removeUnit(String name) {
    try {
      Files.delete(backupPath.resolve(name));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Captures extension fallback signals without changing global logging after the test. */
  private void withDiskStorageLogs(List<LogRecord> records, ThrowingAction action)
      throws Exception {
    var logger = Logger.getLogger(DiskStorage.class.getName());
    var previousLevel = logger.getLevel();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      action.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {

    void run() throws Exception;
  }

  /** Reads tails through the production channel helper and counts bytes returned by the file. */
  private final class CountingTailSupplier
      implements Function<String, InputStream>, BackupTailReadCapability {
    private String failUnit;
    private long fileBytesRead;
    private int fullStreamsOpened;

    @Override
    public byte[] readBackupTail(String name, int count) throws IOException {
      if (name.equals(failUnit)) {
        throw new IOException("injected tail read error");
      }
      try (var channel = new BackupTailReadTest.CountingChannel(
          FileChannel.open(backupPath.resolve(name), StandardOpenOption.READ))) {
        var tail = DiskStorage.readLocalBackupTail(channel, count);
        fileBytesRead += channel.bytesRead;
        return tail;
      }
    }

    @Override
    public InputStream apply(String name) {
      fullStreamsOpened++;
      try {
        return Files.newInputStream(backupPath.resolve(name));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Records ZIP completion, flush, force, tail publication and any later writes. */
  private static final class RecordingOutput extends OutputStream implements BackupForceCapability {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final List<String> events = new ArrayList<>();
    private boolean failForce;
    private boolean failCentralDirectory;
    private boolean failedWrite;
    private int failFirstWrites;
    private int injectedWriteCount;
    private int lastFour;
    private int eocdBytesRemaining = -1;
    private int tailRequests;
    private int forceCalls;
    private int writesAfterTail;
    private boolean tailPublished;

    @Override
    public void write(int value) throws IOException {
      if (failFirstWrites > 0) {
        failFirstWrites--;
        throw new IOException("injected write " + ++injectedWriteCount);
      }
      if (tailPublished) {
        writesAfterTail++;
      }
      lastFour = (lastFour << 8) | (value & 0xff);
      if (failCentralDirectory && !failedWrite && lastFour == 0x504b0102) {
        failedWrite = true;
        throw new IOException("injected central directory write error");
      }
      bytes.write(value);
      if (eocdBytesRemaining > 0 && --eocdBytesRemaining == 0) {
        events.add("zipComplete");
      }
      if (lastFour == 0x504b0506 && eocdBytesRemaining < 0) {
        eocdBytesRemaining = 18;
      }
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      var isTail = length == DiskStorage.IBU_V4_METADATA_SIZE
          && data[offset + Long.BYTES + Integer.BYTES] == 0
          && data[offset + Long.BYTES + Integer.BYTES + 1] == BackupUnitFiles.VERSION_5;
      if (isTail) {
        tailRequests++;
      }
      for (var i = offset; i < offset + length; i++) {
        write(data[i]);
      }
      if (isTail) {
        tailPublished = true;
        events.add("tail");
      }
    }

    @Override
    public void flush() {
      events.add("flush");
    }

    @Override
    public void forceBackupData() throws IOException {
      events.add("force");
      forceCalls++;
      if (failForce) {
        throw new IOException("injected force error");
      }
    }
  }

  /** Publishes pending bytes on flush and discards any remaining bytes on close. */
  private static final class FlushPublishedOutput extends OutputStream {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();
    private boolean closed;

    @Override
    public void write(int value) {
      pending.write(value);
    }

    @Override
    public void write(byte[] value, int offset, int length) {
      pending.write(value, offset, length);
    }

    @Override
    public void flush() throws IOException {
      pending.writeTo(published);
      pending.reset();
    }

    @Override
    public void close() {
      pending.reset();
      closed = true;
    }
  }

  /** Converts a real version 5 unit to version 4 and verifies both checksums before use. */
  private void rewriteVersionFiveAsVersionFour(String unitName, UUID databaseId)
      throws IOException {
    assertEquals(BackupUnitFiles.VERSION_5,
        inspectUnit(unitName, databaseId).metadata().backupFormatVersion());
    var path = backupPath.resolve(unitName);
    var bytes = Files.readAllBytes(path);
    var tailStart = bytes.length - DiskStorage.IBU_V4_METADATA_SIZE;
    // Versions 4 and 5 share the layout. Keep the payload, padding and barrier flag unchanged.
    ByteBuffer.wrap(bytes, tailStart + Long.BYTES + Integer.BYTES, Short.BYTES)
        .putShort((short) BackupUnitFiles.VERSION_4);
    ByteBuffer.wrap(bytes, tailStart, Long.BYTES).putLong(
        DiskStorage.XX_HASH_64.hash(bytes, tailStart + Long.BYTES,
            DiskStorage.IBU_V4_METADATA_SIZE - 2 * Long.BYTES, DiskStorage.METADATA_HASH_SEED));
    ByteBuffer.wrap(bytes, bytes.length - Long.BYTES, Long.BYTES).putLong(
        DiskStorage.XX_HASH_64.hash(bytes, 0, bytes.length - Long.BYTES,
            DiskStorage.XX_HASH_SEED));
    Files.write(path, bytes);
    assertEquals(BackupUnitFiles.VERSION_4,
        inspectUnit(unitName, databaseId).metadata().backupFormatVersion());
  }

  /** Writes one trailing unit of this build with a chosen validity of its hash code. */
  private String writeTrailingUnit(UUID databaseId, boolean validHash) throws IOException {
    return writeTrailingUnit(databaseId, validHash, BackupUnitFiles.CURRENT_BACKUP_FORMAT_VERSION);
  }

  /** Writes one trailing unit of the chosen version with a chosen validity of its hash code. */
  private String writeTrailingUnit(UUID databaseId, boolean validHash, int version)
      throws IOException {
    return BackupUnitFiles.writeUnit(backupPath, databaseId, SOURCE, 1, false,
        version, BackupUnitFiles.supportedFeatureFormat(),
        BackupUnitFiles.supportedLayoutVersion(), BackupUnitFiles.COMPLETED_CREATION_EVIDENCE,
        validHash,
        BackupUnitFiles.unitFileName(databaseId, SOURCE, 1, BackupUnitFiles.FUTURE_DATE_STAMP));
  }

  /** Writes one trailing unit with a chosen header and a valid hash code. */
  private String writeTrailingUnitOfFormat(UUID databaseId, int backupFormatVersion,
      int featureFormat, int layoutVersion, int creationEvidence) throws IOException {
    return BackupUnitFiles.writeUnit(backupPath, databaseId, SOURCE, 1, false,
        backupFormatVersion, featureFormat, layoutVersion, creationEvidence, true,
        BackupUnitFiles.unitFileName(databaseId, SOURCE, 1, BackupUnitFiles.FUTURE_DATE_STAMP));
  }

  /** Inspects one unit of the backup directory. */
  private DiskStorage.BackupUnitInspection inspectUnit(String unitName, UUID databaseId)
      throws IOException {
    try (var stream = Files.newInputStream(backupPath.resolve(unitName))) {
      return DiskStorage.inspectBackupUnit(unitName, SOURCE, databaseId, stream, null);
    }
  }

  /** Returns the sorted names of every backup unit of the backup directory. */
  private List<String> unitNames() throws IOException {
    try (var paths = Files.list(backupPath)) {
      return paths
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".ibu"))
          .sorted()
          .toList();
    }
  }

  /**
   * Returns one digest of every backup unit of the backup directory, keyed by file name.
   *
   * <p>The digest keeps one failure message short. The backup lock file of the directory stays out
   * of the result, because every backup of one directory opens that file.
   */
  private Map<String, String> unitContent() throws Exception {
    var content = new HashMap<String, String>();
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var paths = Files.list(backupPath)) {
      for (var path : paths.toList()) {
        var name = path.getFileName().toString();
        if (!name.endsWith(".ibu")) {
          continue;
        }
        content.put(name,
            java.util.Base64.getEncoder()
                .encodeToString(digest.digest(Files.readAllBytes(path))));
      }
    }
    return content;
  }

  private YouTrackDBImpl openManager() {
    return (YouTrackDBImpl) YourTracks.instance(databasesPath.toString());
  }

  /** Creates the source database with one class and one record, and returns its storage. */
  private AbstractStorage createSourceDatabase(YouTrackDBImpl youTrackDB) {
    youTrackDB.create(SOURCE, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
    try (var session = youTrackDB.open(SOURCE, ADMIN, PASSWORD)) {
      session.getMetadata().getSchema().createClass(RECORD_CLASS);
    }
    addOneRecord(youTrackDB);
    var storage =
        ((YouTrackDBInternalEmbedded) youTrackDB.internal).getStorage(SOURCE);
    assertNotNull("the database must hold one registered storage", storage);
    return storage;
  }

  /** Adds one record to the source database, so the next increment carries changes. */
  private void addOneRecord(YouTrackDBImpl youTrackDB) {
    try (var session = youTrackDB.open(SOURCE, ADMIN, PASSWORD)) {
      session.begin();
      var entity = session.newEntity(RECORD_CLASS);
      entity.setProperty("value", "backed up");
      session.commit();
    }
  }
}
