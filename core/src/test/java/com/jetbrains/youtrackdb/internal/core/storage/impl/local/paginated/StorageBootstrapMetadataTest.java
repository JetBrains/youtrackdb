package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageAdmissionException.Reason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Tests redundant bootstrap selection, publication, recovery, and birth ownership. */
@Category(SequentialTest.class)
public class StorageBootstrapMetadataTest {

  private static final FeatureFormatIdentity FORMAT = new FeatureFormatIdentity(25);
  private static final StorageIdentity STORAGE =
      new StorageIdentity(UUID.fromString("10000000-0000-0000-0000-000000000001"));
  private static final StorageLineageIdentity LINEAGE_ONE =
      new StorageLineageIdentity(UUID.fromString("20000000-0000-0000-0000-000000000001"));
  private static final StorageLineageIdentity LINEAGE_TWO =
      new StorageLineageIdentity(UUID.fromString("20000000-0000-0000-0000-000000000002"));
  private static final StorageLineageIdentity LINEAGE_THREE =
      new StorageLineageIdentity(UUID.fromString("20000000-0000-0000-0000-000000000003"));
  private static final StorageIdentity SOURCE_STORAGE =
      new StorageIdentity(UUID.fromString("30000000-0000-0000-0000-000000000001"));
  private static final StorageLineageIdentity SOURCE_LINEAGE =
      new StorageLineageIdentity(UUID.fromString("40000000-0000-0000-0000-000000000001"));

  private Path directory;

  @Before
  public void setUp() throws IOException {
    directory = Files.createTempDirectory("storage-bootstrap-metadata-");
  }

  @After
  public void tearDown() throws IOException {
    if (directory != null && Files.exists(directory)) {
      try (Stream<Path> paths = Files.walk(directory)) {
        paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
      }
    }
    assertThat(StorageBootstrapMetadata.processLockCountForTests()).isZero();
  }

  /** Initial establishment writes only durable BIRTH_IN_PROGRESS authority in the first slot. */
  @Test
  public void birthEstablishesOnlyPendingAuthority() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);

    assertThat(birth.state()).isEqualTo(StorageBootstrapMetadata.State.BIRTH_IN_PROGRESS);
    assertThat(birth.generation()).isEqualTo(1);
    assertThat(birth.sequenceFloor().highestIssued()).isZero();
    assertThat(authorityFiles()).containsExactly(authorityPath(0));
    assertThat(Files.size(authorityPath(0))).isEqualTo(80);
    // Storage birth writes the storage layout version into the former spare field at offset 28.
    assertThat(readStorageLayoutVersion(authorityPath(0)))
        .isEqualTo(StorageBootstrapMetadata.SUPPORTED_STORAGE_LAYOUT_VERSION);
  }

  /**
   * A record whose storage layout version field holds an unsupported value is rejected by name.
   *
   * <p>The scenario mutates one checksum-valid copy to storage layout version 99. The expected
   * outcome is one rejection with the unsupported-storage-layout-version reason.
   */
  @Test
  public void unsupportedStorageLayoutVersionIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] unsupported = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(unsupported).putInt(28, 99);
    updateChecksum(unsupported);
    Files.write(authorityPath(1), unsupported);

    final var rejection = admissionRejection(metadata()::readRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.UNSUPPORTED_STORAGE_LAYOUT_VERSION);
    assertThat(rejection).hasMessageContaining("Unsupported storage layout version: 99");
  }

  /**
   * An image of an earlier commit of this branch holds the empty storage layout version zero.
   *
   * <p>The scenario mutates one checksum-valid copy to the empty value zero. The expected outcome
   * is the unsupported-storage-layout-version reason. The outcome is neither a damaged record nor
   * a missing record.
   */
  @Test
  public void emptyStorageLayoutVersionOfEarlierCommitIsUnsupported() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] earlierCommit = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(earlierCommit).putInt(28, 0);
    updateChecksum(earlierCommit);
    Files.write(authorityPath(1), earlierCommit);

    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.UNSUPPORTED_STORAGE_LAYOUT_VERSION);
    assertThat(rejection.reason()).isNotEqualTo(Reason.AUTHORITY_DAMAGED);
    assertThat(rejection.reason()).isNotEqualTo(Reason.AUTHORITY_MISSING);
    assertThat(rejection).hasMessageContaining("Unsupported storage layout version: 0");
  }

  /**
   * The integrity check runs before the storage layout version check.
   *
   * <p>The scenario breaks the storage layout version of the older copy and leaves the checksum
   * stale. The expected outcome is a tolerated damaged copy and a successful read of the active
   * copy. A layout check before the integrity check would instead reject the whole image.
   */
  @Test
  public void integrityCheckPrecedesStorageLayoutVersionCheck() throws IOException {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] damaged = Files.readAllBytes(authorityPath(0));
    ByteBuffer.wrap(damaged).putInt(28, 99);
    Files.write(authorityPath(0), damaged);

    assertThat(metadata().readActiveRequired()).isEqualTo(active);
  }

  /**
   * A directory whose every copy fails the integrity check reports an interrupted birth.
   *
   * <p>The scenario overwrites both existing copies with checksum-invalid content. The expected
   * outcome is the interrupted-birth reason with one suppressed damaged-copy failure per copy.
   */
  @Test
  public void everyCopyDamagedReportsInterruptedBirthWithDamageDetail() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    for (var index = 0; index < 2; index++) {
      final byte[] damaged = Files.readAllBytes(authorityPath(index));
      ByteBuffer.wrap(damaged).putLong(72, 0);
      Files.write(authorityPath(index), damaged);
    }

    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    assertThat(rejection.getSuppressed()).hasSize(2);
    for (var suppressed : rejection.getSuppressed()) {
      assertThat(((StorageAdmissionException) suppressed).reason())
          .isEqualTo(Reason.AUTHORITY_DAMAGED);
    }
  }

  /**
   * A directory without any bootstrap authority artifact reports a missing record.
   *
   * <p>The scenario reads an empty storage directory. The expected outcome is the authority-missing
   * reason and a message that names the reason and the storage path.
   */
  @Test
  public void directoryWithoutArtifactReportsMissingRecord() throws IOException {
    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_MISSING);
    assertThat(rejection).hasMessageContaining("admission reason: AUTHORITY_MISSING");
    assertThat(rejection).hasMessageContaining(directory.toRealPath().toString());
  }

  /**
   * A directory that holds only the authority lock file reports an interrupted birth.
   *
   * <p>The scenario creates the authority lock file alone. The expected outcome is the
   * interrupted-birth reason, because the lock file is a bootstrap authority artifact.
   */
  @Test
  public void lockFileResidueReportsInterruptedBirth() throws IOException {
    Files.createFile(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME));

    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
  }

  /**
   * A directory that holds only an unfinished record candidate reports an interrupted birth.
   *
   * <p>The scenario creates one candidate file without any authority copy. The expected outcome is
   * the interrupted-birth reason, and the candidate file survives the rejected decision.
   */
  @Test
  public void candidateResidueWithoutRecordReportsInterruptedBirth() throws IOException {
    Files.write(temporaryPath(0), new byte[] {1});

    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    assertThat(temporaryPath(0)).exists();
  }

  /**
   * Admission repair removes candidate residue and confirms the accepted snapshot.
   *
   * <p>The scenario leaves one candidate file next to one active record. The expected outcome is an
   * accepted snapshot, no candidate residue, and one further confirmed copy.
   */
  @Test
  public void admissionRepairRemovesCandidateAndConfirmsSnapshot() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    Files.delete(authorityPath(0));
    Files.write(temporaryPath(2), new byte[] {1});

    assertThat(metadata().readActiveRequired()).isEqualTo(active);
    assertThat(temporaryFiles()).isEmpty();
    assertThat(authorityFiles()).hasSize(2);
  }

  /**
   * A failed repair after acceptance never fails the admission decision.
   *
   * <p>The scenario injects a publication failure into the repair of an accepted image. The
   * expected outcome is the accepted snapshot and an unchanged authority copy count.
   */
  @Test
  public void failedRepairAfterAcceptanceKeepsAdmissionSuccessful() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var failingRepair =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw new IOException("injected repair failure");
            });

    assertThat(failingRepair.readActiveRequired()).isEqualTo(active);
    assertThat(authorityFiles()).hasSize(2);
    assertThat(temporaryFiles()).isEmpty();
  }

  /**
   * A low-level input and output failure of the decision carries one named reason.
   *
   * <p>The scenario removes every read permission from the only authority copy. The expected
   * outcome is the authority-input-output-failure reason.
   */
  @Test
  public void lowLevelReadFailureReportsInputOutputReason() throws IOException {
    Assume.assumeTrue(
        "Posix file permissions are unavailable",
        Files.getFileStore(directory).supportsFileAttributeView("posix"));
    Assume.assumeFalse("The root user ignores file permissions", "root".equals(
        System.getProperty("user.name")));
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    Files.setPosixFilePermissions(authorityPath(0), PosixFilePermissions.fromString("---------"));
    try {
      final var rejection = admissionRejection(metadata()::readActiveRequired);

      assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_IO_FAILURE);
    } finally {
      Files.setPosixFilePermissions(authorityPath(0), PosixFilePermissions.fromString("rw-------"));
    }
  }

  /** The live creator can activate its token while another instance rejects the interrupted birth. */
  @Test
  public void onlyCreatingCallCanContinueDurableBirth() throws IOException {
    final var creator = metadata();
    final var birth = creator.createBirth(STORAGE, LINEAGE_ONE);
    final var laterOperation = metadata();
    final var discovered = laterOperation.readRequired();

    final var rejection = admissionRejection(laterOperation::readActiveRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    assertThat(rejection).hasMessageContaining("Interrupted storage birth");
    assertThatThrownBy(() -> laterOperation.activate(discovered))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot continue");

    assertThat(creator.activate(birth).state()).isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
  }

  /** A later creation attempt cannot overwrite durable incomplete-birth authority. */
  @Test
  public void repeatedBirthIsRejectedUntilExplicitRemoval() throws IOException {
    metadata().createBirth(STORAGE, LINEAGE_ONE);

    assertThatThrownBy(() -> metadata().createBirth(STORAGE, LINEAGE_TWO))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("already exists");
  }

  /** Each update uses another slot and recovery selects the newest legal generation. */
  @Test
  public void redundantPublicationSelectsNewestLegalRecord() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    final var active = metadata.activate(birth);
    final var advanced =
        metadata.advanceFloor(active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 9));

    assertThat(authorityFiles()).hasSize(3);
    assertThat(metadata().readRequired()).isEqualTo(advanced);
  }

  /** Once all slots are used, publication safely reuses the oldest non-selected location. */
  @Test
  public void publicationSafelyReusesOldestLocation() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    final var active = metadata.activate(birth);
    final var floorThree =
        metadata.advanceFloor(active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 3));
    final var floorFour =
        metadata.advanceFloor(floorThree, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 4));

    assertThat(floorFour.generation()).isEqualTo(4);
    assertThat(metadata.readRequired()).isEqualTo(floorFour);
    assertThat(readGeneration(authorityPath(0))).isEqualTo(4);
  }

  /** Confirmation reuses one damaged slot while preserving two verified authority records. */
  @Test
  public void confirmationReusesDamagedOccupiedSlot() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    Files.write(authorityPath(2), new byte[] {1, 2, 3});

    final var confirmed = metadata().readRequired();

    assertThat(confirmed).isEqualTo(active);
    assertThat(Files.size(authorityPath(2))).isEqualTo(80);
    assertThat(metadata().readRequired()).isEqualTo(active);
    assertThat(metadata().readActiveRequired()).isEqualTo(active);
  }

  /** Damaged slots remain reusable even when only the selected authority is verified. */
  @Test
  public void publicationReusesDamageWithoutOverwritingSelectedAuthority() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    final byte[] selectedBytes = Files.readAllBytes(authorityPath(0));
    Files.write(authorityPath(1), new byte[] {1});
    Files.write(authorityPath(2), new byte[] {2});

    final var active = metadata.activate(birth);

    assertThat(active.state()).isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
    assertThat(Files.readAllBytes(authorityPath(0))).isEqualTo(selectedBytes);
  }

  /** Failed publication removes the observed candidate without deleting the storage directory. */
  @Test
  public void failedPublicationRemovesCandidateWithoutDirectoryDeletion() throws IOException {
    final var creator = metadata();
    final var birth = creator.createBirth(STORAGE, LINEAGE_ONE);
    final var active = creator.activate(birth);
    final byte[] selectedBytes = Files.readAllBytes(authorityPath(1));
    final var observedCandidate = new AtomicReference<Path>();
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              assertThat(source).exists();
              observedCandidate.set(source);
              throw new IOException("injected publication failure");
            });

    assertThatThrownBy(
        () -> failing.advanceFloor(
            active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("injected");
    assertThat(observedCandidate).doesNotHaveValue(null);
    assertThat(observedCandidate.get()).doesNotExist();
    assertThat(directory).isDirectory();
    assertThat(Files.readAllBytes(authorityPath(1))).isEqualTo(selectedBytes);
    assertThat(metadata().readActiveRequired()).isEqualTo(active);
  }

  /** An unchecked publication failure removes its owned candidate and remains primary. */
  @Test
  public void uncheckedPublicationFailureRemovesOwnedCandidate() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var publicationFailure = new IllegalStateException("unchecked publication failure");
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw publicationFailure;
            });

    final Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)));

    assertThat(thrown).isSameAs(publicationFailure);
    assertThat(temporaryFiles()).isEmpty();
    assertThat(metadata().readActiveRequired()).isEqualTo(active);
  }

  /** A failed warning remains diagnostic and cannot prevent candidate removal. */
  @Test
  public void warningFailureDoesNotPreventCandidateRemoval() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var publicationFailure = new IOException("primary publication failure");
    final var removalAttempted = new AtomicBoolean();
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw publicationFailure;
            },
            (requester, storageDirectory, candidate, failure) -> {
              throw new IllegalStateException("warning failure");
            },
            candidate -> {
              removalAttempted.set(true);
              Files.deleteIfExists(candidate);
            });

    final Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)));

    assertThat(thrown).isSameAs(publicationFailure);
    assertThat(removalAttempted).isTrue();
    assertThat(temporaryFiles()).isEmpty();
  }

  /** Warning and removal failures remain diagnostic under the original publication failure. */
  @Test
  public void warningAndRemovalFailuresDoNotReplacePublicationFailure() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var publicationFailure = new IOException("primary publication failure");
    final var removalFailure = new IllegalStateException("removal failure");
    final var removalAttempted = new AtomicBoolean();
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw publicationFailure;
            },
            (requester, storageDirectory, candidate, failure) -> {
              throw new IllegalStateException("warning failure");
            },
            candidate -> {
              removalAttempted.set(true);
              throw removalFailure;
            });

    final Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)));

    assertThat(thrown).isSameAs(publicationFailure);
    assertThat(removalAttempted).isTrue();
    assertThat(thrown.getSuppressed()).containsExactly(removalFailure);
  }

  /** The same failure from publication and cleanup remains primary without self-suppression. */
  @Test
  public void sharedPublicationAndCleanupFailureRemainsPrimary() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var sharedFailure = new IllegalStateException("shared publication and cleanup failure");
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw sharedFailure;
            },
            (requester, storageDirectory, candidate, failure) -> {
            },
            candidate -> {
              throw sharedFailure;
            });

    final Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)));

    assertThat(thrown).isSameAs(sharedFailure);
    assertThat(thrown.getSuppressed()).isEmpty();
  }

  /** A cleanup throwable outside standard exception categories remains diagnostic only. */
  @Test
  public void arbitraryCleanupThrowableDoesNotReplacePublicationFailure() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var publicationFailure = new IOException("primary publication failure");
    final var cleanupFailure = new Throwable("arbitrary cleanup failure");
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw publicationFailure;
            },
            (requester, storageDirectory, candidate, failure) -> {
            },
            candidate -> throwUnchecked(cleanupFailure));

    final Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)));

    assertThat(thrown).isSameAs(publicationFailure);
    assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
  }

  /** Confirmation removes an abandoned candidate when durable authority still exists. */
  @Test
  public void preexistingCandidateResidueIsRemovedDuringRead() throws IOException {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    Files.write(temporaryPath(2), new byte[] {1});

    assertThat(metadata.readActiveRequired()).isEqualTo(active);
    assertThat(temporaryFiles()).isEmpty();
  }

  /** Candidate cleanup failure is suppressed under the original publication failure. */
  @Test
  public void cleanupFailureDoesNotReplacePublicationFailure() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw new IOException("primary publication failure");
            },
            (requester, storageDirectory, candidate, failure) -> {
            },
            candidate -> {
              throw new IOException("secondary cleanup failure");
            });

    final IOException failure =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> failing.advanceFloor(
                active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)),
            IOException.class);

    assertThat(failure).hasMessageContaining("primary publication failure");
    assertThat(failure.getSuppressed()).hasSize(1);
    assertThat(failure.getSuppressed()[0].getMessage()).contains("secondary cleanup failure");
  }

  /** A complete active candidate from a failed barrier is selected only after fresh confirmation. */
  @Test
  public void recoverySelectsUnconfirmedActiveCandidateAfterFreshBarrier() throws IOException {
    final var moves = new AtomicInteger();
    final var uncertainCreator =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              FileUtils.durableAtomicMove(source, target, requester);
              if (moves.incrementAndGet() == 2) {
                throw new IOException("directory barrier failed after move");
              }
            });
    final var birth = uncertainCreator.createBirth(STORAGE, LINEAGE_ONE);

    assertThatThrownBy(() -> uncertainCreator.activate(birth))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("barrier failed");

    final var recovered = metadata().readActiveRequired();
    assertThat(recovered.state()).isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
    assertThat(authorityFiles()).hasSize(3);
  }

  /** Different records at the newest generation are ambiguous and keep storage unavailable. */
  @Test
  public void conflictingNewestGenerationIsRejectedAsAmbiguous() throws IOException {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] conflict = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(conflict).putLong(64, 11);
    updateChecksum(conflict);
    Files.write(authorityPath(0), conflict);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_AMBIGUOUS);
    assertThat(rejection).hasMessageContaining("ambiguous");
    assertThat(active.generation()).isEqualTo(2);
  }

  /** Foreign storage identity among valid records makes authority ambiguous. */
  @Test
  public void foreignStorageIdentityIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] foreign = Files.readAllBytes(authorityPath(0));
    ByteBuffer.wrap(foreign).putLong(32, 0x7777777777777777L);
    updateChecksum(foreign);
    Files.write(authorityPath(0), foreign);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.FOREIGN_AUTHORITY_COPY);
    assertThat(rejection).hasMessageContaining("different storage identities");
  }

  /**
   * Consecutive valid generations with an illegal lifecycle transition fail closed.
   *
   * <p>The scenario mutates the older copy into the active lifecycle state and mutates the newer
   * copy into the birth-in-progress lifecycle state. An active image never returns to a birth, so
   * that pair stays illegal. The expected outcome is one rejection with the illegal-transition
   * reason. The pair of a birth and a restore is no longer illegal, because Track 24 allows the
   * transition from birth in progress to restore in progress.
   */
  @Test
  public void illegalStateTransitionIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] olderCopy = Files.readAllBytes(authorityPath(0));
    ByteBuffer.wrap(olderCopy).putInt(24, 2);
    updateChecksum(olderCopy);
    Files.write(authorityPath(0), olderCopy);
    final byte[] illegal = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(illegal).putInt(24, 1);
    updateChecksum(illegal);
    Files.write(authorityPath(1), illegal);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_ILLEGAL_TRANSITION);
    assertThat(rejection).hasMessageContaining("illegal state transition");
  }

  /** One torn authority is ignored when another complete authority remains selectable. */
  @Test
  public void tornCandidateDoesNotDestroyPriorAuthority() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    Files.write(authorityPath(1), new byte[] {1, 2, 3});

    assertThat(metadata.readRequired()).isEqualTo(birth);
  }

  /** A symbolic authority cannot import a valid record from outside its fixed location. */
  @Test
  public void symbolicAuthorityIsRejected() throws Exception {
    final var metadata = metadata();
    metadata.createBirth(STORAGE, LINEAGE_ONE);
    final var foreign = directory.resolve("foreign.bsm");
    Files.move(authorityPath(0), foreign);
    try {
      Files.createSymbolicLink(authorityPath(0), foreign.getFileName());
    } catch (UnsupportedOperationException | IOException e) {
      Assume.assumeNoException("Symbolic links are not supported", e);
    }

    final var rejection = admissionRejection(metadata::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.NON_REGULAR_AUTHORITY_FILE);
    assertThat(rejection).hasMessageContaining("not a regular file");
  }

  /** A checksum-valid wrong magic value fails closed instead of using older authority. */
  @Test
  public void invalidAuthorityMagicIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] invalid = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(invalid).putLong(0, 7);
    updateChecksum(invalid);
    Files.write(authorityPath(1), invalid);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_INVALID_CONTENT);
    assertThat(rejection).hasMessageContaining("Invalid bootstrap authority magic: 7");
  }

  /** A checksum-valid unsupported encoding version fails closed and names that version. */
  @Test
  public void unsupportedEncodingVersionIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] unsupported = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(unsupported).putInt(8, 99);
    updateChecksum(unsupported);
    Files.write(authorityPath(1), unsupported);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.UNSUPPORTED_ENCODING_VERSION);
    assertThat(rejection).hasMessageContaining("Unsupported bootstrap encoding version: 99");
  }

  /** A checksum-valid foreign feature format fails closed instead of using an older record. */
  @Test
  public void unsupportedFeatureFormatIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] unsupported = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(unsupported).putInt(12, 99);
    updateChecksum(unsupported);
    Files.write(authorityPath(1), unsupported);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.UNSUPPORTED_FEATURE_FORMAT_VERSION);
    assertThat(rejection).hasMessageContaining("Unsupported feature format version: 99");
  }

  /** A checksum-valid unknown state fails closed instead of using an older record. */
  @Test
  public void unsupportedAuthorityStateIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] invalid = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(invalid).putInt(24, 99);
    updateChecksum(invalid);
    Files.write(authorityPath(1), invalid);

    final var rejection = admissionRejection(metadata::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.UNSUPPORTED_LIFECYCLE_STATE);
    assertThat(rejection).hasMessageContaining("Unsupported bootstrap state: 99");
  }

  /** A checksum-valid nonpositive generation fails closed and names that value. */
  @Test
  public void invalidAuthorityGenerationIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] invalid = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(invalid).putLong(16, 0);
    updateChecksum(invalid);
    Files.write(authorityPath(1), invalid);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_INVALID_CONTENT);
    assertThat(rejection).hasMessageContaining("Invalid bootstrap authority generation: 0");
  }

  /** A checksum-valid negative sequence value fails closed and names that value. */
  @Test
  public void invalidHighestIssuedValueIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] invalid = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(invalid).putLong(64, -1);
    updateChecksum(invalid);
    Files.write(authorityPath(1), invalid);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.INVALID_SEQUENCE_FLOOR);
    assertThat(rejection).hasMessageContaining(
        "Invalid bootstrap authority highest issued value: -1");
  }

  /** Temporary publication residue is removed without treating its bytes as authority. */
  @Test
  public void temporaryResidueIsDiscardedWhenAuthorityExists() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    Files.write(temporaryPath(1), new byte[] {1});

    assertThat(metadata.readRequired()).isEqualTo(birth);
    assertThat(temporaryFiles()).isEmpty();
  }

  /** Temporary publication residue without durable authority still blocks a new birth. */
  @Test
  public void temporaryResidueWithoutAuthorityBlocksCreation() throws IOException {
    Files.write(temporaryPath(0), new byte[] {1});

    assertThatThrownBy(() -> metadata().createBirth(STORAGE, LINEAGE_ONE))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("already exists");
  }

  /** Equal floor advance preserves the live token so its creating object can activate birth. */
  @Test
  public void equalBirthFloorAdvancePreservesLiveToken() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);

    final var repeated =
        metadata.advanceFloor(birth, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 0));

    assertThat(repeated).isSameAs(birth);
    assertThat(metadata.activate(repeated).state())
        .isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
  }

  /** Floor rules reject rewinds and foreign identities while accepting an equal no-op. */
  @Test
  public void floorAdvancementIsIdentityQualifiedAndMonotonic() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    final var floor = new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 8);
    final var advanced = metadata.advanceFloor(birth, floor);

    assertThat(metadata.advanceFloor(advanced, floor)).isEqualTo(advanced);
    assertThatThrownBy(
        () -> metadata.advanceFloor(
            advanced, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rewind");
    assertThatThrownBy(
        () -> metadata.advanceFloor(
            advanced, new LogicalSequenceFloor(SOURCE_STORAGE, LINEAGE_ONE, 9)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity");
  }

  /** Lineage replacement rejects a birth that has not become active. */
  @Test
  public void lineageReplacementRequiresActiveStorage() throws IOException {
    final var birth = metadata().createBirth(STORAGE, LINEAGE_ONE);

    assertThatThrownBy(() -> metadata().beginLineageReplacement(birth, adoption(20)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Lineage replacement requires active storage");
  }

  /** Production replacement generates a lineage distinct from both inputs and retains the floor. */
  @Test
  public void lineageReplacementRetainsTargetHighWater() throws IOException {
    final var metadata = metadata();
    final var birth = metadata.createBirth(STORAGE, LINEAGE_ONE);
    final var highTarget =
        metadata.advanceFloor(birth, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 50));
    final var active = metadata.activate(highTarget);

    final var replacement = metadata.beginLineageReplacement(active, adoption(20));
    final var newLineage = replacement.lineageIdentity();
    assertThat(newLineage).isNotEqualTo(LINEAGE_ONE).isNotEqualTo(SOURCE_LINEAGE);
    assertThat(replacement.sequenceFloor())
        .isEqualTo(new LogicalSequenceFloor(STORAGE, newLineage, 50));
  }

  /** A current-lineage collision is retried, and the next fresh candidate is returned. */
  @Test
  public void freshLineageRetriesCurrentLineageCollision() {
    final var candidates = List.of(LINEAGE_ONE, LINEAGE_THREE).iterator();

    assertThat(
        StorageBootstrapMetadata.generateFreshLineage(
            candidates::next, LINEAGE_ONE, SOURCE_LINEAGE))
        .isEqualTo(LINEAGE_THREE);
  }

  /** A source-lineage collision is retried, and the next fresh candidate is returned. */
  @Test
  public void freshLineageRetriesSourceLineageCollision() {
    final var candidates = List.of(SOURCE_LINEAGE, LINEAGE_THREE).iterator();

    assertThat(
        StorageBootstrapMetadata.generateFreshLineage(
            candidates::next, LINEAGE_ONE, SOURCE_LINEAGE))
        .isEqualTo(LINEAGE_THREE);
  }

  /** Three colliding candidates exhaust the fixed retry bound and report the collision cause. */
  @Test
  public void freshLineageRejectsCollisionExhaustion() {
    final var attempts = new AtomicInteger();

    assertThatThrownBy(
        () -> StorageBootstrapMetadata.generateFreshLineage(
            () -> attempts.getAndIncrement() % 2 == 0 ? LINEAGE_ONE : SOURCE_LINEAGE,
            LINEAGE_ONE,
            SOURCE_LINEAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("all 3 candidates collided")
        .hasMessageContaining("current or source lineage identity");
    assertThat(attempts).hasValue(3);
  }

  /** Every null generation input is rejected with the protected parameter's name. */
  @Test
  public void freshLineageRejectsNullInputs() {
    assertThatThrownBy(
        () -> StorageBootstrapMetadata.generateFreshLineage(null, LINEAGE_ONE, SOURCE_LINEAGE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("candidateSource");
    assertThatThrownBy(
        () -> StorageBootstrapMetadata.generateFreshLineage(() -> LINEAGE_THREE, null,
            SOURCE_LINEAGE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("currentLineage");
    assertThatThrownBy(
        () -> StorageBootstrapMetadata.generateFreshLineage(() -> LINEAGE_THREE, LINEAGE_ONE, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sourceLineage");
    assertThatThrownBy(
        () -> StorageBootstrapMetadata.generateFreshLineage(() -> null, LINEAGE_ONE,
            SOURCE_LINEAGE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("candidateSource returned null");
  }

  /** Recovery accepts the active-to-restore successor that starts a fresh lineage. */
  @Test
  public void recoveryAcceptsLineageReplacementSuccessor() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var replacement = creator.beginLineageReplacement(active, adoption(20));

    assertThat(metadata().readRequired()).isEqualTo(replacement);
  }

  /** Open rejects an interrupted restore instead of accepting partially replaced content. */
  @Test
  public void openRejectsInterruptedRestore() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    creator.beginLineageReplacement(active, adoption(20));

    final var rejection = admissionRejection(metadata()::readActiveRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_RESTORE);
    assertThat(rejection).hasMessageContaining("Interrupted storage restore");
  }

  /** A failed replacement token permits another restore attempt under the pending lineage. */
  @Test
  public void failedLineageReplacementCanBeRetried() throws IOException {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final var failedReplacement = metadata.beginLineageReplacement(active, adoption(20));

    final var retry = metadata.beginLineageReplacement(failedReplacement, adoption(30));
    final var completed = metadata.activate(retry);

    assertThat(retry).isEqualTo(failedReplacement);
    assertThat(completed.state()).isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
    assertThat(completed.lineageIdentity()).isEqualTo(failedReplacement.lineageIdentity());
  }

  /** A FIFO at the lock path is rejected before FileChannel.open can block. */
  @Test(timeout = 10_000)
  public void specialLockResourceFailsPromptly() throws Exception {
    Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
    final var lockPath = directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME);
    final var process = new ProcessBuilder("mkfifo", lockPath.toString()).start();
    Assume.assumeTrue("mkfifo is unavailable", process.waitFor() == 0);

    final var rejection = admissionRejection(metadata()::readRequired);
    assertThat(rejection.reason()).isEqualTo(Reason.NON_REGULAR_AUTHORITY_FILE);
    assertThat(rejection).hasMessageContaining("lock is not a regular file");
  }

  /** Process lock entries exist only while an operation uses them. */
  @Test
  public void processLockRetentionIsBoundedByActiveOperations() throws IOException {
    for (var i = 0; i < 100; i++) {
      final var child = Files.createDirectory(directory.resolve("storage-" + i));
      assertThatThrownBy(() -> new StorageBootstrapMetadata(child, FORMAT).readRequired())
          .isInstanceOf(IOException.class);
      assertThat(StorageBootstrapMetadata.processLockCountForTests()).isZero();
    }
  }

  /** A symbolic directory alias shares the same process publication exclusion domain. */
  @Test
  public void pathAliasesSerializePublication() throws Exception {
    final var alias = directory.resolveSibling(directory.getFileName() + "-alias");
    try {
      Files.createSymbolicLink(alias, directory);
    } catch (UnsupportedOperationException | IOException e) {
      Assume.assumeNoException("Symbolic links are not supported", e);
    }
    try {
      final var started = new CountDownLatch(1);
      final var proceed = new CountDownLatch(1);
      final var moves = new AtomicInteger();
      final var blocking =
          new StorageBootstrapMetadata(
              directory,
              FORMAT,
              (source, target, requester) -> {
                if (moves.incrementAndGet() == 2) {
                  started.countDown();
                  await(proceed);
                }
                FileUtils.durableAtomicMove(source, target, requester);
              });
      final var birth = blocking.createBirth(STORAGE, LINEAGE_ONE);
      final var throughAlias = new StorageBootstrapMetadata(alias, FORMAT);

      try (var executor = Executors.newFixedThreadPool(2)) {
        final var activation = executor.submit(() -> blocking.activate(birth));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        final var read = executor.submit(throughAlias::readRequired);
        assertThat(read.isDone()).isFalse();
        proceed.countDown();
        assertThat(activation.get(10, TimeUnit.SECONDS).state())
            .isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
        assertThat(read.get(10, TimeUnit.SECONDS).state())
            .isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
      }
    } finally {
      Files.deleteIfExists(alias);
    }
  }

  /** Generation exhaustion fails before a wrapped record can be published. */
  @Test
  public void generationExhaustionIsRejected() throws Exception {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] record = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(record).putLong(16, Long.MAX_VALUE);
    updateChecksum(record);
    Files.write(authorityPath(1), record);
    final var exhausted = metadata().readRequired();

    assertThatThrownBy(
        () -> metadata().advanceFloor(
            exhausted, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation is exhausted");
    assertThat(active.state()).isEqualTo(StorageBootstrapMetadata.State.ACTIVE);
  }

  /**
   * A restore target publishes the restore-in-progress state directly from the birth state.
   *
   * <p>The scenario creates one birth record and publishes the restore-in-progress lifecycle state
   * without any activation. The expected outcome has three parts. The published record carries the
   * restore-in-progress lifecycle state. The published record keeps the storage identity, the
   * storage lineage, and the logical sequence floor of the birth record. A later read accepts the
   * successor, which proves that the lifecycle transition table allows the new transition.
   */
  @Test
  public void restoreTargetPublishesRestoreStateFromBirthState() throws IOException {
    final var creator = metadata();
    final var birth = creator.createBirth(STORAGE, LINEAGE_ONE);

    final var restoreTarget = creator.beginRestoreFromBirth(birth);

    assertThat(restoreTarget.state())
        .isEqualTo(StorageBootstrapMetadata.State.RESTORE_IN_PROGRESS);
    assertThat(restoreTarget.generation()).isEqualTo(birth.generation() + 1);
    assertThat(restoreTarget.sequenceFloor()).isEqualTo(birth.sequenceFloor());
    assertThat(metadata().readRequired()).isEqualTo(restoreTarget);
  }

  /**
   * The restore transition of one target is repeat safe.
   *
   * <p>A restore of one target can start a second time after a failed first attempt. The scenario
   * publishes the restore-in-progress lifecycle state twice for the same target. The expected
   * outcome has three parts. The second call returns the snapshot of the first call. The second
   * call publishes no new generation. The durable record still holds the first published record.
   */
  @Test
  public void restoreTransitionIsRepeatSafeForTheSameTarget() throws IOException {
    final var creator = metadata();
    final var first = creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));

    final var second = creator.beginRestoreFromBirth(first);

    assertThat(second).isEqualTo(first);
    assertThat(second.generation()).isEqualTo(first.generation());
    assertThat(metadata().readRequired()).isEqualTo(first);
  }

  /**
   * The new transition refuses a record that no longer carries the birth-in-progress state.
   *
   * <p>The scenario activates one birth record and then asks for the restore transition. The
   * expected outcome is one refusal that names the required source state.
   */
  @Test
  public void restoreTransitionRequiresTheBirthState() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));

    assertThatThrownBy(() -> creator.beginRestoreFromBirth(active))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("birth-in-progress state only");
  }

  /**
   * The restart deletion removes every content file before the authority copies.
   *
   * <p>A destructive restart is the full deletion of an interrupted restore target and a fresh
   * restore. The scenario deletes one restore target and inspects the directory from inside the
   * content deletion. The expected outcome has four parts. Every authority copy still exists
   * while the content deletion runs. The content file is gone after the deletion. No authority
   * copy and no unfinished record candidate survives the deletion. The authority lock file stays
   * in place, because an unlink of that file would split the exclusive unit across two file
   * objects.
   */
  @Test
  public void restartDeletionRemovesAuthorityCopiesAfterContent() throws IOException {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var contentFile = Files.createFile(directory.resolve("content.pcl"));
    final var authorityCountBeforeDeletion = authorityFiles().size();
    final var authorityCountDuringContentDeletion = new AtomicInteger(-1);

    creator.deleteInterruptedRestoreTarget(
        storageDirectory -> {
          authorityCountDuringContentDeletion.set(authorityFiles().size());
          Files.delete(contentFile);
        });

    assertThat(authorityCountBeforeDeletion).isPositive();
    assertThat(authorityCountDuringContentDeletion.get()).isEqualTo(authorityCountBeforeDeletion);
    assertThat(Files.exists(contentFile)).isFalse();
    assertThat(authorityFiles()).isEmpty();
    assertThat(temporaryFiles()).isEmpty();
    assertThat(Files.exists(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME))).isTrue();
  }

  /**
   * The restart deletion unlinks the copy of the newest record last.
   *
   * <p>A generation is a counter inside the bootstrap authority record, and a higher value means a
   * newer record. A crash inside the copy loop must keep the newest record on disk, because that
   * newest record carries the restore-in-progress lifecycle state. A deletion in file-name order
   * would break that property for one reachable slot layout.
   *
   * <p>The scenario builds the slot layout of an in-place restore. That layout writes the restore
   * record into the first slot and keeps the older active record in the second slot and in the
   * third slot. The scenario then stops the deletion after two unlinked copies. The expected
   * outcome has three parts. The single remaining copy is the copy of the first slot. That
   * remaining copy carries the restore record. A later restart still accepts the target.
   */
  @Test
  public void restartDeletionUnlinksTheNewestAuthorityCopyLast() throws IOException {
    final var creator = metadata();
    final var active = creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    // A second instance confirms the active record into the third slot.
    metadata().readRequired();
    final var restore = creator.beginLineageReplacement(active, adoption(20));
    assertThat(readGeneration(authorityPath(0))).isEqualTo(restore.generation());
    assertThat(readGeneration(authorityPath(1))).isEqualTo(active.generation());
    assertThat(readGeneration(authorityPath(2))).isEqualTo(active.generation());

    final var unlinkedCopies = new AtomicInteger();
    final var deletionStop = new IOException("the deletion stops inside the authority copy loop");
    assertThatThrownBy(
        () -> creator.deleteInterruptedRestoreTarget(
            storageDirectory -> {
            },
            copy -> {
              if (unlinkedCopies.getAndIncrement() == 2) {
                throw deletionStop;
              }
              Files.deleteIfExists(copy);
            }))
        .isSameAs(deletionStop);

    assertThat(authorityFiles()).containsExactly(authorityPath(0));
    assertThat(readGeneration(authorityPath(0))).isEqualTo(restore.generation());
    // The remaining copy still proves an interrupted restore, so a later restart repeats.
    metadata().requireInterruptedRestoreTarget();
  }

  /**
   * The interrupted-birth message names both causes of the state and advises no single deletion.
   *
   * <p>A complete database whose every authority copy fails the integrity check reaches the
   * interrupted-birth reason. The message of that reason therefore must not advise the drop of the
   * database as the single course of action. The scenario damages every copy of one active image.
   * The expected outcome has three parts. The rejection reports the interrupted-birth reason. The
   * message names the interrupted creation and names the damaged records. The message offers the
   * backup of a complete database next to the drop of an incomplete database.
   */
  @Test
  public void interruptedBirthMessageNamesBothCausesAndOffersARestore() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    for (var index = 0; index < 2; index++) {
      final byte[] damaged = Files.readAllBytes(authorityPath(index));
      ByteBuffer.wrap(damaged).putLong(72, 0);
      Files.write(authorityPath(index), damaged);
    }

    final var rejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    final var description = Reason.INTERRUPTED_BIRTH.description();
    assertThat(description).contains("An interrupted creation produces this state");
    assertThat(description).contains("Damaged bootstrap authority records");
    assertThat(description).contains("A backup restores a complete database");
    assertThat(description).doesNotContain("Drop the database, and create the database again");
  }

  /**
   * The restart refuses an active target and keeps every file of that target.
   *
   * <p>The scenario asks for the restart deletion of an active image. The expected outcome has two
   * parts. The refusal names the reason for a target that is no interrupted restore. The content
   * file and the authority copies survive the refusal.
   */
  @Test
  public void restartRefusesActiveTarget() throws IOException {
    final var creator = metadata();
    creator.activate(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var contentFile = Files.createFile(directory.resolve("content.pcl"));

    final var rejection =
        admissionRejection(
            () -> creator.deleteInterruptedRestoreTarget(
                storageDirectory -> Files.delete(contentFile)));

    assertThat(rejection.reason()).isEqualTo(Reason.RESTART_TARGET_NOT_RESTORE_IN_PROGRESS);
    assertThat(rejection).hasMessageContaining("restore-in-progress lifecycle state only");
    assertThat(Files.exists(contentFile)).isTrue();
    assertThat(authorityFiles()).isNotEmpty();
  }

  /**
   * The restart refuses an interrupted storage birth and keeps every file of that image.
   *
   * <p>The scenario asks for the restart deletion of a birth record. The expected outcome has two
   * parts. The refusal names the interrupted-birth reason, which a drop tolerates. The authority
   * copy survives the refusal.
   */
  @Test
  public void restartRefusesInterruptedBirthTarget() throws IOException {
    final var creator = metadata();
    creator.createBirth(STORAGE, LINEAGE_ONE);

    final var rejection =
        admissionRejection(
            () -> creator.deleteInterruptedRestoreTarget(storageDirectory -> {
            }));

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    assertThat(authorityFiles()).isNotEmpty();
  }

  /**
   * A same-process holder of the normal database lock prevents every restart mutation.
   *
   * <p>The scenario holds the existing dirty-file lock in this virtual machine and asks for a
   * restart deletion. The expected outcome has four parts. The restart reports a busy target. The
   * content callback never runs. Every original byte and authority copy survives. After lock
   * release, the same deletion succeeds.
   */
  @Test
  public void restartRefusesOverlappingNormalDatabaseLockAndSucceedsAfterRelease()
      throws IOException {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var normalLockFile =
        Files.writeString(
            directory.resolve(StorageBootstrapMetadata.NORMAL_DATABASE_LOCK_FILE_NAME),
            "dirty-metadata");
    final var contentFile = Files.writeString(directory.resolve("content.pcl"), "payload");
    final var authorityBeforeRefusal = authorityBytes();
    final var deletionRan = new AtomicBoolean(false);

    try (var channel =
        FileChannel.open(
            normalLockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        var ignored = channel.lock()) {
      final var rejection =
          admissionRejection(
              () -> creator.deleteInterruptedRestoreTarget(
                  storageDirectory -> deletionRan.set(true)));

      assertThat(rejection.reason()).isEqualTo(Reason.RESTART_TARGET_BUSY);
      assertThat(Reason.RESTART_TARGET_BUSY.description())
          .isEqualTo(
              "The interrupted restore target is busy, so the destructive restart refuses the"
                  + " database.");
      assertThat(rejection).hasMessageContaining("normal database lock is busy");
      assertThat(deletionRan.get()).isFalse();
      assertThat(Files.readString(normalLockFile)).isEqualTo("dirty-metadata");
      assertThat(Files.readString(contentFile)).isEqualTo("payload");
      assertAuthorityBytesUnchanged(authorityBeforeRefusal);
    }

    creator.deleteInterruptedRestoreTarget(
        storageDirectory -> {
          Files.delete(contentFile);
          Files.delete(normalLockFile);
        });
    assertThat(Files.exists(contentFile)).isFalse();
    assertThat(Files.exists(normalLockFile)).isFalse();
  }

  /**
   * A foreign-process holder of the normal database lock prevents every restart mutation.
   *
   * <p>The scenario starts one small Java helper process and waits for its bounded ready
   * handshake. The expected outcome has four parts. The restart reports a busy target without
   * waiting. Every original byte and authority copy survives. The helper exits after releasing its
   * lock. The same deletion then succeeds.
   */
  @Test(timeout = 60_000)
  public void restartRefusesForeignProcessNormalDatabaseLockAndSucceedsAfterRelease()
      throws Exception {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var normalLockFile =
        Files.writeString(
            directory.resolve(StorageBootstrapMetadata.NORMAL_DATABASE_LOCK_FILE_NAME),
            "dirty-metadata");
    final var contentFile = Files.writeString(directory.resolve("content.pcl"), "payload");
    final var authorityBeforeRefusal = authorityBytes();
    final var lockHolder = startForeignLockHolder(normalLockFile);
    try {
      assertThat(awaitProcessLine(lockHolder)).isEqualTo("READY");

      final var rejection =
          admissionRejection(
              () -> creator.deleteInterruptedRestoreTarget(
                  storageDirectory -> {
                    Files.delete(contentFile);
                    Files.delete(normalLockFile);
                  }));

      assertThat(rejection.reason()).isEqualTo(Reason.RESTART_TARGET_BUSY);
      assertThat(Files.readString(normalLockFile)).isEqualTo("dirty-metadata");
      assertThat(Files.readString(contentFile)).isEqualTo("payload");
      assertAuthorityBytesUnchanged(authorityBeforeRefusal);
      lockHolder.outputWriter().write("release\n");
      lockHolder.outputWriter().flush();
      assertThat(lockHolder.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(lockHolder.exitValue()).isZero();
    } finally {
      stopProcess(lockHolder);
    }

    creator.deleteInterruptedRestoreTarget(
        storageDirectory -> {
          Files.delete(contentFile);
          Files.delete(normalLockFile);
        });
    assertThat(Files.exists(contentFile)).isFalse();
    assertThat(Files.exists(normalLockFile)).isFalse();
  }

  /**
   * An unavailable normal database lock file refuses the restart before mutation.
   *
   * <p>The scenario puts a directory at the normal lock path, so the path cannot provide a file
   * lock. The expected outcome has three parts. An input or output failure reaches the caller. The
   * content callback never runs. Every authority copy and the unavailable path survive.
   */
  @Test
  public void restartRefusesUnavailableNormalDatabaseLockBeforeMutation() throws IOException {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var unavailableLockPath =
        Files.createDirectory(
            directory.resolve(StorageBootstrapMetadata.NORMAL_DATABASE_LOCK_FILE_NAME));
    final var authorityBeforeRefusal = authorityBytes();
    final var deletionRan = new AtomicBoolean(false);

    assertThatThrownBy(
        () -> creator.deleteInterruptedRestoreTarget(
            storageDirectory -> deletionRan.set(true)))
        .isInstanceOf(IOException.class);

    assertThat(deletionRan.get()).isFalse();
    assertThat(unavailableLockPath).isDirectory();
    assertAuthorityBytesUnchanged(authorityBeforeRefusal);
  }

  /**
   * A content-deletion failure releases the acquired normal database lock.
   *
   * <p>The scenario proves that the lock remains held inside the content callback and then throws
   * from that callback. The expected outcome has three parts. The same failure reaches the caller.
   * A later lock attempt succeeds, proving cleanup. A repeated restart deletion then succeeds.
   */
  @Test
  public void restartDeletionFailureReleasesNormalDatabaseLock() throws IOException {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var normalLockFile =
        Files.writeString(
            directory.resolve(StorageBootstrapMetadata.NORMAL_DATABASE_LOCK_FILE_NAME),
            "dirty-metadata");
    final var contentFile = Files.writeString(directory.resolve("content.pcl"), "payload");
    final var deletionFailure = new IOException("content deletion failed");

    assertThatThrownBy(
        () -> creator.deleteInterruptedRestoreTarget(
            storageDirectory -> {
              try (var competingChannel =
                  FileChannel.open(
                      normalLockFile,
                      StandardOpenOption.READ,
                      StandardOpenOption.WRITE)) {
                assertThatThrownBy(competingChannel::tryLock)
                    .isInstanceOf(OverlappingFileLockException.class);
              }
              throw deletionFailure;
            }))
        .isSameAs(deletionFailure);

    try (var channel =
        FileChannel.open(
            normalLockFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        var acquiredAfterFailure = channel.tryLock()) {
      assertThat(acquiredAfterFailure).isNotNull();
    }
    creator.deleteInterruptedRestoreTarget(
        storageDirectory -> {
          Files.delete(contentFile);
          Files.delete(normalLockFile);
        });
    assertThat(authorityFiles()).isEmpty();
  }

  /**
   * A missing normal lock file remains valid repeatable restart-deletion residue.
   *
   * <p>The scenario deletes a restore target that never gained a dirty file and repeats the
   * deletion on the authority-lock-only result. The expected outcome has two parts. Both deletions
   * run without creating a dirty file. The authority lock file survives both deletions.
   */
  @Test
  public void restartAcceptsMissingNormalLockAcrossRepeatedDeletion() throws IOException {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var deletionCount = new AtomicInteger();

    creator.deleteInterruptedRestoreTarget(
        storageDirectory -> deletionCount.incrementAndGet());
    metadata().deleteInterruptedRestoreTarget(
        storageDirectory -> deletionCount.incrementAndGet());

    assertThat(deletionCount.get()).isEqualTo(2);
    assertThat(
        Files.exists(
            directory.resolve(StorageBootstrapMetadata.NORMAL_DATABASE_LOCK_FILE_NAME)))
        .isFalse();
    assertThat(Files.exists(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME))).isTrue();
  }

  /**
   * The restart refuses a directory that holds no bootstrap authority artifact at all.
   *
   * <p>Such a directory can hold a database of a format that carries no bootstrap authority
   * record. The scenario asks for the restart deletion of a directory that holds one content file
   * only. The expected outcome has three parts. The refusal names the missing-record reason. The
   * content deletion never runs. Every file of the directory survives the refusal.
   */
  @Test
  public void restartRefusesDirectoryWithoutAnyBootstrapArtifact() throws IOException {
    final var contentFile = Files.createFile(directory.resolve("content.pcl"));
    final var deletionRan = new AtomicBoolean(false);

    final var rejection =
        admissionRejection(
            () -> metadata().deleteInterruptedRestoreTarget(
                storageDirectory -> deletionRan.set(true)));

    assertThat(rejection.reason()).isEqualTo(Reason.AUTHORITY_MISSING);
    assertThat(deletionRan.get()).isFalse();
    assertThat(Files.exists(contentFile)).isTrue();
    assertThat(Files.exists(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME))).isFalse();
  }

  /**
   * The restart refuses a directory that holds content files and no readable authority record.
   *
   * <p>The deletion of a restart removes every content file before the authority copies, so a
   * content file can never survive the loss of every authority copy. The scenario builds that
   * impossible shape with one content file and the authority lock file. The expected outcome has
   * three parts. The refusal names the interrupted-birth reason. The content deletion never runs.
   * The content file survives the refusal.
   */
  @Test
  public void restartRefusesContentWithoutAnyReadableRecord() throws IOException {
    Files.createFile(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME));
    final var contentFile = Files.createFile(directory.resolve("content.pcl"));
    final var deletionRan = new AtomicBoolean(false);

    final var rejection =
        admissionRejection(
            () -> metadata().deleteInterruptedRestoreTarget(
                storageDirectory -> deletionRan.set(true)));

    assertThat(rejection.reason()).isEqualTo(Reason.INTERRUPTED_BIRTH);
    assertThat(deletionRan.get()).isFalse();
    assertThat(Files.exists(contentFile)).isTrue();
  }

  /**
   * A rejected admission reports the same reason for the same unchanged image twice.
   *
   * <p>An admission that creates the authority lock file would turn a directory without any
   * bootstrap authority artifact into birth residue. The second read of the same directory would
   * then report the interrupted-birth reason instead of the missing-record reason. The scenario
   * reads one directory with one content file twice. The expected outcome has two parts. Both
   * reads report the missing-record reason. The read creates no authority lock file.
   */
  @Test
  public void rejectedAdmissionReportsTheSameReasonAcrossTwoReads() throws IOException {
    Files.createFile(directory.resolve("content.pcl"));

    final var firstRejection = admissionRejection(metadata()::readActiveRequired);
    final var secondRejection = admissionRejection(metadata()::readActiveRequired);

    assertThat(firstRejection.reason()).isEqualTo(Reason.AUTHORITY_MISSING);
    assertThat(secondRejection.reason()).isEqualTo(Reason.AUTHORITY_MISSING);
    assertThat(Files.exists(directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME))).isFalse();
  }

  /**
   * The acceptance check and the deletion of one restart exclude a concurrent restart.
   *
   * <p>The scenario starts one restart deletion and holds that deletion inside the content
   * deletion. A second thread then starts another restart deletion of the same target. The
   * expected outcome has two parts. The second restart makes no progress while the first restart
   * holds the exclusive unit. The second restart finishes after the first restart released the
   * exclusive unit.
   */
  @Test(timeout = 60_000)
  public void restartAcceptanceAndDeletionExcludeAConcurrentRestart() throws Exception {
    final var creator = metadata();
    creator.beginRestoreFromBirth(creator.createBirth(STORAGE, LINEAGE_ONE));
    final var firstEnteredDeletion = new CountDownLatch(1);
    final var releaseFirstDeletion = new CountDownLatch(1);
    final var secondDeletionEntered = new AtomicBoolean(false);
    final var executor = Executors.newSingleThreadExecutor();
    try {
      final var second = new AtomicReference<Throwable>();
      final var secondFinished = new CountDownLatch(1);
      creator.deleteInterruptedRestoreTarget(
          storageDirectory -> {
            executor.execute(
                () -> {
                  try {
                    new StorageBootstrapMetadata(directory, FORMAT)
                        .deleteInterruptedRestoreTarget(
                            ignored -> secondDeletionEntered.set(true));
                  } catch (Throwable failure) {
                    second.set(failure);
                  } finally {
                    secondFinished.countDown();
                  }
                });
            firstEnteredDeletion.countDown();
            // The second restart must not enter its own deletion while this deletion runs.
            assertNoCompletionWithin(secondFinished);
            assertThat(secondDeletionEntered.get()).isFalse();
            releaseFirstDeletion.countDown();
          });

      await(firstEnteredDeletion);
      assertThat(secondFinished.await(30, TimeUnit.SECONDS)).isTrue();
      // The second restart runs after the first restart removed every authority copy, so the
      // second restart accepts a target without any readable record and deletes nothing more.
      assertThat(second.get()).isNull();
      assertThat(authorityFiles()).isEmpty();
    } finally {
      releaseFirstDeletion.countDown();
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /** Value objects reject null identities and invalid numeric values before persistence. */
  @Test
  public void identityValueObjectsRejectInvalidValues() {
    assertThatThrownBy(() -> new FeatureFormatIdentity(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageIdentity(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StorageLineageIdentity(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private StorageBootstrapMetadata metadata() throws IOException {
    return new StorageBootstrapMetadata(directory, FORMAT);
  }

  private List<Path> authorityFiles() {
    return Stream.iterate(0, i -> i + 1)
        .limit(StorageBootstrapMetadata.AUTHORITY_FILE_NAMES.size())
        .map(this::authorityPath)
        .filter(Files::exists)
        .toList();
  }

  private Map<Path, byte[]> authorityBytes() throws IOException {
    final var bytes = new LinkedHashMap<Path, byte[]>();
    for (final var path : authorityFiles()) {
      bytes.put(path, Files.readAllBytes(path));
    }
    return bytes;
  }

  private void assertAuthorityBytesUnchanged(final Map<Path, byte[]> expected) throws IOException {
    assertThat(authorityFiles()).containsExactlyInAnyOrderElementsOf(expected.keySet());
    for (final var entry : expected.entrySet()) {
      assertThat(Files.readAllBytes(entry.getKey())).containsExactly(entry.getValue());
    }
  }

  private List<Path> temporaryFiles() {
    return Stream.iterate(0, i -> i + 1)
        .limit(StorageBootstrapMetadata.AUTHORITY_FILE_NAMES.size())
        .map(this::temporaryPath)
        .filter(Files::exists)
        .toList();
  }

  private Path authorityPath(final int index) {
    return directory.resolve(StorageBootstrapMetadata.AUTHORITY_FILE_NAMES.get(index));
  }

  private Path temporaryPath(final int index) {
    final var authority = authorityPath(index);
    return authority.resolveSibling(authority.getFileName() + ".tmp");
  }

  private LineageFloorAdoption adoption(final long highestIssued) {
    return new LineageFloorAdoption(
        FORMAT, new LogicalSequenceFloor(SOURCE_STORAGE, SOURCE_LINEAGE, highestIssued));
  }

  private static long readGeneration(final Path path) throws IOException {
    return ByteBuffer.wrap(Files.readAllBytes(path)).getLong(16);
  }

  private static int readStorageLayoutVersion(final Path path) throws IOException {
    return ByteBuffer.wrap(Files.readAllBytes(path)).getInt(28);
  }

  /** Runs the given call and returns its single named admission rejection. */
  private static StorageAdmissionException admissionRejection(
      final org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    final var rejection = catchThrowableOfType(call, StorageAdmissionException.class);
    assertThat(rejection).isNotNull();
    return rejection;
  }

  private static void updateChecksum(final byte[] record) {
    final var checksum =
        XXHashFactory.fastestInstance().hash64().hash(record, 0, 72, 0x648B7A2195D3L);
    ByteBuffer.wrap(record).putLong(72, checksum);
  }

  /** Fails when the given latch completes, which would break the exclusivity of one restart. */
  private static void assertNoCompletionWithin(final CountDownLatch latch) throws IOException {
    try {
      if (latch.await(2, TimeUnit.SECONDS)) {
        throw new IOException("A concurrent restart entered the exclusive unit of one restart");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while the exclusivity proof waited", e);
    }
  }

  private static void await(final CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IOException("Timed out while holding publication");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while holding publication", e);
    }
  }

  private static Process startForeignLockHolder(final Path lockPath) throws IOException {
    final var javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", "java").toString();
    final var testClasspath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return new ProcessBuilder(
        javaExecutable,
        "-cp",
        testClasspath,
        ForeignFileLockHolder.class.getName(),
        lockPath.toString())
        .redirectErrorStream(true)
        .start();
  }

  private static String awaitProcessLine(final Process process) throws Exception {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (process.inputReader().ready()) {
        return process.inputReader().readLine();
      }
      if (!process.isAlive()) {
        throw new IOException("Foreign lock holder exited before its ready handshake");
      }
      Thread.sleep(10);
    }
    throw new IOException("Timed out waiting for the foreign lock holder ready handshake");
  }

  private static void stopProcess(final Process process) throws InterruptedException {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(final Throwable failure) throws T {
    throw (T) failure;
  }

  private void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup after each deterministic test.
    }
  }
}
