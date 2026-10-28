package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
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
import java.util.concurrent.atomic.AtomicInteger;
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
   * A valid unit from another database stays unclassifiable and reports its stored UUID.
   * The independent UUID literal has distinct halves so a swapped diagnostic cannot pass.
   */
  @Test
  public void otherDatabaseWarningPrintsTheStoredUuidInItsOriginalOrder() throws Exception {
    var storedId = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
    var expectedId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    var unit = BackupUnitFiles.writeSupportedUnit(backupPath, storedId, SOURCE, 0, true);
    var records = new CopyOnWriteArrayList<LogRecord>();

    withDiskStorageLogs(records, () -> {
      var inspection = inspectUnit(unit, expectedId);
      assertEquals(DiskStorage.BackupUnitClassification.UNCLASSIFIABLE,
          inspection.classification());
      org.junit.Assert.assertNull(inspection.metadata());
    });

    var warnings = records.stream()
        .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
        .filter(record -> record.getMessage().contains("stored in metadata"))
        .toList();
    assertEquals("the identity mismatch must produce one warning", 1, warnings.size());
    assertTrue(warnings.getFirst().getMessage(),
        warnings.getFirst().getMessage().contains("stored in metadata " + storedId));
    assertTrue(warnings.getFirst().getMessage(),
        warnings.getFirst().getMessage().contains("the database identifier " + expectedId));
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
   * Extending a version 3 chain writes version 6 without rewriting any older unit.
   *
   * <p>The full backup has real content, but its header identifies the previous release. The
   * extension must admit it and write a current-version increment while keeping its bytes.
   */
  @Test
  public void incrementalBackupExtendsPreviousVersionChainWithCurrentVersion() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var fullUnit = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteAsVersion(backupPath.resolve(fullUnit), 3);
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

      assertTrue(refusal.getMessage(), refusal.getMessage().contains("version 7"));
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, 5, and 6"));
      assertEquals("no existing file may change", contentBeforeBackup, unitContent());
    }
  }

  /** A broken version 4 trailing unit remains removable below a version 6 replacement. */
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
   * <p>The expected outcome has two parts. The refusal names version 2 and all four accepted
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
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, 5, and 6"));
      assertEquals(
          "the refused backup must keep every existing unit unchanged",
          contentBeforeBackup,
          unitContent());
    }
  }

  /**
   * A real version 1 unit below a supported head refuses extension without changing files.
   * The refusal names version 1 and all four accepted versions.
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
      assertTrue(refusal.getMessage(), refusal.getMessage().contains("versions 3, 4, 5, and 6"));
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

  /** A v5 full unit followed by real v6 increments preserves old bytes and replays all records. */
  @Test
  public void mixedVersionChainExtendsAndRestoresPastPaddingAndTail() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteAsVersion(backupPath.resolve(full), 5);
      var oldBytes = Files.readAllBytes(backupPath.resolve(full));
      addOneRecord(youTrackDB);
      var first = storage.backup(backupPath);
      org.junit.Assert.assertArrayEquals(oldBytes, Files.readAllBytes(backupPath.resolve(full)));
      assertEquals(BackupUnitFiles.VERSION_5,
          inspectUnit(full, storage.getUuid()).metadata().backupFormatVersion());
      assertEquals(BackupUnitFiles.VERSION_6,
          inspectUnit(first, storage.getUuid()).metadata().backupFormatVersion());
      addOneRecord(youTrackDB);
      storage.backup(backupPath);
      youTrackDB.internal.restore("mixedVersionRestore", backupPath.toString(), null, null);
      try (var restored = youTrackDB.open("mixedVersionRestore", ADMIN, PASSWORD)) {
        assertEquals(3, restored.query("select from " + RECORD_CLASS).stream().count());
      }
    }
  }

  /** Mixed real ZIP units keep their bytes, admit different pairs and replay after v6 extension. */
  @Test
  public void mixedVersionThreeFourFiveSixChainUsesBoundedTailsAndRestores() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      BackupUnitFiles.rewriteAsVersion(backupPath.resolve(full), 3);
      addOneRecord(youTrackDB);
      var v4 = storage.backup(backupPath);
      rewriteCurrentAsVersionFour(v4, storage.getUuid());
      addOneRecord(youTrackDB);
      var v5 = storage.backup(backupPath);
      BackupUnitFiles.rewriteAsVersion(backupPath.resolve(v5), 5);
      addOneRecord(youTrackDB);
      var v6 = storage.backup(backupPath);
      var livePair = literalContinuity(Files.readAllBytes(backupPath.resolve(v6)));
      assertNotNull("real capable output carries a newly consumed pair", livePair);
      assertEquals(livePair.generationId(), trackerField(tracker(storage), "lastCompleted"));
      BackupUnitFiles.rewriteAsVersion6(backupPath.resolve(v6),
          DiskStorage.BARRIER_PRESENT,
          new DiskStorage.BackupContinuity(UUID.randomUUID(), UUID.randomUUID()));
      var before = unitContent();
      var reader = new CountingTailSupplier();
      var appended = storage.backup(this::unitIterator, reader, this::openOutput,
          name -> {
            throw new AssertionError("no removal");
          });
      assertEquals(4 * 122, reader.fileBytesRead);
      assertEquals(0, reader.fullStreamsOpened);
      assertEquals(6, inspectUnit(appended, storage.getUuid()).metadata().backupFormatVersion());
      assertEquals(
          "plain extension retains the actual previous completion despite a mismatched head",
          livePair, literalContinuity(Files.readAllBytes(backupPath.resolve(appended))));
      var after = unitContent();
      after.remove(appended);
      assertEquals(before, after);
      youTrackDB.internal.restore("mixedSixRestore", backupPath.toString(), null, null);
      try (var restored = youTrackDB.open("mixedSixRestore", ADMIN, PASSWORD)) {
        assertEquals(4, restored.query("select from " + RECORD_CLASS).stream().count());
      }
    }
  }

  /** V6 barrier and checksum fallbacks preserve files and open exactly the required streams. */
  @Test
  public void versionSixFallbacksAndMalformedMarkersNeverRemoveAmbiguousUnits() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var head = storage.fullBackup(backupPath);
      var path = backupPath.resolve(head);
      var sound = Files.readAllBytes(path);
      for (int scenario = 0; scenario < 6; scenario++) {
        var bytes = sound.clone();
        var tail = bytes.length - 122;
        if (scenario < 2) {
          ByteBuffer.wrap(bytes).putInt(tail + 8, scenario == 0 ? 0 : 123);
          BackupUnitFiles.repairVersion6Hashes(bytes, true, true);
        } else if (scenario == 2) {
          bytes[tail] ^= 1;
          BackupUnitFiles.repairVersion6Hashes(bytes, false, true);
        } else if (scenario >= 4) {
          ByteBuffer.wrap(bytes).putInt(tail + 12, 1);
          BackupUnitFiles.repairVersion6Hashes(bytes, true, true);
          if (scenario == 5) {
            bytes[0] ^= 1;
          }
        }
        Files.write(path, bytes);
        var reader = new CountingTailSupplier();
        var fullStreamsOpened = new AtomicInteger();
        // This lambda has no tail capability, so the single head needs one full inspection.
        Function<String, InputStream> fullStreamSupplier = name -> {
          fullStreamsOpened.incrementAndGet();
          return openInput(name);
        };
        if (scenario == 2 || scenario >= 4) {
          var before = unitContent();
          assertThrows(UnsupportedBackupException.class, () -> storage.backup(this::unitIterator,
              reader, this::openOutput, name -> {
                throw new AssertionError("no removal");
              }));
          assertEquals(before, unitContent());
        } else {
          var appended = scenario == 3
              ? storage.backup(this::unitIterator, fullStreamSupplier, this::openOutput,
                  name -> {
                    throw new AssertionError("no removal");
                  })
              : storage.backup(this::unitIterator, reader, this::openOutput,
                  name -> {
                    throw new AssertionError("no removal");
                  });
          Files.delete(backupPath.resolve(appended));
        }
        if (scenario == 3) {
          assertEquals("the no-tail supplier must open the single v6 head exactly once", 1,
              fullStreamsOpened.get());
        } else {
          assertEquals(scenario <= 2 ? 1 : 0, reader.fullStreamsOpened);
        }
      }
      Files.write(path, sound);
    }
  }

  /** A plain output stream records no barrier and emits one information-level signal. */
  @Test
  public void plainOutputStreamWritesAnAbsentBarrier() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new ByteArrayOutputStream();
      var records = new CopyOnWriteArrayList<LogRecord>();
      var names = new ArrayList<String>();
      withDiskStorageLogs(records, () -> names.add(storage.backup(
          () -> List.<String>of().iterator(),
          file -> InputStream.nullInputStream(), file -> output, file -> {
          })));
      var name = names.getFirst();
      assertEquals("the missing force capability must be reported once at INFO", 1,
          records.stream().filter(record -> record.getLevel() == Level.INFO
              && record.getMessage().contains("has no force capability")).count());
      var unit = output.toByteArray();
      assertFalse(DiskStorage.hasBackupBarrier(
          java.util.Arrays.copyOfRange(unit, unit.length - 122,
              unit.length)));
      assertEquals("the full hash must cover every preceding byte",
          DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - Long.BYTES,
              DiskStorage.XX_HASH_SEED),
          ByteBuffer.wrap(unit, unit.length - Long.BYTES, Long.BYTES).getLong());
      assertCanonicalVersionSix(unit, name, storage.getUuid(), 0, false);
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
      assertNotNull("the published bytes must form a complete v6 unit",
          DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
              new ByteArrayInputStream(published), null).metadata());
      assertEquals(BackupUnitFiles.VERSION_6,
          DiskStorage.inspectBackupUnit(name, SOURCE, storage.getUuid(),
              new ByteArrayInputStream(published), null).metadata().backupFormatVersion());
    }
  }

  /** Real full and incremental ZIP writes leave a zero-filled gap before a sector-local v6 tail. */
  @Test
  public void realBackupTailsStayInsideOneSectorWithZeroPadding() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      for (var index = 0; index < 2; index++) {
        var name = index == 0 ? storage.fullBackup(backupPath) : storage.backup(backupPath);
        var unit = Files.readAllBytes(backupPath.resolve(name));
        var tailStart = unit.length - 122;
        assertTrue("the real tail must fit inside one sector",
            tailStart % DiskStorage.IBU_SECTOR_SIZE + 122
                <= DiskStorage.IBU_SECTOR_SIZE);
        var zipEnd = zipEndBeforeTail(unit, tailStart);
        assertTrue("the ZIP end marker must precede the tail", zipEnd <= tailStart);
        for (var byteIndex = zipEnd; byteIndex < tailStart; byteIndex++) {
          assertEquals("the gap after the ZIP must contain zero padding", 0, unit[byteIndex]);
        }
        assertCanonicalVersionSix(unit, name, storage.getUuid(), index, true);
        assertEquals("UNCLASSIFIABLE", BackupFormatVersion4Test.frozenHeadClassification(
            unit, name, storage.getUuid(), true));
        assertEquals("UNCLASSIFIABLE", BackupFormatVersion4Test.frozenHeadClassification(
            unit, name, storage.getUuid(), false));
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

  /** Each publication failure reaches the caller, attempts close and keeps its output unit. */
  @Test
  public void publicationPhaseFailuresReachCallerWithoutSuccessLog() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      for (var phase : List.of("payloadFlush", "force", "tail", "tailFlush", "secondForce",
          "close")) {
        var output = new RecordingOutput();
        output.failPhase = phase;
        var records = new CopyOnWriteArrayList<LogRecord>();
        var created = new ArrayList<String>();
        var remaining = new ArrayList<String>();
        var removed = new ArrayList<String>();
        withDiskStorageLogs(records, () -> {
          var failure = assertThrows(DatabaseException.class, () -> storage.backup(
              () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> {
                created.add(file);
                remaining.add(file);
                return output;
              }, file -> {
                removed.add(file);
                remaining.remove(file);
              }));
          assertEquals("injected " + phase, failure.getCause().getMessage());
        });
        assertTrue("a publication failure must not invoke the remover", removed.isEmpty());
        assertEquals("one output unit must be created", 1, created.size());
        assertEquals("the failed output unit must remain", created, remaining);
        assertTrue("the injected phase must be reached", output.faultReached);
        assertTrue("close must be attempted", output.closed);
        assertEquals(phase.equals("payloadFlush") || phase.equals("force") ? 0 : 1,
            output.tailRequests);
        assertEquals(phase.equals("payloadFlush") ? 0
            : phase.equals("secondForce") || phase.equals("close") ? 2 : 1, output.forceCalls);
        assertEquals("close must be attempted exactly once", 1, output.closeCalls);
        assertFalse("a failed backup must not log success", records.stream().anyMatch(
            record -> record.getMessage().contains("is completed")));
        if (phase.equals("tailFlush") || phase.equals("secondForce") || phase.equals("close")) {
          assertCanonicalVersionSix(output.bytes.toByteArray(), created.getFirst(),
              storage.getUuid(), 0, true);
          assertEquals("the retained tail must never be rewritten", 0, output.writesAfterTail);
        }
        if (phase.equals("secondForce") || phase.equals("close")) {
          assertEquals(List.of("force", "tail", "flush", "force", "close"),
              output.events.subList(output.events.indexOf("force"), output.events.size()));
        }
      }
    }
  }

  /** The third local force fails during close after both explicit forces and retains the unit. */
  @Test
  public void localFinalForceFailureFailsBackupAfterTailPublication() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var channel = org.mockito.Mockito.mock(FileChannel.class);
      var forces = new AtomicInteger();
      org.mockito.Mockito.doAnswer(invocation -> {
        if (forces.incrementAndGet() == 3) {
          throw new IOException("injected final force");
        }
        return null;
      }).when(channel).force(true);
      var delegate = new RecordingOutput();
      var type = Class.forName(DiskStorage.class.getName() + "$LocalBackupOutputStream");
      var constructor = type.getDeclaredConstructor(OutputStream.class, FileChannel.class,
          Path.class);
      constructor.setAccessible(true);
      var output = (OutputStream) constructor.newInstance(delegate, channel, backupPath);
      var created = new ArrayList<String>();
      var remaining = new ArrayList<String>();
      var removed = new ArrayList<String>();
      var failure = assertThrows(RuntimeException.class, () -> storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> {
            created.add(file);
            remaining.add(file);
            return output;
          }, file -> {
            removed.add(file);
            remaining.remove(file);
          }));
      assertEquals("injected final force", failure.getCause().getMessage());
      assertTrue("a final force failure must not invoke the remover", removed.isEmpty());
      assertEquals("one output unit must be created", 1, created.size());
      assertEquals("the complete output unit must remain", created, remaining);
      assertEquals("two explicit forces plus the close-time force", 3, forces.get());
      assertEquals("the local wrapper does not delegate capability calls", 0, delegate.forceCalls);
      assertTrue(delegate.closed);
      assertEquals(1, delegate.tailRequests);
      var unit = delegate.bytes.toByteArray();
      assertEquals(6, ByteBuffer.wrap(unit).getShort(unit.length - 74));
      assertEquals(DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - 8,
          DiskStorage.XX_HASH_SEED), ByteBuffer.wrap(unit).getLong(unit.length - 8));
    }
  }

  /** A second-force error stays primary when close also fails, with complete bytes retained. */
  @Test
  public void secondForceFailureKeepsCloseFailureSuppressed() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      output.failPhase = "secondForce";
      output.failClose = true;
      var records = new CopyOnWriteArrayList<LogRecord>();
      var names = new ArrayList<String>();
      var removals = new AtomicInteger();
      withDiskStorageLogs(records, () -> {
        var failure = assertThrows(DatabaseException.class, () -> storage.backup(
            () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
            file -> {
              names.add(file);
              return output;
            }, file -> removals.incrementAndGet()));
        var primary = failure.getCause();
        assertEquals("injected secondForce", primary.getMessage());
        assertEquals(1, primary.getSuppressed().length);
        assertEquals("injected close", primary.getSuppressed()[0].getMessage());
      });
      assertTrue(output.faultReached);
      assertEquals(2, output.forceCalls);
      assertEquals(1, output.closeCalls);
      assertEquals(1, output.tailRequests);
      assertEquals(0, output.writesAfterTail);
      assertEquals(0, removals.get());
      assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
      assertCanonicalVersionSix(output.bytes.toByteArray(), names.getFirst(),
          storage.getUuid(), 0, true);
    }
  }

  /** A cached payload-copy error survives close unchanged and the failed unit stays retained. */
  @Test
  public void cachedPayloadFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("payload");
  }

  /** A cached padding error survives close unchanged without a success log or removal. */
  @Test
  public void cachedPaddingFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("padding");
  }

  /** A cached pre-tail flush error survives close unchanged and closes exactly once. */
  @Test
  public void cachedPayloadFlushFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("payloadFlush");
  }

  /** A cached first-force error survives close unchanged without publishing a tail. */
  @Test
  public void cachedFirstForceFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("force");
  }

  /** A cached tail-write error survives close unchanged without retrying the tail. */
  @Test
  public void cachedTailFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("tail");
  }

  /** A cached final-flush error survives close unchanged with the single tail retained. */
  @Test
  public void cachedTailFlushFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("tailFlush");
  }

  /** A cached second-force error maps to DatabaseException with no self-suppressed failure. */
  @Test
  public void cachedSecondForceFailureSurvivesClose() throws Exception {
    assertCachedFailureSurvivesClose("secondForce");
  }

  /** A null supplier output preserves its null-pointer failure without attempting cleanup. */
  @Test
  public void nullOutputPreservesFailure() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      assertThrows(NullPointerException.class, () -> storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> null, file -> {
            throw new AssertionError("no removal");
          }));
    }
  }

  /** A cached runtime failure stays primary when close rethrows that same object. */
  @Test
  public void cachedRuntimeFailureSurvivesClose() throws Exception {
    assertUncheckedFailureSurvivesClose(new IllegalStateException("cached runtime failure"));
  }

  /** A cached error stays primary when close rethrows that same object. */
  @Test
  public void cachedErrorSurvivesClose() throws Exception {
    assertUncheckedFailureSurvivesClose(new AssertionError("cached error"));
  }

  private void assertUncheckedFailureSurvivesClose(Throwable primary) throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = org.mockito.Mockito.mock(OutputStream.class);
      org.mockito.Mockito.doThrow(primary).when(output).write(
          org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyInt(),
          org.mockito.ArgumentMatchers.anyInt());
      org.mockito.Mockito.doThrow(primary).when(output).close();
      var records = new CopyOnWriteArrayList<LogRecord>();
      withDiskStorageLogs(records, () -> assertSame(primary,
          assertThrows(primary.getClass(), () -> storage.backup(
              () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> output, file -> {
                throw new AssertionError("no removal");
              }))));
      org.mockito.Mockito.verify(output, org.mockito.Mockito.times(1)).close();
      assertEquals(0, primary.getSuppressed().length);
      assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
    }
  }

  private void assertCachedFailureSurvivesClose(String phase) throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var output = new RecordingOutput();
      output.failPhase = phase;
      output.cachedFailure = new IOException("cached " + phase);
      var tracker = tracker(storage);
      markTracker(tracker, 123456, 111L);
      var records = new CopyOnWriteArrayList<LogRecord>();
      var retained = new HashMap<String, OutputStream>();
      var removals = new AtomicInteger();
      withDiskStorageLogs(records, () -> {
        var failure = assertThrows(DatabaseException.class, () -> storage.backup(
            () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
            file -> {
              retained.put(file, output);
              return output;
            }, file -> {
              removals.incrementAndGet();
              retained.remove(file);
            }));
        assertSame(output.cachedFailure, failure.getCause());
        assertEquals(0, output.cachedFailure.getSuppressed().length);
      });
      assertTrue("the selected phase must fail", output.faultReached);
      assertEquals(1, output.closeCalls);
      assertEquals(0, removals.get());
      assertEquals(1, retained.size());
      assertSame(output, retained.values().iterator().next());
      assertEquals(List.of(111L), trackerCandidates(tracker, "active", 123456));
      org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
      assertBackupUnlocked(storage);
      assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
      assertEquals(List.of("tail", "tailFlush", "secondForce").contains(phase) ? 1 : 0,
          output.tailRequests);
      assertEquals(0, output.writesAfterTail);
    }
  }

  /** An unavailable directory still allows local close after its file force succeeds. */
  @Test
  public void localDirectoryForceIOExceptionDoesNotFailBackup() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var channel = org.mockito.Mockito.mock(FileChannel.class);
      var delegate = new RecordingOutput();
      var type = Class.forName(DiskStorage.class.getName() + "$LocalBackupOutputStream");
      var constructor = type.getDeclaredConstructor(OutputStream.class, FileChannel.class,
          Path.class);
      constructor.setAccessible(true);
      // Opening this nonexistent directory deterministically throws IOException on every platform.
      var missingDirectory = backupPath.resolve("missing-directory");
      assertFalse(Files.exists(missingDirectory));
      var output = (OutputStream) constructor.newInstance(delegate, channel, missingDirectory);
      var name = storage.backup(() -> List.<String>of().iterator(),
          file -> InputStream.nullInputStream(), file -> output, file -> {
            throw new AssertionError("no removal");
          });
      org.mockito.Mockito.verify(channel, org.mockito.Mockito.times(3)).force(true);
      assertEquals(0, delegate.forceCalls);
      assertEquals(1, delegate.closeCalls);
      assertCanonicalVersionSix(delegate.bytes.toByteArray(), name, storage.getUuid(), 0, true);
    }
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

  /**
   * Real supplier completion uses current WAL preflight and preserves every fallback warning.
   * Filesystem obstructions distinguish save failure from failed invalidation. Invalidating at
   * close distinguishes saved-untrusted history from a trusted full-read bootstrap.
   */
  @Test
  public void supplierCompletionUsesBridgeAndReportsExactFallbackStatus() throws Exception {
    String[][] cases = {
        {"SAVED_CONTINUITY", ""},
        {"SAVED_UNTRUSTED", "Backup completion saved untrusted history."},
        {"SAVE_FAILED_INVALIDATED", "Backup completion save failed. History invalidated."},
        {"SAVE_FAILED_INVALIDATION_FAILED", "Backup completion save and invalidation failed."},
        {"COVERAGE_UNAVAILABLE", "Backup completion coverage unavailable. History invalidated."},
        {"COVERAGE_UNAVAILABLE_INVALIDATION_FAILED",
            "Backup completion coverage and invalidation unavailable."}
    };
    for (var row : cases) {
      var casePath = databasesPath.resolve(row[0]);
      try (var manager = (YouTrackDBImpl) YourTracks.instance(casePath.toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        var orderMethod = tracker.getClass().getDeclaredMethod("saveOrderLock");
        orderMethod.setAccessible(true);
        var order = (java.util.concurrent.locks.ReentrantLock) orderMethod.invoke(tracker);
        var realWal = storage.getWALInstance();
        var wal = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
            org.mockito.AdditionalAnswers.delegatesTo(realWal));
        var coverage = new java.util.concurrent.atomic.AtomicReference<
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight>();
        var preflights = new AtomicInteger();
        var callerThread = Thread.currentThread();
        var outputClosed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
          // Ordinary WAL maintenance can also preflight. Fault and count only this request's
          // post-close completion on the backup thread, not concurrent checkpoint work.
          if (Thread.currentThread() != callerThread || !outputClosed.get()) {
            return realWal.preflightCut(call.getArgument(0));
          }
          preflights.incrementAndGet();
          if (row[0].startsWith("COVERAGE_UNAVAILABLE")) {
            return null;
          }
          var result = realWal.preflightCut(call.getArgument(0));
          coverage.set(result);
          return result;
        }).when(wal).preflightCut(org.mockito.ArgumentMatchers.anyLong());
        var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
        walField.setAccessible(true);
        var sideFile = casePath.resolve(SOURCE).resolve("changed-pages.cpt");
        var temporary = sideFile.resolveSibling("changed-pages.cpt.tmp");
        var output = new RecordingOutput();
        output.onClose = () -> {
          // Retain save order from close through the byte oracle. The production completion
          // reenters it on this thread. A later checkpoint cannot replace the observed header.
          order.lock();
          try {
            if (row[0].equals("SAVED_UNTRUSTED")) {
              var invalidate = tracker.getClass().getDeclaredMethod("invalidate");
              invalidate.setAccessible(true);
              invalidate.invoke(tracker);
            }
            if (row[0].startsWith("SAVE_FAILED")) {
              Files.createDirectories(temporary);
              Files.writeString(temporary.resolve("obstruction"), "owned test obstruction");
            }
            if (row[0].endsWith("INVALIDATION_FAILED")) {
              Files.deleteIfExists(sideFile);
              Files.createDirectories(sideFile);
              Files.writeString(sideFile.resolve("obstruction"), "owned test obstruction");
            }
            outputClosed.set(true);
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
        var records = new CopyOnWriteArrayList<LogRecord>();
        walField.set(storage, wal);
        try {
          withDiskStorageLogs(records, () -> storage.backup(
              () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> output, file -> {
                throw new AssertionError("no removal");
              }));
          assertEquals(row[0], 1, preflights.get());
          assertEquals(2, output.forceCalls);
          assertEquals(1, output.closeCalls);
          assertNotNull(literalContinuity(output.bytes.toByteArray()));
          org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
          assertEquals(row[0], row[0].equals("SAVED_CONTINUITY"), tracker.isTrusted());
          var warnings = records.stream().filter(record -> record.getLevel() == Level.WARNING)
              .map(LogRecord::getMessage).toList();
          assertEquals(row[0], row[1].isEmpty() ? List.of()
              : List.of("youtrackdb:" + SOURCE + " [" + SOURCE + "] " + row[1]), warnings);
          assertTrue(records.stream()
              .anyMatch(record -> record.getMessage().contains("is completed. Backup unit file")));
          if (row[0].equals("SAVED_CONTINUITY")) {
            var pair = literalContinuity(output.bytes.toByteArray());
            assertSavedPair(sideFile, pair);
            assertNotNull(coverage.get());
            var header = ByteBuffer.wrap(Files.readAllBytes(sideFile));
            // The exact live preflight LSN must reach the persisted header, not a guessed anchor.
            assertEquals(coverage.get().coverageLsn().getSegment(), header.getLong(9));
            assertEquals(coverage.get().coverageLsn().getPosition(), header.getInt(17));
          }
        } finally {
          walField.set(storage, realWal);
          try {
            // Remove only the obstructions this case created, before ordinary manager shutdown.
            for (var path : List.of(temporary, sideFile)) {
              if (Files.isDirectory(path)) {
                Files.delete(path.resolve("obstruction"));
                Files.delete(path);
              }
            }
          } finally {
            if (order.isHeldByCurrentThread()) {
              order.unlock();
            }
          }
        }
      }
    }
  }

  /** Q-f on fresh history cannot bootstrap completion. A subsequent capable unit can. */
  @Test
  public void plainFreshOutputMergesAndLaterCapableOutputBootstraps() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var tracker = tracker(storage);
      var plain = new ByteArrayOutputStream();
      var name = storage.backup(() -> List.<String>of().iterator(),
          file -> InputStream.nullInputStream(), file -> plain, file -> {
          });
      var unit = plain.toByteArray();
      org.junit.Assert.assertNull(literalContinuity(unit));
      assertCanonicalVersionSix(unit, name, storage.getUuid(), 0, false);
      org.junit.Assert.assertNull(trackerField(tracker, "lastCompleted"));
      org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
      var capable = new RecordingOutput();
      storage.backup(() -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> capable, file -> {
          });
      var pair = literalContinuity(capable.bytes.toByteArray());
      assertNotNull(pair);
      assertEquals(pair.generationId(), trackerField(tracker, "lastCompleted"));
    }
  }

  /** Q-f carries the previous pair, saves no completion, and preserves pre/post-boundary marks. */
  @Test
  public void plainOutputKeepsPreviousCompletionAndBothGenerationsOfMarks() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      long evidence = storage.getWriteCache().addFile("plain.tst");
      for (int page = 0; page < 64; page++) {
        writeEvidencePage(storage, evidence, page, page);
      }
      var full = storage.fullBackup(backupPath);
      writeEvidencePage(storage, evidence, 7, 7007);
      var previous = literalContinuity(Files.readAllBytes(backupPath.resolve(full)));
      var tracker = tracker(storage);
      var sideFile = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
      addOneRecord(manager);
      var orderMethod = tracker.getClass().getDeclaredMethod("saveOrderLock");
      orderMethod.setAccessible(true);
      var order = (java.util.concurrent.locks.ReentrantLock) orderMethod.invoke(tracker);
      var plain = new ByteArrayOutputStream();
      String name;
      order.lock();
      try {
        // Ordinary checkpoints can save pending marks. Exclude them while checking that Q-f itself
        // does not publish a completion file, including under the slower instrumented runner.
        var saved = Files.readAllBytes(sideFile);
        var mark = tracker.getClass().getDeclaredMethod("mark", int.class, long.class);
        mark.setAccessible(true);
        mark.invoke(tracker, 123456, 111L);
        var postBoundary = new java.util.concurrent.atomic.AtomicBoolean();
        OutputStream output = new java.io.FilterOutputStream(plain) {
          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            if (postBoundary.compareAndSet(false, true)) {
              try {
                assertNotNull(trackerField(tracker, "sealed"));
                mark.invoke(tracker, 123456, 222L);
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            }
            out.write(bytes, offset, length);
          }
        };
        var realReads = storage.getReadCache();
        var reads = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(realReads));
        var readField = AbstractStorage.class.getDeclaredField("readCache");
        readField.setAccessible(true);
        readField.set(storage, reads);
        try {
          name = storage.backup(this::unitIterator, this::openInput, file -> output,
              file -> {
                throw new AssertionError("no removal");
              });
          for (int page = 0; page < 64; page++) {
            org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(page == 7 ? 1 : 0))
                .silentLoadForRead(evidence, page, storage.getWriteCache(), true);
          }
        } finally {
          readField.set(storage, realReads);
        }
        assertTrue(postBoundary.get());
        assertEquals(previous, literalContinuity(plain.toByteArray()));
        assertEquals(previous.generationId(), trackerField(tracker, "lastCompleted"));
        org.junit.Assert.assertArrayEquals(saved, Files.readAllBytes(sideFile));
        org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
        var active = trackerField(tracker, "active");
        var candidates = active.getClass().getDeclaredMethod("forEachCandidate", int.class,
            java.util.function.LongConsumer.class);
        candidates.setAccessible(true);
        var pages = new ArrayList<Long>();
        candidates.invoke(active, 123456, (java.util.function.LongConsumer) pages::add);
        assertEquals(List.of(111L, 222L), pages);
      } finally {
        order.unlock();
      }
      Files.write(backupPath.resolve(name), plain.toByteArray());
      addOneRecord(manager);
      storage.backup(backupPath);
      manager.internal.restore("plainPreviousRestore", backupPath.toString(), null, null);
      try (var restored = manager.open("plainPreviousRestore", ADMIN, PASSWORD)) {
        assertEquals(3, restored.query("select from " + RECORD_CLASS).stream().count());
      }
    }
  }

  /**
   * Owner construction runs before lock acquisition. A constructor failure leaves exclusion free,
   * never reaches the suppliers, and permits a later real backup.
   */
  @Test
  public void failedOwnerConstructionLeavesBackupExclusionFree() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var constructions = new AtomicInteger();
      try (var ignored = org.mockito.Mockito.mockConstruction(DiskStorage.BackupRequestOwner.class,
          (owner, context) -> {
            assertBackupUnlocked(storage);
            constructions.incrementAndGet();
            throw new OutOfMemoryError("owner allocation");
          })) {
        assertThrows(org.mockito.exceptions.base.MockitoException.class, () -> storage.backup(
            () -> {
              throw new AssertionError("construction failure must precede suppliers");
            }, file -> InputStream.nullInputStream(), file -> new RecordingOutput(), file -> {
              throw new AssertionError("no removal");
            }));
        assertEquals(1, constructions.get());
        assertBackupUnlocked(storage);
      }
      assertNotNull(storage.backup(
          () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> new RecordingOutput(), file -> {
            throw new AssertionError("no removal");
          }));
      assertBackupUnlocked(storage);
    }
  }

  /** Begin failure after WAL retention registration removes retention without inventing ownership. */
  @Test
  public void failedBeginReleasesRetentionAndBackupExclusion() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var tracker = tracker(storage);
      var primary = new OutOfMemoryError("boundary allocation");
      var reached = new AtomicInteger();
      var oldObserver = replaceTrackerObserver(tracker, (method, args) -> {
        if (method.equals("beforeBoundaryAllocation")) {
          reached.incrementAndGet();
          throw primary;
        }
      });
      var realWal = storage.getWALInstance();
      var wal = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
          org.mockito.AdditionalAnswers.delegatesTo(realWal));
      var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
      walField.setAccessible(true);
      var output = new RecordingOutput();
      walField.set(storage, wal);
      try {
        assertSame(primary, assertThrows(OutOfMemoryError.class, () -> storage.backup(
            () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
            file -> output, file -> {
              throw new AssertionError("no removal");
            })));
        assertEquals(1, reached.get());
        var retained = org.mockito.ArgumentCaptor.forClass(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber.class);
        var sequence = org.mockito.Mockito.inOrder(wal);
        sequence.verify(wal).addCutTillLimit(retained.capture());
        sequence.verify(wal).removeCutTillLimit(retained.getValue());
        assertFalse(tracker.isTrusted());
        org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
        assertEquals(1, output.closeCalls);
        assertBackupUnlocked(storage);
      } finally {
        walField.set(storage, realWal);
        restoreTrackerObserver(tracker, oldObserver);
      }
      // A real retry must acquire exclusion and establish its own context.
      storage.backup(() -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
          file -> new RecordingOutput(), file -> {
          });
    }
  }

  /** Non-save completion errors preserve identity before and after staging and clean up only once. */
  @Test
  public void exceptionalCallerCompletionReleasesExclusionWithoutInventingSuccess()
      throws Exception {
    for (boolean staged : new boolean[] {false, true}) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("completion-" + staged).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        var primary = new AssertionError("completion " + staged);
        var reached = new AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var caller = Thread.currentThread();
        var oldObserver = replaceTrackerObserver(tracker, (method, args) -> {
          if (staged && closed.get() && Thread.currentThread() == caller
              && method.equals("beforeCompletionCapture")) {
            reached.incrementAndGet();
            throw primary;
          }
        });
        var realWal = storage.getWALInstance();
        var wal = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
            org.mockito.AdditionalAnswers.delegatesTo(realWal));
        org.mockito.Mockito.doAnswer(call -> {
          if (!staged && closed.get() && Thread.currentThread() == caller) {
            reached.incrementAndGet();
            throw primary;
          }
          return realWal.preflightCut(call.getArgument(0));
        }).when(wal).preflightCut(org.mockito.ArgumentMatchers.anyLong());
        var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
        walField.setAccessible(true);
        var output = new RecordingOutput();
        output.onClose = () -> closed.set(true);
        var records = new CopyOnWriteArrayList<LogRecord>();
        walField.set(storage, wal);
        try {
          withDiskStorageLogs(records, () -> assertSame(primary,
              assertThrows(AssertionError.class, () -> storage.backup(
                  () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
                  file -> output, file -> {
                    throw new AssertionError("no removal");
                  }))));
          assertEquals(1, reached.get());
          assertEquals(1, output.closeCalls);
          org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
          // Staging has detached the seal. The one stale cleanup is secondary, not retried.
          assertEquals(staged ? 1 : 0, primary.getSuppressed().length);
          if (staged) {
            assertTrue(primary.getSuppressed()[0] instanceof IllegalStateException);
            assertFalse(tracker.isTrusted());
          }
          assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
          assertBackupUnlocked(storage);
        } finally {
          walField.set(storage, realWal);
          restoreTrackerObserver(tracker, oldObserver);
        }
      }
    }
  }

  /** Body failure keeps merge failure secondary, releases exclusion and leaves unresolved work pending. */
  @Test
  public void callerBodyFailureMakesOneCleanupAttemptWithIdentitySafeSuppression()
      throws Exception {
    for (boolean same : new boolean[] {false, true}) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("cleanup-" + same).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        markTracker(tracker, 123456, 111L);
        markTracker(tracker, 234567, 222L);
        var primary = new AssertionError("body");
        var cleanup = same ? primary : new OutOfMemoryError("merge allocation");
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var attempts = new AtomicInteger();
        var oldObserver = replaceTrackerObserver(tracker, (method, args) -> {
          if (armed.get() && method.equals("beforeAllocation")
              && args[0].toString().equals("FILE")) {
            // Let the first file merge. The second allocation fails with the seal still present.
            if (attempts.incrementAndGet() >= 2) {
              throw cleanup;
            }
          }
        });
        var output = new RecordingOutput();
        output.onFirstWrite = () -> {
          armed.set(true);
          throw primary;
        };
        var records = new CopyOnWriteArrayList<LogRecord>();
        try {
          withDiskStorageLogs(records, () -> assertSame(primary,
              assertThrows(AssertionError.class, () -> storage.backup(
                  () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
                  file -> output, file -> {
                    throw new AssertionError("no removal");
                  }))));
          assertEquals(2, attempts.get());
          assertEquals(same ? 0 : 1, primary.getSuppressed().length);
          if (!same) {
            assertSame(cleanup, primary.getSuppressed()[0]);
          }
          assertEquals(1, output.closeCalls);
          assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
          assertBackupUnlocked(storage);
          var seal = trackerField(tracker, "sealed");
          assertNotNull(seal);
          var pending =
              com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
                  .begin(tracker, null, true);
          assertEquals(
              com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.BeginOutcome.PENDING_SEAL,
              pending.outcome());
          assertSame(seal, trackerField(tracker, "sealed"));
          armed.set(false);
          // Later real requests cannot repair or replace the earlier partial merge.
          for (boolean capable : new boolean[] {false, true}) {
            var later = new RecordingOutput();
            var plain = new ByteArrayOutputStream();
            var realReads = storage.getReadCache();
            var reads = org.mockito.Mockito.mock(
                com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
                org.mockito.AdditionalAnswers.delegatesTo(realReads));
            var readField = AbstractStorage.class.getDeclaredField("readCache");
            readField.setAccessible(true);
            readField.set(storage, reads);
            try {
              storage.backup(() -> List.<String>of().iterator(),
                  file -> InputStream.nullInputStream(), file -> capable ? later : plain,
                  file -> {
                    throw new AssertionError("no removal");
                  });
              var bytes = capable ? later.bytes.toByteArray() : plain.toByteArray();
              org.junit.Assert.assertNull(literalContinuity(bytes));
              org.junit.Assert.assertArrayEquals(new byte[36], java.util.Arrays.copyOfRange(
                  bytes, bytes.length - 110, bytes.length - 74));
              assertSame(seal, trackerField(tracker, "sealed"));
              assertFalse(tracker.isTrusted());
              var cache = storage.getWriteCache();
              int reached = 0;
              for (var id : cache.files().values()) {
                var external = cache.externalFileId(cache.internalFileId(id));
                var size = cache.physicalSizeForBackupSnapshot(external);
                for (int page = 0; page < size; page++) {
                  org.mockito.Mockito.verify(reads).silentLoadForRead(external, page, cache, true);
                  reached++;
                }
              }
              assertTrue(reached > 0);
            } finally {
              readField.set(storage, realReads);
            }
          }
          var restarted = saveAndMergeLoadedTracker(tracker);
          org.junit.Assert.assertNull(trackerField(restarted, "sealed"));
          assertEquals(List.of(111L), trackerCandidates(restarted, "active", 123456));
          assertEquals(List.of(222L), trackerCandidates(restarted, "active", 234567));
          markTracker(tracker, 345678, 333L);
          saveTrackerAt(tracker,
              databasesPath.resolve("cleanup-" + same).resolve(SOURCE).resolve("changed-pages.cpt"),
              storage.getWALInstance().begin());
        } finally {
          armed.set(false);
          restoreTrackerObserver(tracker, oldObserver);
        }
      }
      // The first manager and its caches are fully stopped before real startup owns this image.
      var image = databasesPath.resolve("cleanup-" + same);
      var saved = Files.readAllBytes(image.resolve(SOURCE).resolve("changed-pages.cpt"));
      assertTrue("real startup must receive a saved pending seal", (saved[8] & 4) != 0);
      try (var reopened = (YouTrackDBImpl) YourTracks.instance(image.toString());
          var session = reopened.open(SOURCE, ADMIN, PASSWORD)) {
        var loaded = tracker((AbstractStorage) session.getStorage());
        org.junit.Assert.assertNull("startup reaches mergeLoadedSealed",
            trackerField(loaded, "sealed"));
        assertEquals(List.of(111L), trackerCandidates(loaded, "active", 123456));
        assertEquals(List.of(222L), trackerCandidates(loaded, "active", 234567));
        assertEquals(List.of(333L), trackerCandidates(loaded, "active", 345678));
      }
    }
  }

  /** Readable capable and Q-f failed units are retained unchanged and extended without tail repair. */
  @Test
  public void failedCapableUnitsMergeMarksAndExtendWithoutRewritingTheirTail() throws Exception {
    for (var phase : List.of("tailFlush", "secondForce", "close", "plainClose")) {
      var casePath = databasesPath.resolve(phase);
      var units = Files.createDirectories(root.resolve("units-" + phase));
      try (var manager = (YouTrackDBImpl) YourTracks.instance(casePath.toString())) {
        var storage = createSourceDatabase(manager);
        long evidence = storage.getWriteCache().addFile("retained.tst");
        for (int page = 0; page < 64; page++) {
          writeEvidencePage(storage, evidence, page, page);
        }
        var full = storage.fullBackup(units);
        writeEvidencePage(storage, evidence, 7, 7007);
        var previous = literalContinuity(Files.readAllBytes(units.resolve(full)));
        var tracker = tracker(storage);
        addOneRecord(manager);
        markTracker(tracker, 123456, 111L);
        var output = new RecordingOutput();
        boolean plain = phase.equals("plainClose");
        output.failPhase = plain ? "close" : phase;
        var marked = new java.util.concurrent.atomic.AtomicBoolean();
        output.onFirstWrite = () -> {
          if (marked.compareAndSet(false, true)) {
            try {
              markTracker(tracker, 123456, 222L);
              writeEvidencePage(storage, evidence, 33, 3333);
              addOneRecord(manager);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          }
        };
        var retainedName = new ArrayList<String>();
        var records = new CopyOnWriteArrayList<LogRecord>();
        withDiskStorageLogs(records, () -> assertThrows(DatabaseException.class,
            () -> storage.backup(() -> List.of(full).iterator(), file -> {
              try {
                return Files.newInputStream(units.resolve(file));
              } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            }, file -> {
              retainedName.add(file);
              // Hide the force capability for the retained Q-f close-failure row.
              return plain ? new java.io.FilterOutputStream(output) {
                @Override
                public void close() throws IOException {
                  out.close();
                }
              } : output;
            }, file -> {
              throw new AssertionError("no removal");
            })));
        assertTrue(output.faultReached);
        assertTrue(marked.get());
        assertEquals(1, output.closeCalls);
        assertEquals(0, output.writesAfterTail);
        assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
        assertEquals(previous.generationId(), trackerField(tracker, "lastCompleted"));
        org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
        assertEquals(List.of(111L, 222L), trackerCandidates(tracker, "active", 123456));
        assertBackupUnlocked(storage);
        var retained = output.bytes.toByteArray();
        var failedPair = literalContinuity(retained);
        assertNotNull(failedPair);
        if (plain) {
          assertEquals(previous, failedPair);
        } else {
          assertFalse(previous.equals(failedPair));
        }
        var admission =
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
                .begin(tracker, DiskStorage.toFacadeContinuity(failedPair),
                    true);
        assertEquals(plain, admission.selectionEligible());
        com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
            .abandon(tracker, admission);
        var failedPath = units.resolve(retainedName.getFirst());
        Files.write(failedPath, retained);
        var next = measuredEvidenceBackup(storage, units, evidence, 64,
            plain ? List.of(7L, 33L) : java.util.stream.LongStream.range(0, 64).boxed().toList());
        var nextPair = literalContinuity(Files.readAllBytes(units.resolve(next)));
        assertFalse(failedPair.generationId().equals(nextPair.generationId()));
        org.junit.Assert.assertArrayEquals(retained, Files.readAllBytes(failedPath));
        manager.internal.restore("retryRestore", units.toString(), null, null);
        try (var restored = manager.open("retryRestore", ADMIN, PASSWORD)) {
          assertEquals(3, restored.query("select from " + RECORD_CLASS).stream().count());
        }
      }
    }
  }

  /** Pending caller requests read all pages, warn exactly and never act on the earlier seal. */
  @Test
  public void pendingCallerOutputsHaveNoContinuityOrAuthorityForEitherCapability()
      throws Exception {
    for (boolean capable : new boolean[] {false, true}) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("pending-" + capable).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        markTracker(tracker, 123456, 111L);
        var earlier =
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
                .begin(tracker, null, true);
        var sealed = trackerField(tracker, "sealed");
        var active = trackerField(tracker, "active");
        var capableOutput = new RecordingOutput();
        var plainOutput = new ByteArrayOutputStream();
        var realReads = storage.getReadCache();
        var reads = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(realReads));
        var readField = AbstractStorage.class.getDeclaredField("readCache");
        readField.setAccessible(true);
        readField.set(storage, reads);
        var records = new CopyOnWriteArrayList<LogRecord>();
        try {
          withDiskStorageLogs(records, () -> storage.backup(
              () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> capable ? capableOutput : plainOutput, file -> {
                throw new AssertionError("no removal");
              }));
          var unit = capable ? capableOutput.bytes.toByteArray() : plainOutput.toByteArray();
          org.junit.Assert.assertNull(literalContinuity(unit));
          org.junit.Assert.assertArrayEquals(new byte[36], java.util.Arrays.copyOfRange(
              unit, unit.length - 122 + 12, unit.length - 122 + 48));
          assertSame(sealed, trackerField(tracker, "sealed"));
          assertSame(active, trackerField(tracker, "active"));
          assertFalse(tracker.isTrusted());
          var warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING)
              .map(LogRecord::getMessage).toList();
          assertEquals(List.of("youtrackdb:" + SOURCE + " [" + SOURCE
              + "] Backup has a pending seal. Continuity is absent."), warnings);
          var cache = storage.getWriteCache();
          int reached = 0;
          for (var id : cache.files().values()) {
            var external = cache.externalFileId(cache.internalFileId(id));
            var size = cache.physicalSizeForBackupSnapshot(external);
            for (int page = 0; page < size; page++) {
              org.mockito.Mockito.verify(reads).silentLoadForRead(external, page, cache, true);
              reached++;
            }
          }
          assertTrue(reached > 0);
          assertBackupUnlocked(storage);
        } finally {
          readField.set(storage, realReads);
          com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
              .abandon(tracker, earlier);
        }
      }
    }
  }

  /** Group 2: the admitted writer drains into sealed, and a resumed writer marks only active. */
  @Test
  public void writersStraddleTheRealBackupPause() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var tracker = tracker(storage);
      var operations = storage.getAtomicOperationsManager();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var releaseBefore = new java.util.concurrent.CountDownLatch(1);
      var frozen = new java.util.concurrent.CountDownLatch(1);
      var boundary = new java.util.concurrent.CountDownLatch(1);
      var releaseBoundary = new java.util.concurrent.CountDownLatch(1);
      var after = new java.util.concurrent.CountDownLatch(1);
      var afterEntered = new java.util.concurrent.CountDownLatch(1);
      var releaseAfter = new java.util.concurrent.CountDownLatch(1);
      var afterThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      var previous = replaceTrackerObserver(tracker, (method, args) -> {
        if (method.equals("beforeBoundaryAllocation") && args[0].toString().equals("CONTEXT")) {
          boundary.countDown();
          awaitBackupLatch(releaseBoundary);
        }
      });
      try {
        tasks.add(executor.submit(() -> {
          operations.executeInsideAtomicOperation(op -> {
            entered.countDown();
            awaitBackupLatch(releaseBefore);
            markTracker(tracker, 123456, 111L);
          });
          return null;
        }));
        assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
        operations.setFreezeRegisteredActionForTesting(frozen::countDown);
        var output = new RecordingOutput();
        output.onFirstWrite = () -> {
          // Copying starts after unfreeze. Keep completion away from the generation oracle.
          awaitBackupLatch(after);
          try {
            var seal = trackerField(tracker, "sealed");
            var pages = seal.getClass().getDeclaredMethod("pages");
            pages.setAccessible(true);
            assertEquals(List.of(111L), generationCandidates(pages.invoke(seal), 123456));
            assertEquals(List.of(), trackerCandidates(tracker, "active", 123456));
            assertEquals(List.of(), generationCandidates(pages.invoke(seal), 234567));
            assertEquals(List.of(222L), trackerCandidates(tracker, "active", 234567));
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
        tasks.add(executor.submit(() -> storage.backup(() -> List.<String>of().iterator(),
            file -> InputStream.nullInputStream(), file -> output, file -> {
            })));
        assertTrue(frozen.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("begin must wait for the admitted writer", 1L, boundary.getCount());
        releaseBefore.countDown();
        assertTrue(boundary.await(30, java.util.concurrent.TimeUnit.SECONDS));
        var afterStarted = new java.util.concurrent.CountDownLatch(1);
        tasks.add(executor.submit(() -> {
          afterThread.set(Thread.currentThread());
          afterStarted.countDown();
          operations.executeInsideAtomicOperation(op -> {
            afterEntered.countDown();
            awaitBackupLatch(releaseAfter);
            markTracker(tracker, 234567, 222L);
            after.countDown();
          });
          return null;
        }));
        assertTrue(afterStarted.await(30, java.util.concurrent.TimeUnit.SECONDS));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        Object blocker;
        do {
          blocker = java.util.concurrent.locks.LockSupport.getBlocker(afterThread.get());
          if (blocker != null && blocker.getClass().getSimpleName().equals("OperationsFreezer")) {
            break;
          }
          Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        assertNotNull("second writer must reach the actual freezer", blocker);
        assertEquals("OperationsFreezer", blocker.getClass().getSimpleName());
        assertEquals("the second writer cannot pass the pause", 1L, afterEntered.getCount());
        releaseBoundary.countDown();
        assertTrue(afterEntered.await(30, java.util.concurrent.TimeUnit.SECONDS));
        releaseAfter.countDown();
        for (var task : tasks) {
          task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
      } finally {
        releaseBefore.countDown();
        releaseBoundary.countDown();
        releaseAfter.countDown();
        after.countDown();
        try {
          for (var task : tasks) {
            task.get(30, java.util.concurrent.TimeUnit.SECONDS);
          }
        } finally {
          operations.clearTestActions();
          restoreTrackerObserver(tracker, previous);
          executor.shutdownNow();
          assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
      }
    }
  }

  /** Group 2 / CN2: begin completes while an older side-file force owns save order. */
  @Test
  public void beginDoesNotWaitForAnOlderSideFileForce() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var tracker = tracker(storage);
      var forcing = new java.util.concurrent.CountDownLatch(1);
      var releaseForce = new java.util.concurrent.CountDownLatch(1);
      var copying = new java.util.concurrent.CountDownLatch(1);
      var releaseCopy = new java.util.concurrent.CountDownLatch(1);
      var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
      var type = Class.forName(
          "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile$FileOperations");
      var target = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
      var coverage = storage.getWALInstance().begin();
      var older = executor.submit(() -> {
        try (var mocked = org.mockito.Mockito.mockConstruction(type,
            org.mockito.Mockito.withSettings().defaultAnswer(call -> {
              var result = call.callRealMethod();
              if (call.getMethod().getName().equals("forceFile")) {
                forcing.countDown();
                awaitBackupLatch(releaseForce);
              }
              return result;
            }))) {
          var codec = Class.forName(
              "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile");
          var save = codec.getDeclaredMethod("save", Path.class, tracker.getClass(),
              coverage.getClass());
          save.setAccessible(true);
          // Begin can invalidate this older capture. Its save result is not the begin oracle.
          return save.invoke(null, target, tracker, coverage);
        }
      });
      java.util.concurrent.Future<?> backup = null;
      try {
        assertTrue(forcing.await(30, java.util.concurrent.TimeUnit.SECONDS));
        var output = new RecordingOutput();
        output.onFirstWrite = () -> {
          try {
            assertNotNull(trackerField(tracker, "sealed"));
            assertEquals("older force is still blocked", 1L, releaseForce.getCount());
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
          copying.countDown();
          awaitBackupLatch(releaseCopy);
        };
        backup = executor.submit(() -> storage.backup(() -> List.<String>of().iterator(),
            file -> InputStream.nullInputStream(), file -> output, file -> {
            }));
        assertTrue("begin and unfreeze must precede release of the older force",
            copying.await(30, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        releaseForce.countDown();
        releaseCopy.countDown();
        try {
          older.get(30, java.util.concurrent.TimeUnit.SECONDS);
          if (backup != null) {
            backup.get(30, java.util.concurrent.TimeUnit.SECONDS);
          }
        } finally {
          executor.shutdownNow();
          assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
      }
    }
  }

  /** Group 8: failed caller invalidation authorizes no later cut until removal is durable. */
  @Test
  public void failedCallerInvalidationCannotAuthorizeALaterWalCut() throws Exception {
    try (var manager = crashManager(databasesPath)) {
      var storage = createSourceDatabase(manager);
      var tracker = tracker(storage);
      var orderMethod = tracker.getClass().getDeclaredMethod("saveOrderLock");
      orderMethod.setAccessible(true);
      var order = (java.util.concurrent.locks.ReentrantLock) orderMethod.invoke(tracker);
      var realWal = storage.getWALInstance();
      for (int i = 0; i < 3; i++) {
        realWal.appendNewSegment();
        addOneRecord(manager);
      }
      realWal.flush();
      var wal = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
          org.mockito.AdditionalAnswers.delegatesTo(realWal));
      var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
      walField.setAccessible(true);
      var side = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
      var temporary = side.resolveSibling("changed-pages.cpt.tmp");
      var writerField = realWal.getClass().getDeclaredField("recordsWriterLock");
      writerField.setAccessible(true);
      var writer = (java.util.concurrent.locks.ReentrantLock) writerField.get(realWal);
      var forcedInvalidation = new AtomicInteger();
      var cuts = new AtomicInteger();
      org.mockito.Mockito.doAnswer(call -> {
        cuts.incrementAndGet();
        assertTrue("durable invalidation must precede the cut", forcedInvalidation.get() > 0);
        assertTrue("invalidation must remove or empty the side file",
            !Files.exists(side) || Files.size(side) == 0);
        return realWal.cutAllSegmentsSmallerThan(call.getArgument(0));
      }).when(wal).cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
      var output = new RecordingOutput();
      output.onClose = () -> {
        order.lock();
        try {
          Files.createDirectories(temporary);
          Files.writeString(temporary.resolve("obstruction"), "save blocker");
          Files.deleteIfExists(side);
          Files.createDirectory(side);
          Files.writeString(side.resolve("obstruction"), "invalidation blocker");
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      };
      walField.set(storage, wal);
      try {
        var records = new CopyOnWriteArrayList<LogRecord>();
        withDiskStorageLogs(records, () -> storage.backup(() -> List.<String>of().iterator(),
            file -> InputStream.nullInputStream(), file -> output, file -> {
            }));
        assertTrue(records.stream().anyMatch(r -> r.getMessage()
            .endsWith("Backup completion save and invalidation failed.")));
        // Backup rotation can still publish a new physical WAL file asynchronously. Drain it
        // before the inventory oracle, then exclude the writer across both coordinator calls.
        // Never flush while holding recordsWriterLock.
        realWal.flush();
        assertTrue(writer.tryLock(30, java.util.concurrent.TimeUnit.SECONDS));
        var boundary = realWal.activeSegment();
        assertTrue("fixture must predict real removal",
            realWal.preflightCut(boundary).removesSegments());
        var inventory = physicalWalInventory();
        assertThrows(IOException.class,
            () -> ((DiskStorage) storage).checkpointChangedPages(boundary));
        assertEquals(0, cuts.get());
        assertEquals(inventory, physicalWalInventory());
        Files.delete(side.resolve("obstruction"));
        Files.delete(side);
        var type = Class.forName(
            "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile$FileOperations");
        try (var mocked = org.mockito.Mockito.mockConstruction(type,
            org.mockito.Mockito.withSettings().defaultAnswer(call -> {
              var result = call.callRealMethod();
              var method = call.getMethod().getName();
              // Portable deletion forces the folder. Windows publishes an empty tombstone or
              // forces the existing file empty when native replacement is unavailable.
              if ((method.equals("forceFolder") || method.equals("windowsMove")
                  || method.equals("truncateAndForce"))
                  && (!Files.exists(side) || Files.size(side) == 0)) {
                forcedInvalidation.incrementAndGet();
              }
              return result;
            }))) {
          var result = ((DiskStorage) storage).checkpointChangedPages(boundary);
          assertEquals("INVALIDATED", result.outcome().toString());
          assertTrue(result.segmentsRemoved());
          assertEquals(1, cuts.get());
          assertTrue(forcedInvalidation.get() > 0);
          assertTrue(physicalWalInventory().size() < inventory.size());
        }
      } finally {
        if (writer.isHeldByCurrentThread()) {
          writer.unlock();
        }
        walField.set(storage, realWal);
        for (var path : List.of(temporary, side)) {
          if (Files.isDirectory(path)) {
            Files.deleteIfExists(path.resolve("obstruction"));
            Files.delete(path);
          }
        }
        if (order.isHeldByCurrentThread()) {
          order.unlock();
        }
      }
    }
  }

  /** T37-DU1: finish failures preserve identity, one cleanup and pending ownership at the caller. */
  @Test
  public void callerFinishMergeFailuresReleaseExclusionAndKeepPendingOwnership() throws Exception {
    for (String mode : List.of("plain", "nonDurable", "coverage")) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("finish-" + mode).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        markTracker(tracker, 123456, 111L);
        markTracker(tracker, 234567, 222L);
        var primary = new OutOfMemoryError("finish merge");
        var cleanup = new OutOfMemoryError("finish cleanup");
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var attempts = new AtomicInteger();
        var previous = replaceTrackerObserver(tracker, (method, args) -> {
          if (armed.get() && method.equals("beforeAllocation")
              && args[0].toString().equals("FILE")) {
            int count = attempts.incrementAndGet();
            if (count >= 2) {
              throw count == 2 ? primary : cleanup;
            }
          }
        });
        var realWal = storage.getWALInstance();
        var wal = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
            org.mockito.AdditionalAnswers.delegatesTo(realWal));
        org.mockito.Mockito.doAnswer(call -> mode.equals("coverage") && armed.get()
            ? null : realWal.preflightCut(call.getArgument(0)))
            .when(wal).preflightCut(org.mockito.ArgumentMatchers.anyLong());
        var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
        walField.setAccessible(true);
        walField.set(storage, wal);
        var output = new RecordingOutput();
        output.onClose = () -> armed.set(true);
        var factsType =
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.CompletionFacts.class;
        // This boundary-ineligible supplier reports (true, true). Intercept its record to
        // exercise non-durable facts without adding an unreachable production switch.
        try (var facts = org.mockito.Mockito.mockConstruction(factsType, (mock, context) -> {
          org.mockito.Mockito.doReturn(!mode.equals("nonDurable")).when(mock).completeUnitDurable();
          org.mockito.Mockito.doReturn(true).when(mock).fullRead();
        })) {
          var records = new CopyOnWriteArrayList<LogRecord>();
          withDiskStorageLogs(records, () -> assertSame(primary,
              assertThrows(OutOfMemoryError.class, () -> storage.backup(
                  () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
                  file -> mode.equals("plain") ? new java.io.FilterOutputStream(output) {
                    @Override
                    public void close() throws IOException {
                      out.close();
                    }
                  } : output, file -> {
                  }))));
          assertEquals(1, facts.constructed().size());
          assertEquals(mode.equals("coverage") ? 3 : 2, attempts.get());
          assertEquals(mode.equals("coverage") ? 1 : 0, primary.getSuppressed().length);
          if (mode.equals("coverage")) {
            assertSame(cleanup, primary.getSuppressed()[0]);
          }
          assertEquals(1, output.closeCalls);
          assertFalse(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
          assertBackupUnlocked(storage);
          var seal = trackerField(tracker, "sealed");
          assertNotNull(seal);
          org.junit.Assert.assertNull(trackerField(tracker, "lastCompleted"));
          armed.set(false);
          var later = new RecordingOutput();
          storage.backup(() -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> later, file -> {
              });
          org.junit.Assert.assertNull(literalContinuity(later.bytes.toByteArray()));
          assertSame(seal, trackerField(tracker, "sealed"));
          var loaded = saveAndMergeLoadedTracker(tracker);
          assertEquals(List.of(111L), trackerCandidates(loaded, "active", 123456));
          assertEquals(List.of(222L), trackerCandidates(loaded, "active", 234567));
          markTracker(tracker, 345678, 333L);
          saveTrackerAt(tracker, databasesPath.resolve("finish-" + mode).resolve(SOURCE)
              .resolve("changed-pages.cpt"), realWal.begin());
        } finally {
          armed.set(false);
          walField.set(storage, realWal);
          restoreTrackerObserver(tracker, previous);
        }
      }
      // A fresh manager runs real startup only after the first cache has stopped.
      var image = databasesPath.resolve("finish-" + mode);
      assertTrue(
          (Files.readAllBytes(image.resolve(SOURCE).resolve("changed-pages.cpt"))[8] & 4) != 0);
      try (var reopened = (YouTrackDBImpl) YourTracks.instance(image.toString());
          var session = reopened.open(SOURCE, ADMIN, PASSWORD)) {
        var loaded = tracker((AbstractStorage) session.getStorage());
        org.junit.Assert.assertNull(trackerField(loaded, "sealed"));
        org.junit.Assert.assertNull(trackerField(loaded, "lastCompleted"));
        assertEquals(List.of(111L), trackerCandidates(loaded, "active", 123456));
        assertEquals(List.of(222L), trackerCandidates(loaded, "active", 234567));
        assertEquals(List.of(333L), trackerCandidates(loaded, "active", 345678));
      }
    }
  }

  private List<String> physicalWalInventory() throws IOException {
    try (var paths = Files.walk(databasesPath.resolve(SOURCE))) {
      return paths.filter(p -> p.getFileName().toString().endsWith(".wal"))
          .map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  private static void awaitBackupLatch(java.util.concurrent.CountDownLatch latch) {
    try {
      assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static List<Long> generationCandidates(Object generation, int file) throws Exception {
    var method = generation.getClass().getDeclaredMethod("forEachCandidate", int.class,
        java.util.function.LongConsumer.class);
    method.setAccessible(true);
    var pages = new ArrayList<Long>();
    method.invoke(generation, file, (java.util.function.LongConsumer) pages::add);
    return pages;
  }

  /** A second request cannot begin while the first completion or Q-f merge waits in save order. */
  @Test
  public void backupExclusionCoversCompletionAndMergeWaits() throws Exception {
    for (boolean capable : new boolean[] {false, true}) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("exclusion-" + capable).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        var orderMethod = tracker.getClass().getDeclaredMethod("saveOrderLock");
        orderMethod.setAccessible(true);
        var order = (java.util.concurrent.locks.ReentrantLock) orderMethod.invoke(tracker);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var continueClose = new java.util.concurrent.CountDownLatch(1);
        var backupThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var output = new RecordingOutput();
        output.onClose = () -> {
          closed.countDown();
          try {
            assertTrue(continueClose.await(30, java.util.concurrent.TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
          }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var task = executor.submit(() -> {
          backupThread.set(Thread.currentThread());
          return storage.backup(() -> List.<String>of().iterator(),
              file -> InputStream.nullInputStream(), file -> capable ? output
                  : new java.io.FilterOutputStream(output) {
                    @Override
                    public void close() throws IOException {
                      out.close();
                    }
                  },
              file -> {
                throw new AssertionError("no removal");
              });
        });
        try {
          assertTrue(closed.await(30, java.util.concurrent.TimeUnit.SECONDS));
          order.lock();
          try {
            continueClose.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!order.hasQueuedThread(backupThread.get()) && System.nanoTime() < deadline) {
              Thread.sleep(1);
            }
            assertTrue("the terminal operation must reach save order",
                order.hasQueuedThread(backupThread.get()));
            assertThrows(
                com.jetbrains.youtrackdb.internal.core.exception.BackupInProgressException.class,
                () -> storage.backup(() -> List.<String>of().iterator(),
                    file -> InputStream.nullInputStream(), file -> {
                      throw new AssertionError("a second output must not be constructed");
                    }, file -> {
                      throw new AssertionError("no removal");
                    }));
            assertNotNull(trackerField(tracker, "sealed"));
          } finally {
            order.unlock();
          }
          assertNotNull(task.get(30, java.util.concurrent.TimeUnit.SECONDS));
          org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
          assertBackupUnlocked(storage);
        } finally {
          continueClose.countDown();
          try {
            task.get(30, java.util.concurrent.TimeUnit.SECONDS);
          } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
          }
        }
      }
    }
  }

  /** Independent side-file force observation follows both output forces and the one explicit close. */
  @Test
  public void completionSideFileForceFollowsTheCompleteOutputBarrier() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var output = new RecordingOutput();
      var sideForces = new AtomicInteger();
      var moves = new AtomicInteger();
      var windowsMoves = new AtomicInteger();
      var windows = new java.util.concurrent.atomic.AtomicBoolean();
      var folders = new AtomicInteger();
      var type = Class.forName(
          "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile$FileOperations");
      var settings = org.mockito.Mockito.withSettings().defaultAnswer(call -> {
        switch (call.getMethod().getName()) {
          case "forceFile" -> {
            assertEquals("side publication requires the complete-unit force", 2, output.forceCalls);
            assertEquals("side publication requires successful explicit close", 1,
                output.closeCalls);
            assertTrue(output.closed);
            sideForces.incrementAndGet();
          }
          case "windows" -> {
            var result = (boolean) call.callRealMethod();
            windows.set(result);
            return result;
          }
          case "windowsMove" -> {
            assertEquals(1, sideForces.get());
            assertEquals(0, moves.get());
            assertEquals(0, folders.get());
            windowsMoves.incrementAndGet();
          }
          case "move" -> {
            // The overload delegates to the varargs overload. Count the physical move only.
            if (call.getRawArguments().length == 3) {
              assertEquals(1, sideForces.get());
              moves.incrementAndGet();
            }
          }
          case "forceFolder" -> {
            assertEquals(1, moves.get());
            folders.incrementAndGet();
          }
          default -> {
          }
        }
        return call.callRealMethod();
      });
      // Construction interception is thread-local. Ordinary checkpoint forces are not counted.
      try (var observed = org.mockito.Mockito.mockConstruction(type, settings)) {
        storage.backup(() -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
            file -> output, file -> {
              throw new AssertionError("no removal");
            });
        assertFalse(observed.constructed().isEmpty());
        assertEquals(1, sideForces.get());
        assertEquals(windows.get() ? 1 : 0, windowsMoves.get());
        assertEquals(windows.get() ? 0 : 1, moves.get());
        assertEquals(windows.get() ? 0 : 1, folders.get());
        assertEquals(literalContinuity(output.bytes.toByteArray()).generationId(),
            trackerField(tracker(storage), "lastCompleted"));
      }
    }
  }

  /** Throwing warning delivery cannot undo completion, and publication restores inherited interrupt. */
  @Test
  public void callerWarningFailuresAndInheritedInterruptKeepTerminalOwnership() throws Exception {
    for (boolean distrust : new boolean[] {false, true}) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("diagnostic-" + distrust).toString())) {
        var storage = createSourceDatabase(manager);
        var tracker = tracker(storage);
        var publication = new AtomicInteger();
        var interruptedAtPublication = new java.util.concurrent.atomic.AtomicBoolean();
        var caller = Thread.currentThread();
        var oldObserver = replaceTrackerObserver(tracker, (method, args) -> {
          if (Thread.currentThread() == caller && method.equals("beforeCompletionCapture")) {
            publication.incrementAndGet();
            interruptedAtPublication.set(Thread.currentThread().isInterrupted());
          }
        });
        var output = new RecordingOutput();
        output.onClose = () -> {
          if (distrust) {
            try {
              var invalidate = tracker.getClass().getDeclaredMethod("invalidate");
              invalidate.setAccessible(true);
              invalidate.invoke(tracker);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          }
          Thread.currentThread().interrupt();
        };
        var warnings = new AtomicInteger();
        var records = new CopyOnWriteArrayList<LogRecord>() {
          @Override
          public boolean add(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
              warnings.incrementAndGet();
              throw new AssertionError("diagnostic delivery");
            }
            return super.add(record);
          }
        };
        try {
          withDiskStorageLogs(records, () -> storage.backup(
              () -> List.<String>of().iterator(), file -> InputStream.nullInputStream(),
              file -> output, file -> {
                throw new AssertionError("no removal");
              }));
          assertTrue(Thread.currentThread().isInterrupted());
          assertTrue(interruptedAtPublication.get());
          assertEquals(1, publication.get());
          assertEquals(distrust ? 1 : 0, warnings.get());
          assertEquals(1, output.closeCalls);
          assertEquals(2, output.forceCalls);
          org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
          assertEquals(!distrust, tracker.isTrusted());
          assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("is completed")));
          assertBackupUnlocked(storage);
        } finally {
          Thread.interrupted();
          restoreTrackerObserver(tracker, oldObserver);
        }
      }
    }
  }

  /**
   * Group 10: a halt after successful completion preserves the exact pair and active marks.
   * This proves restart reconstruction and the publication boundary, not force placement.
   */
  @Test
  public void crashAfterCompletionSavePreservesPairAndActiveMarks() throws Exception {
    assertCrashRestart("published");
  }

  /**
   * Group 11: output closed, completion not saved. Startup cannot infer completion from the tail.
   * This proves restart reconstruction and the pre-publication boundary, not force placement.
   */
  @Test
  public void crashAfterOutputCloseDoesNotInventCompletion() throws Exception {
    assertCrashRestart("closed");
  }

  /**
   * Group 12: a checkpoint during copying saves pending and active marks for real startup merge.
   * This proves restart reconstruction and pending-seal publication, not force placement.
   */
  @Test
  public void crashWithSavedPendingSealMergesBothGenerations() throws Exception {
    assertCrashRestart("pending");
  }

  private void assertCrashRestart(String mode) throws Exception {
    var handshake = root.resolve("reached");
    // Reuse the bounded, output-draining JVM pattern from DiskStorageBootstrapWiringTest.
    var process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "-Dstorage.diskCache.bufferSize=64",
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        getClass().getName(), mode, root.toString(), handshake.toString())
        .redirectErrorStream(true).start();
    var drained = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return new String(process.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    });
    try {
      assertTrue("crash child timed out",
          process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(drained.get(10, java.util.concurrent.TimeUnit.SECONDS), 0, process.exitValue());
      assertEquals("positive reached-site handshake", mode, Files.readString(handshake));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
      drained.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    if (mode.equals("closed")) {
      assertClosedRestartAfterEarlierCompletion();
      return;
    }
    var sideFile = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
    var savedBytes = Files.readAllBytes(sideFile);
    // Inspect the saved image before any startup checkpoint can replace it.
    assertEquals("pending flag at the observed boundary", mode.equals("pending"),
        (savedBytes[8] & 4) != 0);
    DiskStorage.BackupContinuity exported = null;
    if (!mode.equals("pending")) {
      exported = literalContinuity(Files.readAllBytes(backupPath.resolve(unitNames().getFirst())));
      assertNotNull(exported);
      if (mode.equals("published")) {
        assertSavedPair(sideFile, exported);
      }
    }
    try (var manager = crashManager(databasesPath);
        var session = manager.open(SOURCE, ADMIN, PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();
      var tracker = tracker(storage);
      org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
      assertTrue("saved active mark survives real startup",
          trackerCandidates(tracker, "active", 123456).contains(222L));
      if (mode.equals("published")) {
        assertTrue("real WAL startup proof must preserve trust", tracker.isTrusted());
        assertEquals(exported.trackerId(), trackerField(tracker, "trackerId"));
        assertEquals(exported.generationId(), trackerField(tracker, "lastCompleted"));
      } else {
        org.junit.Assert.assertNull("no saved completion may be inferred from backup output",
            trackerField(tracker, "lastCompleted"));
        if (mode.equals("pending")) {
          assertTrue("saved sealed mark survives startup merge",
              trackerCandidates(tracker, "active", 123456).contains(111L));
        }
        storage.synch();
        org.junit.Assert.assertNull("an unrelated checkpoint cannot consume the backup ID",
            trackerField(tracker, "lastCompleted"));
      }
      if (mode.equals("published")) {
        assertTrue("post-close active mark survives publication and startup",
            trackerCandidates(tracker, "active", 123456).contains(333L));
      }
      var expectedRecords = mode.equals("published") ? 3L : 2L;
      try (var result = session.query("select count(*) as count from " + RECORD_CLASS)) {
        assertEquals(expectedRecords, ((Number) result.next().getProperty("count")).longValue());
      }
      if (mode.equals("pending")) {
        // Incomplete copying output stays untouched and cannot be admitted as a chain head.
        backupPath = Files.createDirectory(root.resolve("restart-backup"));
      }
      var next = storage.backup(backupPath);
      var nextPair = literalContinuity(Files.readAllBytes(backupPath.resolve(next)));
      assertNotNull(nextPair);
      var consumed = UUID.fromString(Files.readString(root.resolve("consumed")));
      assertFalse("restart must consume a fresh ID", consumed.equals(nextPair.generationId()));
      manager.internal.restore("crashRestore", backupPath.toString(), null, null);
      try (var restored = manager.open("crashRestore", ADMIN, PASSWORD);
          var result = restored.query("select count(*) as count from " + RECORD_CLASS)) {
        assertEquals(expectedRecords, ((Number) result.next().getProperty("count")).longValue());
      }
    }
  }

  /**
   * A checkpoint while the later selected unit is pending preserves the earlier completion and
   * both generations. Real startup proof must merge them without trusting the closed newer head.
   */
  private void assertClosedRestartAfterEarlierCompletion() throws Exception {
    var names = unitNames();
    assertEquals(2, names.size());
    var earlier = literalContinuity(Files.readAllBytes(backupPath.resolve(names.getFirst())));
    var later = literalContinuity(Files.readAllBytes(backupPath.resolve(names.getLast())));
    assertNotNull(earlier);
    assertNotNull(later);
    assertFalse(earlier.equals(later));
    var side = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
    assertSavedPair(side, earlier, 7);
    assertTrue("copy checkpoint must retain pending marks",
        (Files.readAllBytes(side)[8] & 4) != 0);
    var earlierChain = Files.createDirectory(root.resolve("earlier-chain"));
    Files.copy(backupPath.resolve(names.getFirst()), earlierChain.resolve(names.getFirst()));
    try (var manager = crashManager(databasesPath);
        var session = manager.open(SOURCE, ADMIN, PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();
      var tracker = tracker(storage);
      assertTrue("real startup coverage proof must authorize earlier history", tracker.isTrusted());
      assertEquals(earlier.trackerId(), trackerField(tracker, "trackerId"));
      assertEquals(earlier.generationId(), trackerField(tracker, "lastCompleted"));
      org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
      assertEquals("pre-boundary and active marks must survive real startup",
          List.of(111L, 222L), trackerCandidates(tracker, "active", 123456));
      long file = storage.getWriteCache().fileIdByName("closed.tst");
      assertEquals(List.of(7L),
          trackerCandidates(tracker, "active", storage.getWriteCache().internalFileId(file)));
      measuredEvidenceBackup(storage, earlierChain, file, 128, List.of(7L));
      manager.internal.restore("earlierCrashRestore", earlierChain.toString(), null, null);
      try (var restored = manager.open("earlierCrashRestore", ADMIN, PASSWORD);
          var rows = restored.query("select count(*) as count from " + RECORD_CLASS)) {
        assertEquals(4L, ((Number) rows.next().getProperty("count")).longValue());
        var restoredStorage = (AbstractStorage) restored.getStorage();
        var cache = restoredStorage.getWriteCache();
        var entry = restoredStorage.getReadCache().silentLoadForRead(
            cache.fileIdByName("closed.tst"), 7, cache, true);
        try {
          assertEquals(7007, entry.getCachePointer().getBuffer().getInt(64));
        } finally {
          restoredStorage.getReadCache().releaseFromRead(entry);
        }
      }
      // The closed unit itself remains restorable, independently of the new earlier-head extension.
      manager.internal.restore("closedRestore", backupPath.toString(), null, null);
      try (var restored = manager.open("closedRestore", ADMIN, PASSWORD);
          var rows = restored.query("select count(*) as count from " + RECORD_CLASS)) {
        assertEquals(4L, ((Number) rows.next().getProperty("count")).longValue());
      }
      // Keep the retained newer head separate. Its pair never became saved completion.
      measuredEvidenceBackup(storage, backupPath, file, 128,
          java.util.stream.LongStream.range(0, 128).boxed().toList());
    }
  }

  /** Counts every reached evidence-file load, including unexpected duplicate and out-of-bound loads. */
  private String measuredEvidenceBackup(AbstractStorage storage, Path chain, long file, int bound,
      List<Long> expected) throws Exception {
    var real = storage.getReadCache();
    var reached = new ArrayList<Long>();
    var reads = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
        org.mockito.AdditionalAnswers.delegatesTo(real));
    org.mockito.Mockito.doAnswer(call -> {
      int page = call.getArgument(1);
      if ((long) call.getArgument(0) == file) {
        assertTrue("evidence load must stay in sampled bounds", page >= 0 && page < bound);
        reached.add((long) page);
      }
      return call.getMethod().invoke(real, call.getArguments());
    }).when(reads).silentLoadForRead(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean());
    var field = AbstractStorage.class.getDeclaredField("readCache");
    field.setAccessible(true);
    field.set(storage, reads);
    try {
      var name = storage.backup(chain);
      assertEquals("exact evidence-file selected or full loads", expected, reached);
      return name;
    } finally {
      field.set(storage, real);
    }
  }

  private static YouTrackDBImpl crashManager(Path path) {
    var config = new org.apache.commons.configuration2.BaseConfiguration();
    config.setProperty(
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(),
        false);
    config.setProperty(
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.STORAGE_CALL_FSYNC.getKey(), true);
    return (YouTrackDBImpl) YourTracks.instance(path.toString(), config);
  }

  /** Runs only in the child JVM. Every reached-site exit deliberately skips manager shutdown. */
  public static void main(String[] args) throws Exception {
    var fixture = new IncrementalBackupExtensionTest();
    var mode = args[0];
    fixture.root = Path.of(args[1]);
    fixture.databasesPath = fixture.root.resolve("databases");
    fixture.backupPath = fixture.root.resolve("backup");
    var handshake = Path.of(args[2]);
    var manager = crashManager(fixture.databasesPath);
    var storage = fixture.createSourceDatabase(manager);
    var tracker = tracker(storage);
    if (mode.equals("closed")) {
      long evidence = storage.getWriteCache().addFile("closed.tst");
      for (int page = 0; page < 128; page++) {
        writeEvidencePage(storage, evidence, page, page);
      }
      storage.fullBackup(fixture.backupPath);
      fixture.addOneRecord(manager);
      writeEvidencePage(storage, evidence, 7, 7007);
    }
    var wal = storage.getWALInstance();
    // Leave older segments available for the pending checkpoint's real bounded cut.
    wal.appendNewSegment();
    storage.synch();
    wal.appendNewSegment();
    markTracker(tracker, 123456, 111L);
    var realReads = storage.getReadCache();
    var reads = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
        org.mockito.AdditionalAnswers.delegatesTo(realReads));
    var firstRead = new java.util.concurrent.atomic.AtomicBoolean();
    var outputClosed = new java.util.concurrent.atomic.AtomicBoolean();
    replaceTrackerObserver(tracker, (method, arguments) -> {
      if (mode.equals("closed") && method.equals("beforeCompletionCapture")) {
        try {
          assertTrue("explicit close must have returned before the save site", outputClosed.get());
          // Completion is staged in memory, but no replacement side file is published yet.
          org.junit.Assert.assertNull(trackerField(tracker, "lastCompleted"));
          haltAt(handshake, mode);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      if (firstRead.compareAndSet(false, true)) {
        assertNotNull("copying must follow begin", trackerField(tracker, "sealed"));
        var seal = trackerField(tracker, "sealed");
        var id = seal.getClass().getDeclaredField("identifier");
        id.setAccessible(true);
        Files.writeString(fixture.root.resolve("consumed"), id.get(seal).toString());
        fixture.addOneRecord(manager);
        markTracker(tracker, 123456, 222L);
        wal.flush();
        if (mode.equals("pending") || mode.equals("closed")) {
          storage.synch();
          var saved = Files.readAllBytes(
              fixture.databasesPath.resolve(SOURCE).resolve("changed-pages.cpt"));
          assertTrue("ordinary checkpoint must save the pending seal", (saved[8] & 4) != 0);
          if (mode.equals("pending")) {
            haltAt(handshake, mode);
          }
          // A full checkpoint can clear the dirty flag. Commit again so this halt exercises
          // the real unclean-startup proof policy rather than clean-shutdown validation.
          fixture.addOneRecord(manager);
          wal.flush();
        }
      }
      return invocation.getMethod().invoke(realReads, invocation.getArguments());
    }).when(reads).silentLoadForRead(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq(true));
    var readField = AbstractStorage.class.getDeclaredField("readCache");
    readField.setAccessible(true);
    readField.set(storage, reads);
    var records = new CopyOnWriteArrayList<LogRecord>() {
      @Override
      public boolean add(LogRecord record) {
        if (mode.equals("published") && record.getMessage().contains("is completed. Backup unit")) {
          try {
            assertTrue(firstRead.get());
            haltAt(handshake, mode);
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        }
        return super.add(record);
      }
    };
    fixture.withDiskStorageLogs(records, () -> storage.backup(
        fixture::unitIterator, fixture::openInput, name -> {
          try {
            return new CrashOutput(fixture.backupPath.resolve(name), () -> {
              assertTrue(firstRead.get());
              if (mode.equals("published")) {
                try {
                  fixture.addOneRecord(manager);
                  markTracker(tracker, 123456, 333L);
                  wal.flush();
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              }
              outputClosed.set(true);
            });
          } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        }, name -> {
          throw new AssertionError("fresh backup must not remove a unit");
        }));
    throw new AssertionError("child did not reach " + mode);
  }

  private static void haltAt(Path handshake, String mode) throws Exception {
    Files.writeString(handshake, mode, StandardOpenOption.CREATE_NEW);
    try (var channel = FileChannel.open(handshake, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    Runtime.getRuntime().halt(0);
  }

  /** Real file output with the same explicit force capability as a local backup unit. */
  private static final class CrashOutput extends OutputStream implements BackupForceCapability {
    private final FileChannel channel;
    private final OutputStream stream;
    private final Runnable afterClose;

    private CrashOutput(Path path, Runnable afterClose) throws IOException {
      channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      stream = java.nio.channels.Channels.newOutputStream(channel);
      this.afterClose = afterClose;
    }

    @Override
    public void write(int value) throws IOException {
      stream.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      stream.write(bytes, offset, length);
    }

    @Override
    public void forceBackupData() throws IOException {
      channel.force(true);
    }

    @Override
    public void close() throws IOException {
      stream.close();
      afterClose.run();
    }
  }

  private static void saveTrackerAt(Object tracker, Path target,
      com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber coverage)
      throws Exception {
    var codec = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile");
    var save = codec.getDeclaredMethod("save", Path.class, tracker.getClass(), coverage.getClass());
    save.setAccessible(true);
    assertEquals("SAVED", save.invoke(null, target, tracker, coverage).toString());
  }

  private Object saveAndMergeLoadedTracker(Object tracker) throws Exception {
    var codec = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile");
    var trackerType = tracker.getClass();
    var lsnType =
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber.class;
    var save = codec.getDeclaredMethod("save", Path.class, trackerType, lsnType);
    save.setAccessible(true);
    var target = Files.createTempDirectory(root, "partial-merge-").resolve("history");
    assertEquals("SAVED", save.invoke(null, target, tracker,
        new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber(
            17, 32))
        .toString());
    var load = codec.getDeclaredMethod("load", Path.class);
    load.setAccessible(true);
    var loaded = load.invoke(null, target);
    var accessor = loaded.getClass().getDeclaredMethod("tracker");
    accessor.setAccessible(true);
    var restarted = accessor.invoke(loaded);
    assertNotNull(trackerField(restarted, "sealed"));
    var merge = trackerType.getDeclaredMethod("mergeLoadedSealed");
    merge.setAccessible(true);
    merge.invoke(restarted);
    return restarted;
  }

  private static void assertBackupUnlocked(AbstractStorage storage) throws Exception {
    var field = AbstractStorage.class.getDeclaredField("backupLock");
    field.setAccessible(true);
    assertFalse(((java.util.concurrent.locks.ReentrantLock) field.get(storage)).isLocked());
  }

  private static void markTracker(Object tracker, int file, long page) throws Exception {
    var method = tracker.getClass().getDeclaredMethod("mark", int.class, long.class);
    method.setAccessible(true);
    method.invoke(tracker, file, page);
  }

  private static List<Long> trackerCandidates(Object tracker, String generation, int file)
      throws Exception {
    var active = trackerField(tracker, generation);
    var method = active.getClass().getDeclaredMethod("forEachCandidate", int.class,
        java.util.function.LongConsumer.class);
    method.setAccessible(true);
    var pages = new ArrayList<Long>();
    method.invoke(active, file, (java.util.function.LongConsumer) pages::add);
    return pages;
  }

  private interface TrackerObservation {
    void visit(String method, Object[] args);
  }

  private static Object replaceTrackerObserver(Object tracker, TrackerObservation observation)
      throws Exception {
    var field = tracker.getClass().getDeclaredField("allocations");
    field.setAccessible(true);
    var previous = field.get(tracker);
    var type = field.getType();
    field.set(tracker, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
        new Class<?>[] {type}, (proxy, method, args) -> {
          observation.visit(method.getName(), args);
          return null;
        }));
    return previous;
  }

  private static void restoreTrackerObserver(Object tracker, Object previous) throws Exception {
    var field = tracker.getClass().getDeclaredField("allocations");
    field.setAccessible(true);
    field.set(tracker, previous);
  }

  /** Matching history selects measured marks. Every other admitted head reads physical bounds. */
  @Test
  public void everyHeadCaseUsesItsMeasuredReadMode() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var full = storage.fullBackup(backupPath);
      var pair = literalContinuity(Files.readAllBytes(backupPath.resolve(full)));
      for (var head : List.of("matching", "qfOlder", "trackerMismatch", "generationMismatch",
          "mismatched", "historical", "missing", "untrusted")) {
        var bytes = Files.readAllBytes(backupPath.resolve(full));
        if (head.equals("mismatched")) {
          BackupUnitFiles.rewriteAsVersion6(backupPath.resolve(full), DiskStorage.BARRIER_PRESENT,
              new DiskStorage.BackupContinuity(UUID.randomUUID(), UUID.randomUUID()));
        } else if (head.equals("trackerMismatch") || head.equals("generationMismatch")) {
          BackupUnitFiles.rewriteAsVersion6(backupPath.resolve(full), DiskStorage.BARRIER_PRESENT,
              new DiskStorage.BackupContinuity(
                  head.equals("trackerMismatch") ? UUID.randomUUID() : pair.trackerId(),
                  head.equals("generationMismatch") ? UUID.randomUUID() : pair.generationId()));
        } else if (head.equals("historical")) {
          BackupUnitFiles.rewriteAsVersion(backupPath.resolve(full), 3);
        } else if (head.equals("untrusted")) {
          var invalidate = tracker(storage).getClass().getDeclaredMethod("invalidate");
          invalidate.setAccessible(true);
          invalidate.invoke(tracker(storage));
        }
        boolean selected = head.equals("matching") || head.equals("qfOlder");
        var real = storage.getReadCache();
        var reads = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(real));
        var field = AbstractStorage.class.getDeclaredField("readCache");
        field.setAccessible(true);
        field.set(storage, reads);
        try {
          var output = new RecordingOutput();
          storage.backup(head.equals("missing") ? () -> List.<String>of().iterator()
              : this::unitIterator, this::openInput,
              file -> new java.io.FilterOutputStream(output) {
              }, file -> {
              });
          var cache = storage.getWriteCache();
          int reached = 0;
          for (var id : cache.files().values()) {
            var external = cache.externalFileId(cache.internalFileId(id));
            var size = cache.physicalSizeForBackupSnapshot(external);
            for (int page = 0; page < size; page++) {
              org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(
                  selected ? 0 : 1))
                  .silentLoadForRead(external, page, cache, true);
              reached++;
            }
          }
          assertTrue(head, reached > 0);
        } finally {
          field.set(storage, real);
          Files.write(backupPath.resolve(full), bytes);
        }
        // Q-f keeps the pair. The older full head still matches after that completion.
        // All mismatching, absent and untrusted cases must reach every sampled physical page.
      }
    }
  }

  /**
   * Real durable bootstrap enables measured selected reads. Inventory-time trust loss falls back,
   * while post-selection loss (including an empty stream) fails before WAL copy and metadata.
   * Non-durable files retain the full walk. Completion facts record the fixed mode exactly.
   */
  @Test
  public void selectedCallerUsesFixedModeAndBothTrustChecks() throws Exception {
    for (var mode : List.of("selected", "ineligible", "before", "after", "empty", "nonDurable",
        "overflow")) {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(
          databasesPath.resolve("selection-" + mode).toString())) {
        var storage = createSourceDatabase(manager);
        var full = storage.fullBackup(backupPath);
        var tracker = tracker(storage);
        assertTrue(tracker.isTrusted());
        var realCache = storage.getWriteCache();
        var inventory = realCache.files();
        var id = inventory.values().stream()
            .filter(value -> realCache.physicalSizeForBackupSnapshot(value) > 0)
            .findFirst().orElseThrow();
        long external = realCache.externalFileId(realCache.internalFileId(id));
        long marked = mode.equals("overflow") ? (long) Integer.MAX_VALUE + 1 : 0;
        if (!mode.equals("empty") && !mode.equals("nonDurable")) {
          markTracker(tracker, realCache.internalFileId(external), marked);
        }
        if (mode.equals("ineligible")) {
          BackupUnitFiles.rewriteAsVersion6(backupPath.resolve(full), DiskStorage.BARRIER_PRESENT,
              new DiskStorage.BackupContinuity(UUID.randomUUID(), UUID.randomUUID()));
        }
        var cache = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(realCache));
        var invalidate = tracker.getClass().getDeclaredMethod("invalidate");
        invalidate.setAccessible(true);
        org.mockito.Mockito.doAnswer(call -> {
          var snapshot = realCache.files();
          if (mode.equals("before")) {
            invalidate.invoke(tracker);
          }
          return snapshot;
        }).when(cache).files();
        var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
          long fileId = call.getArgument(0);
          if ((mode.equals("after") || mode.equals("empty"))
              && invalidated.compareAndSet(false, true)) {
            invalidate.invoke(tracker);
          }
          return mode.equals("overflow") && fileId == external ? marked + 1
              : realCache.physicalSizeForBackupSnapshot(fileId);
        }).when(cache).physicalSizeForBackupSnapshot(org.mockito.ArgumentMatchers.anyLong());
        if (mode.equals("nonDurable")) {
          org.mockito.Mockito.doReturn(true).when(cache).isNonDurable(external);
        }
        var realReads = storage.getReadCache();
        var reads = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(realReads));
        var realWal = storage.getWALInstance();
        var wal = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
            org.mockito.AdditionalAnswers.delegatesTo(realWal));
        var cacheField = AbstractStorage.class.getDeclaredField("writeCache");
        var readField = AbstractStorage.class.getDeclaredField("readCache");
        var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
        for (var field : List.of(cacheField, readField, walField)) {
          field.setAccessible(true);
        }
        cacheField.set(storage, cache);
        readField.set(storage, reads);
        walField.set(storage, wal);
        var factsArgs = new ArrayList<List<?>>();
        var output = new RecordingOutput();
        boolean fullRead = mode.equals("before") || mode.equals("ineligible");
        boolean fails = mode.equals("after") || mode.equals("empty") || mode.equals("overflow");
        var factsType =
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.CompletionFacts.class;
        try (var facts = org.mockito.Mockito.mockConstruction(factsType, (mock, construction) -> {
          factsArgs.add(List.copyOf(construction.arguments()));
          org.mockito.Mockito.when(mock.fullRead()).thenReturn(fullRead);
          org.mockito.Mockito.when(mock.completeUnitDurable()).thenReturn(true);
        })) {
          java.util.concurrent.Callable<String> backup = () -> storage.backup(this::unitIterator,
              this::openInput, file -> output, file -> {
                throw new AssertionError("no removal");
              });
          if (fails) {
            var reported = assertThrows(DatabaseException.class, backup::call);
            assertTrue(reported.getCause() instanceof IOException);
            var failure = reported.getCause();
            assertTrue(failure.getMessage(), failure.getMessage().contains(
                mode.equals("overflow") ? "Backup page index outside read-cache range"
                    : "trust lost"));
            assertEquals(0, output.tailRequests);
            assertTrue(factsArgs.isEmpty());
            org.mockito.Mockito.verify(wal, org.mockito.Mockito.never())
                .nonActiveSegments(org.mockito.ArgumentMatchers.anyLong());
            org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
            if (!mode.equals("empty")) {
              assertTrue(trackerCandidates(tracker, "active", realCache.internalFileId(external))
                  .contains(marked));
            }
          } else {
            try {
              backup.call();
            } catch (Exception failure) {
              throw new AssertionError(mode + " must complete with its fixed read mode", failure);
            }
            assertEquals(mode, List.of(List.of(fullRead, true)), factsArgs);
            // A failure after the boundary also prevents completion from trusting that boundary.
            assertEquals(mode, !mode.equals("before"), tracker.isTrusted());
            assertEquals(1, output.tailRequests);
          }
          int physical = 0;
          for (var file : inventory.values()) {
            long composite = realCache.externalFileId(realCache.internalFileId(file));
            long bound = realCache.physicalSizeForBackupSnapshot(composite);
            for (int page = 0; page < bound; page++) {
              boolean expected = fullRead || (!mode.equals("empty") && composite == external
                  && (page == 0 || mode.equals("nonDurable")));
              if (mode.equals("overflow")) {
                expected = false;
              }
              org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(expected ? 1 : 0))
                  .silentLoadForRead(composite, page, cache, true);
              physical++;
            }
          }
          assertTrue(physical > 1);
        } finally {
          cacheField.set(storage, realCache);
          readField.set(storage, realReads);
          walField.set(storage, realWal);
          Files.deleteIfExists(backupPath.resolve(full));
        }
      }
    }
  }

  /**
   * A real completed full backup leaves 128 independent pages untouched. Two new pages and one
   * conservative old-LSN mark produce three loads but only two copied pages. Empty and unmarked
   * files remain inventory entries. Restore checks both the independent bytes and logical records.
   */
  @Test
  public void selectedSubsetSeparatesCandidatesLoadsCopiesAndRestoredState() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var cache = storage.getWriteCache();
      long file = cache.addFile("subset.tst");
      long empty = cache.addFile("empty.tst");
      long removed = cache.addFile("removed.tst");
      long truncated = cache.addFile("truncated.tst");
      writeEvidencePage(storage, truncated, 7, 77);
      for (int page = 0; page < 128; page++) {
        writeEvidencePage(storage, file, page, page);
      }
      storage.fullBackup(backupPath);
      addOneRecord(manager);
      writeEvidencePage(storage, file, 7, 7007);
      writeEvidencePage(storage, file, 93, 9093);
      storage.getReadCache().deleteFile(removed, cache);
      storage.getReadCache().shrinkFile(truncated, 0, cache);
      writeEvidencePage(storage, truncated, 3, 33);
      var tracker = tracker(storage);
      markTracker(tracker, cache.internalFileId(file), 11);
      // An out-of-bounds conservative bit must never reach the read cache.
      markTracker(tracker, cache.internalFileId(file), 128);
      markTracker(tracker, cache.internalFileId(removed), 99);
      markTracker(tracker, cache.internalFileId(truncated), 7);
      assertEquals(List.of(7L, 11L, 93L, 128L),
          trackerCandidates(tracker, "active", cache.internalFileId(file)));
      assertTrue(trackerCandidates(tracker, "active", cache.internalFileId(empty)).isEmpty());
      var real = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(real));
      org.mockito.Mockito.doAnswer(call -> {
        long id = call.getArgument(0);
        int page = call.getArgument(1);
        assertTrue("deleted file must never be loaded", id != removed);
        assertTrue("subset load must stay below sampled bound 128", id != file || page < 128);
        assertTrue("truncated load must stay below sampled bound 4",
            id != truncated || page < 4);
        return real.silentLoadForRead(id, page, call.getArgument(2), call.getArgument(3));
      }).when(reads).silentLoadForRead(org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyBoolean());
      var field = AbstractStorage.class.getDeclaredField("readCache");
      field.setAccessible(true);
      field.set(storage, reads);
      String increment;
      var visits = new ArrayList<Long>();
      var facade =
          com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.class;
      try (var bridge = org.mockito.Mockito.mockStatic(facade,
          org.mockito.Mockito.CALLS_REAL_METHODS)) {
        bridge.when(
            () -> com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup
                .begin(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenAnswer(call -> {
              var context =
                  (com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Context) call
                      .callRealMethod();
              var observed = org.mockito.Mockito.spy(context);
              org.mockito.Mockito.doAnswer(stream -> {
                com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.CandidateConsumer consumer =
                    stream.getArgument(3);
                int id = stream.getArgument(1);
                context.forEachCandidate(stream.getArgument(0), id, stream.getArgument(2),
                    page -> {
                      if (id == cache.internalFileId(file)) {
                        assertTrue("candidate must stay below sampled bound 128", page < 128);
                        visits.add(page);
                      }
                      consumer.accept(page);
                    });
                return null;
              }).when(observed).forEachCandidate(org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                  org.mockito.ArgumentMatchers.any());
              return observed;
            });
        increment = storage.backup(backupPath);
      } finally {
        field.set(storage, real);
      }
      assertEquals("caller candidate visits precede the independent load and copy oracles",
          List.of(7L, 11L, 93L), visits);
      for (int page = 0; page <= 128; page++) {
        org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(
            page == 7 || page == 11 || page == 93 ? 1 : 0))
            .silentLoadForRead(file, page, cache, true);
      }
      var entries = backupEntries(Files.readAllBytes(backupPath.resolve(increment)));
      assertEquals(8, entries.get("empty.tst").length);
      assertFalse(entries.containsKey("removed.tst"));
      assertTrue(entries.containsKey("subset.tst"));
      assertEquals(8 + 8 + cache.pageSize(), entries.get("truncated.tst").length);
      org.mockito.Mockito.verify(reads, org.mockito.Mockito.never())
          .silentLoadForRead(truncated, 7, cache, true);
      var copied = entries.get("subset.tst");
      assertEquals(8 + 2 * (8 + cache.pageSize()), copied.length);
      var serializer =
          com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer.INSTANCE;
      assertEquals(7, serializer.deserializeNative(copied, 8));
      assertEquals(93,
          serializer.deserializeNative(copied, 8 + 8 + cache.pageSize()));
      manager.internal.restore("subsetRestore", backupPath.toString(), null, null);
      try (var session = manager.open("subsetRestore", ADMIN, PASSWORD)) {
        assertEquals(2, session.query("select from " + RECORD_CLASS).stream().count());
      }
      var restored = ((YouTrackDBInternalEmbedded) manager.internal).getStorage("subsetRestore");
      var restoredCache = restored.getWriteCache();
      long restoredFile = restoredCache.fileIdByName("subset.tst");
      for (int page = 0; page < 128; page++) {
        var entry = restored.getReadCache().silentLoadForRead(restoredFile, page,
            restoredCache, true);
        long stamp = entry.acquireSharedLock();
        try {
          assertEquals(page == 7 ? 7007 : page == 93 ? 9093 : page,
              entry.getCachePointer().getBuffer().getInt(64));
        } finally {
          entry.releaseSharedLock(stamp);
          restored.getReadCache().releaseFromRead(entry);
        }
      }
    }
  }

  /**
   * Same-ID rename changes only the current inventory name. Stale marks cannot create entries
   * or loads outside that inventory and its sampled bound.
   */
  @Test
  public void renamedInventorySelectsOnlyValidCandidatesWithoutGhosts() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var cache = storage.getWriteCache();
      long file = cache.addFile("original.tst");
      writeEvidencePage(storage, file, 0, 10);
      var full = storage.fullBackup(backupPath);
      cache.renameFile(file, "renamed.tst");
      markTracker(tracker(storage), cache.internalFileId(file), 0);
      markTracker(tracker(storage), cache.internalFileId(file), 1);
      var real = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(real));
      var field = AbstractStorage.class.getDeclaredField("readCache");
      field.setAccessible(true);
      field.set(storage, reads);
      String increment;
      try {
        increment = storage.backup(backupPath);
      } finally {
        field.set(storage, real);
      }
      var before = backupEntries(Files.readAllBytes(backupPath.resolve(full)));
      var after = backupEntries(Files.readAllBytes(backupPath.resolve(increment)));
      assertFalse(after.containsKey("original.tst"));
      assertTrue(after.containsKey("renamed.tst"));
      org.junit.Assert.assertArrayEquals(
          java.util.Arrays.copyOf(before.get("original.tst"), 8),
          java.util.Arrays.copyOf(after.get("renamed.tst"), 8));
      org.mockito.Mockito.verify(reads).silentLoadForRead(file, 0, cache, true);
      org.mockito.Mockito.verify(reads, org.mockito.Mockito.never())
          .silentLoadForRead(file, 1, cache, true);
      // Restore of a same-ID rename across units is a known pre-existing limitation outside this test.
    }
  }

  /** A writer extends after the sampled bound. Only WAL restores the excluded new page. */
  @Test
  public void extensionAfterSampleRestoresThroughWalWithoutLoadingNewPage() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var cache = storage.getWriteCache();
      String fileName;
      try (var session = manager.open(SOURCE, ADMIN, PASSWORD)) {
        int collection;
        try (var rows = session.query("select from " + RECORD_CLASS)) {
          collection = rows.next().asEntity().getIdentity().getCollectionId();
        }
        fileName = storage.getCollectionNameById(collection) + ".pcl";
      }
      long file = cache.fileIdByName(fileName);
      long sampledBound = cache.physicalSizeForBackupSnapshot(file);
      var bytes = new byte[512 * 1024];
      new java.util.Random(42).nextBytes(bytes);
      var payload = java.util.Base64.getEncoder().encodeToString(bytes);
      long evidence = cache.addFile("lifecycle.tst");
      for (int page = 0; page < 128; page++) {
        writeEvidencePage(storage, evidence, page, page);
      }
      storage.fullBackup(backupPath);
      writeEvidencePage(storage, evidence, 7, 7007);
      var realReads = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(realReads));
      var proxy = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(cache));
      var reached = new java.util.concurrent.atomic.AtomicBoolean();
      var writer = java.util.concurrent.Executors.newSingleThreadExecutor();
      org.mockito.Mockito.doAnswer(call -> {
        long id = call.getArgument(0);
        long bound = cache.physicalSizeForBackupSnapshot(id);
        if (id == file && reached.compareAndSet(false, true)) {
          assertEquals(sampledBound, bound);
          var seal = trackerField(tracker(storage), "sealed");
          var pages = seal.getClass().getDeclaredMethod("pages");
          pages.setAccessible(true);
          assertEquals(List.of(7L), generationCandidates(pages.invoke(seal),
              cache.internalFileId(evidence)));
          writer.submit(() -> {
            try (var session = manager.open(SOURCE, ADMIN, PASSWORD)) {
              session.begin();
              session.command("update " + RECORD_CLASS + " set value = ?", payload);
              session.commit();
            }
            return null;
          }).get(30, java.util.concurrent.TimeUnit.SECONDS);
          assertTrue("writer must physically extend the sampled file",
              cache.physicalSizeForBackupSnapshot(file) > bound);
          assertFalse("resumed commits must mark active",
              trackerCandidates(tracker(storage), "active", cache.internalFileId(file)).isEmpty());
        }
        return bound;
      }).when(proxy).physicalSizeForBackupSnapshot(org.mockito.ArgumentMatchers.anyLong());
      var cacheField = AbstractStorage.class.getDeclaredField("writeCache");
      var readField = AbstractStorage.class.getDeclaredField("readCache");
      cacheField.setAccessible(true);
      readField.setAccessible(true);
      cacheField.set(storage, proxy);
      readField.set(storage, reads);
      String increment;
      try {
        increment = storage.backup(backupPath);
      } finally {
        cacheField.set(storage, cache);
        readField.set(storage, realReads);
        writer.shutdownNow();
        assertTrue(writer.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
      }
      assertTrue("extension must follow the real physical sample", reached.get());
      for (long page = sampledBound; page < cache.physicalSizeForBackupSnapshot(file); page++) {
        org.mockito.Mockito.verify(reads, org.mockito.Mockito.never())
            .silentLoadForRead(file, (int) page, proxy, true);
      }
      assertEquals("new pages are absent from the page stream", 8,
          backupEntries(Files.readAllBytes(backupPath.resolve(increment))).get(fileName).length);
      org.mockito.Mockito.verify(reads).silentLoadForRead(evidence, 7, proxy, true);
      org.mockito.Mockito.verify(reads, org.mockito.Mockito.never())
          .silentLoadForRead(evidence, 93, proxy, true);
      manager.internal.restore("growthRestore", backupPath.toString(), null, null);
      try (var session = manager.open("growthRestore", ADMIN, PASSWORD)) {
        var values = session.query("select value from " + RECORD_CLASS).stream()
            .map(result -> result.<String>getProperty("value")).toList();
        assertEquals(1, values.size());
        assertTrue("WAL must restore the record on excluded extension pages",
            values.contains(payload));
      }
      writeEvidencePage(storage, evidence, 93, 9093);
      measuredEvidenceBackup(storage, backupPath, evidence, 128, List.of(93L));
    }
  }

  /** Real alternative-chain and older-head pairs must fall back at every sampled address. */
  @Test
  public void realOtherChainAndOlderHeadMismatchReadEveryPhysicalPage() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var older = storage.fullBackup(backupPath);
      var otherPath = Files.createDirectories(root.resolve("other-chain"));
      var other = storage.fullBackup(otherPath);
      var oldPair = literalContinuity(Files.readAllBytes(backupPath.resolve(older)));
      var otherPair = literalContinuity(Files.readAllBytes(otherPath.resolve(other)));
      assertFalse(oldPair.generationId().equals(otherPair.generationId()));
      for (boolean otherChain : new boolean[] {false, true}) {
        var selectedPath = otherChain ? otherPath : backupPath;
        // Advance the saved pair beyond either independently retained head.
        storage.fullBackup(Files.createDirectories(root.resolve("advance-" + otherChain)));
        var real = storage.getReadCache();
        var reads = org.mockito.Mockito.mock(
            com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
            org.mockito.AdditionalAnswers.delegatesTo(real));
        var field = AbstractStorage.class.getDeclaredField("readCache");
        field.setAccessible(true);
        field.set(storage, reads);
        try {
          storage.backup(selectedPath);
        } finally {
          field.set(storage, real);
        }
        int reached = 0;
        var cache = storage.getWriteCache();
        for (long file : cache.files().values()) {
          for (int page = 0; page < cache.physicalSizeForBackupSnapshot(file); page++) {
            org.mockito.Mockito.verify(reads).silentLoadForRead(file, page, cache, true);
            reached++;
          }
        }
        assertTrue("real mismatching head must reach physical bounds", reached > 0);
      }
    }
  }

  /**
   * Unknown durable content adopted after unfreeze but before inventory forces the entire request
   * full. Its page LSN lies after the head and before this unit's end, so restore needs that copy.
   */
  @Test
  public void adoptionBeforeInventoryReadsAllPagesAndRestoresAdoptedContent() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var head = storage.fullBackup(backupPath);
      var headEnd = inspectUnit(head, storage.getUuid()).metadata().endLsn();
      addOneRecord(manager);
      var adoptedLsn = storage.getWALInstance().end();
      assertTrue("adopted page must follow the head", adoptedLsn.compareTo(headEnd) > 0);
      var cache = storage.getWriteCache();
      long file = cache.addFile("adopted.tst");
      writeEvidencePage(storage, file, 0, 5678);
      cache.flush();
      var physical = databasesPath.resolve(SOURCE)
          .resolve("adopted_" + cache.internalFileId(file) + ".tst");
      byte[] saved = Files.readAllBytes(physical);
      storage.getReadCache().deleteFile(file, cache);
      Files.write(physical, saved);
      var proxy = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(cache));
      var adopted = new java.util.concurrent.atomic.AtomicBoolean();
      org.mockito.Mockito.doAnswer(call -> {
        if (adopted.compareAndSet(false, true)) {
          assertEquals(file, cache.loadFile("adopted.tst"));
        }
        return cache.files();
      }).when(proxy).files();
      var realReads = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(realReads));
      var cacheField = AbstractStorage.class.getDeclaredField("writeCache");
      var readField = AbstractStorage.class.getDeclaredField("readCache");
      cacheField.setAccessible(true);
      readField.setAccessible(true);
      cacheField.set(storage, proxy);
      readField.set(storage, reads);
      var arguments = new ArrayList<List<?>>();
      var factsType =
          com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.CompletionFacts.class;
      try (var facts = org.mockito.Mockito.mockConstruction(factsType, (mock, context) -> {
        arguments.add(List.copyOf(context.arguments()));
        org.mockito.Mockito.when(mock.fullRead()).thenReturn(true);
        org.mockito.Mockito.when(mock.completeUnitDurable()).thenReturn(true);
      })) {
        String increment;
        try {
          increment = storage.backup(backupPath);
        } catch (Exception failure) {
          throw new AssertionError("adoption before inventory must complete in full mode", failure);
        }
        var end = inspectUnit(increment, storage.getUuid()).metadata().endLsn();
        assertTrue("unit end must cover adopted page LSN", adoptedLsn.compareTo(end) <= 0);
        assertTrue(adopted.get());
        assertEquals(List.of(List.of(true, true)), arguments);
        for (var id : cache.files().values()) {
          for (int page = 0; page < cache.physicalSizeForBackupSnapshot(id); page++) {
            org.mockito.Mockito.verify(reads).silentLoadForRead(id, page, proxy, true);
          }
        }
      } finally {
        cacheField.set(storage, cache);
        readField.set(storage, realReads);
      }
      manager.internal.restore("adoptedRestore", backupPath.toString(), null, null);
      var restored = ((YouTrackDBInternalEmbedded) manager.internal).getStorage("adoptedRestore");
      var restoredCache = restored.getWriteCache();
      var entry = restored.getReadCache().silentLoadForRead(
          restoredCache.fileIdByName("adopted.tst"), 0, restoredCache, true);
      long stamp = entry.acquireSharedLock();
      try {
        assertEquals(5678, entry.getCachePointer().getBuffer().getInt(64));
      } finally {
        entry.releaseSharedLock(stamp);
        restored.getReadCache().releaseFromRead(entry);
      }
    }
  }

  /**
   * Non-durable writes have no marks but still load every page. An unchanged old LSN filters their
   * changed bytes out, so restore retains the earlier bytes. This asserts the existing limitation.
   */
  @Test
  public void nonDurableFullWalkDoesNotRepairOldLsnRestoreLimitation() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      var cache = storage.getWriteCache();
      long file = cache.addFile("volatile.tst", cache.bookFileId("volatile.tst"), true);
      for (int page = 0; page < 4; page++) {
        writeEvidencePage(storage, file, page, 10);
      }
      storage.fullBackup(backupPath);
      var oldLsn = storage.getWALInstance().end();
      for (int page = 0; page < 4; page++) {
        var entry = storage.getReadCache().loadOrAddForWrite(file, page, cache, false, null);
        try {
          entry.getCachePointer().getBuffer().putInt(64, 20);
        } finally {
          storage.getReadCache().releaseFromWrite(entry, cache, true);
        }
      }
      assertEquals(oldLsn, storage.getWALInstance().end());
      assertTrue(
          trackerCandidates(tracker(storage), "active", cache.internalFileId(file)).isEmpty());
      var real = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(real));
      var field = AbstractStorage.class.getDeclaredField("readCache");
      field.setAccessible(true);
      field.set(storage, reads);
      String increment;
      try {
        increment = storage.backup(backupPath);
      } finally {
        field.set(storage, real);
      }
      for (int page = 0; page < 4; page++) {
        org.mockito.Mockito.verify(reads).silentLoadForRead(file, page, cache, true);
      }
      assertEquals(8,
          backupEntries(Files.readAllBytes(backupPath.resolve(increment)))
              .get("volatile.tst").length);
      manager.internal.restore("nonDurableRestore", backupPath.toString(), null, null);
      var restored =
          ((YouTrackDBInternalEmbedded) manager.internal).getStorage("nonDurableRestore");
      var restoredCache = restored.getWriteCache();
      for (int page = 0; page < 4; page++) {
        var entry = restored.getReadCache().silentLoadForRead(
            restoredCache.fileIdByName("volatile.tst"), page, restoredCache, true);
        long stamp = entry.acquireSharedLock();
        try {
          assertEquals(10, entry.getCachePointer().getBuffer().getInt(64));
        } finally {
          entry.releaseSharedLock(stamp);
          restored.getReadCache().releaseFromRead(entry);
        }
      }
    }
  }

  /** Write independent fixture bytes through the real cache and stamp the current real WAL end. */
  private static void writeEvidencePage(AbstractStorage storage, long file, int page, int value)
      throws Exception {
    var cache = storage.getWriteCache();
    var lsn = storage.getWALInstance().end();
    var entry = storage.getReadCache().loadOrAddForWrite(file, page, cache, false, lsn);
    try {
      var buffer = entry.getCachePointer().getBuffer();
      buffer.putInt(64, value);
      com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage
          .setLogSequenceNumberForPage(buffer, lsn);
    } finally {
      storage.getReadCache().releaseFromWrite(entry, cache, true);
    }
  }

  /** Decode ZIP entries independently of the backup page selection path. */
  private static Map<String, byte[]> backupEntries(byte[] unit) throws IOException {
    var entries = new HashMap<String, byte[]>();
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(unit))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.put(entry.getName(), zip.readAllBytes());
      }
    }
    return entries;
  }

  /**
   * Tombstone adoption after selection revokes trust even when no candidate is streamed. Both
   * capable and custom outputs retain a tail-less unit. Admission refuses it until the operator
   * explicitly removes it. Adoption after the inventory snapshot is absent from the unit, as in
   * the current full-read path. This pre-existing limitation is not repaired here.
   */
  @Test
  public void reboundContentFailsBeforeWalAndTailEvenWithoutCandidates() throws Exception {
    for (boolean capable : new boolean[] {true, false}) {
      for (boolean marked : new boolean[] {false, true}) {
        try (var manager = (YouTrackDBImpl) YourTracks.instance(
            databasesPath.resolve("rebind-" + capable + "-" + marked).toString())) {
          var storage = createSourceDatabase(manager);
          var cache = storage.getWriteCache();
          long file = cache.addFile("rebound.tst");
          writeEvidencePage(storage, file, 0, 42);
          cache.flush();
          var physical = databasesPath.resolve("rebind-" + capable + "-" + marked)
              .resolve(SOURCE).resolve("rebound_" + cache.internalFileId(file) + ".tst");
          byte[] saved = Files.readAllBytes(physical);
          var full = storage.fullBackup(backupPath);
          var tracker = tracker(storage);
          if (marked) {
            markTracker(tracker, cache.internalFileId(file), 0);
            markTracker(tracker, 123456, 99);
          }
          if (!marked) {
            for (long id : cache.files().values()) {
              assertTrue("zero-candidate request must begin without any marks",
                  trackerCandidates(tracker, "active", cache.internalFileId(id)).isEmpty());
            }
          }
          var proxy = org.mockito.Mockito.mock(
              com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
              org.mockito.AdditionalAnswers.delegatesTo(cache));
          var reached = new java.util.concurrent.atomic.AtomicBoolean();
          org.mockito.Mockito.doAnswer(call -> {
            long id = call.getArgument(0);
            long bound = cache.physicalSizeForBackupSnapshot(id);
            if (id == file && reached.compareAndSet(false, true)) {
              storage.getReadCache().deleteFile(file, cache);
              Files.write(physical, saved);
              assertEquals(file, cache.loadFile("rebound.tst"));
            }
            return bound;
          }).when(proxy).physicalSizeForBackupSnapshot(org.mockito.ArgumentMatchers.anyLong());
          var realWal = storage.getWALInstance();
          var wal = org.mockito.Mockito.mock(
              com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.class,
              org.mockito.AdditionalAnswers.delegatesTo(realWal));
          var cacheField = AbstractStorage.class.getDeclaredField("writeCache");
          var walField = AbstractStorage.class.getDeclaredField("writeAheadLog");
          cacheField.setAccessible(true);
          walField.setAccessible(true);
          cacheField.set(storage, proxy);
          walField.set(storage, wal);
          var output = new RecordingOutput();
          var names = new ArrayList<String>();
          try {
            var failure = assertThrows(DatabaseException.class, () -> storage.backup(
                this::unitIterator, this::openInput, name -> {
                  names.add(name);
                  return capable ? output : new java.io.FilterOutputStream(output) {
                  };
                }, name -> {
                  throw new AssertionError("no automatic removal");
                }));
            assertTrue(failure.getCause().getMessage().contains("trust lost"));
            assertTrue(reached.get());
            assertEquals(0, output.tailRequests);
            org.mockito.Mockito.verify(wal, org.mockito.Mockito.never())
                .nonActiveSegments(org.mockito.ArgumentMatchers.anyLong());
            org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
            if (marked) {
              assertEquals(List.of(99L), trackerCandidates(tracker, "active", 123456));
            }
          } finally {
            cacheField.set(storage, cache);
            walField.set(storage, realWal);
          }
          var failed = backupPath.resolve(names.getFirst());
          Files.write(failed, output.bytes.toByteArray());
          var before = unitContent();
          assertThrows(DatabaseException.class, () -> storage.backup(backupPath));
          assertThrows(UnsupportedBackupException.class, () -> manager.internal.restore(
              "refusedRebind", backupPath.toString(), null, null));
          assertEquals(before, unitContent());
          // Explicit operator resolution of the ambiguous output, not automatic chain repair.
          Files.delete(failed);
          storage.backup(backupPath);
          for (var name : unitNames()) {
            Files.delete(backupPath.resolve(name));
          }
          assertFalse(Files.exists(backupPath.resolve(full)));
        }
      }
    }
  }

  /** Allocation failure after selection fails before the tail and requires operator resolution. */
  @Test
  public void markAllocationFailureBetweenChecksRetainsUntailedOutput() throws Exception {
    try (var manager = openManager()) {
      var storage = createSourceDatabase(manager);
      storage.fullBackup(backupPath);
      var tracker = tracker(storage);
      markTracker(tracker, 123456, 99);
      var cache = storage.getWriteCache();
      var proxy = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(cache));
      var reached = new java.util.concurrent.atomic.AtomicBoolean();
      var primary = new OutOfMemoryError("injected mark allocation");
      var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
      var previous = replaceTrackerObserver(tracker, (method, args) -> {
        if (method.equals("beforeAllocation") && args[0].toString().equals("FILE")
            && armed.compareAndSet(true, false)) {
          throw primary;
        }
      });
      org.mockito.Mockito.doAnswer(call -> {
        long bound = cache.physicalSizeForBackupSnapshot(call.getArgument(0));
        if (reached.compareAndSet(false, true)) {
          var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
              () -> markTracker(tracker, 234567, 33));
          assertSame(primary, failure.getCause());
          restoreTrackerObserver(tracker, previous);
        }
        return bound;
      }).when(proxy).physicalSizeForBackupSnapshot(org.mockito.ArgumentMatchers.anyLong());
      var field = AbstractStorage.class.getDeclaredField("writeCache");
      field.setAccessible(true);
      field.set(storage, proxy);
      var output = new RecordingOutput();
      var names = new ArrayList<String>();
      try {
        var failure = assertThrows(DatabaseException.class, () -> storage.backup(
            this::unitIterator, this::openInput, name -> {
              names.add(name);
              return output;
            }, name -> {
              throw new AssertionError("no automatic removal");
            }));
        assertTrue(failure.getCause().getMessage().contains("trust lost"));
        assertTrue(reached.get());
        assertEquals(0, output.tailRequests);
        org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
        assertEquals(List.of(99L), trackerCandidates(tracker, "active", 123456));
      } finally {
        field.set(storage, cache);
        restoreTrackerObserver(tracker, previous);
      }
      var failed = backupPath.resolve(names.getFirst());
      Files.write(failed, output.bytes.toByteArray());
      var before = unitContent();
      assertThrows(DatabaseException.class, () -> storage.backup(backupPath));
      assertThrows(UnsupportedBackupException.class, () -> manager.internal.restore(
          "refusedAllocation", backupPath.toString(), null, null));
      assertEquals(before, unitContent());
      Files.delete(failed);
      storage.backup(backupPath);
    }
  }

  /** Opt-in burst evidence has no timing threshold and runs only in a separate child. */
  @Test
  public void firstMarkBurstPerformanceEvidence() throws Exception {
    com.jetbrains.youtrackdb.internal.core.storage.cache.local.TrackerPerformanceProbe
        .launchStartup(
            com.jetbrains.youtrackdb.internal.core.storage.cache.local.TrackerPerformanceProbe.class,
            List.of("firstMarkBurst"), "firstMarkNs", "firstMarkBytes", "commitNs", "share",
            "classificationNs", "interceptionExtraNs", "interceptionExtraBytes");
  }

  /** Wide full-walk indices stay valid at MAX_VALUE and fail clearly at the next long value. */
  @Test
  public void backupReadCacheNarrowingNeverWraps() throws Exception {
    assertEquals(Integer.MAX_VALUE, DiskStorage.backupPageIndex(Integer.MAX_VALUE));
    for (long index : new long[] {-1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
      var failure = assertThrows(IOException.class, () -> DiskStorage.backupPageIndex(index));
      assertTrue(failure.getMessage().contains(Long.toString(index)));
    }
  }

  /** Fresh capable output reads every page and publishes its exact pair only after close. */
  @Test
  public void forcedOutputPublishesOneTailRequest() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var tracker = tracker(storage);
      org.junit.Assert.assertNull(trackerField(tracker, "lastCompleted"));
      var sideFile = databasesPath.resolve(SOURCE).resolve("changed-pages.cpt");
      var cacheTracker = storage.getWriteCache().getClass().getDeclaredField("changedPageTracker");
      cacheTracker.setAccessible(true);
      assertSame("the caller and WOWCache must share the same tracker", tracker,
          cacheTracker.get(storage.getWriteCache()));
      var realReads = storage.getReadCache();
      var reads = org.mockito.Mockito.mock(
          com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class,
          org.mockito.AdditionalAnswers.delegatesTo(realReads));
      var readField = AbstractStorage.class.getDeclaredField("readCache");
      readField.setAccessible(true);
      var physicalSizes = new HashMap<Long, Long>();
      var cache = storage.getWriteCache();
      for (var fileId : cache.files().values()) {
        var external = cache.externalFileId(cache.internalFileId(fileId));
        physicalSizes.put(external, cache.physicalSizeForBackupSnapshot(external));
      }
      var output = new RecordingOutput();
      output.onClose = () -> {
        try {
          org.junit.Assert.assertNull("completion must wait for explicit close",
              trackerField(tracker, "lastCompleted"));
          assertNotNull(trackerField(tracker, "sealed"));
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      };
      var successObserved = new java.util.concurrent.atomic.AtomicBoolean();
      var created = new ArrayList<String>();
      var records = new CopyOnWriteArrayList<LogRecord>() {
        @Override
        public boolean add(LogRecord record) {
          if (record.getMessage().contains("is completed. Backup unit file")) {
            assertEquals("close must precede success reporting", 1, output.closeCalls);
            var pair = literalContinuity(output.bytes.toByteArray());
            try {
              assertEquals(pair.generationId(), trackerField(tracker, "lastCompleted"));
              assertSavedPair(sideFile, pair);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            successObserved.set(true);
          }
          return super.add(record);
        }
      };
      readField.set(storage, reads);
      try {
        withDiskStorageLogs(records, () -> created.add(storage.backup(
            () -> List.<String>of().iterator(),
            file -> InputStream.nullInputStream(), file -> output, file -> {
            })));
      } finally {
        readField.set(storage, realReads);
      }
      assertTrue("the success log must observe published completion", successObserved.get());
      var name = created.getFirst();
      assertTrue("the fixture must contain physical pages", physicalSizes.values().stream()
          .anyMatch(size -> size > 0));
      for (var entry : physicalSizes.entrySet()) {
        for (int page = 0; page < entry.getValue(); page++) {
          org.mockito.Mockito.verify(reads).silentLoadForRead(entry.getKey(), page, cache, true);
        }
      }
      var pair = literalContinuity(output.bytes.toByteArray());
      assertNotNull("capable bootstrap exports its consumed boundary", pair);
      assertEquals(pair.generationId(), trackerField(tracker, "lastCompleted"));
      org.junit.Assert.assertNull(trackerField(tracker, "sealed"));
      assertTrue(tracker.isTrusted());
      assertSavedPair(sideFile, pair);
      assertEquals(2, output.forceCalls);
      assertEquals(1, output.closeCalls);
      assertEquals(1, output.tailRequests);
      var forceIndex = output.events.indexOf("force");
      assertTrue("the ZIP must finish before the force", output.events.contains("zipComplete"));
      assertTrue(output.events.indexOf("zipComplete") < forceIndex - 1);
      assertEquals("flush", output.events.get(forceIndex - 1));
      assertTrue("padding must precede the payload flush",
          output.events.indexOf("padding") < forceIndex - 1);
      assertEquals("the complete tail must be flushed and forced before close",
          List.of("force", "tail", "flush", "force", "close"),
          output.events.subList(forceIndex, output.events.size()));
      assertEquals("nothing may be written after the tail", 0, output.writesAfterTail);
      var unit = output.bytes.toByteArray();
      assertTrue(DiskStorage.hasBackupBarrier(
          java.util.Arrays.copyOfRange(unit, unit.length - 122,
              unit.length)));
      assertCanonicalVersionSix(unit, name, storage.getUuid(), 0, true);
    }
  }

  private static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker
      tracker(AbstractStorage storage) throws Exception {
    var field = DiskStorage.class.getDeclaredField("changedPageTracker");
    field.setAccessible(true);
    return (com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker) field
        .get(storage);
  }

  private static Object trackerField(Object tracker, String name) throws Exception {
    var field = tracker.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(tracker);
  }

  /** Decode literal v6 slots without the production decoder, including the absent zero control. */
  private static DiskStorage.BackupContinuity literalContinuity(byte[] unit) {
    var tail = unit.length - 122;
    var bytes = ByteBuffer.wrap(unit);
    int marker = bytes.getInt(tail + 12);
    if (marker == 0) {
      for (int offset = 16; offset < 48; offset++) {
        assertEquals("absent UUID slots must be zero", 0, unit[tail + offset]);
      }
      return null;
    }
    assertEquals(0x434F4E54, marker);
    return new DiskStorage.BackupContinuity(
        new UUID(bytes.getLong(tail + 16), bytes.getLong(tail + 24)),
        new UUID(bytes.getLong(tail + 32), bytes.getLong(tail + 40)));
  }

  /** The side-file header is big endian: magic, version, flags, LSN, tracker and completion. */
  private static void assertSavedPair(Path sideFile, DiskStorage.BackupContinuity pair)
      throws IOException {
    assertSavedPair(sideFile, pair, 3);
  }

  private static void assertSavedPair(Path sideFile, DiskStorage.BackupContinuity pair, int flags)
      throws IOException {
    var bytes = ByteBuffer.wrap(Files.readAllBytes(sideFile));
    assertEquals(0x59544350, bytes.getInt());
    assertEquals(1, bytes.getInt());
    assertEquals("exact saved trust, completion and pending flags", flags, bytes.get());
    bytes.getLong();
    bytes.getInt();
    assertEquals(pair.trackerId(), new UUID(bytes.getLong(), bytes.getLong()));
    assertEquals(pair.generationId(), new UUID(bytes.getLong(), bytes.getLong()));
  }

  /** Checks literal offsets independently of the production layout helpers. */
  private static void assertCanonicalVersionSix(byte[] unit, String name, UUID id,
      int sequence, boolean forced) throws IOException {
    var tail = unit.length - 122;
    var bytes = ByteBuffer.wrap(unit);
    assertEquals(6, bytes.getShort(tail + 48));
    assertEquals(6, bytes.getShort(unit.length - 74));
    assertEquals(forced ? 0x42415252 : 0, bytes.getInt(tail + 8));
    var literalPair = literalContinuity(unit);
    assertEquals(id.getLeastSignificantBits(), bytes.getLong(tail + 50));
    assertEquals(id.getMostSignificantBits(), bytes.getLong(tail + 58));
    assertEquals(sequence, bytes.getInt(tail + 66));
    var metadata = DiskStorage.inspectBackupUnit(name, SOURCE, id,
        new ByteArrayInputStream(unit), null).metadata();
    assertNotNull(metadata);
    assertEquals(literalPair, metadata.continuity());
    assertEquals(sequence == 0 ? -1 : metadata.startLsn().getSegment(),
        bytes.getLong(tail + 70));
    assertEquals(sequence == 0 ? -1 : metadata.startLsn().getPosition(),
        bytes.getInt(tail + 78));
    assertEquals(metadata.endLsn().getSegment(), bytes.getLong(tail + 82));
    assertEquals(metadata.endLsn().getPosition(), bytes.getInt(tail + 90));
    assertEquals(metadata.lastTxId(), bytes.getLong(tail + 94));
    assertEquals(BackupUnitFiles.supportedFeatureFormat(), bytes.getInt(tail + 102));
    assertEquals(BackupUnitFiles.supportedLayoutVersion(), bytes.getInt(tail + 106));
    assertEquals(BackupUnitFiles.COMPLETED_CREATION_EVIDENCE, bytes.getInt(tail + 110));
    assertEquals(DiskStorage.XX_HASH_64.hash(unit, tail + 8, 106, 0x4D45544144415441L),
        bytes.getLong(tail));
    assertEquals(DiskStorage.XX_HASH_64.hash(unit, 0, unit.length - 8,
        DiskStorage.XX_HASH_SEED), bytes.getLong(unit.length - 8));
  }

  /** Restore refuses a bad metadata checksum even when the full hash still agrees. */
  @Test
  public void restoreRejectsVersionFourMetadataChecksumFailure() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var name = storage.fullBackup(backupPath);
      rewriteCurrentAsVersionFour(name, storage.getUuid());
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
      rewriteCurrentAsVersionFour(full, storage.getUuid());
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

  /** A v4 chain with a forced head reads only 122 bytes per unit from the file itself. */
  @Test
  public void extensionReadsOnlyFileTailsForVersionFourChain() throws Exception {
    try (var youTrackDB = openManager()) {
      var storage = createSourceDatabase(youTrackDB);
      var full = storage.fullBackup(backupPath);
      rewriteCurrentAsVersionFour(full, storage.getUuid());
      addOneRecord(youTrackDB);
      var first = storage.backup(backupPath);
      rewriteCurrentAsVersionFour(first, storage.getUuid());
      addOneRecord(youTrackDB);
      var second = storage.backup(backupPath);
      rewriteCurrentAsVersionFour(second, storage.getUuid());
      var before = unitContent();
      var reader = new CountingTailSupplier();
      var extension = storage.backup(this::unitIterator, reader, this::openOutput,
          name -> {
            throw new AssertionError("no file should be removed");
          });
      assertEquals(BackupUnitFiles.VERSION_6,
          inspectUnit(extension, storage.getUuid()).metadata().backupFormatVersion());
      var after = unitContent();
      after.remove(extension);
      assertEquals("extension must not rewrite any version 4 unit", before, after);
      assertEquals("all three units must be read through the positioned file channel",
          3 * 122, reader.fileBytesRead);
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
      BackupUnitFiles.rewriteAsVersion(backupPath.resolve(full), 3);
      addOneRecord(youTrackDB);
      var head = storage.backup(backupPath);
      rewriteCurrentAsVersionFour(head, storage.getUuid());
      var reader = new CountingTailSupplier();
      storage.backup(this::unitIterator, reader, this::openOutput,
          name -> {
            throw new AssertionError("no file should be removed");
          });
      assertEquals(2 * 122, reader.fileBytesRead);
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
      rewriteCurrentAsVersionFour(head, storage.getUuid());
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
      rewriteCurrentAsVersionFour(head, storage.getUuid());
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
      bytes[bytes.length - 122] ^= 1;
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
      bytes[bytes.length - 122] ^= 1;
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
      for (var count : new int[] {12, 86, 122}) {
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

  /** Captures only the calling backup thread and restores the logger configuration afterward. */
  private void withDiskStorageLogs(List<LogRecord> records, ThrowingAction action)
      throws Exception {
    var logger = Logger.getLogger(DiskStorage.class.getName());
    var previousLevel = logger.getLevel();
    // Backup publication and its log calls run synchronously on the caller thread.
    var threadId = Thread.currentThread().threadId();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLongThreadID() == threadId) {
          records.add(record);
        }
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

  /** Records ZIP completion, padding, flush, explicit force, one tail write and close. */
  private static final class RecordingOutput extends OutputStream implements BackupForceCapability {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final List<String> events = new ArrayList<>();
    private Runnable onClose = () -> {
    };
    private Runnable onFirstWrite = () -> {
    };
    private boolean firstWriteObserved;
    private String failPhase;
    private IOException cachedFailure;
    private boolean faultReached;
    private boolean closed;
    private boolean paddingSeen;
    private boolean failForce;
    private boolean failClose;
    private int closeCalls;
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
      if (!firstWriteObserved) {
        firstWriteObserved = true;
        onFirstWrite.run();
      }
      failAt("payload");
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
      var isTail = length == 122 && data[offset + 48] == 0 && data[offset + 49] == 6;
      if (isTail) {
        tailRequests++;
        failAt("tail");
      } else if (events.contains("zipComplete")) {
        // The writer issues a padding request even when no padding bytes are needed.
        paddingSeen = true;
        events.add("padding");
        failAt("padding");
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
    public void flush() throws IOException {
      events.add("flush");
      if (tailPublished) {
        failAt("tailFlush");
      } else if (paddingSeen) {
        failAt("payloadFlush");
      }
    }

    @Override
    public void close() throws IOException {
      closed = true;
      closeCalls++;
      events.add("close");
      if (cachedFailure != null) {
        throw cachedFailure;
      }
      if (failClose) {
        throw new IOException("injected close");
      }
      failAt("close");
      onClose.run();
    }

    private void failAt(String phase) throws IOException {
      if (phase.equals(failPhase)) {
        faultReached = true;
        if (cachedFailure != null) {
          throw cachedFailure;
        }
        throw new IOException("injected " + phase);
      }
    }

    @Override
    public void forceBackupData() throws IOException {
      events.add("force");
      forceCalls++;
      failAt(forceCalls == 1 ? "force" : "secondForce");
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

  /** Converts real current output to v4 and verifies both checksums before use. */
  private void rewriteCurrentAsVersionFour(String unitName, UUID databaseId)
      throws IOException {
    assertEquals(6, inspectUnit(unitName, databaseId).metadata().backupFormatVersion());
    BackupUnitFiles.rewriteAsVersion(backupPath.resolve(unitName), 4);
    assertEquals(4, inspectUnit(unitName, databaseId).metadata().backupFormatVersion());
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
