package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageAdmissionException.Reason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

/** Persists the redundant, format-neutral authority for a disk storage. */
public final class StorageBootstrapMetadata {

  static final String LOCK_FILE_NAME = "storage-bootstrap.bsml";
  static final String NORMAL_DATABASE_LOCK_FILE_NAME = "dirty.fl";
  static final List<String> AUTHORITY_FILE_NAMES =
      List.of(
          "storage-bootstrap-0.bsm", "storage-bootstrap-1.bsm", "storage-bootstrap-2.bsm");

  private static final long MAGIC = 0x5954444242534D31L;
  private static final int ENCODING_VERSION = 1;
  private static final int RECORD_SIZE = 80;

  /**
   * Names the single storage layout version that this build supports.
   *
   * <p>The storage layout version is the version of the on-disk storage layout. The record carries
   * that version in the field that carried no meaning before. The record size therefore stays at
   * {@link #RECORD_SIZE} bytes, and every field offset stays unchanged.
   */
  static final int SUPPORTED_STORAGE_LAYOUT_VERSION = StorageConfiguration.CURRENT_VERSION;

  private static final int CHECKSUM_OFFSET = RECORD_SIZE - Long.BYTES;
  private static final long XX_HASH_SEED = 0x648B7A2195D3L;
  private static final int LINEAGE_GENERATION_ATTEMPTS = 3;
  private static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();
  private static final Object PROCESS_LOCKS_MONITOR = new Object();
  private static final Map<Path, ProcessLock> PROCESS_LOCKS = new HashMap<>();
  private static final ThreadLocal<MoveStrategy> SCOPED_MOVE_STRATEGY = new ThreadLocal<>();

  private final Path directory;
  private final List<Path> authorityPaths;
  private final List<Path> temporaryPaths;
  private final Path lockPath;
  private final FeatureFormatIdentity expectedFormat;
  private final MoveStrategy moveStrategy;
  private final PublicationWarning publicationWarning;
  private final CandidateCleanup candidateCleanup;

  private Snapshot liveBirth;
  private Snapshot locallyConfirmed;

  /**
   * Records whether the authority lock file existed before the current locked operation.
   *
   * <p>A locked operation that establishes new authority creates the lock file when the lock file
   * is absent. The residue precedence rule must not treat that fresh lock file as birth residue.
   * The field therefore keeps the observation that the lock acquisition made before the lock
   * acquisition created the file.
   */
  private boolean lockFileExistedBeforeOperation;

  public StorageBootstrapMetadata(
      final Path storageDirectory, final FeatureFormatIdentity expectedFormat) throws IOException {
    this(
        storageDirectory,
        expectedFormat,
        SCOPED_MOVE_STRATEGY.get() == null
            ? FileUtils::durableAtomicMove
            : SCOPED_MOVE_STRATEGY.get());
  }

  /** Overrides new metadata moves on the current thread until the returned scope closes. */
  static AutoCloseable useMoveStrategyForCurrentThread(final MoveStrategy moveStrategy) {
    final var previous = SCOPED_MOVE_STRATEGY.get();
    SCOPED_MOVE_STRATEGY.set(Objects.requireNonNull(moveStrategy, "moveStrategy"));
    return () -> {
      if (previous == null) {
        SCOPED_MOVE_STRATEGY.remove();
      } else {
        SCOPED_MOVE_STRATEGY.set(previous);
      }
    };
  }

  StorageBootstrapMetadata(
      final Path storageDirectory,
      final FeatureFormatIdentity expectedFormat,
      final MoveStrategy moveStrategy)
      throws IOException {
    this(
        storageDirectory,
        expectedFormat,
        moveStrategy,
        StorageBootstrapMetadata::warnAboutFailedPublication,
        Files::deleteIfExists);
  }

  StorageBootstrapMetadata(
      final Path storageDirectory,
      final FeatureFormatIdentity expectedFormat,
      final MoveStrategy moveStrategy,
      final PublicationWarning publicationWarning,
      final CandidateCleanup candidateCleanup)
      throws IOException {
    this.directory = Objects.requireNonNull(storageDirectory, "storageDirectory").toRealPath();
    this.expectedFormat = Objects.requireNonNull(expectedFormat, "expectedFormat");
    this.moveStrategy = Objects.requireNonNull(moveStrategy, "moveStrategy");
    this.publicationWarning = Objects.requireNonNull(publicationWarning, "publicationWarning");
    this.candidateCleanup = Objects.requireNonNull(candidateCleanup, "candidateCleanup");
    this.authorityPaths =
        AUTHORITY_FILE_NAMES.stream().map(directory::resolve).toList();
    this.temporaryPaths =
        authorityPaths.stream().map(path -> path.resolveSibling(path.getFileName() + ".tmp"))
            .toList();
    this.lockPath = directory.resolve(LOCK_FILE_NAME);
  }

  /** Establishes the first durable birth authority and returns its live-creation token. */
  public Snapshot createBirth(
      final StorageIdentity storageIdentity, final StorageLineageIdentity lineageIdentity)
      throws IOException {
    Objects.requireNonNull(storageIdentity, "storageIdentity");
    Objects.requireNonNull(lineageIdentity, "lineageIdentity");

    return withAuthorityLock(
        () -> {
          if (anyPublicationResourceExists()) {
            throw new IOException("Bootstrap authority or publication residue already exists");
          }

          final var floor = new LogicalSequenceFloor(storageIdentity, lineageIdentity, 0);
          final var birth = new Snapshot(expectedFormat, 1, State.BIRTH_IN_PROGRESS, floor);
          publishTo(birth, authorityPaths.get(0), temporaryPaths.get(0));
          liveBirth = birth;
          locallyConfirmed = birth;
          return birth;
        });
  }

  /** Reads and confirms the newest unambiguous legal authority. */
  public Snapshot readRequired() throws IOException {
    return withAuthorityLock(false, this::readAndConfirmLocked);
  }

  /**
   * Decides admission of this storage image and returns the accepted authority snapshot.
   *
   * <p>This method is the single admission decision of storage open. Storage open calls this method
   * before write-ahead log initialization and before recovery. A write-ahead log records a change
   * before that change reaches its final file. Admission accepts only one unambiguous selected
   * snapshot. The accepted snapshot carries the active lifecycle state, a supported authority
   * encoding version, a supported authority feature format version, a supported storage layout
   * version, and a non-negative logical sequence floor. The decoder validates every version rule
   * and the floor rule, so this method repeats no check.
   *
   * <p>Repair runs only after acceptance. Repair removes each unfinished record candidate, then
   * reselects the publication target, and then confirms the selected snapshot. A repair failure is
   * logged and never fails the open.
   *
   * @throws StorageAdmissionException when one named cause rejects this storage image
   */
  public Snapshot readActiveRequired() throws StorageAdmissionException {
    try {
      return withAuthorityLock(
          false,
          () -> {
            final var admitted = admitLocked();
            repairAfterAdmissionLocked(admitted);
            return admitted.snapshot();
          });
    } catch (StorageAdmissionException admissionRejection) {
      throw admissionRejection;
    } catch (IOException inputOutputFailure) {
      // Every remaining low-level input and output failure carries one named admission reason.
      throw new StorageAdmissionException(
          Reason.AUTHORITY_IO_FAILURE,
          directory,
          "The bootstrap authority read failed before the admission decision",
          inputOutputFailure);
    }
  }

  /** Decides admission without any repair write. The caller already holds the authority lock. */
  private RecordAt admitLocked() throws IOException {
    final var selected = selectLocked(false);
    final var state = selected.snapshot().state();
    if (state == State.BIRTH_IN_PROGRESS) {
      throw admissionFailure(
          Reason.INTERRUPTED_BIRTH, "Interrupted storage birth must be removed before Open");
    }
    if (state == State.RESTORE_IN_PROGRESS) {
      throw admissionFailure(
          Reason.INTERRUPTED_RESTORE, "Interrupted storage restore must be retried before Open");
    }
    return selected;
  }

  /** Repairs the authority set after acceptance. A repair failure never fails the open. */
  private void repairAfterAdmissionLocked(final RecordAt admitted) {
    try {
      removeCandidateResidueLocked();
      confirmSelectedLocked(admitted);
    } catch (IOException | RuntimeException repairFailure) {
      LogManager.instance()
          .warn(
              this,
              "Bootstrap authority repair failed in directory %s after admission accepted the"
                  + " storage image: %s",
              repairFailure,
              directory,
              repairFailure);
    }
  }

  /** Advances the current lineage floor. A stale identity or lower floor is rejected. */
  public Snapshot advanceFloor(final Snapshot expected, final LogicalSequenceFloor requested)
      throws IOException {
    Objects.requireNonNull(requested, "requested");

    return withAuthorityLock(
        () -> {
          final var current = verifyExpected(expected);
          requireLiveBirthWhenPending(expected);
          verifyCurrentIdentity(current, requested);
          if (requested.highestIssued() < current.sequenceFloor().highestIssued()) {
            throw new IllegalStateException("Logical sequence floor cannot rewind");
          }
          if (requested.highestIssued() == current.sequenceFloor().highestIssued()) {
            // Preserve the identity-bearing token after verifyExpected decoded its durable copy.
            return current.state() == State.BIRTH_IN_PROGRESS ? liveBirth : current;
          }

          final var next = current.withFloor(requested, nextGeneration(current.generation()));
          publish(next);
          if (current.state() == State.BIRTH_IN_PROGRESS) {
            liveBirth = next;
          }
          return next;
        });
  }

  /** Activates a validated pending image without changing its identity or sequence floor. */
  public Snapshot activate(final Snapshot expected) throws IOException {
    return withAuthorityLock(
        () -> {
          final var current = verifyExpected(expected);
          requireLiveBirthWhenPending(expected);
          if (current.state() != State.BIRTH_IN_PROGRESS
              && current.state() != State.RESTORE_IN_PROGRESS) {
            throw new IllegalStateException("Only a pending image can become active");
          }

          final var next =
              new Snapshot(
                  current.format(),
                  nextGeneration(current.generation()),
                  State.ACTIVE,
                  current.sequenceFloor());
          publish(next);
          if (current.state() == State.BIRTH_IN_PROGRESS) {
            liveBirth = null;
          }
          return next;
        });
  }

  /**
   * Publishes the restore-in-progress lifecycle state directly from the birth-in-progress state.
   *
   * <p>A restore target must never appear as an empty active database. Restore therefore creates
   * the target without genesis and without activation. Genesis is the creation of the initial
   * database metadata. This method publishes the restore-in-progress lifecycle state of that fresh
   * target, and this method keeps the storage identity, the storage lineage, and the logical
   * sequence floor of the birth record.
   *
   * @param expected the birth snapshot that the creation of this target published
   * @return the restore-in-progress snapshot of this target
   */
  public Snapshot beginRestoreFromBirth(final Snapshot expected) throws IOException {
    return withAuthorityLock(
        () -> {
          final var current = verifyExpected(expected);
          requireLiveBirthWhenPending(expected);
          if (current.state() == State.RESTORE_IN_PROGRESS) {
            // The transition is repeat safe. A retried restore of the same target therefore
            // publishes no second record and keeps the generation of the first publication.
            return current;
          }
          if (current.state() != State.BIRTH_IN_PROGRESS) {
            throw new IllegalStateException(
                "A restore target publishes the restore-in-progress state from the"
                    + " birth-in-progress state only");
          }

          final var next =
              new Snapshot(
                  current.format(),
                  nextGeneration(current.generation()),
                  State.RESTORE_IN_PROGRESS,
                  current.sequenceFloor());
          publish(next);
          // The birth ended with this publication, so no later call may continue that birth.
          liveBirth = null;
          return next;
        });
  }

  /**
   * Accepts an interrupted restore target and deletes that target inside one exclusive unit.
   *
   * <p>A destructive restart is the full deletion of an interrupted restore target and a fresh
   * restore. The acceptance check and the deletion run under the authority lock, so two concurrent
   * restarts never destroy a healthy restored database.
   *
   * <p>Before deletion, the restart attempts the existing normal database lock without waiting.
   * A busy lock refuses the restart before mutation. The probe never creates or replaces the lock
   * file, and an acquired lock remains held through content deletion. A missing lock file is valid
   * crash residue, so deletion proceeds without creating one.
   *
   * <p>The deletion follows one fixed order. The deletion removes every content file first, then
   * removes each unfinished record candidate, and then removes the authority copies. The authority
   * lock file stays in place forever, because an unlink of that file would split the exclusive
   * unit across two file objects. Every crash point of that order therefore leaves a target that a
   * later restart still accepts.
   *
   * <p>The authority copies leave in one further order. The copy that carries the newest record
   * leaves last. A crash inside the copy loop therefore keeps the newest record on disk, and that
   * newest record still carries the restore-in-progress lifecycle state. The safety of every crash
   * point of the copy loop follows from that order alone, and the safety needs no assumption about
   * the slot of any copy.
   *
   * @param contentDeletion removes every file of the target that is not a bootstrap artifact
   * @throws StorageAdmissionException when this target is no interrupted restore target
   */
  public void deleteInterruptedRestoreTarget(final ContentDeletion contentDeletion)
      throws IOException {
    deleteInterruptedRestoreTarget(contentDeletion, Files::deleteIfExists);
  }

  /**
   * Deletes one interrupted restore target and unlinks each authority copy through one seam.
   *
   * <p>A test replaces the seam of the authority copies and stops the deletion inside the copy
   * loop. The test then inspects the copies that the stopped deletion left behind.
   *
   * @param contentDeletion removes every file of the target that is not a bootstrap artifact
   * @param authorityCopyDeletion unlinks one authority copy of the target
   * @throws StorageAdmissionException when this target is no interrupted restore target
   */
  void deleteInterruptedRestoreTarget(
      final ContentDeletion contentDeletion, final AuthorityCopyDeletion authorityCopyDeletion)
      throws IOException {
    Objects.requireNonNull(contentDeletion, "contentDeletion");
    Objects.requireNonNull(authorityCopyDeletion, "authorityCopyDeletion");

    withAuthorityLock(
        false,
        () -> {
          requireRestoreTargetLocked();
          // The order of the copies comes from the records of the untouched target.
          final var orderedCopies = authorityCopyDeletionOrderLocked();
          deleteContentUnderNormalDatabaseLock(contentDeletion);
          removeCandidateResidueLocked();
          for (var path : orderedCopies) {
            authorityCopyDeletion.deleteAuthorityCopy(path);
          }
          liveBirth = null;
          locallyConfirmed = null;
          return null;
        });
  }

  /**
   * Deletes content while holding the existing normal database lock when that file remains.
   *
   * <p>The authority lock is already held. The normal lock attempt must not wait because restore
   * activation takes these locks in the opposite order. Opening without {@code CREATE} preserves
   * the accepted lock-file-only crash residue. A missing normal lock file also means an earlier
   * restart already deleted that content file, so deletion remains repeatable.
   */
  private void deleteContentUnderNormalDatabaseLock(final ContentDeletion contentDeletion)
      throws IOException {
    final var normalLockPath = directory.resolve(NORMAL_DATABASE_LOCK_FILE_NAME);
    final FileChannel normalLockChannel;
    try {
      normalLockChannel =
          FileChannel.open(
              normalLockPath,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException missingNormalLock) {
      contentDeletion.deleteEveryContentFile(directory);
      return;
    }

    try (normalLockChannel) {
      final FileLock normalLock;
      try {
        normalLock = normalLockChannel.tryLock();
      } catch (OverlappingFileLockException overlappingLock) {
        throw normalDatabaseLockBusy(overlappingLock);
      }
      if (normalLock == null) {
        throw normalDatabaseLockBusy(null);
      }
      try (normalLock) {
        contentDeletion.deleteEveryContentFile(directory);
      }
    }
  }

  private StorageAdmissionException normalDatabaseLockBusy(final Throwable cause) {
    return new StorageAdmissionException(
        Reason.RESTART_TARGET_BUSY,
        directory,
        "The destructive restore restart refuses a target whose normal database lock is busy",
        cause);
  }

  /**
   * Orders the authority copies of one deletion, and puts the newest record last.
   *
   * <p>A generation is a counter inside the bootstrap authority record, and a higher value means a
   * newer record. A copy without any readable record carries no accepting evidence, so such a copy
   * leaves first. Two copies of the same generation keep the order of the file names.
   *
   * @return every authority path of this image, ordered by generation, lowest generation first
   */
  private List<Path> authorityCopyDeletionOrderLocked() throws IOException {
    final Map<Path, Long> generationByPath = new HashMap<>();
    for (var record : readValidRecordsLocked()) {
      generationByPath.put(record.path(), record.snapshot().generation());
    }
    // Every readable generation is positive, so the value zero marks a copy without a record.
    return authorityPaths.stream()
        .sorted(Comparator.comparingLong(path -> generationByPath.getOrDefault(path, 0L)))
        .toList();
  }

  /**
   * Checks that this image is a valid destructive restart target and changes no durable content.
   *
   * <p>A destructive restart is the full deletion of an interrupted restore target and a fresh
   * restore. The caller runs this check before the caller discards any in-memory state of the
   * named database. A refused restart therefore keeps every file and every live session of a
   * healthy database.
   *
   * <p>This check is not the acceptance decision of the restart. The deletion repeats the same
   * check inside its own exclusive unit, so the acceptance and the deletion stay one exclusive
   * unit.
   *
   * @throws StorageAdmissionException when this image is no interrupted restore target
   */
  public void requireInterruptedRestoreTarget() throws IOException {
    withAuthorityLock(
        false,
        () -> {
          requireRestoreTargetLocked();
          return null;
        });
  }

  /**
   * Checks that this target belongs to a destructive restore restart.
   *
   * <p>The check accepts positive evidence of an interrupted restore only. The first accepted
   * shape is a readable authority record in the restore-in-progress lifecycle state. The second
   * accepted shape is one directory whose only entry is the authority lock file.
   *
   * <p>Two events produce that second shape. The deletion of an earlier restart produces the
   * second shape, because the deletion removes every content file first and every authority copy
   * afterwards. An interrupted storage birth also produces the second shape, because the birth
   * creates the authority lock file before the birth writes the first record. The check therefore
   * cannot name the event that produced the second shape.
   *
   * <p>The check accepts the second shape for both events on purpose. The second shape holds no
   * content file at all, so the restart destroys no data of any database. The restart then restores
   * the named backup into that directory.
   *
   * <p>The check rejects every other case with one named admission reason and leaves every file in
   * place. A directory without any bootstrap authority artifact is never a valid restart target. A
   * directory that still holds content files without a readable authority record is never a valid
   * restart target either, because such a directory can hold a database of an earlier format.
   */
  private void requireRestoreTargetLocked() throws IOException {
    final RecordAt selected;
    try {
      selected = selectLocked(false);
    } catch (StorageAdmissionException rejection) {
      if (rejection.reason() == Reason.INTERRUPTED_BIRTH && holdsOnlyTheAuthorityLockFile()) {
        // A crash in the tail of an earlier restart deletion produces this shape. An interrupted
        // storage birth produces the same shape. The shape holds no content file, so the restart
        // accepts the shape for both events.
        return;
      }
      throw rejection;
    }

    final var state = selected.snapshot().state();
    if (state == State.RESTORE_IN_PROGRESS) {
      return;
    }
    if (state == State.BIRTH_IN_PROGRESS) {
      throw admissionFailure(
          Reason.INTERRUPTED_BIRTH,
          "The destructive restore restart refuses an interrupted storage birth, which a drop"
              + " removes");
    }
    throw admissionFailure(
        Reason.RESTART_TARGET_NOT_RESTORE_IN_PROGRESS,
        "The destructive restore restart accepts the restore-in-progress lifecycle state only,"
            + " and the current lifecycle state is "
            + state);
  }

  /**
   * Starts replacement under a fresh target lineage while retaining the target high-water.
   *
   * <p>A target that already carries the restore-in-progress lifecycle state keeps its lineage.
   * The production restore path creates a fresh target. The birth publication of that fresh target
   * already generated a random storage lineage. That fresh target therefore needs no replacement.
   * The lifecycle transition table also allows no second restore-in-progress publication under a
   * new lineage, because the design allows exactly one new transition.
   *
   * @param expected the previously observed authority snapshot
   * @param adoption the source format and sequence floor to adopt
   * @return the restore-in-progress authority snapshot carrying the generated target lineage
   */
  public Snapshot beginLineageReplacement(
      final Snapshot expected, final LineageFloorAdoption adoption) throws IOException {
    Objects.requireNonNull(adoption, "adoption");

    return withAuthorityLock(
        () -> {
          final var current = verifyExpected(expected);
          if (!current.format().equals(adoption.format())) {
            throw new IllegalStateException("Cannot adopt a floor from another feature format");
          }
          if (current.state() == State.RESTORE_IN_PROGRESS) {
            // The target already carries a fresh lineage from its own birth publication.
            return current;
          }
          if (current.state() != State.ACTIVE) {
            throw new IllegalStateException("Lineage replacement requires active storage");
          }
          final var sourceFloor = adoption.sourceFloor();
          final var newLineage =
              generateFreshLineage(
                  StorageLineageIdentity::random,
                  current.lineageIdentity(),
                  sourceFloor.lineageIdentity());

          final var retainedFloor =
              Math.max(current.sequenceFloor().highestIssued(), sourceFloor.highestIssued());
          final var targetFloor =
              new LogicalSequenceFloor(current.storageIdentity(), newLineage, retainedFloor);
          final var next =
              new Snapshot(
                  current.format(),
                  nextGeneration(current.generation()),
                  State.RESTORE_IN_PROGRESS,
                  targetFloor);
          publish(next);
          return next;
        });
  }

  static StorageLineageIdentity generateFreshLineage(
      final Supplier<StorageLineageIdentity> candidateSource,
      final StorageLineageIdentity currentLineage,
      final StorageLineageIdentity sourceLineage) {
    Objects.requireNonNull(candidateSource, "candidateSource");
    Objects.requireNonNull(currentLineage, "currentLineage");
    Objects.requireNonNull(sourceLineage, "sourceLineage");

    // A replacement must not retain either prior lineage. This check is defensive because the
    // production candidates are random. Bounded retries preserve the rule if collisions occur.
    for (var attempt = 0; attempt < LINEAGE_GENERATION_ATTEMPTS; attempt++) {
      final var generated =
          Objects.requireNonNull(candidateSource.get(), "candidateSource returned null");
      if (!generated.equals(currentLineage) && !generated.equals(sourceLineage)) {
        return generated;
      }
    }
    throw new IllegalStateException(
        "Unable to generate a fresh target lineage because all "
            + LINEAGE_GENERATION_ATTEMPTS
            + " candidates collided with the current or source lineage identity");
  }

  /**
   * Reports whether the authority lock file is the only entry of this storage directory.
   *
   * <p>The deletion of a destructive restart removes every content file, then every unfinished
   * record candidate, and then every authority copy. The deletion keeps the authority lock file.
   * A directory whose only entry is that lock file therefore comes either from a crash in the tail
   * of an earlier restart deletion or from an interrupted storage birth. A storage birth creates
   * the authority lock file before the birth writes the first record, so both events produce this
   * one shape. The shape holds no content file under either event.
   *
   * <p>The method also requires that the lock file existed before the current locked operation, so
   * a lock file of the current operation never proves anything.
   */
  private boolean holdsOnlyTheAuthorityLockFile() throws IOException {
    if (!lockFileExistedBeforeOperation) {
      return false;
    }
    return holdsOnlyTheAuthorityLockFile(directory);
  }

  /**
   * Reports whether the authority lock file is the only entry of the given storage directory.
   *
   * <p>The deletion of a destructive restart removes every content file, then every unfinished
   * record candidate, and then every authority copy. The deletion keeps the authority lock file.
   * A directory of that shape therefore holds no storage content at all.
   */
  public static boolean holdsOnlyTheAuthorityLockFile(final Path storageDirectory)
      throws IOException {
    Objects.requireNonNull(storageDirectory, "storageDirectory");
    if (!Files.isDirectory(storageDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try (var entries = Files.list(storageDirectory)) {
      var names = entries.map(entry -> entry.getFileName().toString()).toList();
      return names.size() == 1 && names.get(0).equals(LOCK_FILE_NAME);
    }
  }

  private <T> T withAuthorityLock(final IOOperation<T> operation) throws IOException {
    return withAuthorityLock(true, operation);
  }

  /**
   * Runs one operation inside the exclusive unit over the authority copies.
   *
   * <p>The exclusive unit uses the authority lock file. A lock acquisition that establishes new
   * authority creates that lock file when the lock file is absent. Every other operation creates
   * no lock file in a directory without any bootstrap authority artifact. A created lock file
   * would otherwise turn such a directory into birth residue, and the reported admission reason of
   * one unchanged image would then differ between two repeated opens.
   *
   * @param establishesAuthority true when the operation may create the first authority artifact
   * @throws StorageAdmissionException with the missing-record reason when the directory holds no
   *     bootstrap authority artifact and the operation establishes no authority
   */
  private <T> T withAuthorityLock(
      final boolean establishesAuthority, final IOOperation<T> operation) throws IOException {
    final var processLock = acquireProcessLock();
    processLock.lock.lock();
    try {
      if (!prepareLockResource(establishesAuthority)) {
        // The directory holds no bootstrap authority artifact, so the operation guards nothing.
        throw missingAuthorityFailure();
      }
      try (var lockChannel =
          FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
          FileLock ignored = lockChannel.lock()) {
        return operation.execute();
      }
    } finally {
      processLock.lock.unlock();
      releaseProcessLock(processLock);
    }
  }

  private ProcessLock acquireProcessLock() {
    synchronized (PROCESS_LOCKS_MONITOR) {
      final var lock = PROCESS_LOCKS.computeIfAbsent(directory, ignored -> new ProcessLock());
      lock.users++;
      return lock;
    }
  }

  private void releaseProcessLock(final ProcessLock lock) {
    synchronized (PROCESS_LOCKS_MONITOR) {
      lock.users--;
      if (lock.users == 0) {
        PROCESS_LOCKS.remove(directory, lock);
      }
    }
  }

  /**
   * Prepares the authority lock file of one exclusive unit.
   *
   * @param establishesAuthority true when the caller may create the first authority artifact
   * @return false when the directory holds no bootstrap authority artifact and the caller
   *     establishes no authority
   */
  private boolean prepareLockResource(final boolean establishesAuthority) throws IOException {
    lockFileExistedBeforeOperation = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS);
    if (!lockFileExistedBeforeOperation) {
      if (!establishesAuthority && !anyPublicationResourceExists()) {
        return false;
      }
      try {
        Files.createFile(lockPath);
      } catch (java.nio.file.FileAlreadyExistsException ignored) {
        // A cooperating process can establish the shared lock file first.
      }
    }
    final var attributes =
        Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw admissionFailure(
          Reason.NON_REGULAR_AUTHORITY_FILE, "Bootstrap publication lock is not a regular file");
    }
    return true;
  }

  private Snapshot verifyExpected(final Snapshot expected) throws IOException {
    Objects.requireNonNull(expected, "expected");
    final var current = readAndConfirmLocked();
    if (!current.equals(expected)) {
      throw new IllegalStateException("Bootstrap authority changed since it was observed");
    }
    return current;
  }

  private void requireLiveBirthWhenPending(final Snapshot expected) {
    // Use the caller's record for identity. A decoded record would reject every creator.
    if (expected.state() == State.BIRTH_IN_PROGRESS && expected != liveBirth) {
      throw new IllegalStateException("An interrupted storage birth cannot continue");
    }
  }

  private Snapshot readAndConfirmLocked() throws IOException {
    // Every publication path keeps the historical order and removes candidate residue first.
    final var selected = selectLocked(true);
    if (selected.snapshot().state() == State.BIRTH_IN_PROGRESS
        || selected.snapshot().equals(locallyConfirmed)) {
      return selected.snapshot();
    }
    return confirmSelectedLocked(selected).snapshot();
  }

  private RecordAt confirmSelectedLocked(final RecordAt selected) throws IOException {
    final var records = readValidRecordsLocked();
    final var target = chooseTarget(records, selected);
    publishTo(selected.snapshot(), target, temporaryPath(target));
    locallyConfirmed = selected.snapshot();
    return new RecordAt(target, selected.snapshot());
  }

  /**
   * Selects the newest unambiguous legal record.
   *
   * @param removeCandidateResidue removes each unfinished record candidate before the selection
   */
  private RecordAt selectLocked(final boolean removeCandidateResidue) throws IOException {
    final var damagedCopies = new ArrayList<StorageAdmissionException>();
    final var records = readValidRecordsLocked(damagedCopies);
    if (records.isEmpty()) {
      throw residueVerdictLocked(damagedCopies);
    }
    if (removeCandidateResidue) {
      removeCandidateResidueLocked();
    }

    final var first = records.get(0).snapshot();
    for (var record : records) {
      final var snapshot = record.snapshot();
      // Unsupported formats fail during decoding. This branch rejects conflicting storage identity.
      if (!snapshot.storageIdentity().equals(first.storageIdentity())) {
        throw admissionFailure(
            Reason.FOREIGN_AUTHORITY_COPY,
            "Bootstrap authority copies name different storage identities");
      }
    }

    final Map<Long, Snapshot> generations = new HashMap<>();
    for (var record : records) {
      final var prior = generations.putIfAbsent(record.snapshot().generation(), record.snapshot());
      if (prior != null && !prior.equals(record.snapshot())) {
        throw admissionFailure(
            Reason.AUTHORITY_AMBIGUOUS, "Bootstrap authority generation is ambiguous");
      }
    }
    final var ordered = generations.values().stream()
        .sorted(Comparator.comparingLong(Snapshot::generation))
        .toList();
    for (var i = 1; i < ordered.size(); i++) {
      final var prior = ordered.get(i - 1);
      final var next = ordered.get(i);
      if (next.generation() == prior.generation() + 1 && !isLegalSuccessor(prior, next)) {
        throw admissionFailure(
            Reason.AUTHORITY_ILLEGAL_TRANSITION,
            "Bootstrap authority contains an illegal state transition");
      }
    }

    final var newest = ordered.get(ordered.size() - 1);
    return records.stream()
        .filter(record -> record.snapshot().equals(newest))
        .min(Comparator.comparingInt(record -> authorityPaths.indexOf(record.path())))
        .orElseThrow();
  }

  private List<RecordAt> readValidRecordsLocked() throws IOException {
    return readValidRecordsLocked(new ArrayList<>());
  }

  /**
   * Reads each readable authority copy.
   *
   * @param damagedCopies collects one failure per copy that failed its integrity check
   */
  private List<RecordAt> readValidRecordsLocked(
      final List<StorageAdmissionException> damagedCopies) throws IOException {
    final var records = new ArrayList<RecordAt>();
    for (var path : authorityPaths) {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      final var attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw admissionFailure(
            Reason.NON_REGULAR_AUTHORITY_FILE,
            "Bootstrap authority is not a regular file: " + path);
      }
      try {
        records.add(new RecordAt(path, readRecord(path, attributes)));
      } catch (DamagedRecordException damaged) {
        // A size or checksum failure proves damage. The slot is not authority and can be reused.
        damagedCopies.add(damaged);
      }
    }
    return records;
  }

  /**
   * Applies the single residue precedence rule for a directory without any readable record.
   *
   * <p>A directory that holds any bootstrap authority artifact reports an interrupted birth. A
   * bootstrap authority artifact is an authority copy, the authority lock file, or an unfinished
   * record candidate. A directory without any such artifact reports a missing record. This method
   * is the only place that decides between those two reasons.
   */
  private StorageAdmissionException residueVerdictLocked(
      final List<StorageAdmissionException> damagedCopies) {
    final StorageAdmissionException verdict;
    if (anyBootstrapArtifactExistsLocked()) {
      verdict =
          admissionFailure(
              Reason.INTERRUPTED_BIRTH,
              "Bootstrap authority residue without a readable record proves an interrupted storage"
                  + " birth");
    } else {
      verdict = missingAuthorityFailure();
    }
    for (var damaged : damagedCopies) {
      verdict.addSuppressed(damaged);
    }
    return verdict;
  }

  /** Reports the single admission failure of a directory without any bootstrap artifact. */
  private StorageAdmissionException missingAuthorityFailure() {
    return admissionFailure(
        Reason.AUTHORITY_MISSING, "Bootstrap authority does not exist in this directory");
  }

  private boolean anyBootstrapArtifactExistsLocked() {
    return lockFileExistedBeforeOperation || anyPublicationResourceExists();
  }

  private StorageAdmissionException admissionFailure(final Reason reason, final String message) {
    return new StorageAdmissionException(reason, directory, message);
  }

  private Snapshot readRecord(final Path path, final BasicFileAttributes attributes)
      throws IOException {
    if (attributes.size() != RECORD_SIZE) {
      throw new DamagedRecordException("Invalid bootstrap authority size: " + attributes.size());
    }

    final byte[] bytes = new byte[RECORD_SIZE];
    try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      final var record = ByteBuffer.wrap(bytes);
      while (record.hasRemaining()) {
        if (channel.read(record) < 0) {
          throw new DamagedRecordException("Bootstrap authority was truncated while it was read");
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) >= 0) {
        throw new DamagedRecordException("Bootstrap authority grew while it was read");
      }
    }

    final var expectedChecksum =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong(CHECKSUM_OFFSET);
    if (XX_HASH_64.hash(bytes, 0, CHECKSUM_OFFSET, XX_HASH_SEED) != expectedChecksum) {
      throw new DamagedRecordException("Bootstrap authority checksum mismatch");
    }

    try {
      return decode(bytes);
    } catch (IllegalArgumentException e) {
      throw new StorageAdmissionException(
          Reason.AUTHORITY_INVALID_CONTENT,
          directory,
          "Bootstrap authority contains an invalid value",
          e);
    }
  }

  private Snapshot decode(final byte[] bytes) throws IOException {
    final var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    final var magic = buffer.getLong();
    if (magic != MAGIC) {
      throw admissionFailure(
          Reason.AUTHORITY_INVALID_CONTENT, "Invalid bootstrap authority magic: " + magic);
    }
    final var encodingVersion = buffer.getInt();
    if (encodingVersion != ENCODING_VERSION) {
      throw admissionFailure(
          Reason.UNSUPPORTED_ENCODING_VERSION,
          "Unsupported bootstrap encoding version: " + encodingVersion);
    }
    final var formatVersion = buffer.getInt();
    if (formatVersion != expectedFormat.version()) {
      // A checksum-valid foreign format came from software this reader does not understand.
      throw admissionFailure(
          Reason.UNSUPPORTED_FEATURE_FORMAT_VERSION,
          "Unsupported feature format version: " + formatVersion);
    }
    final var format = new FeatureFormatIdentity(formatVersion);
    final var generation = buffer.getLong();
    final var state = State.fromCode(buffer.getInt(), directory);
    final var storageLayoutVersion = buffer.getInt();
    if (storageLayoutVersion != SUPPORTED_STORAGE_LAYOUT_VERSION) {
      // The empty value zero comes from an earlier commit of this branch and is also unsupported.
      throw admissionFailure(
          Reason.UNSUPPORTED_STORAGE_LAYOUT_VERSION,
          "Unsupported storage layout version: "
              + storageLayoutVersion
              + ", supported storage layout version: "
              + SUPPORTED_STORAGE_LAYOUT_VERSION);
    }
    final var storageIdentity = new StorageIdentity(readUuid(buffer));
    final var lineageIdentity = new StorageLineageIdentity(readUuid(buffer));
    final var highestIssued = buffer.getLong();
    if (generation <= 0) {
      throw admissionFailure(
          Reason.AUTHORITY_INVALID_CONTENT,
          "Invalid bootstrap authority generation: " + generation);
    }
    if (highestIssued < 0) {
      // The non-negative rule is the only floor rule that a durable record can break.
      throw admissionFailure(
          Reason.INVALID_SEQUENCE_FLOOR,
          "Invalid bootstrap authority highest issued value: " + highestIssued);
    }
    final var floor = new LogicalSequenceFloor(storageIdentity, lineageIdentity, highestIssued);
    return new Snapshot(format, generation, state, floor);
  }

  private void publish(final Snapshot snapshot) throws IOException {
    final var records = readValidRecordsLocked();
    final var selected = selectFromKnownRecords(records);
    final var target = chooseTarget(records, selected);
    publishTo(snapshot, target, temporaryPath(target));
    locallyConfirmed = snapshot;
  }

  private RecordAt selectFromKnownRecords(final List<RecordAt> records) throws IOException {
    final var maxGeneration = records.stream()
        .mapToLong(record -> record.snapshot().generation())
        .max()
        .orElseThrow(() -> new IOException("No authority remains for publication"));
    return records.stream()
        .filter(record -> record.snapshot().generation() == maxGeneration)
        .min(Comparator.comparingInt(record -> authorityPaths.indexOf(record.path())))
        .orElseThrow();
  }

  private Path chooseTarget(final List<RecordAt> records, final RecordAt selected)
      throws IOException {
    for (var path : authorityPaths) {
      if (!path.equals(selected.path()) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return path;
      }
    }

    final var verifiedPaths = records.stream().map(RecordAt::path).toList();
    for (var path : authorityPaths) {
      if (!path.equals(selected.path()) && !verifiedPaths.contains(path)) {
        // Overwriting a damaged slot destroys no verified authority.
        return path;
      }
    }

    final var candidates = records.stream()
        .filter(record -> !record.path().equals(selected.path()))
        .sorted(Comparator.comparingLong(record -> record.snapshot().generation()))
        .toList();
    for (var candidate : candidates) {
      final var survivors = records.stream()
          .filter(record -> !record.path().equals(candidate.path()))
          .count();
      if (survivors >= 2) {
        return candidate.path();
      }
    }
    throw new IOException("Publication would overwrite the last verified authority records");
  }

  private void publishTo(final Snapshot snapshot, final Path target, final Path temporary)
      throws IOException {
    if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Temporary bootstrap publication already exists: " + temporary);
    }
    final var buffer = encode(snapshot);
    var candidateCreated = false;
    try {
      try (var channel =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        candidateCreated = true;
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }
      moveStrategy.move(temporary, target, this);
    } catch (IOException | RuntimeException | Error publicationError) {
      if (candidateCreated) {
        // Unchecked publication failures also belong to this call and must not leave owned residue.
        cleanupFailedPublication(temporary, publicationError);
      }
      throw publicationError;
    }
  }

  private void cleanupFailedPublication(final Path temporary, final Throwable publicationError) {
    // Cleanup is best-effort and can never become the primary publication failure.
    try {
      try {
        publicationWarning.warn(this, directory, temporary, publicationError);
      } catch (Throwable ignored) {
        // A diagnostic failure must not prevent candidate removal.
      }

      try {
        candidateCleanup.remove(temporary);
      } catch (Throwable cleanupError) {
        if (publicationError != cleanupError) {
          publicationError.addSuppressed(cleanupError);
        }
      }
    } catch (Throwable ignored) {
      // Removal and suppression are diagnostic cleanup, so neither can replace the original error.
    }
  }

  private static void warnAboutFailedPublication(
      final Object requester,
      final Path directory,
      final Path temporary,
      final Throwable publicationError) {
    // This seam only enables failure testing. Production retains the existing logger and message.
    LogManager.instance()
        .warn(
            requester,
            "Bootstrap publication failed in directory %s using candidate %s: %s",
            publicationError,
            directory,
            temporary,
            publicationError);
  }

  /** Removes each unfinished record candidate. A readable record already exists at this point. */
  private void removeCandidateResidueLocked() throws IOException {
    for (var path : temporaryPaths) {
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(path);
      }
    }
  }

  /** Reports whether the given file name belongs to the bootstrap authority artifact set. */
  public static boolean isBootstrapArtifactName(final String fileName) {
    Objects.requireNonNull(fileName, "fileName");
    if (fileName.equals(LOCK_FILE_NAME)) {
      return true;
    }
    for (var authorityName : AUTHORITY_FILE_NAMES) {
      if (fileName.equals(authorityName) || fileName.equals(authorityName + ".tmp")) {
        return true;
      }
    }
    return false;
  }

  private boolean anyPublicationResourceExists() {
    return authorityPaths.stream().anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        || temporaryPaths.stream().anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
  }

  private Path temporaryPath(final Path authorityPath) {
    return temporaryPaths.get(authorityPaths.indexOf(authorityPath));
  }

  private static boolean isLegalSuccessor(final Snapshot prior, final Snapshot next) {
    if (!prior.storageIdentity().equals(next.storageIdentity())
        || next.sequenceFloor().highestIssued() < prior.sequenceFloor().highestIssued()) {
      return false;
    }
    if (prior.lineageIdentity().equals(next.lineageIdentity())) {
      if (prior.state() == State.BIRTH_IN_PROGRESS && next.state() == State.RESTORE_IN_PROGRESS) {
        // A restore target publishes the restore-in-progress state directly from the
        // birth-in-progress state, so a restore target never appears as an empty active database.
        return true;
      }
      return prior.state() == next.state()
          || (prior.state() != State.ACTIVE && next.state() == State.ACTIVE);
    }
    return prior.state() == State.ACTIVE && next.state() == State.RESTORE_IN_PROGRESS;
  }

  private static ByteBuffer encode(final Snapshot snapshot) {
    final var buffer = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.BIG_ENDIAN);
    buffer.putLong(MAGIC);
    buffer.putInt(ENCODING_VERSION);
    buffer.putInt(snapshot.format().version());
    buffer.putLong(snapshot.generation());
    buffer.putInt(snapshot.state().code);
    // The field of the former spare value now carries the supported storage layout version.
    buffer.putInt(SUPPORTED_STORAGE_LAYOUT_VERSION);
    writeUuid(buffer, snapshot.storageIdentity().value());
    writeUuid(buffer, snapshot.lineageIdentity().value());
    buffer.putLong(snapshot.sequenceFloor().highestIssued());
    final var bytes = buffer.array();
    buffer.putLong(XX_HASH_64.hash(bytes, 0, CHECKSUM_OFFSET, XX_HASH_SEED));
    buffer.flip();
    return buffer;
  }

  private static long nextGeneration(final long generation) {
    if (generation == Long.MAX_VALUE) {
      throw new IllegalStateException("Bootstrap authority generation is exhausted");
    }
    return generation + 1;
  }

  private static void verifyCurrentIdentity(
      final Snapshot current, final LogicalSequenceFloor requested) {
    if (!current.storageIdentity().equals(requested.storageIdentity())
        || !current.lineageIdentity().equals(requested.lineageIdentity())) {
      throw new IllegalStateException("Logical sequence floor belongs to another identity");
    }
  }

  private static UUID readUuid(final ByteBuffer buffer) {
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  private static void writeUuid(final ByteBuffer buffer, final UUID value) {
    buffer.putLong(value.getMostSignificantBits());
    buffer.putLong(value.getLeastSignificantBits());
  }

  static int processLockCountForTests() {
    synchronized (PROCESS_LOCKS_MONITOR) {
      return PROCESS_LOCKS.size();
    }
  }

  private static final class ProcessLock {

    private final ReentrantLock lock = new ReentrantLock();
    private int users;
  }

  private record RecordAt(Path path, Snapshot snapshot) {
  }

  /** Reports one authority copy that failed its integrity check. Selection tolerates one copy. */
  private final class DamagedRecordException extends StorageAdmissionException {

    private DamagedRecordException(final String message) {
      super(Reason.AUTHORITY_DAMAGED, directory, message);
    }
  }

  @FunctionalInterface
  private interface IOOperation<T> {

    T execute() throws IOException;
  }

  @FunctionalInterface
  interface MoveStrategy {

    void move(Path source, Path target, Object requester) throws IOException;
  }

  @FunctionalInterface
  interface PublicationWarning {

    void warn(Object requester, Path directory, Path candidate, Throwable publicationError);
  }

  @FunctionalInterface
  interface CandidateCleanup {

    void remove(Path candidate) throws IOException;
  }

  /** Removes every file of one storage directory that is no bootstrap authority artifact. */
  @FunctionalInterface
  public interface ContentDeletion {

    void deleteEveryContentFile(Path storageDirectory) throws IOException;
  }

  /** Unlinks one authority copy of one storage directory. */
  @FunctionalInterface
  interface AuthorityCopyDeletion {

    void deleteAuthorityCopy(Path authorityCopy) throws IOException;
  }

  public enum State {
    BIRTH_IN_PROGRESS(1), ACTIVE(2), RESTORE_IN_PROGRESS(3);

    private final int code;

    State(final int code) {
      this.code = code;
    }

    private static State fromCode(final int code, final Path storageDirectory)
        throws StorageAdmissionException {
      for (var state : values()) {
        if (state.code == code) {
          return state;
        }
      }
      // A checksum-valid unknown state came from software this reader does not understand.
      throw new StorageAdmissionException(
          Reason.UNSUPPORTED_LIFECYCLE_STATE,
          storageDirectory,
          "Unsupported bootstrap state: " + code);
    }
  }

  public record Snapshot(
      FeatureFormatIdentity format,
      long generation,
      State state,
      LogicalSequenceFloor sequenceFloor) {

    public Snapshot {
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(sequenceFloor, "sequenceFloor");
      if (generation <= 0) {
        throw new IllegalArgumentException("Bootstrap generation must be positive");
      }
    }

    public StorageIdentity storageIdentity() {
      return sequenceFloor.storageIdentity();
    }

    public StorageLineageIdentity lineageIdentity() {
      return sequenceFloor.lineageIdentity();
    }

    private Snapshot withFloor(final LogicalSequenceFloor floor, final long nextGeneration) {
      return new Snapshot(format, nextGeneration, state, floor);
    }
  }
}
