package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

/** Owns serialization and versioned persistence rules for index build state records. */
public final class IndexBuildStateStore {

  private static final String FORMAT_VERSION = "formatVersion";
  private static final String DESCRIPTOR_IDENTITY = "descriptorIdentity";
  private static final String LIFECYCLE = "lifecycle";
  private static final String BUILD_INCARNATION = "buildIncarnation";
  private static final String OWNER_EPOCH = "ownerEpoch";
  private static final String COMPLETION_CUT = "completionCut";
  private static final String COMPLETED_UNITS = "completedUnits";
  private static final String SUSPENDED = "suspended";
  private static final String FAILURE = "failure";
  private static final String FAILURE_MESSAGE = "failureMessage";

  private final Backend backend;

  public IndexBuildStateStore(Backend backend) {
    this.backend = Objects.requireNonNull(backend, "backend");
  }

  public IndexLifecycleSnapshot createInitial(RID descriptorIdentity) {
    return create(IndexBuildState.initial(descriptorIdentity));
  }

  public IndexLifecycleSnapshot create(IndexBuildState state) {
    var record = backend.create(state.descriptorIdentity(), serialize(state));
    return snapshot(record);
  }

  public Optional<IndexLifecycleSnapshot> read(RID descriptorIdentity) {
    return backend.read(descriptorIdentity).map(this::snapshot);
  }

  /** Recovers an existing record or creates a direct missing-record quarantine publication. */
  public IndexLifecycleSnapshot recover(RID descriptorIdentity) {
    var existing = read(descriptorIdentity);
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    return create(IndexBuildState.quarantineMissing(descriptorIdentity));
  }

  /**
   * Updates one record after checking its durable version and then its lifecycle transition.
   */
  public IndexLifecycleSnapshot update(
      RID descriptorIdentity, long expectedVersion, IndexBuildState replacement) {
    var current = read(descriptorIdentity)
        .orElseThrow(() -> new IllegalStateException("Index build state record is missing"));
    if (current.recordVersion() != expectedVersion) {
      throw new ConcurrentModificationException(
          "Index build state version changed from " + expectedVersion + " to "
              + current.recordVersion());
    }

    verifyReplacementDescriptor(current.buildState(), replacement, descriptorIdentity);
    var currentLifecycle = current.lifecycle();
    var replacementLifecycle = replacement.lifecycle();
    if (currentLifecycle != replacementLifecycle
        && !IndexBuildState.isLegalTransition(currentLifecycle, replacementLifecycle)) {
      throw new IllegalArgumentException(
          "Illegal index lifecycle transition from " + currentLifecycle + " to "
              + replacementLifecycle);
    }
    if (currentLifecycle == IndexLifecycle.INVALID
        && replacementLifecycle == IndexLifecycle.MAINTAINED) {
      if (current.buildState().buildIncarnation().equals(replacement.buildIncarnation())) {
        throw new IllegalArgumentException("A retry must start a new build incarnation");
      }
      if (replacement.ownerEpoch() != null) {
        throw new IllegalArgumentException("A retry must clear the owner epoch");
      }
    }

    return snapshot(backend.update(descriptorIdentity, expectedVersion, serialize(replacement)));
  }

  public void delete(RID descriptorIdentity, long expectedVersion) {
    backend.delete(descriptorIdentity, expectedVersion);
  }

  Map<String, Object> serialize(IndexBuildState state) {
    var record = new LinkedHashMap<String, Object>();
    record.put(FORMAT_VERSION, state.formatVersion());
    record.put(DESCRIPTOR_IDENTITY, state.descriptorIdentity().toString());
    record.put(LIFECYCLE, state.lifecycle().name());
    record.put(BUILD_INCARNATION, state.buildIncarnation().toString());
    record.put(OWNER_EPOCH, state.ownerEpoch());
    record.put(COMPLETION_CUT, state.completionCut());
    record.put(COMPLETED_UNITS, state.completedUnits());
    record.put(SUSPENDED, state.suspended());
    record.put(FAILURE, state.failure().name());
    record.put(FAILURE_MESSAGE, state.failureMessage());
    return record;
  }

  IndexBuildState deserialize(Map<String, Object> record) {
    return new IndexBuildState(
        integer(record, FORMAT_VERSION),
        RecordIdInternal.fromString(string(record, DESCRIPTOR_IDENTITY), false),
        IndexLifecycle.valueOf(string(record, LIFECYCLE)),
        UUID.fromString(string(record, BUILD_INCARNATION)),
        nullableLong(record, OWNER_EPOCH),
        nullableLong(record, COMPLETION_CUT),
        longValue(record, COMPLETED_UNITS),
        booleanValue(record, SUSPENDED),
        IndexBuildFailure.valueOf(string(record, FAILURE)),
        nullableString(record, FAILURE_MESSAGE));
  }

  private IndexLifecycleSnapshot snapshot(VersionedRecord record) {
    return new IndexLifecycleSnapshot(deserialize(record.value()), record.version());
  }

  private static void verifyReplacementDescriptor(
      IndexBuildState current, IndexBuildState replacement, RID descriptorIdentity) {
    if (!current.descriptorIdentity().equals(descriptorIdentity)
        || !replacement.descriptorIdentity().equals(descriptorIdentity)) {
      throw new IllegalArgumentException("Index build state belongs to another descriptor");
    }
  }

  private static String string(Map<String, Object> record, String field) {
    return Objects.requireNonNull((String) record.get(field), field);
  }

  @Nullable private static String nullableString(Map<String, Object> record, String field) {
    return (String) record.get(field);
  }

  private static int integer(Map<String, Object> record, String field) {
    return ((Number) Objects.requireNonNull(record.get(field), field)).intValue();
  }

  private static long longValue(Map<String, Object> record, String field) {
    return ((Number) Objects.requireNonNull(record.get(field), field)).longValue();
  }

  @Nullable private static Long nullableLong(Map<String, Object> record, String field) {
    var value = (Number) record.get(field);
    return value == null ? null : value.longValue();
  }

  private static boolean booleanValue(Map<String, Object> record, String field) {
    return (Boolean) Objects.requireNonNull(record.get(field), field);
  }

  /** Durable record access supplied by the storage transaction integration. */
  public interface Backend {

    Optional<VersionedRecord> read(RID descriptorIdentity);

    VersionedRecord create(RID descriptorIdentity, Map<String, Object> value);

    VersionedRecord update(
        RID descriptorIdentity, long expectedVersion, Map<String, Object> value);

    void delete(RID descriptorIdentity, long expectedVersion);
  }

  /** One serialized durable record and its storage record version. */
  public record VersionedRecord(Map<String, Object> value, long version) {

    public VersionedRecord {
      value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
      if (version < 0) {
        throw new IllegalArgumentException("The record version cannot be negative");
      }
    }
  }
}
