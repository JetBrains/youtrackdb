package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
  static final List<String> AUTHORITY_FILE_NAMES =
      List.of(
          "storage-bootstrap-0.bsm", "storage-bootstrap-1.bsm", "storage-bootstrap-2.bsm");

  private static final long MAGIC = 0x5954444242534D31L;
  private static final int ENCODING_VERSION = 1;
  private static final int RECORD_SIZE = 80;
  private static final int CHECKSUM_OFFSET = RECORD_SIZE - Long.BYTES;
  private static final long XX_HASH_SEED = 0x648B7A2195D3L;
  private static final int LINEAGE_GENERATION_ATTEMPTS = 3;
  private static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();
  private static final Object PROCESS_LOCKS_MONITOR = new Object();
  private static final Map<Path, ProcessLock> PROCESS_LOCKS = new HashMap<>();

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

  public StorageBootstrapMetadata(
      final Path storageDirectory, final FeatureFormatIdentity expectedFormat) throws IOException {
    this(storageDirectory, expectedFormat, FileUtils::durableAtomicMove);
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
    return withAuthorityLock(this::readAndConfirmLocked);
  }

  /** Reads authority for Open and rejects an interrupted birth before WAL processing can start. */
  public Snapshot readActiveRequired() throws IOException {
    return withAuthorityLock(
        () -> {
          final var selected = selectLocked();
          if (selected.snapshot().state() == State.BIRTH_IN_PROGRESS) {
            throw new IOException("Interrupted storage birth must be removed before Open");
          }
          return confirmSelectedLocked(selected).snapshot();
        });
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
   * Starts replacement under a fresh target lineage while retaining the target high-water.
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
          if (current.state() != State.ACTIVE) {
            throw new IllegalStateException("Lineage replacement requires active storage");
          }
          if (!current.format().equals(adoption.format())) {
            throw new IllegalStateException("Cannot adopt a floor from another feature format");
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

  private <T> T withAuthorityLock(final IOOperation<T> operation) throws IOException {
    final var processLock = acquireProcessLock();
    processLock.lock.lock();
    try {
      prepareLockResource();
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

  private void prepareLockResource() throws IOException {
    if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createFile(lockPath);
      } catch (java.nio.file.FileAlreadyExistsException ignored) {
        // A cooperating process can establish the shared lock file first.
      }
    }
    final var attributes =
        Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("Bootstrap publication lock is not a regular file");
    }
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
    final var selected = selectLocked();
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

  private RecordAt selectLocked() throws IOException {
    rejectTemporaryResidue();
    final var records = readValidRecordsLocked();
    if (records.isEmpty()) {
      throw new IOException("Bootstrap authority does not exist or has no valid record");
    }

    final var first = records.get(0).snapshot();
    for (var record : records) {
      final var snapshot = record.snapshot();
      // Unsupported formats fail during decoding. This branch rejects conflicting storage identity.
      if (!snapshot.storageIdentity().equals(first.storageIdentity())) {
        throw new IOException("Bootstrap authority records are ambiguous");
      }
    }

    final Map<Long, Snapshot> generations = new HashMap<>();
    for (var record : records) {
      final var prior = generations.putIfAbsent(record.snapshot().generation(), record.snapshot());
      if (prior != null && !prior.equals(record.snapshot())) {
        throw new IOException("Bootstrap authority generation is ambiguous");
      }
    }
    final var ordered = generations.values().stream()
        .sorted(Comparator.comparingLong(Snapshot::generation))
        .toList();
    for (var i = 1; i < ordered.size(); i++) {
      final var prior = ordered.get(i - 1);
      final var next = ordered.get(i);
      if (next.generation() == prior.generation() + 1 && !isLegalSuccessor(prior, next)) {
        throw new IOException("Bootstrap authority contains an illegal state transition");
      }
    }

    final var newest = ordered.get(ordered.size() - 1);
    return records.stream()
        .filter(record -> record.snapshot().equals(newest))
        .min(Comparator.comparingInt(record -> authorityPaths.indexOf(record.path())))
        .orElseThrow();
  }

  private List<RecordAt> readValidRecordsLocked() throws IOException {
    final var records = new ArrayList<RecordAt>();
    for (var path : authorityPaths) {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      final var attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw new IOException("Bootstrap authority is not a regular file: " + path);
      }
      try {
        records.add(new RecordAt(path, readRecord(path, attributes)));
      } catch (DamagedRecordException ignored) {
        // A size or checksum failure proves damage. The slot is not authority and can be reused.
      }
    }
    return records;
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
      throw new IOException("Bootstrap authority contains an invalid value", e);
    }
  }

  private Snapshot decode(final byte[] bytes) throws IOException {
    final var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    final var magic = buffer.getLong();
    if (magic != MAGIC) {
      throw new IOException("Invalid bootstrap authority magic: " + magic);
    }
    final var encodingVersion = buffer.getInt();
    if (encodingVersion != ENCODING_VERSION) {
      throw new IOException("Unsupported bootstrap encoding version: " + encodingVersion);
    }
    final var formatVersion = buffer.getInt();
    if (formatVersion != expectedFormat.version()) {
      // A checksum-valid foreign format came from software this reader does not understand.
      throw new UnsupportedRecordException(
          "Unsupported feature format version: " + formatVersion);
    }
    final var format = new FeatureFormatIdentity(formatVersion);
    final var generation = buffer.getLong();
    final var state = State.fromCode(buffer.getInt());
    final var reserved = buffer.getInt();
    if (reserved != 0) {
      throw new IOException("Invalid bootstrap authority reserved field: " + reserved);
    }
    final var storageIdentity = new StorageIdentity(readUuid(buffer));
    final var lineageIdentity = new StorageLineageIdentity(readUuid(buffer));
    final var highestIssued = buffer.getLong();
    if (generation <= 0) {
      throw new IOException("Invalid bootstrap authority generation: " + generation);
    }
    if (highestIssued < 0) {
      throw new IOException("Invalid bootstrap authority highest issued value: " + highestIssued);
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

  private void rejectTemporaryResidue() throws IOException {
    for (var path : temporaryPaths) {
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Interrupted bootstrap publication left a temporary file");
      }
    }
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
    buffer.putInt(0);
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

  private static final class DamagedRecordException extends IOException {

    private DamagedRecordException(final String message) {
      super(message);
    }
  }

  private static final class UnsupportedRecordException extends IOException {

    private UnsupportedRecordException(final String message) {
      super(message);
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

  public enum State {
    BIRTH_IN_PROGRESS(1), ACTIVE(2), RESTORE_IN_PROGRESS(3);

    private final int code;

    State(final int code) {
      this.code = code;
    }

    private static State fromCode(final int code) throws IOException {
      for (var state : values()) {
        if (state.code == code) {
          return state;
        }
      }
      // A checksum-valid unknown state came from software this reader does not understand.
      throw new UnsupportedRecordException("Unsupported bootstrap state: " + code);
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
