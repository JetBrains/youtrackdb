package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Unit tests for {@link RecordOperationMetadata}. The metadata wraps a per-atomic-operation
 * set of RIDs that were modified by a paginated collection so the WAL machinery can replay
 * those changes. Tests pin the metadata key constant, the dedup-set semantics of
 * {@link RecordOperationMetadata#addRid(RID)}, and the contract that {@code getValue()}
 * returns the live mutable set (mutations after capture are visible — by design, since the
 * metadata accumulates changes during the atomic operation lifetime).
 */
public class RecordOperationMetadataTest {

  /**
   * The metadata key constant must match the published value used by readers across the
   * codebase. Hardcoding the literal here detects an accidental rename (which would make all
   * existing readers silently miss the metadata).
   */
  @Test
  public void testMetadataKeyConstant() {
    assertThat(RecordOperationMetadata.RID_METADATA_KEY)
        .isEqualTo("collection.record.rid");
  }

  /**
   * The {@code getKey()} method returns the published constant.
   */
  @Test
  public void testGetKeyReturnsConstant() {
    var metadata = new RecordOperationMetadata();
    assertThat(metadata.getKey()).isEqualTo(RecordOperationMetadata.RID_METADATA_KEY);
  }

  /**
   * A freshly constructed metadata exposes an empty (but non-null) RID set.
   */
  @Test
  public void testFreshMetadataHasEmptyValue() {
    var metadata = new RecordOperationMetadata();
    assertThat(metadata.getValue()).isNotNull();
    assertThat(metadata.getValue()).isEmpty();
  }

  /**
   * A single {@link RecordOperationMetadata#addRid(RID)} call adds the rid to the value set.
   */
  @Test
  public void testAddSingleRid() {
    var metadata = new RecordOperationMetadata();
    var rid = new RecordId(7, 42L);

    metadata.addRid(rid);

    assertThat(metadata.getValue()).containsExactly(rid);
  }

  /**
   * Adding multiple distinct RIDs accumulates them in the value set.
   */
  @Test
  public void testAddMultipleDistinctRids() {
    var metadata = new RecordOperationMetadata();
    var r1 = new RecordId(7, 1L);
    var r2 = new RecordId(7, 2L);
    var r3 = new RecordId(8, 9L);

    metadata.addRid(r1);
    metadata.addRid(r2);
    metadata.addRid(r3);

    assertThat(metadata.getValue()).containsExactlyInAnyOrder(r1, r2, r3);
  }

  /**
   * Re-adding the same RID is idempotent because the underlying set deduplicates by
   * {@code RecordId.equals}. This is critical for atomic-operation accumulation: a record
   * touched multiple times in the same tx must be replayed once.
   */
  @Test
  public void testAddDuplicateRidIsDeduplicated() {
    var metadata = new RecordOperationMetadata();
    var rid = new RecordId(7, 42L);
    var sameRid = new RecordId(7, 42L);

    metadata.addRid(rid);
    metadata.addRid(rid);
    metadata.addRid(sameRid);

    assertThat(metadata.getValue()).hasSize(1);
    assertThat(metadata.getValue()).containsExactly(rid);
  }

  /**
   * The set returned by {@code getValue()} is the live mutable set; subsequent
   * {@code addRid} calls are visible to a previously obtained reference. The current
   * design relies on this for incremental accumulation during the atomic operation
   * lifetime.
   */
  @Test
  public void testGetValueReturnsLiveSet() {
    var metadata = new RecordOperationMetadata();
    var initialView = metadata.getValue();
    assertThat(initialView).isEmpty();

    metadata.addRid(new RecordId(1, 1L));
    metadata.addRid(new RecordId(1, 2L));

    // Same reference must reflect new additions (live view, not snapshot).
    assertThat(initialView).hasSize(2);
    assertThat(initialView).isSameAs(metadata.getValue());
  }
}
