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
import java.util.function.Function;
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

  /** Creates an unlinked initial record after confirming that the descriptor has no link. */
  public CreatedLifecycleRecord createInitial(
      RID descriptorIdentity, @Nullable RID existingLifecycleIdentity) {
    return create(IndexBuildState.initial(descriptorIdentity), existingLifecycleIdentity);
  }

  /** Creates an initial record through the creator of an enclosing atomic unit. */
  public CreatedLifecycleRecord createInitial(
      RID descriptorIdentity,
      @Nullable RID existingLifecycleIdentity,
      Function<Map<String, Object>, CreatedRecord> creator) {
    return create(
        IndexBuildState.initial(descriptorIdentity), existingLifecycleIdentity, creator);
  }

  /** Creates an unlinked record after confirming that the descriptor has no link. */
  public CreatedLifecycleRecord create(
      IndexBuildState state, @Nullable RID existingLifecycleIdentity) {
    return create(state, existingLifecycleIdentity, backend::create);
  }

  private CreatedLifecycleRecord create(
      IndexBuildState state,
      @Nullable RID existingLifecycleIdentity,
      Function<Map<String, Object>, CreatedRecord> creator) {
    if (existingLifecycleIdentity != null) {
      throw new IllegalStateException("Index descriptor already has a lifecycle record link");
    }
    var created = creator.apply(serialize(state));
    return new CreatedLifecycleRecord(created.identity(), snapshot(created.record()));
  }

  /** Reads the single lifecycle record named by the descriptor link. */
  public IndexLifecycleSnapshot read(
      RID descriptorIdentity, @Nullable RID lifecycleIdentity) {
    if (lifecycleIdentity == null) {
      throw new IllegalStateException("Index descriptor has no lifecycle record link");
    }
    var record =
        backend
            .read(lifecycleIdentity)
            .orElseThrow(() -> new MissingLinkedRecordException(lifecycleIdentity));
    var snapshot = snapshot(record);
    verifyDescriptor(snapshot.buildState(), descriptorIdentity);
    return snapshot;
  }

  /** Publishes one record after comparing the expected snapshot by value except owner epoch. */
  public IndexLifecycleSnapshot publish(
      RID descriptorIdentity,
      RID lifecycleIdentity,
      IndexLifecycleSnapshot expected,
      IndexBuildState replacement) {
    verifyReplacementDescriptor(expected.buildState(), replacement, descriptorIdentity);
    backend.checkStandaloneUpdateAllowed();
    var current = read(descriptorIdentity, lifecycleIdentity);
    if (current.recordVersion() != expected.recordVersion()
        || !current
            .buildState()
            .withoutProcessOwnership()
            .equals(expected.buildState().withoutProcessOwnership())) {
      throw new ConcurrentModificationException(
          "Index build state changed from expected revision " + expected.recordVersion()
              + " to durable revision " + current.recordVersion());
    }

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

    return snapshot(
        backend.update(lifecycleIdentity, current.recordVersion(), serialize(replacement)));
  }

  public void delete(RID lifecycleIdentity, long expectedVersion) {
    backend.delete(lifecycleIdentity, expectedVersion);
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
    verifyDescriptor(current, descriptorIdentity);
    verifyDescriptor(replacement, descriptorIdentity);
  }

  private static void verifyDescriptor(IndexBuildState state, RID descriptorIdentity) {
    if (!state.descriptorIdentity().equals(descriptorIdentity)) {
      throw new IllegalArgumentException("Index build state belongs to another descriptor");
    }
  }

  private static String string(Map<String, Object> record, String field) {
    return requiredValue(record, field, String.class);
  }

  @Nullable private static String nullableString(Map<String, Object> record, String field) {
    return nullableValue(record, field, String.class);
  }

  private static int integer(Map<String, Object> record, String field) {
    return requiredValue(record, field, Integer.class);
  }

  private static long longValue(Map<String, Object> record, String field) {
    return requiredValue(record, field, Long.class);
  }

  @Nullable private static Long nullableLong(Map<String, Object> record, String field) {
    return nullableValue(record, field, Long.class);
  }

  private static boolean booleanValue(Map<String, Object> record, String field) {
    return requiredValue(record, field, Boolean.class);
  }

  private static <T> T requiredValue(
      Map<String, Object> record, String field, Class<T> expectedType) {
    return Objects.requireNonNull(nullableValue(record, field, expectedType), field);
  }

  @Nullable private static <T> T nullableValue(
      Map<String, Object> record, String field, Class<T> expectedType) {
    var value = record.get(field);
    if (value != null && !expectedType.isInstance(value)) {
      throw new IllegalArgumentException(
          "Index build state field " + field + " must have type " + expectedType.getSimpleName());
    }
    return expectedType.cast(value);
  }

  /** Signals that the descriptor link names no durable lifecycle record. */
  public static final class MissingLinkedRecordException extends IllegalStateException {

    public MissingLinkedRecordException(RID lifecycleIdentity) {
      super("Linked index build state record is missing");
      Objects.requireNonNull(lifecycleIdentity, "lifecycleIdentity");
    }
  }

  /** Durable record access supplied by the storage transaction integration. */
  public interface Backend {

    Optional<VersionedRecord> read(RID recordIdentity);

    CreatedRecord create(Map<String, Object> value);

    /** Rejects a protected caller before publication performs its internal record read. */
    default void checkStandaloneUpdateAllowed() {
    }

    VersionedRecord update(RID recordIdentity, long expectedVersion, Map<String, Object> value);

    void delete(RID recordIdentity, long expectedVersion);
  }

  /** The durable address and committed value of a newly created lifecycle record. */
  public record CreatedRecord(RID identity, VersionedRecord record) {

    public CreatedRecord {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(record, "record");
    }
  }

  /** The durable address and immutable snapshot of a newly created lifecycle record. */
  public record CreatedLifecycleRecord(RID identity, IndexLifecycleSnapshot snapshot) {

    public CreatedLifecycleRecord {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(snapshot, "snapshot");
    }
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
