package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Tests redundant bootstrap selection, publication, recovery, and birth ownership. */
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
  }

  /** The live creator can activate its token while another instance rejects the interrupted birth. */
  @Test
  public void onlyCreatingCallCanContinueDurableBirth() throws IOException {
    final var creator = metadata();
    final var birth = creator.createBirth(STORAGE, LINEAGE_ONE);
    final var laterOperation = metadata();
    final var discovered = laterOperation.readRequired();

    assertThatThrownBy(laterOperation::readActiveRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Interrupted storage birth");
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

  /** A failed publication removes its candidate and leaves selected authority readable. */
  @Test
  public void failedPublicationPreservesSelectedAuthority() throws IOException {
    final var creator = metadata();
    final var birth = creator.createBirth(STORAGE, LINEAGE_ONE);
    final var active = creator.activate(birth);
    final byte[] selectedBytes = Files.readAllBytes(authorityPath(1));
    final var failing =
        new StorageBootstrapMetadata(
            directory,
            FORMAT,
            (source, target, requester) -> {
              throw new IOException("injected publication failure");
            });

    assertThatThrownBy(
        () -> failing.advanceFloor(
            active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("injected");
    assertThat(Files.readAllBytes(authorityPath(1))).isEqualTo(selectedBytes);
    assertThat(temporaryFiles()).isEmpty();
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

  /** Residue present before a call is not removed and keeps recovery fail closed. */
  @Test
  public void preexistingCandidateResidueIsNeverRemoved() throws IOException {
    final var metadata = metadata();
    final var active = metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    Files.write(temporaryPath(2), new byte[] {1});

    assertThatThrownBy(
        () -> metadata.advanceFloor(
            active, new LogicalSequenceFloor(STORAGE, LINEAGE_ONE, 7)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("temporary");
    assertThat(Files.readAllBytes(temporaryPath(2))).containsExactly(1);
    assertThatThrownBy(metadata::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("temporary");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ambiguous");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ambiguous");
  }

  /** Consecutive valid generations with an illegal lifecycle transition fail closed. */
  @Test
  public void illegalStateTransitionIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] illegal = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(illegal).putInt(24, 3);
    updateChecksum(illegal);
    Files.write(authorityPath(1), illegal);

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("illegal state transition");
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

    assertThatThrownBy(metadata::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not a regular file");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid bootstrap authority magic: 7");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unsupported bootstrap encoding version: 99");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unsupported feature format version: 99");
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

    assertThatThrownBy(metadata::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Unsupported bootstrap state: 99");
  }

  /** A checksum-valid nonzero reserved field fails closed and names that value. */
  @Test
  public void nonzeroReservedFieldIsRejected() throws IOException {
    final var metadata = metadata();
    metadata.activate(metadata.createBirth(STORAGE, LINEAGE_ONE));
    final byte[] invalid = Files.readAllBytes(authorityPath(1));
    ByteBuffer.wrap(invalid).putInt(28, 99);
    updateChecksum(invalid);
    Files.write(authorityPath(1), invalid);

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid bootstrap authority reserved field: 99");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid bootstrap authority generation: 0");
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

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid bootstrap authority highest issued value: -1");
  }

  /** Temporary publication residue is never treated as confirmed authority. */
  @Test
  public void temporaryResidueFailsClosed() throws IOException {
    final var metadata = metadata();
    metadata.createBirth(STORAGE, LINEAGE_ONE);
    Files.write(temporaryPath(1), new byte[] {1});

    assertThatThrownBy(metadata::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("temporary");
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

  /** A FIFO at the lock path is rejected before FileChannel.open can block. */
  @Test(timeout = 10_000)
  public void specialLockResourceFailsPromptly() throws Exception {
    Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
    final var lockPath = directory.resolve(StorageBootstrapMetadata.LOCK_FILE_NAME);
    final var process = new ProcessBuilder("mkfifo", lockPath.toString()).start();
    Assume.assumeTrue("mkfifo is unavailable", process.waitFor() == 0);

    assertThatThrownBy(metadata()::readRequired)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("lock is not a regular file");
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

  private static void updateChecksum(final byte[] record) {
    final var checksum =
        XXHashFactory.fastestInstance().hash64().hash(record, 0, 72, 0x648B7A2195D3L);
    ByteBuffer.wrap(record).putLong(72, checksum);
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
